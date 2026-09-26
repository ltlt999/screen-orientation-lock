package com.orientlock.data

import com.orientlock.domain.DisplayRotation
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.RotationState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemOrientationWriterTest {

    /** 测试里不真的等待 300ms，settle 传空实现 */
    private fun writerOf(
        access: FakeOrientationAccess = FakeOrientationAccess(),
    ): Pair<SystemOrientationWriter, FakeOrientationAccess> =
        SystemOrientationWriter(access) { } to access

    @Test
    fun `锁竖屏时先关自动旋转再写角度 0`() = runTest {
        val (writer, access) = writerOf()

        writer.apply(OrientationMode.PORTRAIT, NaturalOrientation.PORTRAIT, 0)

        assertEquals(listOf("auto:off", "angle:0"), access.writes)
    }

    @Test
    fun `天然横屏设备上锁横屏写角度 0`() = runTest {
        val (writer, access) = writerOf()

        writer.apply(OrientationMode.LANDSCAPE, NaturalOrientation.LANDSCAPE, 0)

        assertEquals(listOf("auto:off", "angle:0"), access.writes)
    }

    @Test
    fun `天然横屏设备上锁竖屏写角度 1`() = runTest {
        val (writer, access) = writerOf()

        writer.apply(OrientationMode.PORTRAIT, NaturalOrientation.LANDSCAPE, 0)

        assertEquals(listOf("auto:off", "angle:1"), access.writes)
    }

    @Test
    fun `锁反向竖屏写角度 2`() = runTest {
        val (writer, access) = writerOf()

        writer.apply(OrientationMode.PORTRAIT_REVERSE, NaturalOrientation.PORTRAIT, 0)

        assertEquals(listOf("auto:off", "angle:2"), access.writes)
    }

    @Test
    fun `自动模式只开自动旋转不写角度`() = runTest {
        val (writer, access) = writerOf()

        writer.apply(OrientationMode.AUTO, NaturalOrientation.PORTRAIT, 0)

        assertEquals(listOf("auto:on"), access.writes)
    }

    @Test
    fun `当前方向模式写入固定角度`() = runTest {
        val (writer, access) = writerOf()

        writer.apply(OrientationMode.CURRENT, NaturalOrientation.PORTRAIT, 3)

        assertEquals(listOf("auto:off", "angle:3"), access.writes)
    }

    @Test
    fun `非法旋转角度被拒绝`() = runTest {
        val (writer, access) = writerOf()

        val error = runCatching {
            writer.apply(OrientationMode.CURRENT, NaturalOrientation.PORTRAIT, 4)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(access.writes.isEmpty())
    }

    @Test
    fun `读取系统状态映射为 RotationState`() = runTest {
        val access = FakeOrientationAccess(rotation = 2)
        val (writer) = writerOf(access)
        access.writeAutoRotate(false)

        assertEquals(
            RotationState(userRotation = 2, autoRotate = false),
            writer.readState(),
        )
    }

    @Test
    fun `探测天然朝向不写任何系统设置`() = runTest {
        val access = FakeOrientationAccess()
        access.writeAutoRotate(true)
        access.writes.clear()
        val (writer) = writerOf(access)

        writer.naturalOrientation()

        assertTrue("探测不得修改系统设置，实际写入了 ${access.writes}", access.writes.isEmpty())
    }

    @Test
    fun `两次采样一致时缓存天然朝向`() = runTest {
        val access = FakeOrientationAccess()
        access.reportNatural(NaturalOrientation.LANDSCAPE)
        val (writer) = writerOf(access)

        val first = writer.naturalOrientation()
        access.reportNatural(NaturalOrientation.PORTRAIT)
        val second = writer.naturalOrientation()

        assertEquals(NaturalOrientation.LANDSCAPE, first)
        assertEquals("缓存后不应重新采样", first, second)
    }

    @Test
    fun `两次采样不一致时不缓存下次重新采样`() = runTest {
        val access = FakeOrientationAccess()
        val (writer) = writerOf(access)

        access.reportNaturalOnce(NaturalOrientation.PORTRAIT, NaturalOrientation.LANDSCAPE)
        val observed = writer.naturalOrientation()
        assertEquals("不一致时以第二次为准", NaturalOrientation.LANDSCAPE, observed)

        access.reportNatural(NaturalOrientation.LANDSCAPE)
        val again = writer.naturalOrientation()
        assertEquals("未缓存所以重新采样", NaturalOrientation.LANDSCAPE, again)
    }

    @Test
    fun `显示旋转角来自平台实现`() = runTest {
        val access = FakeOrientationAccess(rotation = DisplayRotation.THREE_QUARTER)
        val (writer) = writerOf(access)

        assertEquals(DisplayRotation.THREE_QUARTER, writer.displayRotation())
    }

    @Test
    fun `权限查询转交平台实现`() = runTest {
        val access = FakeOrientationAccess(canWriteResult = false)
        val (writer) = writerOf(access)

        assertFalse(writer.canWrite())
    }
}
