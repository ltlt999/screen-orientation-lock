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
import com.orientlock.ui.theme.modeGradient

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
    val gradient = mode.modeGradient

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

internal fun modeIconRes(mode: OrientationMode): Int = when (mode) {
    OrientationMode.PORTRAIT -> R.drawable.ic_mode_portrait
    OrientationMode.PORTRAIT_REVERSE -> R.drawable.ic_mode_portrait_reverse
    OrientationMode.LANDSCAPE -> R.drawable.ic_mode_landscape
    OrientationMode.LANDSCAPE_REVERSE -> R.drawable.ic_mode_landscape_reverse
    OrientationMode.CURRENT -> R.drawable.ic_mode_current
    OrientationMode.AUTO -> R.drawable.ic_mode_auto
}
