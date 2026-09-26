package com.orientlock.data

import androidx.datastore.preferences.core.intPreferencesKey
import com.orientlock.domain.DisplayRotation
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOrientationRepositoryTest {

    private fun fixture(
        natural: NaturalOrientation = NaturalOrientation.PORTRAIT,
    ): Triple<SettingsOrientationRepository, FakeOrientationAccess, FakeDataStore> {
        val dataStore = FakeDataStore()
        val access = FakeOrientationAccess().also { it.reportNatural(natural) }
        val writer = SystemOrientationWriter(access) { }
        return Triple(SettingsOrientationRepository(dataStore, writer), access, dataStore)
    }

    @Test
    fun `锁竖屏后落盘模式与角度 0 并写入系统`() = runTest {
        val (repo, access, _) = fixture()

        repo.setMode(OrientationMode.PORTRAIT)

        assertEquals(OrientationMode.PORTRAIT, repo.snapshot().mode)
        assertEquals(0, repo.snapshot().pinnedRotation)
        assertEquals(listOf("auto:off", "angle:0"), access.writes)
    }

    @Test
    fun `锁横屏写入角度 1`() = runTest {
        val (repo, access, _) = fixture()

        repo.setMode(OrientationMode.LANDSCAPE)

        assertEquals(listOf("auto:off", "angle:1"), access.writes)
    }

    @Test
    fun `天然横屏设备上锁横屏写入角度 0`() = runTest {
        val (repo, access, _) = fixture(NaturalOrientation.LANDSCAPE)

        repo.setMode(OrientationMode.LANDSCAPE)

        assertEquals(listOf("auto:off", "angle:0"), access.writes)
    }

    @Test
    fun `锁当前方向落盘抓到的角度`() = runTest {
        val (repo, access, _) = fixture()
        access.rotation = DisplayRotation.THREE_QUARTER

        repo.setMode(OrientationMode.CURRENT)

        assertEquals(DisplayRotation.THREE_QUARTER, repo.snapshot().pinnedRotation)
        assertEquals(listOf("auto:off", "angle:3"), access.writes)
    }

    @Test
    fun `解锁只开自动旋转且不触发天然朝向探测`() = runTest {
        val (repo, access, _) = fixture()

        repo.setMode(OrientationMode.AUTO)

        assertEquals(listOf("auto:on"), access.writes)
        assertEquals("解锁不应触发探测", 0, access.sampleCalls)
        assertNull("解锁不应落盘天然朝向", repo.snapshot().naturalOrientation)
    }

    @Test
    fun `锁固定角度模式会落盘稳定的天然朝向`() = runTest {
        val (repo, access, _) = fixture(NaturalOrientation.LANDSCAPE)

        repo.setMode(OrientationMode.PORTRAIT)

        assertEquals(NaturalOrientation.LANDSCAPE, repo.snapshot().naturalOrientation)
        assertEquals(2, access.sampleCalls)
    }

    @Test
    fun `探测不稳定时不落盘天然朝向`() = runTest {
        val (repo, _, _) = fixture()
        val dataStore = FakeDataStore()
        val access = FakeOrientationAccess()
        val repo2 = SettingsOrientationRepository(dataStore, SystemOrientationWriter(access) { })

        access.reportNaturalOnce(NaturalOrientation.PORTRAIT, NaturalOrientation.LANDSCAPE)
        repo2.setMode(OrientationMode.LANDSCAPE)

        assertNull("不稳定的探测结果不得落盘", repo2.snapshot().naturalOrientation)
        assertEquals("模式本身仍应落盘", OrientationMode.LANDSCAPE, repo2.snapshot().mode)
    }

    @Test
    fun `守护关闭时心跳不写系统`() = runTest {
        val (repo, access, _) = fixture()
        repo.setGuardEnabled(false)
        access.writes.clear()

        assertFalse(repo.guardTick())

        assertTrue(access.writes.isEmpty())
    }

    @Test
    fun `目标为自动时心跳不写系统也不探测`() = runTest {
        val (repo, access, _) = fixture()
        repo.setMode(OrientationMode.AUTO)
        access.writes.clear()
        access.sampleCalls = 0

        assertFalse(repo.guardTick())

        assertTrue(access.writes.isEmpty())
        assertEquals(0, access.sampleCalls)
    }

    @Test
    fun `系统偏离目标时心跳重写`() = runTest {
        val (repo, access, _) = fixture()
        repo.setMode(OrientationMode.PORTRAIT)
        access.writes.clear()
        // 模拟方向被别的途径改掉
        access.rotation = DisplayRotation.QUARTER

        assertTrue(repo.guardTick())

        assertEquals(listOf("auto:off", "angle:0"), access.writes)
    }

    @Test
    fun `系统与目标一致时心跳不写`() = runTest {
        val (repo, access, _) = fixture()
        repo.setMode(OrientationMode.PORTRAIT)
        access.writes.clear()
        access.rotation = DisplayRotation.NATURAL

        assertFalse(repo.guardTick())

        assertTrue(access.writes.isEmpty())
    }

    @Test
    fun `越界的持久化角度读回时归零`() = runTest {
        val (repo, _, dataStore) = fixture()
        dataStore.putRaw(intPreferencesKey("pinned_rotation"), 9)

        assertEquals(0, repo.snapshot().pinnedRotation)
    }
}
