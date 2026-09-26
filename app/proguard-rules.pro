# manifest 中引用的广播接收器。ProGuard 通过 manifest 已能保住它，显式声明更稳，
# 也避免以后有人调整 R8 规则时被误删——开机自启全靠它。
-keep class com.orientlock.system.BootReceiver { *; }

# DataStore 的 Preferences.Key 子类带泛型签名，R8 默认规则未覆盖
-keep class * extends androidx.datastore.preferences.core.Preferences$Key { *; }

# Compose 与 Kotlin 协程规则由各自依赖自带；这两条只消掉注解库的无关告警，
# 不掩盖任何真实缺失。
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**
