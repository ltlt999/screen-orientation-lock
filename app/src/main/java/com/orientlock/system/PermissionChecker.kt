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

    companion object {
        /** 供前台服务在构造 PermissionChecker 之外直接查询用 */
        fun canPostNotifications(context: Context): Boolean =
            AndroidPermissionChecker(context).canPostNotifications()
    }
}
