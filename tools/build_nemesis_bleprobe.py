#!/usr/bin/env python3
"""Wrap the headless BLE probe DEX in a NEMESIS kind=sh package and a USB install layout.

Like other NEMESIS packages, nothing is installed through PackageManager: the package's shell
app runs the DEX with app_process (as root under NEMESIS). This avoids the Gen5W OEM signer
allowlist entirely. See docs/research/GEN5W_INSTALL_ALLOWLIST.md.

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
DEFAULT_DEX = ROOT / "private" / "ble-probe-dex" / "k8-ble-probe.dex"
DEFAULT_OUT = ROOT / "private" / "nemesis-bleprobe"
SCRIPT = ROOT / "ble-probe-dex" / "nemesis" / "bin" / "probe.sh"
MAIN_CLASS = b"Lio/github/k8ambient/bleprobe/HeadlessProbe;"

PACKAGE_ID = "k8ambient.bleprobe"
PACKAGE_VERSION = "0.2.0"
SCRIPT_PATH = "bin/probe.sh"
DEX_PATH = "bin/k8-ble-probe.dex"
SHORT_DESC = "Read-only BLE scan and GATT discovery for one lamp"
DETAILED_DESC = ("Lists nearby BLE devices, or connects to the lamp address in k8-ble-probe.txt on "
                 "the USB and lists its services. Sends no lamp or vehicle commands. Result: "
                 "k8-ble-probe-result.txt on the USB.")
# Fixed timestamp keeps the zip byte-identical for the same inputs.
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
        "kind=sh",
        "path=" + SCRIPT_PATH,
        "uid=root",
        "",
    ])


def usb_descriptor(zip_name):
    return "\n".join(["[nemesis]", "version=1.0", "type=install", "path=packages/" + zip_name, ""])


def _entry(name, mode):
    info = zipfile.ZipInfo(name, ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = mode << 16
    return info


def check_script(text):
    if not text.startswith("#!/system/bin/sh\n") or "\r" in text:
        raise ValueError("Probe script must be an LF-only /system/bin/sh script")


def check_dex(dex):
    if not dex.startswith(b"dex\n035\x00") or MAIN_CLASS not in dex:
        raise ValueError("Not the API 19 headless probe DEX")


def package_bytes(script, dex):
    check_script(script)
    check_dex(dex)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", allowZip64=False) as archive:
        archive.writestr(_entry("NEMESIS.data", 0o644), descriptor())
        archive.writestr(_entry(SCRIPT_PATH, 0o755), script)
        archive.writestr(_entry(DEX_PATH, 0o644), dex)
    return buffer.getvalue()


def build(dex_path, out_dir, script_path=SCRIPT):
    dex = Path(dex_path).read_bytes()
    script = Path(script_path).read_bytes().decode("utf-8")
    data = package_bytes(script, dex)
    zip_name = PACKAGE_ID + "-" + PACKAGE_VERSION + ".zip"
    out = Path(out_dir)
    (out / "usb" / "packages").mkdir(parents=True, exist_ok=True)
    for stale in out.glob(PACKAGE_ID + "-*.zip"):
        stale.unlink()
    for stale in (out / "usb" / "packages").glob(PACKAGE_ID + "-*.zip"):
        stale.unlink()
    (out / zip_name).write_bytes(data)
    (out / "usb" / "packages" / zip_name).write_bytes(data)
    (out / "usb" / "NEMESIS.data").write_text(usb_descriptor(zip_name), encoding="utf-8", newline="\n")
    (out / "usb" / "k8-ble-probe.example.txt").write_text(
        "AA:BB:CC:DD:EE:FF\n", encoding="utf-8", newline="\n")
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
