"""Read only Galaxy OS/app metadata; keep the report in the ignored private folder."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.szelk.ledlamppro"


class ProbeError(Exception):
    pass


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        raise ProbeError("Invalid command arguments; use --help")


def _read(adb, arguments):
    try:
        result = subprocess.run([str(adb)] + arguments, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        raise ProbeError("ADB unavailable; check the authorized phone connection.") from None
    if result.returncode or len(result.stdout) > 2 * 1024 * 1024:
        raise ProbeError("ADB read failed; check the authorized phone connection.")
    return result.stdout


def _device(adb, serial):
    if serial is not None and not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", serial):
        raise ProbeError("Invalid device selection.")
    lines = _read(adb, ["devices"]).splitlines()
    if not lines or lines[0].strip() != "List of devices attached":
        raise ProbeError("Unexpected ADB device listing.")
    devices = [line.split() for line in lines[1:] if line.strip()]
    if any(len(row) != 2 for row in devices):
        raise ProbeError("Unexpected ADB device listing.")
    selected = [row for row in devices if serial is None or row[0] == serial]
    if len(selected) != 1 or selected[0][1] != "device":
        raise ProbeError("Select exactly one authorized phone.")
    return selected[0][0]


def _version(value):
    if len(value) > 48 or not re.fullmatch(r"[0-9]{1,8}(?:\.[0-9]{1,8}){0,5}", value):
        raise ProbeError("Unsupported or malformed version metadata.")
    return value


def probe(adb, serial=None):
    selected = _device(adb, serial)

    def shell(*words):
        return _read(adb, ["-s", selected, "shell"] + list(words)).strip()

    manufacturer = shell("getprop", "ro.product.manufacturer")
    sdk = shell("getprop", "ro.build.version.sdk")
    characteristics = shell("getprop", "ro.build.characteristics")
    if (manufacturer.casefold() != "samsung" or not re.fullmatch(r"[0-9]{1,3}", sdk)
            or int(sdk) < 23 or "automotive" in characteristics.casefold()):
        raise ProbeError("This probe is restricted to a modern Samsung phone, not a vehicle.")
    release = _version(shell("getprop", "ro.build.version.release"))
    package = shell("dumpsys", "package", PACKAGE)
    if not re.search(r"(?m)^\s*Package \[" + re.escape(PACKAGE) + r"\]", package):
        raise ProbeError("LotusLamp X package was not found.")
    names = re.findall(r"(?m)^\s*versionName=(\S+)\s*$", package)
    codes = re.findall(r"(?m)^\s*versionCode=([0-9]+)(?:[ \t]|$)", package)
    if len(names) != 1 or len(codes) != 1 or len(codes[0]) > 19 or int(codes[0]) > 2**63 - 1:
        raise ProbeError("Unsupported or malformed package metadata.")
    name = _version(names[0])
    features = shell("pm", "list", "features").splitlines()
    return {"schema_version": 1, "package_name": PACKAGE, "version_name": name,
            "version_code": int(codes[0]), "android_release": release, "android_sdk": int(sdk),
            "ble_feature_declared": any(re.fullmatch(r"feature:android\.hardware\.bluetooth_le(?:=[0-9]+)?",
                                                      line.strip()) for line in features)}


def private_output(path):
    path = Path(path)
    try:
        resolved = (ROOT / path).resolve() if not path.is_absolute() else path.resolve()
    except (OSError, RuntimeError, ValueError):
        raise ProbeError("Private report path could not be resolved.") from None
    if not resolved.is_relative_to(ROOT / "private") or resolved == ROOT / "private":
        raise ProbeError("Report output must stay inside this project's private folder.")
    return resolved


def save_report(path, report):
    path = private_output(path)
    temporary = None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=".phone-probe-", suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            json.dump(report, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    except OSError:
        raise ProbeError("Private report could not be written.") from None
    finally:
        if temporary is not None:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass


def main(argv=None):
    parser = SafeParser(prog="phone_probe.py", description=__doc__)
    parser.add_argument("--adb", type=Path, help="Existing adb executable; otherwise use PATH")
    parser.add_argument("--serial", help="Local device selection; never included in output")
    parser.add_argument("--output", default="private/phone-probe.json")
    try:
        args = parser.parse_args(argv)
        output = private_output(args.output)
        adb = args.adb or shutil.which("adb")
        if not adb:
            raise ProbeError("ADB not found; pass --adb with an existing executable.")
        report = probe(adb, args.serial)
        save_report(output, report)
        print(json.dumps(report, sort_keys=True))
        return 0
    except ProbeError as error:
        print("Phone probe failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
