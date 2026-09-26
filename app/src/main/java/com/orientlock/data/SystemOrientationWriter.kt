package com.orientlock.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Surface
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.RotationState
import com.orientlock.domain.naturalOrientationFrom

/**
 * 唯一接触 Settings.System 的类。
 *
 * 锁定一个方向必须同时做两件事：先关闭自动旋转，再写入目标角度。
 * 只写角度而不关自动旋转无效——自动旋转开启时系统忽略 USER_ROTATION。
 *
 * 天然朝向探测有副作用（会临时改写系统设置），因此 [naturalOrientation] 会把结果
 * 缓存在内存里，整个进程只探测一次。
 */
class SystemOrientationWriter(private val context: Context) {

    private var cachedNatural: NaturalOrientation? = null

    private val resolver get() = context.contentResolver

    /** 是否已获得「修改系统设置」特殊权限 */
    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    /**
     * 探测并缓存设备的天然朝向，同一进程内只探测一次。
     *
     * 做法：把 USER_ROTATION 短暂置 0（同时关掉自动旋转），此时设备必然处于天然
     * 朝向，读一次屏幕宽高即可判定。平板天然横屏，与手机的映射不同，
     * 不区分会导致锁出来的方向在平板和折叠屏上是反的。
     */
    fun naturalOrientation(): NaturalOrientation {
        cachedNatural?.let { return it }
        applyAutoRotate(false)
        applyUserRotation(Surface.ROTATION_0)
        val metrics = context.resources.displayMetrics
        return naturalOrientationFrom(metrics.widthPixels, metrics.heightPixels)
            .also { cachedNatural = it }
    }

    fun readState(): RotationState = RotationState(
        userRotation = Settings.System.getInt(
            resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0
        ),
        autoRotate = Settings.System.getInt(
            resolver, Settings.System.ACCELEROMETER_ROTATION, 1
        ) != 0,
    )

    /** 设备当前实际的显示旋转角度 */
    fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.rotation
        }

    /**
     * 应用一个方向模式。
     *
     * @param mode 目标模式
     * @param natural 天然朝向，用于把模式换算成系统角度值
     * @param pinnedRotationForCurrent 选中 [OrientationMode.CURRENT] 时要固定的角度
     */
    fun apply(
        mode: OrientationMode,
        natural: NaturalOrientation,
        pinnedRotationForCurrent: Int,
    ) {
        when (mode) {
            OrientationMode.AUTO -> applyAutoRotate(true)
            OrientationMode.CURRENT -> {
                applyAutoRotate(false)
                applyUserRotation(pinnedRotationForCurrent)
            }
            else -> {
                val angle = mode.userRotationFor(natural)
                    ?: error("模式 $mode 不是固定角度模式")
                applyAutoRotate(false)
                applyUserRotation(angle)
            }
        }
    }

    private fun applyAutoRotate(enabled: Boolean) {
        Settings.System.putInt(
            resolver,
            Settings.System.ACCELEROMETER_ROTATION,
            if (enabled) 1 else 0,
        )
    }

    private fun applyUserRotation(angle: Int) {
        require(angle in Surface.ROTATION_0..Surface.ROTATION_270) {
            "非法旋转角度 $angle"
        }
        Settings.System.putInt(resolver, Settings.System.USER_ROTATION, angle)
    }
}
