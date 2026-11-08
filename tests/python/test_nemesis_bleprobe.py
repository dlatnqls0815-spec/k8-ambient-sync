import contextlib
import hashlib
import io
from pathlib import Path
import re
import struct
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import build_nemesis_bleprobe as builder

FAKE_DEX = b"dex\n035\x00" + b"\x00" * 24 + builder.MAIN_CLASS
SCRIPT = builder.SCRIPT.read_bytes().decode("utf-8")


@contextlib.contextmanager
def temporary_dir():
    directory = ROOT / "build"
    directory.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="nemesis-test-", dir=directory) as folder:
        yield Path(folder)


def ini(text):
    sections, current = {}, None
    for line in text.splitlines():
        if line.startswith("["):
            current = sections.setdefault(line.strip("[]"), {})
        elif "=" in line:
            key, value = line.split("=", 1)
            current[key] = value.strip('"')
    return sections


def central_entries(data):
    """Parse the central directory the way NEMESIS zip.c does (no zip64, no encryption)."""
    end = data.rfind(b"PK\x05\x06")
    count, size, offset = struct.unpack("<HII", data[end + 10:end + 20])
    entries, pos = [], offset
    for _ in range(count):
        header = data[pos:pos + 46]
        assert header[:4] == b"PK\x01\x02"
        flags, method = struct.unpack("<HH", header[8:12])
        csize, usize = struct.unpack("<II", header[20:28])
        namelen, extralen, commentlen = struct.unpack("<HHH", header[28:34])
        local = struct.unpack("<I", header[42:46])[0]
        mode = struct.unpack("<I", header[38:42])[0] >> 16
        name = data[pos + 46:pos + 46 + namelen].decode()
        entries.append((name, flags, method, csize, usize, local, mode))
        pos += 46 + namelen + extralen + commentlen
    return entries


class NemesisPackageTest(unittest.TestCase):
    def test_descriptor_declares_shell_app_without_hooks(self):
        sections = ini(builder.descriptor())
        app, meta = sections["app.0"], sections["nemesis"]
        self.assertEqual(app["kind"], "sh")
        self.assertEqual(app["path"], builder.SCRIPT_PATH)
        self.assertEqual(meta["version"], "1.1")
        self.assertLessEqual(len(meta["short-desc"]), 64)
        self.assertLessEqual(len(meta["detailed-desc"]), 256)
        self.assertNotIn("..", app["path"])
        self.assertEqual([s for s in sections if s.startswith("service.")], [], "no resident service")
        for hook in ("preinstall", "postinstall", "preuninstall", "postuninstall"):
            self.assertNotIn(hook, meta, "probe package must not run install hooks")

    def test_zip_is_readable_by_nemesis_reader(self):
        data = builder.package_bytes(SCRIPT, FAKE_DEX)
        entries = central_entries(data)
        self.assertEqual([e[0] for e in entries], ["NEMESIS.data", builder.SCRIPT_PATH, builder.DEX_PATH])
        for name, flags, method, csize, usize, local, mode in entries:
            self.assertFalse(flags & 1, "encrypted entries are refused")
            self.assertIn(method, (0, 8))
            self.assertLess(max(csize, usize, local), 0xFFFFFFFF)
            self.assertFalse(name.startswith("/") or ".." in name)
        self.assertEqual(entries[1][6] & 0o777, 0o755, "script is executable")
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            self.assertEqual(archive.read(builder.DEX_PATH), FAKE_DEX)

    def test_script_is_read_only_and_toolbox_compatible(self):
        builder.check_script(SCRIPT)
        code = "\n".join(line for line in SCRIPT.splitlines() if not line.lstrip().startswith("#"))
        self.assertIn("app_process /system/bin", code)
        self.assertIn("io.github.k8ambient.bleprobe.HeadlessProbe", code)
        self.assertIn('"$dex"', code)
        # The script must not change Bluetooth state, properties, packages or /system.
        for forbidden in ("setprop", "svc ", "pm ", "am ", "mount", "remount", "service call",
                          "settings put", "rm -", "/system/app", "nemesis remount"):
            self.assertNotIn(forbidden, code)
        self.assertIsNone(re.search(r"\$\(\(|\[\[|<<<|\bfunction\b", code), "avoid non-POSIX shell syntax")
        for directory in ("/storage/usb0", "/mnt/media_rw/usb0"):
            self.assertIn(directory, code)

    def test_build_is_deterministic_and_writes_usb_layout(self):
        with temporary_dir() as folder:
            dex = folder / "probe.dex"
            dex.write_bytes(FAKE_DEX)
            first = builder.build(dex, folder / "a")
            second = builder.build(dex, folder / "b")
            self.assertEqual(first["zip_sha256"], second["zip_sha256"])
            usb = ini((folder / "a" / "usb" / "NEMESIS.data").read_text(encoding="utf-8"))["nemesis"]
            self.assertEqual(usb["type"], "install")
            self.assertFalse(usb["path"].startswith("/") or ".." in usb["path"])
            copied = (folder / "a" / "usb" / usb["path"]).read_bytes()
            self.assertEqual(hashlib.sha256(copied).hexdigest(), first["zip_sha256"])
            self.assertEqual(len(list((folder / "a" / "usb" / "packages").iterdir())), 1)

    def test_rejects_wrong_inputs(self):
        with self.assertRaises(ValueError):
            builder.package_bytes(SCRIPT, b"PK\x03\x04apk")
        with self.assertRaises(ValueError):
            builder.package_bytes(SCRIPT, b"dex\n039\x00" + builder.MAIN_CLASS)
        with self.assertRaises(ValueError):
            builder.package_bytes(SCRIPT.replace("\n", "\r\n"), FAKE_DEX)


if __name__ == "__main__":
    unittest.main()
