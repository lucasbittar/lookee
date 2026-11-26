package com.lookee.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Preview frames for media TMDB doesn't know (lessons, personal videos, unmatched titles).
 * One frame ~10% in, decoded by the platform's retriever (hardware where possible), scaled
 * to 480 px and saved as a JPEG under cache/frames, so each video is grabbed once.
 * Android glue only: which file to use is decided by the Go core (previewPath).
 */
object VideoFrames {
    private val gate = Semaphore(2) // the Mi Box has two decoder-friendly cores to spare at most
    private val failed = mutableSetOf<String>()

    suspend fun frame(context: Context, videoPath: String): File? = withContext(Dispatchers.IO) {
        val src = File(videoPath)
        if (!src.isFile) return@withContext null
        val key = sha1("$videoPath:${src.length()}:${src.lastModified()}")
        val out = File(context.cacheDir, "frames/$key.jpg")
        if (out.isFile && out.length() > 0) return@withContext out
        synchronized(failed) { if (key in failed) return@withContext null }
        gate.withPermit {
            if (out.isFile && out.length() > 0) return@withPermit out
            val bitmap = grab(videoPath)
            if (bitmap == null) {
                synchronized(failed) { failed += key }
                return@withPermit null
            }
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".tmp")
            runCatching {
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                tmp.renameTo(out)
            }
            bitmap.recycle()
            out.takeIf { it.isFile }
        }
    }

    // ---- Continue Watching: a frame from where the viewer stopped ----

    /**
     * The frame for a Continue Watching card: the one the player grabbed on the way out (see
     * FrameGrab), else, for a plain file watched before that existed, one decoded at
     * [positionMs]. Files are resume/<key hash>-<seconds>.jpg: a new position is a new name,
     * so the image cache never shows a stale frame. Null means use the artwork.
     */
    suspend fun resumeFrame(context: Context, key: String, positionMs: Long): File? = withContext(Dispatchers.IO) {
        val prefix = sha1(key) + "-"
        val dir = File(context.cacheDir, "resume")
        dir.listFiles { f -> f.name.startsWith(prefix) && f.length() > 0 }?.maxByOrNull { it.lastModified() }?.let { return@withContext it }
        val src = File(key)
        if (!src.isFile || positionMs <= 0) return@withContext null
        val failKey = "resume:$key:$positionMs"
        synchronized(failed) { if (failKey in failed) return@withContext null }
        val bitmap = gate.withPermit { grabAt(key, positionMs) }
        if (bitmap == null) {
            synchronized(failed) { failed += failKey }
            return@withContext null
        }
        try { saveResume(context, key, positionMs, bitmap) } finally { bitmap.recycle() }
    }

    /** Stores [bitmap] as the frame for [key], replacing its older one; keeps the newest 40. */
    fun saveResume(context: Context, key: String, positionMs: Long, bitmap: Bitmap): File? {
        val prefix = sha1(key) + "-"
        val dir = File(context.cacheDir, "resume").apply { mkdirs() }
        val out = File(dir, "$prefix${positionMs / 1000}.jpg")
        val tmp = File(dir, out.name + ".tmp")
        val ok = runCatching {
            val trimmed = trimBars(bitmap)
            tmp.outputStream().use { trimmed.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (trimmed !== bitmap) trimmed.recycle()
            tmp.renameTo(out)
        }.getOrDefault(false)
        if (!ok) return null
        dir.listFiles { f -> f.name.startsWith(prefix) && f.name != out.name }?.forEach { it.delete() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
        return out
    }

    /**
     * Cuts the black bars off a letterboxed (scope film) or pillarboxed (4:3) frame, so the
     * picture fills the card instead of sitting in a band of black.
     */
    private fun trimBars(b: Bitmap): Bitmap {
        // A line is bar when nearly all of it is black: a logo or a speck in the bar is allowed.
        fun dark(n: Int, at: (Int) -> Int): Boolean {
            val samples = (0 until n step 6).map { luma(at(it)) }
            return samples.count { it < 24 } >= samples.size * 0.95
        }
        fun rowDark(y: Int) = dark(b.width) { b.getPixel(it, y) }
        fun colDark(x: Int) = dark(b.height) { b.getPixel(x, it) }
        var top = 0
        while (top < b.height / 3 && rowDark(top)) top++
        var bottom = b.height - 1
        while (bottom > b.height * 2 / 3 && rowDark(bottom)) bottom--
        var left = 0
        while (left < b.width / 3 && colDark(left)) left++
        var right = b.width - 1
        while (right > b.width * 2 / 3 && colDark(right)) right--
        // A couple of pixels in, past the soft edge between bar and picture.
        if (top > 0) top += 2
        if (bottom < b.height - 1) bottom -= 2
        if (left > 0) left += 2
        if (right < b.width - 1) right -= 2
        val w = right - left + 1
        val h = bottom - top + 1
        if (w == b.width && h == b.height) return b
        if (w < b.width / 3 || h < b.height / 3) return b // mostly dark: leave it be
        return Bitmap.createBitmap(b, left, top, w, h)
    }

    private fun luma(c: Int) = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    private fun grabAt(path: String, positionMs: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.getScaledFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                ?.takeUnless { isDark(it).also { dark -> if (dark) it.recycle() } }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun grab(path: String): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // ~10% in skips most openings. Lessons and films often start on black or a small
            // title card, so a nearly black frame is retried at 30% and 50%.
            val points = if (durationMs > 20_000) listOf(0.10, 0.30, 0.50) else listOf(0.0)
            var best: Bitmap? = null
            for (p in points) {
                val atUs = maxOf(1_000L, (durationMs * p).toLong()) * 1000
                val frame = r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270) ?: continue
                if (!isDark(frame)) {
                    best?.recycle()
                    return frame
                }
                if (best == null) best = frame else frame.recycle()
            }
            best
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Mean brightness under ~8% on a coarse grid of samples: a black or near-black frame. */
    private fun isDark(b: Bitmap): Boolean {
        var sum = 0L
        var n = 0
        val stepX = maxOf(1, b.width / 16)
        val stepY = maxOf(1, b.height / 9)
        for (y in 0 until b.height step stepY) for (x in 0 until b.width step stepX) {
            val c = b.getPixel(x, y)
            sum += ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            n++
        }
        return n > 0 && sum / n < 20
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
