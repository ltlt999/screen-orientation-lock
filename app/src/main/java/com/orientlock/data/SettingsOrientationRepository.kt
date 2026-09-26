package com.orientlock.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.orientlock.domain.AppSettings
import com.orientlock.domain.DisplayRotation
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.OrientationRepository
import com.orientlock.domain.shouldReapply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * OrientationRepository 的实现。
 *
 * 职责：把 [AppSettings] 与 DataStore、[SystemOrientationWriter] 对上，
 * 并承担守护心跳里的比对与重写逻辑。
 */
class SettingsOrientationRepository(
    private val dataStore: DataStore<Preferences>,
    private val writer: SystemOrientationWriter,
) : OrientationRepository {

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val PINNED_ROTATION = intPreferencesKey("pinned_rotation")
        val AUTO_START = booleanPreferencesKey("auto_start_on_boot")
        val NOTIFICATION = booleanPreferencesKey("persistent_notification")
        val GUARD = booleanPreferencesKey("guard_enabled")
        val NATURAL = stringPreferencesKey("natural_orientation")
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { it.toAppSettings() }

    override suspend fun snapshot(): AppSettings =
        dataStore.data.first().toAppSettings()

    override suspend fun setMode(mode: OrientationMode) {
        val pinned = if (mode == OrientationMode.CURRENT) writer.displayRotation() else 0
        // 先落盘再写入系统。落盘的是**用户意图**：万一系统写入失败（例如 WRITE_SETTINGS
        // 被收回，putInt 会静默失败或抛 SecurityException），守护的职责正是把系统
        // 收敛回这个意图。反过来（先锁后记）一旦落盘失败，就成了
        // 「系统锁着、应用忘了」的永久不一致，重启后开机恢复还会主动把用户的
        // 选择改回去。MODE 与 PINNED_ROTATION 在同一次 edit 里写，不会出现半应用状态。
        dataStore.edit { prefs ->
            prefs[Keys.MODE] = mode.storageName
            prefs[Keys.PINNED_ROTATION] = pinned
        }
        val natural = if (mode == OrientationMode.AUTO) {
            // AUTO 不需要天然朝向：apply 的 AUTO 分支只开自动旋转开关。
            // 已落盘过就直接用，否则才探测——探测是 suspend 且带 300ms 稳定等待，
            // 冷启动后第一次点击不该为已知答案白等。
            null
        } else {
            resolveNaturalOrientation(snapshot().naturalOrientation)
        }
        writer.apply(mode, natural, pinned)
    }

    override suspend fun guardTick(): Boolean {
        val snap = snapshot()
        if (!snap.guardEnabled) return false
        // AUTO 必须在探测之前短路：解锁状态既不需要天然朝向，也不该重写系统
        if (snap.mode == OrientationMode.AUTO) return false
        val natural = resolveNaturalOrientation(snap.naturalOrientation)
        val current = writer.readState()
        if (!shouldReapply(current, snap.mode, natural)) return false
        writer.apply(snap.mode, natural, snap.pinnedRotation)
        return true
    }

    override suspend fun setAutoStartOnBoot(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_START] = enabled }
    }

    override suspend fun setPersistentNotification(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATION] = enabled }
    }

    override suspend fun setGuardEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.GUARD] = enabled }
    }

    /**
     * 取天然朝向：已落盘就直接用，否则探测。
     *
     * @param cached 调用方已读到的 [AppSettings.naturalOrientation]，传进来省一次
     *   DataStore 读；没有就传 null
     *
     * **只有稳定的探测结果才允许落盘。** 两次采样不一致说明设备当时正在转动、
     * displayMetrics 还没跟上 displayRotation，此时的值是对是错无法判断；
     * 落盘了就永久错，而且只会错一次却再也改不回来（清除应用数据才行）。
     * 不落盘时本次仍返回该值让调用继续，但 DataStore 保持 null，
     * 下一次调用会重新探测，直到取到稳定值。
     */
    private suspend fun resolveNaturalOrientation(cached: NaturalOrientation?): NaturalOrientation {
        cached?.let { return it }
        val reading = writer.naturalOrientation()
        if (reading.stable) {
            dataStore.edit { it[Keys.NATURAL] = reading.orientation.name }
        }
        return reading.orientation
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val mode = OrientationMode.fromStorageName(this[Keys.MODE])
        return AppSettings(
            mode = mode,
            // 越界的持久化角度会让守护心跳周期性抛异常（服务上没有 try/catch，
            // 于是变成每 10 秒崩一次）。DataStore 算外部输入，读取时就收敛掉。
            pinnedRotation = (this[Keys.PINNED_ROTATION] ?: 0).takeIf {
                it in DisplayRotation.NATURAL..DisplayRotation.THREE_QUARTER
            } ?: 0,
            autoStartOnBoot = this[Keys.AUTO_START] ?: true,
            persistentNotification = this[Keys.NOTIFICATION] ?: true,
            guardEnabled = this[Keys.GUARD] ?: true,
            naturalOrientation = this[Keys.NATURAL]
                ?.let { name -> NaturalOrientation.entries.firstOrNull { it.name == name } },
        )
    }
}
