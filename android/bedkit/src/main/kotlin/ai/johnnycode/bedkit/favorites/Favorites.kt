package ai.johnnycode.bedkit.favorites

import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.BedSide
import ai.johnnycode.bedkit.SleepNumber
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// Port of BedKit/Sources/Common/Favorites.swift (data model), per docs/FAVORITES-SPEC.md §1.

/**
 * A user-saved favorite for one sleeper: that side's head, the shared foot, and that side's Sleep
 * Number. Build values through [Favorite.create] / [normalized]: positions are clamped to 0..100 and the
 * Sleep Number snapped to 5..100 step 5, on creation, on every edit, and when a file is read.
 *
 * Properties are declared in alphabetical order so the JSON keys come out sorted, like iOS.
 */
@Serializable
data class Favorite(
    /** ISO 8601 UTC, e.g. "2026-10-10T21:15:03Z". */
    val createdAt: String,
    val foot: Int,
    val head: Int,
    val id: String,
    val name: String,
    val sleepNumber: Int,
) {
    fun normalized(): Favorite = copy(
        name = cleanName(name),
        head = clampPosition(head),
        foot = clampPosition(foot),
        sleepNumber = SleepNumber.normalize(sleepNumber),
    )

    /** Same favorite with edited values (normalized). */
    fun edited(name: String = this.name, head: Int = this.head, foot: Int = this.foot, sleepNumber: Int = this.sleepNumber) =
        copy(name = name, head = head, foot = foot, sleepNumber = sleepNumber).normalized()

    companion object {
        fun create(
            name: String,
            head: Int,
            foot: Int,
            sleepNumber: Int,
            id: String = UUID.randomUUID().toString().uppercase(),
            createdAt: Instant = Instant.now(),
        ) = Favorite(
            createdAt = createdAt.truncatedTo(ChronoUnit.SECONDS).toString(),
            foot = foot, head = head, id = id, name = name, sleepNumber = sleepNumber,
        ).normalized()

        fun clampPosition(value: Int): Int = value.coerceIn(0, 100)

        /** Trims whitespace, caps at 40 characters, and falls back to "Favorite" when empty. */
        fun cleanName(name: String): String {
            val trimmed = name.trim()
            return if (trimmed.isEmpty()) "Favorite" else trimmed.take(40)
        }
    }
}

/** All favorites for one bed, per side, in display order. Stored as one JSON document per bed. */
@Serializable
data class FavoritesDocument(
    /** The bed this document belongs to (Android: the bed's Bluetooth MAC address). */
    val bedID: String,
    val left: List<Favorite> = emptyList(),
    val right: List<Favorite> = emptyList(),
    val version: Int = CURRENT_VERSION,
) {
    operator fun get(side: BedSide): List<Favorite> = if (side == BedSide.LEFT) left else right

    private fun with(side: BedSide, items: List<Favorite>) = if (side == BedSide.LEFT) copy(left = items) else copy(right = items)

    /** Default editor name for a new favorite: "<Side> <n>", n = count + 1. */
    fun suggestedName(side: BedSide): String = "${side.title} ${this[side].size + 1}"

    fun adding(favorite: Favorite, side: BedSide) = with(side, this[side] + favorite.normalized())

    /** Replaces the favorite with the same id (rename / edit values), in place. No-op if absent. */
    fun updating(favorite: Favorite, side: BedSide): FavoritesDocument {
        if (this[side].none { it.id == favorite.id }) return this
        return with(side, this[side].map { if (it.id == favorite.id) favorite.normalized() else it })
    }

    fun deleting(id: String, side: BedSide) = with(side, this[side].filterNot { it.id == id })

    /** Same semantics as SwiftUI's `onMove`: moves the items at [offsets] to before [destination]. */
    fun moving(side: BedSide, offsets: Set<Int>, destination: Int): FavoritesDocument {
        val items = this[side].toMutableList()
        val sorted = offsets.filter { it in items.indices }.sorted()
        val moving = sorted.map { items[it] }
        val before = sorted.count { it < destination }
        for (index in sorted.reversed()) items.removeAt(index)
        items.addAll((destination - before).coerceIn(0, items.size), moving)
        return with(side, items)
    }

    fun movingUp(id: String, side: BedSide): FavoritesDocument {
        val index = this[side].indexOfFirst { it.id == id }
        return if (index <= 0) this else moving(side, setOf(index), index - 1)
    }

    fun movingDown(id: String, side: BedSide): FavoritesDocument {
        val index = this[side].indexOfFirst { it.id == id }
        return if (index < 0 || index >= this[side].size - 1) this else moving(side, setOf(index), index + 2)
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val CURRENT_VERSION = 1
        private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

        /**
         * Decodes a stored document, normalizing every value. Throws [BedError.MalformedResponse] for
         * unreadable JSON or a version newer than this app; the caller treats that as "no favorites"
         * and moves the file aside rather than overwriting it.
         */
        fun decode(text: String): FavoritesDocument {
            val document = try {
                json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                throw BedError.MalformedResponse("favorites file is unreadable: ${e.message}")
            }
            if (document.version > CURRENT_VERSION) {
                throw BedError.MalformedResponse("favorites file version ${document.version} is newer than this app")
            }
            return document.copy(left = document.left.map { it.normalized() }, right = document.right.map { it.normalized() })
        }

        /** Storage file name for a bed: every character other than letters, digits and '-' stripped. */
        fun fileName(bedID: String): String = bedID.filter { it.isLetterOrDigit() || it == '-' } + ".json"
    }
}
