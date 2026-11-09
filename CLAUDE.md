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
branch publishes or records anything, and it records in two steps, each a `[skip ci]` commit by the
workflow:

1. Before the build, the central version contract writes `versionMajor`/`versionMinor`/`versionPatch`/
   `versionBuild` **and `versionName`** together, so the file never says one version in its fields
   and another in its name.
2. After a successful Google Play publish, the workflow writes `versionCode` (never lowered). If Play
   refuses the save (its daily save quota), nothing is published, no `versionCode` is recorded and no
   GitHub release is cut; the run succeeds with a warning and an open `play-publish-deferred` issue
   gives the retry time. A newer commit on the default branch that is not `[skip ci]` supersedes an
   older one, so only the newest commit publishes.

The build never increments anything itself, so a local or CI compile leaves `version.properties`
untouched.

## CI rules a compiler does not enforce

- Native builds use the one NDK pinned as `graffux.ndkVersion` in `gradle.properties`; native
  libraries must be 16 KB page aligned (`scripts/ci/check-elf-alignment.sh` checks the built APK).
- Every disabled test (`@Ignore`/`@Disabled`) carries an issue reference on its own line
  (`scripts/ci/check-ignored-tests.sh`).
- JNI classes need R8 keep rules that R8 actually reads (`scripts/ci/check_r8_keep_rules.py`).
- Jobs that run pull-request code are read-only and keep nothing sensitive on disk; only the
  `release`, `submit-dependency-graph` and `report-failure` jobs write, and they run none of it.

Read .entire/agent-guide.md for this repository's workflow, source inspection, and verification guidance.
@.entire/agent-guide.md
