"""Summarize existing K8 Bluetooth declarations and services without connecting to a device."""
import argparse
import re
import shutil
import sys

import att_evidence
import collect_k8_adb as adb_collection


def service_presence(raw, pattern):
    text = adb_collection.decoded(raw)
    lines = text.splitlines()
    header = re.fullmatch(r"Found ([0-9]{1,5}) services:", lines[0].strip()) if lines else None
    if header is None:
        return "unknown"
    names = []
    for line in lines[1:]:
        match = re.match(r"\s*[0-9]{1,5}\s+([A-Za-z0-9_.-]{1,80}):", line)
        if match:
            names.append(match.group(1))
    if not names or len(names) != int(header.group(1)):
        return "unknown"
    return "observed" if any(re.fullmatch(pattern, name, re.IGNORECASE) for name in names) else "not_observed"


def manager_state(raw):
    text = adb_collection.decoded(raw)
    if not text.strip() or text.lstrip().startswith(("Can't find service:", "Permission Denial:")):
        return "unknown", "unknown"
    enabled = {value.lower() for value in re.findall(
        r"(?m)^\s*enabled:\s*(true|false)\s*$", text, re.IGNORECASE)}
    states = {value.upper() for value in re.findall(
        r"(?m)^\s*state:\s*(ON|OFF|TURNING_ON|TURNING_OFF)\s*$", text, re.IGNORECASE)}
    enabled_value = "on" if enabled == {"true"} else "off" if enabled == {"false"} else "unknown"
    state_value = next(iter(states)).upper() if len(states) == 1 else "unknown"
    return enabled_value, state_value


def hci_presence(raw):
    names = [name.strip() for name in adb_collection.decoded(raw).splitlines() if name.strip()]
    if not names or any(not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", name) for name in names):
        return "unknown"
    return "observed" if any(re.fullmatch(r"hci[0-9]+", name) for name in names) else "not_observed"


def probe(adb, serial=None):
    selected, _ = adb_collection.select_k8(adb, serial)

    def read(*words):
        try:
            code, output = adb_collection.shell(adb, selected, *words)
        except adb_collection.CollectionError:
            return b""
        return output if code == 0 else b""

    features = read("pm", "list", "features")
    services = read("service", "list")
    enabled, state = manager_state(read("dumpsys", "bluetooth_manager"))
    hci = read("ls", "-1", "/sys/class/bluetooth")
    return {
        "schema": "k8-ble-readonly-preflight-v1",
        "target_verified": True,
        "ble_feature_declared": adb_collection.summarize_features(features),
        "bluetooth_binder_service": service_presence(services, r"bluetooth(?:_manager)?"),
        "gatt_binder_service": service_presence(services, r"bluetooth_gatt|gatt"),
        "bluetooth_manager_enabled": enabled,
        "bluetooth_manager_state": state,
        "hci_node": hci_presence(hci),
        "ble_central_proven": False,
        "stack_health": "unknown",
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb")
    parser.add_argument("--serial")
    parser.add_argument("--output", default="private/k8-ble-preflight.json")
    args = parser.parse_args(argv)
    adb = args.adb or shutil.which("adb")
    if not adb:
        print("BLE preflight failed: pass --adb with an installed ADB executable", file=sys.stderr)
        return 1
    try:
        report = probe(adb, args.serial)
        destination = att_evidence.private_output(args.output)
        att_evidence.write_report(report, destination)
    except (adb_collection.CollectionError, att_evidence.EvidenceError, OSError, ValueError) as error:
        print("BLE preflight failed: " + str(error), file=sys.stderr)
        return 1
    print("K8 BLE read-only summary saved privately; Central capability remains unverified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
