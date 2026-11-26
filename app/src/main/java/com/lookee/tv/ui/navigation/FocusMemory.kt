package com.lookee.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * The card that last had focus on the tabs, so closing a pushed screen (Details, the player)
 * puts focus back on it. Compose's own restore didn't survive opening a screen: focus is
 * cleared then (see LookeeRoot), and the fallback search on the way back picked whichever
 * card it met first, often a row lower.
 */
class FocusMemory {
    var last: FocusRequester? = null
        private set

    fun remember(requester: FocusRequester) {
        last = requester
    }

    /** Focuses the remembered card; false when there is none or it has left the screen. */
    fun restore(): Boolean {
        val r = last ?: return false
        return runCatching { r.requestFocus() }.isSuccess
    }
}

/** Null under pushed screens: only the tabs' cards are remembered. */
val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }

/** Lets this focusable (a card) be put back in focus after a pushed screen closes. */
@Composable
fun Modifier.rememberedFocus(): Modifier {
    val memory = LocalFocusMemory.current ?: return this
    val requester = remember { FocusRequester() }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) memory.remember(requester) }
}
