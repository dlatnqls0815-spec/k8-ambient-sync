"""PC check of the ESP32 firmware over USB serial (no lamp or car traffic).

1. HELLO / STATUS / GET answer.
2. Parity: every CALC answer equals the Java core (tests/java EspGolden, the phone relay 0.4.8 pipeline).
3. Settings hand-over: the same lines the phone app sends (EspConfig.snapshot) are applied, saved and
   survive a reboot; a bad value is refused.
4. Simulated relay stream (SML / MODE / ALERT): TARGET lines and dry-run "TX lotus|zengge" frames.

Lamp output must be off (default): the script refuses to run when the board reports "ST output 1".
Usage: python tools/esp32_serial_check.py --port COM9 [--jdk <JDK>]
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

try:
    import serial
except ImportError:  # pragma: no cover - tool dependency
    sys.exit("pyserial required: python -m pip install --user pyserial")

ROOT = Path(__file__).resolve().parents[1]


class Board:
    def __init__(self, port):
        self.port = serial.Serial(port, 115200, timeout=0.1)
        # Opening the port resets most boards through DTR/RTS; wait for the banner to pass.
        time.sleep(2.5)
        self.drain()

    def drain(self, seconds=0.3):
        lines = []
        end = time.time() + seconds
        while time.time() < end:
            lines += self._read()
        return lines

    def _read(self):
        out = []
        raw = self.port.readline()
        while raw:
            out.append(raw.decode("utf-8", "replace").rstrip("\r\n"))
            raw = self.port.readline()
        return out

    def ask(self, line, until, timeout=3.0):
        """Sends a line and returns the received lines up to the first starting with `until`."""
        self.port.write((line + "\n").encode("utf-8"))
        got = []
        end = time.time() + timeout
        while time.time() < end:
            for text in self._read():
                got.append(text)
                if text.startswith(until):
                    return got
        raise RuntimeError("no %r reply to %r; got %r" % (until, line, got[-5:]))


def java_golden(jdk):
    javac = Path(jdk) / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    java = Path(jdk) / "bin" / ("java.exe" if os.name == "nt" else "java")
    out = Path(tempfile.mkdtemp(prefix="espgold-", dir=ROOT / "build"))
    sources = list((ROOT / "core/src").rglob("*.java")) + [ROOT / "tests/java/io/github/k8ambient/core/EspGolden.java"]
    args = out / "sources.txt"
    args.write_text("\n".join('"%s"' % p.as_posix() for p in sources), encoding="utf-8")
    subprocess.run([str(javac), "-encoding", "UTF-8", "-d", str(out), "@" + str(args)], check=True)
    text = subprocess.check_output([str(java), "-cp", str(out), "io.github.k8ambient.core.EspGolden"], text=True)
    return [line.split("\t") for line in text.splitlines() if line.strip()]


def java_snapshot(jdk, classes):
    """The phone app's snapshot lines for sample values, from the Java EspConfig."""
    javac = Path(jdk) / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    java = Path(jdk) / "bin" / ("java.exe" if os.name == "nt" else "java")
    out_dir = Path(tempfile.mkdtemp(prefix="espsnap-", dir=ROOT / "build"))
    src = out_dir / "Snap.java"
    src.write_text("""
import io.github.k8ambient.core.*;
public class Snap {
  public static void main(String[] a) {
    EspConfig.Values v = new EspConfig.Values();
    v.car = "02:00:00:00:00:01"; v.lotus = "02:00:00:00:00:02"; v.zengge = "02:00:00:00:00:03";
    v.fadeMs = 400; v.lotusMax = 22; v.hue = new int[] {60, 140, 100, 95, 70, 65};
    v.eco = new int[] {40, 150, 160};
    java.util.Map<String, int[]> t = new java.util.TreeMap<String, int[]>();
    t.put("016b015a0195", new int[] {126, 0, 255});
    v.themes = t;
    for (String line : EspConfig.snapshot(v)) System.out.println(line);
  }
}
""", encoding="utf-8")
    subprocess.run([str(javac), "-encoding", "UTF-8", "-cp", str(classes), "-d", str(out_dir), str(src)], check=True)
    text = subprocess.check_output([str(java), "-cp", str(out_dir) + os.pathsep + str(classes), "Snap"], text=True)
    return [line for line in text.splitlines() if line.strip()]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", required=True)
    parser.add_argument("--jdk", default=os.environ.get("JAVA_HOME"))
    args = parser.parse_args()
    if not args.jdk:
        sys.exit("--jdk or JAVA_HOME required")
    (ROOT / "build").mkdir(exist_ok=True)
    golden, classes = java_golden(args.jdk)
    board = Board(args.port)

    hello = board.ask("HELLO", "K8ESP")
    print("1. " + hello[-1])
    status = board.ask("STATUS", "ST end")
    if "ST output 1" in status:
        sys.exit("lamp output is ON; send 'SET output 0' first (this check must not write to lamps)")
    print("   status: " + " | ".join(s for s in status if s.startswith("ST ")))

    # Parity runs on defaults; keep the user's saved settings to restore them afterwards.
    saved = [line[4:] for line in board.ask("GET", "CFG end") if line.startswith("CFG ") and line != "CFG end"]
    board.ask("DEFAULTS", "OK DEFAULTS")
    bad = 0
    for command, expected in golden:
        reply = board.ask(command, "CALC")[-1]
        if reply != expected:
            bad += 1
            if bad <= 5:
                print("   MISMATCH %s\n     esp32 %s\n     java  %s" % (command, reply, expected))
    print("2. parity with Java core: %d/%d equal" % (len(golden) - bad, len(golden)))

    snapshot = java_snapshot(args.jdk, classes)
    for line in snapshot:
        reply = board.ask(line, "OK" if line != "SAVE" else "OK SAVE")[-1]
        if not reply.startswith("OK"):
            raise RuntimeError("refused %r: %r" % (line, reply))
    refused = board.ask("SET lotusMax 500", "ERR")[-1]
    board.ask("REBOOT", "OK REBOOT")
    time.sleep(3.5)
    board.drain(0.5)
    after = [line[4:] for line in board.ask("GET", "CFG end") if line.startswith("CFG ") and line != "CFG end"]
    expected_cfg = [l for l in snapshot if l.startswith(("SET ", "THEME "))]
    missing = [l for l in expected_cfg if l not in after]
    print("3. settings hand-over: %d lines applied, kept after reboot: %s; bad value: %s"
          % (len(snapshot), "yes" if not missing else "NO " + repr(missing), refused))

    # Simulated relay stream with the snapshot settings (themes, eco color) in place.
    stream = ["SML 00ff00a600460b0101", "MODE 03", "SML 0132019a019e0b0102", "MODE 02", "ALERT 1",
              "SML 00ff000000000b0102", "SML 000000000000010102", "ALERT 0"]
    seen = []
    for line in stream:
        board.port.write((line + "\n").encode())
        seen += board.drain(0.9)
    targets = [s for s in seen if s.startswith("TARGET")]
    tx = [s for s in seen if s.startswith("TX ")]
    print("4. simulated stream: %d TARGET lines, %d dry-run frames (lotus %d, zengge %d)"
          % (len(targets), len(tx), sum(s.startswith("TX lotus") for s in tx), sum(s.startswith("TX zengge") for s in tx)))
    for t in targets:
        print("   " + t)

    # Restore what the board had before (the user's own settings).
    board.ask("DEFAULTS", "OK DEFAULTS")
    for line in saved:
        board.ask(line, "OK")
    board.ask("SAVE", "OK SAVE")
    print("   restored %d saved setting lines" % len(saved))
    ok = bad == 0 and not missing and refused.startswith("ERR") and len(targets) >= 5 and tx
    print("ESP32 serial check: " + ("passed" if ok else "FAILED"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
