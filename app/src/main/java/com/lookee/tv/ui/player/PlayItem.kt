package com.lookee.tv.ui.player

/**
 * What the player plays. [path] is a file on the drive or the core's loopback stream URL.
 * Progress is saved under [path] (the key Continue Watching resumes from).
 */
data class PlayItem(
    val path: String,
    val title: String,
    /** The line above the title: "Doctor Who · Season 1 · Episode 3", a lesson's folder, a year. */
    val subtitle: String = "",
    val titleId: Long = 0,
    val startMs: Long = 0,
    /** A torrent stream: the player owns it and stops it (deleting its pieces) when it closes. */
    val stream: Boolean = false,
    /** Played when this one ends, or from the "Next" button. */
    val next: PlayItem? = null,
) {
    val source: String get() = if (stream) "Streaming" else "On your drive"
}

/** Builds a chain where each item's [PlayItem.next] is the following one. */
fun chain(items: List<PlayItem>): PlayItem? =
    items.foldRight(null as PlayItem?) { item, next -> item.copy(next = next) }
