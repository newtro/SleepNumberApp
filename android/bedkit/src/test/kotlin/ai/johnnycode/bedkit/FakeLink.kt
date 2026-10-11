package ai.johnnycode.bedkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * In-memory GATT link (port of BedKit/Tests/FakeLink.swift): records writes, answers them with
 * scripted notifications and serves scripted reads. Notifications are delivered asynchronously,
 * like a real BLE stack would.
 */
class FakeLink(private val scope: CoroutineScope, private val now: () -> Long = { 0L }) : BedLink {
    class Write(val data: ByteArray, val characteristic: BedCharacteristic, val at: Long)

    val writes = mutableListOf<Write>()

    /** Returns the notifications to emit in reply to a write. */
    var responder: ((ByteArray, BedCharacteristic) -> List<ByteArray>)? = null
    var onNotify: ((ByteArray) -> Unit)? = null

    /** Values returned by successive reads, per characteristic. */
    val readQueue = mutableMapOf<BedCharacteristic, ArrayDeque<ByteArray>>()
    var readError: BedError? = null
    var readCount = 0
        private set
    val subscribed = mutableListOf<BedCharacteristic>()

    override suspend fun write(frame: ByteArray, characteristic: BedCharacteristic) {
        writes += Write(frame, characteristic, now())
        val replies = responder?.invoke(frame, characteristic) ?: emptyList()
        if (replies.isEmpty()) return
        scope.launch {
            yield()
            replies.forEach { onNotify?.invoke(it) }
        }
    }

    override suspend fun read(characteristic: BedCharacteristic): ByteArray {
        readCount++
        readError?.let { throw it }
        val queue = readQueue[characteristic]
        if (queue.isNullOrEmpty()) throw BedError.Transport("no scripted read for $characteristic")
        return queue.removeFirst()
    }

    override suspend fun enableNotifications(characteristic: BedCharacteristic) {
        subscribed += characteristic
    }

    fun queueReads(characteristic: BedCharacteristic, values: List<ByteArray>) {
        readQueue.getOrPut(characteristic) { ArrayDeque() }.addAll(values)
    }

    companion object {
        /** Splits data into 20-byte notifications, the size an MTU-23 link delivers. */
        fun chunks(data: ByteArray, size: Int = 20): List<ByteArray> =
            (data.indices step size).map { data.copyOfRange(it, minOf(it + size, data.size)) }
    }
}
