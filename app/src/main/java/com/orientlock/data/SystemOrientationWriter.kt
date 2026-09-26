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
 * 天然朝向探测无副作用，结果缓存在内存里，整个进程只算一次。
 */
class SystemOrientationWriter(private val context: Context) {

    private var cachedNatural: NaturalOrientation? = null

    private val resolver get() = context.contentResolver

    /** 是否已获得「修改系统设置」特殊权限 */
    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    /**
     * 探测并缓存设备的天然朝向，同一进程内只探测一次。
     *
     * 做法：直接由「当前逻辑宽高 + 当前旋转角」反推，不改写任何系统设置。
     *
     * 早先的设计是先把 USER_ROTATION 置 0 再读宽高，那有三个问题：
     * 一是留下「自动旋转被悄悄关掉」的副作用；二是旋转重构是异步的，
     * 紧接着读到的可能还是旧方向的宽高；三是这个结果会被持久化，
     * 判错就永久错——它还会连带把 OrientationMode.CURRENT
     * 的固定角度污染成永远 0。当前实现三条一并消除。
     */
    fun naturalOrientation(): NaturalOrientation {
        cachedNatural?.let { return it }
        val rotation = displayRotation()
        val metrics = context.resources.displayMetrics
        return naturalOrientationFrom(metrics.widthPixels, metrics.heightPixels, rotation)
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
