import contextlib
import hashlib
import io
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import build_nemesis_bleprobe as builder

FAKE_APK = b"PK\x03\x04synthetic-apk-bytes"


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
        name = data[pos + 46:pos + 46 + namelen].decode()
        entries.append((name, flags, method, csize, usize, local))
        pos += 46 + namelen + extralen + commentlen
    return entries


class NemesisPackageTest(unittest.TestCase):
    def test_descriptor_declares_apk_install_and_uninstall(self):
        app = ini(builder.descriptor())["app.0"]
        meta = ini(builder.descriptor())["nemesis"]
        self.assertEqual(app["kind"], "apk")
        self.assertEqual(app["path"], builder.APK_PATH)
        self.assertEqual(app["package"], "io.github.k8ambient.bleprobe")
        self.assertTrue(app["component"].startswith(app["package"] + "/"))
        self.assertEqual(meta["version"], "1.1")
        self.assertLessEqual(len(meta["short-desc"]), 64)
        self.assertLessEqual(len(meta["detailed-desc"]), 256)
        self.assertNotIn("..", app["path"])
        for hook in ("preinstall", "postinstall", "preuninstall", "postuninstall"):
            self.assertNotIn(hook, meta, "probe package must not run root scripts")

    def test_zip_is_readable_by_nemesis_reader(self):
        data = builder.package_bytes(FAKE_APK)
        entries = central_entries(data)
        self.assertEqual([e[0] for e in entries], ["NEMESIS.data", builder.APK_PATH])
        for name, flags, method, csize, usize, local in entries:
            self.assertFalse(flags & 1, "encrypted entries are refused")
            self.assertIn(method, (0, 8))
            self.assertLess(max(csize, usize, local), 0xFFFFFFFF)
            self.assertFalse(name.startswith("/") or ".." in name)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            self.assertEqual(archive.read(builder.APK_PATH), FAKE_APK)

    def test_build_is_deterministic_and_writes_usb_layout(self):
        with temporary_dir() as folder:
            apk = folder / "probe.apk"
            apk.write_bytes(FAKE_APK)
            digest = hashlib.sha256(FAKE_APK).hexdigest()
            first = builder.build(apk, folder / "a", digest)
            second = builder.build(apk, folder / "b", digest)
            self.assertEqual(first["zip_sha256"], second["zip_sha256"])
            usb = ini((folder / "a" / "usb" / "NEMESIS.data").read_text(encoding="utf-8"))["nemesis"]
            self.assertEqual(usb["type"], "install")
            self.assertFalse(usb["path"].startswith("/") or ".." in usb["path"])
            copied = (folder / "a" / "usb" / usb["path"]).read_bytes()
            self.assertEqual(hashlib.sha256(copied).hexdigest(), first["zip_sha256"])

    def test_rejects_unverified_apk(self):
        with temporary_dir() as folder:
            apk = folder / "probe.apk"
            apk.write_bytes(FAKE_APK)
            with self.assertRaises(ValueError):
                builder.build(apk, folder / "out")
            apk.write_bytes(b"not a zip")
            with self.assertRaises(ValueError):
                builder.build(apk, folder / "out", hashlib.sha256(b"not a zip").hexdigest())
            self.assertFalse((folder / "out").exists())


if __name__ == "__main__":
    unittest.main()
