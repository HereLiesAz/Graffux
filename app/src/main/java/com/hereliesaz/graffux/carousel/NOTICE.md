# Forked material3 carousel

These files are copied from AOSP's `androidx.compose.material3.carousel` package, taken from
`material3-android-1.5.0-alpha29-sources.jar` (Google Maven,
`androidx/compose/material3/material3-android/1.5.0-alpha29/`), `commonMain`. They are licensed
under the Apache License, Version 2.0; each file keeps its original AOSP header.

Forked: `Arrangement.kt`, `Carousel.kt`, `CarouselItemScope.kt`, `CarouselState.kt`,
`KeylineList.kt`, `KeylineSnapPosition.kt`, `Keylines.kt`, `Strategy.kt`.
Not forked: `CarouselParallaxScrollEffect.kt`.

Why: material3 keeps `Carousel(keylineList = …)`, `KeylineList` and `keylineListOf` internal, so
the stock `HorizontalCenteredHeroCarousel` could only show small · HERO · small with the end items
off-centre. The fork lets the app supply its own keylines (`centredHeroKeylineList` in
`CarouselKeylines.kt`).

Changes, all marked `Graffux fork`:

- Package renamed to `com.hereliesaz.graffux.carousel`; every `public` declaration made `internal`
  (to the `:app` module).
- `Carousel(...)` gains `pinFocalRange`. When true (horizontal only) it measures its width, pads the
  pager before and after by the space around the focal keyline, and builds `Strategy` with no
  start/end shift steps. So the focal range never moves toward the edges, and the first and last
  items rest in it as every other item does.
- `CarouselPageSize` passes the full container width (pager padding included) to the keyline
  list and `Strategy`. `KeylineSnapPosition` and `CarouselBringIntoViewSpec` subtract the pager's
  start padding, because the pager measures page offsets from after its padding.

Re-sync this package whenever material3 is bumped: copy the new sources over and re-apply the
changes above.
