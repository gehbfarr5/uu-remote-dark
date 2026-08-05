package io.github.uuremotedark.core

import android.content.SharedPreferences
import io.github.uuremotedark.config.PreferenceKeys
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

class ThemeState(
    private val preferences: SharedPreferences,
    private val uiModeProvider: () -> Int,
) {
    fun interface Listener {
        fun onThemeChanged(snapshot: ThemeSnapshot)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val generation = AtomicLong(0L)
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile
    private var mode: ThemeMode = ThemeMode.FOLLOW_SYSTEM

    @Volatile
    private var uiMode: Int = uiModeProvider()

    @Volatile
    private var snapshot: ThemeSnapshot = ThemeSnapshot(
        mode = mode,
        uiMode = uiMode,
        isDark = ThemeModeResolver.isDark(mode, uiMode),
        generation = generation.get(),
    )

    fun start() {
        mode = ThemeMode.fromWireValue(preferences.getString(PreferenceKeys.THEME_MODE, null))
        uiMode = uiModeProvider()
        preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PreferenceKeys.THEME_MODE) {
                mode = ThemeMode.fromWireValue(preferences.getString(key, null))
                publish()
            }
        }.also(preferences::registerOnSharedPreferenceChangeListener)
        publish()
    }

    fun stop() {
        preferenceListener?.let(preferences::unregisterOnSharedPreferenceChangeListener)
        preferenceListener = null
        listeners.clear()
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onThemeChanged(snapshot)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun updateSystemUiMode(newUiMode: Int) {
        if (uiMode == newUiMode) return
        uiMode = newUiMode
        publish()
    }

    /** Update the selected mode immediately, before the remote preference
     * callback is delivered. This keeps an in-app settings control visually
     * synchronous while the persisted value is written in parallel. */
    fun setMode(newMode: ThemeMode) {
        if (mode == newMode) return
        mode = newMode
        publish()
    }

    fun current(): ThemeSnapshot = snapshot

    private fun publish() {
        val next = ThemeSnapshot(
            mode = mode,
            uiMode = uiMode,
            isDark = ThemeModeResolver.isDark(mode, uiMode),
            generation = generation.incrementAndGet(),
        )
        if (next == snapshot) return
        snapshot = next
        listeners.forEach { it.onThemeChanged(next) }
    }
}
