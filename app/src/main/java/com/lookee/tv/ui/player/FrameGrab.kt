package com.lookee.tv.ui.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Copies the picture on the player's video surface (whichever engine drew it) into a small
 * bitmap, for the Continue Watching card. PixelCopy reads the surface's last frame, so nothing
 * is decoded twice, and it works the same for files, discs and either engine. The OSD is
 * drawn by Compose above the surface, so it is never in the copy.
 */
object FrameGrab {
    suspend fun capture(root: View?): Bitmap? {
        val surface = root?.let(::findSurface) ?: return null
        if (!surface.holder.surface.isValid || surface.width == 0) return null
        val bitmap = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val ok = withTimeoutOrNull(1_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    PixelCopy.request(surface, bitmap, { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
                }.onFailure { if (cont.isActive) cont.resume(false) }
            }
        } ?: false
        if (!ok || isDark(bitmap)) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    /** The video surface: the first SurfaceView (Exo's surface_view, VLC's surface_video). */
    private fun findSurface(v: View): SurfaceView? {
        if (v is SurfaceView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findSurface(v.getChildAt(i))?.let { return it }
        return null
    }

    /** A black frame (a fade, a surface not drawn yet) isn't worth keeping. */
    fun isDark(b: Bitmap): Boolean {
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

    const val FRAME_W = 480
    const val FRAME_H = 270
}
