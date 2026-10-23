# Codex Instructions for Graffux

## version.properties and versionCode

Releases are versioned by `HereLiesAz/workflows` (`android-play-release.yml` and
`android-github-release.yml`; the files of the same names here are hand-off stubs that call them),
the same single rule every HereLiesAz Android app uses. `CLAUDE.md` is the canonical description;
in short:

- `versionCode` = the highest of the code recorded in `version.properties` and every code Google
  Play has ever accepted for the package (drafts included), **+ 1**. No offsets, multipliers, run
  numbers, timestamps or commit counts. Play and GitHub builds share it.
- `versionName` = `versionMajor.versionMinor.versionPatch.versionBuild` from `version.properties`.
- Only the default branch publishes or writes version state back; the build never increments
  anything.
