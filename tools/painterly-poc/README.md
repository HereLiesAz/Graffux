# painterly proof of concept

Scratch pack for `spec/painterly.md` phase P1. Not a shipped pack, never submitted to azphalt.
It builds a loaded-flat petal pull from features that exist today: `emit` lanes across the width,
per-lane `val` for a darker rim, pressure-driven swell, and a lift-off tail (`phase == 3`).

Render it with the pack harness:

~~~
./gradlew :core:engine:desktopTest --tests '*PackRenderHarness*' --offline \
    -Pgraffux.packs=tools/painterly-poc -Pgraffux.packs.out=build/painterly-poc
~~~

The harness colours dabs with a fixed grey plus the dab's hue/sat/val shift, so the two-tone here is a
darker rim over a lighter body, not real red over pink. Real two-colour load is spec phases P3 and P4.
