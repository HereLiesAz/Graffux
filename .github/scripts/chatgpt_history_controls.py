from pathlib import Path
import re

main_path = Path('app/src/main/java/com/hereliesaz/graffux/MainActivity.kt')
s = main_path.read_text()

old = '''                } else if (uiState.activePanel == EditorPanel.NONE) Row(
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val viewMoved = uiState.viewportZoom != 1f ||
                        uiState.viewportOffset != Offset.Zero ||
                        uiState.viewportRotation != 0f
                    if (viewMoved) {
                        FloatingActionButton(onClick = { vm.resetViewport() }, containerColor = surfaceVariantColor) {
                            Icon(painterResource(GraffuxIcons.ZoomFit), contentDescription = "Fit to screen")
                        }
                    }
                    if (uiState.undoCount > 0) {
                        FloatingActionButton(onClick = { vm.onUndoClicked() }, containerColor = surfaceVariantColor) {
                            Icon(painterResource(GraffuxIcons.Undo), contentDescription = strings.adj.undo)
                        }
                    }
                    if (uiState.redoCount > 0) {
                        FloatingActionButton(onClick = { vm.onRedoClicked() }, containerColor = surfaceVariantColor) {
                            Icon(painterResource(GraffuxIcons.Redo), contentDescription = strings.adj.redo)
                        }
                    }
                }
'''

new = '''                } else if (uiState.activePanel == EditorPanel.NONE) Row(
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val viewMoved = uiState.viewportZoom != 1f ||
                        uiState.viewportOffset != Offset.Zero ||
                        uiState.viewportRotation != 0f

                    // These are permanent positions, not a row of whichever buttons happen to exist.
                    // Keeping all three 56dp slots mounted prevents the row from recentering when one
                    // action becomes unavailable. Order is always Undo -> Reset -> Redo.
                    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        if (uiState.undoCount > 0) {
                            FloatingActionButton(
                                onClick = { vm.onUndoClicked() },
                                containerColor = surfaceVariantColor,
                            ) {
                                Icon(painterResource(GraffuxIcons.Undo), contentDescription = strings.adj.undo)
                            }
                        }
                    }
                    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        if (viewMoved) {
                            FloatingActionButton(
                                onClick = { vm.resetViewport() },
                                containerColor = surfaceVariantColor,
                            ) {
                                Icon(painterResource(GraffuxIcons.ZoomFit), contentDescription = "Fit to screen")
                            }
                        }
                    }
                    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        if (uiState.redoCount > 0) {
                            FloatingActionButton(
                                onClick = { vm.onRedoClicked() },
                                containerColor = surfaceVariantColor,
                            ) {
                                Icon(painterResource(GraffuxIcons.Redo), contentDescription = strings.adj.redo)
                            }
                        }
                    }
                }
'''

if old not in s:
    raise SystemExit('MainActivity history-controls block did not match current source')
main_path.write_text(s.replace(old, new, 1))

versions_path = Path('gradle/libs.versions.toml')
v = versions_path.read_text()
v2, n = re.subn(
    r'(?m)^azNavRail\s*=\s*"[^"]+"$',
    'azNavRail = "d116cfa93971d58afab86f1bcb6ed6d62e65b0dd"',
    v,
    count=1,
)
if n != 1:
    raise SystemExit('Could not update AzNavRail version pin')
versions_path.write_text(v2)
