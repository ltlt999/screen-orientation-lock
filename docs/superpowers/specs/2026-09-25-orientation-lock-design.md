# 屏幕方向锁 · 设计文档

- 日期：2026-09-25
- 应用名：屏幕方向锁
- 包名：`com.orientlock`
- 目标平台：Android 8.0（API 26）及以上
- 形态：单模块原生安卓应用，Kotlin + Jetpack Compose

---

## 1. 目标与范围

做一款免 root 的安卓屏幕方向锁定软件。锁一次之后，手机上所有"跟随系统方向"的应用都按指定方向显示；重启手机后自动恢复上次的锁定，全程不弹界面。

### 1.1 功能清单

| # | 功能 | 说明 |
|---|---|---|
| F1 | 全局方向锁定 | 竖屏、反向竖屏、横屏、反向横屏、当前方向、自动（跟随传感器）共 6 种模式 |
| F2 | 常驻通知栏 | 一条不可划掉的常驻通知，带 4 个方向按钮，不打开 App 即可切换 |
| F3 | 开机自启 | 重启后自动恢复上次锁定方向，静默运行 |
| F4 | 守护模式 | 系统方向被其他途径改掉时自动改回来（可开关） |
| F5 | 精美深色界面 | 深色玻璃质感、方向渐变配色、可旋转的手机示意图 |
| F6 | 全中文界面 | 所有 UI 文案、通知、权限引导均为中文 |
| F7 | 权限引导 | 「修改系统设置」权限的一步式引导，授权后自动消失 |
| F8 | 国产 ROM 适配 | 自启动管理 / 电池白名单的设置路径指引 |

### 1.2 明确不做（YAGNI）

- 不做按应用单独指定方向（每个 App 单独锁）
- 不做 root / 系统签名模式
- 不做快捷设置磁贴（Quick Settings Tile）
- 不做桌面小组件
- 不做悬浮球
- 不做浅色主题（整体设计为深色质感，强制深色，不跟随系统）
- 不做账号、云同步、统计

### 1.3 已知技术限制（必须向用户说明）

安卓应用的屏幕方向由两部分决定：

1. 系统设置里的「自动旋转」开关及其对应的 `USER_ROTATION` 值
2. 每个应用自己在代码里声明写死的方向（`android:screenOrientation` 或运行时 `setRequestedOrientation`）

本软件通过方案 A（见 §2）控制第 1 项，**无法控制第 2 项**。因此：在代码里写死了方向的应用——绝大多数游戏、部分视频与直播应用、部分支付类应用——锁不住。这不是缺陷，是免 root 方案的能力边界。此说明会出现在 App 界面底部和 README 中。

---

## 2. 方向控制机制

### 2.1 方案比选

| 方案 | 原理 | 能否全局生效 | 结论 |
|---|---|---|---|
| **A. 写入系统设置** | 申请「修改系统设置」特殊权限（`WRITE_SETTINGS`），关闭「自动旋转」，把 `Settings.System.USER_ROTATION` 写成目标角度。这是系统级设置，所有跟随系统方向的应用立即跟随 | 能，对绝大多数应用有效 | **采用** |
| B. 悬浮窗 + 强制方向页 | 弹一个透明 Activity，自己声明方向 | 只能影响自己 | 排除：无法影响其他应用 |
| C. root / 系统签名 | 以系统权限直接下指令 | 能，连写死方向的应用也能强制 | 排除：需要 root，不适配主流设备 |

### 2.2 锁定的两个动作

锁定方向必须同时做两件事，缺一不可：

```kotlin
Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)  // 关掉自动旋转
Settings.System.putInt(cr, Settings.System.USER_ROTATION, targetRotation)  // 写入目标角度
```

只写 `USER_ROTATION` 而不关 `ACCELEROMETER_ROTATION` 无效——自动旋转开启时系统忽略 `USER_ROTATION`。

解除锁定（「自动」模式）则把 `ACCELEROMETER_ROTATION` 置回 1。

### 2.3 `USER_ROTATION` 取值

| 值 | 含义 |
|---|---|
| 0 | 设备天然朝向 |
| 1 | 天然朝向顺时针 90° |
| 2 | 天然朝向 180°（反向） |
| 3 | 天然朝向逆时针 90°（270°） |

### 2.4 天然朝向探测

**问题**：手机天然朝向是竖屏，`USER_ROTATION = 0` 即竖屏、`1` 即横屏；但平板天然朝向是横屏，`USER_ROTATION = 0` 反而是横屏、`1` 才是竖屏。不做区分的话，锁出来的方向在平板和折叠屏上是反的。

**做法**：不改写任何系统设置，直接由「当前逻辑宽高 + 当前旋转角」反推，连续两次采样（间隔 300ms）一致才采信。`displayMetrics` 报的是当前旋转后的逻辑宽高：旋转为 0 / 180°（`ROTATION_0` / `ROTATION_180`）时逻辑宽高即天然宽高，旋转为 90° / 270° 时两者互换。判定结果缓存，进程内只算一次。

```kotlin
// 纯函数，便于单元测试
fun naturalOrientationFrom(displayWidthPx: Int, displayHeightPx: Int): NaturalOrientation =
    if (displayHeightPx >= displayWidthPx) NaturalOrientation.PORTRAIT else NaturalOrientation.LANDSCAPE
```

### 2.5 模式到系统值的映射

天然竖屏设备（绝大多数手机）：

| 模式 | `USER_ROTATION` |
|---|---|
| 竖屏 | 0 |
| 横屏 | 1 |
| 反向竖屏 | 2 |
| 反向横屏 | 3 |

天然横屏设备（平板、展开的折叠屏）：

| 模式 | `USER_ROTATION` |
|---|---|
| 横屏 | 0 |
| 竖屏 | 1 |
| 反向横屏 | 2 |
| 反向竖屏 | 3 |

「当前方向」模式：选中瞬间读取 `Display.getRotation()`，直接把该值作为 `USER_ROTATION` 写入，不参与上表映射。
「自动」模式：不写 `USER_ROTATION`，把 `ACCELEROMETER_ROTATION` 置 1。

### 2.6 `targetSdk = 35` 的有意选择

Android 16（API 36）引入了行为变更：**在屏幕宽度 ≥ 600dp 的大屏设备（平板、折叠屏）上，系统会无视应用声明写死的方向**。若 `targetSdk` 设为 36，本软件在平板上的锁定会失效。因此将 `targetSdk` 定为 35，`compileSdk` 为 36。这是经过权衡的决定，不是疏漏。

---

## 3. 权限模型

### 3.1 权限清单

| 权限 | 类型 | 用途 | 授予方式 |
|---|---|---|---|
| `WRITE_SETTINGS` | 特殊权限（Special Access） | 写系统方向设置 | 用户手动授权：`Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))` |
| `RECEIVE_BOOT_COMPLETED` | 普通权限，安装即得 | 开机自启 | 安装时授予 |
| `FOREGROUND_SERVICE` | 普通权限 | 前台服务 | 安装时授予 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 普通权限 | 声明前台服务类型 | 安装时授予 |
| `POST_NOTIFICATIONS` | 运行时权限，Android 13+ | 显示常驻通知 | 应用内请求 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 特殊权限 | 请求退出电池优化白名单 | 用户手动授权 |

### 3.2 前台服务类型

Android 14（API 34）起前台服务必须声明类型。本服务选 `specialUse`，并在 manifest 中按要求声明 `<property>` 说明用途：

```xml
<service
    android:name=".system.OrientationService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="持续维护用户选择的屏幕方向，并通过常驻通知提供快捷切换入口" />
</service>
```

### 3.3 权限引导流程

```
应用启动
  └─ canWriteSettings() ?
       ├─ true  → 正常可用
       └─ false → 界面顶部显示引导卡
                     └─ 用户点「去开启」→ 跳系统特殊权限页
                          └─ onResume 复检 → 已授权则卡片消失、界面立即可用
```

`POST_NOTIFICATIONS`（Android 13+）：在「常驻通知」开关处提示；未授予时通知栏入口不可用，但不影响锁定功能本身。

---

## 4. 守护机制

### 4.1 为什么需要

写完系统设置后，用户仍可能通过下拉状态栏的「自动旋转」快捷开关、其他工具应用、或系统自身行为改动方向值。没有守护，锁一次就白锁了。

### 4.2 实现

**主路径**：`ContentObserver` 监听以下两个 URI，变了才反应，不是空转。

- `Settings.System.getUriFor(Settings.System.USER_ROTATION)`
- `Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION)`

**兜底路径**：每 10 秒做一次轻量比对。用于覆盖 observer 未触发或被杀的情况。

### 4.3 守护判定（纯函数）

```kotlin
fun shouldReapply(current: RotationState, target: OrientationMode): Boolean
```

- 目标模式为 `AUTO` 时，永不重新应用（用户已主动解除）。
- 其余模式下，`current.autoRotate != false` 或 `current.userRotation != expected(target, natural)` 时判定需要重新应用。

### 4.4 「守护模式」开关

关闭后，服务仅承担通知栏职责，不再重写系统设置。开机时仍然恢复一次方向。

---

## 5. 界面设计

### 5.1 布局结构

```
┌─────────────────────────────────┐
│ 屏幕方向锁               ⚙ 设置  │  20sp/700 标题 + 12sp/400 副标题
│ 让每一屏都按你要的方向显示        │
│         ╭───────────────╮       │
│         │ ● 已锁定 · 竖屏 │       │  状态药丸徽标
│         ╰───────────────╯       │
│           ╭─────────╮           │
│           │  ┌───┐  │           │  中央手机示意图（Canvas 手绘）
│           │  │ ▓ │  │           │  锁定时按方向整体弹簧旋转
│           │  │ 竖 │  │           │  描边套用该方向渐变
│           │  └───┘  │           │
│           │  ─────   │           │
│           ╰─────────╯           │
│      所有跟随系统方向的应用        │  12sp 说明
│  ╭──────────╮  ╭──────────╮     │
│  │ ▯ 竖屏    │  │ ▯ 横屏    │     │  2列3行方向卡片
│  ╰──────────╯  ╰──────────╯     │
│  ╭──────────╮  ╭──────────╮     │
│  │ ⇅ 反向竖屏│  │ ⇄ 反向横屏│     │
│  ╰──────────╯  ╰──────────╯     │
│  ╭──────────╮  ╭──────────╮     │
│  │ ◎ 当前方向│  │ ↻ 自动    │     │
│  ╰──────────╯  ╰──────────╯     │
│  ┌───────────────────────────┐  │
│  │ ⚡ 开机自启        [开]───│  │  设置开关卡
│  │    重启后自动恢复上次锁定  │  │
│  │ 🔔 常驻通知        [开]───│  │
│  │    下拉通知栏即可切换方向  │  │
│  │ 🛡 守护模式        [开]───│  │
│  │    方向被改掉时自动改回来  │  │
│  └───────────────────────────┘  │
│  若应用自身写死了方向，           │  底部免责小字
│  本软件无法改变它                │
└─────────────────────────────────┘
```

权限引导卡（未授予 `WRITE_SETTINGS` 时出现，插在状态徽标上方）：

```
┌─────────────────────────────┐
│ ⚠ 需要一项权限               │  琥珀色描边
│ 锁定方向需要开启「修改系统    │
│ 设置」权限          [去开启] │
└─────────────────────────────┘
```

### 5.2 中央手机示意图

界面视觉中心，用 Compose `Canvas` 手绘，不用图片资源（省体积、可无损缩放、颜色随模式变化）：

- 机身：竖长圆角矩形，宽约 130dp、高约 260dp、圆角 40dp
- 机身描边：3dp，`Brush.linearGradient` 套用当前模式的渐变；未锁定时为白色 12% 透明度的单色
- 屏幕内：顶部听筒小胶囊、底部 Home 指示横条、中央一个大号方向字（「竖」/「横」）
- 旋转：整块绕中心旋转，`Animatable` + `spring(dampingRatio = 0.55f)`，带回弹
- 未锁定状态：叠加缓慢的呼吸透明度动画，弱化视觉重量

### 5.3 配色规范

| 用途 | 颜色 |
|---|---|
| 背景 | `#0A0C12 → #131828` 竖向渐变，顶部叠加一层蓝紫径向光晕 |
| 玻璃卡片 | 白色 5% 填充 + 白色 9% 描边，圆角 20dp |
| 文字 主 / 次 / 弱 | `#EDF0F7` / `#9AA3B8` / `#5C6580` |
| 竖屏 | 紫 `#8B5CF6` → 粉 `#EC4899` |
| 横屏 | 青 `#06B6D4` → 蓝 `#3B82F6` |
| 反向两种 | 橙 `#F59E0B` → 红 `#EF4444` |
| 自动 | 石板灰 `#64748B` → `#475569` |
| 已锁定状态 | 绿 `#34D399` |
| 权限引导 | 琥珀 `#F59E0B` |

### 5.4 字体与间距

不用自定义字体文件，跟随系统默认中文字体。字重与字号做层次：

- 标题 20sp / 700
- 正文 14sp / 400
- 辅助 12sp / 400

间距：卡片圆角 20dp，卡片内边距 20dp，网格间距 12dp，屏幕左右边距 16dp，方向卡片高 88dp，图标 28dp。

### 5.5 动效清单

| 场景 | 动效 |
|---|---|
| 切换方向 | 手机示意图弹簧旋转（`spring(dampingRatio = 0.55f)`） |
| 卡片选中 | 交叉淡入 + 缩放至 1.03 |
| 状态文字变化 | `AnimatedContent` 纵向滑入 |
| 权限卡出现/消失 | `AnimatedVisibility` 滑入 + 淡入 |
| 未锁定待机 | 手机示意缓慢呼吸透明度 |

### 5.6 主题策略

**强制深色**，`darkColorScheme` 手写，不读 `isSystemInDarkTheme()`。理由：整体设计为深色质感，浅色下视觉重点（渐变、光晕、玻璃卡）会全部失效。

---

## 6. 通知栏设计

渠道「方向锁定」，重要性 `IMPORTANCE_LOW`（无声、不浮动、常驻）。

```
屏幕方向锁
竖屏锁定中
点按打开 · 用下方按钮快速切换
[竖屏] [横屏] [反向] [解除]
```

- 四个 action 直接生效，无需打开 App
- 「反向」= 当前已锁方向的反向（竖屏 ↔ 反向竖屏，横屏 ↔ 反向横屏）；当前未锁定时按反向竖屏处理
- 「解除」= 切回「自动」模式，跟随传感器
- `setOngoing(true)` 不可划掉
- 点击通知主体 → `PendingIntent` 打开 `MainActivity`
- 图标随模式变色，锁定时彩色，未锁定灰色
- Android 13+ 需 `POST_NOTIFICATIONS` 运行时权限，未授予时此入口不可用

---

## 7. 代码结构

单模块 `:app`，按职责分层。`domain` 层为纯 Kotlin，零安卓依赖，可在 JVM 上直接单测。

```
app/src/main/java/com/orientlock/
├── OrientLockApp.kt                   Application：建通知渠道、依赖容器
├── MainActivity.kt                    单 Activity，setContent { AppRoot() }
│
├── domain/
│   ├── OrientationMode.kt             枚举：6 个模式 × 中文标签 / 图标 / 渐变 / 系统值
│   ├── NaturalOrientation.kt          天然朝向枚举与纯函数判定
│   └── OrientationGuard.kt            守护判定纯函数
│
├── data/
│   ├── SystemOrientationWriter.kt     封装 Settings.System 读写、权限检查、朝向探测
│   └── SettingsOrientationRepository.kt  DataStore 持久化 + 对外 StateFlow
│
├── system/
│   ├── OrientationService.kt          前台服务：常驻通知 + 守护
│   ├── BootReceiver.kt                开机 / 应用更新后拉起
│   ├── NotificationHelper.kt          通知渠道与通知构建
│   └── PermissionGate.kt              权限检查与跳转 Intent
│
├── ui/
│   ├── theme/                         Color.kt / Type.kt / Theme.kt
│   ├── MainViewModel.kt               UiState 与用户意图
│   ├── components/                    PhonePreview / ModeCard / StatusPill /
│   │                                  SettingRow / PermissionBanner
│   └── MainScreen.kt                  主界面布局
│
└── test/                              JVM 单元测试
```

### 7.1 数据流（单向）

```
主界面点方向 ──► MainViewModel ──► Repository.setMode()
                                        ├─ 写 DataStore（记住选择）
                                        └─ 写 Settings.System（立即生效）
                                                  │
                                                  ▼
                                OrientationService（前台常驻）
                                        ├─ observe 状态 → 刷新通知栏四按钮
                                        └─ 守护：监听到系统值被改 → 重新写入

通知栏点按钮 ──► Service ──► Repository ──► ViewModel 自动同步
```

Repository 是唯一数据源，持有 `StateFlow<OrientationState>`；Service 与 ViewModel 都只订阅它，不互相通信。

### 7.2 依赖管理

不用 Hilt。`OrientLockApp` 里持一个简单依赖容器（object / 手动构造），按需传给 Service 与 ViewModel。体量小，引入 KSP 与注解处理反而增加构建失败面。

### 7.3 开机链路

```
BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / 国产 ROM 的 QUICKBOOT_POWERON
   └─ 「开机自启」开关关闭 → 不动作
   └─ 开启 → 启动前台服务
        ├─ 成功 → 恢复上次锁定方向，通知栏上线
        └─ 被系统拒绝（ForegroundServiceStartNotAllowedException，
              Android 15 对开机启动前台服务有限制）
             → 退化为 WorkManager OneTimeWork 立即执行，由 Worker 再拉起服务

MY_PACKAGE_REPLACED → 同样检查开关并重启服务（应用更新后保持常驻）
```

### 7.4 国产 ROM 适配

小米（MIUI）/ 华为（EMUI）/ OPPO（ColorOS）/ vivo（OriginOS）的「自启动管理」「后台弹窗」「电池白名单」会杀掉后台服务。设置页放一张**系统适配卡**，列出各 ROM 的开启路径，并提供跳转按钮：

- 通用：`ACTION_APPLICATION_DETAILS_SETTINGS`
- 分 ROM 的 `Intent` 尽力跳转，解析失败回落通用页

---

## 8. 工程配置

| 项 | 值 |
|---|---|
| Gradle | 8.12（本机 `~/.gradle/wrapper/dists` 已缓存，免下载） |
| Android Gradle Plugin | 8.9.3 |
| Kotlin | 2.1.0 + `org.jetbrains.kotlin.plugin.compose` 编译器插件 |
| Compose BOM | 2025.01.00 |
| compileSdk / minSdk / targetSdk | 36 / 26 / 35 |
| JVM target | 17 |
| 执行 JDK | Android Studio 自带 JBR 21（`org.gradle.java.home` 指定），避开系统默认 Java 26 与 AGP 不兼容 |
| versionCode / versionName | 1 / 1.0 |
| 仓库 | google() + mavenCentral() |

**为什么不用版本目录文件之外的新版依赖**：本机 Gradle 缓存与网络均可用，但选型时优先使用已验证可获取的版本，降低首次构建失败概率。

---

## 9. 测试策略

`domain` 层全部为纯 Kotlin，JVM 单元测试直接覆盖：

| 测试 | 覆盖内容 |
|---|---|
| `OrientationModeTest` | 6 个模式在天然竖屏 / 天然横屏设备下的 `USER_ROTATION` 映射 |
| `NaturalOrientationTest` | 朝向探测纯函数（宽高比较的边界） |
| `OrientationGuardTest` | 守护判定：该不该重新写入 |
| `MainViewModelTest` | 选方向、切换开关、权限未授予时的行为 |
| `OrientationNotificationContentTest` | 通知标题与文案随模式变化 |

执行：`./gradlew test`

### 9.1 验收边界（诚实声明）

本机无连接的安卓设备，可完成的验证：

- ✅ Gradle 配置解析通过
- ✅ 编译通过（`assembleDebug`）
- ✅ 单元测试全绿（`test`）
- ✅ Lint 无 error
- ✅ 产出可安装 APK
- ❌ 真机运行验证（无设备）

因此交付物中附**真机自测清单**，逐条覆盖：权限授予、锁定生效、反向方向、通知栏四按钮、守护恢复、开机自启、国产 ROM 白名单、平板方向映射。

---

## 10. 交付物

1. 完整 Android Studio 工程（`E:\APP\2026-9-25`，可直接打开）
2. `app/build/outputs/apk/debug/app-debug.apk`（可安装）
3. 中文 `README.md`：功能、权限说明、构建方法、分发说明
4. `docs/真机自测清单.md`：逐条可勾选
5. release 构建配置（签名需用户提供 keystore，工程内留位与说明）

---

## 11. 实现顺序

1. 工程骨架：Gradle 配置、manifest、主题、包结构
2. `domain` 层：`OrientationMode`、`NaturalOrientation`、`OrientationGuard`
3. `domain` 层单元测试（先红后绿）
4. `data` 层：`SystemOrientationWriter`、`SettingsOrientationRepository`
5. `system` 层：`NotificationHelper`、`OrientationService`、`BootReceiver`、`PermissionGate`
6. `ui` 层：主题与配色、`PhonePreview` 等组件、`MainScreen`、`MainViewModel`
7. 剩余单元测试
8. 编译、测试、Lint、产出 APK
9. README 与真机自测清单
