package com.orientlock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** 全进程唯一的 DataStore 实例 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "orientation_settings"
)
