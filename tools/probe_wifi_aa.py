"""Read-only snapshot of the head unit's Wi-Fi / wireless Android Auto state over an existing ADB session.

Run it several times with different --label values (for example aa_off, aa_on, wifi_client_on) and
compare the snapshots with --diff. Every device command is a read from a fixed allowlist: no setprop,
no svc/settings put, no file writes on the car. Wi-Fi credential files are never read (only file names).
Raw output stays under the project's ignored private/ folder.
"""
import argparse
from datetime import datetime
from pathlib import Path
import re
import sys

import collect_k8_adb as base

ROOT = Path(__file__).resolve().parents[1]
LABEL = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,39}")
SECRET = re.compile(r"(?i)psk|passw|wep_key|private_key|passphrase")
# Lines of `ps` worth keeping: Wi-Fi stack, hotspot daemons and anything that looks like phone projection.
PS_KEEP = re.compile(r"(?i)hostapd|wpa_supp|dnsmasq|dhcp|wifi|wlan|projection|androidauto|\.auto|gearhead|"
                     r"carplay|hmgapi|hkmc|nemesis|adbd")
LOG_KEEP = re.compile(r"(?i)wifi|wlan|hostapd|wpa_|softap|tether|projection|androidauto|gearhead|"
                      r"android.auto|carplay|aoa|hotspot")

# (section name, device command words). The allowlist is the whole attack surface of this tool.
COMMANDS = [
    ("getprop_wifi", ["getprop"]),
    ("ps", ["ps"]),
    ("netcfg", ["netcfg"]),
    ("netstat_listen", ["netstat", "-ltn"]),
    ("netstat_all", ["netstat", "-tn"]),
    ("settings_wifi_on", ["settings", "get", "global", "wifi_on"]),
    ("settings_adb", ["settings", "get", "global", "adb_enabled"]),
    ("dumpsys_wifi", ["dumpsys", "wifi"]),
    ("dumpsys_connectivity", ["dumpsys", "connectivity"]),
    ("ls_wifi_dir", ["ls", "-l", "/data/misc/wifi"]),
    ("logcat_wifi", ["logcat", "-d", "-v", "time"]),
]
PROP_KEEP = re.compile(r"(?i)^\[(wifi|wlan|init\.svc\.(wpa|hostapd|dhcp|dnsmasq|adbd)|service\.adb|"
                       r"persist\.service\.adb|persist\.adb|persist\.sys\.(wifi|softap)|net\.|dhcp\.|sys\.boot_completed|"
                       r"ro\.(wifi|build\.product))")


def clean(text, section):
    lines = text.replace("\r", "").splitlines()
    if section == "getprop_wifi":
        lines = [line for line in lines if PROP_KEEP.match(line)]
    elif section == "ps":
        lines = lines[:1] + [line for line in lines[1:] if PS_KEEP.search(line)]
    elif section == "logcat_wifi":
        lines = [line for line in lines if LOG_KEEP.search(line)][-300:]
    lines = [line for line in lines if not SECRET.search(line)]
    return "\n".join(lines[:600]) + "\n"


def snapshot(adb, label, serial):
    selected, product = base.select_k8(adb, serial)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = ROOT / "private" / "vehicle-adb" / ("WIFI-AA-%s-%s" % (stamp, label))
    out.mkdir(parents=True)
    head = ["K8 wifi/wireless-AA read-only probe", "label=" + label, "time=" + stamp, "product=" + product]
    # The car's own clock and speed are not needed; park/ignition state is read from the relay log instead.
    code, data = base.shell(adb, selected, "tail", "-n", "40", "/data/nemesis/logs/k8ambient.relay/relay.log")
    (out / "relay_log_tail.txt").write_text(base.decoded(data) if code == 0 else "UNAVAILABLE\n", encoding="utf-8")
    for section, words in COMMANDS:
        try:
            code, data = base.shell(adb, selected, *words)
        except base.CollectionError:
            code, data = 1, b""
        text = clean(base.decoded(data), section) if code == 0 or data else "UNAVAILABLE\n"
        (out / (section + ".txt")).write_text(text, encoding="utf-8")
        head.append("%s=%d lines" % (section, text.count("\n")))
    (out / "manifest.txt").write_text("\n".join(head) + "\n", encoding="utf-8")
    return out


NOISE = re.compile(r"\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+|\(\s*\d+\)|\b\d{4,}\b|Time:.*")


def diff(first, second):
    import difflib
    a, b = Path(first), Path(second)
    for name in sorted(p.name for p in a.glob("*.txt")):
        if name.startswith("relay_log") or not (b / name).exists():
            continue
        left = [NOISE.sub("#", x) for x in (a / name).read_text(encoding="utf-8").splitlines()]
        right = [NOISE.sub("#", x) for x in (b / name).read_text(encoding="utf-8").splitlines()]
        changes = [x for x in difflib.unified_diff(left, right, lineterm="", n=0)
                   if x[:1] in "+-" and x[:3] not in ("+++", "---")]
        if changes:
            print("== %s ==" % name)
            print("\n".join(changes[:80]))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--adb", required=False, help="adb.exe path (omit when adb is on PATH)")
    parser.add_argument("--serial", help="vehicle serial, local only")
    parser.add_argument("--label", help="short state name, e.g. aa_off, aa_on, wifi_client_on")
    parser.add_argument("--diff", nargs=2, metavar=("SNAP_A", "SNAP_B"), help="compare two snapshot folders")
    args = parser.parse_args(argv)
    if args.diff:
        diff(*args.diff)
        return 0
    if not args.label or not LABEL.fullmatch(args.label):
        parser.error("--label is required (letters, digits, _ or -, up to 40)")
    try:
        out = snapshot(Path(args.adb) if args.adb else "adb", args.label, args.serial)
    except base.CollectionError as error:
        print("probe failed: %s" % error)
        return 1
    print("saved: %s" % out.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
