import contextlib
import csv
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import att_evidence as evidence


@contextlib.contextmanager
def temporary_repo():
    directory = ROOT / "build"
    directory.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="att-test-", dir=directory) as folder:
        if not Path(folder).resolve().is_relative_to(directory.resolve()):
            raise AssertionError("Test directory must remain inside the build directory")
        yield folder


def row(**changes):
    values = {"frame.number": "10", "frame.time_relative": "0.125",
              "bthci_acl.chandle": "0x0040", "bthci_acl.connect_in": "1",
              "hci_h4.direction": "0x00", "btl2cap.cid": "0x0004",
              "btatt.opcode": "0x52", "btatt.handle": "0x0025",
              "btatt.value": "7e070503ff800010ef"}
    values.update(changes)
    return [values[key] for key in evidence.HEADERS]


def tsv(*rows, headers=evidence.HEADERS):
    text = io.StringIO(newline="")
    writer = csv.writer(text, delimiter="\t", quoting=csv.QUOTE_ALL)
    writer.writerow(headers)
    writer.writerows(rows)
    return text.getvalue()


class AttEvidenceTest(unittest.TestCase):
    def test_public_candidate_golden_vectors_match_java_reference(self):
        vectors = [
            ("7e070503ff800010ef", {"kind": "rgb", "red": 255, "green": 128, "blue": 0}),
            ("7e070164ffffff00ef", {"kind": "brightness", "percent": 100}),
            ("7e070100ffffff00ef", {"kind": "brightness", "percent": 0}),
            ("7e07040100ffff00ef", {"kind": "power", "on": True}),
            ("7e07040000ffff00ef", {"kind": "power", "on": False}),
        ]
        for encoded, expected in vectors:
            with self.subTest(encoded=encoded):
                self.assertEqual(expected, evidence.candidate(bytes.fromhex(encoded)))
        report = evidence.analyze_tsv(tsv(row(**{"btatt.value": "7E:07:05:03:FF:80:00:10:EF"})))
        self.assertEqual(vectors[0][1], report["candidate_events"][0]["candidate"])
        self.assertFalse(report["protocol_verified"])
        self.assertEqual("unknown", report["transport_type"])
        self.assertIsNone(report["service_uuid"])
        self.assertIsNone(report["characteristic_uuid"])

    def test_mismatched_and_other_profile_packets_remain_unknown(self):
        for encoded in ("7e070503ff800000ef", "7e040164ffffff00ef", "7e070165ffffff00ef",
                        "7e07040200ffff00ef", "7e07040101ffff00ef", "7e070503ff800010ee", ""):
            with self.subTest(encoded=encoded):
                report = evidence.analyze_tsv(tsv(row(**{"btatt.value": encoded})))
                self.assertEqual(1, report["counts"]["unknown_count"])
                self.assertEqual([], report["candidate_events"])

    def test_capture_preparation_rgb_and_lotus_default_length_brightness(self):
        # Synthetic builder-shaped vectors, not captured target-device packets.
        rgb = ["7e070503ff000010ef", "7e07050300ff0010ef", "7e0705030000ff10ef"]
        brightness = ["7e0401" + value + "00ff0000ef" for value in ("19", "32", "4b")]
        packets = rgb + brightness + ["8e020119"]
        report = evidence.analyze_tsv(tsv(*[
            row(**{"frame.number": str(10 + i), "frame.time_relative": str(i * 5),
                   "btatt.value": value}) for i, value in enumerate(packets)]))
        colors = [event["candidate"] for event in report["candidate_events"]]
        self.assertEqual([(255, 0, 0), (0, 255, 0), (0, 0, 255)],
                         [(c["red"], c["green"], c["blue"]) for c in colors])
        self.assertEqual(4, report["counts"]["unknown_count"])
        self.assertFalse(report["protocol_verified"])
        self.assertNotIn("percent", json.dumps(report))

    def test_exact_observed_lotus_brightness_stays_separate_from_public_candidate(self):
        levels = [5, 10, 15, 20, 23]
        report = evidence.analyze_tsv(tsv(*[
            row(**{"frame.number": str(10 + i), "frame.time_relative": str(i * 5),
                   "btatt.value": "7e0401" + format(level, "02x") + "01ff0201ef"})
            for i, level in enumerate(levels)]))
        self.assertEqual(0, report["counts"]["candidate_matches"])
        self.assertEqual(len(levels), report["counts"]["observed_brightness_matches"])
        self.assertEqual(0, report["counts"]["unknown_count"])
        self.assertEqual([], report["candidate_events"])
        self.assertEqual(levels, [event["observed"]["raw_level"] for event in report["observed_events"]])
        self.assertTrue(all(event["observed"]["format"] == "lotus-e1-04-observed"
                            for event in report["observed_events"]))
        self.assertFalse(report["protocol_verified"])
        self.assertNotIn("percent", json.dumps(report))
        self.assertNotIn("7e0401", json.dumps(report))

    def test_observed_shape_and_range_fail_closed(self):
        for encoded in ("7e04010401ff0201ef", "7e04011801ff0201ef",
                        "7e04016401ff0201ef", "7e04010500ff0201ef",
                        "7e04010501ff0200ef", "7e04010501ff0201ee",
                        "7e04010501ff0201ef00"):
            with self.subTest(encoded=encoded):
                report = evidence.analyze_tsv(tsv(row(**{"btatt.value": encoded})))
                self.assertEqual(0, report["counts"]["observed_brightness_matches"])
                self.assertEqual(1, report["counts"]["unknown_count"])
                self.assertEqual([], report["observed_events"])

    def test_incoming_observed_shape_is_not_phone_command(self):
        report = evidence.analyze_tsv(tsv(row(**{
            "hci_h4.direction": "1", "btatt.value": "7e04010501ff0201ef"})))
        self.assertEqual(1, report["counts"]["ignored_incoming"])
        self.assertEqual(0, report["counts"]["observed_brightness_matches"])
        self.assertEqual([], report["observed_events"])

    def test_unknown_payload_is_never_copied_into_report(self):
        secret = "SYNTHETIC_PRIVATE_TOKEN"
        encoded = secret.encode().hex()
        report = evidence.analyze_tsv(tsv(row(**{"btatt.value": encoded})))
        serialized = json.dumps(report)
        self.assertNotIn(secret, serialized)
        self.assertNotIn(encoded, serialized)
        self.assertEqual(1, report["counts"]["unknown_count"])

    def test_connection_handle_reuse_creates_distinct_aliases(self):
        report = evidence.analyze_tsv(tsv(
            row(), row(**{"frame.number": "11"}),
            row(**{"frame.number": "20", "bthci_acl.connect_in": "19"}),
            row(**{"frame.number": "21", "bthci_acl.connect_in": "19", "bthci_acl.chandle": "0x0041"})))
        aliases = [event["session_alias"] for event in report["candidate_events"]]
        self.assertEqual(aliases[0], aliases[1])
        self.assertNotEqual(aliases[1], aliases[2])
        self.assertNotEqual(aliases[2], aliases[3])
        self.assertEqual(3, len(report["sessions"]))
        self.assertNotIn("chandle", json.dumps(report))

    def test_missing_epoch_does_not_invent_or_merge_a_session(self):
        report = evidence.analyze_tsv(tsv(row(**{"bthci_acl.connect_in": ""}),
            row(**{"frame.number": "11", "bthci_acl.connect_in": ""})))
        self.assertEqual([], report["sessions"])
        self.assertEqual(2, report["counts"]["unknown_epoch_writes"])
        for event in report["candidate_events"]:
            self.assertIsNone(event["session_alias"])
            self.assertFalse(event["connection_epoch_known"])

    def test_incoming_write_does_not_count_as_a_phone_command(self):
        report = evidence.analyze_tsv(tsv(row(**{"hci_h4.direction": "1"})))
        self.assertEqual(1, report["counts"]["ignored_incoming"])
        self.assertEqual(0, report["counts"]["outbound_writes"])
        self.assertEqual([], report["candidate_events"])
        self.assertEqual([], report["sessions"])

    def test_request_and_command_are_both_supported(self):
        report = evidence.analyze_tsv(tsv(row(**{"btatt.opcode": "0x12"})))
        self.assertEqual(0x12, report["candidate_events"][0]["write_opcode"])

    def test_headers_and_quoting_fail_closed(self):
        cases = [tsv(row(), headers=evidence.HEADERS[:-1]),
                 tsv(row(), headers=evidence.HEADERS + ("device.address",)),
                 tsv(row(), headers=(evidence.HEADERS[0],) * len(evidence.HEADERS)),
                 tsv(row()[:-1]), tsv() + '"unterminated']
        for malformed in cases:
            with self.subTest(malformed=malformed[:20]):
                with self.assertRaises(evidence.EvidenceError):
                    evidence.analyze_tsv(malformed)

    def test_multiple_occurrences_are_rejected_not_silently_selected(self):
        for field, value in (("btatt.handle", "0x0025,0x0026"),
                             ("btatt.value", "7e07040100ffff00ef,7e07040000ffff00ef"),
                             ("hci_h4.direction", "0,1"), ("frame.time_relative", "0.1,0.2")):
            with self.subTest(field=field):
                with self.assertRaises(evidence.EvidenceError):
                    evidence.analyze_tsv(tsv(row(**{field: value})))

    def test_numeric_ranges_time_and_supported_transport_scope(self):
        invalid = {"frame.number": ["0", "4294967296", "-1", "1.5"],
                   "bthci_acl.chandle": ["0x0f00", "0x0fff", "0x1000", "-1"], "bthci_acl.connect_in": ["0", "11"],
                   "hci_h4.direction": ["2", "255"], "btl2cap.cid": ["0x0040", "5"],
                   "btatt.opcode": ["0x1b", "0xd2", "0x16"], "btatt.handle": ["0", "65536"],
                   "frame.time_relative": ["NaN", "inf", "-0.1", "1e309", ""]}
        for field, values in invalid.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    with self.assertRaises(evidence.EvidenceError):
                        evidence.analyze_tsv(tsv(row(**{field: value})))
        with self.assertRaises(evidence.EvidenceError):
            evidence.analyze_tsv(tsv(row(), row()))
        supported = evidence.analyze_tsv(tsv(row(**{"bthci_acl.chandle": "0x0eff"})))
        self.assertEqual(1, supported["counts"]["candidate_matches"])

    def test_payload_syntax_and_size_bounds(self):
        for value in ("f", "0x7e", "gg", "7e::07", "7e 07", "ff" * 513):
            with self.subTest(size=len(value)):
                with self.assertRaises(evidence.EvidenceError):
                    evidence.analyze_tsv(tsv(row(**{"btatt.value": value})))

    def test_row_and_input_bounds(self):
        with patch.object(evidence, "MAX_ROWS", 1):
            with self.assertRaises(evidence.EvidenceError):
                evidence.analyze_tsv(tsv(row(), row(**{"frame.number": "11"})))
        text = tsv(row())
        with patch.object(evidence, "MAX_INPUT_BYTES", len(text.encode()) - 1):
            with self.assertRaises(evidence.EvidenceError):
                evidence.analyze_tsv(text)

    def test_output_must_remain_inside_private(self):
        with temporary_repo() as folder:
            root = Path(folder).resolve()
            self.assertEqual(root / "private/report.json", evidence.private_output("private/report.json", root))
            for name in ("report.json", "private/../report.json", "private", root.parent / "outside.json"):
                with self.subTest(name=str(name)):
                    with self.assertRaises(evidence.EvidenceError):
                        evidence.private_output(name, root)

    def test_redirected_private_directory_is_rejected(self):
        with temporary_repo() as folder:
            root = Path(folder).resolve()
            outside = root / "elsewhere"
            outside.mkdir()
            try:
                (root / "private").symlink_to(outside, target_is_directory=True)
            except OSError:
                self.skipTest("Creating directory symlinks is unavailable on this host")
            with self.assertRaises(evidence.EvidenceError):
                evidence.private_output("private/report.json", root)

    def test_cli_writes_private_report_without_printing_input(self):
        with temporary_repo() as folder, patch.object(evidence, "ROOT", Path(folder)):
            source = Path(folder) / "synthetic-device-identifier.tsv"
            source.write_text(tsv(row()), encoding="utf-8-sig")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, evidence.main([str(source)]))
            report = json.loads((Path(folder) / "private/att-evidence.json").read_text())
            self.assertEqual(1, report["counts"]["candidate_matches"])
            self.assertNotIn(str(source), output.getvalue())
            self.assertNotIn("7e070503", output.getvalue())
            self.assertNotIn("255", output.getvalue())

    def test_atomic_failure_and_output_limit_preserve_previous_report(self):
        with temporary_repo() as folder, patch.object(evidence, "ROOT", Path(folder)):
            target = Path(folder) / "private/report.json"
            target.parent.mkdir()
            target.write_text("previous-report")
            report = evidence.analyze_tsv(tsv(row()))
            with patch.object(evidence.os, "replace", side_effect=OSError("synthetic-private-path")):
                with self.assertRaises(OSError):
                    evidence.write_report(report, target)
            self.assertEqual("previous-report", target.read_text())
            self.assertEqual([], list(target.parent.glob(".att-evidence-*.tmp")))
            with patch.object(evidence, "MAX_OUTPUT_BYTES", 1):
                with self.assertRaises(evidence.EvidenceError):
                    evidence.write_report(report, target)
            self.assertEqual("previous-report", target.read_text())

    def test_input_and_output_cannot_be_same_file(self):
        with temporary_repo() as folder, patch.object(evidence, "ROOT", Path(folder)):
            source = Path(folder) / "private/source.tsv"
            source.parent.mkdir()
            source.write_text(tsv(row()))
            with self.assertRaises(evidence.EvidenceError):
                evidence.main([str(source), "--output", str(source)])
            self.assertTrue(source.read_text().startswith('"frame.number"'))

    def test_cli_errors_never_echo_raw_argv_or_paths(self):
        secret = "SYNTHETIC_SECRET_DEVICE_SERIAL"
        for arguments in (["--invalid", secret], [str(ROOT / "private" / secret)]):
            completed = subprocess.run([sys.executable, str(ROOT / "tools/att_evidence.py")] + arguments,
                capture_output=True, text=True, check=False)
            self.assertEqual(1, completed.returncode)
            self.assertNotIn(secret, completed.stdout + completed.stderr)
            self.assertNotIn(str(ROOT), completed.stdout + completed.stderr)
            self.assertNotIn("Traceback", completed.stderr)


if __name__ == "__main__":
    unittest.main()
