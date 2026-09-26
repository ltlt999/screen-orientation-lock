package com.orientlock.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrientationTest {

    @Test
    fun `旋转为 0 且高大于宽时为天然竖屏`() {
        assertEquals(NaturalOrientation.PORTRAIT, naturalOrientationFrom(1080, 2400, 0))
    }

    @Test
    fun `旋转为 0 且宽大于高时为天然横屏`() {
        assertEquals(NaturalOrientation.LANDSCAPE, naturalOrientationFrom(2400, 1080, 0))
    }

    @Test
    fun `旋转为 2 时宽高未互换仍按天然宽高判定`() {
        assertEquals(NaturalOrientation.PORTRAIT, naturalOrientationFrom(1080, 2400, 2))
        assertEquals(NaturalOrientation.LANDSCAPE, naturalOrientationFrom(2400, 1080, 2))
    }

    @Test
    fun `旋转为 1 时宽高互换竖屏设备横持仍判为竖屏`() {
        // 手机被横持：逻辑宽高报成 2400x1080，但天然朝向仍是竖屏
        assertEquals(NaturalOrientation.PORTRAIT, naturalOrientationFrom(2400, 1080, 1))
    }

    @Test
    fun `旋转为 1 时宽高互换横屏设备竖持仍判为横屏`() {
        // 平板被竖持：逻辑宽高报成 1080x2400，但天然朝向仍是横屏
        assertEquals(NaturalOrientation.LANDSCAPE, naturalOrientationFrom(1080, 2400, 1))
    }

    @Test
    fun `旋转为 3 时与旋转为 1 判定一致`() {
        assertEquals(NaturalOrientation.PORTRAIT, naturalOrientationFrom(2400, 1080, 3))
        assertEquals(NaturalOrientation.LANDSCAPE, naturalOrientationFrom(1080, 2400, 3))
    }

    @Test
    fun `宽高相等时按竖屏处理`() {
        assertEquals(NaturalOrientation.PORTRAIT, naturalOrientationFrom(1200, 1200, 0))
        assertEquals(NaturalOrientation.PORTRAIT, naturalOrientationFrom(1200, 1200, 1))
    }
}
