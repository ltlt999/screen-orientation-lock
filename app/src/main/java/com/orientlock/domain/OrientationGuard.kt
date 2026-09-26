package com.orientlock.domain

/** 从 Settings.System 读到的方向状态快照 */
data class RotationState(val userRotation: Int, val autoRotate: Boolean)

/**
 * 判断当前系统方向状态是否偏离了用户选择的目标，需要重新写入。
 *
 * 由 OrientationService 的守护逻辑调用。规则：
 * - 目标为 [OrientationMode.AUTO] 时永不重新应用——用户已主动解除锁定，
 *   此时再写回去会和用户的意图对抗。
 * - 目标为 [OrientationMode.CURRENT] 时，只要自动旋转是关的就算达成，
 *   因为该模式的角度在选中瞬间就已固定，不参与后续比对。
 * - 其余模式下，自动旋转被重新打开，或系统角度不等于目标角度，均判定需重新应用。
 */
fun shouldReapply(
    current: RotationState,
    target: OrientationMode,
    natural: NaturalOrientation,
): Boolean {
    if (target == OrientationMode.AUTO) return false
    if (target == OrientationMode.CURRENT) return current.autoRotate
    if (current.autoRotate) return true
    return current.userRotation != target.userRotationFor(natural)
}
