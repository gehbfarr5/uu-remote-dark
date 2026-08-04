package io.github.uuremotedark.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService
import io.github.uuremotedark.R
import io.github.uuremotedark.config.PreferenceKeys
import io.github.uuremotedark.core.ThemeMode
import io.github.uuremotedark.core.ThemeModeResolver
import io.github.uuremotedark.core.UuPalettes

class ThemeChooserActivity : Activity(), UuDarkApplication.ServiceStateListener {
    companion object {
        fun intent(context: Context): Intent = Intent(context, ThemeChooserActivity::class.java)
    }

    private var service: XposedService? = null
    private lateinit var radioGroup: RadioGroup
    private lateinit var subtitle: TextView
    private var suppressCallbacks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val systemUiMode = resources.configuration.uiMode
        applyWindowTheme(systemUiMode)
        val dark = ThemeModeResolver.isDark(ThemeMode.FOLLOW_SYSTEM, systemUiMode)
        val palette = if (dark) UuPalettes.DARK else UuPalettes.LIGHT
        val padding = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(palette.surface)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.theme_title)
            textSize = 20f
            setTextColor(palette.primaryText)
        }, ViewGroup.LayoutParams(-1, -2))
        subtitle = TextView(this).apply {
            textSize = 12f
            setTextColor(palette.secondaryText)
            setPadding(0, padding / 3, 0, padding / 2)
        }
        root.addView(subtitle, ViewGroup.LayoutParams(-1, -2))
        radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setOnCheckedChangeListener { _, checkedId ->
                if (suppressCallbacks) return@setOnCheckedChangeListener
                val selected = when (checkedId) {
                    1 -> ThemeMode.FOLLOW_SYSTEM
                    2 -> ThemeMode.DARK
                    else -> return@setOnCheckedChangeListener
                }
                writeMode(selected)
            }
        }
        addRadio(1, ThemeMode.FOLLOW_SYSTEM, palette)
        addRadio(2, ThemeMode.DARK, palette)
        root.addView(radioGroup, ViewGroup.LayoutParams(-1, -2))
        setContentView(root)
        refreshFromService()
    }

    private fun addRadio(id: Int, mode: ThemeMode, palette: io.github.uuremotedark.core.ThemePalette) {
        radioGroup.addView(RadioButton(this).apply {
            this.id = id
            text = mode.label
            textSize = 16f
            setTextColor(palette.primaryText)
            setPadding(0, 8, 0, 8)
        }, RadioGroup.LayoutParams(-1, -2))
    }

    override fun onStart() {
        super.onStart()
        UuDarkApplication.addServiceStateListener(this, true)
    }

    override fun onStop() {
        UuDarkApplication.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        this.service = service
        runOnUiThread { refreshFromService() }
    }

    private fun refreshFromService() {
        val currentService = service
        if (currentService == null) {
            subtitle.text = getString(R.string.theme_service_waiting)
            radioGroup.isEnabled = false
            return
        }
        subtitle.text = getString(R.string.module_status_framework, currentService.frameworkName, currentService.apiVersion)
        radioGroup.isEnabled = true
        val mode = ThemeMode.fromWireValue(
            currentService.getRemotePreferences(PreferenceKeys.GROUP)
                .getString(PreferenceKeys.THEME_MODE, null),
        )
        suppressCallbacks = true
        radioGroup.check(if (mode == ThemeMode.DARK) 2 else 1)
        suppressCallbacks = false
    }

    private fun writeMode(mode: ThemeMode) {
        val currentService = service
        if (currentService == null) {
            Toast.makeText(this, R.string.theme_service_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        currentService.getRemotePreferences(PreferenceKeys.GROUP)
            .edit()
            .putString(PreferenceKeys.THEME_MODE, mode.wireValue)
            .apply()
        finish()
    }

    private fun applyWindowTheme(uiMode: Int) {
        val dark = ThemeModeResolver.isDark(ThemeMode.FOLLOW_SYSTEM, uiMode)
        val palette = if (dark) UuPalettes.DARK else UuPalettes.LIGHT
        window.statusBarColor = palette.surface
        window.navigationBarColor = palette.surface
        var flags = window.decorView.systemUiVisibility
        flags = if (dark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            flags = if (dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        window.decorView.systemUiVisibility = flags
    }
}
