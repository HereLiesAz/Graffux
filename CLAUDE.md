# Claude Code Instructions for Graffux

## version.properties and versionCode

Releases are versioned by HereLiesAz/workflows `android-release.yml`, the same single rule every
HereLiesAz Android app uses:

- `versionCode` = the highest of the code recorded in `version.properties` and every code Google
  Play has ever accepted for the package, **+ 1**. Nothing else: no offsets, multipliers, run
  numbers, timestamps or commit counts.
- `versionName` = `versionMajor.versionMinor.versionPatch` from `version.properties` (edit these by
  hand when you mean to), with the last field of the previous name **+ 1**.

Gradle receives them as `-PversionCode` / `-PversionName`. After a successful publish the workflow
records the pair (`versionCode`, `versionName`, `versionBuild`) back into `version.properties` on the
default branch with a `[skip ci]` commit; local builds reuse that last published pair. The build
never increments anything itself, so a local or CI compile leaves `version.properties` untouched.

## AzNavRail

Before writing or changing any AzNavRail code (rail items, pages, bottom sheets, overlays,
anything from `com.hereliesaz.aznavrail`), read the AzNavRail complete guide in full and follow
its API and conventions. It ships inside the library at `assets/AZNAVRAIL_COMPLETE_GUIDE.md`. For the version in `gradle/libs.versions.toml`, read it directly from the cached AAR; this works
even on a fresh checkout before Gradle has extracted/transformed the dependency:

~~~
AAR="$(find ~/.gradle/caches -name 'aznavrail-<version>.aar' -print -quit)"
unzip -p "$AAR" assets/AZNAVRAIL_COMPLETE_GUIDE.md
~~~

If `$AAR` is empty, resolve dependencies first (for example `./gradlew :app:dependencies`) and
run the command again.

If you delegate AzNavRail work to another agent, pass it the guide too.
