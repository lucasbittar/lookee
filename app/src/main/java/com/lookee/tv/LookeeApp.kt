package com.lookee.tv

import android.app.Application
import android.content.Context
import com.lookee.core.lookeecore.Lookeecore
import com.lookee.tv.data.GoCoreBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class LookeeApp : Application() {
    /** Lives as long as the process; for work that must outlast a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var core: GoCoreBridge
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
    }

    companion object {
        fun from(context: Context): LookeeApp = context.applicationContext as LookeeApp
    }
}
