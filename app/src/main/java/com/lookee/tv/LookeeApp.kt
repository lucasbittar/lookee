package com.lookee.tv

import android.app.Application
import android.content.Context
import com.lookee.core.lookeecore.Lookeecore
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lookee.tv.data.GoCoreBridge
import com.lookee.tv.data.LibrarySync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class LookeeApp : Application(), ImageLoaderFactory {
    /** Lives as long as the process; for work that must outlast a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var core: GoCoreBridge
        private set

    lateinit var library: LibrarySync
        private set

    override fun onCreate() {
        super.onCreate()
        // Opening SQLite and sweeping old stream pieces takes a moment: do it off the main
        // thread. Every bridge call waits for it, and a failure surfaces on the first call.
        val ready = appScope.async(Dispatchers.IO) {
            Lookeecore.initEngine(
                filesDir.absolutePath,
                cacheDir.absolutePath,
                // App-specific storage on the big drive (adopted storage on the Mi Box), for torrent pieces.
                getExternalFilesDir(null)?.absolutePath.orEmpty(),
            )
        }
        core = GoCoreBridge(ready)
        library = LibrarySync(core, appScope)
        appScope.launch { seedPrivateDefaults() }
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

        fun from(context: Context): LookeeApp = context.applicationContext as LookeeApp
    }
}
