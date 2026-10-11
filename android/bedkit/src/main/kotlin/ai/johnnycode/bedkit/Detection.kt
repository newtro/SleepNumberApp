package ai.johnnycode.bedkit

// Port of BedKit/Sources/Common/Detection.swift.

/** Advertisement fields the scanner sees, independent of Android scan types. */
data class AdvertisementInfo(
    val name: String?,
    val serviceUuids: List<String> = emptyList(),
    /** Raw manufacturer data as iOS reports it: 2-byte little-endian company ID, then payload. */
    val manufacturerData: ByteArray? = null,
    /**
     * Android only: the device's Bluetooth address. iOS hides it; Android exposes it, so an MCR bed
     * that advertises no name is still recognised by the Select Comfort OUI.
     */
    val address: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is AdvertisementInfo && name == other.name &&
        serviceUuids == other.serviceUuids && address == other.address &&
        bytesEqual(manufacturerData, other.manufacturerData)

    override fun hashCode(): Int = (name.hashCode() * 31 + serviceUuids.hashCode()) * 31 + address.hashCode()

    companion object {
        /** Builds iOS-style manufacturer data (LE company ID + payload) from Android's per-company map. */
        fun manufacturerData(companyId: Int, payload: ByteArray): ByteArray =
            byteArrayOf((companyId and 0xFF).toByte(), ((companyId shr 8) and 0xFF).toByte()) + payload
    }
}

/** Why a device was offered as a possible Sleep Number bed. */
enum class CandidateReason(val label: String) {
    MCR_SERVICE("MCR service"),
    FUZION_SERVICE("Fuzion service"),
    SMART_BED_NAME("\"Smart bed\" name"),
    SELECT_COMFORT_MAC("Select Comfort MAC name"),
    MAC_NAME("MAC-style name"),
    MANUFACTURER_DATA("Sleep Number manufacturer data"),
}

data class CandidateClassification(
    val reasons: List<CandidateReason>,
    /** Best guess before connecting. The real decision is made from discovered services. */
    val likelyProtocol: BedProtocol?,
) {
    val isCandidate: Boolean get() = reasons.isNotEmpty()

    /** Higher is a stronger match; used to sort the scan list. */
    val score: Int
        get() = reasons.sumOf {
            when (it) {
                CandidateReason.MCR_SERVICE, CandidateReason.FUZION_SERVICE -> 100
                CandidateReason.SELECT_COMFORT_MAC -> 60
                CandidateReason.MANUFACTURER_DATA -> 40
                CandidateReason.SMART_BED_NAME -> 40
                CandidateReason.MAC_NAME -> 10
            }.toInt()
        }
}

object BedDetector {
    /** Company identifier the user reported for Sleep Number advertisements ("SN"). */
    const val MANUFACTURER_ID = 0x534E

    /** IEEE OUI registered to Select Comfort (Sleep Number); MCR beds advertise their MAC as name. */
    val selectComfortOuis = setOf("64:DB:A0")

    fun isMacLike(name: String): Boolean {
        val parts = name.split(":")
        val dashParts = name.split("-")
        val groups = if (parts.size == 6) parts else if (dashParts.size == 6) dashParts else return false
        return groups.all { it.length == 2 && it.toIntOrNull(16) != null }
    }

    /** The MCR bed address MCR-era beds derive from the last two MAC octets ("…:32:d3" -> 0x32D3). */
    fun mcrAddress(fromMacName: String): Int? {
        if (!isMacLike(fromMacName)) return null
        val groups = fromMacName.replace("-", ":").split(":")
        val high = groups[4].toIntOrNull(16) ?: return null
        val low = groups[5].toIntOrNull(16) ?: return null
        return (high shl 8) or low
    }

    private fun isSelectComfort(mac: String): Boolean =
        selectComfortOuis.contains(mac.replace("-", ":").uppercase().take(8))

    fun classify(info: AdvertisementInfo): CandidateClassification {
        val reasons = mutableListOf<CandidateReason>()
        var likely: BedProtocol? = null
        val services = info.serviceUuids.map { it.uppercase() }.toSet()
        if (BedProtocol.MCR_SERVICE_UUID in services) {
            reasons += CandidateReason.MCR_SERVICE
            likely = BedProtocol.MCR
        }
        if (BedProtocol.FUZION_SERVICE_UUID in services) {
            reasons += CandidateReason.FUZION_SERVICE
            likely = likely ?: BedProtocol.FUZION
        }
        val name = info.name?.trim()
        if (!name.isNullOrEmpty()) {
            val lower = name.lowercase()
            if (lower.startsWith("smart bed") || lower.startsWith("smartbed")) reasons += CandidateReason.SMART_BED_NAME
            if (isMacLike(name)) {
                if (isSelectComfort(name)) {
                    reasons += CandidateReason.SELECT_COMFORT_MAC
                    likely = likely ?: BedProtocol.MCR
                } else {
                    reasons += CandidateReason.MAC_NAME
                }
            }
        }
        // Android extension: no MAC-style name, but the address itself has the Select Comfort OUI.
        val address = info.address
        if (address != null && CandidateReason.SELECT_COMFORT_MAC !in reasons && isMacLike(address) && isSelectComfort(address)) {
            reasons += CandidateReason.SELECT_COMFORT_MAC
            likely = likely ?: BedProtocol.MCR
        }
        val data = info.manufacturerData
        if (data != null && data.size >= 2) {
            val company = data.u(0) or (data.u(1) shl 8)
            if (company == MANUFACTURER_ID) reasons += CandidateReason.MANUFACTURER_DATA
        }
        return CandidateClassification(reasons, likely)
    }
}
