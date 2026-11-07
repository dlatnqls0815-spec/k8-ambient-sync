import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "tools"))
import phone_probe


SERIAL = "synthetic-phone-id"
PRIVATE = "synthetic-private-detail"
PACKAGE_DUMP = ("Packages:\n  Package [com.szelk.ledlamppro] (synthetic):\n"
                "    versionCode=42 minSdk=23 targetSdk=35\n    versionName=2.3.4\n"
                "    dataDir=/private/" + PRIVATE + "\n")


class PhoneProbeTest(unittest.TestCase):
    def setUp(self):
        (PROJECT_ROOT / "build").mkdir(exist_ok=True)
        self.calls = []
        self.outputs = {
            ("devices",): "List of devices attached\n" + SERIAL + "\tdevice\n",
            ("getprop", "ro.product.manufacturer"): "samsung\n",
            ("getprop", "ro.build.version.sdk"): "37\n",
            ("getprop", "ro.build.characteristics"): "phone\n",
            ("getprop", "ro.build.version.release"): "17\n",
            ("dumpsys", "package", phone_probe.PACKAGE): PACKAGE_DUMP,
            ("pm", "list", "features"): "feature:android.hardware.bluetooth_le\n",
        }
        self.runner = mock.patch.object(phone_probe.subprocess, "run", side_effect=self.run_adb)
        self.runner.start()
        self.addCleanup(self.runner.stop)

    def run_adb(self, command, **options):
        self.calls.append(command)
        self.assertTrue(options["capture_output"])
        self.assertEqual(options["timeout"], 15)
        if command[1:] == ["devices"]:
            key = ("devices",)
        else:
            self.assertEqual(command[1:4], ["-s", SERIAL, "shell"])
            key = tuple(command[4:])
        self.assertIn(key, self.outputs, "Only allowlisted read commands may run")
        value = self.outputs[key]
        if isinstance(value, Exception):
            raise value
        if isinstance(value, subprocess.CompletedProcess):
            return value
        return subprocess.CompletedProcess(command, 0, value, PRIVATE)

    def test_collects_only_whitelisted_metadata(self):
        result = phone_probe.probe("fake-adb")
        self.assertEqual(result, {"schema_version": 1, "package_name": phone_probe.PACKAGE,
                                 "version_name": "2.3.4", "version_code": 42,
                                 "android_release": "17", "android_sdk": 37,
                                 "ble_feature_declared": True})
        self.assertNotIn(SERIAL, json.dumps(result))
        self.assertNotIn(PRIVATE, json.dumps(result))
        self.assertEqual(len(self.calls), 7)

    def test_missing_ble_feature_is_only_a_declaration(self):
        self.outputs[("pm", "list", "features")] = "feature:android.hardware.bluetooth\n"
        self.assertFalse(phone_probe.probe("fake-adb")["ble_feature_declared"])

    def test_optional_numeric_feature_version_is_supported(self):
        for declaration in ("feature:android.hardware.bluetooth_le", "feature:android.hardware.bluetooth_le=1",
                            "feature:android.hardware.bluetooth_le=15"):
            with self.subTest(declaration=declaration):
                self.outputs[("pm", "list", "features")] = declaration + "\n"
                self.assertTrue(phone_probe.probe("fake-adb")["ble_feature_declared"])
        for declaration in ("feature:android.hardware.bluetooth_le_extra", "feature:android.hardware.bluetooth_le=secret"):
            with self.subTest(declaration=declaration):
                self.outputs[("pm", "list", "features")] = declaration + "\n"
                self.assertFalse(phone_probe.probe("fake-adb")["ble_feature_declared"])

    def test_requires_unambiguous_authorized_device(self):
        for listing in ("", "List of devices attached\n", "unexpected\n",
                        "List of devices attached\n" + SERIAL + "\tunauthorized\n",
                        "List of devices attached\n" + SERIAL + "\toffline\n",
                        "List of devices attached\n" + SERIAL + "\tdevice\nother\tdevice\n",
                        "List of devices attached\n" + SERIAL + "\tdevice\nother\tunauthorized\n",
                        "List of devices attached\n" + SERIAL + "\tdevice\textra\n"):
            with self.subTest(listing=listing):
                self.outputs[("devices",)] = listing
                before = len(self.calls)
                with self.assertRaises(phone_probe.ProbeError) as error:
                    phone_probe.probe("fake-adb")
                self.assertEqual(len(self.calls), before + 1)
                self.assertNotIn(SERIAL, str(error.exception))

    def test_explicit_local_device_selection(self):
        self.outputs[("devices",)] += "other\tdevice\n"
        self.assertEqual(phone_probe.probe("fake-adb", SERIAL)["version_code"], 42)
        self.assertNotIn(SERIAL, json.dumps(phone_probe.probe("fake-adb", SERIAL)))
        with self.assertRaises(phone_probe.ProbeError):
            phone_probe.probe("fake-adb", "absent")
        with self.assertRaises(phone_probe.ProbeError):
            phone_probe.probe("fake-adb", "bad serial")

    def test_vehicle_and_non_samsung_stop_before_app_read(self):
        for field, value in ((("getprop", "ro.product.manufacturer"), "other"),
                             (("getprop", "ro.build.version.sdk"), "19"),
                             (("getprop", "ro.build.version.sdk"), PRIVATE),
                             (("getprop", "ro.build.characteristics"), "tablet,automotive")):
            with self.subTest(field=field):
                original = self.outputs[field]
                self.outputs[field] = value
                self.calls.clear()
                with self.assertRaises(phone_probe.ProbeError):
                    phone_probe.probe("fake-adb")
                self.assertFalse(any("dumpsys" in call for call in self.calls))
                self.outputs[field] = original

    def test_malformed_versions_and_ambiguous_package_are_rejected(self):
        for dump in ("Unable to find package: " + PRIVATE,
                     PACKAGE_DUMP.replace("2.3.4", PRIVATE),
                     PACKAGE_DUMP.replace("2.3.4", "2.3.4 " + PRIVATE),
                     PACKAGE_DUMP.replace("versionCode=42", "versionCode=-1"),
                     PACKAGE_DUMP.replace("versionCode=42", "versionCode=42.5"),
                     PACKAGE_DUMP.replace("versionCode=42", "versionCode=99999999999999999999"),
                     PACKAGE_DUMP + "versionName=1.0\n",
                     PACKAGE_DUMP + "versionCode=11\n"):
            with self.subTest(dump=dump):
                self.outputs[("dumpsys", "package", phone_probe.PACKAGE)] = dump
                with self.assertRaises(phone_probe.ProbeError) as error:
                    phone_probe.probe("fake-adb")
                self.assertNotIn(PRIVATE, str(error.exception))
        self.outputs[("getprop", "ro.build.version.release")] = PRIVATE
        with self.assertRaises(phone_probe.ProbeError):
            phone_probe.probe("fake-adb")

    def test_adb_errors_do_not_expose_command_or_output(self):
        for result in (subprocess.TimeoutExpired(["fake-adb", SERIAL], 15, PRIVATE),
                       OSError(PRIVATE), subprocess.CompletedProcess([], 1, PRIVATE, PRIVATE)):
            with self.subTest(result=type(result).__name__):
                self.outputs[("devices",)] = result
                with self.assertRaises(phone_probe.ProbeError) as error:
                    phone_probe.probe("fake-adb")
                self.assertNotIn(PRIVATE, str(error.exception))
                self.assertNotIn(SERIAL, str(error.exception))

    def test_cli_saves_only_private_report_and_sanitized_stdout(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT / "build") as directory, mock.patch.object(phone_probe, "ROOT", Path(directory)):
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(phone_probe.main(["--adb", "fake-adb"]), 0)
            report = json.loads((Path(directory) / "private/phone-probe.json").read_text())
            self.assertEqual(json.loads(stdout.getvalue()), report)
            self.assertEqual(stderr.getvalue(), "")
            self.assertNotIn(SERIAL, stdout.getvalue())
            self.assertNotIn(PRIVATE, stdout.getvalue())
            self.assertEqual(list((Path(directory) / "private").iterdir()),
                             [Path(directory) / "private/phone-probe.json"])

    def test_output_escape_is_rejected_before_device_access(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT / "build") as directory, mock.patch.object(phone_probe, "ROOT", Path(directory)):
            for output in ("docs/result.json", "private/../../result.json", "private", str(Path(directory) / "outside.json")):
                with self.subTest(output=output), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(phone_probe.main(["--adb", "fake-adb", "--output", output]), 1)
            self.assertEqual(self.calls, [])

    def test_existing_report_survives_failed_probe(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT / "build") as directory, mock.patch.object(phone_probe, "ROOT", Path(directory)):
            report = Path(directory) / "private/phone-probe.json"
            report.parent.mkdir()
            report.write_text("previous synthetic report")
            self.outputs[("devices",)] = OSError(PRIVATE)
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(phone_probe.main(["--adb", "fake-adb"]), 1)
            self.assertEqual(report.read_text(), "previous synthetic report")
            self.assertNotIn(PRIVATE, stderr.getvalue())
            self.assertNotIn(SERIAL, stderr.getvalue())
            self.assertEqual(stdout.getvalue(), "")

    def test_path_resolution_errors_do_not_expose_private_paths(self):
        for failure in (OSError(PRIVATE), RuntimeError(PRIVATE), ValueError(PRIVATE)):
            with self.subTest(failure=type(failure).__name__), mock.patch.object(Path, "resolve", side_effect=failure):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    self.assertEqual(phone_probe.main(["--adb", "fake-adb"]), 1)
                self.assertNotIn(PRIVATE, stderr.getvalue())
                self.assertNotIn("Traceback", stderr.getvalue())

    def test_cli_parse_errors_never_echo_private_arguments(self):
        self.runner.stop()
        secret = "SYNTHETIC_SECRET_DEVICE_SERIAL"
        private_path = str(PROJECT_ROOT / "private" / secret)
        for arguments in (["--invalid", secret], ["--serial", secret, "--output"],
                          ["--invalid", private_path]):
            with self.subTest(arguments=arguments):
                completed = subprocess.run([sys.executable, str(PROJECT_ROOT / "tools/phone_probe.py")] + arguments,
                                           capture_output=True, text=True, check=False)
                self.assertEqual(completed.returncode, 1)
                self.assertNotIn(secret, completed.stdout + completed.stderr)
                self.assertNotIn(str(PROJECT_ROOT), completed.stdout + completed.stderr)
                self.assertNotIn("Traceback", completed.stderr)


if __name__ == "__main__":
    unittest.main()
