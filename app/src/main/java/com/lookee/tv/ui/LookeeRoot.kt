package com.lookee.tv.ui

import com.lookee.tv.ui.player.PlayerScreen
import android.Manifest
import com.lookee.tv.ui.navigation.LocalNavInset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.lookee.tv.ui.splash.BootSplash
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.withFrameNanos
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
import com.lookee.tv.ui.navigation.FocusMemory
import com.lookee.tv.ui.navigation.LocalFocusMemory
import com.lookee.tv.ui.navigation.LocalNavigator
import com.lookee.tv.ui.navigation.FollowFocusScroll
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import com.lookee.tv.ui.navigation.Navigator
import com.lookee.tv.ui.navigation.Route
import com.lookee.tv.ui.navigation.TopNav
import com.lookee.tv.ui.navigation.ToastHost
import com.lookee.tv.ui.navigation.SyncToasts
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
    val pushedRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val boot by app.boot.state.collectAsState()
    // Shown on a cold start only: reopening the app while the process lives skips it.
    var splash by remember { mutableStateOf(!app.boot.state.value.done) }

    // Library folders are read by path, which needs the storage permission (Decision 1).
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app.library.autoScan(force = true)
    }
    LaunchedEffect(Unit) {
        requesters.getValue(Destination.Home).requestFocus()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val hasFolders = runCatching { app.core.roots().isNotEmpty() }.getOrDefault(false)
        when {
            granted -> {} // MainActivity.onStart syncs
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
    val focusMemory = remember { FocusMemory() }
    val pushed = navigator.stack.lastOrNull()
    LaunchedEffect(pushed == null) {
        if (pushed == null && !focusMemory.restore()) runCatching { tabsRequester.requestFocus() }
    }
    // Opening a screen moves focus into it. Without this, focus stayed on the hidden button
    // that opened it, and OK kept pressing that button (Add folder looked stuck).
    LaunchedEffect(pushed) {
        if (pushed != null) {
            focusManager.clearFocus(force = true)
            withFrameNanos { }
            runCatching { pushedRequester.requestFocus() }
        }
    }

    var navHeight by remember { mutableStateOf(88.dp) }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalNavigator provides navigator, LocalBringIntoViewSpec provides FollowFocusScroll, LocalNavInset provides navHeight) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LookeeColors.Bg)
                // Home is composed under the splash; keys mustn't move focus there unseen.
                .onPreviewKeyEvent { splash && it.key != Key.Back },
        ) {
            // The tabs stay composed under pushed screens, so their scroll and focus survive.
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(tabsRequester)
                    .focusRestorer { requesters.getValue(selected) }
                    // Safety net: lost focus re-enters on the selected tab, never Home.
                    .focusProperties {
                        // Covered by a pushed screen: the tabs can't take focus at all.
                        enter = { if (navigator.stack.isNotEmpty()) FocusRequester.Cancel else requesters.getValue(selected) }
                    }
                    .focusGroup()
                    .background(
                        Brush.radialGradient(
                            listOf(LookeeColors.Accent.copy(alpha = 0.05f), LookeeColors.Bg.copy(alpha = 0f)),
                            radius = 1400f,
                            center = Offset(0f, 0f),
                        ),
                    ),
            ) {
                // The nav floats over the screen: backdrops and glows run up behind it.
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        label = "destination",
                        modifier = Modifier
                            .fillMaxSize()
                            .onFocusChanged { contentHasFocus = it.hasFocus },
                    ) { dest ->
                        CompositionLocalProvider(LocalFocusMemory provides focusMemory) {
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
                    TopNav(
                        selected = selected,
                        onSelect = { selected = it },
                        requesters = requesters,
                        modifier = Modifier.onSizeChanged { navHeight = with(density) { it.height.toDp() } },
                    )
                }
            }

            ToastHost(navigator, Modifier.align(Alignment.BottomCenter).zIndex(2f))
            // After the splash, so the "syncing" toast isn't spent behind it.
            if (!splash) SyncToasts(app.library, navigator)

            if (pushed != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(LookeeColors.Bg)
                        // Focus stays inside the pushed screen until Back.
                        .focusRequester(pushedRequester)
                        .focusProperties { exit = { FocusRequester.Cancel } }
                        .focusGroup(),
                ) {
                    when (pushed) {
                        is Route.Details -> DetailsScreen(pushed.titleId)
                        is Route.Sources -> SourcesScreen(pushed.item)
                        is Route.Buffering -> BufferingScreen(pushed.title, pushed.result)
                        Route.AddFolder -> AddFolderScreen()
                        is Route.Player -> PlayerScreen(pushed.item)
                    }
                }
            }

            AnimatedVisibility(visible = splash, enter = EnterTransition.None, exit = fadeOut(tween(360)), modifier = Modifier.zIndex(3f)) {
                BootSplash(boot) {
                    splash = false
                    // The launch logo stays behind everything otherwise: drop it to save overdraw.
                    (context as? Activity)?.window?.setBackgroundDrawable(ColorDrawable(LookeeColors.Bg.toArgb()))
                }
            }
        }
    }
}
