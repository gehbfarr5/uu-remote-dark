package io.github.uuremotedark.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun unknownPreferenceFailsToFollowSystem() {
        assertEquals(ThemeMode.FOLLOW_SYSTEM, ThemeMode.fromWireValue("unknown"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromWireValue("dark"))
    }
}
