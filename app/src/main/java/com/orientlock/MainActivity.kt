package com.orientlock

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import com.orientlock.system.PermissionIntents
import com.orientlock.ui.AppRoot
import com.orientlock.ui.theme.OrientLockTheme

class MainActivity : AppCompatActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OrientLockTheme {
                AppRoot()

                // Android 13 起通知要运行时权限。没授予只是通知栏入口不可用，
                // 不影响锁定功能本身，所以这里不解释、不重试。
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestNotificationPermission.launch(
                            PermissionIntents.NOTIFICATION_PERMISSION
                        )
                    }
                }
            }
        }
    }
}
