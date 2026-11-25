package com.lookee.tv.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lookee.tv.LookeeApp
import com.lookee.tv.data.Category
import com.lookee.tv.data.ContinueItem
import com.lookee.tv.data.GoCoreBridge
import com.lookee.tv.data.HomeView
import com.lookee.tv.data.LibrarySync
import com.lookee.tv.data.MediaTitle
import com.lookee.tv.data.ScanUpdate
import com.lookee.tv.ui.components.Alert
import com.lookee.tv.ui.components.EmptyState
import com.lookee.tv.ui.components.FitText
import com.lookee.tv.ui.components.LookeeButton
import com.lookee.tv.ui.components.PosterCard
import com.lookee.tv.ui.components.RemoteImage
import com.lookee.tv.ui.components.RowPadding
import com.lookee.tv.ui.components.RowTitle
import com.lookee.tv.ui.components.ScreenInset
import com.lookee.tv.ui.components.StillCard
import com.lookee.tv.ui.components.progressOf
import com.lookee.tv.ui.components.runtimeLabel
import com.lookee.tv.ui.components.timeLeft
import com.lookee.tv.ui.navigation.Destination
import com.lookee.tv.ui.navigation.LocalNavigator
import com.lookee.tv.ui.navigation.Route
import com.lookee.tv.ui.theme.LookeeColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the banner shows: whichever card has focus. */
data class Hero(
    val title: String,
    val kicker: String,
    val meta: String,
    val overview: String,
    val backdrop: String,
)

data class HomeState(
    val loading: Boolean = true,
    val home: HomeView = HomeView(),
    val hero: Hero? = null,
    val error: String? = null,
)

class HomeViewModel(private val core: GoCoreBridge, library: LibrarySync) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        viewModelScope.launch { library.version.collect { load() } }
    }

    private suspend fun load() {
        _state.value = try {
            val home = core.home(20)
            val first = home.continueWatching.firstOrNull()?.let(::heroOf) ?: home.recentlyAdded.firstOrNull()?.let(::heroOf)
            _state.value.copy(loading = false, home = home, hero = _state.value.hero ?: first, error = null)
        } catch (e: Exception) {
            _state.value.copy(loading = false, error = e.message)
        }
    }

    fun focus(hero: Hero) {
        _state.value = _state.value.copy(hero = hero)
    }
}

fun categoryLabel(category: String): String = when (category) {
    Category.MOVIES -> "Movies"
    Category.TV_SHOWS -> "Series"
    Category.CONCERTS -> "Concerts"
    Category.TUTORIALS -> "Tutorials"
    else -> ""
}

fun heroOf(item: ContinueItem) = Hero(
    title = item.title,
    kicker = "Continue watching",
    meta = listOfNotNull(
        item.year.takeIf { it > 0 }?.toString(),
        item.genres.take(2).joinToString(", ").ifEmpty { null },
        if (item.episode > 0) "S${item.season} · E${item.episode} ${item.episodeName}".trim() else null,
        timeLeft(item.positionMs, item.durationMs),
    ).joinToString(" · "),
    overview = item.overview,
    backdrop = item.backdropUrl.ifEmpty { item.stillUrl },
)

fun heroOf(t: MediaTitle) = Hero(
    title = t.title,
    kicker = "Recently added",
    meta = listOfNotNull(
        t.year.takeIf { it > 0 }?.toString(),
        runtimeLabel(t.runtime).ifEmpty { null },
        t.genres.take(2).joinToString(", ").ifEmpty { null },
    ).joinToString(" · "),
    overview = t.overview,
    backdrop = t.backdropUrl,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen() {
    val app = LookeeApp.from(LocalContext.current)
    val vm = viewModel { HomeViewModel(app.core, app.library) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scan by app.library.scan.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val home = state.home

    Box(Modifier.fillMaxSize()) {
        // Banner backdrop, crossfading as focus moves.
        Crossfade(state.hero?.backdrop, animationSpec = tween(400), label = "backdrop", modifier = Modifier.align(Alignment.TopEnd)) { url ->
            Box(Modifier.width(620.dp).height(360.dp)) {
                if (!url.isNullOrBlank()) RemoteImage(url, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(LookeeColors.Bg, LookeeColors.Bg.copy(alpha = 0.55f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to LookeeColors.Bg, 0.18f to Color.Transparent, 0.6f to LookeeColors.Bg.copy(alpha = 0.6f), 1f to LookeeColors.Bg)))
            }
        }

        SyncPill(scan, Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 4.dp))

        val nothing = !state.loading && home.continueWatching.isEmpty() && home.recentlyAdded.isEmpty()
        when {
            state.error != null && home.recentlyAdded.isEmpty() -> Alert(
                "The core couldn't load the library: ${state.error}",
                modifier = Modifier.padding(48.dp),
            )
            nothing -> EmptyState(
                title = "Let's find your movies",
                body = "Add your drive's folders in Settings. lookee scans them and fetches posters, cast and synopses.",
                modifier = Modifier.align(Alignment.Center),
                action = { LookeeButton("Open Settings", onClick = { nav.goToTab(Destination.Settings) }) },
            )
            else -> Column(Modifier.fillMaxSize()) {
                HeroText(state.hero, Modifier.padding(start = 48.dp, top = 18.dp, end = 360.dp).height(176.dp))
                LazyColumn(
                    contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    if (home.continueWatching.isNotEmpty()) {
                        item("continue") {
                            Column {
                                RowTitle("Continue watching", inset = ScreenInset)
                                LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = RowPadding) {
                                    items(home.continueWatching, key = { it.key }) { item ->
                                        StillCard(
                                            title = item.title,
                                            caption = if (item.episode > 0) "S${item.season} · E${item.episode} ${item.episodeName}".trim() else timeLeft(item.positionMs, item.durationMs),
                                            imageUrl = item.stillUrl.ifEmpty { item.backdropUrl },
                                            progress = progressOf(item.positionMs, item.durationMs),
                                            onClick = { if (item.titleId > 0) nav.push(Route.Details(item.titleId)) else nav.toast("The player arrives in the next build.") },
                                            onFocused = { vm.focus(heroOf(item)) },
                                            videoPath = item.key,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (home.recentlyAdded.isNotEmpty()) {
                        item("recent") {
                            Column {
                                RowTitle("Recently added", inset = ScreenInset)
                                LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = RowPadding) {
                                    items(home.recentlyAdded, key = { it.id }) { t ->
                                        PosterCard(
                                            title = t.title,
                                            caption = listOfNotNull(t.year.takeIf { it > 0 }?.toString(), categoryLabel(t.category)).joinToString(" · "),
                                            posterUrl = t.posterUrl,
                                            onClick = { nav.push(Route.Details(t.id)) },
                                            onFocused = { vm.focus(heroOf(t)) },
                                            videoPath = t.previewPath,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroText(hero: Hero?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hero == null) return@Column
        Text(
            hero.kicker,
            style = MaterialTheme.typography.labelMedium,
            color = LookeeColors.Accent,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(LookeeColors.AccentSoft)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
        FitText(hero.title, MaterialTheme.typography.displayLarge, LookeeColors.Text)
        if (hero.meta.isNotEmpty()) {
            Text(hero.meta, style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hero.overview.isNotEmpty()) {
            Text(hero.overview, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC9CED6), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Syncing your drive" while a scan runs; nothing when idle. */
@Composable
fun SyncPill(scan: ScanUpdate?, modifier: Modifier = Modifier) {
    if (scan == null || scan.isFinal) return
    val text = when (scan.phase) {
        "matching" -> "Fetching posters · ${scan.matched}/${scan.toMatch}"
        else -> "Syncing your drive · ${scan.filesSeen} files"
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(LookeeColors.Surface2)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(8.dp).fillMaxHeight().padding(vertical = 2.dp).clip(RoundedCornerShape(50)).background(LookeeColors.Accent))
        Text(text, style = MaterialTheme.typography.labelMedium, color = LookeeColors.Text)
    }
}

