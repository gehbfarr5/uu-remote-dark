package io.github.uuremotedark.ui

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class UuDarkApplication : Application(), XposedServiceHelper.OnServiceListener {
    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    companion object {
        @Volatile
        var service: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners += listener
            if (notifyImmediately) listener.onServiceStateChanged(service)
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners -= listener
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Companion.service = service
        listeners.forEach { it.onServiceStateChanged(service) }
    }

    override fun onServiceDied(service: XposedService) {
        Companion.service = null
        listeners.forEach { it.onServiceStateChanged(null) }
    }
}
