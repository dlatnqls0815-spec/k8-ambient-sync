import io
from pathlib import Path
import re
import sys
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import build_nemesis_relay as relay

FAKE_DEX = b"dex\n035\x00" + b"\x00" * 24 + relay.MAIN_CLASS
SERVICE = (relay.NEMESIS_DIR / relay.SERVICE_PATH).read_bytes().decode("utf-8")
STATUS = (relay.NEMESIS_DIR / relay.STATUS_PATH).read_bytes().decode("utf-8")


def ini(text):
    sections, current = {}, None
    for line in text.splitlines():
        if line.startswith("["):
            current = sections.setdefault(line.strip("[]"), {})
        elif "=" in line:
            key, value = line.split("=", 1)
            current[key] = value.strip('"')
    return sections


def code(script):
    return "\n".join(line for line in script.splitlines() if not line.lstrip().startswith("#"))


class NemesisRelayPackageTest(unittest.TestCase):
    def test_descriptor_registers_restarting_service_without_hooks(self):
        sections = ini(relay.descriptor())
        meta, service, app = sections["nemesis"], sections["service.0"], sections["app.0"]
        self.assertEqual(service["mode"], "restart")
        self.assertEqual(service["path"], relay.SERVICE_PATH)
        self.assertEqual(app["kind"], "sh")
        self.assertLessEqual(len(meta["short-desc"]), 64)
        self.assertLessEqual(len(meta["detailed-desc"]), 256)
        for hook in ("preinstall", "postinstall", "preuninstall", "postuninstall"):
            self.assertNotIn(hook, meta)

    def test_scripts_are_read_only_toward_vehicle(self):
        for script in (SERVICE, STATUS):
            body = code(script)
            for forbidden in ("setprop", "svc ", "pm ", "am ", "mount", "service call", "settings put",
                              "rm -", "/system/app", "persist."):
                self.assertNotIn(forbidden, body)
            self.assertIsNone(re.search(r"\[\[|<<<|\bfunction\b", body))
        self.assertIn("app_process /system/bin io.github.k8ambient.relay.CarRelay", code(SERVICE))
        self.assertIn('getprop sys.boot_completed', code(SERVICE))

    def test_zip_layout(self):
        data = relay.package_bytes(SERVICE, STATUS, FAKE_DEX)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = archive.namelist()
            self.assertEqual(names, ["NEMESIS.data", relay.STATUS_PATH, relay.SERVICE_PATH, relay.DEX_PATH])
            for info in archive.infolist():
                self.assertFalse(info.flag_bits & 1)
                self.assertFalse(info.filename.startswith("/") or ".." in info.filename)

    def test_rejects_wrong_dex(self):
        with self.assertRaises(ValueError):
            relay.package_bytes(SERVICE, STATUS, b"dex\n035\x00" + b"Lio/github/k8ambient/bleprobe/HeadlessProbe;")


if __name__ == "__main__":
    unittest.main()
