package com.orientlock.data

import com.orientlock.domain.DisplayRotation
import com.orientlock.domain.NaturalOrientation

/** 记录全部读写操作的测试替身 */
class FakeOrientationAccess(
    var canWriteResult: Boolean = true,
    var rotation: Int = DisplayRotation.NATURAL,
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

    override fun writeAutoRotate(enabled: Boolean) {
        writes += if (enabled) "auto:on" else "auto:off"
        autoRotateOn = enabled
    }

    override fun writeUserRotation(angle: Int) {
        writes += "angle:$angle"
        rotation = angle
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
