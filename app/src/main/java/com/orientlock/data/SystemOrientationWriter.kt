package com.orientlock.data

import com.orientlock.domain.DisplayRotation
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.RotationState
import kotlinx.coroutines.delay

/**
 * 全应用唯一的方向写入策略。
 *
 * 锁定一个方向必须同时做两件事：先关闭自动旋转，再写入目标角度。顺序不能反——
 * 自动旋转开启时系统忽略 USER_ROTATION，且窗口管理器会用传感器值覆盖它；
 * 先关再写能保证一次确定的跳转。
 *
 * 本类只做决策，所有系统读写都经由 [SystemOrientationAccess]，
 * 因此可以在 JVM 上用假实现测试。
 */
class SystemOrientationWriter(
    private val access: SystemOrientationAccess,
    private val settle: suspend (Long) -> Unit = { delay(it) },
) {

    @Volatile
    private var cachedNatural: NaturalOrientation? = null

    /** 是否已获得「修改系统设置」特殊权限 */
    fun canWrite(): Boolean = access.canWrite()

    /**
     * 探测并缓存设备的天然朝向，同一进程内只算一次（取到稳定值才算）。
     *
     * 零系统写入：只读，不改任何设置。
     *
     * 早先的设计是先把 USER_ROTATION 置 0 再读宽高，那有三个问题：
     * 一是留下「自动旋转被悄悄关掉」的副作用；二是旋转重构是异步的，
     * 紧接着读到的可能还是旧方向的宽高；三是这个结果会被持久化，
     * 判错就永久错——它还会连带把 OrientationMode.CURRENT
     * 的固定角度污染成永远 0。
     *
     * 现在改成纯计算，但单次采样仍可能落在方向重构的中间态：
     * displayRotation() 已返回新值而 displayMetrics 还是旧值。
     * 因此连采两次、间隔 [SAMPLE_SETTLE_MS]，一致才采信并缓存；
     * 不一致说明设备正在转动，本进程先按第二次的结果用，但不缓存，
     * 下次调用会重新采样，直到取到稳定值。
     */
    suspend fun naturalOrientation(): NaturalOrientation {
        cachedNatural?.let { return it }
        val first = access.sampleNaturalOrientation()
        settle(SAMPLE_SETTLE_MS)
        val second = access.sampleNaturalOrientation()
        if (first == second) {
            cachedNatural = second
        }
        return second
    }

    fun readState(): RotationState =
        RotationState(userRotation = access.userRotation(), autoRotate = access.autoRotate())

    /** 设备当前实际的显示旋转角度 */
    fun displayRotation(): Int = access.displayRotation()

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
            OrientationMode.AUTO -> access.writeAutoRotate(true)

            OrientationMode.CURRENT -> {
                // 先校验再落盘。若反过来，非法角度会在抛异常前就把自动旋转关掉，
                // 给系统留一个「没锁成、自动旋转反而没了」的中间态。
                checkAngle(pinnedRotationForCurrent)
                access.writeAutoRotate(false)
                writeUserRotation(pinnedRotationForCurrent)
            }

            else -> {
                val angle = mode.userRotationFor(natural)
                    ?: error("模式 $mode 不是固定角度模式")
                // 顺序有意为之：auto:off 必须先于 angle:。
                // 自动旋转开启时窗口管理器会用传感器值覆盖 USER_ROTATION，
                // 先关掉才能让写入的角度一次确定地生效。
                access.writeAutoRotate(false)
                writeUserRotation(angle)
            }
        }
    }

    private fun writeUserRotation(angle: Int) {
        checkAngle(angle)
        access.writeUserRotation(angle)
    }

    private fun checkAngle(angle: Int) {
        require(angle in DisplayRotation.NATURAL..DisplayRotation.THREE_QUARTER) {
            "非法旋转角度 $angle"
        }
    }

    private companion object {
        /** 方向重构的稳定等待；设备稳定时每进程一次，正在转动则每次探测都等 */
        const val SAMPLE_SETTLE_MS = 300L
    }
}
