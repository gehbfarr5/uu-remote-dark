package io.github.uuremotedark.hook

import android.content.res.Configuration
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import android.webkit.WebView

/**
 * Dispatches only a night-mode configuration delta to Compose roots. Compose's
 * Android host turns configuration changes into snapshot state, which causes
 * theme functions to run again without destroying the Activity.
 */
object ComposeRefreshDispatcher {
    private const val COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView"

    fun dispatch(root: View, dark: Boolean) {
        if (isExcluded(root)) return
        if (root.javaClass.name == COMPOSE_VIEW) {
            val configuration = Configuration(root.resources.configuration)
            val nightMask = if (dark) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
            configuration.uiMode =
                (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMask
            root.dispatchConfigurationChanged(configuration)
            return
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                dispatch(root.getChildAt(index), dark)
            }
        }
    }

    private fun isExcluded(view: View): Boolean =
        view is WebView ||
            view is SurfaceView ||
            view is TextureView ||
            view.javaClass.name.contains("RemoteSurface", ignoreCase = true) ||
            view.javaClass.name.contains("Streamer", ignoreCase = true)
}
