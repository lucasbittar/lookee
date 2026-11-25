package com.lookee.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lookee.tv.LookeeApp
import com.lookee.tv.data.GoCoreBridge
import com.lookee.tv.data.Settings
import com.lookee.tv.ui.components.Alert
import com.lookee.tv.ui.components.FocusableCard
import com.lookee.tv.ui.components.SectionLabel
import com.lookee.tv.ui.components.StatusBadge
import com.lookee.tv.ui.components.Tone
import com.lookee.tv.ui.navigation.ScreenPadding
import com.lookee.tv.ui.theme.LookeeColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = true,
    val settings: Settings? = null,
    val folders: Int = 0,
    val titles: Int = 0,
    val error: String? = null,
)

class HomeViewModel(private val core: GoCoreBridge) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        viewModelScope.launch {
            _state.value = try {
                HomeState(
                    loading = false,
                    settings = core.settings(),
                    folders = core.roots().size,
                    titles = core.media(category = "", limit = 10_000).size,
                )
            } catch (e: Exception) {
                HomeState(loading = false, error = e.message)
            }
        }
    }
}

/**
 * Phase 3 Home: a greeting and live status from the Go core. The carousels replace it in
 * Phase 4, once the layout mockups are agreed (ROADMAP Decision 15).
 */
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val app = LookeeApp.from(LocalContext.current)
    val vm = viewModel { HomeViewModel(app.core) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(ScreenPadding.fillMaxSize().padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Welcome to lookee", style = MaterialTheme.typography.displayLarge, color = LookeeColors.Text)
        Text(
            "Your drive, your streams, one place. Here is where things stand.",
            style = MaterialTheme.typography.bodyLarge,
            color = LookeeColors.Muted,
        )
        state.error?.let { Alert("The core didn't start: $it", modifier = Modifier.padding(top = 16.dp)) }
        val s = state.settings
        Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatusTile(
                label = "Library",
                value = if (state.loading) "…" else "${state.titles} titles",
                detail = "${state.folders} folders",
                badge = if (state.folders > 0) "Ready" to Tone.Ok else "Add folders" to Tone.Muted,
                onClick = onOpenSettings,
            )
            StatusTile(
                label = "Metadata",
                value = "TMDB",
                detail = s?.tmdbLanguage ?: "…",
                badge = if (s?.tmdbKeySet == true) "Key set" to Tone.Ok else "Needs key" to Tone.Accent,
                onClick = onOpenSettings,
            )
            StatusTile(
                label = "Subtitles",
                value = "OpenSubtitles",
                detail = s?.subtitleLanguages ?: "…",
                badge = if (s?.openSubtitlesKeySet == true) "Key set" to Tone.Ok else "Needs key" to Tone.Accent,
                onClick = onOpenSettings,
            )
            StatusTile(
                label = "Streaming",
                value = "${s?.torrentSources ?: 0} sources",
                detail = "Torrent indexers",
                badge = if ((s?.torrentSources ?: 0) > 0) "Ready" to Tone.Ok else "None yet" to Tone.Muted,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun StatusTile(label: String, value: String, detail: String, badge: Pair<String, Tone>, onClick: () -> Unit) {
    FocusableCard(onClick = onClick, modifier = Modifier.width(196.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(label)
            Text(value, style = MaterialTheme.typography.titleLarge, color = LookeeColors.Text, modifier = Modifier.padding(top = 6.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted)
            StatusBadge(badge.first, badge.second, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
