package io.github.uuremotedark.hook

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.graphics.drawable.Drawable
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.uuremotedark.core.ColorMath
import io.github.uuremotedark.core.ThemePalette
import io.github.uuremotedark.core.UuPalettes
import io.github.uuremotedark.uu.TargetInfo

/**
 * Covers Views inflated after the first decor-tree pass.  This deliberately
 * hooks semantic setters instead of Paint/Canvas/renderer hot paths, so a
 * remote framebuffer, WebView and images are never colour-transformed.
 */
object DynamicViewHooks {
    fun install(
        module: XposedModule,
        isDark: () -> Boolean,
        log: (String) -> Unit,
        addHandle: (XposedInterface.HookHandle) -> Unit,
    ) {
        hook(module, View::class.java.getDeclaredMethod("setBackgroundColor", Int::class.javaPrimitiveType), "view.setBackgroundColor", addHandle) { chain ->
            val view = chain.thisObject as View
            val color = chain.getArg(0) as Int
            rememberOrForget(view, isDark())
            val mapped = if (isDark() && isTargetView(view) && ColorMath.isMostlyLight(color)) {
                darkSurfaceFor(color)
            } else {
                color
            }
            chain.proceed(arrayOf(mapped))
        }

        hook(module, View::class.java.getDeclaredMethod("setBackgroundResource", Int::class.javaPrimitiveType), "view.setBackgroundResource", addHandle) { chain ->
            val view = chain.thisObject as View
            val resourceId = chain.getArg(0) as Int
            rememberOrForget(view, isDark())
            if (isDark() && isTargetView(view) && isColorResource(view, resourceId)) {
                val original = runCatching {
                    view.resources.getColor(resourceId, view.context.theme)
                }.getOrNull()
                if (original != null) {
                    view.setBackgroundColor(
                        ResourcePaletteMapper.map(view.resources, resourceId, original, UuPalettes.DARK),
                    )
                    null
                } else {
                    chain.proceed()
                }
            } else {
                chain.proceed()
            }
        }

        hook(module, View::class.java.getDeclaredMethod("setBackground", Drawable::class.java), "view.setBackground", addHandle) { chain ->
            val view = chain.thisObject as View
            val drawable = chain.getArg(0) as? Drawable
            rememberOrForget(view, isDark())
            val mapped = if (isDark() && isTargetView(view) && drawable != null) {
                ThemeTreeApplier.mapDrawableForDark(drawable, UuPalettes.DARK)
            } else {
                drawable
            }
            chain.proceed(arrayOf(mapped))
        }

        hook(module, TextView::class.java.getDeclaredMethod("setTextColor", Int::class.javaPrimitiveType), "textView.setTextColor", addHandle) { chain ->
            val view = chain.thisObject as TextView
            val color = chain.getArg(0) as Int
            rememberOrForget(view, isDark())
            val mapped = if (isDark() && isTargetView(view)) mapTextColor(color, UuPalettes.DARK) else color
            chain.proceed(arrayOf(mapped))
        }

        hook(module, TextView::class.java.getDeclaredMethod("setTextColor", ColorStateList::class.java), "textView.setTextColor.stateList", addHandle) { chain ->
            val view = chain.thisObject as TextView
            val colors = chain.getArg(0) as ColorStateList
            rememberOrForget(view, isDark())
            val mapped = if (isDark() && isTargetView(view)) mapTextColors(colors, UuPalettes.DARK) else colors
            chain.proceed(arrayOf(mapped))
        }
        log("dynamic View setter hooks installed")
    }

    private fun hook(
        module: XposedModule,
        method: java.lang.reflect.Method,
        id: String,
        addHandle: (XposedInterface.HookHandle) -> Unit,
        interceptor: (XposedInterface.Chain) -> Any?,
    ) {
        addHandle(
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(interceptor),
        )
    }

    private fun isTargetView(view: View): Boolean {
        if (view.context.packageName != TargetInfo.PACKAGE_NAME) return false
        return !ThemeTreeApplier.isExcludedForHook(view)
    }

    private fun rememberOrForget(view: View, dark: Boolean) {
        if (!isTargetView(view)) return
        if (dark) ThemeTreeApplier.rememberOriginal(view) else ThemeTreeApplier.forgetOriginal(view)
    }

    private fun isColorResource(view: View, resourceId: Int): Boolean =
        runCatching { view.resources.getResourceTypeName(resourceId) == "color" }.getOrDefault(false)

    private fun darkSurfaceFor(color: Int): Int = ColorMath.withAlpha(
        if (ColorMath.luminance(color) > 0.82) UuPalettes.DARK.surface else UuPalettes.DARK.elevatedSurface,
        Color.alpha(color),
    )

    private fun mapTextColor(color: Int, palette: ThemePalette): Int = when {
        ColorMath.isBrandBlue(color) -> color
        ColorMath.isMostlyDark(color) -> {
            val alpha = Color.alpha(color)
            ColorMath.withAlpha(if (alpha < 190) palette.secondaryText else palette.primaryText, alpha)
        }
        else -> color
    }

    private fun mapTextColors(colors: ColorStateList, palette: ThemePalette): ColorStateList =
        ColorStateListMapper.map(colors) { mapTextColor(it, palette) }

}
