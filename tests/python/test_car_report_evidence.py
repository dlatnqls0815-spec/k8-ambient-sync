import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import car_report_evidence as evidence

SECRET = "SYNTHETIC_PRIVATE_DETAIL"


def synthetic_report():
    return {"report": "hmgapi car report 1", "date": SECRET,
            "unit": {"serial": SECRET}, "network": {"private": SECRET},
            "map": {"status": "probed", "hooks_on": True,
                    "missing": {"themesettings.split_item.type": SECRET}},
            "map_car": {"points": {point: {"class": SECRET} for point in evidence.SML_METHODS},
                        "differs_from_map": []},
            "verify_map": {"ok": True, "points": 342, "failed": [], "date": SECRET}}


class CarReportEvidenceTest(unittest.TestCase):
    def test_checked_probed_is_eligible_but_runtime_and_per_point_resolution_stay_unknown(self):
        summary = evidence.analyze(synthetic_report())
        self.assertEqual("probed", summary["map_status"])
        self.assertTrue(summary["reported_hooks_eligible"])
        self.assertEqual(342, summary["historical_map_check"]["resolved"])
        self.assertEqual("unknown", summary["runtime_hooks"])
        self.assertEqual("unknown", summary["ambient_event_reception"])
        self.assertFalse(summary["ble_transport_enabled"])
        self.assertEqual(["themesettings.split_item.type"], summary["visible_missing_ids"])
        self.assertEqual([], summary["listed_relay_dependency_gaps"])
        for point in summary["targets"].values():
            self.assertTrue(point["probe_candidate_present"])
            self.assertIsNone(point["provided_framework_entry_present"])
            self.assertEqual("unknown", point["runtime_observation"])
        self.assertNotIn(SECRET, json.dumps(summary))

    def test_missing_input_hook_can_block_relay_even_when_three_sml_candidates_exist(self):
        report = synthetic_report()
        point = "framework.window_manager_service.intercept_key"
        report["map"]["missing"] = {point: SECRET}
        summary = evidence.analyze(report)
        self.assertEqual([point], summary["listed_relay_dependency_gaps"])
        self.assertEqual([], summary["listed_sml_api_gaps"])
        self.assertTrue(all(item["probe_candidate_present"] for item in summary["targets"].values()))

    def test_missing_status_request_does_not_prove_absence_of_passive_broadcasts(self):
        report = synthetic_report()
        point = "framework.system_service.sml_status"
        report["map"]["missing"] = {point: SECRET}
        summary = evidence.analyze(report)
        self.assertEqual([point], summary["listed_sml_api_gaps"])
        self.assertEqual([], summary["listed_relay_dependency_gaps"])
        self.assertEqual("unknown", summary["ambient_event_reception"])

    def test_probed_candidate_and_provided_map_are_independent_evidence(self):
        report = synthetic_report()
        point = "framework.system_service.sml_rgb"
        report["map_car"]["differs_from_map"] = [point]
        summary = evidence.analyze(report, {})
        self.assertTrue(summary["targets"][point]["probe_candidate_present"])
        self.assertTrue(summary["targets"][point]["probe_differs_from_selected"])
        self.assertFalse(summary["targets"][point]["provided_framework_entry_present"])
        framework = {point: {"method": "setSMLRGB", "params": ["byte[]"] * 3,
                             "ret": "void", "static": False}}
        target = evidence.analyze(report, framework)["targets"][point]
        self.assertTrue(target["provided_signature_matches_reference"])
        self.assertEqual("unknown", target["runtime_observation"])

    def test_filtered_or_absent_missing_collections_never_prove_complete_coverage(self):
        for missing in ({}, None):
            report = synthetic_report()
            report["map"]["missing"] = missing
            report.pop("verify_map")
            report.pop("map_car")
            summary = evidence.analyze(report)
            self.assertEqual("unknown", summary["missing_collection_complete"])
            self.assertIsNone(summary["historical_map_check"])
            for target in summary["targets"].values():
                self.assertIsNone(target["probe_candidate_present"])

    def test_unsupported_or_inconsistent_input_is_rejected_with_fixed_errors(self):
        for field, value in (("map", []), ("map_car", []), ("verify_map", [])):
            report = synthetic_report()
            report[field] = value
            with self.assertRaises(evidence.Error):
                evidence.analyze(report)
        for changes in ({"ok": True, "failed": [SECRET]}, {"ok": False},
                        {"points": True}, {"points": 0}, {"failed": [12]}):
            report = synthetic_report()
            report["verify_map"].update(changes)
            with self.assertRaises(evidence.Error) as error:
                evidence.analyze(report)
            self.assertNotIn(SECRET, str(error.exception))
        for invalid in ([], {}, {"report": "not-a-car-report"}):
            with self.assertRaises(evidence.Error):
                evidence.analyze(invalid)
        report = synthetic_report()
        report["map"]["missing"] = {"invalid\n" + SECRET: SECRET}
        with self.assertRaises(evidence.Error):
            evidence.analyze(report)

    def test_cli_keeps_summary_private_and_preserves_original_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "private").mkdir()
            original = root / "private/original.json"
            before = json.dumps(synthetic_report())
            original.write_text(before, encoding="utf-8")
            output = root / "private/summary.json"
            with patch.object(evidence.att_evidence, "ROOT", root), contextlib.redirect_stdout(io.StringIO()) as stdout:
                self.assertEqual(0, evidence.main([str(original), "--output", str(output)]))
            self.assertEqual(before, original.read_text(encoding="utf-8"))
            self.assertNotIn(SECRET, output.read_text(encoding="utf-8"))
            self.assertNotIn(str(original), stdout.getvalue())
            self.assertNotIn(SECRET, stdout.getvalue())
            with patch.object(evidence.att_evidence, "ROOT", root), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, evidence.main([str(original), "--output", str(original)]))
            self.assertEqual(before, original.read_text(encoding="utf-8"))

    def test_parse_size_and_path_errors_do_not_print_raw_data(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / (SECRET + ".json")
            source.write_text(SECRET, encoding="utf-8")
            with self.assertRaises(evidence.Error) as error:
                evidence.read_json(source)
            self.assertNotIn(SECRET, str(error.exception))
            with patch.object(evidence, "MAX_BYTES", 3):
                with self.assertRaises(evidence.Error):
                    evidence.read_json(source)
            with contextlib.redirect_stderr(io.StringIO()) as stderr:
                self.assertEqual(1, evidence.main([str(source), "--output", "docs/public.json"]))
            self.assertNotIn(SECRET, stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
