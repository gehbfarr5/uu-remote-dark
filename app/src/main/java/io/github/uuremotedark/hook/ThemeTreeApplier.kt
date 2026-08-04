package io.github.uuremotedark.hook

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import android.webkit.WebView
import android.widget.TextView
import android.widget.VideoView
import io.github.uuremotedark.core.ColorMath
import io.github.uuremotedark.core.ThemePalette
import java.util.Collections
import java.util.WeakHashMap

/**
 * Applies only semantic, low-risk changes to already-created Android Views.
 * The original values are retained so switching back to light mode does not
 * require Activity recreation.
 */
object ThemeTreeApplier {
    private sealed interface BackgroundSnapshot {
        data class Color(val value: Int) : BackgroundSnapshot
        data class Gradient(val value: ColorStateList) : BackgroundSnapshot
    }

    private data class OriginalStyle(
        val background: BackgroundSnapshot?,
        val textColors: ColorStateList?,
    )

    private val originalStyles = Collections.synchronizedMap(WeakHashMap<View, OriginalStyle>())

    /** Capture a View before a setter hook supplies its dark replacement. */
    fun rememberOriginal(view: View) {
        originalStyles.getOrPut(view) {
            OriginalStyle(
                background = captureBackground(view.background),
                textColors = (view as? TextView)?.textColors,
            )
        }
    }

    /** Drop a stale dark-era snapshot before a light-mode setter changes a View. */
    fun forgetOriginal(view: View) {
        originalStyles.remove(view)
    }

    fun apply(root: View, palette: ThemePalette, dark: Boolean) {
        if (isExcluded(root)) return
        rememberOriginal(root)
        val original = originalStyles[root] ?: return

        if (dark) {
            applyDark(root, original, palette)
        } else {
            restore(root, original)
        }

        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                apply(root.getChildAt(index), palette, dark)
            }
        }
    }

    private fun applyDark(view: View, original: OriginalStyle, palette: ThemePalette) {
        when (val background = original.background) {
            is BackgroundSnapshot.Color -> {
                if (ColorMath.isMostlyLight(background.value)) {
                    view.setBackgroundColor(
                        if (ColorMath.luminance(background.value) > 0.82) {
                            palette.surface
                        } else {
                            palette.elevatedSurface
                        },
                    )
                }
            }
            is BackgroundSnapshot.Gradient -> {
                val color = background.value.defaultColor
                if (ColorMath.isMostlyLight(color)) {
                    (view.background as? GradientDrawable)?.mutate()?.let { drawable ->
                        (drawable as GradientDrawable).setColor(
                            if (ColorMath.luminance(color) > 0.82) palette.surface else palette.elevatedSurface,
                        )
                    }
                }
            }
            null -> Unit
        }

        val textView = view as? TextView ?: return
        val colors = original.textColors ?: return
        val mapped = ColorStateListMapper.map(colors) { textColor ->
            when {
                ColorMath.isBrandBlue(textColor) -> textColor
                ColorMath.isMostlyDark(textColor) -> {
                    val alpha = Color.alpha(textColor)
                    val replacement = if (alpha < 190) palette.secondaryText else palette.primaryText
                    ColorMath.withAlpha(replacement, alpha)
                }
                else -> textColor
            }
        }
        if (mapped !== colors) textView.setTextColor(mapped)
    }

    private fun restore(view: View, original: OriginalStyle) {
        when (val background = original.background) {
            is BackgroundSnapshot.Color -> {
                when (val drawable = view.background) {
                    is ColorDrawable -> drawable.mutate().let { (it as ColorDrawable).color = background.value }
                    else -> view.setBackgroundColor(background.value)
                }
            }
            is BackgroundSnapshot.Gradient -> {
                (view.background as? GradientDrawable)?.mutate()?.let { drawable ->
                    (drawable as GradientDrawable).setColor(background.value)
                }
            }
            null -> Unit
        }
        if (view is TextView) original.textColors?.let(view::setTextColor)
    }

    private fun captureBackground(drawable: Drawable?): BackgroundSnapshot? = when (drawable) {
        is ColorDrawable -> BackgroundSnapshot.Color(drawable.color)
        is GradientDrawable -> drawable.color?.let(BackgroundSnapshot::Gradient)
        else -> null
    }

    fun isExcludedForHook(view: View): Boolean = isExcluded(view)

    fun mapDrawableForDark(drawable: Drawable, palette: ThemePalette): Drawable = when (drawable) {
        is ColorDrawable -> {
            if (!ColorMath.isMostlyLight(drawable.color)) drawable
            else ColorDrawable(
                ColorMath.withAlpha(
                    if (ColorMath.luminance(drawable.color) > 0.82) palette.surface else palette.elevatedSurface,
                    Color.alpha(drawable.color),
                ),
            )
        }
        is GradientDrawable -> {
            val color = drawable.color?.defaultColor
            if (color == null || !ColorMath.isMostlyLight(color)) drawable
            else {
                val copy = drawable.constantState?.newDrawable()?.mutate() as? GradientDrawable
                copy?.setColor(
                    ColorMath.withAlpha(
                        if (ColorMath.luminance(color) > 0.82) palette.surface else palette.elevatedSurface,
                        Color.alpha(color),
                    ),
                )
                copy ?: drawable
            }
        }
        else -> drawable
    }

    private fun isExcluded(view: View): Boolean =
        view is WebView ||
            view is SurfaceView ||
            view is TextureView ||
            view is VideoView ||
            view.javaClass.name.contains("RemoteSurface", ignoreCase = true) ||
            view.javaClass.name.contains("Streamer", ignoreCase = true) ||
            view.javaClass.name.contains("VideoFrame", ignoreCase = true)
}
