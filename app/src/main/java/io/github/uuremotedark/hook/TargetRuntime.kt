package io.github.uuremotedark.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.uuremotedark.config.PreferenceKeys
import io.github.uuremotedark.core.ThemeSnapshot
import io.github.uuremotedark.core.ThemeState
import io.github.uuremotedark.core.UuPalettes
import io.github.uuremotedark.uu.CompatibilityGate
import io.github.uuremotedark.uu.TargetInfo
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TargetRuntime private constructor(
    private val module: XposedModule,
    private val application: Application,
    private val hostClassLoader: ClassLoader,
) {
    companion object {
        private const val TAG = "UuDark/TargetRuntime"
        private val installed = AtomicBoolean(false)

        fun install(
            module: XposedModule,
            application: Application,
            hostClassLoader: ClassLoader,
        ) {
            if (!installed.compareAndSet(false, true)) return
            try {
                TargetRuntime(module, application, hostClassLoader).installInternal()
            } catch (throwable: Throwable) {
                installed.set(false)
                module.log(android.util.Log.ERROR, TAG, "runtime installation failed", throwable)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activities = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val state: ThemeState
    private val themeListener = ThemeState.Listener { snapshot ->
        mainHandler.post { applyToAllActivities(snapshot) }
    }

    init {
        val preferences = module.getRemotePreferences(PreferenceKeys.GROUP)
        state = ThemeState(preferences) { application.resources.configuration.uiMode }
    }

    private fun installInternal() {
        val compatibility = CompatibilityGate.inspect(application)
        if (!compatibility.supported) {
            log("unsupported target: ${compatibility.reason}")
            return
        }

        state.addListener(themeListener)
        state.start()
        installActivityHooks()
        installResourceHooks()
        DynamicViewHooks.install(
            module = module,
            isDark = { state.current().isDark },
            log = ::log,
            addHandle = { handle -> handles += handle },
        )
        log("installed for ${TargetInfo.PACKAGE_NAME} ${compatibility.versionName} host=$hostClassLoader")
    }

    private fun installActivityHooks() {
        hook(
            Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java),
            "activity.onCreate",
        ) { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as Activity
            activities[activity] = true
            scheduleApply(activity)
            result
        }

        hook(Activity::class.java.getDeclaredMethod("onResume"), "activity.onResume") { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as Activity
            activities[activity] = true
            scheduleApply(activity)
            result
        }

        hook(
            Activity::class.java.getDeclaredMethod("onConfigurationChanged", Configuration::class.java),
            "activity.onConfigurationChanged",
        ) { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as Activity
            val configuration = chain.getArg(0) as Configuration
            state.updateSystemUiMode(configuration.uiMode)
            scheduleApply(activity)
            result
        }

        hook(Activity::class.java.getDeclaredMethod("onDestroy"), "activity.onDestroy") { chain ->
            val activity = chain.thisObject as Activity
            activities.remove(activity)
            chain.proceed()
        }
    }

    private fun installResourceHooks() {
        hook(
            Resources::class.java.getDeclaredMethod("getColor", Int::class.javaPrimitiveType),
            "resources.getColor",
        ) { chain ->
            val result = chain.proceed() as Int
            mapResourceColor(chain.thisObject as Resources, chain.getArg(0) as Int, result)
        }

        hook(
            Resources::class.java.getDeclaredMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java,
            ),
            "resources.getColor.theme",
        ) { chain ->
            val result = chain.proceed() as Int
            mapResourceColor(chain.thisObject as Resources, chain.getArg(0) as Int, result)
        }

        hook(
            Resources::class.java.getDeclaredMethod("getColorStateList", Int::class.javaPrimitiveType),
            "resources.getColorStateList",
        ) { chain ->
            val result = chain.proceed() as android.content.res.ColorStateList
            mapResourceColorStateList(chain.thisObject as Resources, chain.getArg(0) as Int, result)
        }

        hook(
            Resources::class.java.getDeclaredMethod(
                "getColorStateList",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java,
            ),
            "resources.getColorStateList.theme",
        ) { chain ->
            val result = chain.proceed() as android.content.res.ColorStateList
            mapResourceColorStateList(chain.thisObject as Resources, chain.getArg(0) as Int, result)
        }
    }

    private fun mapResourceColor(resources: Resources, resourceId: Int, original: Int): Int {
        val snapshot = state.current()
        if (!snapshot.isDark) return original
        return ResourcePaletteMapper.map(resources, resourceId, original, UuPalettes.DARK)
    }

    private fun mapResourceColorStateList(
        resources: Resources,
        resourceId: Int,
        original: android.content.res.ColorStateList,
    ): android.content.res.ColorStateList {
        val snapshot = state.current()
        if (!snapshot.isDark) return original
        return ResourcePaletteMapper.mapStateList(resources, resourceId, original, UuPalettes.DARK)
    }

    private fun hook(method: Method, id: String, interceptor: XposedInterface.Hooker) {
        handles += module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(interceptor)
        log("hooked $id")
    }

    private fun scheduleApply(activity: Activity) {
        mainHandler.postDelayed({
            if (!activity.isFinishing && !activity.isDestroyedCompat()) {
                applyActivity(activity, state.current())
            }
        }, 60L)
    }

    private fun applyToAllActivities(snapshot: ThemeSnapshot) {
        val copy = synchronized(activities) { activities.keys.toList() }
        copy.forEach { activity ->
            if (!activity.isFinishing && !activity.isDestroyedCompat()) {
                applyActivity(activity, snapshot)
            }
        }
    }

    private fun applyActivity(activity: Activity, snapshot: ThemeSnapshot) {
        val palette = if (snapshot.isDark) UuPalettes.DARK else UuPalettes.LIGHT
        WindowChrome.apply(activity.window, palette, snapshot.isDark)
        val root: View = activity.window.decorView
        ThemeTreeApplier.apply(root, palette, snapshot.isDark)
        ComposeRefreshDispatcher.dispatch(root, snapshot.isDark)
        SettingsEntryInjector.inject(activity, snapshot, ::log)
    }

    private fun log(message: String) {
        module.log(android.util.Log.INFO, TAG, message)
    }

    private fun Activity.isDestroyedCompat(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed
}
