#!/usr/bin/env python3
"""Build the NEMESIS package for the K8 car relay (plan B) and its USB install layout.

The package registers one restarting root service that runs the relay DEX with app_process,
plus a launchable status script. No install hooks. Offline only.
"""
import argparse
import hashlib
import io
from pathlib import Path
import sys
import zipfile

import build_nemesis_bleprobe as common

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DEX = ROOT / "private" / "car-relay" / "k8-ambient-relay.dex"
DEFAULT_OUT = ROOT / "private" / "nemesis-relay"
NEMESIS_DIR = ROOT / "car-relay" / "nemesis"
MAIN_CLASS = b"Lio/github/k8ambient/relay/CarRelay;"

PACKAGE_ID = "k8ambient.relay"
PACKAGE_VERSION = "0.4.2"
SERVICE_PATH = "svc/relay.sh"
STATUS_PATH = "bin/status.sh"
DEX_PATH = "bin/k8-ambient-relay.dex"
SHORT_DESC = "Sends OEM ambient color/brightness to the phone over BT"
DETAILED_DESC = ("Reads the factory ambient status from the log and sends it to the bonded phone over "
                 "Bluetooth RFCOMM. Records overspeed logs, serves speed/gear telemetry, turns built-in "
                 "Wi-Fi on after ignition unless the car hotspot runs. No vehicle commands.")


def descriptor():
    if len(SHORT_DESC) > 64 or len(DETAILED_DESC) > 256:
        raise ValueError("NEMESIS description limit exceeded")
    return "\n".join([
        "[nemesis]",
        "version=1.1",
        "id=" + PACKAGE_ID,
        "name=K8 Ambient Relay",
        "type=application,service",
        "package-version=" + PACKAGE_VERSION,
        'short-desc="' + SHORT_DESC + '"',
        'detailed-desc="' + DETAILED_DESC + '"',
        "os=any",
        "models=any",
        "",
        "[app.0]",
        "kind=sh",
        "path=" + STATUS_PATH,
        "uid=root",
        "",
        "[service.0]",
        "name=relay",
        "kind=sh",
        "path=" + SERVICE_PATH,
        "uid=root",
        "mode=restart",
        "",
    ])


def package_bytes(service, status, dex):
    common.check_script(service)
    common.check_script(status)
    if not dex.startswith(b"dex\n035\x00") or MAIN_CLASS not in dex:
        raise ValueError("Not the API 19 car relay DEX")
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", allowZip64=False) as archive:
        archive.writestr(common._entry("NEMESIS.data", 0o644), descriptor())
        archive.writestr(common._entry(STATUS_PATH, 0o755), status)
        archive.writestr(common._entry(SERVICE_PATH, 0o755), service)
        archive.writestr(common._entry(DEX_PATH, 0o644), dex)
    return buffer.getvalue()


def build(dex_path, out_dir):
    service = (NEMESIS_DIR / SERVICE_PATH).read_bytes().decode("utf-8")
    status = (NEMESIS_DIR / STATUS_PATH).read_bytes().decode("utf-8")
    dex = Path(dex_path).read_bytes()
    data = package_bytes(service, status, dex)
    zip_name = PACKAGE_ID + "-" + PACKAGE_VERSION + ".zip"
    out = Path(out_dir)
    packages = out / "usb" / "packages"
    packages.mkdir(parents=True, exist_ok=True)
    for folder in (out, packages):
        for stale in folder.glob(PACKAGE_ID + "-*.zip"):
            stale.unlink()
    (out / zip_name).write_bytes(data)
    (packages / zip_name).write_bytes(data)
    (out / "usb" / "NEMESIS.data").write_text(common.usb_descriptor(zip_name), encoding="utf-8", newline="\n")
    zip_sha = hashlib.sha256(data).hexdigest()
    dex_sha = hashlib.sha256(dex).hexdigest()
    (out / "SHA256SUMS.txt").write_text(
        "%s  %s\n%s  %s\n" % (zip_sha, zip_name, dex_sha, DEX_PATH), encoding="utf-8", newline="\n")
    return {"zip": str(out / zip_name), "zip_sha256": zip_sha, "dex_sha256": dex_sha}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dex", default=str(DEFAULT_DEX))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    args = parser.parse_args(argv)
    result = build(args.dex, args.out)
    for key in ("zip", "zip_sha256", "dex_sha256"):
        print("%s=%s" % (key, result[key]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
