package com.lookee.tv.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lookee.tv.data.GoCoreBridge
import com.lookee.tv.data.RemoteSetup
import com.lookee.tv.data.Settings
import com.lookee.tv.data.lanAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface PhoneSetup {
    data object Idle : PhoneSetup
    data object Starting : PhoneSetup
    data class Open(val setup: RemoteSetup, val secondsLeft: Int) : PhoneSetup
    data object Saved : PhoneSetup
    data class Failed(val message: String) : PhoneSetup
}

data class SettingsState(
    val settings: Settings? = null,
    val settingsJson: String = "",
    val coreVersion: String = "",
    val logs: List<String> = emptyList(),
    val phone: PhoneSetup = PhoneSetup.Idle,
    val error: String? = null,
)

class SettingsViewModel(private val core: GoCoreBridge, private val app: Application) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState(coreVersion = core.coreVersion()))
    val state: StateFlow<SettingsState> = _state
    private var countdown: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val settings = core.settings()
                val json = core.settingsJson()
                val logs = core.logs(30)
                _state.update { it.copy(settings = settings, settingsJson = json, logs = logs, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun startPhoneSetup() {
        _state.update { it.copy(phone = PhoneSetup.Starting) }
        viewModelScope.launch {
            try {
                val setup = core.startRemoteSetup(lanAddress(app)) { saved ->
                    // Called on a Go thread when the page closes.
                    countdown?.cancel()
                    _state.update { it.copy(phone = if (saved) PhoneSetup.Saved else PhoneSetup.Idle) }
                    if (saved) refresh()
                }
                _state.update { it.copy(phone = PhoneSetup.Open(setup, setup.expiresInSeconds)) }
                countdown = launch {
                    var left = setup.expiresInSeconds
                    while (isActive && left > 0) {
                        delay(1_000)
                        left--
                        _state.update { s -> if (s.phone is PhoneSetup.Open) s.copy(phone = PhoneSetup.Open(setup, left)) else s }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(phone = PhoneSetup.Failed(e.message ?: "Couldn't start the setup page")) }
            }
        }
    }

    fun stopPhoneSetup() {
        countdown?.cancel()
        viewModelScope.launch { core.stopRemoteSetup() }
        _state.update { it.copy(phone = PhoneSetup.Idle) }
    }

    override fun onCleared() {
        // Never leave the LAN page running when the user walks away from Settings.
        if (_state.value.phone is PhoneSetup.Open) {
            countdown?.cancel()
            kotlinx.coroutines.runBlocking { runCatching { core.stopRemoteSetup() } }
        }
    }
}
