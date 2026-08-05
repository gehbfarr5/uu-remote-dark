package io.github.uuremotedark.hook

import android.content.res.Resources
import android.content.res.ColorStateList
import io.github.uuremotedark.core.ColorMath
import io.github.uuremotedark.core.ThemePalette
import io.github.uuremotedark.uu.TargetInfo
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Conservative resource mapping for the known UU adapter.
 *
 * Generic names such as `white` are intentionally excluded: they are often
 * used by icons and remote thumbnails. The map grows only from verified UU
 * resource tokens discovered during page coverage.
 */
object ResourcePaletteMapper {
    private val namesByResources = Collections.synchronizedMap(
        WeakHashMap<Resources, ConcurrentHashMap<Int, String>>(),
    )

    private val rootBackgroundTokens = setOf(
        "bg100",
        "background",
        "color_background",
        "custom_1",
        "black_bg_100",
        "black_bg_1d",
        "design_default_color_background",
        "material_grey_50",
        "background_material_light",
    )

    private val surfaceTokens = setOf(
        "bg200",
        "color_surface",
        "surface",
        "surface_color",
        "custom_2",
        "custom_3",
        "black_bg_200",
        "grey6",
        "grey7",
        "gray2",
        "gray3",
        "gray7",
        "design_default_color_surface",
        "material_grey_50",
        "material_grey_100",
        "brand6_2",
        "item_selected_grey8",
    )

    private val elevatedSurfaceTokens = setOf(
        "bg300",
        "bg400",
        "custom_4",
        "black_bg_300",
        "black_bg_400",
        "black_bg_500",
        "black_bg_600",
        "bg_press_1",
        "bg_disable_1",
        "item_pressed_grey3",
        "item_pressed_grey7",
    )

    private val secondaryTextTokens = setOf(
        "text_desc",
        "text_secondary",
        "text_toolbar_desc_selector",
        "text_tab_selected",
        "grey2",
        "grey2_5",
        "grey3",
        "grey4",
        "grey5",
        "text_secondary_and_tertiary",
        "design_default_color_on_background",
        "gray_50",
        "gray_60",
    )

    private val primaryTextTokens = setOf(
        "text_content",
        "text_primary",
        "text_toolbar_title_selector",
        "text_black",
        "grey1",
        "primary_text",
        "n100_black_black",
    )

    private val brandTokens = setOf(
        "brand1",
        "brand2",
        "brand3",
        "brand6",
        "brand7",
        "brand1_2",
        "brand2_font",
        "brand5",
        "brand7",
        "primary",
        "secondary",
        "text_link",
        "text_link_pressed",
        "bg_tab_selected",
        "color_primary",
    )

    fun map(resources: Resources, resourceId: Int, original: Int, palette: ThemePalette): Int {
        val packageName = runCatching { resources.getResourcePackageName(resourceId) }.getOrNull()
        if (packageName != TargetInfo.PACKAGE_NAME) return original
        val typeName = runCatching { resources.getResourceTypeName(resourceId) }.getOrNull()
        if (typeName != "color") return original
        val entryName = namesByResources
            .getOrPut(resources) { ConcurrentHashMap() }
            .getOrPut(resourceId) {
                resources.getResourceEntryName(resourceId).lowercase()
            }

        val mapped = when {
            entryName in rootBackgroundTokens -> palette.background
            entryName in surfaceTokens -> palette.surface
            entryName in elevatedSurfaceTokens -> palette.elevatedSurface
            entryName in secondaryTextTokens && ColorMath.isMostlyDark(original) -> palette.secondaryText
            entryName in primaryTextTokens && ColorMath.isMostlyDark(original) -> palette.primaryText
            entryName in brandTokens && ColorMath.isBrandBlue(original) -> palette.brand
            else -> original
        }
        return if (mapped == original) original else ColorMath.withAlpha(mapped, original ushr 24)
    }

    fun mapStateList(
        resources: Resources,
        resourceId: Int,
        original: ColorStateList,
        palette: ThemePalette,
    ): ColorStateList = ColorStateListMapper.map(original) { color ->
        map(resources, resourceId, color, palette)
    }
}
