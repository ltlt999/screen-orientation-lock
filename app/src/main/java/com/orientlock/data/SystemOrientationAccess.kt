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

    /**
     * 写自动旋转开关。
     *
     * @return 是否写入成功。**没授予 WRITE_SETTINGS 时系统会抛 SecurityException，
     *   不是静默失败**——实测安卓 12：`java.lang.SecurityException: ... was not granted
     *   this permission: android.permission.WRITE_SETTINGS`。这里把它转成 false，
     *   让上层可以继续走悬浮窗那条通路，而不是让整个应用崩掉。
     */
    fun writeAutoRotate(enabled: Boolean): Boolean

    /** 写目标角度。失败语义同 [writeAutoRotate]。 */
    fun writeUserRotation(angle: Int): Boolean

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

    override fun writeAutoRotate(enabled: Boolean): Boolean = putIntSafely(
        Settings.System.ACCELEROMETER_ROTATION,
        if (enabled) 1 else 0,
    )

    override fun writeUserRotation(angle: Int): Boolean =
        putIntSafely(Settings.System.USER_ROTATION, angle)

    /**
     * 写 Settings.System，把「没权限」转成返回值而不是异常。
     *
     * 实测（安卓 12）：没授予 WRITE_SETTINGS 时 `putInt` 抛
     * `SecurityException: ... was not granted this permission`。
     * 这条异常若不接住会一路冒到 ViewModel 的协程里把应用打崩——
     * 而悬浮窗那条通路本来是能正常工作的，不该被它拖死。
     */
    private fun putIntSafely(key: String, value: Int): Boolean = try {
        Settings.System.putInt(resolver, key, value)
    } catch (e: SecurityException) {
        false
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
