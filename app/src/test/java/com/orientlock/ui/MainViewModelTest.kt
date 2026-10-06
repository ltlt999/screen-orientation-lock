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

        // isLoaded 是关键：没有它，这条断言只靠 MainUiState() 的占位默认值也能绿
        assertTrue(vm.uiState.value.isLoaded)
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

        assertTrue(vm.uiState.value.isLoaded)
        val settings = vm.uiState.value.settings
        assertTrue(settings.autoStartOnBoot)
        assertTrue(settings.persistentNotification)
        assertTrue(settings.guardEnabled)
    }

    @Test
    fun `默认未探测天然朝向`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        assertTrue(vm.uiState.value.isLoaded)
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
    fun `两条通路都未授予时选择方向不会写入系统也不会启动服务`() = runTest {
        permissions.writeSettings = false
        permissions.drawOverlays = false
        val vm = createViewModel()
        subscribeTo(vm)

        vm.selectMode(OrientationMode.PORTRAIT)

        assertTrue(repository.modeHistory.isEmpty())
        assertEquals(0, services.startCalls)
        // canWriteSettings=false 同时也是 MainUiState() 的默认值，靠 isLoaded 排除假绿
        assertTrue(vm.uiState.value.isLoaded)
        assertFalse(vm.uiState.value.canLock)
    }

    @Test
    fun `两条通路都未授予时给出提示而不是静默无反应`() = runTest {
        permissions.writeSettings = false
        permissions.drawOverlays = false
        val vm = createViewModel()
        subscribeTo(vm)

        vm.selectMode(OrientationMode.PORTRAIT)

        assertTrue("必须给用户反馈，不能静默 return", vm.hint.value != null)
        vm.consumeHint()
        assertEquals(null, vm.hint.value)
    }

    @Test
    fun `只授予悬浮窗权限时也能锁定`() = runTest {
        // 悬浮窗是能力更强的通路：它能压过应用自己声明的方向，
        // 所以只有它时锁定依然有效，不该被权限门挡住。
        permissions.writeSettings = false
        permissions.drawOverlays = true
        val vm = createViewModel()
        subscribeTo(vm)

        vm.selectMode(OrientationMode.PORTRAIT)

        assertEquals(listOf(OrientationMode.PORTRAIT), repository.modeHistory)
        assertEquals(1, services.startCalls)
        assertTrue(vm.uiState.value.canLock)
        assertEquals(null, vm.hint.value)
    }

    @Test
    fun `只授予修改系统设置权限时也能锁定`() = runTest {
        permissions.writeSettings = true
        permissions.drawOverlays = false
        val vm = createViewModel()
        subscribeTo(vm)

        vm.selectMode(OrientationMode.LANDSCAPE)

        assertEquals(listOf(OrientationMode.LANDSCAPE), repository.modeHistory)
        assertTrue(vm.uiState.value.canLock)
    }

    @Test
    fun `写系统设置抛异常时不崩且仍然启动服务`() = runTest {
        // 真实设备上没授予 WRITE_SETTINGS 时 Settings.System.putInt 抛 SecurityException。
        // 主通路是悬浮窗，它不依赖那个权限——所以异常绝不能把应用打崩，
        // 服务也必须照常启动，否则悬浮窗无从接管。实测时这个异常真的让应用闪退了。
        val vm = createViewModel()
        subscribeTo(vm)
        repository.throwOnSetMode = true

        vm.selectMode(OrientationMode.PORTRAIT)

        assertTrue("必须给出提示，不能静默", vm.hint.value != null)
        assertEquals("服务仍要启动，悬浮窗才能接管", 1, services.startCalls)
    }

    @Test
    fun `界面状态跟随 Repository 的设置变化`() = runTest {
        val vm = createViewModel()
        subscribeTo(vm)

        vm.selectMode(OrientationMode.LANDSCAPE)

        val settings = repository.awaitSettings { it.mode == OrientationMode.LANDSCAPE }
        assertEquals(OrientationMode.LANDSCAPE, settings.mode)
        // 原名只断言了 fake，这里补上对 uiState 的断言，让用例名副实归
        assertEquals(OrientationMode.LANDSCAPE, vm.uiState.value.settings.mode)
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
