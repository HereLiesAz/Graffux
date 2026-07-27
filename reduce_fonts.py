import sys
import re

with open('core/design/src/main/java/com/hereliesaz/graffitixr/design/theme/Typography.kt', 'r', encoding='utf-8') as f:
    text = f.read()

def scale_down(match):
    size = int(match.group(1))
    new_size = max(int(size * 0.8), 9) # keep min size 9
    return f"{new_size}.sp"

text = re.sub(r'(\d+)\.sp', scale_down, text)

with open('core/design/src/main/java/com/hereliesaz/graffitixr/design/theme/Typography.kt', 'w', encoding='utf-8') as f:
    f.write(text)
