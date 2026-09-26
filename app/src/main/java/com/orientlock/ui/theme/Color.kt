package com.orientlock.ui.theme

import androidx.compose.ui.graphics.Color

// 背景
val BgTop = Color(0xFF0A0C12)
val BgBottom = Color(0xFF131828)
val GlowPurple = Color(0x223B2F7A)

// 玻璃卡片：5% 白填充 + 9% 白描边
//
// 注意：GlassFill 也被填进了 Theme.kt 的 `surface` 槽位。Material3 的
// surfaceContainer 阶梯（surfaceContainerLow / Container / ContainerHigh …）
// 仍是 M3 默认的实心底色——Card、TopAppBar、ModalBottomSheet、AlertDialog、
// Menu 这些容器组件读的是那一组，不是 surface。所以**本应用不要用 M3 的
// Surface / Card / Scaffold**，一律用 Box + clip/background/border 手写，
// 否则玻璃面会变成实心灰盒子。目前的界面布局遵守这一点。
val GlassFill = Color(0x0DFFFFFF)
val GlassBorder = Color(0x17FFFFFF)

// 文字 主 / 次 / 弱
val TextPrimary = Color(0xFFEDF0F7)
val TextSecondary = Color(0xFF9AA3B8)
val TextTertiary = Color(0xFF5C6580)

// 模式渐变：起点 → 终点
val PortraitStart = Color(0xFF8B5CF6)
val PortraitEnd = Color(0xFFEC4899)
val LandscapeStart = Color(0xFF06B6D4)
val LandscapeEnd = Color(0xFF3B82F6)
val ReverseStart = Color(0xFFF59E0B)
val ReverseEnd = Color(0xFFEF4444)
val AutoStart = Color(0xFF64748B)
val AutoEnd = Color(0xFF475569)

// 状态色
//
// WarningAmber 与 ReverseStart 同值 #F59E0B 是刻意的：琥珀色既是权限引导卡的
// 强调色，也是「反向」渐变的起点，视觉上同源。改其中一个记得同步另一个。
val LockedGreen = Color(0xFF34D399)
val WarningAmber = Color(0xFFF59E0B)
