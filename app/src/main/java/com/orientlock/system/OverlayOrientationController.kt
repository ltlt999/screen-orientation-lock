package com.orientlock.system

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * 用「悬浮窗」强制整个显示的方向。
 *
 * 这是 [com.orientlock.data.SystemOrientationWriter] 之外的**第二条通路**，也是唯一
 * 能越过「应用自己声明了方向」这条安卓规则的办法。
 *
 * 为什么需要它：安卓的优先级是「应用显式声明的方向 > 全局设置」。
 * `Settings.System.ACCELEROMETER_ROTATION` 的官方文档写着「If 0, it will not be used
 * **unless explicitly requested by the application**」——所以对声明了 `sensorLandscape`
 * 这类应用（车机桌面、部分视频/音乐应用），写系统设置完全无效。
 *
 * 原理：给 WindowManager 添加一个 0×0 的 `TYPE_APPLICATION_OVERLAY` 窗口，并在它的
 * LayoutParams 上设置 `screenOrientation`。窗口管理器在计算显示方向时会把窗口的
 * 方向请求纳入考虑，而悬浮窗在层级上高于普通应用窗口，于是应用自己的声明被压过去。
 *
 * 代价：需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW），且窗口必须由
 * 长期存活的对象持有——因此由前台服务持有，而不是 Activity 或 ViewModel。
 */
class OverlayOrientationController(context: Context) {

    /**
     * 悬浮窗必须挂在「与显示关联」的 Context 上。
     *
     * 直接拿 Application context 加 TYPE_APPLICATION_OVERLAY 在部分设备上会被拒；
     * 正确做法是先 createDisplayContext 再 createWindowContext。
     * `createWindowContext` 是 API 30 才有的，低版本退回 displayContext。
     */
    private val windowContext: Context = run {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return@run context
        val displayContext = context.createDisplayContext(display)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext.createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null,
            )
        } else {
            displayContext
        }
    }

    private val view = View(windowContext)

    private val windowManager =
        windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val layoutParams = WindowManager.LayoutParams(
        0,
        0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private var attached = false

    /** 当前生效的方向请求；UNSPECIFIED 表示未接管 */
    val currentOrientation: Int get() = layoutParams.screenOrientation

    /**
     * 是否真的在接管方向：窗口已挂上，且方向请求不是 UNSPECIFIED。
     *
     * 与 [currentOrientation] 的区别：后者只反映「想请求什么」，
     * 前者才反映「系统接受了没有」。没拿到悬浮窗权限时窗口挂不上去，
     * 这里为 false，而 [currentOrientation] 仍会是请求值——通知文案要按前者判断，
     * 否则会在没接管成功时也显示「已锁定」。
     */
    val isHolding: Boolean
        get() = attached && layoutParams.screenOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /**
     * 设置要强制施加的方向。
     *
     * 传 [ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED] 表示撤下接管，
     * 交还给系统与应用自己声明。
     *
     * @return true 表示窗口操作成功；false 表示被系统拒绝（通常是没拿到悬浮窗权限）
     */
    fun setOrientation(orientation: Int): Boolean {
        if (orientation == layoutParams.screenOrientation && attached == (orientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)) {
            return true
        }
        layoutParams.screenOrientation = orientation
        return runCatching {
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                if (attached) {
                    windowManager.removeViewImmediate(view)
                    attached = false
                }
            } else {
                if (attached) {
                    windowManager.updateViewLayout(view, layoutParams)
                } else {
                    windowManager.addView(view, layoutParams)
                    attached = true
                }
            }
            true
        }.getOrElse { false }
    }

    /** 撤下接管并释放窗口 */
    fun stop() {
        layoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (attached) {
            runCatching { windowManager.removeViewImmediate(view) }
            attached = false
        }
    }
}
