package com.lookee.tv.ui.settings

import androidx.compose.runtime.withFrameNanos
import com.lookee.tv.ui.navigation.LocalNavInset
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lookee.tv.LookeeApp
import com.lookee.tv.data.Settings
import com.lookee.tv.ui.components.Alert
import com.lookee.tv.ui.components.ButtonStyle
import com.lookee.tv.ui.components.FocusableCard
import com.lookee.tv.ui.components.LookeeButton
import com.lookee.tv.ui.components.LookeeCard
import com.lookee.tv.ui.components.PinDisplay
import com.lookee.tv.ui.components.SectionLabel
import com.lookee.tv.ui.components.StatusBadge
import com.lookee.tv.ui.components.SuccessCheck
import com.lookee.tv.ui.components.Tone
import com.lookee.tv.ui.theme.LookeeColors

@Composable
fun SettingsScreen() {
    val app = LookeeApp.from(LocalContext.current)
    val vm = viewModel { SettingsViewModel(app.core, app) }
    val state by vm.state.collectAsStateWithLifecycle()

    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().padding(top = LocalNavInset.current),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // The heading scrolls with the cards.
        item {
            Text("Settings", style = MaterialTheme.typography.displaySmall, color = LookeeColors.Text)
        }
        state.error?.let { item { Alert("The core reported a problem: $it") } }
        item {
            // Scrolling following focus would park the card's button at the top and hide the
            // card's heading; on the first row, go all the way back to the top instead, a couple
            // of frames later so it lands after the focus scroll.
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.onFocusChanged { if (it.hasFocus) scope.launch { withFrameNanos { }; withFrameNanos { }; list.scrollToItem(0) } },
            ) {
                PhoneSetupCard(state.phone, vm::startPhoneSetup, vm::stopPhoneSetup, Modifier.weight(1.2f))
                StatusCard(state.settings, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LibraryCard(Modifier.weight(1.2f))
                StorageCard(Modifier.weight(1f))
            }
        }
        item {
            DiagnosticsCard(state, onRefresh = vm::refresh)
        }
    }
}

@Composable
private fun PhoneSetupCard(phone: PhoneSetup, onStart: () -> Unit, onStop: () -> Unit, modifier: Modifier) {
    LookeeCard(modifier) {
        SectionLabel("Set up from your phone")
        Spacer(Modifier.padding(top = 12.dp))
        when (phone) {
            PhoneSetup.Idle, PhoneSetup.Starting, is PhoneSetup.Failed -> {
                Text(
                    "Paste API keys and torrent sources from your phone instead of typing them with the remote.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LookeeColors.Muted,
                )
                if (phone is PhoneSetup.Failed) Alert(phone.message, modifier = Modifier.padding(top = 12.dp))
            }
            is PhoneSetup.Open -> {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                    QrCode(phone.setup.url, Modifier.size(132.dp))
                    Column {
                        Text("Scan with your phone, or open", style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Muted)
                        Text(
                            phone.setup.url,
                            style = MaterialTheme.typography.headlineSmall,
                            color = LookeeColors.Accent,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                        Text("on the same Wi-Fi, then enter this PIN", style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Muted)
                    }
                }
                PinDisplay(phone.setup.pin, modifier = Modifier.padding(top = 12.dp))
            }
            PhoneSetup.Saved -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SuccessCheck()
                    Column {
                        Text("Settings saved", style = MaterialTheme.typography.titleLarge, color = LookeeColors.Text)
                        Text("The setup page has closed.", style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Muted)
                    }
                }
            }
        }
        // One button for every state, at one call site, so the focused node survives state
        // changes. If it were removed while focused, Android would hand focus to the first
        // tab and the user would land on Home.
        Row(
            Modifier.padding(top = 20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val (label, action, style) = when (phone) {
                PhoneSetup.Idle -> Triple("Start phone setup", onStart, ButtonStyle.Primary)
                PhoneSetup.Starting -> Triple("Starting…", {}, ButtonStyle.Primary)
                is PhoneSetup.Open -> Triple("Stop", onStop, ButtonStyle.Quiet)
                PhoneSetup.Saved -> Triple("Set up again", onStart, ButtonStyle.Quiet)
                is PhoneSetup.Failed -> Triple("Try again", onStart, ButtonStyle.Primary)
            }
            LookeeButton(label, onClick = action, style = style)
            if (phone is PhoneSetup.Open) {
                val m = phone.secondsLeft / 60
                val s = phone.secondsLeft % 60
                Text("Closes in $m:${s.toString().padStart(2, '0')}", style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted)
            }
        }
    }
}

@Composable
private fun StatusCard(settings: Settings?, modifier: Modifier) {
    LookeeCard(modifier) {
        SectionLabel("Status")
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusRow("TMDB key", settings?.tmdbKeySet)
            StatusRow("OpenSubtitles key", settings?.openSubtitlesKeySet)
            StatusRow("OpenSubtitles login", settings?.openSubtitlesLoginSet, unsetLabel = "Optional")
            ValueRow("Metadata language", settings?.tmdbLanguage)
            ValueRow("Subtitle languages", settings?.subtitleLanguages)
            ValueRow("Torrent sources", settings?.torrentSources?.toString())
        }
    }
}

@Composable
private fun StatusRow(label: String, set: Boolean?, unsetLabel: String = "Not set") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Text)
        when (set) {
            null -> StatusBadge("…", Tone.Muted)
            true -> StatusBadge("Set", Tone.Ok)
            false -> StatusBadge(unsetLabel, Tone.Muted)
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Text)
        Text(value ?: "…", style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Muted)
    }
}

/** Raw core output for checking the bridge on a device (Phase 3 exit criterion). OK refreshes it. */
@Composable
private fun DiagnosticsCard(state: SettingsState, onRefresh: () -> Unit) {
    FocusableCard(onClick = onRefresh, focusedScale = 1.01f, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Diagnostics")
                Spacer(Modifier.width(12.dp))
                StatusBadge("core ${state.coreVersion}", Tone.Accent)
                Spacer(Modifier.weight(1f))
                Text("OK to refresh", style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted)
            }
            Text("getSettings()", style = MaterialTheme.typography.labelMedium, color = LookeeColors.Muted, modifier = Modifier.padding(top = 6.dp))
            Mono(state.settingsJson.ifEmpty { "…" }, LookeeColors.Text)
            Text("Log (latest ${state.logs.size} lines)", style = MaterialTheme.typography.labelMedium, color = LookeeColors.Muted, modifier = Modifier.padding(top = 6.dp))
            Mono(state.logs.joinToString("\n").ifEmpty { "No log lines yet." }, LookeeColors.Muted)
        }
    }
}

@Composable
private fun Mono(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = color)
}
