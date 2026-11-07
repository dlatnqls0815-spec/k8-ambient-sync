"""Copy existing K8 ambient evidence over a connected, authorized ADB session.

All device commands are reads. Raw output stays in the project's ignored private folder.
"""
import argparse
from datetime import datetime
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PRODUCT = re.compile(r"GL3HEV\.KOR\.[A-Z0-9.]{1,80}\.260902")
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
MAX_ADB_BYTES = 8 * 1024 * 1024
MAX_FILE_BYTES = 4 * 1024 * 1024
SML_MARKERS = ("SYS_SML_STATUS_M_RESP:", "[SystemService.setSMLRGB] red:",
               "[SystemService.setSMLConfiguration] mode:")


class CollectionError(Exception):
    pass


def run_adb(adb, args, timeout=20):
    try:
        result = subprocess.run([str(adb)] + list(args), stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise CollectionError("ADB command unavailable or timed out") from None
    if len(result.stdout) > MAX_ADB_BYTES:
        raise CollectionError("ADB response exceeds the collection limit")
    return result.returncode, result.stdout


def shell(adb, serial, *words):
    return run_adb(adb, ["-s", serial, "shell"] + list(words))


def decoded(data):
    return data.decode("utf-8", errors="replace").replace("\r", "")


def connected_devices(adb):
    code, data = run_adb(adb, ["devices", "-l"])
    lines = decoded(data).splitlines()
    if code or not lines or lines[0].strip() != "List of devices attached":
        raise CollectionError("ADB device listing failed")
    found = []
    for line in lines[1:]:
        if not line.strip():
            continue
        columns = line.split()
        if len(columns) >= 2 and columns[1] == "device" and re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", columns[0]):
            found.append(columns[0])
    return found


def select_k8(adb, requested):
    connected = connected_devices(adb)
    if requested is not None:
        if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", requested) or requested not in connected:
            raise CollectionError("Selected vehicle is not connected and authorized")
        connected = [requested]
    matches = []
    for serial in connected:
        values = []
        for prop in ("ro.build.product", "ro.build.version.sdk", "ro.build.version.release"):
            code, data = shell(adb, serial, "getprop", prop)
            values.append(decoded(data).strip() if code == 0 and len(data) < 256 else "")
        if PRODUCT.fullmatch(values[0]) and values[1:] == ["19", "4.4.2"]:
            matches.append((serial, values[0]))
    if len(matches) != 1:
        raise CollectionError("Connect exactly one supported K8 vehicle via ADB")
    return matches[0]


def private_dir(value):
    candidate = Path(value)
    resolved = (ROOT / candidate).resolve() if not candidate.is_absolute() else candidate.resolve()
    parent = (ROOT / "private").resolve()
    if not resolved.is_relative_to(parent) or resolved == parent:
        raise CollectionError("Output must be inside the project's private folder")
    if resolved.exists():
        raise CollectionError("Output folder already exists")
    return resolved


def summarize_features(raw):
    lines = decoded(raw).splitlines()
    if not any(line.startswith("feature:") for line in lines):
        return "unknown"
    return "yes" if any(re.fullmatch(r"feature:android\.hardware\.bluetooth_le(?:=[0-9]+)?", line)
                        for line in lines) else "no"


def summarize_processes(raw):
    lines = decoded(raw).splitlines()
    if not lines or "PID" not in lines[0] or "NAME" not in lines[0] or len(lines) < 2:
        return "unknown", "unknown"
    names = [line.split()[-1] for line in lines[1:] if line.split()]
    return ("observed" if any(name.endswith("/hmgapid") or name == "hmgapid" for name in names)
            else "not_observed", "observed" if "hmgapi.app" in names else "not_observed")


def list_names(adb, serial, path, suffix=None):
    code, data = shell(adb, serial, "ls", "-1", path)
    if code:
        return None
    names = [line.strip() for line in decoded(data).splitlines() if line.strip()]
    if any(not NAME.fullmatch(name) or name in (".", "..") for name in names):
        return None
    return [name for name in names if suffix is None or name.endswith(suffix)][:50]


def collect(adb, output_dir, serial=None):
    selected, product = select_k8(adb, serial)
    target = private_dir(output_dir)
    target.mkdir(parents=True)
    manifest = ["K8 ambient ADB existing-evidence collection", "product=" + product,
                "android_sdk=19", "android_release=4.4.2"]

    # The old buffer is the most time-sensitive evidence. Read it before other queries.
    code, data = shell(adb, selected, "logcat", "-b", "system", "-d", "-v", "brief",
                       "-s", "SystemService:D", "*:S")
    if code == 0 and decoded(data).lstrip().startswith(("logcat:", "Permission denied")):
        code = 1
    records = [line for line in decoded(data).splitlines()
               if re.match(r"^[VDIWEF]/SystemService\s*(?:\(|:)", line)
               and any(marker in line for marker in SML_MARKERS)] if code == 0 else []
    log_file = target / "logs/oem-sml.log"
    log_file.parent.mkdir(parents=True)
    log_file.write_text("\n".join(records) + ("\n" if records else ""), encoding="utf-8")
    manifest.extend(("oem_logcat_exit=" + str(code), "oem_sml_log_lines=" + str(len(records))))

    feature_code, feature_data = shell(adb, selected, "pm", "list", "features")
    ps_code, ps_data = shell(adb, selected, "ps")
    hmgapid, hmgapp = summarize_processes(ps_data) if ps_code == 0 else ("unknown", "unknown")
    manifest.extend(("ble_feature_declared=" + (summarize_features(feature_data) if feature_code == 0 else "unknown"),
                     "ble_feature_command_exit=" + str(feature_code),
                     "hmgapid_process_snapshot=" + hmgapid,
                     "hmg_app_process_snapshot=" + hmgapp,
                     "process_command_exit=" + str(ps_code),
                     "vehicle_service_process=unknown"))

    files = [("/data/hmgapi/state/map-check.json", "state/map-check.json"),
             ("/data/hmgapi/installed.txt", "installed.txt"),
             ("/data/hmgapi/logs/hmgapi/log.log", "logs/hmgapi.log"),
             ("/data/hmgapi/logs/hmgapi/vehicle.log", "logs/vehicle.log")]
    reports = list_names(adb, selected, "/data/hmgapi/public/reports", ".json")
    if reports is None:
        manifest.append("UNAVAILABLE reports")
    else:
        files += [("/data/hmgapi/public/reports/" + name, "reports/" + name) for name in reports]
    makes = list_names(adb, selected, "/data/hmgapi/maps")
    if makes is None:
        manifest.append("UNAVAILABLE maps")
    else:
        for make in makes:
            base = "/data/hmgapi/maps/" + make + "/gl3hev/kor/260902"
            files.extend((base + "/" + name, "maps/" + make + "/gl3hev/kor/260902/" + name)
                         for name in ("car.json", "framework.json"))
    copied = 0
    for source, relative in files:
        stat_code, stat_data = shell(adb, selected, "ls", "-ld", source)
        if stat_code or not decoded(stat_data).lstrip().startswith("-"):
            manifest.append("UNAVAILABLE " + relative)
            continue
        file_code, file_data = shell(adb, selected, "cat", source)
        if (file_code or not file_data or len(file_data) > MAX_FILE_BYTES
                or decoded(file_data).lstrip().startswith(("cat:", "Permission denied"))):
            manifest.append("UNAVAILABLE " + relative)
            continue
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(file_data)
        copied += 1
        manifest.append("COPIED " + relative)
    manifest.extend(("copied_files=" + str(copied), "collection=" +
                     ("partial_oem_reader_failure" if code else "finished")))
    (target / "collection.txt").write_text("\n".join(manifest) + "\n", encoding="utf-8")
    return target


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--serial")
    parser.add_argument("--output", default="private/vehicle-adb/K8-AMBIENT-ADB-" +
                        datetime.now().strftime("%Y%m%d-%H%M%S"))
    args = parser.parse_args(argv)
    adb = args.adb or shutil.which("adb")
    if not adb:
        print("ADB collection failed: pass --adb with an installed ADB executable", file=sys.stderr)
        return 1
    try:
        result = collect(adb, args.output, args.serial)
    except (CollectionError, OSError, ValueError) as error:
        print("ADB collection failed: " + str(error), file=sys.stderr)
        return 1
    print("K8 evidence saved privately: " + str(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
