# CI and branch protection

What `Merged Build & Release` (`.github/workflows/merged-build.yml`) does, and the one repository
setting that only the repository owner can apply.

## Jobs and their tokens

| Job | Runs on | Token | Runs pull-request code |
| --- | --- | --- | --- |
| `unit-tests` | PR, main | `contents: read` | yes |
| `wgpu-engine-tests` | PR, main | `contents: read` | yes |
| `detekt` | PR, main | `contents: read` | yes |
| `repo-checks` | PR, main | `contents: read` | yes (shell and Python only) |
| `build` | PR, main | `contents: read` | yes |
| `submit-dependency-graph` | main | `contents: write`, `actions: read` | no |
| `release` | main | `contents: write` | no |
| `report-failure` | failed build | `issues: write` | no |

Every checkout uses `persist-credentials: false`. `app/google-services.json` is written only for a
push to `main`. Only `main` writes the Gradle cache. A push to `main` queues behind the previous run
instead of cancelling it, so a release is never interrupted between its steps.

## Rolling debug release

`scripts/ci/rolling-release.sh` uploads the APK first, moves the `latest-debug-vMAJOR.MINOR` tag
second and edits the release text last, so a failed upload never leaves the tag ahead of its asset.
It also deletes older `*-debug.apk` assets and the retired `build-tools.zip`. Its regression test is
`scripts/ci/test-rolling-release.sh`.

## Checks that no compiler enforces

| Check | What fails it |
| --- | --- |
| `scripts/ci/check-elf-alignment.sh` | a native library in the built APK with a LOAD segment aligned below 16 KB |
| `scripts/ci/check-ignored-tests.sh` | an `@Ignore`/`@Disabled` line with no issue reference (`#123`, `/issues/123`, `ABC-123`) |
| `scripts/ci/check_r8_keep_rules.py` | JNI classes R8 could rename or remove, or rules files R8 never reads |

Native builds use the NDK pinned as `graffux.ndkVersion` in `gradle.properties`.

## Branch ruleset: needs the repository owner

`.github/rulesets/main-protection.json` is a ready-to-import ruleset for the default branch. A
repository file cannot enable it. To apply it: Settings -> Rules -> Rulesets -> New ruleset ->
Import a ruleset, and choose that file.

It blocks deleting or force-pushing `main`, requires a pull request with resolved review threads,
and requires the `unit-tests`, `wgpu-engine-tests`, `build` and `repo-checks` checks. `detekt` is
deliberately not required (its backlog is tracked separately), and neither is `release`.

Two things the file cannot contain, so they need a decision:

1. **The central workflows' app must be a bypass actor.** HereLiesAz/workflows pushes
   `chore(version): ... [skip ci]` commits straight to `main` with its GitHub App token. A rule that
   requires pull requests blocks those pushes, and version recording stops. After importing, open the
   ruleset and add that GitHub App under "Bypass list" with "Always allow". Its id is not knowable
   from this repository.
2. The file lets repository admins bypass (role id 5). Remove that entry if the owner should be bound
   too.

Until the ruleset is applied nothing changes: the checks above still run and show on every PR, they
just are not required to merge.
