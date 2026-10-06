package com.orientlock.data

import com.orientlock.domain.DisplayRotation
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.RotationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全应用唯一的方向写入策略。
 *
 * 锁定一个方向必须同时做两件事：先关闭自动旋转，再写入目标角度。顺序不能反——
 * 自动旋转开启时系统忽略 USER_ROTATION，且窗口管理器会用传感器值覆盖它；
 * 先关再写能保证一次确定的跳转。
 *
 * **写入失败不算错误。** 没授予 WRITE_SETTINGS 时系统会抛 SecurityException
 * （见 [SystemOrientationAccess.writeUserRotation]），这里已经把它转成返回值，
 * 本类只忽略、不抛。原因是写系统设置只是**次要通路**：主通路是
 * [com.orientlock.system.OverlayOrientationController] 的悬浮窗，
 * 它能压过应用自己声明的方向，而写设置不能。只有悬浮窗也拿不到时才真的锁不住，
 * 那个状态由通知文案和界面提示如实反映。
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

    /** 探测互斥，见 [naturalOrientation] 的说明 */
    private val probeMutex = Mutex()

    /** 是否已获得「修改系统设置」特殊权限 */
    fun canWrite(): Boolean = access.canWrite()

    /**
     * 天然朝向探测结果。
     *
     * @property orientation 本次采到的天然朝向
     * @property stable 两次采样是否一致。false 表示设备当时正在转动、
     *   displayMetrics 还没跟上 displayRotation，该值**不可落盘**——
     *   落盘了就永久错，而且只犯一次错。
     */
    data class NaturalOrientationReading(
        val orientation: NaturalOrientation,
        val stable: Boolean,
    )

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
     * 因此连采两次、间隔 [SAMPLE_SETTLE_MS]：
     * - 一致 → 采信、缓存，[NaturalOrientationReading.stable] 为 true
     * - 不一致 → 返回第二次的值（让本次调用有数可用），但不缓存，
     *   [NaturalOrientationReading.stable] 为 false，调用方**不得**把它写进
     *   DataStore，否则一个被转动的瞬间就会永久定错整个设备的方向映射。
     *
     * 探测互斥：[probeMutex] 保证同一进程内只有一个探测在跑。没有它的话，
     * ViewModel 与前台服务两个作用域可能在 [settle] 的挂起点交错，
     * 各跑一次探测、各写一次盘，last-writer-wins。
     */
    suspend fun naturalOrientation(): NaturalOrientationReading = probeMutex.withLock {
        cachedNatural?.let { return@withLock NaturalOrientationReading(it, true) }
        val first = access.sampleNaturalOrientation()
        settle(SAMPLE_SETTLE_MS)
        val second = access.sampleNaturalOrientation()
        val stable = first == second
        if (stable) {
            cachedNatural = second
        }
        NaturalOrientationReading(second, stable)
    }

    fun readState(): RotationState =
        RotationState(userRotation = access.userRotation(), autoRotate = access.autoRotate())

    /** 设备当前实际的显示旋转角度 */
    fun displayRotation(): Int = access.displayRotation()

    /**
     * 应用一个方向模式。
     *
     * @param mode 目标模式
     * @param natural 天然朝向。仅固定角度模式需要，[OrientationMode.AUTO] 可传 null——
     *   它的分支只开自动旋转开关，不读这个值；传 null 是为了让调用方
     *   （尤其是解锁路径）不必先跑一遍天然朝向探测。
     * @param pinnedRotationForCurrent 选中 [OrientationMode.CURRENT] 时要固定的角度
     */
    fun apply(
        mode: OrientationMode,
        natural: NaturalOrientation?,
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
                val angle = mode.userRotationFor(
                    requireNotNull(natural) { "固定角度模式需要天然朝向，AUTO 才可传 null" }
                ) ?: error("模式 $mode 不是固定角度模式")
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
