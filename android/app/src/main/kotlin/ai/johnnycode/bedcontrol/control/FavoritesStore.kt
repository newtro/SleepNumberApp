package ai.johnnycode.bedcontrol.control

import ai.johnnycode.bedkit.BedError
import ai.johnnycode.bedkit.favorites.FavoritesDocument
import android.util.AtomicFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists one [FavoritesDocument] per bed as JSON in `files/Favorites/<bed address>.json` (non
 * alphanumerics stripped, per docs/FAVORITES-SPEC.md §1.2). Writes are atomic.
 *
 * An unreadable file (bad JSON, newer version) is moved aside to `<name>.unreadable-<timestamp>.json`
 * and never overwritten; the bed then starts with no favorites.
 */
class FavoritesStore(filesDir: File) {
    private val directory = File(filesDir, "Favorites")

    data class Loaded(val document: FavoritesDocument, val problem: String? = null)

    private fun file(bedID: String): File {
        val name = FavoritesDocument.fileName(bedID).let { if (it == ".json") "bed.json" else it }
        return File(directory, name)
    }

    fun load(bedID: String): Loaded {
        val target = file(bedID)
        if (!target.exists()) return Loaded(FavoritesDocument(bedID))
        val text = try {
            AtomicFile(target).readFully().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            return Loaded(FavoritesDocument(bedID), moveAside(target, "could not be read: ${e.message}"))
        }
        return try {
            Loaded(FavoritesDocument.decode(text).copy(bedID = bedID))
        } catch (e: BedError) {
            Loaded(FavoritesDocument(bedID), moveAside(target, e.message))
        }
    }

    private fun moveAside(target: File, why: String): String {
        // ISO 8601 with ':' replaced by '-', as in iOS (docs/FAVORITES-SPEC.md §1.2).
        val stamp = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString().replace(":", "-")
        val aside = File(directory, target.nameWithoutExtension + ".unreadable-$stamp.json")
        val moved = target.renameTo(aside)
        return if (moved) "Saved favorites $why. The file was kept as ${aside.name} and the list starts empty."
        else "Saved favorites $why, and the file could not be moved aside; favorites will not be saved until it is removed."
    }

    /** Throws if the document cannot be written, or if an unreadable file is still in the way. */
    fun save(document: FavoritesDocument) {
        directory.mkdirs()
        val target = file(document.bedID)
        if (target.exists()) {
            // Never overwrite a file we could not read (it would have been moved aside on load).
            val readable = runCatching { FavoritesDocument.decode(AtomicFile(target).readFully().toString(Charsets.UTF_8)) }.isSuccess
            if (!readable) throw IllegalStateException("${target.name} is unreadable and was not overwritten")
        }
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(document.encode().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}
