package io.github.uuremotedark.core

data class ThemePalette(
    val background: Int,
    val surface: Int,
    val elevatedSurface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val divider: Int,
    val brand: Int,
    val onBrand: Int,
)

object UuPalettes {
    val LIGHT = ThemePalette(
        background = 0xFFFFFFFF.toInt(),
        surface = 0xFFF7F8FA.toInt(),
        elevatedSurface = 0xFFFFFFFF.toInt(),
        primaryText = 0xFF18191C.toInt(),
        secondaryText = 0xFF747A85.toInt(),
        divider = 0xFFE8EAF0.toInt(),
        brand = 0xFF3A7BFC.toInt(),
        onBrand = 0xFFFFFFFF.toInt(),
    )

    val DARK = ThemePalette(
        background = 0xFF111114.toInt(),
        surface = 0xFF18181C.toInt(),
        elevatedSurface = 0xFF222228.toInt(),
        primaryText = 0xFFF2F3F5.toInt(),
        secondaryText = 0xFFA8ADB7.toInt(),
        divider = 0xFF2D2F36.toInt(),
        brand = 0xFF3A7BFC.toInt(),
        onBrand = 0xFFFFFFFF.toInt(),
    )
}
