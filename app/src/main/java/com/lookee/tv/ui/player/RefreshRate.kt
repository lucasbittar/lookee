package com.lookee.tv.ui.player

import android.app.Activity
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Matches the TV's refresh rate to the video, as Kodi's "adjust display refresh rate" does.
 * At 60 Hz a 25 fps PAL DVD alternates frames between 2 and 3 refreshes, a visible hitch
 * every other frame; at 50 Hz every frame lasts exactly two. Films (23.976/24 fps) get 24 Hz
 * when the box offers it. Android 9 has no Surface.setFrameRate, so this picks a display
 * mode through the window, and [restore] goes back to the system's choice.
 */
object RefreshRate {
    /** The mode before the player changed it; [restore] asks for it back by id (see there). */
    private var original = 0

    /** Picks a mode for [fps] at the current resolution; returns a line for the log. */
    fun match(activity: Activity, fps: Float): String? {
        if (fps < 10f) return null
        val display = activity.window.decorView.display ?: return null
        val current = display.mode
        if (fits(current.refreshRate, fps)) return "refresh %.2f Hz kept for %.3f fps".format(current.refreshRate, fps)
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && fits(it.refreshRate, fps)
        }
        // The highest matching rate up to 60 Hz: 50 over 25 for PAL, 24 for films.
        val best = candidates.filter { it.refreshRate <= 60.5f }.maxByOrNull { it.refreshRate } ?: return "no display mode for %.3f fps (modes: %s)".format(fps, describe(display))
        val params = activity.window.attributes
        if (params.preferredDisplayModeId == best.modeId) return null
        if (original == 0) original = current.modeId
        params.preferredDisplayModeId = best.modeId
        activity.window.attributes = params
        return "refresh %.2f Hz → %.2f Hz for %.3f fps".format(current.refreshRate, best.refreshRate, fps)
    }

    /**
     * Back to the mode from before playback. On the Mi Box, clearing the preference (0) left the
     * TV at the video's rate (50 Hz after a DVD), so the original mode is asked for by id, and
     * the preference is cleared once the display is back on it.
     */
    fun restore(activity: Activity) {
        val params = activity.window.attributes
        if (params.preferredDisplayModeId == 0) return
        val display = activity.window.decorView.display
        val back = original
        original = 0
        params.preferredDisplayModeId = back
        activity.window.attributes = params
        if (back == 0) return
        // Clear the request once the TV has switched, so the system's choice applies again later.
        activity.window.decorView.postDelayed({
            if (display?.mode?.modeId == back && activity.window.attributes.preferredDisplayModeId == back) {
                activity.window.attributes = activity.window.attributes.also { it.preferredDisplayModeId = 0 }
            }
        }, 3_000)
    }

    /** A whole number of refreshes per frame, within 0.2 % (59.94 Hz fits 29.97 fps, not 25). */
    private fun fits(hz: Float, fps: Float): Boolean {
        val ratio = hz / fps
        val n = ratio.roundToInt()
        return n >= 1 && abs(ratio - n) / n < 0.002f
    }

    private fun describe(display: Display) = display.supportedModes.joinToString(", ") {
        "${it.physicalWidth}x${it.physicalHeight}@%.2f".format(it.refreshRate)
    }
}
