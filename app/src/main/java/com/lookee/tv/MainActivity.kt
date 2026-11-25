package com.lookee.tv

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.lookee.tv.ui.LookeeRoot
import com.lookee.tv.ui.theme.LookeeTheme

class MainActivity : ComponentActivity() {
    private val app get() = LookeeApp.from(this)
    private val main = Handler(Looper.getMainLooper())

    /** A drive plugged in or pulled out: sync, so the library matches what's connected. */
    private val drives = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Give the volume a moment to finish mounting before walking it.
            main.removeCallbacksAndMessages(null)
            main.postDelayed({ if (canRead()) app.library.autoScan(force = true) }, MOUNT_SETTLE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LookeeTheme {
                LookeeRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Every time the app comes to the front, catch up with what was added to the drive.
        if (canRead()) app.library.autoScan()
        registerReceiver(
            drives,
            IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            },
        )
    }

    override fun onStop() {
        unregisterReceiver(drives)
        main.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun canRead() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MOUNT_SETTLE_MS = 2_000L
    }
}
