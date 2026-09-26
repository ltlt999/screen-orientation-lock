package com.orientlock.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** 各类系统设置页的跳转 Intent，集中在一处便于 UI 复用 */
object PermissionIntents {

    /** 请求通知权限的权限名（Android 13+ 才有意义） */
    const val NOTIFICATION_PERMISSION: String = android.Manifest.permission.POST_NOTIFICATIONS

    /** 打开「修改系统设置」特殊权限页 */
    fun writeSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** 应用详情页，作为所有 ROM 引导的通用落点 */
    fun appDetails(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * 各国产 ROM 的自启动 / 后台管理设置页。
     *
     * 逐个探测能否解析，返回第一个可用的；都不行时调用方回落到 [appDetails]。
     */
    fun romAutoStart(context: Context): Intent? {
        val candidates = listOf(
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            Intent().setClassName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            Intent().setClassName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            Intent().setClassName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
        )
        return candidates.firstOrNull { intent ->
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }
    }
}
