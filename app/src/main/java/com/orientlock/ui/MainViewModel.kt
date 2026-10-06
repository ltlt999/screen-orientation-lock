package com.orientlock.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.orientlock.data.SettingsOrientationRepository
import com.orientlock.data.SystemOrientationWriter
import com.orientlock.data.AndroidOrientationAccess
import com.orientlock.data.settingsDataStore
import com.orientlock.domain.AppSettings
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.OrientationRepository
import com.orientlock.system.AndroidPermissionChecker
import com.orientlock.system.AndroidServiceGateway
import com.orientlock.system.PermissionChecker
import com.orientlock.system.ServiceGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 界面所需的全部状态 */
data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val canWriteSettings: Boolean = false,
    val canPostNotifications: Boolean = true,
    /**
     * 是否已授予「显示在其他应用上层」。
     *
     * 这是**能力更强**的那个权限：有它才能锁住自己声明了方向的应用
     * （车机桌面、部分音视频应用）；只有「修改系统设置」时，那类应用锁不住。
     */
    val canDrawOverlays: Boolean = false,
    /**
     * Repository 的首次真实值是否已到达。
     *
     * 为 false 时 [settings] 还只是占位默认值——重启后 DataStore 里可能是竖屏，
     * 而这里的默认是 AUTO，界面据此渲染会先闪一帧「未锁定」。
     * 界面应据此决定是否渲染，而不是乐观地相信 settings。
     */
    val isLoaded: Boolean = false,
) {
    /** 两条通路至少有一条可用，锁定才有意义 */
    val canLock: Boolean get() = canDrawOverlays || canWriteSettings
}

/**
 * 主界面 ViewModel。
 *
 * 只依赖 [OrientationRepository] / [PermissionChecker] / [ServiceGateway] 三个接口，
 * 不直接碰 Settings.System 或 DataStore，因此可用假实现做纯 JUnit 测试。
 * 所有设置状态来自 Repository 的 Flow，保证界面与通知栏永远一致。
 */
class MainViewModel(
    private val repository: OrientationRepository,
    private val permissions: PermissionChecker,
    private val services: ServiceGateway,
) : ViewModel() {

    private val permissionSnapshot = MutableStateFlow(
        MainUiState(
            canWriteSettings = permissions.canWriteSettings(),
            canPostNotifications = permissions.canPostNotifications(),
            canDrawOverlays = permissions.canDrawOverlays(),
        )
    )

    /** 一次性提示。界面消费后应调用 [consumeHint]，避免旋转屏幕时重复弹出。 */
    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint

    fun consumeHint() {
        _hint.value = null
    }

    val uiState: StateFlow<MainUiState> =
        combine(repository.settings, permissionSnapshot) { settings, permission ->
            MainUiState(
                settings = settings,
                canWriteSettings = permission.canWriteSettings,
                canPostNotifications = permission.canPostNotifications,
                canDrawOverlays = permission.canDrawOverlays,
                isLoaded = true,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState(isLoaded = false),
        )

    /**
     * 选一个方向。
     *
     * 两条通路都没有时**必须给出反馈**：以前这里只是静默 return，用户点了卡片
     * 什么都不发生、也不报错，会把「没授权限」误判成「软件锁不住」——
     * 这个缺陷实测误导过一次排查。
     */
    fun selectMode(mode: OrientationMode) {
        val canOverlay = permissions.canDrawOverlays()
        val canWrite = permissions.canWriteSettings()
        if (!canOverlay && !canWrite) {
            _hint.value = "还没有授予锁定所需的权限，请先点上方「去开启」"
            refreshPermissions()
            return
        }
        viewModelScope.launch {
            // setMode 会写系统设置，而没授予 WRITE_SETTINGS 时系统抛 SecurityException。
            // 主通路是悬浮窗，它不依赖那个权限，所以这里绝不能让异常逃出协程——
            // 实测过一次：异常冒到协程外，应用直接闪退，连服务都没来得及启动。
            try {
                repository.setMode(mode)
            } catch (e: Exception) {
                _hint.value = "写入系统设置失败，已改用悬浮窗接管"
            }
            services.start()
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoStartOnBoot(enabled) }
    }

    /**
     * 切换常驻通知。关掉时要把服务停掉——否则通知还在，等于没关。
     */
    fun setPersistentNotification(enabled: Boolean) {
        viewModelScope.launch {
            repository.setPersistentNotification(enabled)
            if (!enabled) services.stop()
        }
    }

    /**
     * 切换守护模式。设置会立即落盘；服务在跑就重载布防，没在跑则下次启动生效。
     */
    fun setGuardEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setGuardEnabled(enabled)
            services.restartGuard()
        }
    }

    /** 从 onResume 调用：权限页返回后刷新授权状态 */
    fun refreshPermissions() {
        permissionSnapshot.value = MainUiState(
            settings = permissionSnapshot.value.settings,
            canWriteSettings = permissions.canWriteSettings(),
            canPostNotifications = permissions.canPostNotifications(),
            canDrawOverlays = permissions.canDrawOverlays(),
        )
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    repository = SettingsOrientationRepository(
                        dataStore = application.settingsDataStore,
                        writer = SystemOrientationWriter(AndroidOrientationAccess(application)),
                    ),
                    permissions = AndroidPermissionChecker(application),
                    services = AndroidServiceGateway(application),
                )
            }
        }
    }
}
