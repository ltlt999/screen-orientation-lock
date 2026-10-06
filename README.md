# 屏幕方向锁

一款免 root 的安卓屏幕方向全局锁定工具。锁一次，所有跟随系统方向的应用都按指定方向显示；重启手机后自动恢复，全程不弹界面。

## 功能

- **全局方向锁定**：竖屏、反向竖屏、横屏、反向横屏、当前方向、自动（跟随传感器）共 6 种模式
- **常驻通知栏**：一条不可划掉的通知，带「竖屏 / 横屏 / 解除 / 反向」四个按钮，不打开应用即可切换
  - 系统折叠态最多显示 3 个按钮，所以「反向」要展开通知才看得到；三个最常用的常驻可见
  - 「反向」= 当前已锁方向的反向（竖屏 ↔ 反向竖屏，横屏 ↔ 反向横屏）；未锁定或当前方向模式下按反向竖屏
- **开机自启**：重启后自动恢复上次锁定方向
- **守护模式**：系统方向被其他途径改掉时自动改回来
- **全中文深色界面**：深色玻璃质感、方向渐变配色、可旋转的手机示意图

## 权限说明

| 权限 | 用途 | 授予方式 |
|---|---|---|
| **显示在其他应用上层**（SYSTEM_ALERT_WINDOW） | **推荐**。用悬浮窗强制整个屏幕的方向，**能锁住自己声明了方向的应用**（车机桌面、部分音视频应用） | 应用内点「开启悬浮窗」跳转，需在系统页手动打开 |
| 修改系统设置（WRITE_SETTINGS） | 备用通路。写系统方向设置，只能锁住「跟随系统方向」的应用 | 应用内引导，需在系统页手动打开 |
| 通知（POST_NOTIFICATIONS） | 显示常驻通知栏入口 | 首次打开应用时请求（Android 13+）；未授予不影响锁定，只是没有快捷入口 |
| 开机启动（RECEIVE_BOOT_COMPLETED） | 重启后自动恢复 | 安装时授予 |
| 前台服务 | 保持通知、守护与悬浮窗常驻 | 安装时授予 |

**两条锁定通路有一条能用就能锁。** 推荐给悬浮窗权限：它是唯一能压过应用自身方向声明的通路。只给「修改系统设置」时，车机桌面那类应用锁不住。

应用**不申请**电池优化白名单权限。各国产 ROM 的 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 弹窗既受 Play 政策限制，在 MIUI/EMUI/ColorOS/OriginOS 上又基本无效，所以改用应用内「系统适配」卡片直接跳各家自启动管理页。

## 关于「有些应用锁不住」

安卓的优先级是：**应用自己声明的方向 > 全局设置**。所以只写系统设置的话，凡是自己在代码里声明了方向的应用都锁不住——车机桌面（氢桌面声明的是 `sensorLandscape`）、部分视频与直播应用、部分游戏都属于这一类。

**授予「显示在其他应用上层」后，这类应用也能锁住。** 原理是添加一个 0×0 的悬浮窗并在其窗口参数上声明方向——悬浮窗在窗口层级上高于普通应用窗口，窗口管理器计算屏幕方向时会把它的请求算进去，于是应用自己的声明被压过去。

这条路子已实测验证：在声明了 `sensorLandscape` 的车机桌面前台，锁定竖屏后屏幕保持竖屏 20 秒以上；杀掉本应用撤下悬浮窗，该桌面立刻抢回横屏。

仍然锁不住的只有一种：**系统自己固定的方向**（例如某些车机的开机画面、锁屏），那不属于应用层能触及的范围。

## 国产 ROM 适配

小米、华为、OPPO、vivo 会限制后台自启。若重启后方向没有自动恢复，请在：

- 系统的「自启动管理」/「应用启动管理」里允许本应用自启
- 电池设置里关闭对本应用的后台限制

应用内「系统适配」卡片提供跳转按钮，能自动识别四家 ROM 的设置页，识别不了就落到应用详情页。

## 构建

### 环境要求

- **JDK 21**，推荐直接用 Android Studio 自带 JBR
- Android SDK，`compileSdk 36`
- 本机 Gradle 8.12（wrapper 已随仓库提交，无需单独安装）

### 一个容易踩的坑：系统默认 JDK 太新会构建失败

`gradle.properties` 里写死了

```properties
org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
```

因为开发机的默认 JDK 是 Java 26，与 AGP 8.9.3 不兼容，不指定就会报
`Unsupported class file major version`。**在别的机器上打开这个工程，请把这一行改成本机 JDK 21 的路径**，或删掉它并设好 `JAVA_HOME`。Gradle 只从 `gradle.properties` 或 `JAVA_HOME` 读这个属性，写在 `local.properties` 里无效。

另外 `local.properties` 里的 `sdk.dir` 同样是本机路径，换机器要在 Android Studio 里重新同步一次 SDK 路径。

### 命令

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

跑单元测试：

```bash
./gradlew testDebugUnitTest
```

打 release 包（R8 压缩 + 资源裁剪，未签名）：

```bash
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release-unsigned.apk`

## 正式签名

`app/build.gradle.kts` 未配置正式签名。发布前请准备 keystore，在 `android.buildTypes.release` 里配 `signingConfigs`，或直接用 Android Studio 的 Generate Signed Bundle / APK。

## 兼容性

- 最低支持 Android 8.0（API 26）
- `targetSdk` 定为 35 而非 36：Android 16（API 36）起，系统会**无视平板与折叠屏上应用声明写死的方向**。定 35 可让本软件在大屏设备上继续生效。

## 代码结构

```
app/src/main/java/com/orientlock/
├── OrientLockApp.kt        Application：建通知渠道、带兜底的服务启动
├── MainActivity.kt         单 Activity
├── domain/                 纯 Kotlin，零安卓依赖，可 JVM 单测
│   ├── NaturalOrientation.kt    天然朝向枚举 + 纯函数判定（含 DisplayRotation）
│   ├── OrientationMode.kt       6 种模式 × 中文标签 / 持久化名 / 系统值映射
│   ├── OrientationGuard.kt      守护偏离判定
│   ├── AppSettings.kt           设置数据模型
│   └── OrientationRepository.kt 数据访问接口（依赖倒置，便于测试）
├── data/
│   ├── SystemOrientationAccess.kt  平台读写（唯一接触 Settings.System 之处）
│   ├── SystemOrientationWriter.kt  方向写入策略（探测互斥、双采样、先校验再写）
│   ├── SettingsOrientationRepository.kt  DataStore + 守护心跳
│   └── Preferences.kt               DataStore 委托
├── system/
│   ├── OrientationService.kt   前台服务：常驻通知 + 守护
│   ├── BootReceiver.kt         开机 / 应用更新后拉起
│   ├── NotificationHelper.kt   通知渠道与通知构建
│   ├── PermissionChecker.kt    权限查询接口 + 安卓实现
│   ├── PermissionIntents.kt    系统设置页跳转
│   └── ServiceGateway.kt       启停服务的接口 + 安卓实现
└── ui/
    ├── theme/                  配色 / 字形 / 主题 / 模式渐变（唯一来源）
    ├── MainViewModel.kt        UI 状态与用户意图
    ├── MainScreen.kt           主界面
    ├── AppRoot.kt              ViewModel 与主题的接线
    └── components/             PhonePreview / ModeCard / StatusPill / …
```

`domain` 层不 import 任何 `android.*`，所以 75 个单元测试全部跑在 JVM 上，不需要模拟器，也不需要 Robolectric。`Settings.System` 的读写被限制在 `SystemOrientationAccess` 一个类里，是全应用风险最集中处，也因此有专门测试。

动手改这类软件前，建议先读 `docs/真机自测清单.md`。
