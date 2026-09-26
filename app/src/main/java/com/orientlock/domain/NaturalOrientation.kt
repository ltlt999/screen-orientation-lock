package com.orientlock.domain

/**
 * 设备的天然朝向。
 *
 * 绝大多数手机为 [PORTRAIT]，平板与展开的折叠屏为 [LANDSCAPE]。
 * 天然朝向决定 [OrientationMode] 到 Settings.System.USER_ROTATION 的映射，
 * 详见 OrientationMode.userRotationFor。
 */
enum class NaturalOrientation {
    PORTRAIT,
    LANDSCAPE,
}

/**
 * 与 android.view.Surface.ROTATION_* 等值的显示旋转角。
 *
 * 放在 domain 层，是为了让 [naturalOrientationFrom] 保持纯 Kotlin、
 * 能脱离安卓框架做单元测试。调用方（SystemOrientationWriter）
 * 直接把 Display.getRotation() 的返回值传进来，取值自然一致。
 */
object DisplayRotation {
    const val NATURAL = 0
    const val QUARTER = 1
    const val HALF = 2
    const val THREE_QUARTER = 3
}

/**
 * 依据当前逻辑宽高与实际旋转角，反推设备的天然朝向。
 *
 * 为什么必须带旋转角：displayMetrics 报的是**当前旋转之后**的逻辑宽高。
 * 旋转为 [DisplayRotation.NATURAL] 或 [DisplayRotation.HALF] 时，逻辑宽高就是天然宽高；
 * 旋转为 [DisplayRotation.QUARTER] 或 [DisplayRotation.THREE_QUARTER] 时，两者互换。
 *
 * 这样不需要强制改写系统设置把设备"摆正"再测量，因此没有任何副作用，
 * 也不依赖旋转重构的完成时机。
 *
 * @param widthPx 当前逻辑显示宽（像素）
 * @param heightPx 当前逻辑显示高（像素）
 * @param rotation 当前实际旋转角，取 [DisplayRotation] 之一
 */
fun naturalOrientationFrom(
    widthPx: Int,
    heightPx: Int,
    rotation: Int,
): NaturalOrientation {
    val naturalIsPortrait = when (rotation) {
        DisplayRotation.NATURAL,
        DisplayRotation.HALF -> heightPx >= widthPx

        DisplayRotation.QUARTER,
        DisplayRotation.THREE_QUARTER -> widthPx >= heightPx

        else -> heightPx >= widthPx
    }
    return if (naturalIsPortrait) NaturalOrientation.PORTRAIT else NaturalOrientation.LANDSCAPE
}
