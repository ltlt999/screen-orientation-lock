package com.orientlock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationModeTest {

    @Test
    fun `天然竖屏设备上竖屏映射到 0`() {
        assertEquals(0, OrientationMode.PORTRAIT.userRotationFor(NaturalOrientation.PORTRAIT))
    }

    @Test
    fun `天然竖屏设备上横屏映射到 1`() {
        assertEquals(1, OrientationMode.LANDSCAPE.userRotationFor(NaturalOrientation.PORTRAIT))
    }

    @Test
    fun `天然竖屏设备上反向竖屏映射到 2`() {
        assertEquals(2, OrientationMode.PORTRAIT_REVERSE.userRotationFor(NaturalOrientation.PORTRAIT))
    }

    @Test
    fun `天然竖屏设备上反向横屏映射到 3`() {
        assertEquals(3, OrientationMode.LANDSCAPE_REVERSE.userRotationFor(NaturalOrientation.PORTRAIT))
    }

    @Test
    fun `天然横屏设备上横屏映射到 0`() {
        assertEquals(0, OrientationMode.LANDSCAPE.userRotationFor(NaturalOrientation.LANDSCAPE))
    }

    @Test
    fun `天然横屏设备上竖屏映射到 1`() {
        assertEquals(1, OrientationMode.PORTRAIT.userRotationFor(NaturalOrientation.LANDSCAPE))
    }

    @Test
    fun `天然横屏设备上反向横屏映射到 2`() {
        assertEquals(2, OrientationMode.LANDSCAPE_REVERSE.userRotationFor(NaturalOrientation.LANDSCAPE))
    }

    @Test
    fun `天然横屏设备上反向竖屏映射到 3`() {
        assertEquals(3, OrientationMode.PORTRAIT_REVERSE.userRotationFor(NaturalOrientation.LANDSCAPE))
    }

    @Test
    fun `自动模式不写用户角度返回 null`() {
        assertNull(OrientationMode.AUTO.userRotationFor(NaturalOrientation.PORTRAIT))
        assertNull(OrientationMode.AUTO.userRotationFor(NaturalOrientation.LANDSCAPE))
    }

    @Test
    fun `当前方向模式由调用方提供角度映射返回 null`() {
        assertNull(OrientationMode.CURRENT.userRotationFor(NaturalOrientation.PORTRAIT))
    }

    @Test
    fun `每个模式都有非空中文标签`() {
        OrientationMode.entries.forEach { mode ->
            assertTrue("${mode.name} 标签为空", mode.label.isNotBlank())
        }
    }

    @Test
    fun `每个模式都有非空持久化名称`() {
        OrientationMode.entries.forEach { mode ->
            assertTrue("${mode.name} 持久化名为空", mode.storageName.isNotBlank())
        }
    }

    @Test
    fun `按持久化名称往返转换不丢失`() {
        OrientationMode.entries.forEach { mode ->
            assertEquals(mode, OrientationMode.fromStorageName(mode.storageName))
        }
    }

    @Test
    fun `未知持久化名称回落到自动模式`() {
        assertEquals(OrientationMode.AUTO, OrientationMode.fromStorageName("不存在的值"))
        assertEquals(OrientationMode.AUTO, OrientationMode.fromStorageName(null))
    }

    @Test
    fun `竖屏与横屏的反向模式互为反向`() {
        assertEquals(OrientationMode.PORTRAIT_REVERSE, OrientationMode.PORTRAIT.reversed())
        assertEquals(OrientationMode.PORTRAIT, OrientationMode.PORTRAIT_REVERSE.reversed())
        assertEquals(OrientationMode.LANDSCAPE_REVERSE, OrientationMode.LANDSCAPE.reversed())
        assertEquals(OrientationMode.LANDSCAPE, OrientationMode.LANDSCAPE_REVERSE.reversed())
    }

    @Test
    fun `当前方向与自动模式的反向是自身`() {
        assertEquals(OrientationMode.CURRENT, OrientationMode.CURRENT.reversed())
        assertEquals(OrientationMode.AUTO, OrientationMode.AUTO.reversed())
    }
}
