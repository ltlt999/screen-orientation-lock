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
     * Repository 的首次真实值是否已到达。
     *
     * 为 false 时 [settings] 还只是占位默认值——重启后 DataStore 里可能是竖屏，
     * 而这里的默认是 AUTO，界面据此渲染会先闪一帧「未锁定」。
     * 界面应据此决定是否渲染，而不是乐观地相信 settings。
     */
    val isLoaded: Boolean = false,
)

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
        )
    )

    val uiState: StateFlow<MainUiState> =
        combine(repository.settings, permissionSnapshot) { settings, permission ->
            MainUiState(
                settings = settings,
                canWriteSettings = permission.canWriteSettings,
                canPostNotifications = permission.canPostNotifications,
                isLoaded = true,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState(isLoaded = false),
        )

    /** 选一个方向。权限未授予时不写系统，也不启动服务，只让界面弹引导。 */
    fun selectMode(mode: OrientationMode) {
        if (!permissions.canWriteSettings()) {
            permissionSnapshot.value = permissionSnapshot.value.copy(canWriteSettings = false)
            return
        }
        viewModelScope.launch {
            repository.setMode(mode)
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
