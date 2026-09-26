package com.orientlock.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.orientlock.OrientLockApp
import com.orientlock.data.AndroidOrientationAccess
import com.orientlock.data.SettingsOrientationRepository
import com.orientlock.data.SystemOrientationWriter
import com.orientlock.data.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机与应用更新后拉起服务。
 *
 * 覆盖三类广播：
 * - BOOT_COMPLETED：标准开机
 * - QUICKBOOT_POWERON：小米 / 一加等 ROM 的快速启动广播
 * - MY_PACKAGE_REPLACED：应用被更新后保持常驻
 *
 * 刻意不处理 LOCKED_BOOT_COMPLETED，也不用 directBootAware ——
 * 那会在用户解锁前运行，此时 DataStore 所在凭据加密存储不可读，会直接崩。
 *
 * 用 goAsync() 把工作移出主线程，避免 broadcast 的 10 秒 ANR 时限被打满。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val repository = SettingsOrientationRepository(
                    dataStore = appContext.settingsDataStore,
                    writer = SystemOrientationWriter(AndroidOrientationAccess(appContext)),
                )
                if (repository.snapshot().autoStartOnBoot) {
                    OrientLockApp.startOrientationServiceSafely(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
