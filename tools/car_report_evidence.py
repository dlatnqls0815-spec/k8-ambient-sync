"""Summarize an existing HMG-API Car Report locally for passive SML investigation."""
import json
from pathlib import Path
import re
import sys

import att_evidence

Error = att_evidence.EvidenceError
MAX_BYTES = 8 * 1024 * 1024
SML_METHODS = {
    "framework.system_service.sml_rgb": ("setSMLRGB", ["byte[]", "byte[]", "byte[]"], "colour_setting"),
    "framework.system_service.sml_configuration": ("setSMLConfiguration", ["int", "int"], "configuration_setting"),
    "framework.system_service.sml_status": ("requestSMLStatus", [], "status_request"),
}
# Dependencies of HmgApi.zygoteInit -> Input.zygote/install -> LightRelay.start,
# including the owning classes used by Points.field/method. Fixed upstream 1.0.2.
RELAY_POINTS = frozenset((
    "framework.activity_thread", "framework.activity_thread.handle_bind_application",
    "framework.activity_thread.initial_application", "framework.app_bind_data",
    "framework.app_bind_data.app_info", "framework.app_bind_data.process_name",
    "framework.hkmc_loader", "framework.hkmc_loader.automotive_service",
    "framework.hkmc_loader.class_loader", "framework.window_manager_service",
    "framework.window_manager_service.intercept_key",
))
POINT_ID = re.compile(r"[a-z][a-z0-9_]{0,47}(?:\.[a-z][a-z0-9_]{0,63}){1,5}")


def object_field(parent, name):
    value = parent.get(name)
    if value is None:
        return None
    if not isinstance(value, dict):
        raise Error("Unsupported object field in Car Report")
    return value


def point_names(value, mapping=False):
    if value is None:
        return None
    if not isinstance(value, dict if mapping else list) or len(value) > 10000:
        raise Error("Unsupported point collection in Car Report")
    if any(not isinstance(key, str) or not POINT_ID.fullmatch(key) for key in value):
        raise Error("Unsupported hook identifier in Car Report")
    return set(value)


def check_summary(raw):
    if raw is None:
        return None
    ok, count, failed = raw.get("ok"), raw.get("points"), raw.get("failed")
    if (type(ok) is not bool or type(count) is not int or not 1 <= count <= 100000
            or not isinstance(failed, list) or len(failed) > count
            or any(not isinstance(item, str) for item in failed)
            or ok != (not failed)):
        raise Error("Inconsistent historical map-check fields")
    return {"historical_ok": ok, "checked": count, "resolved": count - len(failed),
            "failed_count": len(failed), "freshness": "unknown"}


def analyze(report, framework=None):
    if not isinstance(report, dict) or report.get("report") != "hmgapi car report 1":
        raise Error("Expected an existing HMG-API Car Report v1 JSON")
    if framework is not None and not isinstance(framework, dict):
        raise Error("Expected a framework map object")
    selected = object_field(report, "map") or {}
    probed = object_field(report, "map_car") or {}
    checked = check_summary(object_field(report, "verify_map"))
    missing = point_names(selected.get("missing"), mapping=True)
    candidates = object_field(probed, "points")
    differing = point_names(probed.get("differs_from_map"))
    status = selected.get("status")
    if status not in (None, "none", "mismatch", "mapped", "probed", "verified"):
        raise Error("Unsupported hook map status")
    hooks_on = selected.get("hooks_on")
    if hooks_on is not None and type(hooks_on) is not bool:
        raise Error("Unsupported hook eligibility flag")
    # Report.notNoFiles filters some absent-app entries. An empty dictionary is
    # therefore only the visible missing set, never proof of complete coverage.
    targets = {}
    for point, (method, params, role) in SML_METHODS.items():
        entry = None if framework is None else framework.get(point)
        matches = None
        if isinstance(entry, dict):
            matches = (entry.get("method") == method and entry.get("params") == params
                       and entry.get("ret") == "void" and entry.get("static") is False)
        targets[point] = {
            "role": role,
            "listed_missing_in_report": None if missing is None else point in missing,
            "probe_candidate_present": None if candidates is None else point in candidates,
            "probe_differs_from_selected": None if differing is None else point in differing,
            "provided_framework_entry_present": None if framework is None else point in framework,
            "provided_signature_matches_reference": matches,
            "runtime_observation": "unknown",
        }
    visible = None if missing is None else sorted(missing)
    relay_missing = None if missing is None else sorted(missing & RELAY_POINTS)
    api_missing = None if missing is None else sorted(missing & (set(SML_METHODS) | {"framework.system_service"}))
    return {
        "schema": "k8-car-report-evidence-v1", "map_status": status,
        "reported_hooks_eligible": hooks_on, "historical_map_check": checked,
        "visible_missing_ids": visible,
        "missing_collection_complete": "unknown",
        "listed_relay_dependency_gaps": relay_missing,
        "listed_sml_api_gaps": api_missing,
        "targets": targets, "runtime_hooks": "unknown", "ambient_event_reception": "unknown",
        "k8_normalization": "unverified", "ble_transport_enabled": False,
    }


def read_json(path):
    with Path(path).open("rb") as source:
        data = source.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise Error("Input size limit exceeded")
    try:
        return json.loads(data.decode("utf-8-sig"))
    except (ValueError, UnicodeError, RecursionError):
        raise Error("Unsupported JSON input") from None


def main(argv=None):
    parser = att_evidence.SafeParser(prog="car_report_evidence.py", description=__doc__)
    parser.add_argument("input", metavar="EXISTING_CAR_REPORT.json")
    parser.add_argument("--framework-map", type=Path, help="Optional copy of the existing selected framework.json")
    parser.add_argument("--output", default="private/car-report-evidence.json")
    try:
        args = parser.parse_args(argv)
        target = att_evidence.private_output(args.output)
        inputs = [Path(args.input)] + ([] if args.framework_map is None else [args.framework_map])
        if any(source.resolve() == target for source in inputs):
            raise Error("Input and output must be different files")
        framework = None if args.framework_map is None else read_json(args.framework_map)
        summary = analyze(read_json(args.input), framework)
        att_evidence.write_report(summary, target)
        print("Existing Car Report summarized privately. Runtime event evidence requires separate observation.")
        return 0
    except Error as error:
        print("Car Report evidence rejected: " + str(error), file=sys.stderr)
        return 1
    except (OSError, RuntimeError):
        print("Car Report evidence failed: local input/output unavailable", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
