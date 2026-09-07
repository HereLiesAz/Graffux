from pathlib import Path
p=Path('core/design/src/main/java/com/hereliesaz/graffitixr/design/GraffuxIcons.kt')
s=p.read_text()
for name in ['Symmetry','SymmetryHorizontal','SymmetryQuadrant','SymmetryRadial','SymmetryVertical']:
    import re
    s=re.sub(r'\n\s*val '+name+r'[^\n]*\n?', '\n', s)
    s=re.sub(r'\n\s*fun '+name+r'[^\n]*\n?', '\n', s)
# Remove any direct stale resource-map/export lines that still mention deleted drawables.
s='\n'.join(line for line in s.splitlines() if 'ic_gx_symmetry' not in line)+"\n"
p.write_text(s)
