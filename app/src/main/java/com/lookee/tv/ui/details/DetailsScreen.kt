package com.lookee.tv.ui.details

import com.lookee.tv.ui.player.chain
import com.lookee.tv.ui.player.PlayItem
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
import com.lookee.tv.data.GoCoreBridge
import com.lookee.tv.data.LibrarySync
import com.lookee.tv.data.LookeeException
import com.lookee.tv.data.MediaDetails
import com.lookee.tv.data.MediaFile
import com.lookee.tv.data.TmdbResult
import com.lookee.tv.ui.components.Alert
import com.lookee.tv.ui.components.ButtonStyle
import com.lookee.tv.ui.components.FitText
import com.lookee.tv.ui.components.LookeeButton
import com.lookee.tv.ui.components.MarqueeText
import com.lookee.tv.ui.components.MediaSurface
import com.lookee.tv.ui.components.ProgressLine
import com.lookee.tv.ui.components.PreviewImage
import com.lookee.tv.ui.components.RemoteImage
import com.lookee.tv.ui.components.RowTitle
import com.lookee.tv.ui.components.SectionLabel
import com.lookee.tv.ui.components.StatusBadge
import com.lookee.tv.ui.components.Tone
import com.lookee.tv.ui.components.clock
import com.lookee.tv.ui.components.progressOf
import com.lookee.tv.ui.components.runtimeLabel
import com.lookee.tv.ui.components.timeLeft
import com.lookee.tv.ui.home.categoryLabel
import com.lookee.tv.ui.navigation.LocalNavigator
import com.lookee.tv.ui.navigation.Route
import com.lookee.tv.ui.theme.LookeeColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FixMatchState(
    val open: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<TmdbResult> = emptyList(),
    val error: String? = null,
)

data class DetailsState(
    val loading: Boolean = true,
    val details: MediaDetails? = null,
    val error: String? = null,
    val fix: FixMatchState = FixMatchState(),
    /** Set after a Fix Match merged this title into another one. */
    val movedTo: Long? = null,
)

class DetailsViewModel(private val titleId: Long, private val core: GoCoreBridge, private val library: LibrarySync) : ViewModel() {
    private val _state = MutableStateFlow(DetailsState())
    val state: StateFlow<DetailsState> = _state

    init {
        viewModelScope.launch { library.version.collect { load() } }
    }

    private suspend fun load() {
        _state.value = try {
            _state.value.copy(loading = false, details = core.details(titleId), error = null)
        } catch (e: Exception) {
            _state.value.copy(loading = false, error = e.message)
        }
    }

    fun openFix() {
        val d = _state.value.details ?: return
        _state.value = _state.value.copy(fix = FixMatchState(open = true, query = d.title))
        search()
    }

    fun closeFix() {
        _state.value = _state.value.copy(fix = FixMatchState())
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(fix = _state.value.fix.copy(query = q))
    }

    fun search() {
        val q = _state.value.fix.query
        _state.value = _state.value.copy(fix = _state.value.fix.copy(searching = true, error = null))
        viewModelScope.launch {
            val fix = try {
                _state.value.fix.copy(searching = false, results = core.matchCandidates(titleId, q))
            } catch (e: LookeeException) {
                val msg = if (e.code == Codes.NO_TMDB_KEY) "Add a TMDB key first: Settings → Set up from your phone." else e.message
                _state.value.fix.copy(searching = false, error = msg)
            }
            _state.value = _state.value.copy(fix = fix)
        }
    }

    fun pick(r: TmdbResult) {
        viewModelScope.launch {
            try {
                val ref = core.rebind(titleId, r.kind, r.id)
                _state.value = _state.value.copy(fix = FixMatchState(), movedTo = ref.titleId.takeIf { it != titleId })
                library.changed()
            } catch (e: Exception) {
                _state.value = _state.value.copy(fix = _state.value.fix.copy(error = e.message))
            }
        }
    }
}

private fun sectionLabel(section: String) = when (section) {
    "movies" -> "Movies"
    "concerts" -> "Concerts"
    "series" -> "Series"
    "tutorials" -> "Tutorials"
    else -> ""
}

private fun discLabel(kind: String) = when (kind) {
    "bluray" -> "Blu-ray"
    "dvd" -> "DVD"
    "iso" -> "ISO"
    else -> null
}

@Composable
fun DetailsScreen(titleId: Long) {
    val app = LookeeApp.from(LocalContext.current)
    val vm = viewModel(key = "details-$titleId") { DetailsViewModel(titleId, app.core, app.library) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    state.movedTo?.let { id -> LaunchedEffect(id) { nav.replace(Route.Details(id)) } }
    BackHandler(enabled = state.fix.open) { vm.closeFix() }

    val d = state.details
    Box(Modifier.fillMaxSize()) {
        if (d == null) {
            state.error?.let { Alert("Couldn't open this title: $it", modifier = Modifier.padding(48.dp)) }
            return@Box
        }
        PreviewImage(d.backdropUrl, d.files.firstOrNull { it.kind == "file" }?.path.orEmpty(), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to LookeeColors.Bg, 0.38f to LookeeColors.Bg.copy(alpha = 0.92f), 0.65f to LookeeColors.Bg.copy(alpha = 0.35f), 1f to LookeeColors.Bg.copy(alpha = 0.1f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to LookeeColors.Bg)))

        Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 28.dp, end = 48.dp)) {
            Text("${sectionLabel(d.section).ifEmpty { categoryLabel(d.category) }}  ›  ${d.title}", style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted)
            if (d.kind == "tv") SeriesBody(d, vm) else FilmBody(d, vm)
        }
        if (state.fix.open) FixMatchPanel(state.fix, vm, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun Meta(d: MediaDetails) {
    val meta = listOfNotNull(
        d.year.takeIf { it > 0 }?.toString(),
        runtimeLabel(d.runtime).ifEmpty { null },
        d.genres.take(3).joinToString(", ").ifEmpty { null },
    ).joinToString(" · ")
    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyLarge, color = LookeeColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun FilmBody(d: MediaDetails, vm: DetailsViewModel) {
    val nav = LocalNavigator.current
    val file = d.files.firstOrNull()
    val first = remember { FocusRequester() }
    LaunchedEffect(d.id) { runCatching { first.requestFocus() } }
    Column(Modifier.width(600.dp).padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            file?.kind?.let(::discLabel)?.let { StatusBadge(it, Tone.Muted) }
            if (d.files.isNotEmpty()) StatusBadge("On your drive", Tone.Muted)
            if (d.status == "pending" || d.status == "unmatched") StatusBadge("Not matched yet", Tone.Accent)
        }
        FitText(d.title, MaterialTheme.typography.displayLarge, LookeeColors.Text)
        Meta(d)
        if (d.overview.isNotEmpty()) {
            Text(d.overview, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFC9CED6), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val resume = file != null && file.positionMs > 0 && file.durationMs > 0 && file.positionMs < file.durationMs * 0.95
            val play = { from: Long ->
                when {
                    file == null -> nav.toast("This title has no file on the drive.")
                    file.kind != "file" -> nav.toast("Disc rips play in a coming build.")
                    else -> nav.push(
                        Route.Player(
                            PlayItem(
                                path = file.path,
                                title = d.title,
                                subtitle = listOfNotNull(d.year.takeIf { it > 0 }?.toString(), d.genres.firstOrNull()).joinToString(" · "),
                                titleId = d.id,
                                startMs = from,
                            ),
                        ),
                    )
                }
            }
            LookeeButton(
                if (resume) "Resume from ${clock(file!!.positionMs)}" else "Play",
                onClick = { play(if (resume) file!!.positionMs else 0) },
                modifier = Modifier.focusRequester(first),
            )
            if (resume) LookeeButton("Play from start", onClick = { play(0) }, style = ButtonStyle.Quiet)
            LookeeButton("Fix match", onClick = vm::openFix, style = ButtonStyle.Quiet)
        }
        if (file != null) progressOf(file.positionMs, file.durationMs)?.let { ProgressLine(it, Modifier.width(320.dp).clip(RoundedCornerShape(2.dp))) }
    }
    if (d.cast.isNotEmpty()) {
        Column(Modifier.padding(top = 28.dp)) {
            RowTitle("Cast")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(d.cast.take(15), key = { it.order to it.name }) { c ->
                    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        RemoteImage(c.profileUrl, Modifier.size(66.dp).clip(CircleShape).border(1.dp, LookeeColors.Line, CircleShape))
                        Text(c.name, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = LookeeColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** An episode on the drive, with TMDB's name and still when known. */
private data class EpisodeRow(val file: MediaFile, val name: String, val overview: String, val still: String, val runtime: Int)

@Composable
private fun SeriesBody(d: MediaDetails, vm: DetailsViewModel) {
    val nav = LocalNavigator.current
    val episodes = remember(d) {
        d.files.filter { it.episode > 0 }.map { f ->
            val m = d.episodes.firstOrNull { it.season == f.season && it.episode == f.episode }
            EpisodeRow(f, m?.name.orEmpty(), m?.overview.orEmpty(), m?.stillUrl.orEmpty(), 0)
        }
    }
    val seasons = remember(episodes) { episodes.map { it.file.season }.distinct().sorted() }
    // Playing an episode queues the rest of the show after it, so "Next" and autoplay work.
    val ordered = remember(episodes) { episodes.sortedWith(compareBy({ it.file.season }, { it.file.episode })) }
    val playFrom = { ep: EpisodeRow ->
        val items = ordered.dropWhile { it !== ep }.take(30).mapIndexed { i, e ->
            val f = e.file
            val finished = f.durationMs > 0 && f.positionMs >= f.durationMs * 0.95
            PlayItem(
                path = f.path,
                title = e.name.ifEmpty { "Episode ${f.episode}" },
                subtitle = "${d.title} · Season ${f.season} · Episode ${f.episode}",
                titleId = d.id,
                startMs = if (i == 0 && !finished) f.positionMs else 0,
            )
        }
        chain(items)?.let { nav.push(Route.Player(it)) }
    }
    val next = episodes.firstOrNull { !(it.file.durationMs > 0 && it.file.positionMs >= it.file.durationMs * 0.95) }
    var season by rememberSaveable(d.id) { mutableStateOf(next?.file?.season ?: seasons.firstOrNull() ?: 1) }
    val first = remember { FocusRequester() }
    LaunchedEffect(d.id) { runCatching { first.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FitText(d.title, MaterialTheme.typography.displayMedium, LookeeColors.Text, minSize = 28.sp)
            Text(
                listOfNotNull(d.year.takeIf { it > 0 }?.toString(), "${seasons.size} season${if (seasons.size == 1) "" else "s"} on your drive", d.genres.take(2).joinToString(", ").ifEmpty { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = LookeeColors.Muted,
            )
            if (d.overview.isNotEmpty()) Text(d.overview, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC9CED6), maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LookeeButton(
                    next?.let { "Resume S${it.file.season} · E${it.file.episode}" } ?: "Play",
                    onClick = { (next ?: ordered.firstOrNull())?.let(playFrom) },
                    modifier = Modifier.focusRequester(first),
                )
                LookeeButton("Fix match", onClick = vm::openFix, style = ButtonStyle.Quiet)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (seasons.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons) { s -> LookeeButton("Season $s", onClick = { season = s }, style = if (s == season) ButtonStyle.Primary else ButtonStyle.Quiet) }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
                items(episodes.filter { it.file.season == season }, key = { it.file.path }) { ep ->
                    EpisodeCard(ep) { playFrom(ep) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(ep: EpisodeRow, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val f = ep.file
    val done = f.durationMs > 0 && f.positionMs >= f.durationMs * 0.95
    val progress = if (done) null else progressOf(f.positionMs, f.durationMs)
    MediaSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, focusedScale = 1.02f) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(9.dp))) {
                PreviewImage(ep.still, f.path, Modifier.fillMaxSize())
                if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth())
                if (done) {
                    Text("✓", color = LookeeColors.Bg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(CircleShape).background(LookeeColors.Ok.copy(alpha = 0.9f)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val label = buildString {
                    append("Episode ${f.episode}")
                    if (f.episodeEnd > f.episode) append("–${f.episodeEnd}")
                    when {
                        done -> append(" · Watched")
                        progress != null -> append(" · ${timeLeft(f.positionMs, f.durationMs)}")
                    }
                }
                Text(label, style = MaterialTheme.typography.bodySmall, color = if (progress != null) LookeeColors.Accent else LookeeColors.Muted)
                MarqueeText(ep.name.ifEmpty { f.path.substringAfterLast('/') }, MaterialTheme.typography.titleMedium, LookeeColors.Text, active = focused)
                if (focused && ep.overview.isNotEmpty()) {
                    Text(ep.overview, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9CED6), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FixMatchPanel(fix: FixMatchState, vm: DetailsViewModel, modifier: Modifier) {
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    Column(
        modifier
            .fillMaxHeight()
            .width(400.dp)
            .background(LookeeColors.Surface.copy(alpha = 0.98f))
            .border(1.dp, LookeeColors.Line)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Fix match", style = MaterialTheme.typography.headlineLarge, color = LookeeColors.Text)
        SectionLabel("Search TMDB")
        var fieldFocused by remember { mutableStateOf(false) }
        BasicTextField(
            value = fix.query,
            onValueChange = vm::setQuery,
            singleLine = true,
            textStyle = TextStyle(color = LookeeColors.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(LookeeColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .onFocusChanged { fieldFocused = it.isFocused }
                .clip(RoundedCornerShape(10.dp))
                .background(LookeeColors.Surface2)
                .border(if (fieldFocused) 2.dp else 1.dp, if (fieldFocused) LookeeColors.Accent else LookeeColors.Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        LookeeButton(if (fix.searching) "Searching…" else "Search", onClick = { vm.search() }, style = ButtonStyle.Quiet)
        fix.error?.let { Alert(it) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(fix.results, key = { "${it.kind}/${it.id}" }) { r ->
                var focused by remember { mutableStateOf(false) }
                MediaSurface(onClick = { vm.pick(r) }, modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, focusedScale = 1.02f) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RemoteImage(r.posterUrl, Modifier.width(40.dp).height(60.dp).clip(RoundedCornerShape(6.dp)))
                        Column(Modifier.weight(1f)) {
                            MarqueeText(r.title, MaterialTheme.typography.titleSmall, LookeeColors.Text, active = focused)
                            Text(listOfNotNull(r.year.takeIf { it > 0 }?.toString(), if (r.kind == "tv") "Series" else "Movie").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted)
                        }
                    }
                }
            }
        }
    }
}
