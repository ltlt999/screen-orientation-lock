package com.orientlock.ui

import com.orientlock.domain.AppSettings
import com.orientlock.domain.OrientationMode
import com.orientlock.fakes.FakeOrientationRepository
import com.orientlock.fakes.FakePermissionChecker
import com.orientlock.fakes.FakeServiceGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: FakeOrientationRepository
    private lateinit var permissions: FakePermissionChecker
    private lateinit var services: FakeServiceGateway

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeOrientationRepository()
        permissions = FakePermissionChecker()
        services = FakeServiceGateway()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = MainViewModel(repository, permissions, services)

    /**
     * uiState 是 WhileSubscribed 的共享流：没有订阅方时 value 永远停在 initialValue。
     * 每条读 uiState 的测试都必须先订阅，否则读到的是占位值，断言会假绿或假红。
     */
    private fun TestScope.subscribeTo(vm: MainViewModel) {
        backgroundScope.launch(dispatcher) { vm.uiState.collect { } }
        runCurrent()
    }

    @Test
    fun `初始状态为未锁定`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        assertEquals(OrientationMode.AUTO, vm.uiState.value.settings.mode)
    }

    @Test
    fun `首次真实值到达后标记为已加载`() = runTest {
        val vm = createViewModel()

        assertFalse("订阅前还是占位默认值", vm.uiState.value.isLoaded)

        subscribeTo(vm)

        assertTrue(vm.uiState.value.isLoaded)
    }

    @Test
    fun `默认开启开机自启、常驻通知与守护`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        val settings = vm.uiState.value.settings
        assertTrue(settings.autoStartOnBoot)
        assertTrue(settings.persistentNotification)
        assertTrue(settings.guardEnabled)
    }

    @Test
    fun `默认未探测天然朝向`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        assertEquals(null, vm.uiState.value.settings.naturalOrientation)
    }

    @Test
    fun `权限已授予时选择竖屏会写入并启动服务`() = runTest {
        permissions.writeSettings = true
        val vm = createViewModel()
        subscribeTo(vm)

        vm.selectMode(OrientationMode.PORTRAIT)

        assertEquals(listOf(OrientationMode.PORTRAIT), repository.modeHistory)
        assertEquals(1, services.startCalls)
    }

    @Test
    fun `权限未授予时选择方向不会写入系统也不会启动服务`() = runTest {
        permissions.writeSettings = false
        val vm = createViewModel()
        subscribeTo(vm)

        vm.selectMode(OrientationMode.PORTRAIT)

        assertTrue(repository.modeHistory.isEmpty())
        assertEquals(0, services.startCalls)
        assertFalse(vm.uiState.value.canWriteSettings)
    }

    @Test
    fun `界面状态跟随 Repository 的设置变化`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        vm.selectMode(OrientationMode.LANDSCAPE)

        val settings = repository.awaitSettings { it.mode == OrientationMode.LANDSCAPE }
        assertEquals(OrientationMode.LANDSCAPE, settings.mode)
    }

    @Test
    fun `选择自动模式同样会启动服务`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        vm.selectMode(OrientationMode.AUTO)

        assertEquals(listOf(OrientationMode.AUTO), repository.modeHistory)
        assertEquals(1, services.startCalls)
    }

    @Test
    fun `关闭常驻通知会停止服务`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        vm.setPersistentNotification(false)

        assertFalse(repository.snapshot().persistentNotification)
        assertEquals(1, services.stopCalls)
    }

    @Test
    fun `打开常驻通知不会停止服务`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        vm.setPersistentNotification(true)

        assertTrue(repository.snapshot().persistentNotification)
        assertEquals(0, services.stopCalls)
    }

    @Test
    fun `切换守护开关会重启守护`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        vm.setGuardEnabled(false)

        assertEquals(1, services.restartGuardCalls)
        assertFalse(repository.snapshot().guardEnabled)
    }

    @Test
    fun `切换开机自启开关会持久化`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        vm.setAutoStartOnBoot(false)

        assertFalse(repository.snapshot().autoStartOnBoot)
    }

    @Test
    fun `刷新权限后授权状态被更新`() = runTest {
        permissions.writeSettings = false
        val vm = createViewModel()
        subscribeTo(vm)
        assertFalse(vm.uiState.value.canWriteSettings)

        permissions.writeSettings = true
        vm.refreshPermissions()
        runCurrent()

        assertTrue(vm.uiState.value.canWriteSettings)
    }

    @Test
    fun `通知权限未授予时界面状态为 false`() = runTest {
        permissions.postNotifications = false
        val vm = createViewModel()
        subscribeTo(vm)

        assertFalse(vm.uiState.value.canPostNotifications)
    }

    @Test
    fun `连续选择多个方向按顺序记录`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        vm.selectMode(OrientationMode.PORTRAIT)
        vm.selectMode(OrientationMode.LANDSCAPE)
        vm.selectMode(OrientationMode.AUTO)

        assertEquals(
            listOf(
                OrientationMode.PORTRAIT,
                OrientationMode.LANDSCAPE,
                OrientationMode.AUTO,
            ),
            repository.modeHistory,
        )
    }

    @Test
    fun `初始 AppSettings 的默认值是预期的一组`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        val expected = AppSettings(
            mode = OrientationMode.AUTO,
            pinnedRotation = 0,
            autoStartOnBoot = true,
            persistentNotification = true,
            guardEnabled = true,
            naturalOrientation = null,
        )
        assertEquals(expected, repository.snapshot())
    }
}
