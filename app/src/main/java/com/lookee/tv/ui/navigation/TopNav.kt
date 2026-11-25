package com.lookee.tv.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.lookee.tv.ui.components.Wordmark
import com.lookee.tv.ui.theme.LookeeColors
import com.lookee.tv.ui.theme.LookeeShapes

/** The seven top-level destinations (CLAUDE.md §5). */
enum class Destination(val label: String) {
    Home("Home"),
    Movies("Movies"),
    Series("Series"),
    Concerts("Concerts"),
    Tutorials("Tutorials"),
    Discover("Discover"),
    Settings("Settings"),
}

/**
 * The top navigation: the wordmark, then one pill per destination. A tab is selected as
 * soon as it takes focus (the usual TV behaviour), and focus returns to the selected tab
 * when the user comes back up from the content.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopNav(
    selected: Destination,
    onSelect: (Destination) -> Unit,
    requesters: Map<Destination, FocusRequester>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark(markSize = 30.dp)
        Spacer(Modifier.width(40.dp))
        Row(
            // Coming up from the content always lands on the selected tab. Without this, focus
            // went to the tab nearest the item (Home, from Settings' left column), and since
            // tabs select on focus, Up switched screens.
            modifier = Modifier
                .focusProperties { enter = { requesters.getValue(selected) } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Destination.entries.forEach { dest ->
                NavTab(
                    label = dest.label,
                    selected = dest == selected,
                    onFocused = { onSelect(dest) },
                    modifier = Modifier.focusRequester(requesters.getValue(dest)),
                )
            }
        }
    }
}

@Composable
private fun NavTab(label: String, selected: Boolean, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val textColor by animateColorAsState(
        when {
            focused -> LookeeColors.AccentInk
            selected -> LookeeColors.Text
            else -> LookeeColors.Muted
        },
        label = "tabText",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            // OK on a tab dives into its content.
            onClick = { focusManager.moveFocus(FocusDirection.Down) },
            modifier = modifier.onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
            shape = ClickableSurfaceDefaults.shape(LookeeShapes.Pill),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = LookeeColors.Accent,
                pressedContainerColor = LookeeColors.Accent,
            ),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(LookeeColors.AccentSoft, 10.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = textColor,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        // The selected tab keeps a small accent dot while focus is down in the content.
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(width = 18.dp, height = 3.dp)
                .clip(LookeeShapes.Pill)
                .background(if (selected && !focused) LookeeColors.Accent else Color.Transparent),
        )
    }
}

/**
 * Height of the top navigation, which floats over the screens with no background of its own
 * (as in the mockups). Screens draw their backdrops and glows from the very top and start
 * their content this far down.
 */
val LocalNavInset = compositionLocalOf { 88.dp }

@Composable
fun rememberNavRequesters(): Map<Destination, FocusRequester> =
    remember { Destination.entries.associateWith { FocusRequester() } }

/** Spacing every screen uses below the navigation. */
val ScreenPadding = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 32.dp)
