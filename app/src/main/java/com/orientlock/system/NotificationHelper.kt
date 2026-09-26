package com.orientlock.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.orientlock.MainActivity
import com.orientlock.R
import com.orientlock.domain.OrientationMode

/**
 * 通知渠道与常驻通知的构建。
 *
 * 只负责「怎么展示」，不碰方向逻辑，文案改动集中在此。
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "orientation_lock"
        const val NOTIFICATION_ID = 1001

        const val ACTION_SET_PORTRAIT = "com.orientlock.action.SET_PORTRAIT"
        const val ACTION_SET_LANDSCAPE = "com.orientlock.action.SET_LANDSCAPE"
        const val ACTION_SET_REVERSE = "com.orientlock.action.SET_REVERSE"
        const val ACTION_UNLOCK = "com.orientlock.action.UNLOCK"
        const val EXTRA_REVERSE_TARGET = "extra_reverse_target"
    }

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "方向锁定",
            NotificationManager.IMPORTANCE_LOW, // 无声、不浮动，安静常驻
        ).apply {
            description = "显示当前锁定的屏幕方向，并提供快速切换按钮"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建常驻通知。
     *
     * 「反向」按钮的语义随当前锁定方向变化：竖屏锁定时切到反向竖屏，
     * 横屏锁定时切到反向横屏；未锁定时按反向竖屏处理。
     * 「当前方向」没有固定反向，同样回落到反向竖屏。
     *
     * @param currentMode 当前模式
     * @param notificationGranted 是否已授予通知权限；未授予时正文明示
     */
    fun build(currentMode: OrientationMode, notificationGranted: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val locked = currentMode != OrientationMode.AUTO
        val title = if (locked) "${currentMode.label}锁定中" else "未锁定 · 跟随传感器"
        val text = if (notificationGranted) {
            "点按打开 · 用下方按钮快速切换"
        } else {
            "通知权限未开启，无法显示此通知"
        }

        val reverseTarget = currentMode.reversed()
            .takeIf { it != OrientationMode.AUTO && it != OrientationMode.CURRENT }
            ?: OrientationMode.PORTRAIT_REVERSE

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_orientation)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 顺序有意：系统折叠态最多显示 3 个 action，第 4 个要展开才看得到。
            // 竖屏、横屏是核心，解除是「恢复正常」的出口，这三个必须常驻可见；
            // 反向频率最低，放在第 4 位，展开后仍可用，不丢功能。
            .addAction(0, "竖屏", actionIntent(ACTION_SET_PORTRAIT))
            .addAction(0, "横屏", actionIntent(ACTION_SET_LANDSCAPE))
            .addAction(0, "解除", actionIntent(ACTION_UNLOCK))
            .addAction(0, "反向", reverseIntent(reverseTarget))
            .build()
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(context, OrientationService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun reverseIntent(mode: OrientationMode): PendingIntent {
        val intent = Intent(context, OrientationService::class.java)
            .setAction(ACTION_SET_REVERSE)
            .putExtra(EXTRA_REVERSE_TARGET, mode.storageName)
        return PendingIntent.getService(
            context,
            ACTION_SET_REVERSE.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
