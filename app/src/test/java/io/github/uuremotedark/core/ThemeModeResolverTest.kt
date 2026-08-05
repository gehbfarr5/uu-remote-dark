package io.github.uuremotedark.core

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeResolverTest {
    @Test
    fun followSystemUsesNightMask() {
        assertFalse(ThemeModeResolver.isDark(ThemeMode.FOLLOW_SYSTEM, Configuration.UI_MODE_NIGHT_NO))
        assertTrue(ThemeModeResolver.isDark(ThemeMode.FOLLOW_SYSTEM, Configuration.UI_MODE_NIGHT_YES))
    }

    @Test
    fun darkModeIgnoresSystemLight() {
        assertTrue(ThemeModeResolver.isDark(ThemeMode.DARK, Configuration.UI_MODE_NIGHT_NO))
        assertTrue(ThemeModeResolver.isDark(ThemeMode.DARK, Configuration.UI_MODE_NIGHT_YES))
    }
}
