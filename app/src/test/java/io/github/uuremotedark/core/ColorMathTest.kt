package io.github.uuremotedark.core

import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {
    @Test
    fun darkTextPaletteHasReadableContrast() {
        assertTrue(ColorMath.contrastRatio(UuPalettes.DARK.primaryText, UuPalettes.DARK.background) >= 4.5)
        assertTrue(ColorMath.contrastRatio(UuPalettes.DARK.secondaryText, UuPalettes.DARK.background) >= 4.5)
    }

    @Test
    fun brandBlueIsRetained() {
        assertTrue(ColorMath.isBrandBlue(UuPalettes.DARK.brand))
    }
}
