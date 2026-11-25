package com.lookee.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.lookee.tv.data.LibrarySync
import com.lookee.tv.data.ScanUpdate

/**
 * Toasts for library syncs: one when a sync starts, one when it ends with what changed.
 * Progress itself stays on Home's sync pill; these say "it started" and "it's done" from any screen.
 */
@Composable
fun SyncToasts(library: LibrarySync, navigator: Navigator) {
    LaunchedEffect(library) {
        var running = false
        library.scan.collect { update ->
            if (update == null) return@collect
            if (!update.isFinal) {
                if (!running) navigator.toast("Syncing your library…", ToastKind.Working)
                running = true
            } else if (running) {
                running = false
                finishedMessage(update)?.let { (text, kind) -> navigator.toast(text, kind) }
            }
        }
    }
}

/** Null when there's nothing worth saying (no folders yet). */
internal fun finishedMessage(update: ScanUpdate): Pair<String, ToastKind>? {
    if (update.phase == "failed") return "Sync failed · ${update.error.ifBlank { "see Settings → Diagnostics" }}" to ToastKind.Problem
    val report = update.report ?: return "Library synced" to ToastKind.Done
    val roots = report.roots
    if (roots.isEmpty()) return null
    if (roots.all { it.unavailable }) return "Drive not connected · showing your saved library" to ToastKind.Problem
    val added = roots.sumOf { it.added }
    val removed = roots.sumOf { it.removed }
    val changes = listOfNotNull(
        added.takeIf { it > 0 }?.let { "$it new" },
        removed.takeIf { it > 0 }?.let { "$it removed" },
    )
    if (report.needsApiKey && report.unmatched > 0) {
        return "Library synced · add a TMDB key in Settings for posters" to ToastKind.Problem
    }
    return if (changes.isEmpty()) "Library up to date" to ToastKind.Done
    else "Library synced · ${changes.joinToString(" · ")}" to ToastKind.Done
}
