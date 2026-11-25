package com.lookee.tv.ui.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lookee.tv.LookeeApp
import com.lookee.tv.data.Category
import com.lookee.tv.data.FolderEntry
import com.lookee.tv.data.GoCoreBridge
import com.lookee.tv.data.LibrarySync
import com.lookee.tv.data.MediaTitle
import com.lookee.tv.data.SeriesView
import com.lookee.tv.data.ShowProgress
import com.lookee.tv.ui.components.ButtonStyle
import com.lookee.tv.ui.components.EmptyState
import com.lookee.tv.ui.components.LookeeButton
import com.lookee.tv.ui.components.MarqueeText
import com.lookee.tv.ui.components.MediaSurface
import com.lookee.tv.ui.components.PosterCard
import com.lookee.tv.ui.components.ProgressLine
import com.lookee.tv.ui.components.PreviewImage
import com.lookee.tv.ui.components.RemoteImage
import com.lookee.tv.ui.components.RowPadding
import com.lookee.tv.ui.components.RowTitle
import com.lookee.tv.ui.components.ScreenInset
import com.lookee.tv.ui.components.ScreenHeader
import com.lookee.tv.ui.components.StillCard
import com.lookee.tv.ui.components.clock
import com.lookee.tv.ui.components.progressOf
import com.lookee.tv.ui.components.runtimeLabel
import com.lookee.tv.ui.components.timeLeft
import com.lookee.tv.ui.navigation.Destination
import com.lookee.tv.ui.navigation.LocalNavigator
import com.lookee.tv.ui.navigation.Route
import com.lookee.tv.ui.theme.BebasNeue
import com.lookee.tv.ui.theme.CategoryGlow
import com.lookee.tv.ui.theme.CategoryLook
import com.lookee.tv.ui.theme.Fraunces
import com.lookee.tv.ui.theme.JetBrainsMono
import com.lookee.tv.ui.theme.LookeeColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Loads something from the core and reloads whenever the library changes. */
class Loader<T>(library: LibrarySync, initial: T, private val load: suspend () -> T) : ViewModel() {
    data class State<T>(val loading: Boolean, val data: T, val error: String?)

    private val _state = MutableStateFlow(State(true, initial, null))
    val state: StateFlow<State<T>> = _state

    init {
        viewModelScope.launch { library.version.collect { reload() } }
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = try {
                State(false, load(), null)
            } catch (e: Exception) {
                _state.value.copy(loading = false, error = e.message)
            }
        }
    }
}

@Composable
private fun <T> rememberLoader(key: String, initial: T, load: suspend (GoCoreBridge) -> T): Loader.State<T> {
    val app = LookeeApp.from(LocalContext.current)
    val vm: Loader<T> = viewModel(key = key) { Loader(app.library, initial) { load(app.core) } }
    val state by vm.state.collectAsStateWithLifecycle()
    return state
}

@Composable
private fun EmptyLibrary(title: String, body: String) {
    val nav = LocalNavigator.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(title, body, action = { LookeeButton("Open Settings", onClick = { nav.goToTab(Destination.Settings) }) })
    }
}

// ---- Movies: "Cinema" ----

private enum class MovieSort(val label: String) { Recent("Recently added"), Az("A–Z"), Year("Year") }

@Composable
fun MoviesScreen() {
    val state = rememberLoader("movies", emptyList<MediaTitle>()) { it.libraryView("movies") }
    val nav = LocalNavigator.current
    var sort by rememberSaveable { mutableStateOf(MovieSort.Recent) }
    val movies = remember(state.data, sort) {
        when (sort) {
            MovieSort.Recent -> state.data.sortedByDescending { it.addedAt }
            MovieSort.Az -> state.data.sortedBy { it.title.lowercase() }
            MovieSort.Year -> state.data.sortedByDescending { it.year }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(360.dp)) {
            CategoryGlow(CategoryLook.Movies, Offset(960f, -200f), radius = 1100f)
        }
        if (!state.loading && movies.isEmpty()) {
            EmptyLibrary("No movies yet", "Add your Movies folder in Settings and lookee will fetch posters and details.")
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            kicker = CategoryLook.Movies.kicker,
            title = "Movies",
            tint = CategoryLook.Movies.tint,
            titleStyle = MaterialTheme.typography.displayMedium.copy(fontFamily = Fraunces, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = ScreenInset, end = ScreenInset, top = 16.dp, bottom = 16.dp),
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MovieSort.entries.forEach { s ->
                        LookeeButton(s.label, onClick = { sort = s }, style = if (s == sort) ButtonStyle.Primary else ButtonStyle.Quiet)
                    }
                }
            },
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            items(movies, key = { it.id }) { m ->
                PosterCard(
                    title = m.title,
                    caption = listOfNotNull(m.year.takeIf { it > 0 }?.toString(), runtimeLabel(m.runtime).ifEmpty { null }).joinToString(" · "),
                    posterUrl = m.posterUrl,
                    width = 108.dp,
                    videoPath = m.previewPath,
                    onClick = { nav.push(Route.Details(m.id)) },
                )
            }
        }
        }
    }
}

// ---- Series: "Up next" ----

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeriesScreen() {
    val state = rememberLoader("series", SeriesView()) { it.series() }
    val nav = LocalNavigator.current
    val look = CategoryLook.Series
    Box(Modifier.fillMaxSize()) {
        CategoryGlow(look, Offset(1700f, -100f))
        if (!state.loading && state.data.all.isEmpty()) {
            EmptyLibrary("No shows yet", "Add your TV Shows folder in Settings. Seasons and episodes are matched automatically.")
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
        ScreenHeader(look.kicker, "Series", look.tint, MaterialTheme.typography.displayMedium, Modifier.padding(start = ScreenInset, end = ScreenInset, top = 16.dp, bottom = 20.dp))
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            if (state.data.inProgress.isNotEmpty()) {
                item {
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = RowPadding) {
                        items(state.data.inProgress, key = { it.id }) { show -> UpNextCard(show, look.tint) { nav.push(Route.Details(show.id)) } }
                    }
                }
            }
            item {
                Column {
                    RowTitle("All shows", inset = ScreenInset)
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = RowPadding) {
                        items(state.data.all, key = { it.id }) { show ->
                            StillCard(
                                title = show.title,
                                caption = if (show.total > 0) "Season ${show.progressSeason} · ${show.watched} of ${show.total} watched" else show.year.takeIf { it > 0 }?.toString().orEmpty(),
                                imageUrl = show.backdropUrl,
                                videoPath = show.next?.key ?: show.previewPath,
                                width = 200.dp,
                                onClick = { nav.push(Route.Details(show.id)) },
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun UpNextCard(show: ShowProgress, tint: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val next = show.next
    Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MediaSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().height(169.dp).onFocusChanged { focused = it.isFocused }, focusedScale = 1.05f) {
            PreviewImage(next?.stillUrl?.ifEmpty { null } ?: show.backdropUrl, next?.key ?: show.previewPath, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, LookeeColors.Bg.copy(alpha = 0.92f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                MarqueeText(show.title, MaterialTheme.typography.headlineMedium, LookeeColors.Text, active = focused)
                if (next != null) {
                    MarqueeText("Next · S${next.season} E${next.episode} ${next.name}".trim(), MaterialTheme.typography.bodySmall, Color(0xFFC9CED6), active = focused)
                }
            }
            if (next != null) progressOf(next.positionMs, next.durationMs)?.let { ProgressLine(it, Modifier.align(Alignment.BottomStart).fillMaxWidth()) }
        }
        // One segment per episode on the drive in this season.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(show.total.coerceAtMost(30)) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i < show.watched) tint else LookeeColors.Line),
                )
            }
        }
        Text("Season ${show.progressSeason} · ${show.watched} of ${show.total} watched", style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted)
    }
}

// ---- Concerts: "Live" ----

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConcertsScreen() {
    val state = rememberLoader("concerts", emptyList<MediaTitle>()) { it.libraryView("concerts") }
    val nav = LocalNavigator.current
    val look = CategoryLook.Concerts
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0E))) {
        // Two stage lights.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(look.tint.copy(alpha = 0.16f), Color.Transparent), center = Offset(380f, -80f), radius = 900f)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(LookeeColors.Accent.copy(alpha = 0.09f), Color.Transparent), center = Offset(1600f, -80f), radius = 900f)))
        if (!state.loading && state.data.isEmpty()) {
            EmptyLibrary("No concerts yet", "Concert films in your Movies folder show up here automatically, or add a Concerts folder in Settings.")
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(start = ScreenInset, top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Concerts", fontFamily = BebasNeue, fontSize = 64.sp, color = LookeeColors.Text)
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(look.tint.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(look.tint))
                        Text("LIVE", style = MaterialTheme.typography.labelMedium, color = look.tint)
                    }
                }
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            // Featured: the newest additions as big gig-poster cards.
            item {
                LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = RowPadding) {
                    items(state.data.sortedByDescending { it.addedAt }.take(12), key = { it.id }) { c -> GigCard(c) { nav.push(Route.Details(c.id)) } }
                }
            }
            // Everything, A–Z, as name tags: quick to scan with fifty-plus concerts.
            item {
                Column {
                    RowTitle("All concerts", inset = ScreenInset)
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = RowPadding) {
                        items(state.data, key = { it.id }) { c -> ArtistChip(c.title) { nav.push(Route.Details(c.id)) } }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun GigCard(c: MediaTitle, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    MediaSurface(onClick = onClick, modifier = Modifier.width(274.dp).height(210.dp).onFocusChanged { focused = it.isFocused }, focusedScale = 1.05f) {
        PreviewImage(c.backdropUrl.ifEmpty { c.posterUrl }, c.previewPath, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF20B0A0E)), startY = 120f)))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            MarqueeText(c.title, MaterialTheme.typography.displaySmall.copy(fontFamily = BebasNeue, fontWeight = FontWeight.Normal, fontSize = 40.sp, letterSpacing = 0.sp), LookeeColors.Text, active = focused)
            Text(listOfNotNull("Live", c.year.takeIf { it > 0 }?.toString()).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9CED6))
        }
    }
}

@Composable
private fun ArtistChip(name: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    MediaSurface(onClick = onClick, modifier = Modifier.onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(11.dp), focusedScale = 1.06f) {
        MarqueeText(
            name,
            MaterialTheme.typography.headlineMedium.copy(fontFamily = BebasNeue, fontWeight = FontWeight.Normal, fontSize = 24.sp, letterSpacing = 0.sp),
            LookeeColors.Text,
            active = focused,
            modifier = Modifier.width(220.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

// ---- Tutorials: "Workshop" ----

private data class Folder(val name: String, val path: String)

@Composable
fun TutorialsScreen() {
    val roots = rememberLoader("tutorial-roots", emptyList<Folder>()) { core ->
        core.roots().filter { it.category == Category.TUTORIALS }.map { Folder(it.path.substringAfterLast('/'), it.path) }
    }
    val look = CategoryLook.Tutorials
    var trail by rememberSaveable { mutableStateOf(listOf<String>()) } // folder paths, deepest last
    // A single lessons folder opens straight away: no extra step.
    LaunchedEffect(roots.data) {
        if (trail.isEmpty() && roots.data.size == 1) trail = listOf(roots.data.first().path)
    }
    val current = trail.lastOrNull()
    val listing = rememberLoader("browse:${current.orEmpty()}", emptyList<FolderEntry>()) { core -> if (current == null) emptyList() else core.browse(current) }
    val nav = LocalNavigator.current
    // Entering a folder replaces the rows, so put focus on the first lesson (or folder) of
    // the new listing instead of letting it fall back to the tabs.
    val firstLesson = remember { FocusRequester() }
    val firstFolder = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }
    BackHandler(enabled = trail.size > (if (roots.data.size == 1) 1 else 0)) {
        entered = true
        trail = trail.dropLast(1)
    }

    Box(Modifier.fillMaxSize()) {
        CategoryGlow(look, Offset(-100f, 1100f))
        if (!roots.loading && roots.data.isEmpty()) {
            EmptyLibrary("No tutorials yet", "Add a lessons folder in Settings as Tutorials. lookee shows it exactly as it is on the drive.")
            return@Box
        }
        val folders = if (current == null) roots.data else listing.data.filter { it.isDir }.map { Folder(it.name, it.path) }
        val lessons = listing.data.filter { !it.isDir }
        LaunchedEffect(listing.data, entered) {
            if (entered && !listing.loading) {
                runCatching { if (lessons.isNotEmpty()) firstLesson.requestFocus() else firstFolder.requestFocus() }
            }
        }
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 16.dp)) {
            Text(
                trail.map { it.substringAfterLast('/') }.ifEmpty { listOf("All lesson folders") }.joinToString(" / "),
                fontFamily = JetBrainsMono,
                fontSize = 13.sp,
                color = look.tint,
            )
            Text("Tutorials", style = MaterialTheme.typography.displayMedium, color = LookeeColors.Text, modifier = Modifier.padding(bottom = 20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                LazyColumn(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { RowTitle("Folders") }
                    itemsIndexed(folders, key = { _, f -> f.path }) { i, f ->
                        FolderRow(f.name, look.tint, if (i == 0) Modifier.focusRequester(firstFolder) else Modifier) {
                            entered = true
                            trail = trail + f.path
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
                    item {
                        RowTitle(if (current == null) "Pick a folder" else "${current.substringAfterLast('/')} · ${lessons.size} lessons · ${lessons.count { it.durationMs > 0 && it.positionMs >= it.durationMs * 0.95 }} done")
                    }
                    itemsIndexed(lessons, key = { _, l -> l.path }) { i, l ->
                        LessonRow(i + 1, l, look.tint, if (i == 0) Modifier.focusRequester(firstLesson) else Modifier) { nav.toast("The player arrives in the next build.") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    MediaSurface(onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(10.dp), focusedScale = 1.03f) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(width = 16.dp, height = 12.dp).clip(RoundedCornerShape(3.dp)).border(2.dp, if (focused) tint else LookeeColors.Muted, RoundedCornerShape(3.dp)))
            MarqueeText(name, MaterialTheme.typography.titleSmall, LookeeColors.Text, active = focused)
        }
    }
}

@Composable
private fun LessonRow(number: Int, lesson: FolderEntry, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val done = lesson.durationMs > 0 && lesson.positionMs >= lesson.durationMs * 0.95
    MediaSurface(onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(10.dp), focusedScale = 1.02f) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("%02d".format(number), fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = tint)
            // Lessons have no artwork anywhere: a frame from the video is their picture.
            PreviewImage("", lesson.path, Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(7.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                MarqueeText(lesson.name.substringBeforeLast('.'), MaterialTheme.typography.titleSmall, LookeeColors.Text, active = focused)
                if (!done) progressOf(lesson.positionMs, lesson.durationMs)?.let { ProgressLine(it, Modifier.width(200.dp), height = 3.dp) }
            }
            if (lesson.hasSubtitle) {
                Text("CC", style = MaterialTheme.typography.labelMedium, color = Color(0xFFC9CED6), modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(LookeeColors.Line).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            val right = when {
                done -> "✓"
                lesson.positionMs > 0 && lesson.durationMs > 0 -> timeLeft(lesson.positionMs, lesson.durationMs)
                lesson.durationMs > 0 -> clock(lesson.durationMs)
                else -> ""
            }
            Text(right, fontFamily = JetBrainsMono, fontSize = 13.sp, color = if (done) LookeeColors.Ok else LookeeColors.Muted)
        }
    }
}
