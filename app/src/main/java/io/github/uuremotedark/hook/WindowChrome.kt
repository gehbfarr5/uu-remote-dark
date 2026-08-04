package io.github.uuremotedark.hook

import android.os.Build
import android.view.View
import android.view.Window
import io.github.uuremotedark.core.ThemePalette

object WindowChrome {
    fun apply(window: Window, palette: ThemePalette, dark: Boolean) {
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        val decor = window.decorView
        var flags = decor.systemUiVisibility
        if (dark) {
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= 26) {
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        } else {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        decor.systemUiVisibility = flags
    }
}
