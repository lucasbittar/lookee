package com.lookee.tv.data

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
    private var startedOnce = false

    val running: Boolean get() = job?.isActive == true

    fun scanNow() {
        if (running) return
        job = scope.launch {
            runCatching {
                core.scan().collect { update ->
                    _scan.value = update
                    if (update.isFinal) _version.value++
                }
            }.onFailure { _scan.value = ScanUpdate(phase = "failed", error = it.message.orEmpty()) }
        }
    }

    /** The startup scan: once per process. */
    fun scanOnLaunch() {
        if (startedOnce) return
        startedOnce = true
        scanNow()
    }

    fun changed() {
        _version.value++
    }
}
