"""Host-side fixtures for the bounded, read-only K8 ADB collector."""
import contextlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import collect_k8_adb

SERIAL = "synthetic-k8"
PRODUCT = "GL3HEV.KOR.0000.V000.000.260902"
SECRET = "SYNTHETIC_PRIVATE_DETAIL"
REPORT = "report-1.json"
MAP = "/data/hmgapi/maps/unknown/gl3hev/kor/260902/"
REPORTS = "/data/hmgapi/public/reports/"


class CollectK8AdbTest(unittest.TestCase):
    def setUp(self):
        (ROOT / "private").mkdir(exist_ok=True)
        temp = tempfile.TemporaryDirectory(dir=ROOT / "private", prefix="adb-test-")
        self.addCleanup(temp.cleanup)
        self.output = Path(temp.name) / "result"
        self.calls = []
        self.values = {
            ("devices", "-l"): "List of devices attached\n" + SERIAL + " device product:synthetic\n",
            ("getprop", "ro.build.product"): PRODUCT,
            ("getprop", "ro.build.version.sdk"): "19",
            ("getprop", "ro.build.version.release"): "4.4.2",
            ("getprop", "ro.board.platform"): "tcc803x",
            ("ls", "-1", REPORTS.rstrip("/")): REPORT,
            ("ls", "-1", "/data/hmgapi/maps"): "unknown",
            ("cat", REPORTS + REPORT): '{"synthetic":"report"}',
            ("cat", MAP + "car.json"): '{"synthetic":"car"}',
            ("cat", MAP + "framework.json"): '{"synthetic":"framework"}',
            ("cat", "/data/hmgapi/state/map-check.json"): '{"synthetic":"state"}',
            ("cat", "/data/hmgapi/installed.txt"): "synthetic-installed",
            ("cat", "/data/hmgapi/logs/hmgapi/log.log"): "synthetic-hmg-log",
            ("cat", "/data/hmgapi/logs/hmgapi/vehicle.log"): "synthetic-vehicle-log",
            ("pm", "list", "features"): "feature:android.hardware.bluetooth_le\nfeature:" + SECRET,
            ("ps",): "USER PID PPID NAME\nroot 1 0 init\nroot 2 1 hmgapid\nu0_a 3 1 " + SECRET,
            ("logcat", "-b", "system", "-d", "-v", "brief", "-s", "SystemService:D", "*:S"):
                "D/SystemService( 123): [MicomEventHandler.handleMessage] SYS_SML_STATUS_M_RESP: 0x00 0x06\n"
                "D/SystemService( 123): [SystemService.setSMLRGB] red: 0xff, green: 0x00, blue: 0x00\n"
                "D/SystemService( 123): [SystemService.setSMLConfiguration] mode: 5, data: 6\n"
                "D/SystemService( 123): " + SECRET + "\n",
        }
        self.file_paths = {REPORTS + REPORT, MAP + "car.json", MAP + "framework.json",
                           "/data/hmgapi/state/map-check.json", "/data/hmgapi/installed.txt",
                           "/data/hmgapi/logs/hmgapi/log.log", "/data/hmgapi/logs/hmgapi/vehicle.log"}
        runner = patch.object(collect_k8_adb.subprocess, "run", side_effect=self.run_adb)
        runner.start()
        self.addCleanup(runner.stop)

    def run_adb(self, command, **options):
        self.calls.append(command)
        self.assertEqual("fake-adb", str(command[0]))
        if command[1:3] == ["devices", "-l"]:
            key = ("devices", "-l")
        else:
            self.assertEqual(["-s", SERIAL, "shell"], command[1:4], "Only selected K8 reads are allowed")
            key = tuple(command[4:])
        if key[:2] == ("ls", "-ld") and len(key) == 3 and key[2] in self.file_paths:
            value = self.values.get(key, "-rw-r--r-- 1 root root 64 Nov 7 14:00 " + key[2])
            if isinstance(value, subprocess.CompletedProcess):
                return value
            return subprocess.CompletedProcess(command, 0, value.encode("utf-8"), b"")
        self.assertIn(key, self.values, "Unexpected ADB command")
        value = self.values[key]
        if isinstance(value, subprocess.CompletedProcess):
            return value
        return subprocess.CompletedProcess(command, 0, value.encode("utf-8"), b"")

    def test_collects_existing_files_and_only_oem_sml_messages(self):
        collect_k8_adb.collect("fake-adb", self.output)
        self.assertEqual('{"synthetic":"report"}', (self.output / "reports" / REPORT).read_text())
        self.assertEqual('{"synthetic":"framework"}',
                         (self.output / "maps/unknown/gl3hev/kor/260902/framework.json").read_text())
        log = (self.output / "logs/oem-sml.log").read_text()
        self.assertEqual(3, len(log.splitlines()))
        self.assertNotIn(SECRET, log)
        manifest = (self.output / "collection.txt").read_text()
        self.assertIn("oem_sml_log_lines=3", manifest)
        self.assertNotIn(SECRET, manifest)
        self.assertNotIn(SERIAL, manifest)
        self.assertFalse(any(" logcat -c" in " ".join(map(str, c)) for c in self.calls))

    def test_non_k8_device_stops_before_log_or_file_read(self):
        self.values[("getprop", "ro.build.product")] = "CVEV.USA.0000"
        with self.assertRaises(Exception):
            collect_k8_adb.collect("fake-adb", self.output)
        self.assertFalse(any("logcat" in c for call in self.calls for c in call))
        self.assertFalse(any("cat" in c for call in self.calls for c in call))

    def test_no_authorized_connection_does_not_create_a_capture(self):
        self.values[("devices", "-l")] = "List of devices attached\n"
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            code = collect_k8_adb.main(["--adb", "fake-adb", "--output", str(self.output)])
        self.assertNotEqual(0, code)
        self.assertFalse(self.output.exists())
        self.assertNotIn(SERIAL, errors.getvalue())

    def test_oem_read_failure_is_distinct_from_empty_buffer(self):
        key = ("logcat", "-b", "system", "-d", "-v", "brief", "-s", "SystemService:D", "*:S")
        self.values[key] = subprocess.CompletedProcess([], 1, SECRET.encode(), SECRET.encode())
        collect_k8_adb.collect("fake-adb", self.output)
        manifest = (self.output / "collection.txt").read_text()
        self.assertIn("oem_logcat_exit=1", manifest)
        self.assertIn("oem_sml_log_lines=0", manifest)
        self.assertNotIn(SECRET, manifest)

    def test_nonregular_remote_file_is_not_copied(self):
        source = "/data/hmgapi/state/map-check.json"
        self.values[("ls", "-ld", source)] = "lrwxrwxrwx 1 root root 24 Nov 7 14:00 " + source
        collect_k8_adb.collect("fake-adb", self.output)
        self.assertFalse((self.output / "state/map-check.json").exists())
        self.assertIn("UNAVAILABLE state/map-check.json", (self.output / "collection.txt").read_text())
        self.assertFalse(any(call[-2:] == ["cat", source] for call in self.calls))


if __name__ == "__main__":
    unittest.main()
