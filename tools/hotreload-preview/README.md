# hotreload-preview

A desktop Compose sandbox for [Compose Hot Reload](https://github.com/JetBrains/compose-hot-reload):
edit a composable in this module and see the change applied to the running window instantly,
without restarting anything.

This module is intentionally **not** a preview of the shipping Android app. Compose Hot Reload
runs on the JetBrains Runtime and cannot attach to code running on an Android device or emulator,
so it can't target `:app`, `:feature:editor`, or `:core:design` directly — those depend on
CameraX/OpenCV/Hilt/native code (or, for `:core:design`, the Android-AAR-only `az-nav-rail`) that
don't run on a plain JVM. Use this module to prototype a composable in isolation with fast
iteration, then port the result back into the real Android module by hand.

## Running

From the IDE (Android Studio Otter 2025.2.1+ / IntelliJ IDEA 2025.2.2+): run `main()` in
`Main.kt` and pick "Run with Compose Hot Reload" — the IDE supplies its bundled JetBrains
Runtime automatically.

From the command line:

```sh
./gradlew :tools:hotreload-preview:hotRun --auto
```

`--auto` reloads on every save; without it, trigger a reload manually with
`./gradlew reload`. `gradle.properties` enables `compose.reload.jbr.autoProvisioningEnabled`
so a plain terminal invocation (no IDE) still fetches a matching JetBrains Runtime on its own.

## MCP server (AI agent UI control)

`.mcp.json` at the repo root registers a `graffux-compose-hot-reload` MCP server that runs
`:tools:hotreload-preview:hotMcpServer`. Once the app above is running, an MCP client
gets tools to interact with the live window: `take_screenshot`, `get_semantic_tree`, `click`,
`long_click`, `type_text`, `scroll`, `reload`, `get_logs`, `get_ui_error`, and more.
