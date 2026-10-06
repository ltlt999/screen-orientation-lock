package com.orientlock.data

import com.orientlock.domain.DisplayRotation
import com.orientlock.domain.NaturalOrientation

/** 记录全部读写操作的测试替身 */
class FakeOrientationAccess(
    var canWriteResult: Boolean = true,
    var rotation: Int = DisplayRotation.NATURAL,
    /**
     * 写系统设置是否成功。置 false 用来模拟「没授予 WRITE_SETTINGS」——
     * 真实设备上那会让 putInt 抛 SecurityException，实测确认过。
     */
    var writesSucceed: Boolean = true,
) : SystemOrientationAccess {

    /** 按发生顺序记录的写操作，形如 "auto:on" / "auto:off" / "angle:1" */
    val writes = mutableListOf<String>()

    /** sampleNaturalOrientation() 被调用的次数，用于断言是否发生了重新采样 */
    var sampleCalls = 0

    private var autoRotateOn = true

    /** 待返回的「天然朝向」采样队列；见 [reportNaturalOnce] */
    private val naturalSamples = ArrayDeque<NaturalOrientation>()

    override fun canWrite(): Boolean = canWriteResult

    override fun userRotation(): Int = rotation

    override fun autoRotate(): Boolean = autoRotateOn

    override fun writeAutoRotate(enabled: Boolean): Boolean {
        writes += if (enabled) "auto:on" else "auto:off"
        if (!writesSucceed) return false
        autoRotateOn = enabled
        return true
    }

    override fun writeUserRotation(angle: Int): Boolean {
        writes += "angle:$angle"
        if (!writesSucceed) return false
        rotation = angle
        return true
    }

    override fun displayRotation(): Int = rotation

    /** 队列里还有多个值就逐个发完，之后一直重复最后一个 */
    override fun sampleNaturalOrientation(): NaturalOrientation {
        sampleCalls++
        return if (naturalSamples.size > 1) naturalSamples.removeFirst()
        else naturalSamples.lastOrNull() ?: NaturalOrientation.PORTRAIT
    }

    /** 之后所有采样都返回同一个值 */
    fun reportNatural(value: NaturalOrientation) {
        naturalSamples.clear()
        naturalSamples.addLast(value)
    }

    /**
     * 接下来两次采样分别返回 [first] 和 [second]，用于模拟
     * 「设备正在转动、两次采样取到了不同方向」。
     */
    fun reportNaturalOnce(first: NaturalOrientation, second: NaturalOrientation) {
        naturalSamples.clear()
        naturalSamples.addLast(first)
        naturalSamples.addLast(second)
    }
}
