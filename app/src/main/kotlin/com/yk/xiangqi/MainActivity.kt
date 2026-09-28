package com.yk.xiangqi

import android.os.Bundle
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import com.yk.xiangqi.link.LinkForegroundService
import com.yk.xiangqi.ui.XiangqiApp
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK && result.data != null)
                    LinkForegroundService.start(this, result.resultCode, result.data!!)
            }
            MaterialTheme {
                XiangqiApp(onStartLink = {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
                    } else {
                        val manager = getSystemService(MediaProjectionManager::class.java)
                        projection.launch(manager.createScreenCaptureIntent())
                    }
                })
            }
        }
    }
}
