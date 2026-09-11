from pathlib import Path

path = Path("app/src/main/java/com/hereliesaz/graffux/MainActivity.kt")
text = path.read_text()
needle = ", maxHeight = unattachedRailMaxHeight"
count = text.count(needle)
if count:
    text = text.replace(needle, "")
    path.write_text(text)
    print(f"Removed {count} remaining maxHeight references")
else:
    print("No remaining maxHeight references")
