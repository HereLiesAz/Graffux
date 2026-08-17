# Claude Code Instructions for Graffux

## version.properties

NEVER undo, restore, revert, or `git checkout` version.properties. The Gradle
build auto-increments it on `assembleDebug` / `bundleRelease` — that is intended
behavior. When it changes on a branch, commit it along with the other changes.
Do not exclude it from commits, do not treat it specially. It is a normal file.

## versionCode and Play publishing

`app/build.gradle.kts` computes `versionCode` as the *committed*
`version.properties`' `versionBuild` + 1. A local `assemble*`/`bundle*` build also
increments `versionBuild` on disk as a build step, but `.github/workflows/release-aab.yml`
only has `contents: read` and never commits that increment back to the repo — so
`versionCode` does **not** advance on its own between CI runs. It only moves when
some commit actually raises the checked-in `versionBuild` (i.e. a local build's own
bump, committed like any other change — see the section above).

If a run merges to `main` without that bump, every following release build recomputes
the same `versionCode` and Google Play rejects it with `"Version code N has already
been used"`, because the previous run already published it. This has happened
repeatedly: several merges in a row after 2026-08-15 all recomputed the versionCode
the prior merge had already published, until `versionBuild` was bumped by hand — and
it recurred again even after the fail-fast guard below was added, because that same
commit didn't bump `versionBuild` either. `release-aab.yml` now fails fast with a
clear message before attempting the upload when it detects this (confirmed working:
it caught the second recurrence in seconds instead of a wasted multi-minute upload),
but the guard only makes the failure cheap — it doesn't fix it. The actual fix is
still: make sure `version.properties` gets bumped and committed as part of any change
that should ship a new release, including changes to the release pipeline itself.
