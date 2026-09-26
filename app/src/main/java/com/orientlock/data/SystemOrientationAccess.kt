package com.orientlock.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.Surface
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.naturalOrientationFrom

/**
 * 屏幕方向相关的全部系统读写。
 *
 * 把「平台读写」从「写入策略」中分离：策略在 [SystemOrientationWriter] 里，
 * 可在 JVM 上用假实现测试；机制在本接口的实现里，薄到不需要测。
 */
interface SystemOrientationAccess {
    fun canWrite(): Boolean
    fun userRotation(): Int
    fun autoRotate(): Boolean
    fun writeAutoRotate(enabled: Boolean)
    fun writeUserRotation(angle: Int)
    fun displayRotation(): Int

    /** 由当前逻辑宽高与旋转角反推天然朝向；宽高与旋转须取自同一次采样 */
    fun sampleNaturalOrientation(): NaturalOrientation
}

/** 走 ContentResolver 与 DisplayManager 的真实实现。 */
class AndroidOrientationAccess(private val context: Context) : SystemOrientationAccess {

    private val resolver get() = context.contentResolver

    override fun canWrite(): Boolean = Settings.System.canWrite(context)

    override fun userRotation(): Int = Settings.System.getInt(
        resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0
    )

    override fun autoRotate(): Boolean = Settings.System.getInt(
        resolver, Settings.System.ACCELEROMETER_ROTATION, 1
    ) != 0

    override fun writeAutoRotate(enabled: Boolean) {
        Settings.System.putInt(
            resolver,
            Settings.System.ACCELEROMETER_ROTATION,
            if (enabled) 1 else 0,
        )
    }

    override fun writeUserRotation(angle: Int) {
        Settings.System.putInt(resolver, Settings.System.USER_ROTATION, angle)
    }

    /**
     * 当前实际旋转角。
     *
     * 必须用 DisplayManager 而不是 Context.getDisplay()：后者 API 30 引入，
     * SDK javadoc 明确写着「not associated with any display」时抛
     * UnsupportedOperationException，而本类只在 Application / Service 上下文中使用。
     * DisplayManager API 17 起可用，任何 Context 都能拿到。
     */
    override fun displayRotation(): Int =
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
            ?: Surface.ROTATION_0

    override fun sampleNaturalOrientation(): NaturalOrientation {
        val metrics = context.resources.displayMetrics
        return naturalOrientationFrom(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            rotation = displayRotation(),
        )
    }
}
