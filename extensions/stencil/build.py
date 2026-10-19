#!/usr/bin/env python3
"""Builds the Stencil effect as an unsigned, reproducible .azp.

    python3 extensions/stencil/build.py [--refresh-fixture]

Compiles the Rust guest to WASM, writes manifest.json (with sha256 digests of every payload file),
and zips it to extensions/stencil/dist/com.hereliesaz.stencil-<version>.azp. Signing and publishing
to the azphalt store are separate steps that need the publisher's key.
"""
import hashlib, json, pathlib, re, shutil, subprocess, sys, zipfile

HERE = pathlib.Path(__file__).resolve().parent
GUEST = HERE / "guest"
WASM = GUEST / "target/wasm32-unknown-unknown/release/stencil.wasm"
FIXTURE = HERE.parent.parent / "core/data/src/test/resources/azphalt/stencil/stencil.wasm"


def main() -> None:
    subprocess.run(["cargo", "build", "--release", "--target", "wasm32-unknown-unknown"], cwd=GUEST, check=True)
    version = re.search(r'^version\s*=\s*"([^"]+)"', (GUEST / "Cargo.toml").read_text(), re.M).group(1)
    payload = {
        "code/stencil.wasm": WASM.read_bytes(),
        "ui/panel.json": (HERE / "ui/panel.json").read_bytes(),
        "LICENSE": (HERE / "LICENSE").read_bytes(),
    }
    files = {k: "sha256-" + hashlib.sha256(v).hexdigest() for k, v in sorted(payload.items())}
    manifest = (HERE / "manifest.template.json").read_text()
    manifest = manifest.replace("@VERSION@", version).replace("@FILES@", json.dumps(files, indent=2).replace("\n", "\n  "))
    json.loads(manifest)  # fail here, not in the installer

    out = HERE / "dist"
    out.mkdir(exist_ok=True)
    azp = out / f"com.hereliesaz.stencil-{version}.azp"
    with zipfile.ZipFile(azp, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in [("manifest.json", manifest.encode())] + sorted(payload.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))  # fixed: byte-identical rebuilds
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, data)
    if "--refresh-fixture" in sys.argv:
        shutil.copyfile(WASM, FIXTURE)
        print("refreshed", FIXTURE)
    print(azp, azp.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
