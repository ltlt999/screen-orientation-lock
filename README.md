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
| 修改系统设置（WRITE_SETTINGS） | 写入系统方向，锁定的核心 | 首次打开应用时引导，需在系统页手动打开 |
| 通知（POST_NOTIFICATIONS） | 显示常驻通知栏入口 | 首次打开应用时请求（Android 13+）；未授予不影响锁定，只是没有快捷入口 |
| 开机启动（RECEIVE_BOOT_COMPLETED） | 重启后自动恢复 | 安装时授予 |
| 前台服务 | 保持通知与守护常驻 | 安装时授予 |

应用**不申请**电池优化白名单权限。各国产 ROM 的 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 弹窗既受 Play 政策限制，在 MIUI/EMUI/ColorOS/OriginOS 上又基本无效，所以改用应用内「系统适配」卡片直接跳各家自启动管理页。

## 重要限制

安卓应用的屏幕方向由两部分决定：系统设置里的「自动旋转」及其角度值，和应用自己在代码里声明写死的方向。本软件通过「修改系统设置」权限控制前者，**无法控制后者**。

因此：**在代码里写死了方向的应用——绝大多数游戏、部分视频与直播应用、部分支付类应用——锁不住。** 这是免 root 方案的能力边界，不是缺陷。

另外，界面底部也写着这句话，避免误以为软件坏了。

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
