package com.orientlock.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 权限查询接口，供 ViewModel 依赖（测试用假实现） */
interface PermissionChecker {
    fun canWriteSettings(): Boolean
    fun canPostNotifications(): Boolean

    /**
     * 能否显示在其他应用上层（悬浮窗）。
     *
     * 这是**唯一能越过「应用自己声明了方向」那条安卓规则**的权限：
     * 有它才能锁住声明了 sensorLandscape 之类的应用（车机桌面、部分音视频应用），
     * 光有 WRITE_SETTINGS 是锁不住的。
     */
    fun canDrawOverlays(): Boolean
}

class AndroidPermissionChecker(private val context: Context) : PermissionChecker {

    override fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    override fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    companion object {
        /** 供前台服务在构造 PermissionChecker 之外直接查询用 */
        fun canPostNotifications(context: Context): Boolean =
            AndroidPermissionChecker(context).canPostNotifications()

        fun canDrawOverlays(context: Context): Boolean =
            AndroidPermissionChecker(context).canDrawOverlays()
    }
}
