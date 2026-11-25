package com.lookee.tv

import android.app.Application
import android.content.Context
import com.lookee.core.lookeecore.Lookeecore
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lookee.tv.data.Boot
import com.lookee.tv.data.BootStep
import com.lookee.tv.data.GoCoreBridge
import com.lookee.tv.data.LibrarySync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LookeeApp : Application(), ImageLoaderFactory {
    /** Lives as long as the process; for work that must outlast a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var core: GoCoreBridge
        private set

    lateinit var library: LibrarySync
        private set

    /** Startup progress, shown by the splash. */
    val boot = Boot()

    override fun onCreate() {
        super.onCreate()
        recordCrashes()
        // Opening SQLite and sweeping old stream pieces takes a moment: do it off the main
        // thread. Every bridge call waits for it, and a failure surfaces on the first call.
        val ready = appScope.async(Dispatchers.IO) {
            boot.step(BootStep.Engine)
            // The first touch of Lookeecore loads libgojni.so and starts the Go runtime: the
            // slowest part of a cold start on the Mi Box.
            Lookeecore.coreVersion()
            boot.step(BootStep.Library)
            Lookeecore.initEngine(
                filesDir.absolutePath,
                cacheDir.absolutePath,
                // App-specific storage on the big drive (adopted storage on the Mi Box), for torrent pieces.
                getExternalFilesDir(null)?.absolutePath.orEmpty(),
            )
        }
        core = GoCoreBridge(ready)
        library = LibrarySync(core, appScope)
        appScope.launch { startUp(ready) }
    }

    /** The boot steps after the engine; the splash follows them. Failures never stop the app. */
    private suspend fun startUp(ready: Deferred<Unit>) {
        val failure = runCatching { ready.await() }.exceptionOrNull()
        if (failure != null) {
            boot.fail(failure.message ?: "The engine didn't start")
            delay(ERROR_HOLD_MS)
            boot.step(BootStep.Done)
            return
        }
        boot.step(BootStep.Sources)
        seedPrivateDefaults()
        boot.step(BootStep.Drives)
        boot.detail(runCatching { drivesSummary() }.getOrNull())
        boot.step(BootStep.Home)
        boot.awaitHome()
        boot.step(BootStep.Done)
    }

    /** "Drive connected · 3 folders", from the live volume list (a root's saved flag is from the last scan). */
    private suspend fun drivesSummary(): String {
        val roots = core.roots()
        if (roots.isEmpty()) return "No folders added yet"
        val volumes = core.storageVolumes()
        val online = roots.count { root -> volumes.any { root.path.startsWith(it.path) } }
        val folders = if (roots.size == 1) "1 folder" else "${roots.size} folders"
        return when (online) {
            roots.size -> "Drive connected · $folders"
            0 -> "Drive not connected · your library is still here"
            else -> "$online of $folders available"
        }
    }

    /**
     * A crash in the app's own code goes to the core's log before the process dies, so it can
     * be read from the phone page with USB debugging off (CLAUDE.md "Diagnose without ADB").
     * The call into Go is synchronous on purpose: there's no later.
     */
    private fun recordCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = error.stackTraceToString().lines().take(14).joinToString(" | ")
                Lookeecore.logEvent("warn", "crash on ${thread.name}: $trace")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * A private build can ship `assets/sources.local.json` (git-ignored, docs/SOURCES.md) with
     * torrent sources preset. Go fills only empty settings, once per file. A bad file is
     * logged by the core and never stops the app.
     */
    private suspend fun seedPrivateDefaults() {
        val json = runCatching { assets.open(DEFAULTS_ASSET).bufferedReader().use { it.readText() } }
            .getOrNull() ?: return
        runCatching { core.seedDefaults(json) }
    }

    /** Posters and backdrops: 150 MB on disk (CLAUDE.md), a small memory cache for a 2 GB box. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .crossfade(180)
        .build()

    companion object {
        private const val DEFAULTS_ASSET = "sources.local.json"
        private const val ERROR_HOLD_MS = 5_000L

        fun from(context: Context): LookeeApp = context.applicationContext as LookeeApp
    }
}
