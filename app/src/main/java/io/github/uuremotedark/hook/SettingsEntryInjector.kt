package io.github.uuremotedark.hook

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeProvider
import io.github.uuremotedark.core.ThemeMode
import io.github.uuremotedark.core.ThemeSnapshot
import io.github.uuremotedark.core.UuPalettes
import io.github.uuremotedark.ui.ThemeChooserActivity
import java.util.Collections
import java.util.WeakHashMap

/**
 * Adds the module entry only when the currently visible UU page identifies
 * itself as the app settings page.  The 4.35.0 main page is Compose, while a
 * number of secondary settings/dialog surfaces are classic Views, so both
 * cases are handled without guessing an obfuscated class or layout name.
 */
object SettingsEntryInjector {
    private const val TAG = "UuDark/SettingsEntry"
    private const val INJECTED_TAG = "io.github.uuremotedark.settings_entry"
    private const val SUMMARY_TAG = "$INJECTED_TAG.summary"
    private val classicRows = Collections.synchronizedMap(WeakHashMap<Activity, View>())
    private val semanticRows = Collections.synchronizedMap(WeakHashMap<Activity, View>())

    fun inject(activity: Activity, snapshot: ThemeSnapshot, log: (String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyedCompat()) return

        val root = activity.window.decorView
        val classicParent = findClassicSettingsParent(root)
        if (classicParent != null) {
            hideSemanticRow(activity)
            val row = classicRows[activity]
            if (row == null || row.parent !== classicParent) {
                row?.let { (it.parent as? ViewGroup)?.removeView(it) }
                val created = createRow(activity, snapshot)
                if (!addRow(classicParent, created)) {
                    log("$TAG classic parent rejected child=${classicParent.javaClass.name}")
                    return
                }
                classicRows[activity] = created
                log("$TAG injected classic settings parent=${classicParent.javaClass.name}")
                update(created, snapshot)
            } else {
                update(row, snapshot)
            }
            return
        }

        val composeSettingsVisible = findComposeSettingsHost(root) != null
        val overlay = semanticRows[activity] ?: createSemanticOverlay(activity).also {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            if (content == null || !addOverlay(content, it, activity)) return
            semanticRows[activity] = it
            log("$TAG prepared Compose settings overlay")
        }
        overlay.visibility = if (composeSettingsVisible) View.VISIBLE else View.GONE
        update(overlay, snapshot)
    }

    private fun createRow(activity: Activity, snapshot: ThemeSnapshot): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = INJECTED_TAG
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10))
            setOnClickListener {
                activity.startActivity(
                    Intent(activity, ThemeChooserActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY),
                )
            }
            addView(TextView(activity).apply {
                text = "外观"
                textSize = 16f
                tag = "$INJECTED_TAG.title"
            }, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(activity).apply {
                tag = SUMMARY_TAG
                textSize = 12f
                setPadding(0, dp(activity, 2), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            update(this, snapshot)
        }

    private fun createSemanticOverlay(activity: Activity): View = createRow(activity, ThemeSnapshot(
        mode = ThemeMode.FOLLOW_SYSTEM,
        uiMode = activity.resources.configuration.uiMode,
        isDark = false,
        generation = 0L,
    )).apply {
        elevation = dp(activity, 8).toFloat()
        contentDescription = "外观"
    }

    private fun update(row: View?, snapshot: ThemeSnapshot) {
        val container = row as? LinearLayout ?: return
        val palette = if (snapshot.isDark) UuPalettes.DARK else UuPalettes.LIGHT
        (container.getChildAt(0) as? TextView)?.setTextColor(palette.primaryText)
        (container.findViewWithTag<TextView>(SUMMARY_TAG))?.apply {
            text = snapshot.mode.label
            setTextColor(palette.secondaryText)
        }
        (container.background as? GradientDrawable)?.setColor(palette.surface)
        container.background = GradientDrawable().apply {
            cornerRadius = dp(container.context, 10).toFloat()
            setColor(palette.surface)
        }
    }

    private fun findClassicSettingsParent(root: View): ViewGroup? {
        val setting = findTextView(root) ?: return null
        var parent = setting.parent as? ViewGroup
        while (parent != null) {
            if (parent !is ScrollView && parent !is android.widget.HorizontalScrollView &&
                !parent.javaClass.name.contains("NestedScrollView") &&
                (parent is LinearLayout || parent.javaClass.name.contains("ConstraintLayout"))
            ) {
                return parent
            }
            parent = parent.parent as? ViewGroup
        }
        return null
    }

    private fun findTextView(view: View): TextView? {
        if (view is TextView && view.visibility == View.VISIBLE &&
            view.text?.toString()?.trim() == "设置"
        ) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTextView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findComposeSettingsHost(root: View): View? {
        if (root.javaClass.name.contains("ComposeView") ||
            root.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView"
        ) {
            if (containsSettingsSemantics(root)) return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findComposeSettingsHost(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun containsSettingsSemantics(host: View): Boolean {
        val rootInfo = runCatching { host.createAccessibilityNodeInfo() }.getOrNull() ?: return false
        val seen = HashSet<Int>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootInfo)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 512) {
            val node = queue.removeFirst()
            val text = listOf(node.text, node.contentDescription)
                .joinToString(" ") { it?.toString().orEmpty() }
            if (text.contains("设置")) return true
            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                val id = System.identityHashCode(child)
                if (seen.add(id)) queue.add(child)
            }
        }
        // Some Compose versions expose virtual children only through the
        // provider. Querying the root virtual id keeps the adapter useful on
        // those versions without reflecting Compose internals.
        val provider = host.accessibilityNodeProvider ?: return false
        return runCatching {
            provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)
                ?.let { it.text?.toString()?.contains("设置") == true }
        }.getOrNull() == true
    }

    private fun addRow(parent: ViewGroup, row: View): Boolean = runCatching {
        parent.addView(row, ViewGroup.LayoutParams(-1, dp(parent.context, 64)))
        true
    }.getOrDefault(false)

    private fun addOverlay(content: ViewGroup, row: View, activity: Activity): Boolean = runCatching {
        val layoutParams = FrameLayout.LayoutParams(
            dp(activity, 176),
            dp(activity, 64),
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            setMargins(0, 0, dp(activity, 16), dp(activity, 24))
        }
        content.addView(row, layoutParams)
        true
    }.getOrDefault(false)

    private fun hideSemanticRow(activity: Activity) {
        semanticRows[activity]?.visibility = View.GONE
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun Activity.isDestroyedCompat(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed
}
