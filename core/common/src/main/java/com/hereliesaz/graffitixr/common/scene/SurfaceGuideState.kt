package com.hereliesaz.graffitixr.common.scene

/** Where a guided step stands (spec/guided-surface.md, section 4): nothing, a surface already there, or a draft. */
enum class GuideStepState { NOT_DRAWN, EXISTING, DRAFT }

/** How a draft came to be: drawn (or staged) by the user, or the second mirrored in for a skipped third. */
enum class GuideMark { DRAWN, MIRRORED }

/** A surface held in the guide until Done: [flatSheet] marks the predefined sheet chosen on step 1. */
data class GuideDraft(val profile: SurfaceProfile, val mark: GuideMark, val flatSheet: Boolean = false)

/** How a guide ended: with nothing made, abandoned, or with surfaces to commit ([SurfaceGuideState.commits]). */
enum class GuideEnd { NOTHING, CANCELLED, COMMIT }

/** The state after an action, and how the flow ended if it did. A state that ended is never used again. */
data class GuideStep(val state: SurfaceGuideState, val end: GuideEnd? = null)

/**
 * The guided surface flow's whole state (spec/guided-surface.md, sections 4 and 6): an in-memory reducer, never saved,
 * never in history, never in co-op. It holds the [target], the surfaces the group had when the guide opened
 * ([existing]), the drafts the user made, and the step. Every action is a pure function returning the next state, so
 * nothing here touches a document: the view-model commits [commits] on Done, and Cancel changes nothing because nothing
 * was ever written.
 *
 * The steps are strictly dependent: step 1 is the first surface, 2 the second (only once a first exists), 3 the third
 * (only once a second exists). Arriving on a step does not draw, skip or complete it.
 */
@Suppress("TooManyFunctions")
data class SurfaceGuideState(
    val target: GuideTarget,
    /** The group's surface when the guide opened (it may be a group already carrying surfaces), or null. */
    val existing: SurfaceProfile? = null,
    /** The first perspective the user chose or the camera showed; fixed once a first surface exists. */
    val firstView: SurfaceView = SurfaceView.FRONT,
    val step: Int = 1,
    val tool: GuideTool = GuideTool.ELLIPSE,
    val drafts: Map<SurfaceView, GuideDraft> = emptyMap(),
    /** A stroke is armed for the current step (Draw, Edit or Redraw was chosen). */
    val capturing: Boolean = false,
    /** The id of the group made around a layer to wrap, decided once so every captured outline sees the same chain. */
    val newGroupId: String? = null,
    /** The group's pose is baked away when its first surface is committed (see `rebasedForNewSurface`). */
    val bakedPose: Boolean = false,
) {
    /** The perspective of the first surface: the existing base, else the chosen one. */
    val first: SurfaceView get() = existing?.baseView ?: firstView

    /** The three perspectives in step order. */
    val views: List<SurfaceView> get() = SurfaceGuide.order(first)

    /** The perspective of step [n] (1 to 3). */
    fun viewOf(n: Int): SurfaceView = views[n - 1]

    /** The perspective the current step draws. */
    val currentView: SurfaceView get() = viewOf(step)

    fun stateOf(n: Int): GuideStepState {
        val v = viewOf(n)
        return when {
            drafts[v] != null -> GuideStepState.DRAFT
            existing?.view(v)?.isValid == true -> GuideStepState.EXISTING
            else -> GuideStepState.NOT_DRAWN
        }
    }

    fun hasSurface(n: Int): Boolean = stateOf(n) != GuideStepState.NOT_DRAWN

    /** Step [n] is presented only once every step before it has a surface (strict dependency). */
    fun isPresented(n: Int): Boolean = (1 until n).all(::hasSurface)

    /** Any surface drawn this session or kept from the group: Done has something to say. */
    val hasAnySurface: Boolean get() = (1..STEPS).any(::hasSurface)

    /** Next needs a surface on this step (the next would have nothing to bound) and a step after it. */
    val canNext: Boolean get() = hasSurface(step) && step < STEPS

    /** Back is disabled on the first step. */
    val canBack: Boolean get() = step > 1

    /** The first perspective may still be chosen: no draft and no surface the group already has. */
    val canChooseFirstView: Boolean get() = step == 1 && drafts.isEmpty() && existing == null

    /** The flat sheet is offered on step 1 only, for the Z-view, for a group with no surface and no draft. */
    val canUseFlatSheet: Boolean
        get() = step == 1 && existing == null && drafts.isEmpty() && firstView == SurfaceView.FRONT

    /** The first surface is drawn as an outline that closes, so it will be a solid; false for an open line or none. */
    val firstIsClosed: Boolean
        get() = (drafts[first]?.profile ?: existing?.view(first))?.let(SurfaceGuide::isClosed) == true

    fun chooseFirstView(view: SurfaceView): SurfaceGuideState =
        if (canChooseFirstView) copy(firstView = view, capturing = false) else this

    fun chooseTool(next: GuideTool): SurfaceGuideState = copy(tool = next)

    /** Draw, Edit or Redraw: the next stroke is this step's outline. */
    fun beginCapture(): SurfaceGuideState = if (isPresented(step)) copy(capturing = true) else this

    fun endCapture(): SurfaceGuideState = copy(capturing = false)

    /** The step's outline was drawn: it becomes (or replaces) this perspective's draft; a later draft is kept. */
    fun drawn(profile: SurfaceProfile, baked: Boolean = bakedPose): SurfaceGuideState =
        copy(
            drafts = drafts + (currentView to GuideDraft(profile, GuideMark.DRAWN)),
            capturing = false,
            bakedPose = baked,
        )

    /** "Use a flat sheet" on step 1: the predefined sheet is the first surface's draft, an open one. */
    fun flatSheet(profile: SurfaceProfile, baked: Boolean = bakedPose): SurfaceGuideState =
        if (!canUseFlatSheet) this else copy(
            drafts = mapOf(first to GuideDraft(profile, GuideMark.DRAWN, flatSheet = true)),
            capturing = false,
            bakedPose = baked,
        )

    /** Keep or Next: the step has a surface, so move on; Keep on the third is Done. Nothing on an empty step. */
    fun keep(): GuideStep = when {
        !hasSurface(step) -> GuideStep(this)
        step < STEPS -> GuideStep(copy(step = step + 1, capturing = false))
        else -> done()
    }

    /** Next is Keep, and is disabled (does nothing) on a step with nothing drawn. */
    fun next(): GuideStep = if (canNext) keep() else GuideStep(this)

    fun back(): GuideStep = GuideStep(if (canBack) copy(step = step - 1, capturing = false) else this)

    /**
     * Skip on a step with nothing drawn: on step 1 it ends the flow with nothing created; on step 2 it ends it with
     * just the first surface; on step 3 it gives the second, mirrored, as the third. On a step that has a surface it
     * does nothing (that is Keep).
     */
    fun skip(): GuideStep = when {
        hasSurface(step) -> GuideStep(this)
        step == 1 -> GuideStep(this, GuideEnd.NOTHING)
        step == 2 -> done()
        else -> mirroredThird()?.let { done(it) } ?: done()
    }

    /** The second surface mirrored into the third's perspective, when both a first and a second exist; else null. */
    private fun mirroredThird(): SurfaceGuideState? {
        val second = drafts[viewOf(2)]?.profile ?: existing?.view(viewOf(2))
        val firstProfile = drafts[first]?.profile ?: existing?.view(first)
        if (second == null || firstProfile == null) return null
        val third = SurfaceGuide.mirrored(second, firstProfile, first, viewOf(2))
        return copy(drafts = drafts + (viewOf(STEPS) to GuideDraft(third, GuideMark.MIRRORED)), capturing = false)
    }

    private fun done(with: SurfaceGuideState = this): GuideStep =
        if (with.drafts.isEmpty()) GuideStep(with.copy(capturing = false), GuideEnd.NOTHING)
        else GuideStep(with.copy(capturing = false), GuideEnd.COMMIT)

    /** Done: commits what is drawn; with nothing drawn it is Cancel's twin. */
    fun finish(): GuideStep = if (hasAnySurface) done() else GuideStep(this, GuideEnd.NOTHING)

    /** Cancel: every draft is discarded and nothing changes. */
    fun cancel(): GuideStep = GuideStep(copy(drafts = emptyMap(), capturing = false), GuideEnd.CANCELLED)

    /**
     * The surface after each commit, in order: the first surface sets the base (and `solid` for a closed outline),
     * every later one is [SurfaceProfile.withView]; a step that changes nothing adds none, so one history entry each.
     */
    fun commits(): List<SurfaceProfile> {
        val out = ArrayList<SurfaceProfile>()
        var current = existing
        for (view in views) {
            val draft = drafts[view] ?: continue
            val before = current
            val next = if (before == null) rootOf(draft.profile, view) else before.withView(view, draft.profile)
            val settled = if (before != null && view == before.baseView) {
                next.copy(solid = SurfaceGuide.isClosed(next))
            } else {
                next
            }
            if (settled != before) {
                out += settled
                current = settled
            }
        }
        return out
    }

    private fun rootOf(profile: SurfaceProfile, view: SurfaceView): SurfaceProfile =
        profile.copy(
            side = null, top = null, front = null,
            base = view.takeIf { it != SurfaceView.FRONT },
            solid = SurfaceGuide.isClosed(profile),
        )

    companion object {
        /** The number of surfaces the guide walks through. */
        const val STEPS = 3
    }
}
