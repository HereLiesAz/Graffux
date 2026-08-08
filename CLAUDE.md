# Claude Code Instructions for Graffux

## version.properties

NEVER undo, restore, revert, or `git checkout` version.properties. The Gradle
build auto-increments it on `assembleDebug` / `bundleRelease` — that is intended
behavior. When it changes on a branch, commit it along with the other changes.
Do not exclude it from commits, do not treat it specially. It is a normal file.
