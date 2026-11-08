#!/usr/bin/env python3
"""Compile an app_process tool for API 19 (BLE probe or car relay) and convert it to a DEX.

Uses the official Android API 19 android.jar as bootclasspath (no PC-only APIs) and D8 with
--min-api 19 (DEX 035). Outputs go to private/. Offline only.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JAR = ROOT / ".tools" / "android19" / "android-4.4.2" / "android.jar"
TARGETS = {
    "probe": ([ROOT / "ble-probe-dex" / "src"], ROOT / "private" / "ble-probe-dex" / "k8-ble-probe.dex",
              b"Lio/github/k8ambient/bleprobe/HeadlessProbe;"),
    "relay": ([ROOT / "car-relay" / "src", ROOT / "core" / "src"],
              ROOT / "private" / "car-relay" / "k8-ambient-relay.dex",
              b"Lio/github/k8ambient/relay/CarRelay;"),
}


def check_platform(android_jar):
    properties = android_jar.parent / "source.properties"
    if not properties.is_file() or "AndroidVersion.ApiLevel=19" not in properties.read_text(encoding="utf-8"):
        raise ValueError("android.jar must come from the Android SDK Platform 19 package")


def verify_dex(data, main_class):
    if not data.startswith(b"dex\n035\x00"):
        raise ValueError("Expected DEX format 035 for API 19")
    if main_class not in data:
        raise ValueError("Main class missing from DEX")


def build(javac, java, d8_jar, android_jar, output, target="probe"):
    roots, _, main_class = TARGETS[target]
    android_jar = Path(android_jar).resolve()
    check_platform(android_jar)
    sources = sorted(str(p) for root in roots for p in root.rglob("*.java"))
    if not sources:
        raise ValueError("No sources found")
    # Android build tools are happiest with an ASCII working directory.
    with tempfile.TemporaryDirectory(prefix="k8-dex-") as stage:
        stage = Path(stage)
        classes, dex_dir = stage / "classes", stage / "dex"
        classes.mkdir()
        dex_dir.mkdir()
        jar = stage / "android.jar"
        shutil.copyfile(android_jar, jar)
        subprocess.run([javac, "-encoding", "UTF-8", "-source", "7", "-target", "7", "-Xlint:-options",
                        "-bootclasspath", str(jar), "-d", str(classes)] + sources, check=True)
        class_files = sorted(str(p) for p in classes.rglob("*.class"))
        subprocess.run([java, "-cp", str(d8_jar), "com.android.tools.r8.D8", "--min-api", "19",
                        "--lib", str(jar), "--output", str(dex_dir)] + class_files, check=True)
        data = (dex_dir / "classes.dex").read_bytes()
    verify_dex(data, main_class)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
    return hashlib.sha256(data).hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--jdk", default=os.environ.get("JAVA_HOME"), help="JDK home (javac/java)")
    parser.add_argument("--d8", required=True, help="path to build-tools lib/d8.jar")
    parser.add_argument("--android-jar", default=str(DEFAULT_JAR))
    parser.add_argument("--target", choices=sorted(TARGETS), default="probe")
    parser.add_argument("--out", help="default: private/<target dir>/...dex")
    args = parser.parse_args(argv)
    if not args.jdk:
        raise SystemExit("Set JAVA_HOME or pass --jdk")
    suffix = ".exe" if os.name == "nt" else ""
    javac = str(Path(args.jdk) / "bin" / ("javac" + suffix))
    java = str(Path(args.jdk) / "bin" / ("java" + suffix))
    out = args.out or str(TARGETS[args.target][1])
    digest = build(javac, java, args.d8, args.android_jar, out, args.target)
    print("dex=%s\ndex_sha256=%s" % (out, digest))
    return 0


if __name__ == "__main__":
    sys.exit(main())
