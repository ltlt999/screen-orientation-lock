package com.orientlock.fakes

import com.orientlock.domain.AppSettings
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.OrientationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * 内存版 Repository。
 *
 * 行为与真实实现一致——setMode 落盘、设置经 settings 流广播——但不碰磁盘与系统设置。
 */
class FakeOrientationRepository(
    initial: AppSettings = AppSettings(),
) : OrientationRepository {

    private val state = MutableStateFlow(initial)

    /** setMode 被调用的顺序，供断言 */
    val modeHistory = mutableListOf<OrientationMode>()

    var guardTickReturnValue = false

    /**
     * 置 true 让 [setMode] 抛异常，模拟真实设备上「没授予 WRITE_SETTINGS 时
     * Settings.System.putInt 抛 SecurityException」——实测确认过这个行为。
     */
    var throwOnSetMode = false

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun setMode(mode: OrientationMode) {
        if (throwOnSetMode) {
            throw SecurityException("模拟：未授予 WRITE_SETTINGS")
        }
        modeHistory += mode
        state.value = state.value.copy(mode = mode)
    }

    override suspend fun guardTick(): Boolean = guardTickReturnValue

    override suspend fun setAutoStartOnBoot(enabled: Boolean) {
        state.value = state.value.copy(autoStartOnBoot = enabled)
    }

    override suspend fun setPersistentNotification(enabled: Boolean) {
        state.value = state.value.copy(persistentNotification = enabled)
    }

    override suspend fun setGuardEnabled(enabled: Boolean) {
        state.value = state.value.copy(guardEnabled = enabled)
    }

    /** 等 settings 流发出满足条件的值，避免测试依赖固定时序 */
    suspend fun awaitSettings(predicate: (AppSettings) -> Boolean): AppSettings =
        settings.first(predicate)
}
