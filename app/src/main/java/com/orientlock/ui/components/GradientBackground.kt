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
