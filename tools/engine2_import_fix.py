from pathlib import Path
p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DrawingEngine.kt')
s = p.read_text()
s = s.replace(
'''import com.hereliesaz.graffitixr.common.azphalt.Dab\nimport com.hereliesaz.graffitixr.common.azphalt.ImpastoEngine\n''',
'''import com.hereliesaz.graffitixr.common.azphalt.Dab\nimport com.hereliesaz.graffitixr.common.azphalt.DirtyRegion\nimport com.hereliesaz.graffitixr.common.azphalt.ImpastoEngine\nimport com.hereliesaz.graffitixr.common.azphalt.ImpastoRegionShader\n''', 1)
p.write_text(s)
