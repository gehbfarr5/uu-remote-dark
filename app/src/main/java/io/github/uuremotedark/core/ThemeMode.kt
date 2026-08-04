package io.github.uuremotedark.core

enum class ThemeMode(val wireValue: String, val label: String) {
    FOLLOW_SYSTEM("follow_system", "跟随系统"),
    DARK("dark", "深色模式");

    companion object {
        fun fromWireValue(value: String?): ThemeMode =
            entries.firstOrNull { it.wireValue == value } ?: FOLLOW_SYSTEM
    }
}
