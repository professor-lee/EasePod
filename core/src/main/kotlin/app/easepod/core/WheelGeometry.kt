package app.easepod.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

enum class WheelKey { MENU, PREVIOUS, NEXT, PLAY, CENTER }

object WheelGeometry {
    fun angle(x: Float, y: Float): Float = Math.toDegrees(atan2(y, x).toDouble()).toFloat()
    fun delta(previous: Float, current: Float): Float {
        var delta = current - previous
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return delta
    }
    fun key(x: Float, y: Float, radius: Float): WheelKey? {
        val distance = hypot(x, y)
        if (distance > radius) return null
        if (distance < radius * .36f) return WheelKey.CENTER
        return if (abs(x) > abs(y)) {
            if (x < 0) WheelKey.PREVIOUS else WheelKey.NEXT
        } else if (y < 0) WheelKey.MENU else WheelKey.PLAY
    }
    fun stepAngle(sensitivity: Int): Float = when (sensitivity) { 1 -> 24f; 3 -> 12f; else -> 18f }
}
