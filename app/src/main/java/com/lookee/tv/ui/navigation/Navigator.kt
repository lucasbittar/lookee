package com.lookee.tv.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.lookee.tv.data.DiscoverItem
import com.lookee.tv.data.TorrentResult

/** Screens pushed over the tabs. They hide the top navigation; Back pops them. */
sealed interface Route {
    data class Details(val titleId: Long) : Route
    data class Sources(val item: DiscoverItem) : Route
    data class Buffering(val title: String, val result: TorrentResult) : Route
    data object AddFolder : Route
}

/** A tiny back stack. The tab screens stay composed underneath, so focus returns to where it was. */
class Navigator {
    val stack = mutableStateListOf<Route>()
    var selectTab: (Destination) -> Unit = {}

    /** A short message shown at the bottom of the screen for a few seconds. */
    var message by mutableStateOf<String?>(null)
        private set

    fun toast(text: String) {
        message = text
    }

    fun clearMessage() {
        message = null
    }

    fun push(route: Route) {
        stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Replaces the top route (Details after a Fix Match merge, for example). */
    fun replace(route: Route) {
        if (stack.isNotEmpty()) stack[stack.lastIndex] = route else stack.add(route)
    }

    fun goToTab(dest: Destination) {
        stack.clear()
        selectTab(dest)
    }
}

val LocalNavigator = staticCompositionLocalOf { Navigator() }
