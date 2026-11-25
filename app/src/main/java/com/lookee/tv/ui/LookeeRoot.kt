package com.lookee.tv.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.lookee.tv.LookeeApp
import com.lookee.tv.ui.details.DetailsScreen
import com.lookee.tv.ui.discover.BufferingScreen
import com.lookee.tv.ui.discover.DiscoverScreen
import com.lookee.tv.ui.discover.SourcesScreen
import com.lookee.tv.ui.home.HomeScreen
import com.lookee.tv.ui.library.ConcertsScreen
import com.lookee.tv.ui.library.MoviesScreen
import com.lookee.tv.ui.library.SeriesScreen
import com.lookee.tv.ui.library.TutorialsScreen
import com.lookee.tv.ui.navigation.Destination
import com.lookee.tv.ui.navigation.LocalNavigator
import com.lookee.tv.ui.navigation.MinimalScroll
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import com.lookee.tv.ui.navigation.Navigator
import com.lookee.tv.ui.navigation.Route
import com.lookee.tv.ui.navigation.TopNav
import com.lookee.tv.ui.navigation.rememberNavRequesters
import com.lookee.tv.ui.settings.AddFolderScreen
import com.lookee.tv.ui.settings.SettingsScreen
import com.lookee.tv.ui.theme.LookeeColors

/** The app frame: top navigation over the selected tab, and pushed screens over both. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LookeeRoot() {
    val context = LocalContext.current
    val app = LookeeApp.from(context)
    var selected by rememberSaveable { mutableStateOf(Destination.Home) }
    var contentHasFocus by remember { mutableStateOf(false) }
    val requesters = rememberNavRequesters()
    val navigator = remember { Navigator() }
    navigator.selectTab = { dest ->
        selected = dest
        requesters.getValue(dest).requestFocus()
    }
    val tabsRequester = remember { FocusRequester() }

    // Library folders are read by path, which needs the storage permission (Decision 1).
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app.library.scanOnLaunch()
    }
    LaunchedEffect(Unit) {
        requesters.getValue(Destination.Home).requestFocus()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val hasFolders = runCatching { app.core.roots().isNotEmpty() }.getOrDefault(false)
        when {
            granted -> app.library.scanOnLaunch()
            hasFolders -> permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // Back: pop a pushed screen; else from the content to the tabs; else Home; else leave.
    BackHandler(enabled = navigator.stack.isNotEmpty() || contentHasFocus || selected != Destination.Home) {
        when {
            navigator.pop() -> {}
            contentHasFocus -> requesters.getValue(selected).requestFocus()
            else -> {
                selected = Destination.Home
                requesters.getValue(Destination.Home).requestFocus()
            }
        }
    }
    // Back on the tabs after a pop: focus returns to the card that opened the screen.
    val pushed = navigator.stack.lastOrNull()
    LaunchedEffect(pushed == null) {
        if (pushed == null) runCatching { tabsRequester.requestFocus() }
    }

    CompositionLocalProvider(LocalNavigator provides navigator, LocalBringIntoViewSpec provides MinimalScroll) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LookeeColors.Bg),
        ) {
            // The tabs stay composed under pushed screens, so their scroll and focus survive.
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(tabsRequester)
                    .focusRestorer { requesters.getValue(selected) }
                    // Safety net: lost focus re-enters on the selected tab, never Home.
                    .focusProperties { enter = { requesters.getValue(selected) } }
                    .focusGroup()
                    .background(
                        Brush.radialGradient(
                            listOf(LookeeColors.Accent.copy(alpha = 0.05f), LookeeColors.Bg.copy(alpha = 0f)),
                            radius = 1400f,
                            center = Offset(0f, 0f),
                        ),
                    ),
            ) {
                Column(Modifier.fillMaxSize()) {
                    TopNav(selected = selected, onSelect = { selected = it }, requesters = requesters)
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        label = "destination",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onFocusChanged { contentHasFocus = it.hasFocus },
                    ) { dest ->
                        when (dest) {
                            Destination.Home -> HomeScreen()
                            Destination.Movies -> MoviesScreen()
                            Destination.Series -> SeriesScreen()
                            Destination.Concerts -> ConcertsScreen()
                            Destination.Tutorials -> TutorialsScreen()
                            Destination.Discover -> DiscoverScreen()
                            Destination.Settings -> SettingsScreen()
                        }
                    }
                }
            }

            navigator.message?.let { text ->
                LaunchedEffect(text) {
                    kotlinx.coroutines.delay(3500)
                    navigator.clearMessage()
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LookeeColors.Text,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .zIndex(2f)
                        .clip(RoundedCornerShape(50))
                        .background(LookeeColors.Surface2)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }

            if (pushed != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(LookeeColors.Bg)
                        // Focus stays inside the pushed screen until Back.
                        .focusProperties { exit = { FocusRequester.Cancel } }
                        .focusGroup(),
                ) {
                    when (pushed) {
                        is Route.Details -> DetailsScreen(pushed.titleId)
                        is Route.Sources -> SourcesScreen(pushed.item)
                        is Route.Buffering -> BufferingScreen(pushed.title, pushed.result)
                        Route.AddFolder -> AddFolderScreen()
                    }
                }
            }
        }
    }
}
