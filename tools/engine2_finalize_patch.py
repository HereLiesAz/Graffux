from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

old = 'import com.hereliesaz.graffitixr.common.azphalt.ImpastoEngine\n'
new = old + 'import com.hereliesaz.graffitixr.common.azphalt.ImpastoRegionShader\n'
assert old in s and 'ImpastoRegionShader' not in s
s = s.replace(old, new, 1)

old = '            val needsDynamicDabs = stampBrush.dynamics.isNotEmpty() || hasMaskDynamics || stampBrush.taper.isActive()\n'
new = '            val needsDynamicDabs = stampBrush.dynamics.isNotEmpty() || hasMaskDynamics || stampBrush.taper.isActive() || stampBrush.blot.isActive()\n'
assert old in s
s = s.replace(old, new, 1)

old = '''                            stampRenderedMovementDabs.addAll(newDabs)
                    // The stroke this batch was queued for may already be over by the time its turn
                    // comes up (a fast tap-lift-then-redown can win the race) -- onStrokeStart/
                    // clearTransientStrokeState detect that themselves for the engine (via
                    // stampLiveLock's identity check below), but `canvas`/`work`/heightMap/
                    // shadedBitmap have no such guard, so painting into them here would be silently
                    // wasted work at best. Bailing early also means a whole tail of superseded
                    // batches drains near-instantly instead of actually running their GPU/CPU work.
                    if (strokeGeneration != strokeGen || strokeLayerId != strokeLayerIdSnapshot) return@launch
'''
new = '''                    // A previous stroke's native call can finish after a fast lift/redown. Check
                    // generation BEFORE publishing this batch into shared Engine 2 state so that
                    // stale workers cannot contaminate the next stroke's rendered prefix.
                    if (strokeGeneration != strokeGen || strokeLayerId != strokeLayerIdSnapshot) return@launch
                    stampRenderedMovementDabs.addAll(newDabs)
'''
assert old in s, 'stale-worker guard anchor changed'
s = s.replace(old, new, 1)

old = '''                            if (region != null && !region.isEmpty) {
                                val rawPixels = IntArray(work.width * work.height)
                                work.getPixels(rawPixels, 0, work.width, 0, 0, work.width, work.height)
                                val outPixels = IntArray(work.width * work.height)
                                shadedBitmap.getPixels(outPixels, 0, work.width, 0, 0, work.width, work.height)
                                ImpastoEngine.shadeInto(
                                    outPixels, rawPixels, heightMap, work.width, work.height,
                                    region.left, region.top, region.right, region.bottom,
                                    IMPASTO_LIGHT_AZIMUTH_DEG, IMPASTO_LIGHT_ELEVATION_DEG, IMPASTO_LIGHT_STRENGTH,
                                )
                                shadedBitmap.setPixels(outPixels, 0, work.width, 0, 0, work.width, work.height)
                            }
'''
new = '''                            if (region != null && !region.isEmpty) {
                                val regionWidth = region.right - region.left
                                val regionHeight = region.bottom - region.top
                                val rawRegion = IntArray(regionWidth * regionHeight)
                                work.getPixels(
                                    rawRegion, 0, regionWidth,
                                    region.left, region.top, regionWidth, regionHeight,
                                )
                                val shadedRegion = ImpastoRegionShader.shade(
                                    rawRegion, heightMap, work.width, work.height,
                                    region.left, region.top, regionWidth, regionHeight,
                                    IMPASTO_LIGHT_AZIMUTH_DEG, IMPASTO_LIGHT_ELEVATION_DEG,
                                    IMPASTO_LIGHT_STRENGTH,
                                )
                                shadedBitmap.setPixels(
                                    shadedRegion, 0, regionWidth,
                                    region.left, region.top, regionWidth, regionHeight,
                                )
                            }
'''
assert old in s, 'impasto region block changed'
s = s.replace(old, new, 1)

p.write_text(s)
