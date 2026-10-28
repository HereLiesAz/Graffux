package com.hereliesaz.graffitixr.common.model

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.serialization.BlendModeSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import kotlinx.serialization.Serializable
import java.util.UUID

/** The project.json format version this build writes; see [GraffitiProject.formatVersion]. */
const val CURRENT_FORMAT_VERSION = 4

/** What a project.json without a `formatVersion` key is. */
const val LEGACY_FORMAT_VERSION = 1

/**
 * Grouping of legacy editor fields to reduce top-level field count in GraffitiProject.
 */
@Serializable
data class LegacyVisuals(
    val opacity: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val colorBalanceR: Float = 1f,
    val colorBalanceG: Float = 1f,
    val colorBalanceB: Float = 1f,
    val scale: Float = 1f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,

    @Serializable(with = OffsetSerializer::class)
    val offset: Offset = Offset.Zero,

    @Serializable(with = BlendModeSerializer::class)
    val blendMode: BlendMode = BlendMode.SrcOver
)

/**
 * The primary data model representing a user's graffiti project.
 */
@Serializable
data class GraffitiProject(
    /**
     * The project.json format: [CURRENT_FORMAT_VERSION] for every project this build writes. A
     * file without the key is version 1 (before photo adjustment layers); `ProjectManager` reads
     * the key before decoding and migrates (see its `migrateFormat`). Each bump lists what changed:
     * - 2: `OverlayLayer.adjustment`, photo adjustment layers. Nothing in a version 1 file changes.
     * - 3: [documentCrop], the Crop tab's whole-document geometry. Absent before: no crop.
     * - 4: design frames (`OverlayLayer.isFrame`, its fill, radius and clip). A sized group from an
     *   older file (a group given a Frame Size) becomes a frame with no fill and no clip, so it
     *   looks exactly as it did.
     */
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled",
    val created: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),

    @Serializable(with = UriSerializer::class)
    val backgroundImageUri: Uri? = null,

    @Serializable(with = UriSerializer::class)
    val overlayImageUri: Uri? = null,

    @Serializable(with = UriSerializer::class)
    val originalOverlayImageUri: Uri? = null,

    @Serializable(with = UriSerializer::class)
    val thumbnailUri: Uri? = null,

    val targetImageUris: List<@Serializable(with = UriSerializer::class) Uri> = emptyList(),
    val refinementPaths: List<RefinementPath> = emptyList(),

    // Legacy visual state grouped to fix binary compatibility issues with large data classes.
    val legacyVisuals: LegacyVisuals = LegacyVisuals(),

    val drawingPaths: List<List<Pair<Float, Float>>> = emptyList(),

    // Named selections put aside in the editor (Procreate's Save & Load). Part of the project
    // rather than of the session: a region refined with a dozen Add/Remove strokes is real work,
    // and it would be strange for it to survive switching tools but not closing the app.
    val savedSelections: List<SavedSelection> = emptyList(),

    // Unused: nothing reads or writes this any more. Kept only for file compatibility. Removing it
    // would be safe for *this* build's reader (ProjectManager's Json has ignoreUnknownKeys = true, so
    // old project JSON carrying the key still parses), but an older app build reading a project
    // written by this one is only safe because this key keeps a default. Keeping the field costs
    // nothing and avoids reasoning about every reader, so leave it until a format-version bump.
    val progressPercentage: Float = 0f,
    val evolutionImageUris: List<@Serializable(with = UriSerializer::class) Uri> = emptyList(),

    // Multi-layer support
    val layers: List<OverlayLayer> = emptyList(),

    // Shared styles (see StyleOps). The layers above store only style IDs, so the definitions have
    // to live with the project — without them a reopened document would keep the values it last
    // resolved to but lose every link, silently turning shared styles into one-off colours.
    val colorStyles: List<ColorStyle> = emptyList(),
    val textStyles: List<TextStyle> = emptyList(),

    val isRightHanded: Boolean = true,

    // Per-host AzNavRail expansion state (host id -> expanded), so the rail restores exactly as the
    // user left it on reopen. Defaulted for back-compat. Populated via onRailHostExpansionChanged once
    // AzNavRail exposes a per-host expansion-change callback (onExpandedChange, expected 10.11); until
    // then it stays empty and the reactive initiallyExpanded/expandWhen rules drive the rail.
    val railExpansion: Map<String, Boolean> = emptyMap(),

    // Artboard / document dimensions in pixels — the fixed output size the design targets (social
    // preset, print, or custom). Defaulted to a 1080² square for back-compat with projects saved
    // before the document model existed.
    val documentWidth: Int = 1080,
    val documentHeight: Int = 1080,

    // The canvas surface (SubstratePreset.id: "smooth", "cold_press", "canvas_weave",
    // "brick_concrete") every stamp stroke in this project deposits against. Stored as the stable id
    // string so the file format doesn't depend on the engine's enum; projects saved before substrates
    // existed (and any id this build doesn't know) open as Smooth, their historical look.
    val canvasSubstrateId: String = "smooth",

    // The 3D model loaded into the project, and the texture painted onto it. Both are copied into
    // the project folder rather than referenced where the user picked them: a document picker URI
    // is a grant that expires, so a project reopened next week would find its own model gone. Being
    // in the folder also means both ride along in a `.fux` and reopen intact on another device.
    // All null on projects without a model, which is most of them.
    val modelPath: String? = null,
    val modelName: String? = null,
    val modelTexturePath: String? = null,

    // The Crop tab's crop and geometry (format version 3). Non-destructive: the layers keep every
    // pixel; the canvas and the export draw them through this.
    val documentCrop: com.hereliesaz.graffitixr.common.photo.DocumentCrop =
        com.hereliesaz.graffitixr.common.photo.DocumentCrop(),
)
