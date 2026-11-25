package com.lookee.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lookee.tv.ui.LookeeRoot
import com.lookee.tv.ui.theme.LookeeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LookeeTheme {
                LookeeRoot()
            }
        }
    }
}
