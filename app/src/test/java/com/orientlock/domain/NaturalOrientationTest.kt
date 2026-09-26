package com.orientlock.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrientationTest {

    @Test
    fun `高大于宽时为天然竖屏`() {
        assertEquals(NaturalOrientation.PORTRAIT, naturalOrientationFrom(1080, 2400))
    }

    @Test
    fun `宽大于高时为天然横屏`() {
        assertEquals(NaturalOrientation.LANDSCAPE, naturalOrientationFrom(2400, 1080))
    }

    @Test
    fun `宽高相等时按竖屏处理`() {
        assertEquals(NaturalOrientation.PORTRAIT, naturalOrientationFrom(1200, 1200))
    }
}
