package com.orientlock.system

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
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
import com.orientlock.domain.AppSettings
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.OrientationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

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

        /**
         * 最近一次已知的方向模式，进程级缓存。
         *
         * onStartCommand 必须先同步发一条通知才能开始读 DataStore，这一步用这个值
         * 而不是硬编码的 AUTO——否则每次点通知按钮、每次服务被粘性重启，
         * 标题都会先闪一下「未锁定」。放 companion 里是为了让它在服务重建后仍在。
         */
        @Volatile
        private var lastKnownMode: OrientationMode = OrientationMode.AUTO

        /** 进程内唯一实例，供设置页重启守护时使用 */
        @Volatile
        private var instanceRef: WeakReference<OrientationService>? = null

        fun activeInstance(): OrientationService? = instanceRef?.get()
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + job)

    private lateinit var writer: SystemOrientationWriter
    private lateinit var repository: OrientationRepository
    private lateinit var notifications: NotificationHelper

    /**
     * 悬浮窗方向接管。锁定的**主力通路**——写系统设置只能影响「没声明方向」的应用，
     * 而悬浮窗能压过应用自己声明的方向（车机桌面、部分音视频应用都靠它）。
     *
     * 必须由本服务持有：窗口一旦随 Activity 或 ViewModel 销毁，锁定就没了。
     */
    private lateinit var overlay: OverlayOrientationController

    /** 上一次渲染通知时的关键状态，避免心跳每 10 秒重复 post 同一条通知 */
    private var lastRenderedNotification: Triple<OrientationMode, Boolean, Boolean>? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 守护是否已请求运行。**必须在 launch 之前同步占位**：若把标志设在协程里
     * （snapshot 挂起之后），两次快速 onStartCommand 会双双通过检查，
     * observer 注册两遍、heartbeat 投递两条，之后每 10 秒跑两次心跳。
     */
    private val guardRequested = AtomicBoolean(false)

    /** observer 是否已注册。register/unregister 的幂等护栏，与 guardRequested 分开 */
    private var observerRegistered = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
            // 系统方向值被改动（含我们自己写入触发的回执）：交给心跳统一判定。
            // 自己刚写过的值与目标一致，shouldReapply 返回 false，不会自激循环。
            scope.launch { safeGuard() }
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            scope.launch { safeGuard() }
            if (guardRequested.get()) handler.postDelayed(this, GUARD_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        val access = AndroidOrientationAccess(applicationContext)
        writer = SystemOrientationWriter(access)
        repository = SettingsOrientationRepository(
            dataStore = applicationContext.settingsDataStore,
            writer = writer,
        )
        notifications = NotificationHelper(this)
        // 必须在第一次 startForeground 之前建好渠道，否则通知根本不显示
        notifications.ensureChannel()

        overlay = OverlayOrientationController(this)

        // 模式一变就更新悬浮窗。这是响应式的：ViewModel 改设置、通知栏按钮改设置、
        // 开机恢复，全都会经由同一条 settings 流到达这里，不需要各处分别调用。
        scope.launch {
            repository.settings.collect { snap ->
                applyOverlay(snap)
                refreshNotification()
            }
        }
    }

    /**
     * 把当前模式施加到悬浮窗上。
     *
     * 没拿到「显示在其他应用上层」权限时 [OverlayOrientationController.setOrientation]
     * 会返回 false，这里只记日志——此时写系统设置那条路仍然生效，
     * 只是锁不住自己声明了方向的应用。
     */
    private fun applyOverlay(snap: AppSettings) {
        val natural = snap.naturalOrientation
        if (natural == null && snap.mode == OrientationMode.CURRENT) {
            // 「当前方向」要把固定角度换算成绝对方向，必须知道天然朝向。
            // 还没探测出来就先不接管；探测结果落盘会触发下一次发射，那时再接管。
            return
        }
        val orientation = snap.mode.overlayOrientationFor(
            natural = natural ?: NaturalOrientation.PORTRAIT,
            pinnedRotation = snap.pinnedRotation,
        )
        if (!overlay.setOrientation(orientation)) {
            Log.w(TAG, "悬浮窗接管失败：多半是没授予「显示在其他应用上层」权限")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先用最近已知的模式把通知发出去，保证 5 秒时限内一定调用过 startForeground，
        // 不在主线程上阻塞读 DataStore
        startForegroundCompat(
            notifications.build(
                currentMode = lastKnownMode,
                notificationGranted = notificationGranted(),
                lockAvailable = overlay.isHolding || writer.canWrite(),
            )
        )

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
        // 心跳跑完必须刷新通知。权限被收回后 putInt 会静默失败（它返回 Boolean，
        // 我们拿不到也改不了系统），此时若不刷新，通知会一直显示「xx锁定中」——
        // 应用在替自己撒谎，用户会以为锁上了。
        refreshNotification()
    }

    private fun notificationGranted(): Boolean =
        AndroidPermissionChecker.canPostNotifications(this)

    /**
     * 刷新常驻通知。
     *
     * 状态没变就不重复 post：守护每 10 秒跑一次心跳，无条件 notify 会让系统
     * 每 10 秒重建一次通知。状态变了（模式变了、权限变了）才发。
     */
    private suspend fun refreshNotification(force: Boolean = false) {
        val snap = repository.snapshot()
        lastKnownMode = snap.mode
        val granted = notificationGranted()
        // 只要两条通路有一条能用，锁定就是有效的：悬浮窗能压过应用声明的方向，
        // 写系统设置能覆盖跟随系统的应用。
        val lockAvailable = overlay.isHolding || writer.canWrite()
        val key = Triple(snap.mode, granted, lockAvailable)
        if (!force && key == lastRenderedNotification) return
        lastRenderedNotification = key

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NotificationHelper.NOTIFICATION_ID,
            notifications.build(
                currentMode = snap.mode,
                notificationGranted = granted,
                lockAvailable = lockAvailable,
            ),
        )
    }

    private fun startGuardIfNeeded() {
        // 同步占位，见 [guardRequested] 的说明
        if (!guardRequested.compareAndSet(false, true)) return
        scope.launch {
            val snap = repository.snapshot()
            if (!snap.guardEnabled) {
                // 用户关掉了守护：撤销占位，observer 与心跳都不启动
                guardRequested.set(false)
                return@launch
            }
            if (observerRegistered) return@launch
            observerRegistered = true
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
        guardRequested.set(false)
        handler.removeCallbacks(heartbeat)
        if (observerRegistered) {
            observerRegistered = false
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
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
        // 服务没了，悬浮窗也必须撤掉：窗口持有者是本服务，留着会变成孤儿窗口，
        // 用户会看到一个锁不掉也解不开的方向。
        overlay.stop()
        scope.cancel()
        instanceRef = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
