# Claude Code Instructions for Graffux

## version.properties and versionCode

Releases are versioned by HereLiesAz/workflows (`android-play-release.yml` and
`android-github-release.yml`, both through `.github/actions/android-version`), the same single rule
every HereLiesAz Android app uses:

- `versionCode` = the highest of the code recorded in `version.properties` and every code Google
  Play has ever accepted for the package (every track and status, drafts and uploaded bundles
  included), **+ 1**. Nothing else: no offsets, multipliers, run numbers, timestamps or commit
  counts. If Play can't be queried the release fails rather than guess. The GitHub APK uses the same
  rule, so sideloads and Play builds share one sequence.
- `versionName` = `versionMajor.versionMinor.versionPatch.versionBuild` from `version.properties`
  (edit the first three by hand when you mean to; the central version contract moves the rest).

Gradle receives them as `-PversionCode`/`-PversionCodeOverride` and `-PversionName`. Only the default
branch publishes or records anything: after a successful publish the workflow writes `versionCode`
(never lowered) and the version fields back into `version.properties` with a `[skip ci]` commit. The
build never increments anything itself, so a local or CI compile leaves `version.properties` untouched.
