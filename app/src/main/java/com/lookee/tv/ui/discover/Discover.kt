package com.lookee.tv.ui.discover

import androidx.compose.foundation.Canvas
import com.lookee.tv.ui.navigation.LocalNavInset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lookee.tv.LookeeApp
import com.lookee.tv.data.Codes
import com.lookee.tv.data.DiscoverItem
import com.lookee.tv.data.GoCoreBridge
import com.lookee.tv.data.LookeeException
import com.lookee.tv.data.StreamStats
import com.lookee.tv.data.TorrentResult
import com.lookee.tv.data.TorrentSearch
import com.lookee.tv.ui.components.Alert
import com.lookee.tv.ui.components.ButtonStyle
import com.lookee.tv.ui.components.EmptyState
import com.lookee.tv.ui.components.ImageBadge
import com.lookee.tv.ui.components.LookeeButton
import com.lookee.tv.ui.components.MarqueeText
import com.lookee.tv.ui.components.MediaSurface
import com.lookee.tv.ui.components.PosterCard
import com.lookee.tv.ui.components.RemoteImage
import com.lookee.tv.ui.components.RowPadding
import com.lookee.tv.ui.components.RowTitle
import com.lookee.tv.ui.components.ScreenInset
import com.lookee.tv.ui.components.ScreenHeader
import com.lookee.tv.ui.components.SearchField
import com.lookee.tv.ui.player.PlayItem
import com.lookee.tv.ui.components.bytes
import com.lookee.tv.ui.navigation.Destination
import com.lookee.tv.ui.navigation.LocalNavigator
import com.lookee.tv.ui.navigation.Route
import com.lookee.tv.ui.theme.CategoryGlow
import com.lookee.tv.ui.theme.CategoryLook
import com.lookee.tv.ui.theme.JetBrainsMono
import com.lookee.tv.ui.theme.LookeeColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private val violet = CategoryLook.Discover.tint

// ---- browse & search ----

data class DiscoverState(
    val loading: Boolean = true,
    val movies: List<DiscoverItem> = emptyList(),
    val shows: List<DiscoverItem> = emptyList(),
    val query: String = "",
    val results: List<DiscoverItem>? = null,
    /** Torrents for the typed words, from every source including search pages (null: none). */
    val torrents: List<TorrentResult>? = null,
    val torrentsLoading: Boolean = false,
    val needsKey: Boolean = false,
    val error: String? = null,
)

class DiscoverViewModel(private val core: GoCoreBridge) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state
    private var searchJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = try {
                _state.value.copy(loading = false, movies = core.trending("movie"), shows = core.trending("tv"), needsKey = false, error = null)
            } catch (e: LookeeException) {
                _state.value.copy(loading = false, needsKey = e.code == Codes.NO_TMDB_KEY || e.code == Codes.BAD_TMDB_KEY, error = e.message)
            }
        }
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _state.value = _state.value.copy(results = null, torrents = null, torrentsLoading = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(450) // wait for the user to stop typing
            _state.value = _state.value.copy(torrentsLoading = true)
            // Titles (TMDB) and torrents for the words themselves, side by side: the second
            // finds what TMDB doesn't list, through the sources that answer free text.
            val torrents = async {
                runCatching { core.searchTorrentsText("", q.trim()).results }
                    .getOrElse { e -> if (e is LookeeException && e.code == Codes.NO_TORRENT_SOURCES) null else emptyList() }
            }
            _state.value = try {
                _state.value.copy(results = core.searchTmdb(q))
            } catch (e: Exception) {
                _state.value.copy(error = e.message)
            }
            val found = torrents.await()
            _state.value = _state.value.copy(torrents = found?.take(MAX_TEXT_TORRENTS), torrentsLoading = false)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DiscoverScreen() {
    val app = LookeeApp.from(LocalContext.current)
    val vm = viewModel { DiscoverViewModel(app.core) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val open: (DiscoverItem) -> Unit = { item -> if (item.libraryTitleId > 0) nav.push(Route.Details(item.libraryTitleId)) else nav.push(Route.Sources(item)) }

    Box(Modifier.fillMaxSize()) {
        CategoryGlow(CategoryLook.Discover, Offset(1700f, -100f))
        if (state.needsKey) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    "Discover needs a TMDB key",
                    "It's free at themoviedb.org. Paste it from your phone and trending titles appear here.",
                    action = { LookeeButton("Set up from your phone", onClick = { nav.goToTab(Destination.Settings) }) },
                )
            }
            return@Box
        }
        Column(Modifier.fillMaxSize().padding(top = LocalNavInset.current)) {
        ScreenHeader(
            CategoryLook.Discover.kicker, "Discover", violet, MaterialTheme.typography.displayMedium,
            Modifier.padding(start = ScreenInset, end = ScreenInset, top = 16.dp, bottom = 20.dp),
            trailing = { SearchField(state.query, vm::setQuery, "Search movies, shows and concerts", Modifier.width(420.dp)) },
        )
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            val results = state.results
            if (results != null) {
                item { DiscoverRow(if (results.isEmpty()) "No titles for “${state.query}”" else "Results", results, open) }
                val torrents = state.torrents
                when {
                    state.torrentsLoading -> item {
                        Column(Modifier.padding(horizontal = ScreenInset)) {
                            RowTitle("Torrents", inset = 0.dp)
                            Text("Searching your sources for “${state.query.trim()}”…", style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Muted)
                        }
                    }
                    torrents != null -> item {
                        Column(Modifier.padding(horizontal = ScreenInset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RowTitle(if (torrents.isEmpty()) "No torrents for “${state.query.trim()}”" else "Torrents", inset = 0.dp)
                            torrents.forEachIndexed { i, r ->
                                StreamRow(r, best = i == 0, modifier = Modifier) { nav.push(Route.Buffering(r.title, r)) }
                            }
                        }
                    }
                }
            } else {
                if (state.movies.isNotEmpty()) item { DiscoverRow("Trending this week", state.movies, open) }
                if (state.shows.isNotEmpty()) item { DiscoverRow("Trending shows", state.shows, open) }
                state.error?.let { item { Alert(it, modifier = Modifier.padding(horizontal = ScreenInset)) } }
            }
        }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DiscoverRow(title: String, items: List<DiscoverItem>, open: (DiscoverItem) -> Unit) {
    Column {
        RowTitle(title, inset = ScreenInset)
        LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = RowPadding) {
            items(items, key = { "${it.kind}/${it.id}" }) { item ->
                PosterCard(
                    title = item.title,
                    caption = listOfNotNull(item.year.takeIf { it > 0 }?.toString(), if (item.kind == "tv") "Series" else "Movie").joinToString(" · "),
                    posterUrl = item.posterUrl,
                    onClick = { open(item) },
                    badge = {
                        if (item.libraryTitleId > 0) {
                            ImageBadge("On your drive", LookeeColors.Ok.copy(alpha = 0.92f), LookeeColors.Bg)
                        } else {
                            ImageBadge("Stream", violet.copy(alpha = 0.92f), LookeeColors.Bg)
                        }
                    },
                )
            }
        }
    }
}

/** Torrents listed under a typed search: the best seeded first. */
private const val MAX_TEXT_TORRENTS = 15

// ---- pick a stream ----

data class SourcesState(val loading: Boolean = true, val search: TorrentSearch? = null, val noSources: Boolean = false, val error: String? = null)

class SourcesViewModel(private val item: DiscoverItem, private val core: GoCoreBridge) : ViewModel() {
    private val _state = MutableStateFlow(SourcesState())
    val state: StateFlow<SourcesState> = _state

    init {
        viewModelScope.launch {
            _state.value = try {
                SourcesState(loading = false, search = core.searchTorrents(item.kind, item.id))
            } catch (e: LookeeException) {
                SourcesState(loading = false, noSources = e.code == Codes.NO_TORRENT_SOURCES, error = e.message)
            }
        }
    }
}

@Composable
fun SourcesScreen(item: DiscoverItem) {
    val app = LookeeApp.from(LocalContext.current)
    val vm = viewModel(key = "sources-${item.kind}-${item.id}") { SourcesViewModel(item, app.core) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val first = remember { FocusRequester() }
    val results = state.search?.results.orEmpty()
    LaunchedEffect(results.isNotEmpty()) { if (results.isNotEmpty()) runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        CategoryGlow(CategoryLook.Discover, Offset(200f, 300f))
        Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 28.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(210.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Discover  ›  ${item.title}", style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RemoteImage(item.posterUrl, Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(14.dp)))
                Text(item.title, style = MaterialTheme.typography.headlineLarge, color = LookeeColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.year.takeIf { it > 0 }?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Muted)
                Text(item.overview, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9CED6), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text("Choose a stream", style = MaterialTheme.typography.headlineLarge, color = LookeeColors.Text)
                    Text("Best first: quality, then seeders", style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted)
                }
                when {
                    state.loading -> Text("Searching your sources…", style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Muted)
                    state.noSources -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Alert("No torrent sources yet. Add them from your phone (Settings) or your private defaults file.", tone = com.lookee.tv.ui.components.Tone.Accent)
                        LookeeButton("Open Settings", onClick = { nav.goToTab(Destination.Settings) })
                    }
                    state.error != null -> Alert(state.error!!)
                    results.isEmpty() -> Alert("No streams found for this title.", tone = com.lookee.tv.ui.components.Tone.Muted)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(results.size) { i ->
                        val r = results[i]
                        StreamRow(r, best = i == 0, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            nav.push(Route.Buffering(item.title, r, item.id, item.kind))
                        }
                    }
                    val warnings = state.search?.warnings.orEmpty()
                    item {
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Plays while it downloads · deleted when you stop", style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted, modifier = Modifier.clip(RoundedCornerShape(50)).background(LookeeColors.Surface).padding(horizontal = 12.dp, vertical = 5.dp))
                            if (warnings.isNotEmpty()) {
                                Text("${warnings.size} source${if (warnings.size == 1) "" else "s"} didn't answer", style = MaterialTheme.typography.bodySmall, color = LookeeColors.Accent, modifier = Modifier.clip(RoundedCornerShape(50)).background(LookeeColors.AccentSoft).padding(horizontal = 12.dp, vertical = 5.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamRow(r: TorrentResult, best: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    MediaSurface(onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, focusedScale = 1.02f) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                r.quality.ifEmpty { "—" },
                style = MaterialTheme.typography.labelLarge,
                color = if (best) Color(0xFFB9A2F7) else LookeeColors.Text,
                modifier = Modifier.width(64.dp).clip(RoundedCornerShape(7.dp)).background(if (best) violet.copy(alpha = 0.18f) else LookeeColors.Line).padding(vertical = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Column(Modifier.weight(1f)) {
                MarqueeText(r.title, MaterialTheme.typography.titleSmall, LookeeColors.Text, active = focused)
                Text(listOfNotNull(if (best) "Best match" else null, r.provider).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted)
            }
            Text(bytes(r.size), fontFamily = JetBrainsMono, fontSize = 13.sp, color = Color(0xFFC9CED6))
            SignalBars(r.seeders)
            Text(r.seeders.toString(), fontFamily = JetBrainsMono, fontSize = 13.sp, color = Color(0xFFC9CED6), modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/** Seeder health in four bars: green when well shared, yellow when thin. */
@Composable
private fun SignalBars(seeders: Int) {
    val lit = when {
        seeders >= 100 -> 4
        seeders >= 30 -> 3
        seeders >= 10 -> 2
        seeders > 0 -> 1
        else -> 0
    }
    val color = if (lit >= 3) LookeeColors.Ok else LookeeColors.Accent
    Row(Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) Box(Modifier.width(3.dp).height((3 * i + 2).dp).clip(RoundedCornerShape(1.dp)).background(if (i <= lit) color else LookeeColors.Line))
    }
}

// ---- buffering ----

data class BufferingState(val starting: Boolean = true, val error: String? = null)

class BufferingViewModel(private val result: TorrentResult, private val core: GoCoreBridge) : ViewModel() {
    private val _state = MutableStateFlow(BufferingState())
    val state: StateFlow<BufferingState> = _state
    val stats: StateFlow<StreamStats> = core.streamStats

    /** Called each time the screen opens: a stream stopped by leaving is started again. */
    fun start() {
        _state.value = BufferingState()
        viewModelScope.launch {
            _state.value = try {
                core.startStream(result.magnet, result.fileIndex)
                BufferingState(starting = false)
            } catch (e: LookeeException) {
                BufferingState(starting = false, error = if (e.code == Codes.CANCELLED) null else e.message)
            }
        }
    }

    /** Set when the player takes the stream over: from then on the player stops it. */
    var handedOff = false
        private set

    fun handOff() {
        handedOff = true
    }

    fun stop() {
        if (handedOff) return
        viewModelScope.launch { runCatching { core.stopStream() } }
    }

    override fun onCleared() {
        // Leaving the screen always stops the stream and deletes its pieces (CLAUDE.md).
        if (!handedOff) kotlinx.coroutines.runBlocking { runCatching { core.stopStream() } }
    }
}

@Composable
fun BufferingScreen(title: String, result: TorrentResult, tmdbId: Long = 0, kind: String = "") {
    val app = LookeeApp.from(LocalContext.current)
    val vm = viewModel(key = "buffering-${result.infoHash}") { BufferingViewModel(result, app.core) }
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.start()
        runCatching { cancel.requestFocus() }
    }
    // Enough is buffered: the player takes over (and with it, stopping the stream).
    LaunchedEffect(stats.state, stats.url) {
        if (stats.state == "ready" && stats.url.isNotEmpty() && !vm.handedOff) {
            vm.handOff()
            nav.replace(
                Route.Player(
                    PlayItem(
                        path = stats.url,
                        title = title,
                        subtitle = stats.fileName.ifEmpty { stats.name },
                        stream = true,
                        tmdbId = tmdbId,
                        kind = kind,
                        streamFile = stats.fileName,
                    ),
                ),
            )
        }
    }
    DisposableEffect(Unit) { onDispose { vm.stop() } }

    val ready = stats.state == "ready"
    val pct = if (stats.state == "metadata") 0f else (stats.bufferPercent / 100.0).toFloat()
    Box(Modifier.fillMaxSize()) {
        CategoryGlow(CategoryLook.Discover, Offset(960f, 400f), radius = 1100f)
        Column(Modifier.fillMaxSize().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("STARTING STREAM", style = MaterialTheme.typography.labelSmall, color = violet)
            Text(title, style = MaterialTheme.typography.displayMedium, color = LookeeColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(LookeeColors.Line, 0f, 360f, false, style = stroke)
                    drawArc(LookeeColors.Accent, -90f, 360f * pct, false, style = stroke)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(pct * 100).toInt()}%", style = MaterialTheme.typography.displaySmall, color = LookeeColors.Text)
                    Text("buffered", style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Step("Found peers", done = stats.peers > 0 || ready, current = stats.state == "metadata")
                Step("Loading the start", done = ready, current = stats.state == "buffering")
                Step("Ready to play", done = false, current = ready)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("${bytes(stats.speedBps)}/s".takeIf { stats.speedBps > 0 } ?: "– /s")
                Chip("${stats.peers} peers")
                Chip(listOf(result.quality, bytes(result.size)).filter { it.isNotEmpty() }.joinToString(" · "))
            }
            state.error?.let { Alert("Couldn't start: $it", modifier = Modifier.width(520.dp)) }
            LookeeButton("Cancel", onClick = { vm.stop(); nav.pop() }, modifier = Modifier.focusRequester(cancel), style = ButtonStyle.Primary)
        }
    }
}

@Composable
private fun Step(label: String, done: Boolean, current: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            done -> Text("✓", color = LookeeColors.Ok, style = MaterialTheme.typography.titleSmall)
            current -> Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(LookeeColors.Accent))
            else -> Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).border(2.dp, LookeeColors.Line, RoundedCornerShape(50)))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (current) LookeeColors.Text else if (done) Color(0xFFC9CED6) else LookeeColors.Muted, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun Chip(text: String) {
    Text(text, fontFamily = JetBrainsMono, fontSize = 12.sp, color = Color(0xFFC9CED6), modifier = Modifier.clip(RoundedCornerShape(50)).background(LookeeColors.Surface).border(1.dp, LookeeColors.Line, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp))
}
