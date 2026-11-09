#!/usr/bin/env python3
"""Runs the 'Name the rolling debug APK' step of merged-build.yml exactly as Actions would.

The step is taken from the workflow file and run with `bash -e -o pipefail` (the strictest shell a
step can get), in a scratch checkout that has only the settings file Graffux really has.
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
workflow = yaml.safe_load((ROOT / ".github/workflows/merged-build.yml").read_text(encoding="utf-8"))
step = next(s for s in workflow["jobs"]["build"]["steps"] if s.get("id") == "release_vars")
script = step["run"]


def run(settings: dict[str, str]) -> tuple[int, dict[str, str], str]:
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        (work / "version.properties").write_text(
            "versionMajor=1\nversionMinor=85\nversionPatch=0\nversionBuild=1\n", encoding="utf-8"
        )
        for name, text in settings.items():
            (work / name).write_text(text, encoding="utf-8")
        apk_dir = work / "app/build/outputs/apk/debug"
        apk_dir.mkdir(parents=True)
        (apk_dir / "app-debug.apk").write_bytes(b"apk")
        out = work / "github_output"
        out.write_text("", encoding="utf-8")
        proc = subprocess.run(
            ["bash", "-e", "-o", "pipefail", "-c", script],
            cwd=work, env={"PATH": os.environ["PATH"], "GITHUB_OUTPUT": str(out)},
            capture_output=True, text=True, timeout=30,
        )
        outputs = dict(line.split("=", 1) for line in out.read_text(encoding="utf-8").splitlines() if "=" in line)
        return proc.returncode, outputs, proc.stderr


# Graffux's real layout: only settings.gradle.kts exists. This exited 2 under pipefail when sed was
# handed both file names.
code, outputs, err = run({"settings.gradle.kts": 'rootProject.name = "Graffux"\n'})
assert code == 0, err
assert outputs == {"tag_name": "latest-debug-v1.85", "apk_name": "Graffux-1.85.0.1-debug.apk"}, outputs

code, outputs, err = run({"settings.gradle": "rootProject.name = 'Legacy'\n"})
assert code == 0 and outputs["apk_name"] == "Legacy-1.85.0.1-debug.apk", (code, outputs, err)

# No settings file at all falls back to a generic name instead of failing.
code, outputs, err = run({})
assert code == 0 and outputs["apk_name"] == "App-1.85.0.1-debug.apk", (code, outputs, err)

print("merged-build naming step tests passed")
