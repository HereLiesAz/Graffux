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
    publish_play.py --package com.example.app --aab path/to.aab --sa-json creds.json

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
from googleapiclient.http import MediaFileUpload

# (track_slug, release_status). `internal` goes live immediately; the other
# three land as drafts so a human promotes them from the Play Console when
# ready. Slugs are Play's defaults - override here if the app has custom
# closed-testing tracks (Play Console > Testing > Closed testing > track ID).
TARGETS = [
    ("internal",   "completed"),
    ("alpha",      "draft"),   # closed testing
    ("beta",       "draft"),   # open testing
    ("production", "draft"),
]

# The one track that has to work. Everything else is staging for a human to
# promote later; this one is what "published" means to a tester.
REQUIRED_TRACK = "internal"

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]

# httplib2's default socket timeout is None (system default, often ~2 min), which trips the
# TimeoutError we hit on release 14148 while Play digested a ~155MB AAB upload chunk. 10 min
# is plenty for any single chunk read.
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
    parser.add_argument("--aab", required=True, help="path to the signed .aab to upload")
    parser.add_argument("--sa-json", required=True, help="path to the service-account JSON key file")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    if not os.path.isfile(args.aab):
        print(f"::error::No AAB at {args.aab}", file=sys.stderr)
        return EXIT_NOTHING_PUBLISHED
    if not os.path.isfile(args.sa_json):
        print(f"::error::No service-account JSON at {args.sa_json}", file=sys.stderr)
        return EXIT_NOTHING_PUBLISHED

    with open(args.sa_json, encoding="utf-8") as handle:
        creds_info = json.load(handle)

    creds = service_account.Credentials.from_service_account_info(creds_info, scopes=SCOPES)
    authed_http = AuthorizedHttp(creds, http=httplib2.Http(timeout=HTTP_TIMEOUT_S))
    svc = build("androidpublisher", "v3", http=authed_http, cache_discovery=False)

    # Play's concurrent-active-edits quota is small - if we fail between insert and commit,
    # the open edit sits on the quota until Play garbage-collects it. Delete it on any
    # unrecoverable failure so repeated failures don't lock us out.
    edit_id = None
    try:
        edit = svc.edits().insert(packageName=args.package, body={}).execute()
        edit_id = edit["id"]
        print(f"::notice::Opened Play edit {edit_id}")

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

    except Exception as err:
        print(f"::error::Play publish failed: {err}", file=sys.stderr)
        if edit_id is not None:
            discard(svc, args.package, edit_id)
        return EXIT_NOTHING_PUBLISHED


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
