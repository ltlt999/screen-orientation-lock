package com.orientlock.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationGuardTest {

    private val natural = NaturalOrientation.PORTRAIT

    @Test
    fun `目标为自动时永不重新应用`() {
        val current = RotationState(userRotation = 1, autoRotate = true)
        assertFalse(shouldReapply(current, OrientationMode.AUTO, natural))
    }

    @Test
    fun `系统角度已与目标一致且自动旋转关闭时不重新应用`() {
        val current = RotationState(userRotation = 1, autoRotate = false)
        assertFalse(shouldReapply(current, OrientationMode.LANDSCAPE, natural))
    }

    @Test
    fun `系统角度被改掉时需重新应用`() {
        val current = RotationState(userRotation = 0, autoRotate = false)
        assertTrue(shouldReapply(current, OrientationMode.LANDSCAPE, natural))
    }

    @Test
    fun `自动旋转被重新打开时需重新应用`() {
        val current = RotationState(userRotation = 1, autoRotate = true)
        assertTrue(shouldReapply(current, OrientationMode.LANDSCAPE, natural))
    }

    @Test
    fun `目标为当前方向时只要自动旋转关闭就不重新应用`() {
        val current = RotationState(userRotation = 3, autoRotate = false)
        assertFalse(shouldReapply(current, OrientationMode.CURRENT, natural))
    }

    @Test
    fun `目标为当前方向时自动旋转被打开则需重新应用`() {
        val current = RotationState(userRotation = 3, autoRotate = true)
        assertTrue(shouldReapply(current, OrientationMode.CURRENT, natural))
    }

    @Test
    fun `天然横屏设备上横屏判定用角度 0`() {
        val tablet = NaturalOrientation.LANDSCAPE
        val current = RotationState(userRotation = 0, autoRotate = false)
        assertFalse(shouldReapply(current, OrientationMode.LANDSCAPE, tablet))
    }
}
