package io.github.uuremotedark.hook

import android.content.res.ColorStateList

/**
 * Maps text selectors through public ColorStateList APIs.  Android does not
 * expose the selector arrays, so stateful selectors are reconstructed from
 * the state combinations used by UU's native controls.  This keeps enabled,
 * pressed, selected, checked and disabled feedback instead of flattening a
 * selector to one colour.
 */
object ColorStateListMapper {
    private val stateSpecs = arrayOf(
        intArrayOf(-android.R.attr.state_enabled),
        intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed),
        intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected),
        intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
        intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused),
        intArrayOf(android.R.attr.state_enabled),
        intArrayOf(),
    )

    fun map(source: ColorStateList, mapper: (Int) -> Int): ColorStateList {
        if (!source.isStateful) {
            val mapped = mapper(source.defaultColor)
            return if (mapped == source.defaultColor) source else ColorStateList.valueOf(mapped)
        }

        val mapped = IntArray(stateSpecs.size) { index ->
            mapper(source.getColorForState(stateSpecs[index], source.defaultColor))
        }
        return ColorStateList(stateSpecs, mapped)
    }
}
