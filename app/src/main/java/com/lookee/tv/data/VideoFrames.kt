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
