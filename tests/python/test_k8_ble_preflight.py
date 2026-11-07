"""Fixed, read-only Bluetooth observations must never become a Central-capability claim."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import k8_ble_preflight as preflight


class K8BlePreflightTest(unittest.TestCase):
    def setUp(self):
        self.commands = []
        self.answers = {
            ("pm", "list", "features"): (0, b"feature:android.hardware.bluetooth_le\nfeature:private_name\n"),
            ("service", "list"): (0, b"Found 3 services:\n0 bluetooth_manager: [iface]\n1 bluetooth_gatt: [iface]\n2 private_service: [iface]\n"),
            ("dumpsys", "bluetooth_manager"): (0, b"Bluetooth Status\n enabled: true\n state: ON\n address: PRIVATE_DEVICE_ID\n"),
            ("ls", "-1", "/sys/class/bluetooth"): (0, b"hci0\n"),
        }

    def shell(self, adb, serial, *words):
        self.assertEqual("fake-adb", adb)
        self.assertEqual("fake-k8", serial)
        self.commands.append(words)
        answer = self.answers[words]
        if isinstance(answer, Exception):
            raise answer
        return answer

    def test_observations_are_sanitized_and_do_not_prove_central(self):
        with patch.object(preflight.adb_collection, "select_k8", return_value=("fake-k8", "k8")), \
             patch.object(preflight.adb_collection, "shell", side_effect=self.shell):
            result = preflight.probe("fake-adb")
        self.assertEqual("yes", result["ble_feature_declared"])
        self.assertEqual("observed", result["bluetooth_binder_service"])
        self.assertEqual("observed", result["gatt_binder_service"])
        self.assertEqual("on", result["bluetooth_manager_enabled"])
        self.assertEqual("ON", result["bluetooth_manager_state"])
        self.assertEqual("observed", result["hci_node"])
        self.assertFalse(result["ble_central_proven"])
        self.assertEqual("unknown", result["stack_health"])
        self.assertNotIn("PRIVATE_DEVICE_ID", json.dumps(result))
        self.assertEqual(set(self.commands), set(self.answers))

    def test_malformed_and_failed_reads_stay_unknown(self):
        self.answers = {key: (1, b"PRIVATE_DEVICE_ID") for key in self.answers}
        with patch.object(preflight.adb_collection, "select_k8", return_value=("fake-k8", "k8")), \
             patch.object(preflight.adb_collection, "shell", side_effect=self.shell):
            result = preflight.probe("fake-adb")
        self.assertEqual("unknown", result["ble_feature_declared"])
        self.assertEqual("unknown", result["bluetooth_binder_service"])
        self.assertEqual("unknown", result["gatt_binder_service"])
        self.assertEqual("unknown", result["hci_node"])
        self.assertEqual("unknown", result["bluetooth_manager_enabled"])
        self.assertNotIn("PRIVATE_DEVICE_ID", json.dumps(result))

    def test_private_json_output_contains_only_summary(self):
        (ROOT / "private").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / "private", prefix="ble-test-") as temporary:
            output = Path(temporary) / "result.json"
            with patch.object(preflight.adb_collection, "select_k8", return_value=("fake-k8", "k8")), \
                 patch.object(preflight.adb_collection, "shell", side_effect=self.shell):
                code = preflight.main(["--adb", "fake-adb", "--output", str(output)])
            self.assertEqual(0, code)
            saved = output.read_text(encoding="utf-8")
            self.assertNotIn("PRIVATE_DEVICE_ID", saved)
            self.assertFalse(json.loads(saved)["ble_central_proven"])

    def test_timeout_and_truncated_service_table_preserve_other_observations(self):
        self.answers[("dumpsys", "bluetooth_manager")] = preflight.adb_collection.CollectionError("timed out")
        self.answers[("service", "list")] = (0, b"Found 3 services:\n0 unrelated: [iface]\n")
        with patch.object(preflight.adb_collection, "select_k8", return_value=("fake-k8", "k8")), \
             patch.object(preflight.adb_collection, "shell", side_effect=self.shell):
            result = preflight.probe("fake-adb")
        self.assertEqual("unknown", result["bluetooth_manager_state"])
        self.assertEqual("unknown", result["gatt_binder_service"])
        self.assertEqual("yes", result["ble_feature_declared"])
        self.assertEqual("observed", result["hci_node"])


if __name__ == "__main__":
    unittest.main()
