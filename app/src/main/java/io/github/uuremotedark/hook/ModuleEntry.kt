package io.github.uuremotedark.hook

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.uuremotedark.uu.TargetInfo

class ModuleEntry : XposedModule() {
    companion object {
        private const val TAG = "UuDark/ModuleEntry"
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        if (param.processName != TargetInfo.PACKAGE_NAME) {
            log(Log.DEBUG, TAG, "skip process ${param.processName}")
            if (apiVersion >= API_102) detach()
            return
        }
        log(Log.INFO, TAG, "loaded framework=$frameworkName version=$frameworkVersion API=$apiVersion")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TargetInfo.PACKAGE_NAME || !param.isFirstPackage) {
            if (apiVersion >= API_102) detach()
            return
        }

        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attach)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val application = chain.thisObject as Application
                TargetRuntime.install(this, application, param.classLoader)
                result
            }
        log(Log.INFO, TAG, "Application.attach hook installed")
    }
}
