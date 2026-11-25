package com.lookee.tv.data

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-wide library state: runs scans (one at a time) and bumps [version] whenever the
 * library changes, so every screen reloads from the core. Screens never scan themselves.
 */
class LibrarySync(private val core: GoCoreBridge, private val scope: CoroutineScope) {
    private val _scan = MutableStateFlow<ScanUpdate?>(null)

    /** The running scan's latest update; null when idle. The final update stays until the next scan. */
    val scan: StateFlow<ScanUpdate?> = _scan.asStateFlow()

    private val _version = MutableStateFlow(0)

    /** Increases after every scan and library edit (Fix Match, folders added or removed). */
    val version: StateFlow<Int> = _version.asStateFlow()

    private var job: Job? = null
    private var lastAuto = 0L

    val running: Boolean get() = job?.isActive == true

    /** Whether the current (or last) scan was started by the app rather than by the user. */
    @Volatile
    var automatic: Boolean = false
        private set

    /** A scan the user asked for (Scan now, a folder added). */
    fun scanNow() = start(automatic = false)

    /**
     * A scan the app starts on its own: when it comes to the front, and when a drive is
     * plugged in or removed ([force]). Coming back within a minute doesn't rescan.
     * Scans are incremental, so one with nothing new only walks the folders.
     */
    fun autoScan(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastAuto != 0L && now - lastAuto < AUTO_COOLDOWN_MS) return
        lastAuto = now
        start(automatic = true)
    }

    private fun start(automatic: Boolean) {
        if (running) return
        Log.i("lookee.sync", if (automatic) "automatic sync" else "sync requested")
        this.automatic = automatic
        job = scope.launch {
            runCatching {
                core.scan().collect { update ->
                    _scan.value = update
                    if (update.isFinal) _version.value++
                }
            }.onFailure { _scan.value = ScanUpdate(phase = "failed", error = it.message.orEmpty()) }
        }
    }

    fun changed() {
        _version.value++
    }

    private companion object {
        const val AUTO_COOLDOWN_MS = 60_000L
    }
}
