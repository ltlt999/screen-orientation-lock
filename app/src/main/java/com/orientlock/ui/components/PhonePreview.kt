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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.orientlock.domain.NaturalOrientation
import com.orientlock.domain.OrientationMode
import com.orientlock.ui.theme.GlassBorder
import com.orientlock.ui.theme.midColor
import com.orientlock.ui.theme.modeGradient

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
    pinnedRotationDegrees: Int = 0,
) {
    // CURRENT 的角度在选中瞬间就定下来了，存在 AppSettings.pinnedRotation 里；
    // 不接这个参数，用户在横屏状态下点「当前方向」，设备锁横而示意图仍立着。
    val targetDegrees = when (mode) {
        OrientationMode.CURRENT -> pinnedRotationDegrees * 90f
        else -> (mode.userRotationFor(natural) ?: 0) * 90f
    }
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(targetDegrees) {
        // 按最短路径旋转。 Animatable 里存的是累计角度，直接 animateTo(绝对目标) 的话，
        // 从 270°（反向横屏）切到 0°（竖屏）会倒退四分之三圈——用户看着是转了一大圈
        // 才停。这里先折算目标到离当前最近的一圈内，只走最小转角。
        val currentMod = ((rotation.value % 360f) + 360f) % 360f
        var delta = (targetDegrees - currentMod) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f

        rotation.animateTo(
            targetValue = rotation.value + delta,
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

private fun DrawScope.drawPhone(
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

    val gradient = mode.modeGradient
    val start = gradient.first()
    val end = gradient.last()
    val borderColor = if (locked) start else GlassBorder

    // 机身描边
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(left, top),
        size = Size(phoneWidth, phoneHeight),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
        alpha = alpha,
    )
    // 屏幕内填充：取渐变中点色，透明度压低
    drawRoundRect(
        color = gradient.midColor.copy(alpha = 0.12f * alpha),
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
    // 中心方向字。必须用 Compose 的 drawText——它会跟着外层 rotate 变换，
    // 而 DrawScope.canvas.nativeCanvas 不会，那样文字会与机身错位。
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

private fun modeGlyph(mode: OrientationMode): String = when (mode) {
    OrientationMode.PORTRAIT, OrientationMode.PORTRAIT_REVERSE -> "竖"
    OrientationMode.LANDSCAPE, OrientationMode.LANDSCAPE_REVERSE -> "横"
    OrientationMode.CURRENT -> "当"
    OrientationMode.AUTO -> "自"
}
