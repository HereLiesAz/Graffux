package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.Contribution
import com.hereliesaz.graffitixr.data.azphalt.InstalledExtension
import com.hereliesaz.graffitixr.design.GraffuxIcons

/** Which manifest slot an installed effect came from; also its hero-card family label. */
internal enum class ExtensionEffectKind(val label: String, val keyPrefix: String) {
    FILTER("Filter", "ext-filter"),
    TOOL("Tool", "ext-tool"),
    LUT("LUT", "ext-lut"),
}

/**
 * One installed azphalt effect for the Effects page: a declared `contributes.filters`/`.tools`
 * entry ([contributionId] set), or a LUT extension as a whole ([contributionId] null).
 */
internal data class ExtensionEffect(
    val extensionId: String,
    val extensionName: String,
    val kind: ExtensionEffectKind,
    val contributionId: String?,
    val name: String,
    val description: String? = null,
)

/** The stable, favoritable key of an installed effect: `ext-filter:<extId>:<filterId>` and so on. */
internal fun extensionEffectKey(effect: ExtensionEffect): String =
    listOfNotNull(effect.kind.keyPrefix, effect.extensionId, effect.contributionId).joinToString(":")

/**
 * The Effects page's installed entries, from the Extensions panel's own list (`vm.installedExtensions`):
 * per extension, each declared filter, then each declared tool, then the extension's LUT when
 * [hasUsableLut] says it carries one. Commands are not effects and are left to the panel.
 */
internal fun extensionEffectsOf(
    extensions: List<InstalledExtension>,
    hasUsableLut: (InstalledExtension) -> Boolean,
): List<ExtensionEffect> = extensions.flatMap { ext ->
    val m = ext.manifest
    fun of(kind: ExtensionEffectKind, c: Contribution) =
        ExtensionEffect(ext.id, m.name, kind, c.id, c.name, m.description)
    val contributes = m.contributes
    val lut = if (hasUsableLut(ext)) {
        ExtensionEffect(ext.id, m.name, ExtensionEffectKind.LUT, null, m.name, m.description)
    } else {
        null
    }
    contributes?.filters.orEmpty().map { of(ExtensionEffectKind.FILTER, it) } +
        contributes?.tools.orEmpty().map { of(ExtensionEffectKind.TOOL, it) } +
        listOfNotNull(lut)
}

/**
 * An installed effect's card. Never `selected`: filters, extension tools and LUTs are one-shot runs,
 * not something left in hand, so there is no "current" one to light up.
 */
internal fun extensionEffectEntry(effect: ExtensionEffect): CarouselEntry {
    val contributionId = effect.contributionId
    val action = if (effect.kind == ExtensionEffectKind.LUT || contributionId == null) {
        CarouselAction.ExtensionLut(effect.extensionId)
    } else {
        CarouselAction.ExtensionContribution(effect.extensionId, contributionId, effect.kind)
    }
    return CarouselEntry(
        key = extensionEffectKey(effect), label = effect.name,
        action = action, selected = false,
        icon = if (effect.kind == ExtensionEffectKind.LUT) GraffuxIcons.ColorLookup else GraffuxIcons.FilterGallery,
        extensionEffect = effect,
    )
}
