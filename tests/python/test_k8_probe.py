import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import k8_probe

SERIAL = "synthetic-k8-device"
PRODUCT = "GL3HEV.KOR.0000.V000.000.000000"
SECRET = "SYNTHETIC_PRIVATE_DETAIL"
MARKER_COMMAND = ("sh", "-c", "'if [ -r /data/hmgapi/hook/enable ]; then echo visible; else echo unknown; fi'")


class K8ProbeTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.state = {"product": PRODUCT, "ok": True, "points": 12, "failed": [],
                      "date": SECRET, "files": {SECRET: SECRET}}
        self.outputs = {
            ("devices",): "List of devices attached\n" + SERIAL + "\tdevice\n",
            ("getprop", "ro.build.version.sdk"): "19",
            ("getprop", "ro.build.product"): PRODUCT,
            ("getprop", "ro.build.version.release"): "4.4.2",
            ("getprop", "ro.board.platform"): "tcc803x",
            ("pm", "list", "features"): "feature:android.hardware.bluetooth_le\n",
            ("dumpsys", "package", "hmgapi.app"):
                "  Package [hmgapi.app] (synthetic):\n    versionName=1.0.2\n    secret=" + SECRET,
            MARKER_COMMAND: "visible",
            ("cat", k8_probe.MAP_FILE): lambda: json.dumps(self.state),
        }
        self.runner = patch.object(k8_probe.phone_probe.subprocess, "run", side_effect=self.run_adb)
        self.runner.start()
        self.addCleanup(self.runner.stop)

    def run_adb(self, command, **options):
        self.calls.append(command)
        self.assertEqual(15, options["timeout"])
        if command[1:] == ["devices"]:
            key = ("devices",)
        else:
            self.assertEqual(["-s", SERIAL, "shell"], command[1:4])
            key = tuple(command[4:])
        self.assertIn(key, self.outputs, "Only fixed read commands are allowed")
        value = self.outputs[key]
        if isinstance(value, Exception):
            raise value
        if isinstance(value, subprocess.CompletedProcess):
            return value
        return subprocess.CompletedProcess(command, 0, value() if callable(value) else value, SECRET)

    def test_selected_reads_drop_raw_details_and_never_infer_runtime_success(self):
        report = k8_probe.probe("fake-adb")
        self.assertEqual("1.0.2", report["hmg_app_version"])
        self.assertTrue(report["ble_feature_declared"])
        self.assertTrue(report["map_check"]["historical_ok"])
        self.assertEqual("visible", report["hook_enable_marker"])
        self.assertEqual("unknown", report["runtime_hooks"])
        self.assertEqual("unknown", report["map_check"]["ambient_event_reception"])
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertNotIn(SERIAL, json.dumps(report))
        self.assertEqual(9, len(self.calls))

    def test_phone_or_other_vehicle_is_rejected_before_reading_hmg_files(self):
        for key, value in (("ro.build.version.sdk", "37"), ("ro.build.product", "CVEV.USA.000000"),
                           ("ro.build.version.release", "12")):
            with self.subTest(key=key):
                original = self.outputs[("getprop", key)]
                self.outputs[("getprop", key)] = value
                self.calls.clear()
                with self.assertRaises(k8_probe.ProbeError):
                    k8_probe.probe("fake-adb")
                self.assertEqual(4, len(self.calls))
                self.outputs[("getprop", key)] = original

    def test_multiple_or_unauthorized_devices_do_not_start_shell_reads(self):
        for lines in (SERIAL + "\tunauthorized", SERIAL + "\tdevice\nother\tdevice", ""):
            self.outputs[("devices",)] = "List of devices attached\n" + lines + "\n"
            self.calls.clear()
            with self.assertRaises(k8_probe.ProbeError):
                k8_probe.probe("fake-adb")
            self.assertEqual(1, len(self.calls))

    def test_unreadable_optional_files_are_unknown_not_absent_or_disabled(self):
        for key in (("cat", k8_probe.MAP_FILE), MARKER_COMMAND,
                    ("pm", "list", "features"), ("dumpsys", "package", "hmgapi.app")):
            self.outputs[key] = subprocess.CompletedProcess([], 1, SECRET, SECRET)
        report = k8_probe.probe("fake-adb")
        self.assertIsNone(report["hmg_app_version"])
        self.assertIsNone(report["ble_feature_declared"])
        self.assertEqual("unknown", report["hook_enable_marker"])
        self.assertEqual("unavailable", report["map_check"]["availability"])
        self.assertNotIn(SECRET, json.dumps(report))

    def test_old_android_cat_permission_error_with_zero_exit_is_unavailable(self):
        self.outputs[("cat", k8_probe.MAP_FILE)] = "/system/bin/sh: cat: /data/hmgapi/state/map-check.json: Permission denied"
        report = k8_probe.probe("fake-adb")
        self.assertEqual("unavailable", report["map_check"]["availability"])

    def test_failure_summary_drops_exceptions_and_nonallowlisted_point_names(self):
        point = "framework.hkmc_loader.automotive_service"
        self.state.update(ok=False, failed=[point + ": " + SECRET, SECRET + ": failure"])
        report = k8_probe.probe("fake-adb")
        self.assertEqual([point], report["map_check"]["ambient_related_failures"])
        self.assertEqual(2, report["map_check"]["failed_count"])
        self.assertNotIn(SECRET, json.dumps(report))

    def test_other_product_is_historical_only(self):
        self.state["product"] = "CVEV.USA.000000"
        summary = k8_probe.map_summary(json.dumps(self.state), PRODUCT)
        self.assertFalse(summary["same_product"])
        self.assertEqual("unknown", summary["runtime_hooks"])
        self.assertEqual("unknown", summary["freshness"])

    def test_inconsistent_or_malformed_map_is_rejected(self):
        for raw in ("[]", "{", '{"ok":true,"points":true,"failed":[]}',
                    '{"ok":false,"points":1,"failed":[]}',
                    '{"ok":true,"points":1,"failed":["failure"]}',
                    '{"ok":true,"points":0,"failed":[]}',
                    '{"ok":false,"points":1,"failed":[12]}'):
            with self.subTest(raw=raw):
                with self.assertRaises(k8_probe.ProbeError) as error:
                    k8_probe.map_summary(raw, PRODUCT)
                self.assertNotIn(raw, str(error.exception))
        with patch.object(k8_probe, "MAX_STATE_BYTES", 3):
            with self.assertRaises(k8_probe.ProbeError):
                k8_probe.map_summary(json.dumps(self.state), PRODUCT)

    def test_invalid_output_is_rejected_before_contacting_adb(self):
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(1, k8_probe.main(["--adb", "fake-adb", "--output", "docs/report.json"]))
        self.assertEqual([], self.calls)
        self.assertNotIn(SECRET, stderr.getvalue())

    def test_adb_error_details_are_not_printed(self):
        self.outputs[("getprop", "ro.build.product")] = subprocess.CompletedProcess([], 1, SECRET, SECRET)
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(1, k8_probe.main(["--adb", "fake-adb"]))
        self.assertNotIn(SECRET, stderr.getvalue())
        self.assertNotIn(SERIAL, stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
