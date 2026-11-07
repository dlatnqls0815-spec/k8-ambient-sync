"""Offline checks for the bounded live OEM log selector."""
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import collect_k8_adb
import watch_k8_sml_adb


class FakeProcess:
    def __init__(self, command, **kwargs):
        self.command = command
        self.stdout = io.BytesIO(
            b"D/SystemService( 123): [MicomEventHandler.handleMessage] SYS_SML_STATUS_M_RESP: 0x01\n"
            b"D/SystemService( 123): unrelated-private-detail\n"
            b"D/Other( 456): SYS_SML_STATUS_M_RESP: private-other-tag\n"
            b"D/SystemService( 123): [SystemService.setSMLRGB] red: 0xff\n")
        self.returncode = 0

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        return self.returncode

    def terminate(self):
        self.returncode = -1


class LiveWatchTest(unittest.TestCase):
    def setUp(self):
        (ROOT / "private").mkdir(exist_ok=True)
        temp = tempfile.TemporaryDirectory(dir=ROOT / "private", prefix="watch-test-")
        self.addCleanup(temp.cleanup)
        self.output = Path(temp.name) / "result"

    def test_only_sml_lines_are_saved_with_a_bounded_read(self):
        commands = []

        def popen(command, **kwargs):
            commands.append(command)
            return FakeProcess(command, **kwargs)

        with patch.object(watch_k8_sml_adb.base, "select_k8", return_value=("synthetic", "GL3HEV.KOR.0000.260902")):
            with patch.object(watch_k8_sml_adb.subprocess, "Popen", side_effect=popen):
                _, count, status = watch_k8_sml_adb.watch("fake-adb", self.output, seconds=1)
        self.assertEqual((2, "finished"), (count, status))
        output = (self.output / "logs/oem-sml-live.log").read_text()
        self.assertEqual(2, len(output.splitlines()))
        self.assertNotIn("private", output)
        self.assertNotIn("synthetic", (self.output / "collection.txt").read_text())
        self.assertEqual(["fake-adb", "-s", "synthetic", "shell", "logcat", "-b", "system"], commands[0][:7])
        self.assertNotIn("-c", commands[0])

    def test_wrong_vehicle_stops_before_starting_reader(self):
        with patch.object(watch_k8_sml_adb.base, "select_k8", side_effect=collect_k8_adb.CollectionError("wrong")):
            with patch.object(watch_k8_sml_adb.subprocess, "Popen") as popen:
                with self.assertRaises(collect_k8_adb.CollectionError):
                    watch_k8_sml_adb.watch("fake-adb", self.output, seconds=1)
                popen.assert_not_called()
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
