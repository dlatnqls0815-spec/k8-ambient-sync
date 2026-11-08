#!/usr/bin/env python3
"""Wrap the read-only BLE probe APK in a NEMESIS package and a USB install layout.

Gen5W rejects non-allowlisted signers with INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES
while sys.boot_completed=1. NEMESIS (1.15+) clears that property for its own `pm install -r`
of a `kind=apk` entry. See docs/research/GEN5W_INSTALL_ALLOWLIST.md.

Offline only: this builds files on the PC. It never contacts the vehicle.
"""
import argparse
import hashlib
import io
from pathlib import Path
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_APK = ROOT / "private" / "ble-diagnostic" / "gen5w-ble-diagnostic.apk"
DEFAULT_OUT = ROOT / "private" / "nemesis-bleprobe"
# v1/v2-signed API19 build verified on 2026-11-07 (docs/AI_HANDOFF.md).
EXPECTED_APK_SHA256 = "edbc7b9b3fae6ef555b25b5be96dc6e0cc7c552a1179c80c676ba78d32f4831d"

PACKAGE_ID = "k8ambient.bleprobe"
PACKAGE_VERSION = "0.1.0"
ANDROID_PACKAGE = "io.github.k8ambient.bleprobe"
COMPONENT = ANDROID_PACKAGE + "/" + ANDROID_PACKAGE + ".ProbeActivity"
APK_PATH = "app/k8-ble-probe.apk"
SHORT_DESC = "Read-only BLE scan and GATT discovery for one lamp"
DETAILED_DESC = ("Scans for one user-entered lamp address, connects, lists the FFF0 service and "
                 "FFF3 properties, then disconnects. Sends no lamp or vehicle commands. "
                 "Uninstall removes the app.")
# Fixed timestamp keeps the zip byte-identical for the same APK.
ZIP_TIME = (2026, 11, 8, 0, 0, 0)


def descriptor():
    if len(SHORT_DESC) > 64 or len(DETAILED_DESC) > 256:
        raise ValueError("NEMESIS description limit exceeded")
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9._-]{0,63}", PACKAGE_ID) or ".." in PACKAGE_ID:
        raise ValueError("Invalid NEMESIS id")
    return "\n".join([
        "[nemesis]",
        "version=1.1",
        "id=" + PACKAGE_ID,
        "name=K8 BLE Probe",
        "type=application",
        "package-version=" + PACKAGE_VERSION,
        'short-desc="' + SHORT_DESC + '"',
        'detailed-desc="' + DETAILED_DESC + '"',
        "os=any",
        "models=any",
        "",
        "[app.0]",
        "kind=apk",
        "path=" + APK_PATH,
        "package=" + ANDROID_PACKAGE,
        "component=" + COMPONENT,
        "",
    ])


def usb_descriptor(zip_name):
    return "\n".join(["[nemesis]", "version=1.0", "type=install", "path=packages/" + zip_name, ""])


def _entry(name):
    info = zipfile.ZipInfo(name, ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    return info


def package_bytes(apk):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", allowZip64=False) as archive:
        archive.writestr(_entry("NEMESIS.data"), descriptor())
        archive.writestr(_entry(APK_PATH), apk)
    return buffer.getvalue()


def build(apk_path, out_dir, expected_sha256=EXPECTED_APK_SHA256):
    apk = Path(apk_path).read_bytes()
    digest = hashlib.sha256(apk).hexdigest()
    if expected_sha256 and digest != expected_sha256:
        raise ValueError("APK SHA-256 does not match the verified probe build: " + digest)
    if not apk.startswith(b"PK\x03\x04"):
        raise ValueError("APK is not a zip archive")
    zip_name = PACKAGE_ID + "-" + PACKAGE_VERSION + ".zip"
    data = package_bytes(apk)
    out = Path(out_dir)
    (out / "usb" / "packages").mkdir(parents=True, exist_ok=True)
    (out / zip_name).write_bytes(data)
    (out / "usb" / "packages" / zip_name).write_bytes(data)
    (out / "usb" / "NEMESIS.data").write_text(usb_descriptor(zip_name), encoding="utf-8", newline="\n")
    zip_sha = hashlib.sha256(data).hexdigest()
    (out / "SHA256SUMS.txt").write_text(
        "%s  %s\n%s  apk\n" % (zip_sha, zip_name, digest), encoding="utf-8", newline="\n")
    return {"zip": str(out / zip_name), "zip_sha256": zip_sha, "apk_sha256": digest}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", default=str(DEFAULT_APK))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--sha256", default=EXPECTED_APK_SHA256,
                        help="expected APK SHA-256; pass an empty string to skip")
    args = parser.parse_args(argv)
    result = build(args.apk, args.out, args.sha256)
    for key in ("zip", "zip_sha256", "apk_sha256"):
        print("%s=%s" % (key, result[key]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
