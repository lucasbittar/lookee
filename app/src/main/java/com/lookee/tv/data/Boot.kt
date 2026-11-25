package com.lookee.tv.data

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** What the app is doing while it starts, in order. The splash shows one step at a time. */
enum class BootStep(val label: String) {
    Engine("Waking up the engine"),
    Library("Opening your library"),
    Sources("Loading your sources"),
    Drives("Checking your drives"),
    Home("Getting Home ready"),
    Done("Ready"),
}

data class BootState(
    val step: BootStep = BootStep.Engine,
    val detail: String? = null,
    val error: String? = null,
) {
    val done: Boolean get() = step == BootStep.Done
}

/**
 * Startup progress for the splash. Steps are real work, not a timer: loading the 24 MB Go
 * library, opening SQLite, applying presets, looking for the drive and the first Home query.
 * Each step's duration goes to logcat (tag `lookee.boot`) to find what is slow on the box.
 */
class Boot {
    private val _state = MutableStateFlow(BootState())
    val state: StateFlow<BootState> = _state.asStateFlow()

    private val homeLoaded = CompletableDeferred<Unit>()
    private val started = SystemClock.elapsedRealtime()
    private var stepStarted = started

    fun step(step: BootStep, detail: String? = null) {
        val now = SystemClock.elapsedRealtime()
        if (step != _state.value.step) Log.i(TAG, "${_state.value.step} took ${now - stepStarted} ms")
        stepStarted = now
        if (step == BootStep.Done) Log.i(TAG, "ready after ${now - started} ms")
        _state.update { it.copy(step = step, detail = detail) }
    }

    fun detail(text: String?) = _state.update { it.copy(detail = text) }

    fun fail(message: String) {
        Log.w(TAG, "failed at ${_state.value.step}: $message")
        _state.update { it.copy(error = message) }
    }

    /** Called by Home after its first load, which may finish before the boot reaches that step. */
    fun homeReady() {
        homeLoaded.complete(Unit)
    }

    /** Waits for Home's first load; a slow query doesn't hold the splash forever. */
    suspend fun awaitHome() {
        withTimeoutOrNull(HOME_TIMEOUT_MS) { homeLoaded.await() }
    }

    private companion object {
        const val TAG = "lookee.boot"
        const val HOME_TIMEOUT_MS = 8_000L
    }
}
