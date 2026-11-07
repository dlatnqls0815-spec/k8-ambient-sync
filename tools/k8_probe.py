"""Read selected K8 firmware and existing HMG evidence over an authorized ADB session.

No installation, root, engine command, scan, Bluetooth connection or write is used.
An existing map-check file is historical evidence, not proof of a running Hook.
"""
import json
from pathlib import Path
import re
import shutil
import sys

import att_evidence
import phone_probe

ProbeError = phone_probe.ProbeError
MAP_FILE = "/data/hmgapi/state/map-check.json"
MARKER = "/data/hmgapi/hook/enable"
MAX_STATE_BYTES = 256 * 1024
PRODUCT = re.compile(r"GL3(?:HEV)?\.KOR\.[A-Z0-9.]{1,80}")
POINTS = frozenset(("framework.hkmc_loader.automotive_service",
                    "framework.system_service.sml_rgb",
                    "framework.system_service.sml_configuration",
                    "framework.system_service.sml_status"))


def map_summary(text, product):
    """Drop dates, hashes, paths, exceptions and all unrecognized fields."""
    unknown = {"availability": "unavailable", "runtime_hooks": "unknown"}
    if text is None:
        return unknown
    if len(text.encode("utf-8")) > MAX_STATE_BYTES:
        raise ProbeError("Existing map-check exceeds the supported size.")
    try:
        raw = json.loads(text)
    except (ValueError, RecursionError):
        raise ProbeError("Existing map-check has an unsupported format.") from None
    if not isinstance(raw, dict):
        raise ProbeError("Existing map-check has an unsupported format.")
    ok, count, failed = raw.get("ok"), raw.get("points"), raw.get("failed")
    if (type(ok) is not bool or type(count) is not int or not 1 <= count <= 100000
            or not isinstance(failed, list) or len(failed) > count
            or any(not isinstance(item, str) or len(item) > 8192 for item in failed)
            or ok != (len(failed) == 0)):
        raise ProbeError("Existing map-check has inconsistent summary fields.")
    # Firmware equality alone cannot establish age, map/file equality or runtime load.
    same_product = raw.get("product") == product
    failures = sorted({item.split(":", 1)[0] for item in failed} & POINTS)
    return {"availability": "read", "same_product": same_product,
            "historical_ok": ok, "checked_points": count, "failed_count": len(failed),
            "ambient_related_failures": failures, "freshness": "unknown",
            "runtime_hooks": "unknown", "ambient_event_reception": "unknown"}


def probe(adb, serial=None):
    selected = phone_probe._device(adb, serial)

    def shell(*words, optional=False):
        try:
            return phone_probe._read(adb, ["-s", selected, "shell"] + list(words)).strip()
        except ProbeError:
            if optional:
                return None
            raise ProbeError("K8 read failed; use only an existing authorized session.") from None

    sdk = shell("getprop", "ro.build.version.sdk")
    product = shell("getprop", "ro.build.product")
    release = shell("getprop", "ro.build.version.release")
    if sdk != "19" or release != "4.4.2" or not PRODUCT.fullmatch(product):
        raise ProbeError("This probe is restricted to a Korean K8 Gen5W/API19 unit.")
    platform = shell("getprop", "ro.board.platform")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,48}", platform):
        raise ProbeError("Unsupported platform metadata.")
    features = shell("pm", "list", "features", optional=True)
    ble = None if features is None else any(
        re.fullmatch(r"feature:android\.hardware\.bluetooth_le(?:=[0-9]+)?", line.strip())
        for line in features.splitlines())
    package = shell("dumpsys", "package", "hmgapi.app", optional=True)
    version = None
    if package and re.search(r"(?m)^\s*Package \[hmgapi\.app\]", package):
        names = re.findall(r"(?m)^\s*versionName=([0-9]+(?:\.[0-9]+){1,4})\s*$", package)
        if len(names) == 1 and len(names[0]) <= 48:
            version = names[0]
    # -r false also covers inaccessible parents. Never label it 'not installed/off'.
    marker = shell("sh", "-c", "'if [ -r " + MARKER + " ]; then echo visible; else echo unknown; fi'",
                   optional=True)
    state = shell("cat", MAP_FILE, optional=True)
    return {"schema": "k8-readonly-evidence-v1", "android_sdk": 19,
            "android_release": release, "firmware_product": product, "platform": platform,
            "ble_feature_declared": ble, "hmg_app_version": version,
            "hook_enable_marker": "visible" if marker == "visible" else "unknown",
            "map_check": map_summary(state, product), "runtime_hooks": "unknown",
            "ambient_event_reception": "unknown"}


def main(argv=None):
    parser = phone_probe.SafeParser(prog="k8_probe.py", description=__doc__)
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--serial", help="Local selection only; never published")
    parser.add_argument("--output", default="private/k8-probe.json")
    try:
        args = parser.parse_args(argv)
        target = att_evidence.private_output(args.output)
        adb = args.adb or shutil.which("adb")
        if not adb:
            raise ProbeError("ADB not found; pass an existing executable with --adb.")
        report = probe(adb, args.serial)
        att_evidence.write_report(report, target)
        print("K8 evidence saved privately. Runtime Hook and ambient reception remain unknown.")
        return 0
    except (ProbeError, att_evidence.EvidenceError) as error:
        print("K8 probe rejected: " + str(error), file=sys.stderr)
        return 1
    except (OSError, RuntimeError):
        print("K8 probe failed: local input/output unavailable", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
