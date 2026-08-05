package io.github.uuremotedark.hook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.WeakHashMap

class TargetRuntime private constructor(
    private val module: XposedModule,
    private val application: Application,
    private val hostClassLoader: ClassLoader,
) {
    companion object {
        private const val TAG = "UuDark/TargetRuntime"
        private val lock = Any()
        @Volatile
        private var instance: TargetRuntime? = null

        fun install(
            module: XposedModule,
            application: Application,
            hostClassLoader: ClassLoader,
        ): TargetRuntime? {
            instance?.let { return it }
            synchronized(lock) {
                instance?.let { return it }
                return try {
                    TargetRuntime(module, application, hostClassLoader).also {
                        it.installInternal()
                        instance = it
                    }
                } catch (throwable: Throwable) {
                    module.log(android.util.Log.ERROR, TAG, "runtime installation failed", throwable)
                    android.util.Log.e(TAG, "runtime installation failed", throwable)
                    null
                }
            }
        }

        fun observe(module: XposedModule, activity: Activity) {
            val runtime = install(
                module = module,
                application = activity.application,
                hostClassLoader = activity.javaClass.classLoader ?: activity.classLoader,
            )
            runtime?.observeActivity(activity)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * ActivityThread polling deliberately runs off the target main queue.
     * UU's startup can keep that queue busy while Compose is bootstrapping;
     * posting a self-rescheduling Runnable there was therefore not a reliable
     * observation point on the affected ColorOS build.
     */
    private val scannerExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "UuDark-activity-scan").apply { isDaemon = true }
        }
    private val activities = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())
    private val scannedRoots = Collections.synchronizedMap(WeakHashMap<Activity, View>())
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private var activityScanErrorLogged = false
    private var activityScannerTickLogged = false
    private var activityScannerRuns = 0
    private val resourceColorHookHits = AtomicInteger(0)
    private val uiPassQueued = java.util.concurrent.atomic.AtomicBoolean(false)
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
        application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        installActivityHooks()
        installResourceHooks()
        DynamicViewHooks.install(
            module = module,
            isDark = { state.current().isDark },
            log = ::log,
            addHandle = { handle -> handles += handle },
        )
        startActivityScanner()
        log("installed for ${TargetInfo.PACKAGE_NAME} ${compatibility.versionName} host=$hostClassLoader")
    }

    private fun observeActivity(activity: Activity) {
        if (activity.packageName != TargetInfo.PACKAGE_NAME) return
        activities[activity] = true
        scheduleApply(activity)
    }

    private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
            observeLifecycleActivity("created", activity)

        override fun onActivityStarted(activity: Activity) =
            observeLifecycleActivity("started", activity)

        override fun onActivityResumed(activity: Activity) =
            observeLifecycleActivity("resumed", activity)

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            activities.remove(activity)
            scannedRoots.remove(activity)
        }
    }

    private fun observeLifecycleActivity(stage: String, activity: Activity) {
        if (activity.packageName != TargetInfo.PACKAGE_NAME) return
        activities[activity] = true
        log("application lifecycle $stage ${activity.javaClass.name}")
        scheduleApply(activity)
    }

    /**
     * Enumerates ActivityThread's live records as a second lifecycle boundary.
     * This is needed on the current ColorOS/Vector combination because some
     * Activity methods are entered through direct/private framework calls that
     * do not dispatch the public Java hook chain. It also catches a Compose
     * Activity that was already created before PackageReady arrived.
     */
    private fun startActivityScanner() {
        // Run once synchronously: on this device the first Activity can be
        // created before the main queue accepts a newly posted callback.
        scanLiveActivities()
        scannerExecutor.scheduleWithFixedDelay(
            {
                runCatching { scanLiveActivities() }
                    .onFailure { log("activity scanner failed: ${it.message}") }
            },
            250L,
            250L,
            TimeUnit.MILLISECONDS,
        )
        log("activity scanner executor scheduled")
    }

    private fun scanLiveActivities() {
        activityScannerRuns += 1
        // Follow-system mode must not depend solely on Activity's public
        // configuration callback; the OEM launch path can bypass that hook.
        state.updateSystemUiMode(application.resources.configuration.uiMode)
        if (!activityScannerTickLogged) {
            activityScannerTickLogged = true
            log("activity scanner started")
        }
        if (activityScannerRuns <= 3) log("activity scanner run=$activityScannerRuns")
        val thread = runCatching {
            val owner = Class.forName("android.app.ActivityThread")
            owner.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
        }.getOrNull()
        if (thread == null) {
            logActivityScanFailure("ActivityThread.currentActivityThread unavailable")
            return
        }

        val records = runCatching {
            thread.javaClass.getDeclaredField("mActivities").apply { isAccessible = true }
                .get(thread) as? Map<*, *>
        }.getOrNull()
        if (records == null) {
            logActivityScanFailure("ActivityThread.mActivities unavailable")
            return
        }
        if (activityScannerRuns <= 3) log("activity scanner records=${records.size}")

        records.values.forEach { record ->
            val activity = runCatching {
                record?.javaClass?.getDeclaredField("activity")?.apply { isAccessible = true }
                    ?.get(record) as? Activity
            }.onFailure {
                logActivityScanFailure("ActivityThread record.activity unavailable: ${it.message}")
            }.getOrNull() ?: return@forEach
            if (activity.packageName != TargetInfo.PACKAGE_NAME ||
                activity.isFinishing || activity.isDestroyedCompat()
            ) return@forEach

            val root = runCatching { activity.window.decorView }.getOrNull() ?: return@forEach
            activities[activity] = true
            if (!root.isAttachedToWindow) return@forEach
            val previousRoot = scannedRoots[activity]
            val rootChanged = previousRoot !== root
            if (rootChanged) {
                scannedRoots[activity] = root
                log("activity scanner ${activity.javaClass.name}")
            }
            // The tree pass and settings detector must also run when Compose
            // navigates inside the same Activity/root. A root identity change
            // is the only point that needs a synthetic Compose configuration
            // dispatch; later passes cover newly-created classic Views and
            // make the settings entry appear after in-place navigation.
            scheduleUiPass(activity, rootChanged)
        }
    }

    private fun scheduleUiPass(activity: Activity, refreshCompose: Boolean) {
        if (!uiPassQueued.compareAndSet(false, true)) return
        val posted = mainHandler.post {
            try {
                if (!activity.isFinishing && !activity.isDestroyedCompat()) {
                    applyActivity(activity, state.current(), refreshCompose)
                }
            } finally {
                uiPassQueued.set(false)
            }
        }
        if (!posted) {
            uiPassQueued.set(false)
            log("activity scanner UI apply post rejected ${activity.javaClass.name}")
        }
    }

    private fun logActivityScanFailure(message: String) {
        if (activityScanErrorLogged) return
        activityScanErrorLogged = true
        log(message)
    }

    private fun installActivityHooks() {
        installWindowBoundaryHooks()

        Class.forName("android.app.ActivityThread").declaredMethods
            .filter {
                it.name in ACTIVITY_THREAD_LAUNCH_BOUNDARIES &&
                    Activity::class.java.isAssignableFrom(it.returnType)
            }
            .forEach { method ->
                hook(method, "activityThread.${method.name}") { chain ->
                    val result = chain.proceed()
                    val activity = result as? Activity
                    if (activity != null) {
                        activities[activity] = true
                        log("activityThread.performLaunchActivity ${activity.javaClass.name}")
                        scheduleApply(activity)
                    }
                    result
                }
            }

        // ActivityThread drives lifecycle through Instrumentation. Hooking this
        // boundary catches UU's final Compose overrides even when ART dispatch
        // bypasses both Activity and the target-owned superclass methods.
        hook(
            Instrumentation::class.java.getDeclaredMethod(
                "callActivityOnCreate",
                Activity::class.java,
                Bundle::class.java,
            ),
            "instrumentation.callActivityOnCreate",
        ) { chain ->
            val result = chain.proceed()
            val activity = chain.getArg(0) as Activity
            activities[activity] = true
            log("instrumentation.activityOnCreate ${activity.javaClass.name}")
            scheduleApply(activity)
            result
        }

        Instrumentation::class.java.getDeclaredMethodOrNull(
            "callActivityOnCreate",
            Activity::class.java,
            Bundle::class.java,
            android.os.PersistableBundle::class.java,
        )?.let { method ->
            hook(method, "instrumentation.callActivityOnCreate.persistable") { chain ->
                val result = chain.proceed()
                val activity = chain.getArg(0) as Activity
                activities[activity] = true
                log("instrumentation.activityOnCreate ${activity.javaClass.name}")
                scheduleApply(activity)
                result
            }
        }

        hook(
            Instrumentation::class.java.getDeclaredMethod("callActivityOnResume", Activity::class.java),
            "instrumentation.callActivityOnResume",
        ) { chain ->
            val result = chain.proceed()
            val activity = chain.getArg(0) as Activity
            activities[activity] = true
            log("instrumentation.activityOnResume ${activity.javaClass.name}")
            scheduleApply(activity)
            result
        }

        hook(
            Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java),
            "activity.onCreate",
        ) { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as Activity
            activities[activity] = true
            log("activity.onCreate ${activity.javaClass.name}")
            scheduleApply(activity)
            result
        }

        hook(Activity::class.java.getDeclaredMethod("onResume"), "activity.onResume") { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as Activity
            activities[activity] = true
            log("activity.onResume ${activity.javaClass.name}")
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

        Activity::class.java.declaredMethods
            .filter { it.name in ACTIVITY_BOUNDARIES }
            .forEach { method ->
                hook(method, "activity.${method.name}") { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity != null) {
                        activities[activity] = true
                        log("activity.${method.name} ${activity.javaClass.name}")
                        scheduleApply(activity)
                    }
                    result
                }
            }

        // UU's Compose activities inherit from BlinkActivity/BaseActivity and
        // their super calls can bypass a framework Activity.onCreate hook on
        // this ART/ColorOS build. Hook the target-owned lifecycle owners too so
        // ComposeMainActivity and the other native pages receive the same
        // in-place refresh pass.
        listOf(
            "com.remote.provider.BlinkActivity",
            // ComposeMainActivity overrides onCreate/onResume with final methods, so
            // dispatch does not reach the BlinkActivity hooks on this ART build.
            "com.remote.app.ui.activity.ComposeMainActivity",
        ).forEach { className ->
            val owner = runCatching {
                Class.forName(className, false, hostClassLoader)
            }.getOrElse { throwable ->
                log("target lifecycle class unavailable $className: ${throwable.message}")
                return@forEach
            }
            hook(owner.getDeclaredMethod("onCreate", Bundle::class.java), "$className.onCreate") { chain ->
                if (className.endsWith("ComposeMainActivity")) {
                    log("enter target onCreate $className")
                }
                val result = chain.proceed()
                val activity = chain.thisObject as Activity
                activities[activity] = true
                log("activity.onCreate $className -> ${activity.javaClass.name}")
                scheduleApply(activity)
                result
            }
            hook(owner.getDeclaredMethod("onResume"), "$className.onResume") { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as Activity
                activities[activity] = true
                log("activity.onResume $className -> ${activity.javaClass.name}")
                scheduleApply(activity)
                result
            }
            owner.getDeclaredMethodOrNull("onConfigurationChanged", Configuration::class.java)?.let { method ->
                hook(method, "$className.onConfigurationChanged") { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as Activity
                    val configuration = chain.getArg(0) as Configuration
                    state.updateSystemUiMode(configuration.uiMode)
                    scheduleApply(activity)
                    result
                }
            }
        }
    }

    private fun installWindowBoundaryHooks() {
        listOf("android.view.WindowManagerGlobal", "android.view.ViewRootImpl").forEach { className ->
            val owner = Class.forName(className)
            owner.declaredMethods
                .filter { method ->
                    method.name in setOf("addView", "setView") &&
                        method.parameterTypes.firstOrNull() == View::class.java
                }
                .forEach { method ->
                    hook(method, "$className.${method.name}") { chain ->
                        val view = chain.getArg(0) as? View
                        val result = chain.proceed()
                        val activity = view?.context?.findActivity()
                        if (activity != null) {
                            activities[activity] = true
                            log("windowAttached ${activity.javaClass.name} via ${method.name}")
                            scheduleApply(activity)
                        }
                        result
                    }
                }
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return current as? Activity
    }

    private fun Class<*>.getDeclaredMethodOrNull(name: String, vararg parameterTypes: Class<*>): Method? =
        runCatching { getDeclaredMethod(name, *parameterTypes) }.getOrNull()

    private fun installResourceHooks() {
        hook(
            Resources::class.java.getDeclaredMethod("getColor", Int::class.javaPrimitiveType),
            "resources.getColor",
        ) { chain ->
            if (resourceColorHookHits.get() < 8) log("enter resources.getColor")
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
        val hit = resourceColorHookHits.incrementAndGet()
        if (hit <= 8) {
            log("resource getColor hit=$hit id=$resourceId dark=${snapshot.isDark}")
        }
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
        // UU's final Compose lifecycle methods are commonly inlined before the
        // module receives the first activity. Deoptimize target-owned methods
        // before installing the interceptor; framework lifecycle methods are
        // left untouched because ColorOS uses special entry points for them.
        if (method.declaringClass.name.startsWith("com.remote.")) {
            module.deoptimize(method)
        }
        if (method.declaringClass.name == "android.app.ActivityThread" ||
            method.declaringClass.name == "android.app.Activity" ||
            method.declaringClass.name == "android.app.Instrumentation"
        ) {
            log("deopt $id=${runCatching { module.deoptimize(method) }.getOrDefault(false)}")
        }
        handles += module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(interceptor)
        log("hooked $id")
    }

    private fun scheduleApply(activity: Activity) {
        mainHandler.postDelayed({
            if (!activity.isFinishing && !activity.isDestroyedCompat()) {
                log("apply scheduled activity=${activity.javaClass.name}")
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

    private fun applyActivity(
        activity: Activity,
        snapshot: ThemeSnapshot,
        refreshCompose: Boolean = true,
    ) {
        val palette = if (snapshot.isDark) UuPalettes.DARK else UuPalettes.LIGHT
        if (refreshCompose) {
            log(
                "apply activity=${activity.javaClass.name} dark=${snapshot.isDark} " +
                    "mode=${snapshot.mode.wireValue} generation=${snapshot.generation}",
            )
        }
        WindowChrome.apply(activity.window, palette, snapshot.isDark)
        val root: View = activity.window.decorView
        ThemeTreeApplier.apply(root, palette, snapshot.isDark)
        if (refreshCompose) ComposeRefreshDispatcher.dispatch(root, snapshot.isDark)
        SettingsEntryInjector.inject(activity, snapshot, ::log) { mode ->
            module.getRemotePreferences(PreferenceKeys.GROUP)
                .edit()
                .putString(PreferenceKeys.THEME_MODE, mode.wireValue)
                .apply()
            state.setMode(mode)
        }
    }

    private fun log(message: String) {
        module.log(android.util.Log.INFO, TAG, message)
        android.util.Log.i(TAG, message)
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

    private fun Activity.isDestroyedCompat(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed
}
