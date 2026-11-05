package com.lukesteuber.signalstation

import kotlin.math.abs
import kotlin.math.ceil

internal data class SignalCoverCutout(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun signalCoverBottomInset(manufacturer: String, model: String, width: Int, height: Int,
    cutouts: List<SignalCoverCutout>): Int {
    if (width <= 0 || height <= 0) return 0
    val visible = cutouts.filter { it.left < width && it.right > 0 && it.top < height && it.bottom > 0 }
    val reported = visible.filter { it.bottom >= height }.maxOfOrNull { height - it.top.coerceAtLeast(0) }
    if (reported != null) return reported.coerceIn(0, height)
    val knownFlip = manufacturer.equals("samsung", ignoreCase = true) &&
        (model.startsWith("SM-F766", ignoreCase = true) || model.startsWith("SM-F776", ignoreCase = true))
    val coverAspect = 948.0 / 1048.0
    val fullCover = width < height && abs(width.toDouble() / height - coverAspect) / coverAspect <= 0.03
    // The full-panel One UI 9 frame can omit the bottom-right cameras from DisplayCutout.
    return if (knownFlip && fullCover && cutouts.isEmpty()) ceil(height * (209.0 / 1048.0)).toInt() else 0
}
