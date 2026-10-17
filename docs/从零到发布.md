# 从零到发布：完整记录与踩坑清单

这份文档记录把「屏幕方向锁」从空目录做到 GitHub 开源发布的全过程，重点在**那些编译通过、单元测试全绿、却在真机上出问题的地方**——它们是这个项目里最贵的部分。

如果你只是想 clone 下来用，读 [README](../README.md) 就够了。如果你想自己做一个类似的安卓工具，或者维护/二次开发本项目，这篇值得看。

---

## 目录

- [一、核心技术：为什么要用悬浮窗](#一核心技术为什么要用悬浮窗)
- [二、十个真机上才会暴露的坑](#二十个真机上才会暴露的坑)
- [三、我自己的三个低级错误](#三我自己的三个低级错误)
- [四、有用的验证手段](#四有用的验证手段)
- [五、发布流程](#五发布流程)

---

## 一、核心技术：为什么要用悬浮窗

### 安卓的方向优先级

安卓的规则很简单，但很多人不知道：

```
应用自己声明的方向  >  全局系统设置
```

所以只写 `Settings.System` 的方向锁，**对声明了方向的应用完全无效**。`ACCELEROMETER_ROTATION` 的官方文档写得很直白：

> If 0, it will not be used **unless explicitly requested by the application**

### 那批锁不住的应用是什么

把氢桌面的 APK 拉下来解包，一个字段就说明问题：

```xml
android:screenOrientation="6"
```

`6 = SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，即「跟随传感器，但只允许横屏」。它会在横屏左/横屏右之间翻转，任何全局设置都压不住它。

车机桌面、部分视频/音乐应用、部分游戏都属于这一类。

### 悬浮窗为什么能压过去

加一个 0×0 的 `TYPE_APPLICATION_OVERLAY` 窗口，在它的 `LayoutParams.screenOrientation` 上声明方向。悬浮窗在窗口层级上高于普通应用窗口，WindowManager 计算显示旋转时会把它的请求算进去。

实测（`dumpsys window windows` 能看到窗口真实存在）：

```
Window #4 Window{c08608d u0 com.orientlock}:
    mAttrs={(0,0)(0x0) ... ty=APPLICATION_OVERLAY fmt=TRANSLUCENT or=SCREEN_ORIENTATION_PORTRAIT
```

**只需要「显示在其他应用上层」这一个普通权限，不需要 root，也不需要 Shizuku。**

> 这个技术不是我发明的，是开源项目 [paditianxiu/orientation-faker](https://github.com/paditianxiu/orientation-faker) 的思路。它的 README 声称能做到，我实测验证了确实能，然后自己实现了一遍。

---

## 二、十个真机上才会暴露的坑

### 1. `Context.getDisplay()` 在 Android 11+ 必崩

`Context.getDisplay()` 是 API 30 引入的，SDK 文档写明：

```java
/**
 * @throws UnsupportedOperationException if the method is called on an instance that is
 *         not associated with any display.
 */
```

而这个类只在 `Application` / `Service` 上下文里被构造——**那正是「不关联任何 display」**。

编译通过、lint 通过、单元测试全绿。只在真机上第一次点方向时闪退。

正确写法：

```kotlin
(context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
    ?.getDisplay(Display.DEFAULT_DISPLAY)
    ?.rotation
```

注意用 `as?` 安全转换——某些 ROM 的 `getSystemService` 返回值不同，硬转会把 `UnsupportedOperationException` 换成 `ClassCastException`，没解决问题。

### 2. `Settings.System.putInt` 没权限时**抛异常**，不是静默失败

我最初读 SDK 文档里 `@return true if the value was set, false on database errors`，认定它静默失败返回 false，于是整个错误处理都按"检查返回值"来设计。

**实测打脸**：

```
java.lang.SecurityException: com.orientlock was not granted this permission: android.permission.WRITE_SETTINGS.
	at android.provider.Settings$System.putInt(Settings.java:3677)
	at ...setMode(...)
	at kotlinx.coroutines.AbstractCoroutine.resumeWith(...)
```

异常一路冒到 ViewModel 的协程外 → **应用闪退，服务根本没启动**。而悬浮窗那条通路本来是好的，被它拖死。

> **教训**：文档里的 `@return` 只覆盖「数据库错误」，不覆盖「权限被拒」。权限检查走的是另一条 `AppOpsManager` 路径，失败形态完全不同。

### 3. SVG 圆弧的端点顺序不能反

给电源图标画圆弧时我写成 `M左端点 A ... 右端点`，构建成功、aapt2 通过、XML 校验通过——**但 aapt2 只校验 XML 合法性，从不计算 `pathData`**。

实际渲染是坏的。原因是 SVG 端点→圆心的换算中，圆心修正系数的符号由 `large-arc == sweep` 决定：

- `M6.343,6.343 A8,8 0 1 1 17.657,6.343` → 圆心落在 (12, **0.686**)，弧顶到 y = −7.314，**63% 的圆弧画到视口外被裁掉**
- 换成 `M17.657,6.343 A8,8 0 1 1 6.343,6.343` → 圆心 (12,12)，顶部留出 90° 断口，正确

同理还有一处：状态栏小图标里两个子路径用 `fill` 且绕向相同，默认 `nonZero` 环绕下内部的竖条**永远看不见**，是一行死代码——必须 `android:fillType="evenOdd"`。

> **教训**：矢量图标没有任何自动化验证。只能用 SVG 弧的换算公式手算，或者肉眼看渲染结果。

### 4. `gradlew` 的可执行位会在 Windows 提交时丢失

```
[FAIL] 跑单元测试
  Process completed with exit code 126.
```

126 = Permission denied。Windows 上提交的文件模式是 `100644`，`./gradlew` 在 Linux runner 上直接跑不了。

```bash
git update-index --chmod=+x gradlew
```

### 5. 不能在 `if:` 条件里引用 `secrets`

```yaml
if: ${{ secrets.KEYSTORE_BASE64 != '' }}   # ❌ 整个 workflow 解析失败
```

症状极其隐蔽：**每次 push 都冒出一个以文件路径为名字的失败运行**（`.github/workflows/release.yml`），而不是告诉你 YAML 有问题。而且本地 YAML 解析器判定完全合法。

正确写法是用 `env` 中转：

```yaml
- name: 还原签名密钥
  env:
    KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
  run: |
    if [ -z "$KEYSTORE_BASE64" ]; then echo "signed=false" >> "$GITHUB_OUTPUT"; exit 0; fi
    ...
```

### 6. CI 是 Linux，但 `gradle.properties` 里写死了 Windows 的 JDK 路径

```properties
org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
```

这条是为了绕开开发机默认 JDK 是 Java 26（与 AGP 8.9.3 不兼容）而加的。CI 上这个路径不存在。

workflow 里加一步：

```yaml
- run: sed -i '/^org\.gradle\.java\.home/d' gradle.properties
```

> 顺带一提：Gradle **只**从 `gradle.properties` 或 `JAVA_HOME` 读这个属性。写进 `local.properties` 完全无效——我一开始被一个审查者的建议误导过。

### 7. AGP 默认只签 v2，包在国产平板上装不上

联想小新 Pad Plus 2023 报「应用未安装：软件包似乎无效」。

```
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme:                true
Verified using v3 scheme:                false
```

AGP 在 `minSdk >= 24` 时默认不签 v1，而部分国产 ROM 的安装器仍走 v1 校验路径。

```kotlin
enableV1Signing = true
enableV2Signing = true
enableV3Signing = true
```

包体积 +38.5 KB（就是 v3 签名块）。

> **注意 `apksigner verify` 的报告有坑**：三个方案全开时它优先报 v2，就不再单独列 v1，容易误判成"v1 没签上"。要确认 v1 得直接看 `META-INF/CERT.RSA` 是否存在，或用 `jarsigner -verify` 独立验证。

### 8. 守护标志设在协程挂起之后 → observer 注册两遍

```kotlin
private fun startGuardIfNeeded() {
    if (guardRunning) return          // ← 这里检查
    scope.launch {
        val snap = repository.snapshot()   // ← 挂起点
        guardRunning = true                // ← 却在这里才置位
        contentResolver.registerContentObserver(...)   // 注册两遍
        handler.post(heartbeat)                        // 心跳投递两条
    }
}
```

两次快速 `onStartCommand` 会双双通过检查，结果是**observer 注册两遍、心跳每 10 秒跑两次，永久如此**。

修法是**在 launch 之前同步占位**：

```kotlin
private val guardRequested = AtomicBoolean(false)

if (!guardRequested.compareAndSet(false, true)) return
```

### 9. 通知会在应用「撒谎」

守护心跳只调 `guardTick()`、从不 `refreshNotification()`。权限被收回后系统写入静默失败，应用实际没锁，但通知继续显示「竖屏锁定中」。

**用户会据此判断"软件坏了"，而真正原因是权限没了。** 这个缺陷在排查时实打实误导过我一次——我以为锁生效了，其实没有。

修法：心跳跑完刷新通知；并且按 `Triple(mode, 通知权限, 锁是否真的可用)` 去重，避免每 10 秒重复 post。

### 10. 「只读」探测里写了系统设置

最初探测设备天然朝向的做法是：先把 `USER_ROTATION` 置 0（配合关掉自动旋转），等设备摆正，再读宽高。

三个问题：

1. 留下"自动旋转被悄悄关掉"的副作用
2. 旋转重构是**异步**的，紧接着读到的可能还是旧方向的宽高
3. 这个结果会被持久化，**判错就永久错**

改成纯计算：`displayMetrics` 报的是当前旋转后的逻辑宽高，旋转为 0/180° 时它就是天然宽高，90°/270° 时两者互换。

```kotlin
val naturalIsPortrait = when (rotation) {
    NATURAL, HALF -> heightPx >= widthPx
    QUARTER, THREE_QUARTER -> widthPx >= heightPx
    else -> heightPx >= widthPx
}
```

顺带还修掉一个连带 bug：这个探测在 `setMode(CURRENT)` 之前跑，会把用户此刻的方向强制摆正再抓角度，导致「当前方向」模式**永远锁成竖屏**，而且那个错误角度会被持久化。

---

## 三、我自己的三个低级错误

这三个不是安卓的问题，纯粹是我犯的蠢，但每次都浪费不少时间。

### 1. `userRotationFor` 的 import 写错了三次

它在 `OrientationMode` 里是**枚举成员**：

```kotlin
enum class OrientationMode {
    fun userRotationFor(natural: NaturalOrientation): Int? = ...
}
```

不是顶层扩展函数。所以 `import com.orientlock.domain.userRotationFor` 解析不了。

而我在早期的"接口契约"文档里把它写成了顶层函数，后面三次照着抄，全是编译错误。

> **教训**：跨任务复用代码时，先读一眼实际文件再写 import，别照着自己早先的笔记抄。

### 2. 跑了单测就以为包是最新的

改完代码后我跑了 `testDebugUnitTest`，看到全绿就装到模拟器上——然后发现"修复没生效"。

实际上 `testDebugUnitTest` **不重新打包 APK**，我装的是旧包。

**多绕了一大轮才想通。** 改完代码要装到设备上，必须显式跑 `assembleDebug`。

### 3. 先下结论再验证

关于 `putInt` 的失败形态、关于悬浮窗能不能压过应用声明的方向——我都是在没实测的情况下先写进了代码注释和文档。

前者错了（见坑 2），后者对了但当时并不知道。这个习惯在"注释里写'不会抛异常'"这种地方代价尤其大：**注释会变成下一代人的错误依据**。

---

## 四、有用的验证手段

### 变异测试：人工植入 bug，看测试能否发现

这是投入产出比最高的手段。做法很土但有效：手动改坏一处代码，跑测试，看指定的测试会不会红。

本项目前前后后植入过二十多个变异。抓到的关键几个：

| 植入的 bug | 结果 |
|---|---|
| 删掉 `if (first == second)` 缓存守卫 | **13 个测试全过** —— 双采样的核心不变量没人守 |
| 删掉 `settle(300ms)` 等待 | 全过 —— "间隔 300ms"这条硬性要求没测试覆盖 |
| `canWrite()` 硬编码 `false` | 全过 —— 只测了 `false` 分支 |
| CURRENT 分支先写 `auto:off` 再校验角度 | 测试红 —— 非法角度会留下"没锁成、自动旋转反而没了"的中间态 |
| observer 注册去重护栏 | 测试红 |

补完测试后再植入一遍，三个存活的才全部死掉。

> **技巧**：写完测试后，挨个把生产代码里"如果删掉这行会怎样"的地方临时删掉，看测试是否变红。变红说明测试有承重作用；不变红说明它只是摆设。

### 代理测试（fake）替代 Robolectric

关键决策：`domain` 层不 import 任何 `android.*`，把 `Settings.System` 的读写关进一个接口，ViewModel/Repository 都只依赖接口。

结果：**88 个测试全部跑在 JVM 上**，不需要模拟器、不需要 Robolectric，构建快几十倍。

`FakeDataStore` 只需十几行：

```kotlin
class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
```

### 真机/模拟器实测

编译和单测都过不了关的东西，只有实测能抓。对应上面十个坑里的 1、2、3、6、7、9。

最有价值的一次是**对照实验**：

| 操作 | 屏幕 |
|---|---|
| 锁竖屏，氢桌面前台，盯 20 秒 | 稳在竖屏 |
| 杀掉应用撤下悬浮窗 | 氢桌面**立刻抢回横屏** |

最后那步排除了"碰巧"的可能。如果只做前一步，看到"稳在竖屏"仍然无法排除是模拟器不支持旋转这类巧合。

### 读 AOSP 源码而不是猜

`$ANDROID_HOME/sources/android-36.1/` 里有完整的公开 API 源码。几个关键结论都是从这里直接读出来的：

- `ACCELEROMETER_ROTATION` 的"unless explicitly requested by the application"
- `SCREEN_ORIENTATION_SENSOR_LANDSCAPE = 6` 的定义
- `Context.getDisplay()` 的 `@throws`
- `WindowContainerTransaction.setIgnoreOrientationRequest` 是 `@hide`，且文档明写它"只作用于 DisplayArea"——这是判定"没有免 root 出路"的依据

比在搜索引擎里翻博客可靠得多。

### 把 APK 拉下来解包看真实声明

想知道某个应用为什么锁不住，直接看它的 manifest：

```bash
aapt2 dump xmltree --file AndroidManifest.xml app.apk | grep -iE "E: activity|screenOrientation"
```

比猜"它是不是 sensorLandscape"快且准。

---

## 五、发布流程

### 打 tag 自动发布

仓库配了两个 workflow：

| 文件 | 触发 | 做什么 |
|---|---|---|
| `build.yml` | push 到 main / PR / 手动 | 跑单测 + 出 debug 包 |
| `release.yml` | 打 `v*` tag / 手动 | 出签名包 + 创建 Release |

签名密钥从 Actions Secrets 读（`KEYSTORE_BASE64`、`KEYSTORE_PROPERTIES`）。**没配也能构建**，只是出未签名包——开源项目不该因为缺密钥就构建失败。

本地发布则是：

```bash
./gradlew assembleRelease     # 有 keystore.properties 就签名，没有就 -unsigned
```

### 签名密钥必须备份

```
keystore/release.jks
keystore.properties
```

这两个文件**故意不入库**（别人不该拿到你的签名密钥）。

但**它们一旦丢失，就再也无法发布能覆盖安装的更新版本**——用户装新版会因为签名不一致而失败，只能卸载重装，DataStore 里的设置也一并丢失。

请把它们复制到密码管理器或离线备份。

### 推代码用一次性 URL，不把凭据写进 git 配置

```bash
git push "https://x-access-token:$TOKEN@github.com/owner/repo.git" main
git remote add origin https://github.com/owner/repo.git    # 干净的 URL
```

这样 `.git/config` 里不会留下 token。

---

## 附：项目最终状态

- 88 个单元测试，全绿，0 个 Lint error
- release APK 1.73 MB（含 v1+v2+v3 三种签名）
- minSdk 26 / targetSdk 35 / compileSdk 36
- MIT 协议
