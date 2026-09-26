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
