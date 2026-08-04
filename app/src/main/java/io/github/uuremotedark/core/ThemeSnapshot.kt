package io.github.uuremotedark.core

data class ThemeSnapshot(
    val mode: ThemeMode,
    val uiMode: Int,
    val isDark: Boolean,
    val generation: Long,
)
