"""Watch only OEM SML log lines on a connected K8 for at most five minutes.

Reads the system log; it does not change vehicle state or clear any log buffer.
"""
import argparse
from datetime import datetime
import re
import shutil
import subprocess
import sys
import threading

import collect_k8_adb as base

MAX_SECONDS = 300
MAX_LINES = 10000
MAX_LOG_BYTES = 2 * 1024 * 1024
TAG = re.compile(r"^[VDIWEF]/SystemService\s*(?:\(|:)")


def watch(adb, output_dir, serial=None, seconds=MAX_SECONDS):
    if not 1 <= seconds <= MAX_SECONDS:
        raise base.CollectionError("Watch duration must be 1 to 300 seconds")
    selected, product = base.select_k8(adb, serial)
    target = base.private_dir(output_dir)
    target.mkdir(parents=True)
    log_file = target / "logs/oem-sml-live.log"
    log_file.parent.mkdir(parents=True)
    command = [str(adb), "-s", selected, "shell", "logcat", "-b", "system",
               "-v", "brief", "-s", "SystemService:D", "*:S"]
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   stdin=subprocess.DEVNULL)
    except OSError:
        raise base.CollectionError("ADB live log reader could not start") from None
    expired = threading.Event()

    def end_watch():
        expired.set()
        if process.poll() is None:
            process.terminate()

    timer = threading.Timer(seconds, end_watch)
    timer.daemon = True
    timer.start()
    count = total_bytes = 0
    status = "finished"
    try:
        with log_file.open("w", encoding="utf-8") as output:
            for raw in process.stdout:
                line = base.decoded(raw).rstrip("\n")
                if not TAG.match(line) or not any(marker in line for marker in base.SML_MARKERS):
                    continue
                encoded_bytes = len(line.encode("utf-8")) + 1
                if encoded_bytes > 4096 or count >= MAX_LINES or total_bytes + encoded_bytes > MAX_LOG_BYTES:
                    status = "limit_reached"
                    process.terminate()
                    break
                output.write(line + "\n")
                output.flush()
                count += 1
                total_bytes += encoded_bytes
        process.wait(timeout=5)
    finally:
        timer.cancel()
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=5)
    if expired.is_set():
        status = "time_limit"
    elif process.returncode != 0 and status == "finished":
        status = "reader_stopped"
    manifest = ("K8 live SML ADB observation\nproduct=" + product + "\n"
                + "duration_limit_seconds=" + str(seconds) + "\n"
                + "oem_sml_live_lines=" + str(count) + "\n"
                + "collection=" + status + "\n")
    (target / "collection.txt").write_text(manifest, encoding="utf-8")
    return target, count, status


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb")
    parser.add_argument("--serial")
    parser.add_argument("--seconds", type=int, default=MAX_SECONDS)
    parser.add_argument("--output", default="private/vehicle-adb/K8-AMBIENT-LIVE-"
                        + datetime.now().strftime("%Y%m%d-%H%M%S"))
    args = parser.parse_args(argv)
    adb = args.adb or shutil.which("adb")
    if not adb:
        print("Live SML collection failed: pass --adb with an installed ADB executable", file=sys.stderr)
        return 1
    try:
        path, count, status = watch(adb, args.output, args.serial, args.seconds)
    except (base.CollectionError, OSError, subprocess.TimeoutExpired) as error:
        print("Live SML collection failed: " + str(error), file=sys.stderr)
        return 1
    print("Live SML lines=" + str(count) + " status=" + status + " saved=" + str(path))
    return 0 if status in ("time_limit", "finished") else 1


if __name__ == "__main__":
    sys.exit(main())
