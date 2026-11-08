package com.hereliesaz.graffitixr.common.model

/**
 * How much a popup matters, which decides where it sits and whether it may leave by itself.
 *
 * IMPORTANT popups are guidance the user needs to take the next step (a setup or instruction prompt)
 * or a decision (anything that offers an action). They sit in the centre of the screen and stay until
 * the user acts: never a timeout. TRANSIENT popups only report something (a refusal, a confirmation
 * with nothing to do); they stay at the top, over the artwork, and leave after [PopupPolicy.autoDismissMillis].
 */
enum class PopupImportance { IMPORTANT, TRANSIENT }

object PopupPolicy {
    const val MIN_MS = 4_000L
    const val MAX_MS = 12_000L
    private const val BASE_MS = 2_000L
    private const val PER_CHAR_MS = 60L

    /** IMPORTANT when the popup guides the user's next step or offers an action; else TRANSIENT. */
    fun importance(guidesNextStep: Boolean, offersAction: Boolean): PopupImportance =
        if (guidesNextStep || offersAction) PopupImportance.IMPORTANT else PopupImportance.TRANSIENT

    /**
     * How long a transient popup of [textLength] characters stays, in proportion to how long it takes to
     * read, within [MIN_MS]..[MAX_MS]; null (it stays until dismissed) when it is [actionable], since an
     * offered action must not vanish before the user can reach it.
     */
    fun autoDismissMillis(textLength: Int, actionable: Boolean): Long? =
        if (actionable) null else (BASE_MS + PER_CHAR_MS * textLength).coerceIn(MIN_MS, MAX_MS)
}

/**
 * The Draw-surface notice's weight: the confirmations carry the "Draw side view" / "Draw top view"
 * decision and the hint to use it, so they are IMPORTANT and stay until the user acts; the refusals
 * and the too-short line only explain, so they are TRANSIENT.
 */
val SurfaceDrawNotice.importance: PopupImportance
    get() = when (this) {
        SurfaceDrawNotice.DONE, SurfaceDrawNotice.VIEW_DONE -> PopupImportance.IMPORTANT
        else -> PopupImportance.TRANSIENT
    }

/** The Draw-surface prompt's weight: armed it is the instruction (IMPORTANT); unarmed it is its notice's. */
fun surfacePromptImportance(armed: Boolean, notice: SurfaceDrawNotice?): PopupImportance =
    if (armed) PopupImportance.IMPORTANT else notice?.importance ?: PopupImportance.TRANSIENT
