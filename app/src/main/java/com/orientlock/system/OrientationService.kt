package com.orientlock.system

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.orientlock.data.AndroidOrientationAccess
import com.orientlock.data.SettingsOrientationRepository
import com.orientlock.data.SystemOrientationWriter
import com.orientlock.data.settingsDataStore
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.OrientationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * 前台服务：常驻通知栏入口 + 守护心跳。
 *
 * 守护用两条路径：
 * 1. ContentObserver 监听 USER_ROTATION 与 ACCELEROMETER_ROTATION，变了才反应；
 * 2. 每 [GUARD_INTERVAL_MS] 兜底心跳一次，覆盖 observer 未触发的情形。
 *
 * 判定与重写的逻辑不在本类，而在 Repository.guardTick()。
 *
 * **所有对 Repository 的调用都必须包在 try/catch 里。** 两条已证实可达的异常：
 * WRITE_SETTINGS 被收回后 Settings.System.putInt 抛 SecurityException；残留的越界
 * 持久化角度会让 writeUserRotation 的 require 抛 IllegalArgumentException。
 * 心跳每 10 秒跑一次，跑在 SupervisorJob 上的 launch 里，不捕获就是
 * 崩溃 → 服务重启 → 再抛 的循环。方向状态本身幂等，跳过本次心跳下一次自然收敛。
 */
class OrientationService : Service() {

    companion object {
        private const val GUARD_INTERVAL_MS = 10_000L
        private const val TAG = "OrientationService"

        /** 进程内唯一实例，供设置页重启守护时使用 */
        @Volatile
        private var instanceRef: WeakReference<OrientationService>? = null

        fun activeInstance(): OrientationService? = instanceRef?.get()
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + job)

    private lateinit var repository: OrientationRepository
    private lateinit var notifications: NotificationHelper

    private val handler = Handler(Looper.getMainLooper())
    private var guardRunning = false

    private val observer = object : ContentObserver(handler) {
        // 系统方向值被改动（含我们自己写入触发的回执）：交给心跳统一判定。
        // 自己刚写过的值与目标一致，shouldReapply 返回 false，不会自激循环。
        //
        // 覆写 3 参数的 onChange 而非 1 参数的那个：SDK O 起平台推荐前者，
        // compileSdk 36 上 1 参数版本与 dispatchChange(boolean) 一起被标记
        // @Deprecated，覆写它会有弃用告警，而这个回调并不需要用到 uri 与 flags。
        override fun onChange(selfChange: Boolean, uri: android.net.Uri?, flags: Int) {
            scope.launch { safeGuard() }
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            scope.launch { safeGuard() }
            if (guardRunning) handler.postDelayed(this, GUARD_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        repository = SettingsOrientationRepository(
            dataStore = applicationContext.settingsDataStore,
            writer = SystemOrientationWriter(AndroidOrientationAccess(applicationContext)),
        )
        notifications = NotificationHelper(this)
        // 必须在第一次 startForeground 之前建好渠道，否则通知根本不显示
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先用当前已知模式把通知发出去，保证 5 秒时限内一定调用过 startForeground，
        // 不在主线程上阻塞读 DataStore
        startForegroundCompat(notifications.build(OrientationMode.AUTO, notificationGranted()))

        scope.launch {
            when (intent?.action) {
                NotificationHelper.ACTION_SET_PORTRAIT -> safeSelect(OrientationMode.PORTRAIT)
                NotificationHelper.ACTION_SET_LANDSCAPE -> safeSelect(OrientationMode.LANDSCAPE)
                NotificationHelper.ACTION_SET_REVERSE -> {
                    val name = intent.getStringExtra(NotificationHelper.EXTRA_REVERSE_TARGET)
                    safeSelect(OrientationMode.fromStorageName(name))
                }
                NotificationHelper.ACTION_UNLOCK -> safeSelect(OrientationMode.AUTO)
                else -> {
                    // 冷启动或开机拉起：重放上次的锁定
                    safeGuard()
                    refreshNotification()
                }
            }
        }

        startGuardIfNeeded()
        // START_STICKY：被系统杀掉后重建，配合开机广播兜底
        return START_STICKY
    }

    /** 通知 action 的落点。异常只记录，不让服务崩，但通知无论如何都要刷新 */
    private suspend fun safeSelect(mode: OrientationMode) {
        try {
            repository.setMode(mode)
        } catch (e: Exception) {
            Log.w(TAG, "写入方向 $mode 失败", e)
        }
        refreshNotification()
    }

    private suspend fun safeGuard() {
        try {
            repository.guardTick()
        } catch (e: Exception) {
            Log.w(TAG, "守护心跳失败", e)
        }
    }

    private fun notificationGranted(): Boolean =
        AndroidPermissionChecker.canPostNotifications(this)

    private suspend fun refreshNotification() {
        val snap = repository.snapshot()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NotificationHelper.NOTIFICATION_ID,
            notifications.build(snap.mode, notificationGranted()),
        )
    }

    private fun startGuardIfNeeded() {
        if (guardRunning) return
        scope.launch {
            val snap = repository.snapshot()
            if (!snap.guardEnabled) return@launch
            guardRunning = true
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.USER_ROTATION), false, observer
            )
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, observer
            )
            handler.post(heartbeat)
        }
    }

    private fun stopGuard() {
        guardRunning = false
        handler.removeCallbacks(heartbeat)
        runCatching { contentResolver.unregisterContentObserver(observer) }
    }

    /** 供设置页在「守护模式」开关变化后调用 */
    fun restartGuard() {
        stopGuard()
        startGuardIfNeeded()
    }

    /**
     * 前台服务类型 specialUse。
     *
     * 该常量 API 34 才有，低于它时用无参版本；类型已在 manifest 声明。
     * 通知先于 onStartCommand 里的协程发出，因此这里的 5 秒时限一定满足。
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopGuard()
        scope.cancel()
        instanceRef = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
