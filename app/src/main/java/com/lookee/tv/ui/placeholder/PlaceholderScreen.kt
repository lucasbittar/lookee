package com.lookee.tv.ui.placeholder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lookee.tv.ui.components.EmptyState
import com.lookee.tv.ui.navigation.Destination

/** Destinations whose screens arrive in Phase 4, after the layout mockups are agreed. */
@Composable
fun PlaceholderScreen(destination: Destination) {
    val (title, body) = when (destination) {
        Destination.Movies -> "Movies" to "Your films, with posters, cast and synopses from TMDB, will live here."
        Destination.Series -> "Series" to "Shows grouped by season, with episode stills and what to watch next."
        Destination.Concerts -> "Concerts" to "Live shows and music films from your drive."
        Destination.Tutorials -> "Tutorials" to "Browse lesson folders exactly as they are on the drive, no matching needed."
        Destination.Discover -> "Discover" to "Trending titles and anything not on your drive, streamed on demand."
        else -> destination.label to ""
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(title = title, body = "$body\nArriving in the next build.")
    }
}
