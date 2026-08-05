package io.github.uuremotedark.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.service.XposedService
import io.github.uuremotedark.R

class MainActivity : Activity(), UuDarkApplication.ServiceStateListener {
    private lateinit var status: TextView
    private var service: XposedService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.module_status_title)
            textSize = 24f
        }, ViewGroup.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = getString(R.string.module_status_scope)
            setPadding(0, padding / 2, 0, 0)
        }, ViewGroup.LayoutParams(-1, -2))
        status = TextView(this).apply {
            setPadding(0, padding / 2, 0, padding / 2)
            text = getString(R.string.theme_service_waiting)
        }
        root.addView(status, ViewGroup.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = getString(R.string.module_status_note)
            setPadding(0, 0, 0, padding)
        }, ViewGroup.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = getString(R.string.module_open_chooser)
            setOnClickListener { startActivity(ThemeChooserActivity.intent(this@MainActivity)) }
        }, ViewGroup.LayoutParams(-1, -2))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        UuDarkApplication.addServiceStateListener(this, true)
    }

    override fun onStop() {
        UuDarkApplication.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        this.service = service
        runOnUiThread {
            status.text = if (service == null) {
                getString(R.string.module_status_framework_unknown)
            } else {
                getString(R.string.module_status_framework, service.frameworkName, service.apiVersion)
            }
        }
    }
}
