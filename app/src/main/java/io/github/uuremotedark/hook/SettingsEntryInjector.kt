package io.github.uuremotedark.hook

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeProvider
import io.github.uuremotedark.core.ThemeMode
import io.github.uuremotedark.core.ThemeSnapshot
import io.github.uuremotedark.core.UuPalettes
import io.github.uuremotedark.R
import java.util.Collections
import java.util.IdentityHashMap
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
    private const val GROUP_TAG = "$INJECTED_TAG.group"
    private val classicRows = Collections.synchronizedMap(WeakHashMap<Activity, View>())
    private val semanticRows = Collections.synchronizedMap(WeakHashMap<Activity, View>())
    private val lastComposeAnchor = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

    fun inject(
        activity: Activity,
        snapshot: ThemeSnapshot,
        log: (String) -> Unit,
        onModeSelected: (ThemeMode) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyedCompat()) return

        val root = activity.window.decorView
        val classicParent = findClassicSettingsParent(root)
        if (classicParent != null) {
            hideSemanticRow(activity)
            val row = classicRows[activity]
            if (row == null || row.parent !== classicParent) {
                row?.let { (it.parent as? ViewGroup)?.removeView(it) }
                val created = createRow(activity, snapshot, onModeSelected)
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
        val previousAnchor = lastComposeAnchor.put(activity, composeSettingsVisible)
        if (previousAnchor != composeSettingsVisible) {
            log(
                "$TAG compose settings anchor=${composeSettingsVisible} " +
                    "activity=${activity.javaClass.name}",
            )
        }
        val overlay = semanticRows[activity] ?: createSemanticOverlay(activity, onModeSelected).also {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            if (content == null || !addOverlay(content, it, activity)) return
            semanticRows[activity] = it
            log("$TAG prepared Compose settings overlay")
        }
        overlay.visibility = if (composeSettingsVisible) View.VISIBLE else View.GONE
        update(overlay, snapshot)
    }

    private fun createRow(
        activity: Activity,
        snapshot: ThemeSnapshot,
        onModeSelected: (ThemeMode) -> Unit,
    ): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = INJECTED_TAG
            setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 8))
            setTag(R.id.settings_mode_listener, onModeSelected)
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
            addView(RadioGroup(activity).apply {
                tag = GROUP_TAG
                setTag(R.id.settings_mode_listener, onModeSelected)
                orientation = RadioGroup.HORIZONTAL
                setOnCheckedChangeListener { _, checkedId ->
                    val listener = getTag(R.id.settings_mode_listener) as? ((ThemeMode) -> Unit)
                    when (checkedId) {
                        R.id.settings_follow_system -> listener?.invoke(ThemeMode.FOLLOW_SYSTEM)
                        R.id.settings_dark -> listener?.invoke(ThemeMode.DARK)
                    }
                }
                addView(RadioButton(activity).apply {
                    id = R.id.settings_follow_system
                    text = ThemeMode.FOLLOW_SYSTEM.label
                    textSize = 14f
                    setPadding(0, dp(activity, 2), dp(activity, 12), 0)
                    layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
                })
                addView(RadioButton(activity).apply {
                    id = R.id.settings_dark
                    text = ThemeMode.DARK.label
                    textSize = 14f
                    setPadding(0, dp(activity, 2), 0, 0)
                    layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
                })
            }, LinearLayout.LayoutParams(-1, -2))
            update(this, snapshot)
        }

    private fun createSemanticOverlay(
        activity: Activity,
        onModeSelected: (ThemeMode) -> Unit,
    ): View = createRow(activity, ThemeSnapshot(
        mode = ThemeMode.FOLLOW_SYSTEM,
        uiMode = activity.resources.configuration.uiMode,
        isDark = false,
        generation = 0L,
    ), onModeSelected).apply {
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
        container.findViewWithTag<RadioGroup>(GROUP_TAG)?.let { group ->
            group.setOnCheckedChangeListener(null)
            group.check(if (snapshot.mode == ThemeMode.DARK) R.id.settings_dark else R.id.settings_follow_system)
            group.setOnCheckedChangeListener { _, checkedId ->
                val listener = group.getTag(R.id.settings_mode_listener) as? ((ThemeMode) -> Unit)
                when (checkedId) {
                    R.id.settings_follow_system -> listener?.invoke(ThemeMode.FOLLOW_SYSTEM)
                    R.id.settings_dark -> listener?.invoke(ThemeMode.DARK)
                }
            }
            for (index in 0 until group.childCount) {
                (group.getChildAt(index) as? RadioButton)?.setTextColor(palette.primaryText)
            }
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
            view.text?.toString()?.trim() in setOf("设置", "Settings")
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
        inspectComposeSemantics(host)?.let { return it }
        val rootInfo = runCatching { host.createAccessibilityNodeInfo() }.getOrNull() ?: return false
        val seen = HashSet<Int>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootInfo)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 512) {
            val node = queue.removeFirst()
            val text = listOf(node.text, node.contentDescription)
                .joinToString(" ") { it?.toString().orEmpty() }
            if (text.contains("设置") || text.contains("Settings")) return true
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
                ?.let {
                    val text = listOf(it.text, it.contentDescription)
                        .joinToString(" ") { value -> value?.toString().orEmpty() }
                    text.contains("设置") || text.contains("Settings")
                }
        }.getOrNull() == true
    }

    /**
     * Compose's accessibility bridge is often disabled in production builds,
     * so Android's AccessibilityNodeInfo tree can be empty. UU 4.35 bundles
     * the public AndroidComposeView.getSemanticsOwner() API; reading only the
     * semantics text and the clickable flag lets us identify the settings
     * destination without relying on an obfuscated screen class.
     */
    private fun inspectComposeSemantics(host: View): Boolean? = runCatching {
        val owner = host.javaClass.getMethod("getSemanticsOwner").invoke(host) ?: return@runCatching false
        val root = owner.javaClass.getMethod("a").invoke(owner) ?: return@runCatching false
        val nodeQueue = ArrayDeque<Any>()
        val seen = IdentityHashMap<Any, Boolean>()
        nodeQueue.add(root)
        var visited = 0
        while (nodeQueue.isNotEmpty() && visited++ < 1024) {
            val node = nodeQueue.removeFirst()
            if (seen.put(node, true) != null) continue
            val config = node.javaClass.getMethod("i").invoke(node)
            val description = config?.toString().orEmpty()
            val isSettings = description.contains("设置") || description.contains("Settings")
            if (isSettings && !description.contains("OnClick")) return@runCatching true
            val children = node.javaClass.getMethod(
                "g",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).invoke(node, false, false, false) as? Iterable<*>
            children?.forEach { child -> if (child != null) nodeQueue.add(child) }
        }
        false
    }.getOrNull()

    private fun addRow(parent: ViewGroup, row: View): Boolean = runCatching {
        parent.addView(row, ViewGroup.LayoutParams(-1, dp(parent.context, 136)))
        true
    }.getOrDefault(false)

    private fun addOverlay(content: ViewGroup, row: View, activity: Activity): Boolean = runCatching {
        val layoutParams = FrameLayout.LayoutParams(
            -1,
            dp(activity, 136),
            Gravity.TOP,
        ).apply {
            setMargins(dp(activity, 16), dp(activity, 72), dp(activity, 16), 0)
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
