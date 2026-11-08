"""Offline checks. No network, hardware access, dependency installs or packaging."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HMG_SHA = "180185de2c57afb2cca4858d0be7828e8f1051d6"


def run(command):
    subprocess.run([str(item) for item in command], cwd=ROOT, check=True)


def java_tools():
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin")
    compiler = shutil.which("javac")
    if compiler:
        candidates.append(Path(compiler).parent)
    candidates.extend(sorted((ROOT / ".tools/jdk8").glob("*/bin")))
    suffix = ".exe" if os.name == "nt" else ""
    for directory in candidates:
        if all((directory / (tool + suffix)).is_file() for tool in ("javac", "java")):
            return directory / ("javac" + suffix), directory / ("java" + suffix)
    raise RuntimeError("JDK 8 required: set JAVA_HOME or add javac to PATH")


def compile_java(javac, files, output, classpath=None, android_jar=None, version="7"):
    output.mkdir(parents=True, exist_ok=True)
    command = [javac, "-J-Dfile.encoding=UTF-8", "-proc:none", "-encoding", "UTF-8", "-source", version,
               "-target", version, "-Xlint:all,-options", "-d", output]
    if classpath:
        command.extend(["-classpath", os.pathsep.join(map(str, classpath))])
    if android_jar:
        command.extend(["-bootclasspath", android_jar])
    # An argument file handles Windows command length and spaces in checkout paths.
    arguments = output.parent / (output.name + "-sources.txt")
    arguments.write_text("\n".join('"' + p.as_posix() + '"' for p in sorted(files)), encoding="utf-8")
    run(command + ["@" + str(arguments)])


def metadata():
    required = ["AGENTS.md", "README.md", ".gitignore"]
    required += ["docs/" + name + ".md" for name in
                 ("PROGRESS", "DECISIONS", "ARCHITECTURE", "TESTING", "KNOWN_ISSUES")]
    for name in required:
        if not (ROOT / name).is_file():
            raise RuntimeError("Missing required document: " + name)
    descriptor = json.loads((ROOT / "diagnostic/mod.json").read_text(encoding="utf-8"))
    if descriptor["hooks"]["points"] != {"framework.hkmc_loader.automotive_service": "observe"}:
        raise RuntimeError("Diagnostic must declare only the passive observe hook")
    settings = json.loads((ROOT / "diagnostic/settings.json").read_text(encoding="utf-8"))
    if not any(s["key"] == "diagnostics" and s["default"] is False for s in settings["settings"]):
        raise RuntimeError("Diagnostics must default to OFF")
    if not (ROOT / "diagnostic/.disabled").is_file():
        raise RuntimeError("Passive diagnostic must retain its disabled marker")
    print("Project documents and passive diagnostic metadata: passed", flush=True)


def diagnostic_compile(javac, hmg_source, android_jar, output):
    source = hmg_source.resolve()
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if revision != HMG_SHA:
        raise RuntimeError("Expected audited HMG-API 1.0.2 commit " + HMG_SHA)
    if not android_jar:
        jars = sorted((ROOT / ".tools/android19").rglob("android.jar"))
        if not jars:
            raise RuntimeError("Pass --android-jar with the official Android API 19 android.jar")
        android_jar = jars[0]
    android_jar = android_jar.resolve()
    stubs, runtime, core, diagnostic = [output / name for name in ("stubs", "runtime", "core", "diagnostic")]
    compile_java(javac, (source / "core/runtime/stubs").rglob("*.java"), stubs,
                 [android_jar], android_jar, "8")
    compile_java(javac, (source / "core/runtime/java").rglob("*.java"), runtime,
                 [android_jar, stubs], android_jar, "8")
    compile_java(javac, (ROOT / "core").rglob("*.java"), core, [android_jar], android_jar)
    compile_java(javac, (ROOT / "diagnostic/src").rglob("*.java"), diagnostic,
                 [android_jar, runtime, core], android_jar)
    print("Diagnostic compiled against Android API 19 and audited HMG-API source; no DEX/install", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--diagnostic", action="store_true", help="Also compile the passive Android source")
    parser.add_argument("--hmg-source", type=Path, default=ROOT / ".research/HMG-API")
    parser.add_argument("--android-jar", type=Path)
    args = parser.parse_args()
    os.chdir(ROOT)
    javac, java = java_tools()
    metadata()
    run([sys.executable, "-m", "unittest", "discover", "-s", "tests/python", "-p", "test_*.py"])
    files = [p for folder in ("core", "tests/java", "host/src", "host/test")
             for p in (ROOT / folder).rglob("*.java")]
    (ROOT / "build").mkdir(exist_ok=True)
    # A fresh output directory prevents stale classes from passing a later check.
    output = Path(tempfile.mkdtemp(prefix="check-", dir=ROOT / "build"))
    classes = output / "classes"
    compile_java(javac, files, classes)
    run([java, "-ea", "-cp", classes, "io.github.k8ambient.core.TestMain"])
    run([java, "-ea", "-cp", classes, "io.github.k8ambient.host.HostTestMain"])
    diagnostic_tests = output / "diagnostic-tests"
    test_files = [p for folder in ("core", "diagnostic/src", "tests/diagnostic")
                  for p in (ROOT / folder).rglob("*.java")]
    compile_java(javac, test_files, diagnostic_tests)
    run([java, "-ea", "-cp", diagnostic_tests, "DiagnosticTestMain"])
    probe_tests = output / "ble-probe-tests"
    compile_java(javac, [ROOT / "ble-probe-dex/src/io/github/k8ambient/bleprobe/ProbeLogic.java",
                         ROOT / "tests/bleprobe/ProbeLogicTestMain.java"], probe_tests)
    run([java, "-ea", "-cp", probe_tests, "ProbeLogicTestMain"])
    probe_jar = args.android_jar or ROOT / ".tools/android19/android-4.4.2/android.jar"
    if probe_jar.is_file():
        # The headless probe must use only API 19 classes (app_process on the head unit).
        compile_java(javac, (ROOT / "ble-probe-dex/src").rglob("*.java"), output / "ble-probe-api19",
                     android_jar=probe_jar.resolve())
        print("Headless BLE probe: compiled against API 19 android.jar", flush=True)
    else:
        print("Headless BLE probe: API 19 android.jar not found; compile skipped", flush=True)
    if args.diagnostic:
        diagnostic_compile(javac, args.hmg_source, args.android_jar, output / "diagnostic-contract")
    else:
        print("Android diagnostic compilation not requested (use --diagnostic)", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.CalledProcessError) as error:
        print("CHECK FAILED: " + str(error), file=sys.stderr)
        sys.exit(1)
