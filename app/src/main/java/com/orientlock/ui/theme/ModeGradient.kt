package com.orientlock.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.orientlock.domain.OrientationMode

/**
 * 模式 → 渐变。全应用唯一的一处，Canvas 手机示意图与方向卡片都用它。
 *
 * 配色语义见设计文档 §5.3：竖屏紫粉、横屏青蓝、**两种反向一律橙红**、
 * 当前方向归到紫粉、自动石板灰。反向单独用色是为了让「翻个面」在画面上看得见，
 * 靠旋转角度区分已经不够——180° 和 270° 在小图上很难一眼分辨。
 */
val OrientationMode.modeGradient: List<Color>
    get() = when (this) {
        OrientationMode.PORTRAIT -> listOf(PortraitStart, PortraitEnd)
        OrientationMode.PORTRAIT_REVERSE -> listOf(ReverseStart, ReverseEnd)
        OrientationMode.LANDSCAPE -> listOf(LandscapeStart, LandscapeEnd)
        OrientationMode.LANDSCAPE_REVERSE -> listOf(ReverseStart, ReverseEnd)
        OrientationMode.CURRENT -> listOf(PortraitStart, PortraitEnd)
        OrientationMode.AUTO -> listOf(AutoStart, AutoEnd)
    }

/** 渐变的中点色，供 PhonePreview 做屏幕内填充 */
val List<Color>.midColor: Color
    get() = if (size >= 2) lerp(first(), last(), 0.5f) else first()

/** 列表的线性渐变画笔，起点左上、终点右下 */
val List<Color>.diagonalBrush: Brush
    get() = Brush.linearGradient(this)
