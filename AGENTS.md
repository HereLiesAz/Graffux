# Codex Instructions for Graffux

## version.properties and versionCode

Releases are versioned by `HereLiesAz/workflows` `android-release.yml` (the `android-play-release.yml`
and `android-github-release.yml` files here are hand-off stubs that call it), the same single rule
every HereLiesAz Android app uses. `CLAUDE.md` is the canonical description; in short:

- `versionCode` = the highest of the code recorded in `version.properties` and every code Google
  Play has ever accepted for the package, **+ 1**. No offsets, multipliers, run numbers, timestamps
  or commit counts.
- `versionName` = `versionMajor.versionMinor.versionPatch` from `version.properties` (edit these by
  hand when you mean to), with the last field of the previous name **+ 1**.

The workflow passes both to Gradle as `-PversionCode` / `-PversionName`, and after a successful
publish records the pair (`versionCode`, `versionName`, `versionBuild`) back into
`version.properties` on the default branch with a `[skip ci]` commit. Local builds reuse that last
published pair.

**The Gradle build never increments anything.** A local or CI compile leaves `version.properties`
untouched; if it shows up modified in your working tree you changed it (or pulled the workflow's
commit). Do not revert or restore it, and do not hand-bump it just to ship: the release workflow
picks a code Play will accept regardless.

The release hand-off workflows hold no secrets and have `permissions: {}`; signing keys and the Play
credential live in the central workflow. There is no Play-publishing script in this repository.
