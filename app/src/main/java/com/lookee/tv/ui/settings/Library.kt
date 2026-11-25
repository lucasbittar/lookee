package com.lookee.tv.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.lookee.tv.LookeeApp
import com.lookee.tv.data.Category
import com.lookee.tv.data.FolderEntry
import com.lookee.tv.data.LibraryRoot
import com.lookee.tv.data.StorageVolume
import com.lookee.tv.ui.components.Alert
import com.lookee.tv.ui.components.ButtonStyle
import com.lookee.tv.ui.components.LookeeButton
import com.lookee.tv.ui.components.LookeeCard
import com.lookee.tv.ui.components.MarqueeText
import com.lookee.tv.ui.components.MediaSurface
import com.lookee.tv.ui.components.SectionLabel
import com.lookee.tv.ui.components.StatusBadge
import com.lookee.tv.ui.components.Tone
import com.lookee.tv.ui.components.bytes
import com.lookee.tv.ui.home.categoryLabel
import com.lookee.tv.ui.navigation.LocalNavigator
import com.lookee.tv.ui.navigation.Route
import com.lookee.tv.ui.theme.JetBrainsMono
import com.lookee.tv.ui.theme.LookeeColors
import kotlinx.coroutines.launch

private fun hasStoragePermission(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/** Settings → Library: the folders lookee reads, a scan button and its progress. */
@Composable
fun LibraryCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = LookeeApp.from(context)
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val version by app.library.version.collectAsStateWithLifecycle()
    val scan by app.library.scan.collectAsStateWithLifecycle()
    var roots by remember { mutableStateOf<List<LibraryRoot>>(emptyList()) }
    var granted by remember { mutableStateOf(hasStoragePermission(context)) }
    LaunchedEffect(version) { roots = runCatching { app.core.roots() }.getOrDefault(emptyList()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) app.library.scanNow()
    }

    LookeeCard(modifier) {
        SectionLabel("Library")
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!granted) Alert("lookee needs permission to read your drive.", tone = Tone.Accent)
            if (roots.isEmpty()) {
                Text("No folders yet. Add your Movies, TV Shows, Concerts or lessons folders.", style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Muted)
            }
            roots.forEach { r -> RootRow(r) { scope.launch { runCatching { app.core.removeRoot(r.id) }; app.library.changed() } } }
            val s = scan
            val status = when {
                s == null -> null
                s.phase == "matching" -> "Fetching posters · ${s.matched}/${s.toMatch}"
                s.phase == "scanning" -> "Scanning · ${s.filesSeen} files"
                s.phase == "finished" -> s.report?.let { rep ->
                    val added = rep.roots.sumOf { it.added }
                    val removed = rep.roots.sumOf { it.removed }
                    buildString {
                        append("Last scan: $added added, $removed removed, ${rep.matched} matched")
                        if (rep.needsApiKey) append(" · posters need a TMDB key")
                        if (rep.roots.any { it.unavailable }) append(" · a folder wasn't found")
                    }
                }
                else -> "Scan failed: ${s.error}"
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted) }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LookeeButton("Add folder", onClick = {
                    if (!granted) ask.launch(Manifest.permission.READ_EXTERNAL_STORAGE) else nav.push(Route.AddFolder)
                })
                LookeeButton(if (app.library.running) "Scanning…" else "Scan now", onClick = {
                    if (!granted) ask.launch(Manifest.permission.READ_EXTERNAL_STORAGE) else app.library.scanNow()
                }, style = ButtonStyle.Quiet)
            }
        }
    }
}

/** One library folder: its path on its own line, and a Remove that needs a second press. */
@Composable
private fun RootRow(r: LibraryRoot, onRemove: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            kotlinx.coroutines.delay(4000)
            armed = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(r.path, fontFamily = JetBrainsMono, fontSize = 13.sp, color = LookeeColors.Text, maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusBadge(categoryLabel(r.category), Tone.Muted)
            if (!r.available) StatusBadge("Drive not found", Tone.Err)
            Box(Modifier.weight(1f))
            LookeeButton(
                if (armed) "Press again to remove" else "Remove",
                onClick = { if (armed) onRemove() else armed = true },
                style = ButtonStyle.Quiet,
            )
        }
    }
}

/** Settings → Storage: torrent pieces are deleted after every stream; this clears anything left. */
@Composable
fun StorageCard(modifier: Modifier = Modifier) {
    val app = LookeeApp.from(LocalContext.current)
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    LookeeCard(modifier) {
        SectionLabel("Storage")
        Text(
            "Streams keep only what you watch plus a little ahead, and delete it when you stop.",
            style = MaterialTheme.typography.bodyMedium,
            color = LookeeColors.Muted,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        LookeeButton(if (done) "Stream cache cleared" else "Clear stream cache", onClick = {
            scope.launch {
                done = runCatching { app.core.clearStreamCache() }.isSuccess
            }
        }, style = ButtonStyle.Quiet)
    }
}

/** A scannable QR code of [text], dark modules on a white tile (scanners need the contrast). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        bmp.asImageBitmap()
    }
    Image(
        bitmap,
        contentDescription = "QR code for the setup page",
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(6.dp),
        filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
    )
}

// ---- adding a folder: volume → folder → category ----

private sealed interface AddStep {
    data object Volumes : AddStep
    data class Browse(val trail: List<String>) : AddStep
    data class Category(val path: String) : AddStep
}

@Composable
fun AddFolderScreen() {
    val app = LookeeApp.from(LocalContext.current)
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<AddStep>(AddStep.Volumes) }
    var volumes by remember { mutableStateOf<List<StorageVolume>>(emptyList()) }
    var entries by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) { volumes = runCatching { app.core.storageVolumes() }.getOrDefault(emptyList()) }
    LaunchedEffect(step) {
        val s = step
        if (s is AddStep.Browse) entries = runCatching { app.core.browse(s.trail.last()) }.getOrDefault(emptyList())
    }
    // Focus the first item once it exists: the drive list loads after the screen appears.
    LaunchedEffect(step, volumes, entries) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    BackHandler(enabled = step != AddStep.Volumes) {
        step = when (val s = step) {
            is AddStep.Browse -> if (s.trail.size > 1) AddStep.Browse(s.trail.dropLast(1)) else AddStep.Volumes
            is AddStep.Category -> AddStep.Browse(listOf(s.path))
            AddStep.Volumes -> AddStep.Volumes
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings  ›  Add folder", style = MaterialTheme.typography.bodySmall, color = LookeeColors.Muted)
        when (val s = step) {
            AddStep.Volumes -> {
                Text("Where is it?", style = MaterialTheme.typography.displaySmall, color = LookeeColors.Text)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(volumes, key = { it.path }) { v ->
                        PickRow(
                            title = v.label,
                            detail = listOf(v.path, if (v.totalBytes > 0) "${bytes(v.freeBytes)} free of ${bytes(v.totalBytes)}" else "").filter { it.isNotEmpty() }.joinToString(" · "),
                            modifier = if (v == volumes.first()) Modifier.focusRequester(first) else Modifier,
                        ) { step = AddStep.Browse(listOf(v.path)) }
                    }
                }
            }
            is AddStep.Browse -> {
                val here = s.trail.last()
                val title = volumes.firstOrNull { it.path == here }?.label ?: here.substringAfterLast('/').ifEmpty { here }
                Text(title, style = MaterialTheme.typography.displaySmall, color = LookeeColors.Text)
                Text(here, fontFamily = JetBrainsMono, fontSize = 13.sp, color = LookeeColors.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LookeeButton("Use this folder", onClick = { step = AddStep.Category(here) }, modifier = Modifier.focusRequester(first))
                }
                val folders = entries.filter { it.isDir }
                if (folders.isEmpty()) Text("No folders inside.", style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Muted)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(folders, key = { it.path }) { f ->
                        PickRow(title = f.name, detail = "") { step = AddStep.Browse(s.trail + f.path) }
                    }
                }
            }
            is AddStep.Category -> {
                Text("What's in it?", style = MaterialTheme.typography.displaySmall, color = LookeeColors.Text)
                Text(s.path, fontFamily = JetBrainsMono, fontSize = 13.sp, color = LookeeColors.Muted)
                error?.let { Alert(it) }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(Category.MOVIES, Category.TV_SHOWS, Category.CONCERTS, Category.TUTORIALS).forEachIndexed { i, c ->
                        LookeeButton(categoryLabel(c), modifier = if (i == 0) Modifier.focusRequester(first) else Modifier, onClick = {
                            scope.launch {
                                try {
                                    app.core.addRoot(s.path, c)
                                    app.library.changed()
                                    app.library.scanNow()
                                    nav.pop()
                                } catch (e: Exception) {
                                    error = e.message
                                }
                            }
                        })
                    }
                }
                Text(
                    "Movies, Series and Concerts get posters and details from TMDB. Tutorials are shown exactly as they are on the drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LookeeColors.Muted,
                    modifier = Modifier.width(560.dp),
                )
            }
        }
    }
}

@Composable
private fun PickRow(title: String, detail: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    MediaSurface(onClick = onClick, modifier = modifier.width(640.dp).onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(10.dp), focusedScale = 1.02f) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(width = 18.dp, height = 14.dp).clip(RoundedCornerShape(3.dp)).background(if (focused) LookeeColors.Accent else LookeeColors.Muted))
            Column(Modifier.weight(1f)) {
                MarqueeText(title, MaterialTheme.typography.titleSmall, LookeeColors.Text, active = focused)
                if (detail.isNotEmpty()) Text(detail, fontFamily = JetBrainsMono, fontSize = 12.sp, color = LookeeColors.Muted, maxLines = 1)
            }
        }
    }
}
