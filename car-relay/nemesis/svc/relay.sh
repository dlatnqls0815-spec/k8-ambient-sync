#!/system/bin/sh
# K8 ambient relay (NEMESIS service, mode=restart). Reads OEM SML status lines from the system log
# and serves them to one bonded phone over classic Bluetooth RFCOMM. Read-only toward the vehicle:
# no MICOM/lighting commands, no Bluetooth setting changes.

case "$0" in
    /*) pkg_dir=${0%/svc/*} ;;
    *) pkg_dir=/data/nemesis/apps/k8ambient.relay ;;
esac
dex="$pkg_dir/bin/k8-ambient-relay.dex"
work=/data/local/tmp/k8-ambient-relay
[ -f "$dex" ] || { echo "relay dex missing"; exit 1; }
mkdir -p "$work/dalvik-cache" || exit 1

# Wait for Android; the Bluetooth framework and logcat need it.
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 5
done
sleep 10

ANDROID_DATA="$work" CLASSPATH="$dex" exec app_process /system/bin io.github.k8ambient.relay.CarRelay
