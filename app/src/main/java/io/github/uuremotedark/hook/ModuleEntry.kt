package io.github.uuremotedark.hook

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.uuremotedark.uu.TargetInfo
import java.util.concurrent.atomic.AtomicBoolean

class ModuleEntry : XposedModule() {
    companion object {
        private const val TAG = "UuDark/ModuleEntry"
    }

    private val attachHookInstalled = AtomicBoolean(false)
    private val handles = mutableListOf<XposedInterface.HookHandle>()

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Log.i(TAG, "onModuleLoaded process=${param.processName} api=$apiVersion framework=$frameworkName")
        writeLog(Log.INFO, TAG, "loaded framework=$frameworkName version=$frameworkVersion API=$apiVersion")
        EarlyLifecycleBridge.install(this, param.processName)
        // Install before LoadedApk's package callbacks. On Vector/ColorOS the
        // first Activity can begin while onPackageReady is being dispatched.
        installAttachHook(param.processName, true, null)
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        installAttachHook(param.packageName, param.isFirstPackage, param.defaultClassLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        Log.i(
            TAG,
            "onPackageReady package=${param.packageName} first=${param.isFirstPackage} " +
                "loader=${param.classLoader}",
        )
        installAttachHook(param.packageName, param.isFirstPackage, param.classLoader)
    }

    private fun installAttachHook(packageName: String, isFirstPackage: Boolean, classLoader: ClassLoader?) {
        if (packageName != TargetInfo.PACKAGE_NAME || !isFirstPackage) return
        if (!attachHookInstalled.compareAndSet(false, true)) return
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        handles += hook(attach)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val application = chain.thisObject as Application
                TargetRuntime.install(this, application, classLoader ?: application.classLoader)
                result
            }
        writeLog(Log.INFO, TAG, "Application.attach hook installed")
    }

    private fun writeLog(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        super.log(priority, tag, message, throwable)
        Log.println(priority, tag, message)
        throwable?.let { Log.e(tag, "hook detail", it) }
    }
}
