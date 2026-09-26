package com.orientlock.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.orientlock.domain.AppSettings
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
    private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
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
        // 第一次选方向时才探测天然朝向，探测结果同时落盘，之后不再探测
        val natural = resolveNaturalOrientation()
        val pinned = if (mode == OrientationMode.CURRENT) writer.displayRotation() else 0
        dataStore.edit { prefs ->
            prefs[Keys.MODE] = mode.storageName
            prefs[Keys.PINNED_ROTATION] = pinned
        }
        writer.apply(mode, natural, pinned)
    }

    override suspend fun guardTick(): Boolean {
        val snap = snapshot()
        if (!snap.guardEnabled) return false
        val natural = resolveNaturalOrientation()
        val target = snap.mode
        if (target == OrientationMode.AUTO) return false
        val current = writer.readState()
        if (!shouldReapply(current, target, natural)) return false
        writer.apply(target, natural, snap.pinnedRotation)
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

    /** 已探测过就直接用缓存值，否则探测并落盘 */
    private suspend fun resolveNaturalOrientation(): NaturalOrientation {
        snapshot().naturalOrientation?.let { return it }
        val detected = writer.naturalOrientation()
        dataStore.edit { it[Keys.NATURAL] = detected.name }
        return detected
    }

    private fun androidx.datastore.preferences.core.Preferences.toAppSettings(): AppSettings {
        val mode = OrientationMode.fromStorageName(this[Keys.MODE])
        return AppSettings(
            mode = mode,
            pinnedRotation = this[Keys.PINNED_ROTATION] ?: 0,
            autoStartOnBoot = this[Keys.AUTO_START] ?: true,
            persistentNotification = this[Keys.NOTIFICATION] ?: true,
            guardEnabled = this[Keys.GUARD] ?: true,
            naturalOrientation = this[Keys.NATURAL]
                ?.let { name -> NaturalOrientation.entries.firstOrNull { it.name == name } },
        )
    }
}
