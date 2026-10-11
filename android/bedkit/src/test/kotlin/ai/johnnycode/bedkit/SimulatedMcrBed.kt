package ai.johnnycode.bedkit

import ai.johnnycode.bedkit.mcr.MCRFrame

/**
 * A tiny MCR bed for driver tests: answers bind, foundation status (positions step 10 per read toward
 * their targets), Sleep Expert set-position/stop keys, micro-adjust, pinch, pump status/set/idle.
 * Wire side 0 = right, 1 = left; the foot is shared.
 */
class SimulatedMcrBed(private val link: FakeLink) {
    private val bed = 0x32D3
    private val client = 0xA1B2

    val head = mutableMapOf(0 to 10, 1 to 10)
    var foot = 0
    val headTarget = mutableMapOf<Int, Int>()
    var footTarget: Int? = null
    val sleepNumber = mutableMapOf(0 to 40, 1 to 40)
    private var pendingFirmness: Pair<Int, Int>? = null
    private var busyReads = 0

    /** Pump reads that report busy after a set. */
    var pumpBusyReadsPerSet = 2

    /** Pump already busy (e.g. the other side adjusting from the remote) for this many reads. */
    var externalBusyReads = 0

    /** Stop key writes to acknowledge but ignore (a lost stop). */
    var ignoreStops = 0

    /** Sleep Expert keys answered with a NACK. */
    val nackKeys = mutableSetOf<String>()

    /** Every Sleep Expert key write received, in order ("MFUL30_0", "MFHR110", ...). */
    val keyWrites = mutableListOf<String>()

    val moving: Boolean get() = headTarget.isNotEmpty() || footTarget != null

    init {
        link.responder = { data, _ -> listOf(respond(MCRFrame.decode(data))) }
    }

    private fun reply(request: MCRFrame, payload: List<Int> = emptyList(), side: Int = request.side) = MCRFrame(
        command = request.command - 1, source = bed, destination = client, status = request.command - 1,
        sourceEcho = bed, function = 0x80 or request.functionCode, side = side, payload = payload,
    ).encoded()

    private fun step(current: Int, target: Int) = when {
        target > current -> minOf(target, current + 10)
        else -> maxOf(target, current - 10)
    }

    private fun foundationStatus(): List<Int> {
        for ((side, target) in headTarget.toMap()) {
            head[side] = step(head.getValue(side), target)
            if (head[side] == target) headTarget.remove(side)
        }
        footTarget?.let { target ->
            foot = step(foot, target)
            if (foot == target) footTarget = null
        }
        val flags = 0x40 or (if (moving) 0x01 else 0)
        return listOf(flags, head.getValue(0), head.getValue(1), foot, foot) + List(10) { 0 }
    }

    private fun respond(frame: MCRFrame): ByteArray {
        if (frame.functionCode == 0) return hexBytes("16 16 01 32 d3 00 00 01 32 d3 80 0a 01 02 03 04 05 06 07 08 a1 b2 29 44")
        return when (frame.command) {
            0x52 -> {
                val text = String(frame.payload.toByteArray(), Charsets.UTF_8)
                keyWrites += text
                val key = text.take(4)
                val wire = if (key[3] == 'R') 0 else 1
                val value = text.drop(4).substringBefore("_").toIntOrNull()
                if (key in nackKeys) return reply(frame, side = 0x0F)
                when (key.take(3)) {
                    "MFU" -> headTarget[wire] = value!!
                    "MFF" -> footTarget = value!!
                    "MFH" -> if (ignoreStops > 0) ignoreStops-- else { headTarget.clear(); footTarget = null }
                }
                reply(frame)
            }
            0x42 -> when (frame.functionCode) {
                0x12 -> reply(frame, foundationStatus())
                0x11 -> {
                    val p = frame.payload
                    if (p[0] != 0xFF) headTarget[frame.side] = p[0]
                    if (p[2] != 0xFF) footTarget = p[2]
                    reply(frame)
                }
                0x28 -> reply(frame, List(5) { 0 })
                0x25 -> reply(frame, listOf(1, 100, 100, 0x15, 0x14, 0, 0))
                0x14 -> reply(frame, listOf(0, 0, 0))
                else -> reply(frame)
            }
            0x72 -> reply(frame, listOf(0x01, 0x41, 0x51))
            0x02 -> when (frame.functionCode) {
                0x12 -> {
                    var busy = false
                    if (externalBusyReads > 0) {
                        externalBusyReads--
                        busy = true
                    }
                    pendingFirmness?.let { (side, value) ->
                        if (busyReads > 0) {
                            busyReads--
                            busy = true
                        } else {
                            sleepNumber[side] = value
                            pendingFirmness = null
                        }
                    }
                    reply(frame, listOf(if (busy) 1 else 0, sleepNumber.getValue(0), sleepNumber.getValue(1)), side = 0)
                }
                0x11 -> {
                    pendingFirmness = frame.side to frame.payload[1]
                    busyReads = pumpBusyReadsPerSet
                    reply(frame)
                }
                0x02 -> {
                    pendingFirmness = null
                    externalBusyReads = 0
                    reply(frame)
                }
                else -> reply(frame)
            }
            else -> reply(frame)
        }
    }

    /** Decoded writes, in order. */
    fun frames(): List<MCRFrame> = link.writes.map { MCRFrame.decode(it.data) }
}
