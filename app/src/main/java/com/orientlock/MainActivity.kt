package com.orientlock

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Text
import com.orientlock.ui.theme.OrientLockTheme

/** Task 15 会替换为真实界面 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OrientLockTheme {
                Text("屏幕方向锁")
            }
        }
    }
}
