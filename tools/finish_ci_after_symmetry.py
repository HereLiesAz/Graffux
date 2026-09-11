from pathlib import Path

main = Path("app/src/main/java/com/hereliesaz/graffux/MainActivity.kt")
text = main.read_text()

old_visibility = '''    // AzNavRail 11.47 keeps its chrome composed while hiding it, so rail state, floating positions,\n    // nested state and window state survive a stroke. Capture mode and active drawing now use that\n    // one host-level visibility contract instead of each Az surface having to disappear independently.\n    val azChromeVisible = !uiState.hideUiForCapture && !strokeGate.strokeActive\n    // Unattached hosts are scroll viewports in 11.47. Keep them comfortably inside the usable\n    // screen while still letting the library apply its own stricter safe-viewport cap if necessary.\n    val unattachedRailMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp\n'''
if old_visibility not in text:
    raise SystemExit("expected visibility/max-height block not found")
text = text.replace(old_visibility, "", 1)

old_host = '''        AzHostActivityLayout(\n            navController = navController,\n            initiallyExpanded = false,\n            azVisible = azChromeVisible,\n            azVisibilityAnimation = AzVisibilityAnimation.NEAREST_EDGE,\n            azVisibilityDurationMillis = 160,\n        ) {'''
new_host = '''        AzHostActivityLayout(\n            navController = navController,\n            initiallyExpanded = false,\n        ) {'''
if old_host not in text:
    raise SystemExit("expected AzHostActivityLayout block not found")
text = text.replace(old_host, new_host, 1)

needle = "anchor = AzUnattachedAnchor.OPPOSITE, maxHeight = unattachedRailMaxHeight,"
if text.count(needle) != 2:
    raise SystemExit(f"expected 2 unattached maxHeight uses, found {text.count(needle)}")
text = text.replace(needle, "anchor = AzUnattachedAnchor.OPPOSITE,")
main.write_text(text)
