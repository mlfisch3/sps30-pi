package com.sensirion.sps30.config

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.sensirion.sps30.data.SessionCsv
import com.sensirion.sps30.data.TimeSeriesHistory
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * Finds the session data this device has already stored and reads it back.
 *
 * Everything here came out of **Downloads/SPS30**, written by this app: the
 * session archives pulled from the sensor, and any loose session CSVs beside
 * them. No storage permission is involved - an app can always read back the
 * MediaStore items it inserted - which is also the limit: files put there by
 * something else are invisible to us, and that is worth saying rather than
 * passing off as "nothing found".
 */
object StoredSessions {
    private const val SUBDIR = "SPS30"

    /** A candidate file in stored data, before anything has been read from it. */
    data class Source(
        val name: String,
        val uri: Uri?,
        val file: File?,
        val bytes: Long,
        val isArchive: Boolean,
    )

    /** What one import run did, in enough detail to say what it did not do. */
    class Tally {
        var sessions = 0
        var points = 0
        var rows = 0L
        var empty = 0
        var unrecognised = 0
        var duplicate = 0
        var truncated = false
    }

    /**
     * Every stored file worth reading, newest first.
     *
     * Newest first matters: each archive contains *every* session recorded up to
     * the moment it was made, so consecutive archives overlap almost entirely.
     * Taking the newest first means the older ones' duplicate sessions are
     * recognised by name and skipped before they are decompressed.
     *
     * The app's own plot exports (`sps30-*.csv`) are deliberately excluded. They
     * are pictures of a window that was already on screen, derived from the same
     * sessions, so importing them would draw parts of the record twice.
     */
    fun list(context: Context): List<Source> {
        val out = ArrayList<Source>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR%")
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    if (!isSessionData(name)) continue
                    val uri = Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        c.getLong(idCol).toString(),
                    )
                    out.add(Source(name, uri, null, c.getLong(sizeCol), isArchive(name)))
                }
            }
        }
        // Android 9 and older, and anything the MediaStore insert fell back to.
        File(context.getExternalFilesDir(null), "exports").listFiles()?.forEach { f ->
            if (f.isFile && isSessionData(f.name) && out.none { it.name == f.name }) {
                out.add(Source(f.name, null, f, f.length(), isArchive(f.name)))
            }
        }
        return out.sortedByDescending { it.name }
    }

    /**
     * Total uncompressed CSV behind [sources], for choosing an averaging window
     * before any of it is read.
     *
     * An archive's entry sizes come from its own directory, so this costs a
     * directory read rather than inflating hundreds of megabytes. It is an upper
     * bound: sessions duplicated across archives are counted once per archive
     * but read only once, so the chosen window errs coarse rather than fine.
     */
    fun estimateBytes(context: Context, sources: List<Source>): Long = sources.sumOf { s ->
        if (!s.isArchive) {
            s.bytes
        } else {
            runCatching {
                openArchive(context, s).use { zip ->
                    zip.entries().asSequence().sumOf { it.size.coerceAtLeast(0L) }
                }
            }.getOrDefault(s.bytes * 3) // roughly what this CSV deflates to
        }
    }

    /**
     * Read every session in [sources] into [history], averaged to [bucketSeconds].
     *
     * [onSession] is called before each session is read, so a caller can show
     * progress and - since it may suspend - let other work on a shared dispatcher
     * run between sessions. Cancelling the calling coroutine stops the run and
     * still leaves the history consistent.
     *
     * Failure to read one file is counted, never thrown: the point of the
     * feature is to show whatever is there.
     */
    suspend fun importInto(
        context: Context,
        sources: List<Source>,
        history: TimeSeriesHistory,
        bucketSeconds: Double,
        maxPoints: Int,
        onSession: suspend (name: String, done: Int, total: Int, points: Int) -> Unit =
            { _, _, _, _ -> },
    ): Tally {
        val tally = Tally()
        try {
            val plan = sources.map { it to sessionNames(context, it) }
            val total = plan.sumOf { it.second.size }
            var done = 0
            outer@ for ((source, entries) in plan) {
                if (entries.isEmpty()) continue
                // One open handle per archive, so its 245 sessions cost one
                // directory read rather than 245 of them.
                val zip = if (source.isArchive) {
                    runCatching { openArchive(context, source) }.getOrNull() ?: continue
                } else {
                    null
                }
                try {
                    for (entry in entries) {
                        if (tally.points >= maxPoints) {
                            tally.truncated = true
                            break@outer
                        }
                        onSession(entry, done, total, tally.points)
                        done++
                        if (history.hasImported(entry)) {
                            tally.duplicate++
                            continue
                        }
                        runCatching {
                            val stream = if (zip != null) {
                                zip.getEntry(entry)?.let { zip.getInputStream(it) }
                                    ?: throw NoSuchElementException(entry)
                            } else {
                                openStream(context, source)
                            }
                            stream.use { readOne(entry, it, history, bucketSeconds, maxPoints, tally) }
                        }.onFailure { tally.unrecognised++ }
                    }
                } finally {
                    zip?.close()
                }
            }
        } finally {
            // Even a cancelled run has to leave the store sorted, or every
            // window afterwards would read the wrong slice.
            history.finishImport()
        }
        return tally
    }

    private fun readOne(
        entry: String,
        stream: InputStream,
        history: TimeSeriesHistory,
        bucketSeconds: Double,
        maxPoints: Int,
        tally: Tally,
    ) {
        val reader = BufferedReader(InputStreamReader(stream), 64 * 1024)
        val result = SessionCsv.read(reader, bucketSeconds) { bucket, epoch, values, flagged ->
            if (tally.points >= maxPoints) {
                tally.truncated = true
            } else {
                // The bucket ordinal is absolute time, so re-reading this session
                // later produces the same keys and adds nothing.
                val added = history.addImported(
                    entry, bucket.toInt(), epoch, values, if (flagged) SETTLE else "",
                )
                if (added) tally.points++
            }
        }
        tally.rows += result.rowsRead
        when (result.skip) {
            SessionCsv.Skip.EMPTY -> tally.empty++
            SessionCsv.Skip.UNRECOGNISED -> tally.unrecognised++
            null -> tally.sessions++
        }
    }

    /** Sessions inside [source], newest first; the file itself when it is a CSV. */
    private fun sessionNames(context: Context, source: Source): List<String> {
        if (!source.isArchive) return listOf(source.name)
        return runCatching {
            openArchive(context, source).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".csv", ignoreCase = true) }
                    .map { it.name }
                    .sortedDescending()
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Open [source] as a random-access zip, copying it into the cache first when
     * it is a MediaStore item.
     *
     * `ZipFile` needs a real file and a MediaStore item is a stream, so the copy
     * is unavoidable - but it pays for itself. Random access reads the archive's
     * directory without inflating it, and lets a session that is already loaded
     * be skipped outright instead of being decompressed only to be discarded.
     * Since consecutive archives are nearly identical, that is most of them.
     *
     * The copy is reused across calls in a run and cleared by [releaseCache].
     */
    private fun openArchive(context: Context, source: Source): ZipFile =
        ZipFile(source.file ?: cached(context, source))

    private fun cached(context: Context, source: Source): File {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val dest = File(dir, source.name)
        if (dest.isFile && dest.length() == source.bytes) return dest
        openStream(context, source).use { input ->
            dest.outputStream().use { input.copyTo(it, 256 * 1024) }
        }
        return dest
    }

    /** Delete anything cached while reading archives. */
    fun releaseCache(context: Context) {
        File(context.cacheDir, CACHE_DIR).deleteRecursively()
    }

    private fun openStream(context: Context, source: Source): InputStream =
        source.file?.inputStream()
            ?: source.uri?.let { context.contentResolver.openInputStream(it) }
            ?: throw java.io.FileNotFoundException(source.name)

    /** Session archives, and session CSVs named the way the sensor names them. */
    private fun isSessionData(name: String): Boolean =
        isArchive(name) || (name.startsWith("SPS30-") && name.endsWith(".csv", ignoreCase = true))

    private fun isArchive(name: String): Boolean = name.endsWith(".zip", ignoreCase = true)

    private const val CACHE_DIR = "stored-sessions"

    /** Marks a bucket made entirely of disturbed readings, as the daemon does. */
    private const val SETTLE = "settle"
}
