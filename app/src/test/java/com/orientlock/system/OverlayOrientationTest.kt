package com.orientlock.system

import android.content.pm.ActivityInfo
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 悬浮窗方向映射的测试。
 *
 * 这层映射最容易被写错的地方是**混用两套取值**：`USER_ROTATION`（相对天然朝向的 0..3）
 * 和 `ActivityInfo.SCREEN_ORIENTATION_*`（绝对方向）。同一个 `1` 在前者是
 * 「天然朝向顺时针 90°」，在后者却是「竖屏」。混用会让横屏设备锁出竖屏，
 * 所以这里把两种天然朝向下的每个模式都钉住。
 */
class OverlayOrientationTest {

    private val phone = NaturalOrientation.PORTRAIT
    private val tablet = NaturalOrientation.LANDSCAPE

    @Test
    fun `竖屏映射为竖屏`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OrientationMode.PORTRAIT.overlayOrientationFor(phone, 0),
        )
        // 绝对方向与天然朝向无关：平板锁竖屏同样是 PORTRAIT
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OrientationMode.PORTRAIT.overlayOrientationFor(tablet, 0),
        )
    }

    @Test
    fun `横屏映射为横屏`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            OrientationMode.LANDSCAPE.overlayOrientationFor(phone, 0),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            OrientationMode.LANDSCAPE.overlayOrientationFor(tablet, 0),
        )
    }

    @Test
    fun `反向竖屏映射为反向竖屏`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            OrientationMode.PORTRAIT_REVERSE.overlayOrientationFor(phone, 0),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            OrientationMode.PORTRAIT_REVERSE.overlayOrientationFor(tablet, 0),
        )
    }

    @Test
    fun `反向横屏映射为反向横屏`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            OrientationMode.LANDSCAPE_REVERSE.overlayOrientationFor(phone, 0),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            OrientationMode.LANDSCAPE_REVERSE.overlayOrientationFor(tablet, 0),
        )
    }

    @Test
    fun `自动模式撤下接管`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            OrientationMode.AUTO.overlayOrientationFor(phone, 0),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            OrientationMode.AUTO.overlayOrientationFor(tablet, 0),
        )
    }

    @Test
    fun `当前方向在手机上按固定角度反查出绝对方向`() {
        // 手机上 USER_ROTATION 0=竖屏、1=横屏、2=反向竖屏、3=反向横屏
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OrientationMode.CURRENT.overlayOrientationFor(phone, 0),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            OrientationMode.CURRENT.overlayOrientationFor(phone, 1),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            OrientationMode.CURRENT.overlayOrientationFor(phone, 2),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            OrientationMode.CURRENT.overlayOrientationFor(phone, 3),
        )
    }

    @Test
    fun `当前方向在平板上映射是反过来的`() {
        // 平板天然横屏：USER_ROTATION 0=横屏、1=竖屏——这一条是两套取值最容易搞混的地方
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            OrientationMode.CURRENT.overlayOrientationFor(tablet, 0),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OrientationMode.CURRENT.overlayOrientationFor(tablet, 1),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            OrientationMode.CURRENT.overlayOrientationFor(tablet, 2),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            OrientationMode.CURRENT.overlayOrientationFor(tablet, 3),
        )
    }

    @Test
    fun `当前方向的固定角度越界时撤下接管而不是锁错方向`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            OrientationMode.CURRENT.overlayOrientationFor(phone, 9),
        )
    }

    @Test
    fun `四个固定模式的映射与固定角度无关`() {
        // 固定模式不该受 pinnedRotation 影响；传入异常值也不该改变结果
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OrientationMode.PORTRAIT.overlayOrientationFor(phone, 3),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            OrientationMode.LANDSCAPE.overlayOrientationFor(tablet, 7),
        )
    }
}
