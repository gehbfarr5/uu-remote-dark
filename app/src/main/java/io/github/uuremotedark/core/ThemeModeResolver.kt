package io.github.uuremotedark.core

import android.content.res.Configuration

object ThemeModeResolver {
    fun isDark(mode: ThemeMode, uiMode: Int): Boolean = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.FOLLOW_SYSTEM ->
            uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
}
