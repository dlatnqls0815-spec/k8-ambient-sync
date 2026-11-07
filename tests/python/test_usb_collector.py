from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/usb/CODEX-collect-k8-ambient.sh"
SHELL = shutil.which("sh")
if SHELL is None:
    candidate = Path("C:/Program Files/Git/bin/bash.exe")
    SHELL = str(candidate) if candidate.is_file() else None


@unittest.skipUnless(SHELL, "A local POSIX shell is required for collector fixtures")
class UsbCollectorTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.source = self.root / "existing source"
        self.usb = self.root / "usb"
        self.usb.mkdir()
        self.files = ["public/reports/existing.json", "maps/kia/gl3hev/kor/260902/car.json",
                      "maps/kia/gl3hev/kor/260902/framework.json", "state/map-check.json",
                      "installed.txt", "logs/hmgapi/log.log", "logs/hmgapi/vehicle.log"]
        for name in self.files:
            target = self.source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("synthetic-existing-content", encoding="utf-8")

    def run_fixture(self, product="GL3HEV.KOR.0000.V000.000.260902", usb_root="/storage/usb0", fail_copy=False, missing_wc=False):
        text = SCRIPT.read_text(encoding="utf-8")
        # Retain the production branches and copy operations. The host fixture
        # substitutes only source paths, getprop/pwd and the Android-only PATH.
        text = text.replace("/data/hmgapi/", "${fixture_source}/")
        text = text.replace("export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin", "# host fixture PATH")
        harness = "fixture_source=" + shlex.quote(self.source.as_posix()) + "\n"
        harness += "pwd() { printf '%s\\n' " + shlex.quote(usb_root) + "; }\n"
        harness += "getprop() { case \"$1\" in\n"
        harness += "ro.build.product) printf '%s\\n' " + shlex.quote(product) + ";;\n"
        harness += "ro.build.version.sdk) echo 19;;\nro.build.version.release) echo 4.4.2;;\n"
        harness += "ro.board.platform) echo tcc803x;;\nesac; }\n"
        if fail_copy:
            harness += "cp() { return 1; }\n"
        if missing_wc:
            harness += "wc() { return 127; }\n"
        return subprocess.run([SHELL], input=harness + text, cwd=self.usb,
                              text=True, encoding="utf-8", capture_output=True, timeout=20)

    def manifest(self):
        outputs = list(self.usb.glob("K8-AMBIENT-EVIDENCE-*"))
        self.assertEqual(1, len(outputs))
        return outputs[0], (outputs[0] / "collection.txt").read_text(encoding="utf-8")

    def test_usb_collection_copies_existing_files_and_preserves_source_bytes(self):
        before = {name: (self.source / name).read_bytes() for name in self.files}
        result = self.run_fixture()
        self.assertEqual(0, result.returncode, result.stderr)
        output, manifest = self.manifest()
        self.assertIn("copied_files=7", manifest)
        self.assertEqual(before[self.files[0]], (output / "reports/existing.json").read_bytes())
        for name, content in before.items():
            self.assertEqual(content, (self.source / name).read_bytes())
        self.assertEqual(set(self.files), {p.relative_to(self.source).as_posix() for p in self.source.rglob("*") if p.is_file()})

    def test_wrong_directory_stops_before_writing(self):
        result = self.run_fixture(usb_root="/data/local/tmp")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], list(self.usb.iterdir()))

    def test_wrong_firmware_records_metadata_and_skips_all_copies(self):
        result = self.run_fixture(product="GL3HEV.KOR.0000.V000.000.260901")
        self.assertNotEqual(0, result.returncode)
        output, manifest = self.manifest()
        self.assertIn("collection=unsupported_firmware", manifest)
        self.assertEqual(["collection.txt"], sorted(p.name for p in output.iterdir()))

    def test_missing_json_is_reported_without_generating_a_replacement(self):
        (self.source / "state/map-check.json").unlink()
        result = self.run_fixture()
        self.assertEqual(0, result.returncode, result.stderr)
        output, manifest = self.manifest()
        self.assertIn("UNAVAILABLE state/map-check.json", manifest)
        self.assertFalse((output / "state/map-check.json").exists())
        self.assertFalse((self.source / "state/map-check.json").exists())

    def test_missing_map_folder_is_recorded(self):
        shutil.rmtree(self.source / "maps")
        result = self.run_fixture()
        self.assertEqual(0, result.returncode, result.stderr)
        output, manifest = self.manifest()
        self.assertIn("matching_map_folders=0", manifest)
        self.assertIn("UNAVAILABLE maps/gl3hev/kor/260902", manifest)
        self.assertFalse((output / "maps").exists())

    def test_android_without_wc_still_copies_existing_evidence(self):
        result = self.run_fixture(missing_wc=True)
        self.assertEqual(0, result.returncode, result.stderr)
        output, manifest = self.manifest()
        self.assertIn("copied_files=7", manifest)
        self.assertEqual("synthetic-existing-content", (output / "reports/existing.json").read_text())

    def test_probed_map_under_other_make_folder_is_collected(self):
        (self.source / "maps/kia").rename(self.source / "maps/unknown")
        result = self.run_fixture()
        self.assertEqual(0, result.returncode, result.stderr)
        output, manifest = self.manifest()
        self.assertIn("matching_map_folders=1", manifest)
        self.assertEqual("synthetic-existing-content",
                         (output / "maps/unknown/gl3hev/kor/260902/framework.json").read_text())

    def test_no_existing_files_returns_failure(self):
        for name in self.files:
            (self.source / name).unlink()
        result = self.run_fixture()
        self.assertNotEqual(0, result.returncode)
        _, manifest = self.manifest()
        self.assertIn("copied_files=0", manifest)
        self.assertIn("collection=no_existing_files", manifest)

    def test_copy_failure_returns_failure_and_records_incomplete_collection(self):
        result = self.run_fixture(fail_copy=True)
        self.assertNotEqual(0, result.returncode)
        _, manifest = self.manifest()
        self.assertIn("copy_failures=7", manifest)
        self.assertIn("copied_files=0", manifest)


if __name__ == "__main__":
    unittest.main()
