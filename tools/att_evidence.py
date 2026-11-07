"""Import selected TShark ATT fields offline; never identify or control a device."""
import argparse
import csv
import io
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HEADERS = (
    "frame.number", "frame.time_relative", "bthci_acl.chandle", "bthci_acl.connect_in",
    "hci_h4.direction", "btl2cap.cid", "btatt.opcode", "btatt.handle", "btatt.value",
)
MAX_INPUT_BYTES = 4 * 1024 * 1024
MAX_ROWS = 10000
MAX_PAYLOAD_BYTES = 512
MAX_OUTPUT_BYTES = 8 * 1024 * 1024


class EvidenceError(ValueError):
    """Only fixed messages and row numbers may be shown to the user."""


def number(value, minimum, maximum, row):
    if len(value) > 16 or not re.fullmatch(r"(?:0[xX][0-9a-fA-F]+|[0-9]+)", value):
        raise EvidenceError("Invalid or multiple numeric fields at row " + str(row))
    parsed = int(value, 16 if value.lower().startswith("0x") else 10)
    if not minimum <= parsed <= maximum:
        raise EvidenceError("Numeric field outside supported range at row " + str(row))
    return parsed


def payload(value, row):
    if len(value) > MAX_PAYLOAD_BYTES * 3:
        raise EvidenceError("Payload size limit exceeded at row " + str(row))
    if value and not (re.fullmatch(r"(?:[0-9a-fA-F]{2})+", value)
                      or re.fullmatch(r"[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2})+", value)):
        raise EvidenceError("Invalid or multiple payload fields at row " + str(row))
    decoded = bytes.fromhex(value.replace(":", ""))
    if len(decoded) > MAX_PAYLOAD_BYTES:
        raise EvidenceError("Payload size limit exceeded at row " + str(row))
    return decoded


def candidate(packet):
    """Canonical public candidate facts, not proof of target compatibility."""
    if len(packet) != 9 or packet[:2] != b"\x7e\x07" or packet[-1] != 0xef:
        return None
    if packet[2:4] == b"\x05\x03" and packet[7] == 0x10:
        return {"kind": "rgb", "red": packet[4], "green": packet[5], "blue": packet[6]}
    if packet[2] == 1 and packet[3] <= 100 and packet[4:8] == b"\xff\xff\xff\x00":
        return {"kind": "brightness", "percent": packet[3]}
    if packet[2] == 4 and packet[3] in (0, 1) and packet[4:8] == b"\x00\xff\xff\x00":
        return {"kind": "power", "on": bool(packet[3])}
    return None


def analyze_tsv(text):
    if len(text.encode("utf-8")) > MAX_INPUT_BYTES:
        raise EvidenceError("Input size limit exceeded")
    counts = {"rows": 0, "outbound_writes": 0, "ignored_incoming": 0,
              "candidate_matches": 0, "unknown_count": 0, "unknown_epoch_writes": 0}
    report = {"schema": "k8-ambient-att-evidence-v1", "protocol_verified": False,
              "transport_type": "unknown", "candidate_profile": "wporter82-e1-candidate",
              "service_uuid": None, "characteristic_uuid": None,
              "counts": counts, "sessions": [], "candidate_events": []}
    aliases = {}
    previous_frame = 0
    try:
        rows = csv.reader(io.StringIO(text), delimiter="\t", quotechar='"', strict=True)
        if tuple(next(rows, ())) != HEADERS:
            raise EvidenceError("Expected the exact selected TShark field header")
        for row_index, fields in enumerate(rows, 2):
            counts["rows"] += 1
            if counts["rows"] > MAX_ROWS:
                raise EvidenceError("Row count limit exceeded")
            if len(fields) != len(HEADERS):
                raise EvidenceError("Unexpected field count at row " + str(row_index))
            frame = number(fields[0], 1, 0xffffffff, row_index)
            if frame <= previous_frame:
                raise EvidenceError("Frames must be unique and increasing at row " + str(row_index))
            previous_frame = frame
            try:
                if not fields[1] or len(fields[1]) > 64:
                    raise ValueError()
                relative = float(fields[1])
                if not math.isfinite(relative) or relative < 0:
                    raise ValueError()
            except ValueError:
                raise EvidenceError("Invalid relative time at row " + str(row_index)) from None
            connection = number(fields[2], 0, 0x0eff, row_index)
            epoch = number(fields[3], 1, frame, row_index) if fields[3] else None
            direction = number(fields[4], 0, 1, row_index)
            cid = number(fields[5], 0, 0xffff, row_index)
            opcode = number(fields[6], 0, 0xff, row_index)
            handle = number(fields[7], 1, 0xffff, row_index)
            packet = payload(fields[8], row_index)
            if cid != 4:
                raise EvidenceError("Only fixed ATT CID 4 is supported at row " + str(row_index))
            if opcode not in (0x12, 0x52):
                raise EvidenceError("Only unsigned ATT write request/command is supported at row " + str(row_index))
            if direction == 1:
                counts["ignored_incoming"] += 1
                continue
            counts["outbound_writes"] += 1
            alias = None
            if epoch is None:
                counts["unknown_epoch_writes"] += 1
            else:
                key = connection, epoch
                if key not in aliases:
                    aliases[key] = "connection-" + str(len(aliases) + 1)
                    report["sessions"].append({"alias": aliases[key], "connect_frame": epoch,
                                               "device_identity": "unknown"})
                alias = aliases[key]
            decoded = candidate(packet)
            if decoded is None:
                counts["unknown_count"] += 1
                continue
            counts["candidate_matches"] += 1
            report["candidate_events"].append({"frame": frame, "relative_seconds": relative,
                "session_alias": alias, "connection_epoch_known": epoch is not None,
                "attribute_handle": handle, "write_opcode": opcode, "candidate": decoded})
    except csv.Error:
        raise EvidenceError("Invalid TSV quoting or field size") from None
    return report


def private_output(value, root=None):
    root = (ROOT if root is None else Path(root)).resolve()
    private = root / "private"
    if private.resolve() != private:
        raise EvidenceError("Private output directory must not be redirected")
    target = Path(value)
    if not target.is_absolute():
        target = root / target
    target = target.resolve()
    if not target.is_relative_to(private) or target == private:
        raise EvidenceError("Output must be a file inside this repository's private directory")
    return target


def write_report(report, target):
    content = (json.dumps(report, indent=2, ensure_ascii=True, allow_nan=False) + "\n").encode("utf-8")
    if len(content) > MAX_OUTPUT_BYTES:
        raise EvidenceError("Output size limit exceeded")
    target = private_output(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    target = private_output(target)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".att-evidence-", suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        raise EvidenceError("Invalid command arguments; use --help")


def main(argv=None):
    parser = SafeParser(prog="att_evidence.py", description=__doc__)
    parser.add_argument("input", metavar="INPUT.tsv")
    parser.add_argument("--output", default="private/att-evidence.json", metavar="PRIVATE.json")
    args = parser.parse_args(argv)
    target = private_output(args.output)
    source = Path(args.input)
    if source.resolve() == target:
        raise EvidenceError("Input and output must be different files")
    with source.open("rb") as input_file:
        data = input_file.read(MAX_INPUT_BYTES + 1)
    if len(data) > MAX_INPUT_BYTES:
        raise EvidenceError("Input size limit exceeded")
    try:
        report = analyze_tsv(data.decode("utf-8-sig"))
    except UnicodeError:
        raise EvidenceError("Input must be UTF-8 TSV") from None
    write_report(report, target)
    counts = report["counts"]
    print("ATT evidence saved privately: rows={rows}, candidate_matches={candidate_matches}, "
          "unknown_count={unknown_count}. Protocol remains unverified.".format(**counts))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except EvidenceError as error:
        print("ATT evidence rejected: " + str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, RuntimeError):
        print("ATT evidence failed: local input/output unavailable", file=sys.stderr)
        sys.exit(1)
