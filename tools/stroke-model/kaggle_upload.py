"""Publishes the stroke-data files to a private Kaggle dataset (see .github/workflows/stroke-data-kaggle.yml).

  python kaggle_upload.py DATA_DIR SCHEMA_MD

DATA_DIR is the `stroke-data/` folder of the stroke-data branch (<device-model>/session-*.jsonl.gz).
Credentials come from KAGGLE_TOKEN: a Kaggle API token string, or kaggle.json contents. The dataset
id is KAGGLE_DATASET, else "<username>/graffux-stroke-data". Creates the dataset (private) the first
time, then adds a new version each run. The token is never printed.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SLUG = "graffux-stroke-data"


def credentials() -> str | None:
    """Sets up Kaggle auth from KAGGLE_TOKEN; returns the username when the token carries one."""
    token = os.environ.get("KAGGLE_TOKEN", "").strip()
    if not token:
        sys.exit("KAGGLE_TOKEN secret is not set")
    if token.startswith("{"):
        creds = json.loads(token)
        cfg = Path(tempfile.mkdtemp())
        (cfg / "kaggle.json").write_text(json.dumps(creds))
        (cfg / "kaggle.json").chmod(0o600)
        os.environ["KAGGLE_CONFIG_DIR"] = str(cfg)
        return creds.get("username")
    os.environ["KAGGLE_API_TOKEN"] = token
    return None


def kaggle(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["kaggle", *args], text=True, capture_output=True, check=check)


def main() -> None:
    data_dir, schema = Path(sys.argv[1]), Path(sys.argv[2])
    sessions = sorted(data_dir.rglob("*.jsonl.gz")) if data_dir.is_dir() else []
    if not sessions:
        print("no stroke data yet; nothing to upload")
        return

    user = credentials() or os.environ.get("KAGGLE_USERNAME_VAR") or None
    dataset = os.environ.get("KAGGLE_DATASET") or (f"{user}/{SLUG}" if user else None)
    if not dataset:
        sys.exit("set the KAGGLE_DATASET or KAGGLE_USERNAME repository variable "
                 "(the token carries no username)")

    stage = Path(tempfile.mkdtemp())
    for f in sessions:
        dest = stage / f.relative_to(data_dir)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, dest)
    shutil.copy2(schema, stage / "SCHEMA.md")
    (stage / "dataset-metadata.json").write_text(json.dumps({
        "title": "Graffux Stroke Data",
        "id": dataset,
        "subtitle": "Touch/stylus strokes with motion sensors, for stroke prediction",
        "description": (stage / "SCHEMA.md").read_text(),
        "licenses": [{"name": "copyright-authors"}],
    }))

    exists = kaggle("datasets", "status", dataset, check=False).returncode == 0
    if exists:
        result = kaggle("datasets", "version", "-p", str(stage), "-m",
                        f"{len(sessions)} sessions", "--dir-mode", "zip", check=False)
    else:
        result = kaggle("datasets", "create", "-p", str(stage), "--dir-mode", "zip", check=False)
    print(result.stdout)
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr)
        sys.exit(result.returncode)
    print(f"{'versioned' if exists else 'created (private)'} {dataset}: {len(sessions)} sessions")


if __name__ == "__main__":
    main()
