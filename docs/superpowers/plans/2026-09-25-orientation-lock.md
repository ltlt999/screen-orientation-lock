# 屏幕方向锁 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一款免 root 的安卓屏幕方向全局锁定应用，支持 6 种方向模式、常驻通知栏快捷切换、开机自启与守护，深色全中文界面。

**Architecture:** 单模块 `:app`。`domain` 层为纯 Kotlin（零安卓依赖，可 JVM 单测），定义方向模式与 `USER_ROTATION` 映射、天然朝向判定、守护判定、`AppSettings` 与 `OrientationRepository` 接口；`data` 层实现 Repository，封装 `Settings.System` 写入与 DataStore 持久化，是唯一数据源；`system` 层由一个前台服务承担常驻通知与守护心跳，`BootReceiver` 负责开机拉起；`ui` 层为 Jetpack Compose Material3 深色界面，Canvas 手绘可旋转手机示意图。ViewModel 只依赖接口，测试用假实现，不需要 Robolectric。

**Tech Stack:** Kotlin 2.1.0、Jetpack Compose BOM 2025.01.00、AGP 8.9.3、Gradle 8.12、DataStore Preferences、WorkManager、JUnit 4 + kotlinx-coroutines-test。

---

## 0. 环境约定（所有命令的前提）

- 工作目录：`E:\APP\2026-9-25`（bash 中写 `/e/APP/2026-9-25`）
- Gradle Wrapper 首次在 Task 1 生成；本机已缓存 `gradle-8.12-bin` 发行包，不会重复下载
- **系统默认 JDK 是 Java 26，与 AGP 8.9.3 不兼容**。Task 1 Step 3 会在 `gradle.properties` 指定 Android Studio 自带 JBR 21：
  ```
  org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
  ```
  若 Gradle 报 `Unsupported class file major version 68`，就是这行没生效
- 本机无连接安卓设备，真机行为无法在此环境验证；Task 17 交付逐条自测清单

---

## 文件结构

```
E:\APP\2026-9-25\
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── gradle/wrapper/gradle-wrapper.properties
├── local.properties                      # 存放 sdk.dir，不入库
├── .gitignore
├── README.md
├── docs/
│   ├── superpowers/specs/2026-09-25-orientation-lock-design.md   # 已存在
│   ├── superpowers/plans/2026-09-25-orientation-lock.md          # 本文档
│   └── 真机自测清单.md
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/
        │   │   ├── drawable/*.xml                  # 12 个自绘矢量图标
        │   │   └── values/{strings,colors,themes}.xml
        │   └── java/com/orientlock/
        │       ├── OrientLockApp.kt
        │       ├── MainActivity.kt
        │       ├── domain/
        │       │   ├── NaturalOrientation.kt
        │       │   ├── OrientationMode.kt
        │       │   ├── OrientationGuard.kt
        │       │   ├── AppSettings.kt
        │       │   └── OrientationRepository.kt
        │       ├── data/
        │       │   ├── Preferences.kt
        │       │   ├── SystemOrientationWriter.kt
        │       │   └── SettingsOrientationRepository.kt
        │       ├── system/
        │       │   ├── PermissionChecker.kt
        │       │   ├── PermissionIntents.kt
        │       │   ├── ServiceGateway.kt
        │       │   ├── NotificationHelper.kt
        │       │   ├── OrientationService.kt
        │       │   └── BootReceiver.kt
        │       └── ui/
        │           ├── theme/{Color,Type,Theme}.kt
        │           ├── MainViewModel.kt
        │           ├── AppRoot.kt
        │           ├── MainScreen.kt
        │           └── components/
        │               ├── GradientBackground.kt
        │               ├── StatusPill.kt
        │               ├── PhonePreview.kt
        │               ├── ModeCard.kt
        │               ├── SettingRow.kt
        │               ├── PermissionBanner.kt
        │               └── RomAdaptCard.kt
        └── test/java/com/orientlock/
            ├── fakes/FakeOrientationRepository.kt
            ├── fakes/FakePermissionChecker.kt
            ├── fakes/FakeServiceGateway.kt
            └── ui/MainViewModelTest.kt
```

**职责边界：**

| 文件 | 单一职责 | 依赖 |
|---|---|---|
| `domain/NaturalOrientation.kt` | 天然朝向枚举 + 纯函数判定 | 无 |
| `domain/OrientationMode.kt` | 6 种模式：中文标签、持久化名、到系统值的映射 | `NaturalOrientation` |
| `domain/OrientationGuard.kt` | 守护偏离判定 | 无 |
| `domain/AppSettings.kt` | 可变设置的数据模型 | 无 |
| `domain/OrientationRepository.kt` | 数据访问接口（依赖倒置，便于测试） | 无 |
| `data/SystemOrientationWriter.kt` | 唯一接触 `Settings.System` 之处；权限检查、朝向探测、写入 | 安卓 api |
| `data/SettingsOrientationRepository.kt` | 实现 Repository：DataStore + 守护心跳 | writer |
| `system/NotificationHelper.kt` | 通知渠道与通知构建，只收参数 | 无 |
| `system/OrientationService.kt` | 前台服务：通知 + 定时心跳 | repository |
| `system/BootReceiver.kt` | 开机/更新后拉起服务 | 无 |
| `system/PermissionChecker.kt` | 权限查询接口 + 安卓实现 | 无 |
| `system/ServiceGateway.kt` | 启停服务的接口 + 安卓实现 | 无 |
| `ui/MainViewModel.kt` | UI 状态与用户意图 | 上面三个接口 |

**关键接口契约（后续任务必须逐字一致）：**

```kotlin
enum class OrientationMode { PORTRAIT, PORTRAIT_REVERSE, LANDSCAPE, LANDSCAPE_REVERSE, CURRENT, AUTO }
enum class NaturalOrientation { PORTRAIT, LANDSCAPE }
data class RotationState(val userRotation: Int, val autoRotate: Boolean)

fun naturalOrientationFrom(widthPx: Int, heightPx: Int): NaturalOrientation
fun OrientationMode.userRotationFor(natural: NaturalOrientation): Int?     // null = AUTO/CURRENT
fun shouldReapply(current: RotationState, target: OrientationMode, natural: NaturalOrientation): Boolean

data class AppSettings(
    val mode: OrientationMode = OrientationMode.AUTO,
    val pinnedRotation: Int = 0,
    val autoStartOnBoot: Boolean = true,
    val persistentNotification: Boolean = true,
    val guardEnabled: Boolean = true,
    val naturalOrientation: NaturalOrientation? = null,   // null = 尚未探测
)

interface OrientationRepository {
    val settings: Flow<AppSettings>
    suspend fun snapshot(): AppSettings
    suspend fun setMode(mode: OrientationMode)
    suspend fun guardTick(): Boolean      // true = 本次发生了重写
    suspend fun setAutoStartOnBoot(enabled: Boolean)
    suspend fun setPersistentNotification(enabled: Boolean)
    suspend fun setGuardEnabled(enabled: Boolean)
}

interface PermissionChecker {
    fun canWriteSettings(): Boolean
    fun canPostNotifications(): Boolean
}

interface ServiceGateway {
    fun start()
    fun stop()
    fun restartGuard()
}
```

---

## Task 1: 工程骨架

**Files:**
- Create: `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties`、`.gitignore`、`local.properties`、`app/build.gradle.kts`、`app/proguard-rules.pro`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/values/strings.xml`

- [ ] **Step 1: 写版本目录**

创建 `gradle/libs.versions.toml`：

```toml
[versions]
agp = "8.9.3"
kotlin = "2.1.0"
coreKtx = "1.13.1"
appcompat = "1.7.0"
lifecycleRuntimeKtx = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2025.01.00"
datastore = "1.1.1"
work = "2.10.0"
junit = "4.13.2"
kotlinxCoroutinesTest = "1.9.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutinesTest" }

[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp" }
jetbrainsKotlinAndroid = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

> 刻意不引 `material-icons-extended`：它含上千个图标类，会明显拖慢构建、增大 APK，且做不出定制感。本应用图标全部自绘矢量图（Task 5）。

- [ ] **Step 2: 写 settings 与根构建脚本**

创建 `settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ScreenOrientationLock"
include(":app")
```

创建 `build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
    alias(libs.plugins.composeCompiler) apply false
}
```

- [ ] **Step 3: 写 gradle.properties（含 JDK 指定）**

创建 `gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
```

- [ ] **Step 4: 生成 Gradle Wrapper**

Run:
```bash
cd /e/APP/2026-9-25 && gradle wrapper --gradle-version 8.12 --distribution-type bin
```

Expected: `> Task :wrapper` 成功，无 JDK 版本报错。若报 `Unsupported class file major version 68`，检查 Step 3。

- [ ] **Step 5: 确认 wrapper 版本**

Run:
```bash
cat /e/APP/2026-9-25/gradle/wrapper/gradle-wrapper.properties
```

Expected: 含 `distributionUrl=https\://services.gradle.org/distributions/gradle-8.12-bin.zip`

- [ ] **Step 6: local.properties 与 .gitignore**

创建 `local.properties`：

```properties
sdk.dir=C\:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk
```

创建 `.gitignore`：

```gitignore
*.iml
.gradle/
local.properties
.idea/
.DS_Store
build/
captures/
.externalNativeBuild
.cxx
*.apk
*.keystore
*.jks
```

- [ ] **Step 7: 写 app 模块构建脚本**

创建 `app/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.orientlock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.orientlock"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
```

创建 `app/proguard-rules.pro`：

```proguard
# 保留 manifest 中引用的广播接收器
-keep class com.orientlock.system.BootReceiver { *; }
```

- [ ] **Step 8: 写最小 manifest 与 strings**

创建 `app/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:icon="@android:drawable/ic_menu_compass"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.OrientLock" />
</manifest>
```

创建 `app/src/main/res/values/strings.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">屏幕方向锁</string>
</resources>
```

创建 `app/src/main/res/values/colors.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="bg_top">#0A0C12</color>
    <color name="bg_bottom">#131828</color>
</resources>
```

创建 `app/src/main/res/values/themes.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.OrientLock" parent="Theme.AppCompat.DayNight.NoActionBar">
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowBackground">@color/bg_top</item>
    </style>
</resources>
```

- [ ] **Step 9: 验证构建**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`。首次会下载 AGP 与 Compose 依赖，耗时数分钟属正常。

- [ ] **Step 10: Commit**

```bash
git init && git add -A && git commit -m "chore: 初始化安卓工程骨架"
```

---

## Task 2: 天然朝向判定（TDD）

**Files:**
- Create: `app/src/main/java/com/orientlock/domain/NaturalOrientation.kt`
- Test: `app/src/test/java/com/orientlock/domain/NaturalOrientationTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/orientlock/domain/NaturalOrientationTest.kt`：

```kotlin
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
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:testDebugUnitTest --tests "com.orientlock.domain.NaturalOrientationTest" --console=plain
```

Expected: 编译失败，报 `Unresolved reference: NaturalOrientation` / `naturalOrientationFrom`

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/orientlock/domain/NaturalOrientation.kt`：

```kotlin
package com.orientlock.domain

/**
 * 设备的天然朝向。
 *
 * 绝大多数手机为 [PORTRAIT]，平板与展开的折叠屏为 [LANDSCAPE]。
 * 天然朝向决定 [OrientationMode] 到 Settings.System.USER_ROTATION 的映射，
 * 详见 OrientationMode.userRotationFor。
 */
enum class NaturalOrientation {
    PORTRAIT,
    LANDSCAPE,
}

/**
 * 依据屏幕实际宽高判定天然朝向。
 *
 * 只在设备处于天然朝向时（即 USER_ROTATION 为 0 时）调用才有意义。
 * 两者相等时按竖屏处理：方形屏幕的设备上竖屏是更贴近直觉的默认。
 *
 * @param widthPx 天然朝向下屏幕的实际像素宽
 * @param heightPx 天然朝向下屏幕的实际像素高
 */
fun naturalOrientationFrom(widthPx: Int, heightPx: Int): NaturalOrientation =
    if (heightPx >= widthPx) NaturalOrientation.PORTRAIT else NaturalOrientation.LANDSCAPE
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:testDebugUnitTest --tests "com.orientlock.domain.NaturalOrientationTest" --console=plain
```

Expected: `BUILD SUCCESSFUL`，3 个测试全部通过

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/orientlock/domain/NaturalOrientation.kt app/src/test/java/com/orientlock/domain/NaturalOrientationTest.kt
git commit -m "feat: 天然朝向判定"
```

---

## Task 3: 方向模式与系统值映射（TDD）

**Files:**
- Create: `app/src/main/java/com/orientlock/domain/OrientationMode.kt`
- Test: `app/src/test/java/com/orientlock/domain/OrientationModeTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/orientlock/domain/OrientationModeTest.kt`：

```kotlin
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
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:testDebugUnitTest --tests "com.orientlock.domain.OrientationModeTest" --console=plain
```

Expected: 编译失败，报 `Unresolved reference: OrientationMode`

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/orientlock/domain/OrientationMode.kt`：

```kotlin
package com.orientlock.domain

/**
 * 屏幕方向锁定模式。
 *
 * @property storageName 持久化到 DataStore 的名称。改动会导致旧设置失效，勿随意改。
 * @property label 界面上显示的中文名称。
 */
enum class OrientationMode(val storageName: String, val label: String) {
    /** 自然竖屏 */
    PORTRAIT("portrait", "竖屏"),

    /** 竖屏但上下颠倒 */
    PORTRAIT_REVERSE("portrait_reverse", "反向竖屏"),

    /** 自然横屏（设备左转 90°） */
    LANDSCAPE("landscape", "横屏"),

    /** 横屏但左右颠倒 */
    LANDSCAPE_REVERSE("landscape_reverse", "反向横屏"),

    /** 锁定为选中瞬间设备所处的方向，角度在选中时抓取 */
    CURRENT("current", "当前方向"),

    /** 不锁定，跟随传感器 */
    AUTO("auto", "自动"),

    ;

    /**
     * 该模式对应的 Settings.System.USER_ROTATION 取值。
     *
     * @param natural 设备的天然朝向
     * @return 0..3 的系统角度值；[AUTO] 与 [CURRENT] 返回 null。
     *   [AUTO] 不写角度，只恢复自动旋转开关；
     *   [CURRENT] 的角度在选中瞬间从 Display.getRotation() 抓取，不在此处推导。
     */
    fun userRotationFor(natural: NaturalOrientation): Int? = when (this) {
        AUTO -> null
        CURRENT -> null
        PORTRAIT -> if (natural == NaturalOrientation.PORTRAIT) 0 else 1
        LANDSCAPE -> if (natural == NaturalOrientation.PORTRAIT) 1 else 0
        PORTRAIT_REVERSE -> if (natural == NaturalOrientation.PORTRAIT) 2 else 3
        LANDSCAPE_REVERSE -> if (natural == NaturalOrientation.PORTRAIT) 3 else 2
    }

    /**
     * 反向模式：竖屏 ↔ 反向竖屏，横屏 ↔ 反向横屏。
     * [CURRENT] 与 [AUTO] 没有固定反向，返回自身。
     */
    fun reversed(): OrientationMode = when (this) {
        PORTRAIT -> PORTRAIT_REVERSE
        PORTRAIT_REVERSE -> PORTRAIT
        LANDSCAPE -> LANDSCAPE_REVERSE
        LANDSCAPE_REVERSE -> LANDSCAPE
        CURRENT, AUTO -> this
    }

    companion object {
        /** 按持久化名称查找；未知或空名称回落到 [AUTO]，保证升级不崩 */
        fun fromStorageName(name: String?): OrientationMode =
            entries.firstOrNull { it.storageName == name } ?: AUTO
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:testDebugUnitTest --tests "com.orientlock.domain.OrientationModeTest" --console=plain
```

Expected: `BUILD SUCCESSFUL`，14 个测试全部通过

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/orientlock/domain/OrientationMode.kt app/src/test/java/com/orientlock/domain/OrientationModeTest.kt
git commit -m "feat: 方向模式与 USER_ROTATION 映射"
```

---

## Task 4: 守护判定（TDD）

**Files:**
- Create: `app/src/main/java/com/orientlock/domain/OrientationGuard.kt`
- Test: `app/src/test/java/com/orientlock/domain/OrientationGuardTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/orientlock/domain/OrientationGuardTest.kt`：

```kotlin
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
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:testDebugUnitTest --tests "com.orientlock.domain.OrientationGuardTest" --console=plain
```

Expected: 编译失败，报 `Unresolved reference: RotationState` / `shouldReapply`

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/orientlock/domain/OrientationGuard.kt`：

```kotlin
package com.orientlock.domain

/** 从 Settings.System 读到的方向状态快照 */
data class RotationState(val userRotation: Int, val autoRotate: Boolean)

/**
 * 判断当前系统方向状态是否偏离了用户选择的目标，需要重新写入。
 *
 * 由 OrientationService 的守护逻辑调用。规则：
 * - 目标为 [OrientationMode.AUTO] 时永不重新应用——用户已主动解除锁定，
 *   此时再写回去会和用户的意图对抗。
 * - 目标为 [OrientationMode.CURRENT] 时，只要自动旋转是关的就算达成，
 *   因为该模式的角度在选中瞬间就已固定，不参与后续比对。
 * - 其余模式下，自动旋转被重新打开，或系统角度不等于目标角度，均判定需重新应用。
 */
fun shouldReapply(
    current: RotationState,
    target: OrientationMode,
    natural: NaturalOrientation,
): Boolean {
    if (target == OrientationMode.AUTO) return false
    if (target == OrientationMode.CURRENT) return current.autoRotate
    if (current.autoRotate) return true
    return current.userRotation != target.userRotationFor(natural)
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:testDebugUnitTest --tests "com.orientlock.domain.OrientationGuardTest" --console=plain
```

Expected: `BUILD SUCCESSFUL`，7 个测试全部通过

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/orientlock/domain/OrientationGuard.kt app/src/test/java/com/orientlock/domain/OrientationGuardTest.kt
git commit -m "feat: 守护偏离判定"
```

---

## Task 5: 自绘矢量图标

**Files:**
- Create: `app/src/main/res/drawable/` 下 12 个矢量图

**规格约定（全部统一）：**
- 视口 24×24dp，`android:width="24dp" android:height="24dp"`
- 颜色一律 `#FFFFFFFF`，由调用方用 `tint` 着色
- **改用描边式绘制**（`android:strokeColor` + `android:strokeWidth`）。原设计用非零环绕规则挖孔画轮廓，那套写法对子路径方向极敏感，手写极易变成实心块；描边式不依赖子路径方向，形态必然正确
- 方向图标的设计语义：**外框形状区分竖屏（高瘦）/ 横屏（宽扁），内部箭头方向区分正向 / 反向**——这样四种方向一眼可辨

- [ ] **Step 1: 模式图标 —— 竖屏**（高瘦外框 + 向下箭头）

创建 `app/src/main/res/drawable/ic_mode_portrait.xml`：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M8,3.5h8a2,2 0 0 1 2,2v13a2,2 0 0 1 -2,2H8a2,2 0 0 1 -2,-2v-13a2,2 0 0 1 2,-2z"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.7" />
    <path
        android:pathData="M12,8v7M9,12.2l3,3l3,-3"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

- [ ] **Step 2: 模式图标 —— 反向竖屏**（高瘦外框 + 向上箭头）

创建 `app/src/main/res/drawable/ic_mode_portrait_reverse.xml`：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M8,3.5h8a2,2 0 0 1 2,2v13a2,2 0 0 1 -2,2H8a2,2 0 0 1 -2,-2v-13a2,2 0 0 1 2,-2z"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.7" />
    <path
        android:pathData="M12,16v-7M9,11.8l3,-3l3,3"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

- [ ] **Step 3: 模式图标 —— 横屏**（宽扁外框 + 向右箭头）

创建 `app/src/main/res/drawable/ic_mode_landscape.xml`：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M5,8h14a2,2 0 0 1 2,2v4a2,2 0 0 1 -2,2H5a2,2 0 0 1 -2,-2v-4a2,2 0 0 1 2,-2z"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.7" />
    <path
        android:pathData="M8,12h7M12.6,10l2.4,2l-2.4,2"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

- [ ] **Step 4: 模式图标 —— 反向横屏**（宽扁外框 + 向左箭头）

创建 `app/src/main/res/drawable/ic_mode_landscape_reverse.xml`：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M5,8h14a2,2 0 0 1 2,2v4a2,2 0 0 1 -2,2H5a2,2 0 0 1 -2,-2v-4a2,2 0 0 1 2,-2z"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.7" />
    <path
        android:pathData="M16,12h-7M11.4,10l-2.4,2l2.4,2"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

- [ ] **Step 5: 模式图标 —— 当前方向**（同心圆 + 指针）

创建 `app/src/main/res/drawable/ic_mode_current.xml`：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M3.2,12a8.8,8.8 0 1 1 17.6,0a8.8,8.8 0 1 1 -17.6,0z"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.7" />
    <path
        android:pathData="M7.6,12a4.4,4.4 0 1 1 8.8,0a4.4,4.4 0 1 1 -8.8,0z"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.5" />
    <path
        android:pathData="M12,9.8v4.4"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.7"
        android:strokeLineCap="round" />
</vector>
```

- [ ] **Step 6: 模式图标 —— 自动**（双向循环箭头，跟随传感器）

创建 `app/src/main/res/drawable/ic_mode_auto.xml`：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <!-- 上半圈：从左侧顶点顺时针过顶端到右侧顶点 -->
    <path
        android:pathData="M6,12A6,6 0 0 1 18,12"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineCap="round" />
    <!-- 下半圈：从右侧顶点顺时针过底端回左侧顶点 -->
    <path
        android:pathData="M18,12A6,6 0 0 1 6,12"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineCap="round" />
    <!-- 右端箭头，指向下（顺时针行进方向） -->
    <path
        android:pathData="M15.8,9.5L18,12l2.2,-2.5"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <!-- 左端箭头，指向上 -->
    <path
        android:pathData="M8.2,14.5L6,12l-2.2,2.5"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

- [ ] **Step 7: 设置行图标三个**

创建 `app/src/main/res/drawable/ic_setting_boot.xml`（电源符号：顶部断口的圆 + 竖线）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M17.657,6.343A8,8 0 1 1 6.343,6.343"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.9"
        android:strokeLineCap="round" />
    <path
        android:pathData="M12,3.2V12"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.9"
        android:strokeLineCap="round" />
</vector>
```

> **端点顺序不能对调回 `M6.343,6.343A8,8 0 1 1 17.657,6.343`。** SVG 弧的端点→圆心换算中，圆心修正系数的符号由 `large-arc == sweep` 决定：两者相等时取负。从左上端点出发会让圆心落在 (12, 0.686)，弧顶到 y = −7.314，约 63% 的圆弧画到视口外被裁掉，只剩两块碎角。从右上端点出发才得到圆心 (12,12)，弧线自右侧经下方走到左上，正好在顶部留出 90° 断口。aapt2 只校验 XML 合法、从不计算 pathData，所以这种错误构建时完全看不出来。

创建 `app/src/main/res/drawable/ic_setting_notification.xml`（铃铛 + 铃舌）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,2.8a5.6,5.6 0 0 0 -5.6,5.6v4.4l-2,3.2h15.2l-2,-3.2V8.4A5.6,5.6 0 0 0 12,2.8z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M9.8,18.4a2.4,2.4 0 0 0 4.4,0z" />
</vector>
```

创建 `app/src/main/res/drawable/ic_setting_guard.xml`（盾牌 + 对勾）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M12,2.6l7.4,2.8v5.6c0,4.6 -3.1,7.9 -7.4,9.4 -4.3,-1.5 -7.4,-4.8 -7.4,-9.4V5.4z"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M8.8,11.8l2.4,2.4 4,-4.4"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

- [ ] **Step 8: 杂项图标三个**

创建 `app/src/main/res/drawable/ic_warning.xml`（三角 + 感叹号，感叹点用零长圆头路径绘制）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M12,3.2L21.4,19.6H2.6z"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M12,9.2v4.6"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="2"
        android:strokeLineCap="round" />
    <path
        android:pathData="M12,16.6v0.1"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="2"
        android:strokeLineCap="round" />
</vector>
```

创建 `app/src/main/res/drawable/ic_rom.xml`（手机 + 列表行）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M7,2.6h10a2,2 0 0 1 2,2v14.8a2,2 0 0 1 -2,2H7a2,2 0 0 1 -2,-2V4.6a2,2 0 0 1 2,-2z"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.7" />
    <path
        android:pathData="M9,7.6h6M9,11h6M9,14.4h4"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.7"
        android:strokeLineCap="round" />
</vector>
```

创建 `app/src/main/res/drawable/ic_notification_orientation.xml`（状态栏小图标，只取透明度，需为纯白剪影）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="M7,2h10a2,2 0 0 1 2,2v16a2,2 0 0 1 -2,2H7a2,2 0 0 1 -2,-2V4a2,2 0 0 1 2,-2zM11,6h2v12h-2z" />
</vector>
```

> 必须用 `android:fillType="evenOdd"` 并把两个子路径合成一条。默认的 `nonZero` 环绕下，外框与内部竖条绕向相同、环绕数都是 1，竖条会被填成与外框同色从而完全看不见——那是一行死代码。`evenOdd` 会让竖条变成镂空，形状才有辨识度。`android:fillType` 需 API 24，本项目 minSdk 26，可用。

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/drawable/ && git commit -m "feat: 自绘矢量图标（不引入 material-icons-extended）"
```

---

## Task 6: 系统设置写入器

**Files:**
- Create: `app/src/main/java/com/orientlock/data/SystemOrientationWriter.kt`

- [ ] **Step 1: 写实现**

创建 `app/src/main/java/com/orientlock/data/SystemOrientationWriter.kt`：

```kotlin
package com.orientlock.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Surface
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.RotationState
import com.orientlock.domain.naturalOrientationFrom

/**
 * 唯一接触 Settings.System 的类。
 *
 * 锁定一个方向必须同时做两件事：先关闭自动旋转，再写入目标角度。
 * 只写角度而不关自动旋转无效——自动旋转开启时系统忽略 USER_ROTATION。
 *
 * 天然朝向探测有副作用（会临时改写系统设置），因此 [naturalOrientation] 会把结果
 * 缓存在内存里，整个进程只探测一次。
 */
class SystemOrientationWriter(private val context: Context) {

    private var cachedNatural: NaturalOrientation? = null

    private val resolver get() = context.contentResolver

    /** 是否已获得「修改系统设置」特殊权限 */
    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    /**
     * 探测并缓存设备的天然朝向，同一进程内只探测一次。
     *
     * 做法：把 USER_ROTATION 短暂置 0（同时关掉自动旋转），此时设备必然处于天然
     * 朝向，读一次屏幕宽高即可判定。平板天然横屏，与手机的映射不同，
     * 不区分会导致锁出来的方向在平板和折叠屏上是反的。
     */
    fun naturalOrientation(): NaturalOrientation {
        cachedNatural?.let { return it }
        applyAutoRotate(false)
        applyUserRotation(Surface.ROTATION_0)
        val metrics = context.resources.displayMetrics
        return naturalOrientationFrom(metrics.widthPixels, metrics.heightPixels)
            .also { cachedNatural = it }
    }

    fun readState(): RotationState = RotationState(
        userRotation = Settings.System.getInt(
            resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0
        ),
        autoRotate = Settings.System.getInt(
            resolver, Settings.System.ACCELEROMETER_ROTATION, 1
        ) != 0,
    )

    /** 设备当前实际的显示旋转角度 */
    fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.rotation
        }

    /**
     * 应用一个方向模式。
     *
     * @param mode 目标模式
     * @param natural 天然朝向，用于把模式换算成系统角度值
     * @param pinnedRotationForCurrent 选中 [OrientationMode.CURRENT] 时要固定的角度
     */
    fun apply(
        mode: OrientationMode,
        natural: NaturalOrientation,
        pinnedRotationForCurrent: Int,
    ) {
        when (mode) {
            OrientationMode.AUTO -> applyAutoRotate(true)
            OrientationMode.CURRENT -> {
                applyAutoRotate(false)
                applyUserRotation(pinnedRotationForCurrent)
            }
            else -> {
                val angle = mode.userRotationFor(natural)
                    ?: error("模式 $mode 不是固定角度模式")
                applyAutoRotate(false)
                applyUserRotation(angle)
            }
        }
    }

    private fun applyAutoRotate(enabled: Boolean) {
        Settings.System.putInt(
            resolver,
            Settings.System.ACCELEROMETER_ROTATION,
            if (enabled) 1 else 0,
        )
    }

    private fun applyUserRotation(angle: Int) {
        require(angle in Surface.ROTATION_0..Surface.ROTATION_270) {
            "非法旋转角度 $angle"
        }
        Settings.System.putInt(resolver, Settings.System.USER_ROTATION, angle)
    }
}
```

- [ ] **Step 2: 编译验证**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:compileDebugKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/orientlock/data/SystemOrientationWriter.kt
git commit -m "feat: 系统方向设置写入器"
```

---

## Task 7: 设置模型、Repository 接口与实现

**Files:**
- Create: `app/src/main/java/com/orientlock/domain/AppSettings.kt`
- Create: `app/src/main/java/com/orientlock/domain/OrientationRepository.kt`
- Create: `app/src/main/java/com/orientlock/data/Preferences.kt`
- Create: `app/src/main/java/com/orientlock/data/SettingsOrientationRepository.kt`

- [ ] **Step 1: 写设置模型**

创建 `app/src/main/java/com/orientlock/domain/AppSettings.kt`：

```kotlin
package com.orientlock.domain

/**
 * 应用的全部可持久化设置。
 *
 * @param mode 当前锁定的方向模式
 * @param pinnedRotation 选中 [OrientationMode.CURRENT] 瞬间固定的系统角度值
 * @param autoStartOnBoot 是否开机自启并恢复锁定
 * @param persistentNotification 是否显示常驻通知
 * @param guardEnabled 是否启用守护
 * @param naturalOrientation 设备的天然朝向；null 表示尚未探测
 */
data class AppSettings(
    val mode: OrientationMode = OrientationMode.AUTO,
    val pinnedRotation: Int = 0,
    val autoStartOnBoot: Boolean = true,
    val persistentNotification: Boolean = true,
    val guardEnabled: Boolean = true,
    val naturalOrientation: NaturalOrientation? = null,
)
```

- [ ] **Step 2: 写 Repository 接口**

创建 `app/src/main/java/com/orientlock/domain/OrientationRepository.kt`：

```kotlin
package com.orientlock.domain

import kotlinx.coroutines.flow.Flow

/**
 * 方向设置的数据访问接口。
 *
 * ViewModel 与 OrientationService 都只依赖这个接口，便于用假实现做单元测试。
 * 实现见 com.orientlock.data.SettingsOrientationRepository。
 */
interface OrientationRepository {

    /** 设置变更流。实现方保证这是唯一数据源。 */
    val settings: Flow<AppSettings>

    /** 同步读一次当前设置 */
    suspend fun snapshot(): AppSettings

    /**
     * 选择一个方向模式：持久化并立即写入系统。
     *
     * [OrientationMode.CURRENT] 的固定角度在方法内部抓取。
     */
    suspend fun setMode(mode: OrientationMode)

    /**
     * 守护心跳：比对系统当前状态与目标，偏离则重写。
     *
     * @return true 表示本次发生了重写
     */
    suspend fun guardTick(): Boolean

    suspend fun setAutoStartOnBoot(enabled: Boolean)

    suspend fun setPersistentNotification(enabled: Boolean)

    suspend fun setGuardEnabled(enabled: Boolean)
}
```

- [ ] **Step 3: 写 DataStore 委托**

创建 `app/src/main/java/com/orientlock/data/Preferences.kt`：

```kotlin
package com.orientlock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** 全进程唯一的 DataStore 实例 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "orientation_settings"
)
```

- [ ] **Step 4: 写 Repository 实现**

创建 `app/src/main/java/com/orientlock/data/SettingsOrientationRepository.kt`：

```kotlin
package com.orientlock.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.orientlock.domain.AppSettings
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.OrientationRepository
import com.orientlock.domain.RotationState
import com.orientlock.domain.shouldReapply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * OrientationRepository 的实现。
 *
 * 职责：把 [AppSettings] 与 DataStore、[SystemOrientationWriter] 对上，
 * 并承担守护心跳里的比对与重写逻辑。
 */
class SettingsOrientationRepository(
    private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private val writer: SystemOrientationWriter,
) : OrientationRepository {

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val PINNED_ROTATION = intPreferencesKey("pinned_rotation")
        val AUTO_START = booleanPreferencesKey("auto_start_on_boot")
        val NOTIFICATION = booleanPreferencesKey("persistent_notification")
        val GUARD = booleanPreferencesKey("guard_enabled")
        val NATURAL = stringPreferencesKey("natural_orientation")
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { it.toAppSettings() }

    override suspend fun snapshot(): AppSettings =
        dataStore.data.first().toAppSettings()

    override suspend fun setMode(mode: OrientationMode) {
        // 第一次选方向时才探测天然朝向，探测结果同时落盘，之后不再探测
        val natural = resolveNaturalOrientation()
        val pinned = if (mode == OrientationMode.CURRENT) writer.displayRotation() else 0
        dataStore.edit { prefs ->
            prefs[Keys.MODE] = mode.storageName
            prefs[Keys.PINNED_ROTATION] = pinned
        }
        writer.apply(mode, natural, pinned)
    }

    override suspend fun guardTick(): Boolean {
        val snap = snapshot()
        if (!snap.guardEnabled) return false
        val natural = resolveNaturalOrientation()
        val target = snap.mode
        if (target == OrientationMode.AUTO) return false
        val current = writer.readState()
        if (!shouldReapply(current, target, natural)) return false
        writer.apply(target, natural, snap.pinnedRotation)
        return true
    }

    override suspend fun setAutoStartOnBoot(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_START] = enabled }
    }

    override suspend fun setPersistentNotification(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATION] = enabled }
    }

    override suspend fun setGuardEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.GUARD] = enabled }
    }

    /** 已探测过就直接用缓存值，否则探测并落盘 */
    private suspend fun resolveNaturalOrientation(): NaturalOrientation {
        snapshot().naturalOrientation?.let { return it }
        val detected = writer.naturalOrientation()
        dataStore.edit { it[Keys.NATURAL] = detected.name }
        return detected
    }

    private fun androidx.datastore.preferences.core.Preferences.toAppSettings(): AppSettings {
        val mode = OrientationMode.fromStorageName(this[Keys.MODE])
        return AppSettings(
            mode = mode,
            pinnedRotation = this[Keys.PINNED_ROTATION] ?: 0,
            autoStartOnBoot = this[Keys.AUTO_START] ?: true,
            persistentNotification = this[Keys.NOTIFICATION] ?: true,
            guardEnabled = this[Keys.GUARD] ?: true,
            naturalOrientation = this[Keys.NATURAL]
                ?.let { name -> NaturalOrientation.entries.firstOrNull { it.name == name } },
        )
    }
}
```

- [ ] **Step 5: 编译验证**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:compileDebugKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/orientlock/domain/AppSettings.kt app/src/main/java/com/orientlock/domain/OrientationRepository.kt app/src/main/java/com/orientlock/data/Preferences.kt app/src/main/java/com/orientlock/data/SettingsOrientationRepository.kt
git commit -m "feat: 设置模型、Repository 接口与 DataStore 实现"
```

---

## Task 8: 通知构建

**Files:**
- Create: `app/src/main/java/com/orientlock/system/NotificationHelper.kt`

- [ ] **Step 1: 写实现**

创建 `app/src/main/java/com/orientlock/system/NotificationHelper.kt`：

```kotlin
package com.orientlock.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.orientlock.MainActivity
import com.orientlock.R
import com.orientlock.domain.OrientationMode

/**
 * 通知渠道与常驻通知的构建。
 *
 * 只负责「怎么展示」，不碰方向逻辑，文案改动集中在此。
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "orientation_lock"
        const val NOTIFICATION_ID = 1001

        const val ACTION_SET_PORTRAIT = "com.orientlock.action.SET_PORTRAIT"
        const val ACTION_SET_LANDSCAPE = "com.orientlock.action.SET_LANDSCAPE"
        const val ACTION_SET_REVERSE = "com.orientlock.action.SET_REVERSE"
        const val ACTION_UNLOCK = "com.orientlock.action.UNLOCK"
        const val EXTRA_REVERSE_TARGET = "extra_reverse_target"
    }

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "方向锁定",
            NotificationManager.IMPORTANCE_LOW, // 无声、不浮动，安静常驻
        ).apply {
            description = "显示当前锁定的屏幕方向，并提供快速切换按钮"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建常驻通知。
     *
     * 「反向」按钮的语义随当前锁定方向变化：竖屏锁定时切到反向竖屏，
     * 横屏锁定时切到反向横屏；未锁定时按反向竖屏处理。
     *
     * @param currentMode 当前模式
     * @param notificationGranted 是否已授予通知权限；未授予时正文明示
     */
    fun build(currentMode: OrientationMode, notificationGranted: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val locked = currentMode != OrientationMode.AUTO
        val title = if (locked) "${currentMode.label}锁定中" else "未锁定 · 跟随传感器"
        val text = if (notificationGranted) {
            "点按打开 · 用下方按钮快速切换"
        } else {
            "通知权限未开启，无法显示此通知"
        }

        val reverseTarget = currentMode.reversed()
            .takeIf { it != OrientationMode.AUTO && it != OrientationMode.CURRENT }
            ?: OrientationMode.PORTRAIT_REVERSE

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_orientation)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "竖屏", actionIntent(ACTION_SET_PORTRAIT))
            .addAction(0, "横屏", actionIntent(ACTION_SET_LANDSCAPE))
            .addAction(0, "反向", reverseIntent(reverseTarget))
            .addAction(0, "解除", actionIntent(ACTION_UNLOCK))
            .build()
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(context, OrientationService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun reverseIntent(mode: OrientationMode): PendingIntent {
        val intent = Intent(context, OrientationService::class.java)
            .setAction(ACTION_SET_REVERSE)
            .putExtra(EXTRA_REVERSE_TARGET, mode.storageName)
        return PendingIntent.getService(
            context,
            ACTION_SET_REVERSE.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
```

- [ ] **Step 2: 编译（会因 MainActivity 未建而失败，属预期）**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:compileDebugKotlin --console=plain
```

Expected: 报 `Unresolved reference: MainActivity`。Task 10 建 MainActivity 后此错误消失。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/orientlock/system/NotificationHelper.kt
git commit -m "feat: 常驻通知构建"
```

---

## Task 9: 前台服务与守护心跳

**Files:**
- Create: `app/src/main/java/com/orientlock/system/OrientationService.kt`

- [ ] **Step 1: 写服务**

创建 `app/src/main/java/com/orientlock/system/OrientationService.kt`：

```kotlin
package com.orientlock.system

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.orientlock.data.SettingsOrientationRepository
import com.orientlock.data.SystemOrientationWriter
import com.orientlock.data.settingsDataStore
import com.orientlock.domain.OrientationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * 前台服务：常驻通知栏入口 + 守护心跳。
 *
 * 守护用两条路径：
 * 1. ContentObserver 监听 USER_ROTATION 与 ACCELEROMETER_ROTATION，变了才反应；
 * 2. 每 [GUARD_INTERVAL_MS] 兜底心跳一次，覆盖 observer 未触发的情形。
 *
 * 心跳比对与重写的逻辑不在本类，而在 Repository.guardTick()。
 */
class OrientationService : Service() {

    companion object {
        private const val GUARD_INTERVAL_MS = 10_000L

        /** 进程内唯一实例，供设置页重启守护时使用 */
        @Volatile
        private var instanceRef: WeakReference<OrientationService>? = null

        fun activeInstance(): OrientationService? = instanceRef?.get()
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + job)

    private lateinit var repository: com.orientlock.domain.OrientationRepository
    private lateinit var notifications: NotificationHelper

    private val handler = android.os.Handler(Looper.getMainLooper())
    private var guardRunning = false

    private val observer = ContentObserver(handler) {
        // 系统方向值被改动：立即交给心跳判定，避免重复写入
        scope.launch { repository.guardTick() }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            scope.launch { repository.guardTick() }
            if (guardRunning) handler.postDelayed(this, GUARD_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        repository = SettingsOrientationRepository(
            dataStore = applicationContext.settingsDataStore,
            writer = SystemOrientationWriter(applicationContext),
        )
        notifications = NotificationHelper(this)
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先用当前已知模式把通知发出去，保证 5 秒时限内一定调用过 startForeground，
        // 不在主线程上阻塞读 DataStore
        startForegroundCompat(notifications.build(OrientationMode.AUTO, true))

        scope.launch {
            when (intent?.action) {
                NotificationHelper.ACTION_SET_PORTRAIT -> select(OrientationMode.PORTRAIT)
                NotificationHelper.ACTION_SET_LANDSCAPE -> select(OrientationMode.LANDSCAPE)
                NotificationHelper.ACTION_SET_REVERSE -> {
                    val name = intent.getStringExtra(NotificationHelper.EXTRA_REVERSE_TARGET)
                    select(OrientationMode.fromStorageName(name))
                }
                NotificationHelper.ACTION_UNLOCK -> select(OrientationMode.AUTO)
                else -> {
                    // 冷启动或开机拉起：重放上次的锁定
                    repository.guardTick()
                    refreshNotification()
                }
            }
        }

        startGuardIfNeeded()
        return START_STICKY
    }

    private suspend fun select(mode: OrientationMode) {
        repository.setMode(mode)
        refreshNotification()
    }

    private suspend fun refreshNotification() {
        val snap = repository.snapshot()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NotificationHelper.NOTIFICATION_ID,
            notifications.build(snap.mode, PermissionChecker.canPostNotifications(this)),
        )
    }

    private fun startGuardIfNeeded() {
        if (guardRunning) return
        scope.launch {
            val snap = repository.snapshot()
            if (!snap.guardEnabled) return@launch
            guardRunning = true
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.USER_ROTATION), false, observer
            )
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, observer
            )
            handler.post(heartbeat)
        }
    }

    private fun stopGuard() {
        guardRunning = false
        handler.removeCallbacks(heartbeat)
        runCatching { contentResolver.unregisterContentObserver(observer) }
    }

    /** 供设置页在「守护模式」开关变化后调用 */
    fun restartGuard() {
        stopGuard()
        startGuardIfNeeded()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopGuard()
        scope.cancel()
        instanceRef = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

- [ ] **Step 2: 编译（仍会因 MainActivity 未建而失败，属预期）**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:compileDebugKotlin --console=plain
```

Expected: 报 `Unresolved reference: MainActivity`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/orientlock/system/OrientationService.kt
git commit -m "feat: 前台服务与守护心跳"
```

---

## Task 10: 权限门、服务门、Application、开机广播

**Files:**
- Create: `app/src/main/java/com/orientlock/system/PermissionChecker.kt`
- Create: `app/src/main/java/com/orientlock/system/PermissionIntents.kt`
- Create: `app/src/main/java/com/orientlock/system/ServiceGateway.kt`
- Create: `app/src/main/java/com/orientlock/OrientLockApp.kt`
- Create: `app/src/main/java/com/orientlock/system/BootReceiver.kt`
- Create: `app/src/main/java/com/orientlock/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 写权限查询接口与实现**

创建 `app/src/main/java/com/orientlock/system/PermissionChecker.kt`：

```kotlin
package com.orientlock.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 权限查询接口，供 ViewModel 依赖（测试用假实现） */
interface PermissionChecker {
    fun canWriteSettings(): Boolean
    fun canPostNotifications(): Boolean
}

class AndroidPermissionChecker(private val context: Context) : PermissionChecker {

    override fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    override fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    companion object {
        fun canPostNotifications(context: Context): Boolean =
            AndroidPermissionChecker(context).canPostNotifications()
    }
}
```

- [ ] **Step 2: 写权限跳转 Intent**

创建 `app/src/main/java/com/orientlock/system/PermissionIntents.kt`：

```kotlin
package com.orientlock.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** 各类系统设置页的跳转 Intent，集中在一处便于 UI 复用 */
object PermissionIntents {

    /** 打开「修改系统设置」特殊权限页 */
    fun writeSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** 应用详情页，作为所有 ROM 引导的通用落点 */
    fun appDetails(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )

    /** 请求通知权限的权限名（Android 13+ 才有意义） */
    const val NOTIFICATION_PERMISSION: String = Manifest_permission_POST_NOTIFICATIONS

    @Suppress("PrivatePropertyName")
    private const val Manifest_permission_POST_NOTIFICATIONS =
        android.Manifest.permission.POST_NOTIFICATIONS

    /**
     * 各国产 ROM 的自启动 / 后台管理设置页。
     *
     * 逐个探测能否解析，返回第一个可用的；都不行时调用方回落到 [appDetails]。
     */
    fun romAutoStart(context: Context): Intent? {
        val candidates = listOf(
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            Intent().setClassName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            Intent().setClassName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            Intent().setClassName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
        )
        return candidates.firstOrNull { intent ->
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }
    }
}
```

- [ ] **Step 3: 写服务门**

创建 `app/src/main/java/com/orientlock/system/ServiceGateway.kt`：

```kotlin
package com.orientlock.system

import android.content.Context
import android.content.Intent

/** 启停方向服务的抽象，供 ViewModel 依赖（测试用假实现） */
interface ServiceGateway {
    /** 启动或确保服务在跑；权限未授予时不应调用 */
    fun start()
    /** 停止服务并撤掉常驻通知 */
    fun stop()
    /** 重载守护（设置开关变化后调用） */
    fun restartGuard()
}

class AndroidServiceGateway(private val context: Context) : ServiceGateway {

    override fun start() {
        com.orientlock.OrientLockApp.startOrientationServiceSafely(context)
    }

    override fun stop() {
        context.stopService(Intent(context, OrientationService::class.java))
    }

    override fun restartGuard() {
        OrientationService.activeInstance()?.restartGuard()
    }
}
```

- [ ] **Step 4: 写 Application 与兜底 Worker**

创建 `app/src/main/java/com/orientlock/OrientLockApp.kt`：

```kotlin
package com.orientlock

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orientlock.system.NotificationHelper
import com.orientlock.system.OrientationService

class OrientLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper(this).ensureChannel()
    }

    companion object {
        /**
         * 启动方向服务，带兜底。
         *
         * Android 15 起从 BOOT_COMPLETED 直接启动前台服务可能被系统拒绝
         * （抛 IllegalStateException）。被拒时退化为 WorkManager 立即执行，
         * 由 Worker 在系统允许的时机再拉起服务。
         */
        fun startOrientationServiceSafely(context: Context) {
            try {
                startNow(context)
            } catch (e: IllegalStateException) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<BootRetryWorker>().build(),
                )
            }
        }

        private fun startNow(context: Context) {
            val intent = Intent(context, OrientationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private const val WORK_NAME = "orientation-service-start"
    }
}

/** 开机启动被拒后的兜底 Worker */
class BootRetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        return try {
            val intent = Intent(applicationContext, OrientationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            androidx.work.ListenableWorker.Result.success()
        } catch (e: IllegalStateException) {
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}
```

- [ ] **Step 5: 写开机广播接收器**

创建 `app/src/main/java/com/orientlock/system/BootReceiver.kt`：

```kotlin
package com.orientlock.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.orientlock.OrientLockApp
import com.orientlock.data.SettingsOrientationRepository
import com.orientlock.data.SystemOrientationWriter
import com.orientlock.data.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机与应用更新后拉起服务。
 *
 * 覆盖三类广播：
 * - BOOT_COMPLETED：标准开机
 * - QUICKBOOT_POWERON：小米 / 一加等 ROM 的快速启动广播
 * - MY_PACKAGE_REPLACED：应用被更新后保持常驻
 *
 * 刻意不处理 LOCKED_BOOT_COMPLETED，也不用 directBootAware ——
 * 那会在用户解锁前运行，此时 DataStore 所在凭据加密存储不可读，会直接崩。
 *
 * 用 goAsync() 把工作移出主线程，避免 broadcast 的 10 秒 ANR 时限被打满。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val repository = SettingsOrientationRepository(
                    dataStore = appContext.settingsDataStore,
                    writer = SystemOrientationWriter(appContext),
                )
                if (repository.snapshot().autoStartOnBoot) {
                    OrientLockApp.startOrientationServiceSafely(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
```

- [ ] **Step 6: 写占位 MainActivity（Task 16 换成真实界面）**

创建 `app/src/main/java/com/orientlock/MainActivity.kt`：

```kotlin
package com.orientlock

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Text
import com.orientlock.ui.theme.OrientLockTheme

/** Task 16 会替换为真实界面 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OrientLockTheme {
                Text("屏幕方向锁")
            }
        }
    }
}
```

- [ ] **Step 7: 写完整 manifest**

创建 `app/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission
        android:name="android.permission.WRITE_SETTINGS"
        tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:name=".OrientLockApp"
        android:allowBackup="true"
        android:icon="@android:drawable/ic_menu_compass"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.OrientLock">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"
            android:theme="@style/Theme.OrientLock">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".system.OrientationService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="持续维护用户选择的屏幕方向，并通过常驻通知提供快捷切换入口" />
        </service>

        <receiver
            android:name=".system.BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

- [ ] **Step 8: 首次完整编译**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`，产出 `app/build/outputs/apk/debug/app-debug.apk`（界面目前是占位文字）

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "feat: 权限门、服务门、Application、开机广播，应用可安装"
```

---

## Task 11: 主题与配色

**Files:**
- Create: `app/src/main/java/com/orientlock/ui/theme/Color.kt`
- Create: `app/src/main/java/com/orientlock/ui/theme/Type.kt`
- Create: `app/src/main/java/com/orientlock/ui/theme/Theme.kt`

- [ ] **Step 1: 写配色**

创建 `app/src/main/java/com/orientlock/ui/theme/Color.kt`：

```kotlin
package com.orientlock.ui.theme

import androidx.compose.ui.graphics.Color

// 背景
val BgTop = Color(0xFF0A0C12)
val BgBottom = Color(0xFF131828)
val GlowPurple = Color(0x223B2F7A)

// 玻璃卡片：5% 白填充 + 9% 白描边
val GlassFill = Color(0x0DFFFFFF)
val GlassBorder = Color(0x17FFFFFF)

// 文字 主 / 次 / 弱
val TextPrimary = Color(0xFFEDF0F7)
val TextSecondary = Color(0xFF9AA3B8)
val TextTertiary = Color(0xFF5C6580)

// 模式渐变
val PortraitStart = Color(0xFF8B5CF6)
val PortraitEnd = Color(0xFFEC4899)
val LandscapeStart = Color(0xFF06B6D4)
val LandscapeEnd = Color(0xFF3B82F6)
val ReverseStart = Color(0xFFF59E0B)
val ReverseEnd = Color(0xFFEF4444)
val AutoStart = Color(0xFF64748B)
val AutoEnd = Color(0xFF475569)

// 状态色
val LockedGreen = Color(0xFF34D399)
val WarningAmber = Color(0xFFF59E0B)
```

- [ ] **Step 2: 写字形**

创建 `app/src/main/java/com/orientlock/ui/theme/Type.kt`：

```kotlin
package com.orientlock.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 跟随系统默认中文字体，不内置字体文件，只做字重与字号层次 */
val OrientLockTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
)
```

- [ ] **Step 3: 写主题（强制深色）**

创建 `app/src/main/java/com/orientlock/ui/theme/Theme.kt`：

```kotlin
package com.orientlock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OrientLockColorScheme = darkColorScheme(
    primary = PortraitStart,
    onPrimary = TextPrimary,
    secondary = LandscapeEnd,
    onSecondary = TextPrimary,
    background = BgTop,
    onBackground = TextPrimary,
    surface = GlassFill,
    onSurface = TextPrimary,
    surfaceVariant = GlassFill,
    onSurfaceVariant = TextSecondary,
    outline = GlassBorder,
    outlineVariant = GlassBorder,
    error = ReverseEnd,
    onError = TextPrimary,
)

/**
 * 强制深色，不读 isSystemInDarkTheme()。
 *
 * 理由：整体设计为深色质感，浅色下渐变、光晕、玻璃卡这些视觉重点会全部失效。
 */
@Composable
fun OrientLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OrientLockColorScheme,
        typography = OrientLockTypography,
        content = content,
    )
}
```

- [ ] **Step 4: 编译验证**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:compileDebugKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/orientlock/ui/theme/ && git commit -m "feat: 深色主题与配色规范"
```

---

## Task 12: ViewModel

**Files:**
- Create: `app/src/main/java/com/orientlock/ui/MainViewModel.kt`

- [ ] **Step 1: 写 ViewModel**

创建 `app/src/main/java/com/orientlock/ui/MainViewModel.kt`：

```kotlin
package com.orientlock.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.orientlock.data.SettingsOrientationRepository
import com.orientlock.data.SystemOrientationWriter
import com.orientlock.data.settingsDataStore
import com.orientlock.domain.AppSettings
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.OrientationRepository
import com.orientlock.system.AndroidPermissionChecker
import com.orientlock.system.AndroidServiceGateway
import com.orientlock.system.PermissionChecker
import com.orientlock.system.ServiceGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 界面所需的全部状态 */
data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val canWriteSettings: Boolean = false,
    val canPostNotifications: Boolean = true,
)

/**
 * 主界面 ViewModel。
 *
 * 只依赖 [OrientationRepository] / [PermissionChecker] / [ServiceGateway] 三个接口，
 * 不直接碰 Settings.System 或 DataStore，因此可用假实现做纯 JUnit 测试。
 * 所有设置状态来自 Repository 的 Flow，保证界面与通知栏永远一致。
 */
class MainViewModel(
    private val repository: OrientationRepository,
    private val permissions: PermissionChecker,
    private val services: ServiceGateway,
) : ViewModel() {

    private val permissionSnapshot = MutableStateFlow(
        MainUiState(
            canWriteSettings = permissions.canWriteSettings(),
            canPostNotifications = permissions.canPostNotifications(),
        )
    )

    val uiState: StateFlow<MainUiState> =
        combine(repository.settings, permissionSnapshot) { settings, permission ->
            MainUiState(
                settings = settings,
                canWriteSettings = permission.canWriteSettings,
                canPostNotifications = permission.canPostNotifications,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState(),
        )

    /** 选一个方向。权限未授予时不写系统，也不启动服务，只让界面弹引导。 */
    fun selectMode(mode: OrientationMode) {
        if (!permissions.canWriteSettings()) {
            permissionSnapshot.value = permissionSnapshot.value.copy(canWriteSettings = false)
            return
        }
        viewModelScope.launch {
            repository.setMode(mode)
            services.start()
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoStartOnBoot(enabled) }
    }

    fun setPersistentNotification(enabled: Boolean) {
        viewModelScope.launch {
            repository.setPersistentNotification(enabled)
            if (!enabled) services.stop()
        }
    }

    fun setGuardEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setGuardEnabled(enabled)
            services.restartGuard()
        }
    }

    /** 从 onResume 调用：权限页返回后刷新授权状态 */
    fun refreshPermissions() {
        permissionSnapshot.value = MainUiState(
            settings = permissionSnapshot.value.settings,
            canWriteSettings = permissions.canWriteSettings(),
            canPostNotifications = permissions.canPostNotifications(),
        )
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    repository = SettingsOrientationRepository(
                        dataStore = application.settingsDataStore,
                        writer = SystemOrientationWriter(application),
                    ),
                    permissions = AndroidPermissionChecker(application),
                    services = AndroidServiceGateway(application),
                )
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:compileDebugKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/orientlock/ui/MainViewModel.kt
git commit -m "feat: 主界面 ViewModel（依赖倒置，可纯 JUnit 测试）"
```

---

## Task 13: ViewModel 单元测试（纯 JUnit，不用 Robolectric）

**Files:**
- Create: `app/src/test/java/com/orientlock/fakes/FakeOrientationRepository.kt`
- Create: `app/src/test/java/com/orientlock/fakes/FakePermissionChecker.kt`
- Create: `app/src/test/java/com/orientlock/fakes/FakeServiceGateway.kt`
- Create: `app/src/test/java/com/orientlock/ui/MainViewModelTest.kt`

- [ ] **Step 1: 写假 Repository**

创建 `app/src/test/java/com/orientlock/fakes/FakeOrientationRepository.kt`：

```kotlin
package com.orientlock.fakes

import com.orientlock.domain.AppSettings
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.OrientationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 内存版 Repository，行为与真实实现一致但不碰磁盘与系统设置 */
class FakeOrientationRepository(
    initial: AppSettings = AppSettings(),
) : OrientationRepository {

    private val state = MutableStateFlow(initial)

    /** 记录 setMode 被调用的顺序，供断言 */
    val modeHistory = mutableListOf<OrientationMode>()

    var guardTickReturnValue = false
    var guardTickCallCount = 0

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun setMode(mode: OrientationMode) {
        modeHistory += mode
        state.value = state.value.copy(mode = mode)
    }

    override suspend fun guardTick(): Boolean {
        guardTickCallCount++
        return guardTickReturnValue
    }

    override suspend fun setAutoStartOnBoot(enabled: Boolean) {
        state.value = state.value.copy(autoStartOnBoot = enabled)
    }

    override suspend fun setPersistentNotification(enabled: Boolean) {
        state.value = state.value.copy(persistentNotification = enabled)
    }

    override suspend fun setGuardEnabled(enabled: Boolean) {
        state.value = state.value.copy(guardEnabled = enabled)
    }

    /** 等 settings 流发出满足条件的值，避免测试依赖固定时序 */
    suspend fun awaitSettings(predicate: (AppSettings) -> Boolean): AppSettings =
        settings.first(predicate)
}
```

- [ ] **Step 2: 写假 PermissionChecker**

创建 `app/src/test/java/com/orientlock/fakes/FakePermissionChecker.kt`：

```kotlin
package com.orientlock.fakes

import com.orientlock.system.PermissionChecker

class FakePermissionChecker(
    var writeSettings: Boolean = true,
    var postNotifications: Boolean = true,
) : PermissionChecker {
    override fun canWriteSettings(): Boolean = writeSettings
    override fun canPostNotifications(): Boolean = postNotifications
}
```

- [ ] **Step 3: 写假 ServiceGateway**

创建 `app/src/test/java/com/orientlock/fakes/FakeServiceGateway.kt`：

```kotlin
package com.orientlock.fakes

import com.orientlock.system.ServiceGateway

class FakeServiceGateway : ServiceGateway {
    var startCalls = 0
    var stopCalls = 0
    var restartGuardCalls = 0

    override fun start() { startCalls++ }
    override fun stop() { stopCalls++ }
    override fun restartGuard() { restartGuardCalls++ }
}
```

- [ ] **Step 4: 写测试**

创建 `app/src/test/java/com/orientlock/ui/MainViewModelTest.kt`：

```kotlin
package com.orientlock.ui

import com.orientlock.domain.AppSettings
import com.orientlock.domain.OrientationMode
import com.orientlock.fakes.FakeOrientationRepository
import com.orientlock.fakes.FakePermissionChecker
import com.orientlock.fakes.FakeServiceGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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

    @Test
    fun `初始状态为未锁定`() = runTest {
        val vm = createViewModel()
        assertEquals(OrientationMode.AUTO, vm.uiState.value.settings.mode)
    }

    @Test
    fun `默认开启开机自启、常驻通知与守护`() = runTest {
        val vm = createViewModel()
        val settings = vm.uiState.value.settings
        assertTrue(settings.autoStartOnBoot)
        assertTrue(settings.persistentNotification)
        assertTrue(settings.guardEnabled)
    }

    @Test
    fun `默认未探测天然朝向`() = runTest {
        val vm = createViewModel()
        assertEquals(null, vm.uiState.value.settings.naturalOrientation)
    }

    @Test
    fun `权限已授予时选择竖屏会写入并启动服务`() = runTest {
        permissions.writeSettings = true
        val vm = createViewModel()

        vm.selectMode(OrientationMode.PORTRAIT)

        assertEquals(listOf(OrientationMode.PORTRAIT), repository.modeHistory)
        assertEquals(1, services.startCalls)
    }

    @Test
    fun `权限未授予时选择方向不会写入系统也不会启动服务`() = runTest {
        permissions.writeSettings = false
        val vm = createViewModel()

        vm.selectMode(OrientationMode.PORTRAIT)

        assertTrue(repository.modeHistory.isEmpty())
        assertEquals(0, services.startCalls)
        assertFalse(vm.uiState.value.canWriteSettings)
    }

    @Test
    fun `界面状态跟随 Repository 的设置变化`() = runTest {
        val vm = createViewModel()
        vm.selectMode(OrientationMode.LANDSCAPE)

        val settings = repository.awaitSettings { it.mode == OrientationMode.LANDSCAPE }
        assertEquals(OrientationMode.LANDSCAPE, settings.mode)
    }

    @Test
    fun `选择自动模式同样会启动服务`() = runTest {
        val vm = createViewModel()
        vm.selectMode(OrientationMode.AUTO)
        assertEquals(listOf(OrientationMode.AUTO), repository.modeHistory)
        assertEquals(1, services.startCalls)
    }

    @Test
    fun `关闭常驻通知会停止服务`() = runTest {
        val vm = createViewModel()
        vm.setPersistentNotification(false)

        assertFalse(repository.snapshot().persistentNotification)
        assertEquals(1, services.stopCalls)
    }

    @Test
    fun `打开常驻通知不会停止服务`() = runTest {
        val vm = createViewModel()
        vm.setPersistentNotification(true)

        assertTrue(repository.snapshot().persistentNotification)
        assertEquals(0, services.stopCalls)
    }

    @Test
    fun `切换守护开关会重启守护`() = runTest {
        val vm = createViewModel()
        vm.setGuardEnabled(false)
        assertEquals(1, services.restartGuardCalls)
        assertFalse(repository.snapshot().guardEnabled)
    }

    @Test
    fun `切换开机自启开关会持久化`() = runTest {
        val vm = createViewModel()
        vm.setAutoStartOnBoot(false)
        assertFalse(repository.snapshot().autoStartOnBoot)
    }

    @Test
    fun `刷新权限后授权状态被更新`() = runTest {
        permissions.writeSettings = false
        val vm = createViewModel()
        assertFalse(vm.uiState.value.canWriteSettings)

        permissions.writeSettings = true
        vm.refreshPermissions()

        assertTrue(vm.uiState.value.canWriteSettings)
    }

    @Test
    fun `通知权限未授予时界面状态为 false`() = runTest {
        permissions.postNotifications = false
        val vm = createViewModel()
        assertFalse(vm.uiState.value.canPostNotifications)
    }

    @Test
    fun `连续选择多个方向按顺序记录`() = runTest {
        val vm = createViewModel()
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
```

- [ ] **Step 5: 运行全部单元测试**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`，全部测试通过（Task 2 的 3 个 + Task 3 的 14 个 + Task 4 的 7 个 + 本任务 15 个 = 39 个）

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "test: ViewModel 行为测试（假实现 + 协程测试调度器）"
```

---

## Task 14: 界面组件

**Files:**
- Create: `app/src/main/java/com/orientlock/ui/components/` 下 7 个文件

- [ ] **Step 1: 渐变背景**

创建 `app/src/main/java/com/orientlock/ui/components/GradientBackground.kt`：

```kotlin
package com.orientlock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.orientlock.ui.theme.BgBottom
import com.orientlock.ui.theme.BgTop
import com.orientlock.ui.theme.GlowPurple

/** 深色竖向渐变 + 顶部蓝紫径向光晕 */
@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(GlowPurple, Color.Transparent),
                        center = Offset(200f, 0f),
                        radius = 900f,
                    )
                )
        )
        content()
    }
}
```

- [ ] **Step 2: 状态药丸**

创建 `app/src/main/java/com/orientlock/ui/components/StatusPill.kt`：

```kotlin
package com.orientlock.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.orientlock.domain.OrientationMode
import com.orientlock.ui.theme.LockedGreen
import com.orientlock.ui.theme.TextTertiary

/** 顶部状态药丸：锁定时绿色，未锁定时灰蓝 */
@Composable
fun StatusPill(current: OrientationMode, modifier: Modifier = Modifier) {
    val locked = current != OrientationMode.AUTO
    val accent = if (locked) LockedGreen else TextTertiary
    val text = if (locked) "已锁定 · ${current.label}" else "未锁定 · 跟随传感器"

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(accent)
        )
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                slideInVertically { it } togetherWith slideOutVertically { -it }
            },
            label = "status-text",
        ) { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
```

- [ ] **Step 3: 可旋转手机示意图（Canvas 手绘）**

创建 `app/src/main/java/com/orientlock/ui/components/PhonePreview.kt`：

```kotlin
package com.orientlock.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import com.orientlock.domain.userRotationFor
import com.orientlock.ui.theme.AutoStart
import com.orientlock.ui.theme.GlassBorder
import com.orientlock.ui.theme.LandscapeEnd
import com.orientlock.ui.theme.LandscapeStart
import com.orientlock.ui.theme.PortraitEnd
import com.orientlock.ui.theme.PortraitStart

/**
 * 界面视觉中心：一个手绘的手机示意图。
 *
 * - 机身用 Canvas 绘制，不用图片资源，颜色随模式变化且可无损缩放
 * - 锁定时整块绕中心做弹簧旋转，带回弹
 * - 未锁定时描边降为暗色，并叠加缓慢的呼吸透明度
 */
@Composable
fun PhonePreview(
    mode: OrientationMode,
    natural: NaturalOrientation,
    modifier: Modifier = Modifier,
) {
    val targetDegrees = (mode.userRotationFor(natural) ?: 0) * 90f
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(targetDegrees) {
        rotation.animateTo(
            targetValue = targetDegrees,
            animationSpec = spring(
                dampingRatio = 0.55f,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    val locked = mode != OrientationMode.AUTO
    val breatheAlpha = if (locked) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "breathe")
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breathe-alpha",
        ).value
    }

    val measurer = rememberTextMeasurer()

    Box(
        modifier = modifier.size(width = 300.dp, height = 300.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(280.dp)) {
            rotate(degrees = rotation.value, pivot = center) {
                drawPhone(
                    mode = mode,
                    locked = locked,
                    alpha = breatheAlpha,
                    textMeasurer = measurer,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPhone(
    mode: OrientationMode,
    locked: Boolean,
    alpha: Float,
    textMeasurer: TextMeasurer,
) {
    val phoneWidth = size.minDimension * 0.40f
    val phoneHeight = size.minDimension * 0.82f
    val cornerRadius = phoneWidth * 0.20f
    val left = center.x - phoneWidth / 2f
    val top = center.y - phoneHeight / 2f

    val (start, end) = modeGradientColors(mode)
    val borderColor = if (locked) start else GlassBorder

    // 机身描边
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(left, top),
        size = Size(phoneWidth, phoneHeight),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
        alpha = alpha,
    )
    // 屏幕内填充：用渐变的中点色，透明度较低
    drawRoundRect(
        color = lerp(start, end, 0.5f).copy(alpha = 0.12f * alpha),
        topLeft = Offset(left + phoneWidth * 0.06f, top + phoneHeight * 0.035f),
        size = Size(phoneWidth * 0.88f, phoneHeight * 0.93f),
        cornerRadius = CornerRadius(cornerRadius * 0.85f, cornerRadius * 0.85f),
    )
    // 听筒胶囊
    drawRoundRect(
        color = borderColor.copy(alpha = 0.55f * alpha),
        topLeft = Offset(center.x - phoneWidth * 0.10f, top + phoneHeight * 0.04f),
        size = Size(phoneWidth * 0.20f, phoneHeight * 0.010f),
        cornerRadius = CornerRadius(phoneHeight * 0.005f, phoneHeight * 0.005f),
    )
    // Home 指示条
    drawRoundRect(
        color = borderColor.copy(alpha = 0.45f * alpha),
        topLeft = Offset(center.x - phoneWidth * 0.15f, top + phoneHeight * 0.935f),
        size = Size(phoneWidth * 0.30f, phoneHeight * 0.008f),
        cornerRadius = CornerRadius(phoneHeight * 0.004f, phoneHeight * 0.004f),
    )
    // 中心方向字（用 Compose 的 drawText，会自动跟随 rotate 变换）
    drawText(
        textMeasurer = textMeasurer,
        text = modeGlyph(mode),
        topLeft = Offset(
            x = center.x - phoneWidth * 0.30f,
            y = center.y - phoneHeight * 0.07f,
        ),
        style = TextStyle(
            color = Color.White.copy(alpha = alpha),
            fontSize = with(density) { (phoneWidth * 0.36f).toSp() },
            fontWeight = FontWeight.Bold,
        ),
    )
}

private fun modeGradientColors(mode: OrientationMode): Pair<Color, Color> = when (mode) {
    OrientationMode.PORTRAIT,
    OrientationMode.PORTRAIT_REVERSE,
    OrientationMode.CURRENT -> PortraitStart to PortraitEnd

    OrientationMode.LANDSCAPE,
    OrientationMode.LANDSCAPE_REVERSE -> LandscapeStart to LandscapeEnd

    OrientationMode.AUTO -> AutoStart to AutoStart
}

private fun modeGlyph(mode: OrientationMode): String = when (mode) {
    OrientationMode.PORTRAIT, OrientationMode.PORTRAIT_REVERSE -> "竖"
    OrientationMode.LANDSCAPE, OrientationMode.LANDSCAPE_REVERSE -> "横"
    OrientationMode.CURRENT -> "当"
    OrientationMode.AUTO -> "自"
}
```

- [ ] **Step 4: 方向卡片**

创建 `app/src/main/java/com/orientlock/ui/components/ModeCard.kt`：

```kotlin
package com.orientlock.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.orientlock.R
import com.orientlock.domain.OrientationMode
import com.orientlock.ui.theme.GlassBorder
import com.orientlock.ui.theme.GlassFill
import com.orientlock.ui.theme.TextSecondary

/** 方向选择卡片；选中态用渐变描边 + 轻微放大 */
@Composable
fun ModeCard(
    mode: OrientationMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.03f else 1f,
        label = "card-scale",
    )
    val shape = RoundedCornerShape(20.dp)
    val gradient = modeGradient(mode)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .scale(scale)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    if (selected) gradient.map { it.copy(alpha = 0.16f) }
                    else listOf(GlassFill, GlassFill)
                )
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                brush = if (selected) {
                    Brush.linearGradient(gradient)
                } else {
                    Brush.linearGradient(listOf(GlassBorder, GlassBorder))
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        if (selected) gradient else gradient.map { it.copy(alpha = 0.35f) }
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(modeIconRes(mode)),
                contentDescription = mode.label,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = mode.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else TextSecondary,
        )
    }
}

internal fun modeGradient(mode: OrientationMode): List<Color> = when (mode) {
    OrientationMode.PORTRAIT,
    OrientationMode.PORTRAIT_REVERSE,
    OrientationMode.CURRENT -> listOf(
        com.orientlock.ui.theme.PortraitStart,
        com.orientlock.ui.theme.PortraitEnd,
    )

    OrientationMode.LANDSCAPE,
    OrientationMode.LANDSCAPE_REVERSE -> listOf(
        com.orientlock.ui.theme.LandscapeStart,
        com.orientlock.ui.theme.LandscapeEnd,
    )

    OrientationMode.AUTO -> listOf(
        com.orientlock.ui.theme.AutoStart,
        com.orientlock.ui.theme.AutoEnd,
    )
}

internal fun modeIconRes(mode: OrientationMode): Int = when (mode) {
    OrientationMode.PORTRAIT -> R.drawable.ic_mode_portrait
    OrientationMode.PORTRAIT_REVERSE -> R.drawable.ic_mode_portrait_reverse
    OrientationMode.LANDSCAPE -> R.drawable.ic_mode_landscape
    OrientationMode.LANDSCAPE_REVERSE -> R.drawable.ic_mode_landscape_reverse
    OrientationMode.CURRENT -> R.drawable.ic_mode_current
    OrientationMode.AUTO -> R.drawable.ic_mode_auto
}
```

- [ ] **Step 5: 设置开关行与卡片容器**

创建 `app/src/main/java/com/orientlock/ui/components/SettingRow.kt`：

```kotlin
package com.orientlock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import com.orientlock.ui.theme.GlassBorder
import com.orientlock.ui.theme.GlassFill
import com.orientlock.ui.theme.LockedGreen
import com.orientlock.ui.theme.TextTertiary

/** 设置页的一行开关：左图标 + 标题 + 说明，右 Switch */
@Composable
fun SettingRow(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(LockedGreen.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = title,
                tint = LockedGreen,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = LockedGreen,
                uncheckedThumbColor = com.orientlock.ui.theme.TextSecondary,
                uncheckedTrackColor = GlassFill,
                uncheckedBorderColor = TextTertiary,
            ),
        )
    }
}

/** 包裹多行开关的玻璃卡片容器 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(GlassFill, GlassFill)))
            .border(1.dp, GlassBorder, shape),
    ) {
        content()
    }
}
```

- [ ] **Step 6: 权限引导卡**

创建 `app/src/main/java/com/orientlock/ui/components/PermissionBanner.kt`：

```kotlin
package com.orientlock.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.orientlock.R
import com.orientlock.ui.theme.TextSecondary
import com.orientlock.ui.theme.WarningAmber

/** 「修改系统设置」权限引导；授权后由 visible=false 收起 */
@Composable
fun PermissionBanner(
    visible: Boolean,
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(WarningAmber.copy(alpha = 0.08f))
                .border(1.5.dp, WarningAmber.copy(alpha = 0.5f), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = "需要权限",
                    tint = WarningAmber,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "需要一项权限",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "锁定屏幕方向需要开启「修改系统设置」权限。点下方按钮跳到系统设置页打开开关，返回后即可使用。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WarningAmber,
                    contentColor = Color(0xFF1A1206),
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("去开启")
            }
        }
    }
}
```

- [ ] **Step 7: ROM 适配卡**

创建 `app/src/main/java/com/orientlock/ui/components/RomAdaptCard.kt`：

```kotlin
package com.orientlock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.orientlock.R
import com.orientlock.ui.theme.GlassBorder
import com.orientlock.ui.theme.GlassFill
import com.orientlock.ui.theme.TextSecondary
import com.orientlock.ui.theme.TextTertiary

/**
 * 国产 ROM 适配引导。
 *
 * 小米 / 华为 / OPPO / vivo 会杀掉后台服务，需要在各自的自启动管理里放行。
 */
@Composable
fun RomAdaptCard(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(GlassFill, GlassFill)))
            .border(1.dp, GlassBorder, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_rom),
                contentDescription = "系统适配",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "系统适配",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "小米、华为、OPPO、vivo 等系统会限制应用后台自启。" +
                "若重启手机后方向没有自动恢复，请在系统的「自启动管理」或「应用启动管理」里" +
                "允许本应用自启，并在电池设置里关闭对其的后台限制。",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
        )
        OutlinedButton(
            onClick = onOpenSettings,
            colors = OutlinedButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = TextSecondary,
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("打开设置")
        }
    }
}
```

- [ ] **Step 8: 编译验证**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:compileDebugKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/orientlock/ui/components/ && git commit -m "feat: 界面组件（手机示意图、方向卡、开关、权限引导、ROM 适配）"
```

---

## Task 15: 主界面组装

**Files:**
- Create: `app/src/main/java/com/orientlock/ui/AppRoot.kt`
- Create: `app/src/main/java/com/orientlock/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/orientlock/MainActivity.kt`

- [ ] **Step 1: 写 AppRoot**

创建 `app/src/main/java/com/orientlock/ui/AppRoot.kt`：

```kotlin
package com.orientlock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orientlock.ui.components.GradientBackground

/** 把 ViewModel 与主题接起来，供 MainActivity 直接调用 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(
        factory = MainViewModel.factory(context.applicationContext as android.app.Application)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    GradientBackground {
        MainScreen(state = state, viewModel = viewModel)
    }
}
```

- [ ] **Step 2: 写主界面**

创建 `app/src/main/java/com/orientlock/ui/MainScreen.kt`：

```kotlin
package com.orientlock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.orientlock.R
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import com.orientlock.system.PermissionIntents
import com.orientlock.ui.components.ModeCard
import com.orientlock.ui.components.PermissionBanner
import com.orientlock.ui.components.PhonePreview
import com.orientlock.ui.components.RomAdaptCard
import com.orientlock.ui.components.SettingRow
import com.orientlock.ui.components.SettingsCard
import com.orientlock.ui.components.StatusPill
import com.orientlock.ui.theme.TextTertiary

private val MODE_GRID = listOf(
    OrientationMode.PORTRAIT,
    OrientationMode.PORTRAIT_REVERSE,
    OrientationMode.LANDSCAPE,
    OrientationMode.LANDSCAPE_REVERSE,
    OrientationMode.CURRENT,
    OrientationMode.AUTO,
)

@Composable
fun MainScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 从系统权限页返回时复检授权状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentMode = state.settings.mode

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "屏幕方向锁",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "让每一屏都按你要的方向显示",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        PermissionBanner(
            visible = !state.canWriteSettings,
            onGrantClick = {
                context.startActivity(PermissionIntents.writeSettings(context))
            },
        )

        Spacer(Modifier.height(16.dp))

        StatusPill(current = currentMode)

        Spacer(Modifier.height(16.dp))

        PhonePreview(
            mode = currentMode,
            natural = state.settings.naturalOrientation ?: NaturalOrientation.PORTRAIT,
        )

        Text(
            text = "所有跟随系统方向的应用",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(24.dp))

        // 方向网格：2 列
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MODE_GRID.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { mode ->
                        ModeCard(
                            mode = mode,
                            selected = currentMode == mode,
                            onClick = { viewModel.selectMode(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        SettingsCard {
            SettingRow(
                iconRes = R.drawable.ic_setting_boot,
                title = "开机自启",
                subtitle = "重启后自动恢复上次锁定",
                checked = state.settings.autoStartOnBoot,
                onCheckedChange = viewModel::setAutoStartOnBoot,
            )
            SettingRow(
                iconRes = R.drawable.ic_setting_notification,
                title = "常驻通知",
                subtitle = "下拉通知栏即可切换方向",
                checked = state.settings.persistentNotification,
                onCheckedChange = viewModel::setPersistentNotification,
            )
            SettingRow(
                iconRes = R.drawable.ic_setting_guard,
                title = "守护模式",
                subtitle = "方向被改掉时自动改回来",
                checked = state.settings.guardEnabled,
                onCheckedChange = viewModel::setGuardEnabled,
            )
        }

        Spacer(Modifier.height(16.dp))

        RomAdaptCard(
            onOpenSettings = {
                val intent = PermissionIntents.romAutoStart(context)
                    ?: PermissionIntents.appDetails(context)
                runCatching { context.startActivity(intent) }
                    .onFailure {
                        context.startActivity(PermissionIntents.appDetails(context))
                    }
            },
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "若应用自身写死了方向，本软件无法改变它",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
        )

        Spacer(Modifier.height(28.dp))
    }
}
```

- [ ] **Step 3: 替换 MainActivity 为真实界面 + 请求通知权限**

创建 `app/src/main/java/com/orientlock/MainActivity.kt`：

```kotlin
package com.orientlock

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import com.orientlock.system.PermissionIntents
import com.orientlock.ui.AppRoot
import com.orientlock.ui.theme.OrientLockTheme

class MainActivity : AppCompatActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OrientLockTheme {
                AppRoot()

                // Android 13 起通知需要运行时权限；未授予则通知栏入口不可用，
                // 不影响锁定功能本身
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestNotificationPermission.launch(
                            PermissionIntents.NOTIFICATION_PERMISSION
                        )
                    }
                }
            }
        }
    }
}
```

> `PermissionIntents.NOTIFICATION_PERMISSION` 的类型是 String，不是 Manifest.permission 常量，但值相同；`requestPermission` 接受的正是 `@StringDef` 标注的常量，此处用值传递即可编译通过。若 Lint 报 `InlinedApi` 或权限名警告，把 `launch(...)` 改为 `launch(Manifest.permission.POST_NOTIFICATIONS)`（`android.Manifest` 已在文件顶部 import）。

- [ ] **Step 4: 构建验证**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`，产出 `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: 主界面组装完成，应用可用"
```

---

## Task 16: Lint、README 与交付物

**Files:**
- Create: `README.md`
- Create: `docs/真机自测清单.md`
- Modify: `app/proguard-rules.pro`

- [ ] **Step 1: 运行 Lint**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:lintDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`。有 error 必须先修；warning 逐个判断，能修的修掉，不能修的在 Step 3 用 lint 配置消音并写明原因。

- [ ] **Step 2: 跑全部单元测试**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`，39 个测试全部通过

- [ ] **Step 3: 补全 proguard 规则**

创建 `app/proguard-rules.pro`：

```proguard
# manifest 中引用的广播接收器，显式保留更稳
-keep class com.orientlock.system.BootReceiver { *; }

# Compose 与 DataStore 由各自依赖自带规则；这两条消除注解库的无关告警
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**
```

- [ ] **Step 4: 构建 release APK**

Run:
```bash
cd /e/APP/2026-9-25 && ./gradlew :app:assembleRelease --console=plain
```

Expected: `BUILD SUCCESSFUL`，产出 `app/build/outputs/apk/release/app-release-unsigned.apk`

- [ ] **Step 5: 写 README**

创建 `README.md`：

````markdown
# 屏幕方向锁

一款免 root 的安卓屏幕方向全局锁定工具。锁一次，所有跟随系统方向的应用都按指定方向显示；重启手机后自动恢复，全程不弹界面。

## 功能

- **全局方向锁定**：竖屏、反向竖屏、横屏、反向横屏、当前方向、自动（跟随传感器）共 6 种模式
- **常驻通知栏**：一条不可划掉的通知，带「竖屏 / 横屏 / 反向 / 解除」四个按钮，不打开应用即可切换
  - 「反向」= 当前已锁方向的反向（竖屏 ↔ 反向竖屏，横屏 ↔ 反向横屏）；未锁定时按反向竖屏
- **开机自启**：重启后自动恢复上次锁定方向
- **守护模式**：系统方向被其他途径改掉时自动改回来
- **全中文深色界面**：深色玻璃质感、方向渐变配色、可旋转的手机示意图

## 权限说明

| 权限 | 用途 | 授予方式 |
|---|---|---|
| 修改系统设置（WRITE_SETTINGS） | 写入系统方向，锁定的核心 | 首次打开应用时引导，需在系统页手动打开 |
| 通知（POST_NOTIFICATIONS） | 显示常驻通知栏入口 | 首次打开应用时请求（Android 13+） |
| 开机启动（RECEIVE_BOOT_COMPLETED） | 重启后自动恢复 | 安装时授予 |
| 前台服务 | 保持通知与守护常驻 | 安装时授予 |

## 重要限制

安卓应用的屏幕方向由两部分决定：系统设置里的「自动旋转」及其角度值，和应用自己在代码里声明写死的方向。本软件通过「修改系统设置」权限控制前者，**无法控制后者**。

因此：**在代码里写死了方向的应用——绝大多数游戏、部分视频与直播应用、部分支付类应用——锁不住。** 这是免 root 方案的能力边界，不是缺陷。

## 国产 ROM 适配

小米、华为、OPPO、vivo 会限制后台自启。若重启后方向没有自动恢复，请在：

- 系统的「自启动管理」/「应用启动管理」里允许本应用自启
- 电池设置里关闭对本应用的后台限制

应用内「系统适配」卡片提供跳转按钮。

## 构建

环境要求：JDK 21（Android Studio 自带 JBR）、Android SDK（compileSdk 36）。

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

跑单元测试：

```bash
./gradlew testDebugUnitTest
```

## 正式签名

`app/build.gradle.kts` 未配置正式签名。发布前请准备 keystore，在 `android.buildTypes.release` 里配置 `signingConfigs`，或用 Android Studio 的 Generate Signed Bundle / APK。

## 兼容性

- 最低支持 Android 8.0（API 26）
- `targetSdk` 定为 35 而非 36：Android 16（API 36）起系统会无视平板与折叠屏上应用声明写死的方向，定 35 可让本软件在大屏设备上继续生效
````

- [ ] **Step 6: 写真机自测清单**

创建 `docs/真机自测清单.md`：

````markdown
# 真机自测清单

> 开发机上没有连接的安卓设备，以下条目需在手机上逐条验证。按顺序做，每条做完打勾。

## 准备

- [ ] 安装 `app-debug.apk`，应用名显示为「屏幕方向锁」，图标正常
- [ ] 首次打开，界面为深色，中文显示正常，无英文残留
- [ ] 顶部出现琥珀色权限引导卡

## 权限

- [ ] 点「去开启」，跳到系统的「修改系统设置」页
- [ ] 打开开关，返回应用，权限卡消失，界面立即可用
- [ ] （Android 13+）首次打开时弹出通知权限请求，允许后通知栏功能可用

## 锁定功能

- [ ] 点「竖屏」：转动手机屏幕不横过来；中央手机示意图旋转到竖立后停住
- [ ] 点「反向竖屏」：示意图转 180°，画面确实是颠倒的
- [ ] 点「横屏」：屏幕横过来；示意图横躺并套用青蓝渐变描边
- [ ] 点「反向横屏」：朝另一侧横躺
- [ ] 点「当前方向」：锁定为选中瞬间设备所处的方向
- [ ] 点「自动」：恢复跟随传感器；示意图描边变暗并缓慢呼吸
- [ ] 切到其他应用（浏览器、设置），方向保持锁定
- [ ] 打开一个写死竖屏的应用（微信、支付宝），它仍按自己的方向走 —— **预期行为，不是 bug**

## 通知栏

- [ ] 下拉通知栏，看到常驻通知「xx锁定中」，不能左滑划掉
- [ ] 通知有四个按钮：竖屏、横屏、反向、解除
- [ ] 点「横屏」：屏幕立即横过来，不用打开应用
- [ ] 竖屏锁定时点「反向」：切到反向竖屏
- [ ] 横屏锁定时点「反向」：切到反向横屏
- [ ] 点「解除」：恢复自动旋转

## 守护

- [ ] 锁定竖屏，下拉系统状态栏打开「自动旋转」快捷开关
- [ ] 约 1 秒内应被自动关掉，方向仍保持竖屏
- [ ] 到设置里关掉「守护模式」，再打开系统「自动旋转」，这次能成功打开、方向不再被强制

## 开机自启

- [ ] 锁定竖屏，重启手机
- [ ] 开机进桌面后方向仍为竖屏，转动手机不横过来
- [ ] 通知栏已有常驻通知，内容与锁定的方向一致
- [ ] 全程没有弹出任何界面
- [ ] 若未自动恢复，按应用内「系统适配」卡的说明开启自启动白名单，再重启验证

## 设置开关

- [ ] 关掉「常驻通知」：通知消失，锁定功能仍生效
- [ ] 重新打开「常驻通知」：通知恢复
- [ ] 关掉「开机自启」再重启：开机后方向不自动恢复
- [ ] 重新打开「开机自启」再重启：恢复正常

## 平板 / 折叠屏（如有）

- [ ] 平板（天然横屏）上点「横屏」：确实横屏，不是反向
- [ ] 平板上点「竖屏」：确实竖屏
- [ ] 折叠屏展开与合上后方向仍正确

## 稳定性

- [ ] 连续锁定 / 解除 20 次，无卡顿、无闪退
- [ ] 后台放置 1 小时，方向仍保持，通知仍在
- [ ] 系统「电池」里查看，本应用耗电应在低水平
````

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "docs: README、真机自测清单与 proguard 规则"
```

---

## 收尾核对

**Spec 覆盖**

| Spec 条目 | 任务 |
|---|---|
| F1 六种方向模式 | Task 3、Task 5、Task 15 |
| F2 常驻通知栏 | Task 8、Task 9 |
| F3 开机自启 | Task 10 Step 5 / Step 7 |
| F4 守护模式 | Task 4、Task 7 `guardTick`、Task 9 |
| F5 深色精美界面 | Task 11、Task 14、Task 15 |
| F6 全中文 | Task 14、Task 15 全部文案；Task 16 Step 6 验证项 |
| F7 权限引导 | Task 10 Step 1/2、Task 14 Step 6、Task 15 |
| F8 国产 ROM 适配 | Task 10 Step 2、Task 14 Step 7 |
| §2.2 两个动作 | Task 6 `apply` |
| §2.4 天然朝向探测 | Task 6 `naturalOrientation` |
| §2.5 两条映射表 | Task 3 映射测试 |
| §2.6 targetSdk 35 | Task 1 Step 7 |
| §3.1 权限清单 | Task 10 Step 7 |
| §3.2 specialUse 类型 | Task 10 Step 7 + Task 9 `startForegroundCompat` |
| §3.3 权限流程 | Task 15 Step 2（ON_RESUME 复检） |
| §4.2 双路径守护 | Task 9（ContentObserver + 10 秒心跳） |
| §4.4 守护开关 | Task 12 `setGuardEnabled` |
| §5.1 布局 | Task 15 Step 2 |
| §5.2 Canvas 手机 | Task 14 Step 3 |
| §5.3 配色 | Task 11 Step 1 |
| §5.4 字体间距 | Task 11 Step 2 |
| §5.5 动效 | Task 14 Step 2/3/4 |
| §5.6 强制深色 | Task 11 Step 3 |
| §6 通知设计 | Task 8 |
| §7 代码结构 | 全部 |
| §7.3 开机兜底 | Task 10 Step 4 |
| §7.4 ROM 适配 | Task 10 Step 2 |
| §8 工程配置 | Task 1 |
| §9 测试策略 | Task 2/3/4/13 |
| §9.1 验收边界 | Task 16 Step 6 |
| §10 交付物 | Task 16 |
| §11 实现顺序 | 任务顺序即按此排 |

**相对 spec 的三处有意变更，均为规避真实缺陷：**

1. **不引入 `material-icons-extended`**，改为自绘矢量图。理由：该依赖含上千个图标类，显著拖慢构建、增大 APK，且无法定制。
2. **ViewModel 改为依赖三个接口**（`OrientationRepository` / `PermissionChecker` / `ServiceGateway`）而非直接持有 Android 对象。理由：原设计下 ViewModel 只能靠 Robolectric + DataStore 异步回读测试，不稳定；依赖倒置后可用假实现做纯 JUnit 测试。
3. **`BootReceiver` 不处理 `LOCKED_BOOT_COMPLETED`、不用 `directBootAware`**。理由：那会在用户解锁前运行，此时 DataStore 所在凭据加密存储不可读，必然崩溃。同时把广播里的活移到 `goAsync()` + 协程，避免 ANR。

**类型一致性已核对**：`OrientationMode`、`NaturalOrientation`、`RotationState`、`shouldReapply`、`userRotationFor`、`OrientationRepository`、`AppSettings`、`ServiceGateway`、`PermissionChecker` 在定义任务与使用任务之间签名逐字一致。
