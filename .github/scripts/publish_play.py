#!/usr/bin/env python3
"""Publish one AAB to multiple Play tracks with per-track status.

Model: one Play Developer API edit -> one bundles.upload -> N tracks.update
calls (each with its own release object carrying its own status) -> one
commit. `r0adkll/upload-google-play@v1` forces a single global status per
call, so it can't do the internal=completed + others=draft fan-out this
script does in one edit transaction. Play also rejects re-uploading the same
versionCode, so a single edit is the only way to hand one bundle to several
tracks at all.

Usage (see .github/workflows/release-aab.yml):
    publish_play.py --package com.example.app --aab path/to.aab --sa-json creds.json \
        --version-code 476

--version-code is optional but recommended: given, it's checked against the required track's
current versionCodes before the AAB is uploaded, so a stranded versionCode (version.properties'
versionBuild not bumped since the last successful publish) fails fast with an actionable message
instead of a raw Play 403 after the whole upload.

Second mode -- ask Play what to build next, instead of guessing from the repo:
    publish_play.py --package com.example.app --sa-json creds.json \
        --print-next-version-code --floor 488

It prints a single integer on stdout (nothing else goes there in this mode) and exits 0. That
integer is `max(floor, highest versionCode on any Play track + 1)`, which the release workflow
feeds back into Gradle as -PversionCodeOverride so the AAB is built with a versionCode Play will
accept. This is what stops the "Version code N has already been used" loop at the source: the
number no longer depends on someone remembering to commit a version.properties bump. --floor
keeps a deliberate manual jump (a large hand-edited versionBuild) winning over Play's history.

Exit codes are a contract with the workflow:
    0  every track staged
    2  internal published, but at least one draft track failed -- typically
       because that track has never been created in the Play Console, which
       the API cannot do for you. The build is genuinely usable by testers,
       so this is reported as partial rather than total failure.
    1  nothing published

Anything else means this script itself failed to run, and the workflow treats
that as a hard failure rather than guessing which of the above it resembles.
"""
import argparse
import json
import os
import sys

import httplib2
from google.oauth2 import service_account
from google_auth_httplib2 import AuthorizedHttp
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

# (track_slug, release_status). `internal` goes live immediately; the other
# three land as drafts so a human promotes them from the Play Console when
# ready. Slugs are Play's defaults - override here if the app has custom
# closed-testing tracks (Play Console > Testing > Closed testing > track ID).
TARGETS = [
    ("internal",   "completed"),
    ("alpha",      "draft"),   # closed testing
]

# The one track that has to work. Everything else is staging for a human to
# promote later; this one is what "published" means to a tester.
REQUIRED_TRACK = "internal"

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]

# httplib2.Http(timeout=None) falls back to socket.getdefaulttimeout(), which on CPython is
# None unless something else in the process sets it — i.e. block forever, not time out quickly.
# Whatever produced the TimeoutError on release 14148 while Play digested a ~155MB AAB upload
# chunk wasn't this default, but an explicit bound is still the right fix either way: 10 min is
# plenty for any single chunk read, and it turns a silent hang into a real, visible failure.
HTTP_TIMEOUT_S = 600

# Chunk-level retry count on transient upload failures (5xx, connection errors, timeouts).
# googleapiclient's `.execute(num_retries=N)` retries with exponential backoff.
UPLOAD_RETRIES = 5

# Smaller chunks = shorter individual reads = smaller per-chunk timeout risk. Default is 100MB
# which is too big when Play's edge is slow. 4MB is standard for resumable uploads.
UPLOAD_CHUNK_SIZE = 4 * 1024 * 1024

EXIT_OK = 0
EXIT_NOTHING_PUBLISHED = 1
EXIT_PARTIAL = 2


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Publish one AAB to multiple Play tracks.")
    parser.add_argument("--package", required=True, help="applicationId, e.g. com.hereliesaz.graffux")
    parser.add_argument(
        "--aab", default=None,
        help="path to the signed .aab to upload. Required unless --print-next-version-code, "
        "which runs before any AAB exists.",
    )
    parser.add_argument("--sa-json", required=True, help="path to the service-account JSON key file")
    parser.add_argument(
        "--version-code", type=int, default=None,
        help="versionCode baked into --aab. When given, checked against the versionCodes already "
        f"on '{REQUIRED_TRACK}' before uploading, so a stale versionCode fails fast with a clear "
        "message instead of uploading the whole AAB just to get a raw Play 403. Second line of "
        "defence behind --print-next-version-code, which is what normally keeps it fresh.",
    )
    parser.add_argument(
        "--print-next-version-code", action="store_true",
        help="Do not publish. Print the next free versionCode (see module docstring) on stdout "
        "and exit. Used by the release workflow to pick the versionCode before building.",
    )
    parser.add_argument(
        "--floor", type=int, default=0,
        help="Lower bound for --print-next-version-code, normally version.properties' "
        "versionBuild + 1, so a deliberate manual bump still wins over Play's history.",
    )
    args = parser.parse_args()
    if args.aab is None and not args.print_next_version_code:
        parser.error("--aab is required unless --print-next-version-code is given")
    return args


def main() -> int:
    try:
        args = parse_args()
    except SystemExit as exc:
        # argparse's own parser.error() path (missing/invalid --package, --aab, --version-code,
        # etc.) calls sys.exit(2) — the same code this script uses for "internal published, some
        # drafts failed". Left alone, a bad invocation reads to the workflow as a partial publish
        # that never happened at all. Remap: 0 stays 0 (e.g. --help), anything else is "nothing
        # published", never "partial".
        return EXIT_OK if exc.code == 0 else EXIT_NOTHING_PUBLISHED

    if args.aab is not None and not os.path.isfile(args.aab):
        print(f"::error::No AAB at {args.aab}", file=sys.stderr)
        return EXIT_NOTHING_PUBLISHED
    if not os.path.isfile(args.sa_json):
        print(f"::error::No service-account JSON at {args.sa_json}", file=sys.stderr)
        return EXIT_NOTHING_PUBLISHED

    try:
        with open(args.sa_json, encoding="utf-8") as handle:
            creds_info = json.load(handle)
    except json.JSONDecodeError as err:
        # An unset or truncated PLAY_SERVICE_ACCOUNT_JSON secret lands here, and the raw
        # decoder message ("Expecting value: line 1 column 1") names neither the secret nor
        # the cause. Say which one to look at.
        print(
            "::error::PLAY_SERVICE_ACCOUNT_JSON is not valid JSON — paste the whole "
            f"service-account key file into the repository secret, unedited ({err})",
            file=sys.stderr,
        )
        return EXIT_NOTHING_PUBLISHED

    if creds_info.get("type") != "service_account" or not creds_info.get("client_email"):
        print(
            "::error::PLAY_SERVICE_ACCOUNT_JSON is valid JSON but not a service-account key "
            "(no \"type\": \"service_account\"). An OAuth client secret or a downloaded "
            "google-services.json will not work here.",
            file=sys.stderr,
        )
        return EXIT_NOTHING_PUBLISHED

    # Neither of these is a secret — they are the account's public identifier and its GCP
    # project — and both are what you need to know to fix a 403, which otherwise only says
    # "the caller does not have permission" without naming the caller.
    sa_email = creds_info["client_email"]
    sa_project = creds_info.get("project_id", "<unknown>")
    # See --print-next-version-code: stdout is reserved for the integer in that mode.
    log = sys.stderr if args.print_next_version_code else sys.stdout
    verb = "Querying" if args.print_next_version_code else "Publishing"
    print(f"::notice::{verb} {args.package} as {sa_email} (project {sa_project})", file=log)

    creds = service_account.Credentials.from_service_account_info(creds_info, scopes=SCOPES)
    http = httplib2.Http(timeout=HTTP_TIMEOUT_S)
    # Resumable uploads (like the multi-chunk AAB below) report an in-progress chunk as HTTP 308
    # "Resume Incomplete" with no Location header - that's Google's convention, not a real
    # redirect. httplib2 treats 308 as a redirect by default and raises RedirectMissingLocation
    # ("Redirected but the response is missing a Location: header.") the moment it sees one,
    # which aborts the upload after the first chunk. googleapiclient's own build_http() strips
    # 308 out for exactly this reason; we bypass build_http() to wrap the transport in
    # AuthorizedHttp, so apply the same fix here.
    try:
        http.redirect_codes = http.redirect_codes - {308}
    except AttributeError:
        pass
    authed_http = AuthorizedHttp(creds, http=http)
    svc = build("androidpublisher", "v3", http=authed_http, cache_discovery=False)

    # Play's concurrent-active-edits quota is small - if we fail between insert and commit,
    # the open edit sits on the quota until Play garbage-collects it. Delete it on any
    # unrecoverable failure so repeated failures don't lock us out.
    edit_id = None
    try:
        edit = svc.edits().insert(packageName=args.package, body={}).execute()
        edit_id = edit["id"]
        print(f"::notice::Opened Play edit {edit_id}", file=log)

        if args.print_next_version_code:
            # Read-only: this edit is never committed, so it costs nothing against Play's
            # daily save quota (only commits count) and is discarded again a few lines down.
            try:
                highest = highest_version_code(svc, args.package, edit_id)
            except HttpError as err:
                print(
                    f"::warning::Could not read Play's current versionCodes ({err}) — falling "
                    f"back to version.properties' {args.floor}. If that one is already used, the "
                    "pre-publish check will say so.",
                    file=sys.stderr,
                )
                highest = 0
            nxt = max(args.floor, highest + 1)
            print(
                f"::notice::Highest versionCode on Play is {highest}; floor is {args.floor}; "
                f"building {nxt}",
                file=log,
            )
            discard(svc, args.package, edit_id)
            edit_id = None
            print(nxt)
            return EXIT_OK

        if args.version_code is not None:
            try:
                stale = already_used(svc, args.package, edit_id, args.version_code)
            except HttpError as err:
                # This pre-check is a fast heuristic, not the authoritative check — Play's own
                # upload rejection still runs after it either way. A never-released internal
                # track (a genuinely valid state — see SETUP note 3 in release-aab.yml) or any
                # other transient error here must not block a publish that would otherwise have
                # gone through, so degrade to "couldn't tell" rather than aborting.
                print(
                    f"::warning::Could not pre-check versionCode against '{REQUIRED_TRACK}' "
                    f"({err}) — proceeding to upload; Play's own check still applies.",
                    file=sys.stderr,
                )
                stale = None
            if stale is not None:
                print(
                    f"::error::versionCode {args.version_code} is not newer than the versionCode "
                    f"already on '{REQUIRED_TRACK}' ({stale}). The release workflow normally "
                    "derives this from Play itself (--print-next-version-code), so reaching here "
                    "means that query was skipped or fell back — check the \"Determine next "
                    "versionCode\" step's log for a warning. Building with "
                    f"-PversionCodeOverride={stale + 1} or bumping version.properties' "
                    "versionBuild past it will clear it. See \"versionCode and Play publishing\" "
                    "in CLAUDE.md. (Not attempting the upload — Play would reject it the same "
                    "way, just after spending several minutes moving the AAB.)",
                    file=sys.stderr,
                )
                discard(svc, args.package, edit_id)
                return EXIT_NOTHING_PUBLISHED

        media = MediaFileUpload(
            args.aab,
            mimetype="application/octet-stream",
            resumable=True,
            chunksize=UPLOAD_CHUNK_SIZE,
        )
        # num_retries=N retries individual chunk uploads with exponential backoff on 5xx and
        # transport errors - including the socket-read TimeoutError we hit on release 14148.
        bundle = svc.edits().bundles().upload(
            packageName=args.package, editId=edit_id, media_body=media,
        ).execute(num_retries=UPLOAD_RETRIES)
        version_code = bundle["versionCode"]
        print(f"::notice::Uploaded AAB versionCode={version_code} ({args.aab})")

        failed = []
        for track, status in TARGETS:
            try:
                svc.edits().tracks().update(
                    packageName=args.package,
                    editId=edit_id,
                    track=track,
                    body={
                        "track": track,
                        "releases": [{
                            "status": status,
                            "versionCodes": [str(version_code)],
                        }],
                    },
                ).execute()
                print(f"::notice::Assigned versionCode={version_code} to '{track}' as {status}")
            except Exception as err:
                # A draft track that has never been created in the Play Console can't be
                # updated by the API, and that must not cost the internal testers their
                # build. Record it and carry on; the edit still commits with what worked.
                failed.append(track)
                print(f"::warning::Could not stage '{track}': {err}", file=sys.stderr)

        if REQUIRED_TRACK in failed:
            print(
                f"::error::'{REQUIRED_TRACK}' could not be staged, so nothing worth "
                "committing was produced. Discarding the edit.",
                file=sys.stderr,
            )
            discard(svc, args.package, edit_id)
            return EXIT_NOTHING_PUBLISHED

        svc.edits().commit(packageName=args.package, editId=edit_id).execute()
        edit_id = None  # committed; no longer ours to delete
        print(
            f"::notice::Committed edit - versionCode={version_code} live on "
            f"{REQUIRED_TRACK}, draft on the rest"
        )

        if failed:
            print(
                "::warning::These tracks were not staged and need to be created once by hand "
                f"in the Play Console before the API can write to them: {', '.join(failed)}",
                file=sys.stderr,
            )
            return EXIT_PARTIAL
        return EXIT_OK

    except HttpError as err:
        print(f"::error::Play publish failed: {err}", file=sys.stderr)
        if err.resp.status == 403:
            explain_403(sa_email, sa_project, args.package, err)
        if edit_id is not None:
            discard(svc, args.package, edit_id)
        return EXIT_NOTHING_PUBLISHED

    except Exception as err:
        print(f"::error::Play publish failed: {err}", file=sys.stderr)
        if edit_id is not None:
            discard(svc, args.package, edit_id)
        return EXIT_NOTHING_PUBLISHED


def explain_403(sa_email: str, sa_project: str, package: str, err: HttpError) -> None:
    """Turn Play's "The caller does not have permission" into something actionable.

    A 403 means Google minted a token for the key in PLAY_SERVICE_ACCOUNT_JSON and then
    refused it — so the secret itself is fine and rotating it changes nothing. What is
    missing is a grant on Google's side, and the API will not say which one, so list the
    three that produce this exact error."""
    body = ""
    try:
        body = err.content.decode("utf-8", "replace")
    except Exception:  # noqa: BLE001 - diagnostics must never mask the real error
        body = str(err)

    if "accessNotConfigured" in body or "SERVICE_DISABLED" in body:
        print(
            "::error::The Google Play Android Developer API is not enabled in project "
            f"'{sa_project}'. Enable it at https://console.cloud.google.com/apis/library/"
            f"androidpublisher.googleapis.com?project={sa_project} and re-run.",
            file=sys.stderr,
        )
        return

    if "already been used" in body:
        print(
            "::error::The AAB's versionCode was already uploaded to this app. "
            "This usually means a previous CI run already published this build. "
            "Bump versionCode (rebuild on a fresh commit) and re-run.",
            file=sys.stderr,
        )
        return

    # A `::error::` annotation is one line - a newline inside it ends the annotation and the
    # rest is swallowed. So: one-line annotation for the summary, plain lines for the list.
    print(
        f"::error::Play rejected {sa_email} for {package}. The key authenticated, so "
        "PLAY_SERVICE_ACCOUNT_JSON is being read correctly and rotating it will not help — "
        "the account is not authorised. See the checklist below.",
        file=sys.stderr,
    )
    for line in (
        f"  1. Play Console > Users & permissions: is {sa_email} an *accepted* user with app",
        f"     access to {package}? Grant it 'Release to testing tracks' (and 'Release to",
        "     production' for the production draft). A pending invitation is not access.",
        f"  2. Play Console > Setup > API access: is project '{sa_project}' the project linked",
        "     to this developer account? A key from any other project authenticates and is",
        "     then refused with exactly this error.",
        "  3. Google Cloud Console: is the Google Play Android Developer API enabled in",
        f"     project '{sa_project}'?",
        "  Permission changes can take a few minutes to propagate.",
    ):
        print(line, file=sys.stderr)


def highest_version_code(svc, package: str, edit_id: str) -> int:
    """Highest versionCode currently released on ANY Play track, or 0 if there are none.

    Deliberately every track, not just REQUIRED_TRACK: a code staged as a draft on alpha is just
    as used, and Play rejects reusing it. 0 for a brand-new app with nothing on any track, which
    makes the caller's max(floor, highest + 1) fall back to the repo's own number.

    Same blind spot as already_used(): a code retired from every current track still can't be
    reused, and won't be seen here. The floor keeps climbing independently, so in practice the
    number this feeds only ever moves forward.
    """
    tracks = svc.edits().tracks().list(packageName=package, editId=edit_id).execute()
    used = [
        int(code)
        for track in tracks.get("tracks", [])
        for release in track.get("releases", [])
        for code in release.get("versionCodes", []) or []
    ]
    return max(used, default=0)


def already_used(svc, package: str, edit_id: str, version_code: int) -> int | None:
    """None if version_code is newer than everything already on REQUIRED_TRACK, otherwise the
    highest versionCode found there (what made it stale).

    Only checks REQUIRED_TRACK, not Play's full history for the app — a versionCode retired from
    every current track (rolled back, or once staged only as a draft that got replaced) would slip
    past this and still get the same 403 from Play itself. That's fine: this is a fast pre-check
    for the one failure mode that has actually recurred here (a merge landing without a
    version.properties bump), not a substitute for Play's own authoritative check.
    """
    track = svc.edits().tracks().get(
        packageName=package, editId=edit_id, track=REQUIRED_TRACK,
    ).execute()
    used = [
        int(code)
        for release in track.get("releases", [])
        for code in release.get("versionCodes", [])
    ]
    highest = max(used, default=0)
    return highest if version_code <= highest else None


def discard(svc, package: str, edit_id: str) -> None:
    """Best-effort release of the edit quota. Never raises - a cleanup failure must not
    replace the real error with a less useful one."""
    try:
        svc.edits().delete(packageName=package, editId=edit_id).execute()
        print(f"::warning::Deleted uncommitted Play edit {edit_id}", file=sys.stderr)
    except Exception as cleanup_err:
        print(f"::warning::Could not delete edit {edit_id}: {cleanup_err}", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
