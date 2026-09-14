package com.hereliesaz.graffitixr.common.azphalt

/**
 * Versioned, renderer-independent semantic boundary between an artist-facing medium choice and the
 * coefficients/channels the material engine consumes.
 *
 * A profile deliberately does NOT contain a paint colour. Colour remains stroke/brush intent;
 * selecting a red or white swatch must never silently select different viscosity, drying, pickup,
 * or pigment semantics. Named pigment libraries may eventually bundle optical/material metadata,
 * but they must opt into that explicitly above this boundary.
 *
 * [id] is a stable machine identifier suitable for future persistence (for example
 * `legacy-dry`, `heavy-oil-v1`, or another product-defined identifier). [version] versions the
 * profile semantics rather than the display name: if a shipped profile is retuned incompatibly,
 * increment its version so recorded strokes can continue resolving the coefficients they used.
 *
 * The current engine has no product media catalogue yet. [LEGACY_DRY] is intentionally the only
 * built-in profile here; Heavy Oil/Acrylic/etc. belong in a later, artist-tuned catalogue rather
 * than being guessed into the core model before reference-stroke calibration exists.
 */
data class PaintMediaProfile(
    val id: String,
    val version: Int = 1,
    val medium: PaintMedium = PaintMedium(),
    val channels: MaterialChannels = MaterialChannels(),
    /** Initial stroke-local reservoir fill. The evolving value lives in [BrushReservoirState]. */
    val initialLoad: Float = 1f,
    /** Initial carried vehicle/wetness fraction; canvas wetness is a separate future channel. */
    val initialWetness: Float = 0f,
) {
    init {
        require(id.isNotBlank()) { "PaintMediaProfile.id must not be blank" }
        require(version >= 1) { "PaintMediaProfile.version must be at least 1" }
    }

    /**
     * Sanitizes artist/configuration coefficients without changing [id] or [version]. Stable
     * identity is part of replay/persistence semantics and must never be repaired implicitly.
     */
    fun sanitized(): PaintMediaProfile = copy(
        medium = medium.sanitized(),
        initialLoad = initialLoad.coerceIn(0f, 1f),
        initialWetness = initialWetness.coerceIn(0f, 1f),
    )

    /**
     * Creates the mutable-in-concept stroke reservoir from this profile and an explicitly supplied
     * colour. Keeping colour as an argument is the architectural guardrail behind "colour is not
     * medium": the same profile can start with any selected colour without changing its physics.
     */
    fun initialReservoir(carriedColor: MaterialColor): BrushReservoirState {
        val profile = sanitized()
        return BrushReservoirState(
            load = profile.initialLoad,
            wetness = profile.initialWetness,
            carriedColor = carriedColor.clamped(),
        )
    }

    /**
     * True when selecting this profile requires behavior beyond the historical dry colour path.
     * Evaluate the sanitized coefficients so malformed imported/user values do not accidentally
     * allocate material state before the same values are clamped back to compatibility defaults.
     */
    val usesMaterialPath: Boolean
        get() {
            val profile = sanitized()
            return profile.medium.usesMaterialPath || !profile.channels.isColorOnly ||
                profile.initialLoad != 1f || profile.initialWetness != 0f
        }

    companion object {
        /** Exact compatibility profile: colour-only, full load, dry reservoir, legacy RGB mixing. */
        val LEGACY_DRY = PaintMediaProfile(id = "legacy-dry")
    }
}
