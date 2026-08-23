# Claude Code Instructions for Graffux

## version.properties

NEVER undo, restore, revert, or `git checkout` version.properties. The Gradle
build auto-increments it on `assembleDebug` / `bundleRelease` — that is intended
behavior. When it changes on a branch, commit it along with the other changes.
Do not exclude it from commits, do not treat it specially. It is a normal file.

## versionCode and Play publishing

`versionCode` is asked of Google Play, not read out of the repo. `.github/workflows/release-aab.yml`
runs `publish_play.py --print-next-version-code` before the build, which returns
`max(version.properties' versionBuild + 1, highest versionCode on any Play track + 1)`, and passes
it to Gradle as `-PversionCodeOverride`. Local builds pass no override and behave exactly as before:
`versionCode` is the committed `versionBuild` + 1.

Two consequences worth knowing:

- **You no longer have to bump `version.properties` to ship a release.** The release pipeline picks
  a code Play will accept whether or not any commit touched that file.
- **A deliberate jump still wins.** The committed `versionBuild + 1` is a floor, so hand-editing
  `versionBuild` to a much larger number does what you would expect.

`version.properties` still drives `versionName` (`versionMajor.versionMinor.versionPatch`), and the
auto-increment described above still runs on local `assemble*`/`bundle*` builds — so keep committing
it like any other file.

### Why it works this way

`versionCode` used to be purely the committed `versionBuild` + 1. The release job is `contents: read`
and never commits its own increment back, so the number did not advance between CI runs — it moved
only when some commit happened to raise the checked-in `versionBuild`. Every merge that didn't do
that rebuilt the code the previous merge had already published, and Play rejected it with
`"Version code N has already been used"`.

This was not occasional. Runs 235, 236 and 237 all rebuilt 481 after run 234 published it; runs 241
and 246 both rebuilt 486 after run 240 published it. Roughly half of all release runs failed for this
one reason, each time needing a human to notice and hand-bump.

A fail-fast guard (`publish_play.py --version-code`) was added first. It only made the failure cheap
— seconds instead of a multi-minute upload — and it is still wired up as a second line of defence,
but it never fixed the cause.

The obvious alternative fix — grant the release job `contents: write` and let it commit the bump —
was rejected deliberately. That job holds the signing key and the Play service-account credential
for its whole lifetime, and write access to the repo from there widens what a compromised step can
reach. Deriving the number from Play needs no new privilege at all.

If the Play query fails, `publish_play.py` warns and falls back to the floor, which is the old
behaviour; the pre-publish guard still catches a collision. So a transient Play blip degrades to
the previous failure mode rather than breaking a release outright.
