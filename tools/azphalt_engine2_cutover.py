from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

old = '''import com.hereliesaz.graffitixr.common.azphalt.IncrementalStaticDabGenerator
'''
new = '''import com.hereliesaz.graffitixr.common.azphalt.IncrementalStaticDabGenerator
import com.hereliesaz.graffitixr.common.azphalt.IncrementalDynamicDabGenerator
import com.hereliesaz.graffitixr.common.azphalt.IncrementalAirbrushGenerator
'''
assert old in s and 'IncrementalDynamicDabGenerator' not in s
s = s.replace(old, new, 1)

old = '''    private var stampStaticDabGenerator: IncrementalStaticDabGenerator? = null
    private val stampGeneratedMovementDabs = ArrayList<Dab>()
    private var stampStaticConsumedPointCount: Int = 0
    private var stampGpuEngine: VulkanStampEngine? = null
'''
new = '''    private var stampStaticDabGenerator: IncrementalStaticDabGenerator? = null
    private var stampDynamicDabGenerator: IncrementalDynamicDabGenerator? = null
    private val stampGeneratedMovementDabs = ArrayList<Dab>()
    private var stampMovementConsumedSampleCount: Int = 0
    private var stampAirbrushGenerator: IncrementalAirbrushGenerator? = null
    private val stampGeneratedHeldDabs = ArrayList<Dab>()
    private var stampAirbrushConsumedSampleCount: Int = 0
    private var stampGpuEngine: VulkanStampEngine? = null
'''
assert old in s, 'generator fields changed'
s = s.replace(old, new, 1)

old = '''            val dabs = if (needsDynamicDabs && mappedSamples.isNotEmpty()) {
                // Dynamic/taper brushes still use the canonical resolver for now; unlike the old
                // render path, their output is consumed by one bounded worker so GPU work cannot
                // queue behind the pointer. A stateful dynamic resolver is the next Engine 2 cut.
                BrushStamps.dynamicDabs(mappedSamples, diameterPx, stampBrush, stampSeed)
            } else {
                // Static brushes are fully incremental: process only mapped points this generator
                // has not seen. No BrushStamps.place()/dabs() walk over the growing stroke prefix.
                var generator = stampStaticDabGenerator
                if (generator == null) {
                    generator = IncrementalStaticDabGenerator(diameterPx, stampBrush, stampSeed)
                    stampStaticDabGenerator = generator
                }
                val pointCount = stampMappedPoints.size / 2
                while (stampStaticConsumedPointCount < pointCount) {
                    val index = stampStaticConsumedPointCount * 2
                    stampGeneratedMovementDabs.addAll(
                        generator.appendPoint(stampMappedPoints[index], stampMappedPoints[index + 1])
                    )
                    stampStaticConsumedPointCount++
                }
                stampGeneratedMovementDabs
            }
'''
new = '''            val dabs = if (mappedSamples.isNotEmpty()) {
                if (needsDynamicDabs) {
                    var generator = stampDynamicDabGenerator
                    if (generator == null) {
                        generator = IncrementalDynamicDabGenerator(diameterPx, stampBrush, stampSeed)
                        stampDynamicDabGenerator = generator
                    }
                    while (stampMovementConsumedSampleCount < mappedSamples.size) {
                        stampGeneratedMovementDabs.addAll(
                            generator.append(mappedSamples[stampMovementConsumedSampleCount])
                        )
                        stampMovementConsumedSampleCount++
                    }
                } else {
                    var generator = stampStaticDabGenerator
                    if (generator == null) {
                        generator = IncrementalStaticDabGenerator(diameterPx, stampBrush, stampSeed)
                        stampStaticDabGenerator = generator
                    }
                    val pointCount = stampMappedPoints.size / 2
                    while (stampMovementConsumedSampleCount < pointCount) {
                        val index = stampMovementConsumedSampleCount * 2
                        stampGeneratedMovementDabs.addAll(
                            generator.appendPoint(stampMappedPoints[index], stampMappedPoints[index + 1])
                        )
                        stampMovementConsumedSampleCount++
                    }
                }
                stampGeneratedMovementDabs
            } else {
                emptyList()
            }
'''
assert old in s, 'movement generator block changed'
s = s.replace(old, new, 1)

old = '''            val heldDabs = if (stampBrush.airbrushDabsPerSecond > 0f && mappedSamples.isNotEmpty()) {
                AirbrushEngine.heldDabs(
                    mappedSamples, diameterPx, stampBrush,
                    stampBrush.airbrushDabsPerSecond, stampBrush.airbrushStillnessRadiusPx, stampSeed,
                )
            } else {
                emptyList()
            }
'''
new = '''            val heldDabs = if (stampBrush.airbrushDabsPerSecond > 0f && mappedSamples.isNotEmpty()) {
                var generator = stampAirbrushGenerator
                if (generator == null) {
                    generator = IncrementalAirbrushGenerator(
                        diameterPx, stampBrush, stampBrush.airbrushDabsPerSecond,
                        stampBrush.airbrushStillnessRadiusPx, stampSeed,
                    )
                    stampAirbrushGenerator = generator
                }
                while (stampAirbrushConsumedSampleCount < mappedSamples.size) {
                    stampGeneratedHeldDabs.addAll(
                        generator.append(mappedSamples[stampAirbrushConsumedSampleCount])
                    )
                    stampAirbrushConsumedSampleCount++
                }
                stampGeneratedHeldDabs
            } else {
                emptyList()
            }
'''
assert old in s, 'held generator block changed'
s = s.replace(old, new, 1)

old = '''        stampStaticDabGenerator = null
        stampGeneratedMovementDabs.clear()
        stampStaticConsumedPointCount = 0
'''
new = '''        stampStaticDabGenerator = null
        stampDynamicDabGenerator = null
        stampGeneratedMovementDabs.clear()
        stampMovementConsumedSampleCount = 0
        stampAirbrushGenerator = null
        stampGeneratedHeldDabs.clear()
        stampAirbrushConsumedSampleCount = 0
'''
count = s.count(old)
assert count >= 2, f'expected generator reset blocks, found {count}'
s = s.replace(old, new)

p.write_text(s)
