package com.lookee.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Brush
import com.lookee.tv.ui.home.HomeScreen
import com.lookee.tv.ui.navigation.Destination
import com.lookee.tv.ui.navigation.TopNav
import com.lookee.tv.ui.navigation.rememberNavRequesters
import com.lookee.tv.ui.placeholder.PlaceholderScreen
import com.lookee.tv.ui.settings.SettingsScreen
import com.lookee.tv.ui.theme.LookeeColors

/** The app frame: top navigation over the selected destination. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LookeeRoot() {
    var selected by rememberSaveable { mutableStateOf(Destination.Home) }
    var contentHasFocus by remember { mutableStateOf(false) }
    val requesters = rememberNavRequesters()

    // Start with focus on the Home tab.
    LaunchedEffect(Unit) { requesters.getValue(Destination.Home).requestFocus() }

    // Back: from the content, return to the tabs; from a tab, go Home; from Home, leave the app.
    BackHandler(enabled = contentHasFocus || selected != Destination.Home) {
        if (contentHasFocus) {
            requesters.getValue(selected).requestFocus()
        } else {
            selected = Destination.Home
            requesters.getValue(Destination.Home).requestFocus()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Safety net: if focus is ever lost (a focused item disappears), it re-enters on
            // the selected tab, never on the first tab, which would switch to Home.
            .focusProperties { enter = { requesters.getValue(selected) } }
            .focusGroup()
            .background(LookeeColors.Bg)
            // A faint warm glow in the top-left corner, like the banner.
            .background(Brush.radialGradient(listOf(LookeeColors.Accent.copy(alpha = 0.06f), LookeeColors.Bg.copy(alpha = 0f)), radius = 1400f, center = androidx.compose.ui.geometry.Offset(0f, 0f))),
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
                    Destination.Home -> HomeScreen(onOpenSettings = {
                        selected = Destination.Settings
                    })
                    Destination.Settings -> SettingsScreen()
                    else -> PlaceholderScreen(dest)
                }
            }
        }
    }
}
