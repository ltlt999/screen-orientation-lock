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
 * 依据屏幕实际宽高判定天然朝向。
 *
 * 只在设备处于天然朝向时（即 USER_ROTATION 为 0 时）调用才有意义。
 * 两者相等时按竖屏处理：方形屏幕的设备上竖屏是更贴近直觉的默认。
 *
 * @param widthPx 天然朝向下屏幕的实际像素宽
 * @param heightPx 天然朝向下屏幕的实际像素高
 */
fun naturalOrientationFrom(widthPx: Int, heightPx: Int): NaturalOrientation =
    if (heightPx >= widthPx) NaturalOrientation.PORTRAIT else NaturalOrientation.LANDSCAPE
