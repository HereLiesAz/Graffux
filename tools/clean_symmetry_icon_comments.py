from pathlib import Path
p=Path('core/design/src/main/java/com/hereliesaz/graffitixr/design/GraffuxIcons.kt')
s=p.read_text()
for label in ['symmetry','symmetry-horizontal','symmetry-quadrant','symmetry-radial','symmetry-vertical']:
    s=s.replace(f'    /** `{label}` — tools. */\n','')
p.write_text(s)
