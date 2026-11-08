#!/system/bin/sh
# K8 BLE probe (NEMESIS kind=sh). Read-only: BLE scan, optional GATT service discovery of one
# lamp, then disconnect. Sends no lamp, Bluetooth-setting or vehicle commands.
#
# Optional input: first line of k8-ble-probe.txt on the USB root = lamp address AA:BB:CC:DD:EE:FF.
# Without it the probe only lists nearby BLE devices. Results go to /data/local/tmp/k8-ble-probe
# and, when the USB is writable, to k8-ble-probe-result.txt next to the input file.

case "$0" in
    /*) pkg_dir=${0%/bin/*} ;;
    *) pkg_dir=/data/nemesis/apps/k8ambient.bleprobe ;;
esac
dex="$pkg_dir/bin/k8-ble-probe.dex"
work=/data/local/tmp/k8-ble-probe
[ -f "$dex" ] || { echo "RESULT DEX_MISSING"; exit 1; }
mkdir -p "$work/dalvik-cache" || { echo "RESULT WORKDIR_ERROR"; exit 1; }

usb=
address=
for dir in /storage/usb0 /storage/usb1 /storage/usb2 /mnt/media_rw/usb0 /mnt/media_rw/usb1; do
    if [ -f "$dir/k8-ble-probe.txt" ]; then
        usb=$dir
        read -r address < "$dir/k8-ble-probe.txt"
        break
    fi
done

result="$work/result.txt"
if [ -n "$address" ]; then
    ANDROID_DATA="$work" CLASSPATH="$dex" app_process /system/bin \
        io.github.k8ambient.bleprobe.HeadlessProbe --address "$address" > "$result" 2>&1
else
    ANDROID_DATA="$work" CLASSPATH="$dex" app_process /system/bin \
        io.github.k8ambient.bleprobe.HeadlessProbe --scan-only > "$result" 2>&1
fi

if [ -n "$usb" ]; then
    cat "$result" > "$usb/k8-ble-probe-result.txt" 2>/dev/null && sync
fi
# NEMESIS Manager shows a script's output as a toast; keep the final line short.
last=
while read -r line; do
    case "$line" in RESULT*) last=$line ;; esac
done < "$result"
echo "${last:-RESULT NO_OUTPUT}"
