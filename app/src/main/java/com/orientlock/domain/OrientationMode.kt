package com.orientlock.domain

/**
 * 屏幕方向锁定模式。
 *
 * @property storageName 持久化到 DataStore 的名称。改动会导致旧设置失效，勿随意改。
 * @property label 界面上显示的中文名称。
 */
enum class OrientationMode(val storageName: String, val label: String) {
    /** 自然竖屏 */
    PORTRAIT("portrait", "竖屏"),

    /** 竖屏但上下颠倒 */
    PORTRAIT_REVERSE("portrait_reverse", "反向竖屏"),

    /** 自然横屏（设备左转 90°） */
    LANDSCAPE("landscape", "横屏"),

    /** 横屏但左右颠倒 */
    LANDSCAPE_REVERSE("landscape_reverse", "反向横屏"),

    /** 锁定为选中瞬间设备所处的方向，角度在选中时抓取 */
    CURRENT("current", "当前方向"),

    /** 不锁定，跟随传感器 */
    AUTO("auto", "自动"),

    ;

    /**
     * 该模式对应的 Settings.System.USER_ROTATION 取值。
     *
     * @param natural 设备的天然朝向
     * @return 0..3 的系统角度值；[AUTO] 与 [CURRENT] 返回 null。
     *   [AUTO] 不写角度，只恢复自动旋转开关；
     *   [CURRENT] 的角度在选中瞬间从 Display.getRotation() 抓取，不在此处推导。
     */
    fun userRotationFor(natural: NaturalOrientation): Int? = when (this) {
        AUTO -> null
        CURRENT -> null
        PORTRAIT -> if (natural == NaturalOrientation.PORTRAIT) 0 else 1
        LANDSCAPE -> if (natural == NaturalOrientation.PORTRAIT) 1 else 0
        PORTRAIT_REVERSE -> if (natural == NaturalOrientation.PORTRAIT) 2 else 3
        LANDSCAPE_REVERSE -> if (natural == NaturalOrientation.PORTRAIT) 3 else 2
    }

    /**
     * 反向模式：竖屏 ↔ 反向竖屏，横屏 ↔ 反向横屏。
     * [CURRENT] 与 [AUTO] 没有固定反向，返回自身。
     */
    fun reversed(): OrientationMode = when (this) {
        PORTRAIT -> PORTRAIT_REVERSE
        PORTRAIT_REVERSE -> PORTRAIT
        LANDSCAPE -> LANDSCAPE_REVERSE
        LANDSCAPE_REVERSE -> LANDSCAPE
        CURRENT, AUTO -> this
    }

    companion object {
        /** 按持久化名称查找；未知或空名称回落到 [AUTO]，保证升级不崩 */
        fun fromStorageName(name: String?): OrientationMode =
            entries.firstOrNull { it.storageName == name } ?: AUTO
    }
}
