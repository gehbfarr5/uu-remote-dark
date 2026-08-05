package io.github.uuremotedark.hook

import android.app.Activity
import android.app.Instrumentation
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.uuremotedark.config.PreferenceKeys
import io.github.uuremotedark.core.ThemeMode
import io.github.uuremotedark.core.ThemeModeResolver
import io.github.uuremotedark.core.UuPalettes
import io.github.uuremotedark.uu.TargetInfo
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs only framework-level observation hooks while the process is being
 * created.  Vector can dispatch PackageReady after the first Activity has
 * already been created, so the full target ClassLoader based runtime cannot
 * be the first observation point.
 */
internal object EarlyLifecycleBridge {
    private const val TAG = "UuDark/EarlyLifecycle"
    private val installed = AtomicBoolean(false)
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    @Volatile
    private var preferences: SharedPreferences? = null

    fun install(module: XposedModule, processName: String) {
        if (processName != TargetInfo.PACKAGE_NAME) return
        if (!installed.compareAndSet(false, true)) return
        preferences = runCatching { module.getRemotePreferences(PreferenceKeys.GROUP) }
            .onFailure { log(module, "early remote preferences unavailable", it) }
            .getOrNull()

        var hookCount = 0
        runCatching {
            val method = Instrumentation::class.java.getDeclaredMethod(
                "callActivityOnCreate",
                Activity::class.java,
                Bundle::class.java,
            )
            hook(module, method, "instrumentation.callActivityOnCreate") { chain ->
                val result = chain.proceed()
                observe(module, chain.getArg(0) as? Activity)
                result
            }
            hookCount += 1
        }.onFailure { log(module, "early instrumentation hook unavailable", it) }

        // Android 16/OPlus may enter the Activity lifecycle through these
        // framework template methods without going through the public
        // Instrumentation callback.  They are stable framework boundaries and
        // do not depend on UU's obfuscated/Compose classes.
        runCatching {
            Activity::class.java.declaredMethods
                .filter { it.name in ACTIVITY_BOUNDARIES }
                .forEach { method ->
                    hook(module, method, "activity.${method.name}") { chain ->
                        val result = chain.proceed()
                        observe(module, chain.thisObject as? Activity)
                        result
                    }
                    hookCount += 1
                }
        }.onFailure { log(module, "early Activity boundary hooks unavailable", it) }

        runCatching {
            Class.forName("android.app.ActivityThread").declaredMethods
                .filter { method ->
                    method.name in ACTIVITY_THREAD_LAUNCH_BOUNDARIES &&
                        Activity::class.java.isAssignableFrom(method.returnType)
                }
                .forEach { method ->
                    hook(module, method, "activityThread.${method.name}") { chain ->
                        if (method.name == "handleLaunchActivity") {
                            log(module, "enter activityThread.handleLaunchActivity")
                        }
                        val result = chain.proceed()
                        observe(module, result as? Activity)
                        result
                    }
                    hookCount += 1
                }
        }.onFailure { log(module, "early ActivityThread hook unavailable", it) }

        // UU's Compose color adapter reads Resources during its first
        // composition. Register this process-wide mapping at module-load time
        // so the initial frame is covered even when later Activity callbacks
        // are not dispatched by the OEM framework path.
        runCatching {
            hook(
                module,
                Resources::class.java.getDeclaredMethod("getColor", Int::class.javaPrimitiveType),
                "resources.getColor",
            ) { chain ->
                val resources = chain.thisObject as Resources
                val original = chain.proceed() as Int
                mapColor(resources, chain.getArg(0) as Int, original)
            }
            hook(
                module,
                Resources::class.java.getDeclaredMethod(
                    "getColor",
                    Int::class.javaPrimitiveType,
                    Resources.Theme::class.java,
                ),
                "resources.getColor.theme",
            ) { chain ->
                val resources = chain.thisObject as Resources
                val original = chain.proceed() as Int
                mapColor(resources, chain.getArg(0) as Int, original)
            }
            hook(
                module,
                Resources::class.java.getDeclaredMethod("getColorStateList", Int::class.javaPrimitiveType),
                "resources.getColorStateList",
            ) { chain ->
                val resources = chain.thisObject as Resources
                val original = chain.proceed() as ColorStateList
                mapStateList(resources, chain.getArg(0) as Int, original)
            }
            hook(
                module,
                Resources::class.java.getDeclaredMethod(
                    "getColorStateList",
                    Int::class.javaPrimitiveType,
                    Resources.Theme::class.java,
                ),
                "resources.getColorStateList.theme",
            ) { chain ->
                val resources = chain.thisObject as Resources
                val original = chain.proceed() as ColorStateList
                mapStateList(resources, chain.getArg(0) as Int, original)
            }
        }.onFailure { log(module, "early resource hooks unavailable", it) }

        log(module, "early lifecycle bridge installed hooks=$hookCount")
    }

    private fun mapColor(resources: Resources, resourceId: Int, original: Int): Int {
        if (!isDark(resources)) return original
        return ResourcePaletteMapper.map(resources, resourceId, original, UuPalettes.DARK)
    }

    private fun mapStateList(
        resources: Resources,
        resourceId: Int,
        original: ColorStateList,
    ): ColorStateList {
        if (!isDark(resources)) return original
        return ResourcePaletteMapper.mapStateList(resources, resourceId, original, UuPalettes.DARK)
    }

    private fun isDark(resources: Resources): Boolean {
        val mode = ThemeMode.fromWireValue(
            preferences?.getString(PreferenceKeys.THEME_MODE, null),
        )
        return ThemeModeResolver.isDark(mode, resources.configuration.uiMode)
    }

    private fun observe(module: XposedModule, activity: Activity?) {
        if (activity == null || activity.packageName != TargetInfo.PACKAGE_NAME) return
        log(module, "first Activity observed ${activity.javaClass.name}")
        TargetRuntime.observe(module, activity)
    }

    private fun hook(
        module: XposedModule,
        method: Method,
        id: String,
        interceptor: XposedInterface.Hooker,
    ) {
        if (method.declaringClass.name == "android.app.ActivityThread" ||
            method.declaringClass.name == "android.app.Activity" ||
            method.declaringClass.name == "android.app.Instrumentation" ||
            method.declaringClass.name == "android.content.res.Resources"
        ) {
            val deoptimized = runCatching { module.deoptimize(method) }.getOrDefault(false)
            log(module, "deopt $id=$deoptimized")
        }
        handles += module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(interceptor)
        log(module, "hooked $id")
    }

    private fun log(module: XposedModule, message: String, throwable: Throwable? = null) {
        module.log(Log.INFO, TAG, message, throwable)
        Log.println(Log.INFO, TAG, message)
        throwable?.let { Log.e(TAG, "hook detail", it) }
    }

    private val ACTIVITY_BOUNDARIES = setOf(
        "performCreate",
        "performStart",
        "performResume",
        "performPostCreate",
        "performPostResume",
        "onPostResume",
        "onWindowFocusChanged",
    )

    private val ACTIVITY_THREAD_LAUNCH_BOUNDARIES = setOf(
        "handleLaunchActivity",
        "performLaunchActivity",
    )
}
