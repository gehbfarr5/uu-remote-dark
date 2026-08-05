package io.github.uuremotedark.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object ColorMath {
    fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
        }

        return 0.2126 * channel(color ushr 16 and 0xFF) +
            0.7152 * channel(color ushr 8 and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    fun contrastRatio(first: Int, second: Int): Double {
        val high = max(luminance(first), luminance(second))
        val low = min(luminance(first), luminance(second))
        return (high + 0.05) / (low + 0.05)
    }

    fun isBrandBlue(color: Int): Boolean {
        val r = color ushr 16 and 0xFF
        val g = color ushr 8 and 0xFF
        val b = color and 0xFF
        return b > r + 30 && b > g + 10
    }

    fun isMostlyLight(color: Int): Boolean = luminance(color) >= 0.55

    fun isMostlyDark(color: Int): Boolean = luminance(color) <= 0.35

    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255) and 0xFF) shl 24)

    fun blend(first: Int, second: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val inverse = 1f - t
        fun component(shift: Int): Int =
            ((first ushr shift and 0xFF) * inverse + (second ushr shift and 0xFF) * t).toInt()
        return (component(24) shl 24) or
            (component(16) shl 16) or
            (component(8) shl 8) or
            component(0)
    }

}
