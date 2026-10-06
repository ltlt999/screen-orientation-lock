package com.orientlock.system

import android.content.pm.ActivityInfo
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode

/** 四个固定角度模式，用于把「当前方向」的固定角度反查回具体方向 */
private val FIXED_MODES = listOf(
    OrientationMode.PORTRAIT,
    OrientationMode.PORTRAIT_REVERSE,
    OrientationMode.LANDSCAPE,
    OrientationMode.LANDSCAPE_REVERSE,
)

/**
 * 该模式要施加给悬浮窗的方向值（`ActivityInfo.SCREEN_ORIENTATION_*`）。
 *
 * 与 [com.orientlock.domain.OrientationMode.userRotationFor] 的区别很重要：
 * - `userRotationFor` 返回的是 **USER_ROTATION 值**（相对设备天然朝向的 0..3），
 *   用于写系统设置，只在「没有应用声明方向」时才生效；
 * - 本函数返回的是**绝对方向**（竖屏 / 横屏 / 反向竖屏 / 反向横屏），
 *   用于悬浮窗，**能压过应用自己声明的方向**。
 *
 * 两套取值不能混用：同一个 `1` 在 USER_ROTATION 里是「天然朝向顺时针 90°」，
 * 在 ActivityInfo 里却是「竖屏」。混用会让横屏设备锁出竖屏。
 *
 * @param natural 设备天然朝向，用于把「当前方向」的固定角度换算成绝对方向
 * @param pinnedRotation 选中「当前方向」瞬间固定的 USER_ROTATION 值（0..3）
 * @return 施加给悬浮窗的方向；[OrientationMode.AUTO] 返回 `UNSPECIFIED`，表示撤下接管
 */
fun OrientationMode.overlayOrientationFor(
    natural: NaturalOrientation,
    pinnedRotation: Int,
): Int = when (this) {
    OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    OrientationMode.PORTRAIT_REVERSE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    OrientationMode.LANDSCAPE_REVERSE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE

    // 「当前方向」存的是 USER_ROTATION 值，先反查出它对应哪个固定方向，
    // 再取那个方向的绝对取值。查不到（数据异常）就撤下接管，宁可不管也不要锁错方向。
    OrientationMode.CURRENT -> FIXED_MODES
        .firstOrNull { it.userRotationFor(natural) == pinnedRotation }
        ?.overlayOrientationFor(natural, pinnedRotation)
        ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
