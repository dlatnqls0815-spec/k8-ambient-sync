#!/system/bin/sh
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

usb_root=$(pwd -P) || exit 1
case "$usb_root" in
 /storage/usb[012]|/mnt/media_rw/usb*) ;;
 *) echo 'Collection must run from the mounted USB root'; exit 1 ;;
esac

stamp=$(date +%Y%m%d-%H%M%S) || exit 1
out="./K8-AMBIENT-EVIDENCE-$stamp-$$"
mkdir "$out" || exit 1
manifest="$out/collection.txt"
product=$(getprop ro.build.product 2>/dev/null)
sdk=$(getprop ro.build.version.sdk 2>/dev/null)
release=$(getprop ro.build.version.release 2>/dev/null)
{
 echo 'K8 ambient existing-file collection'
 echo "product=$product"
 echo "android_sdk=$sdk"
 echo "android_release=$release"
 echo "platform=$(getprop ro.board.platform 2>/dev/null)"
 echo 'runtime_hooks=unknown'
 echo 'ambient_event_reception=unknown'
} > "$manifest" || exit 1
case "$product" in
 GL3HEV.KOR.*.260902) ;;
 *) echo 'collection=unsupported_firmware' >> "$manifest"; exit 2 ;;
esac
if [ "$sdk" != 19 ] || [ "$release" != 4.4.2 ]; then
 echo 'collection=unsupported_android' >> "$manifest"
 exit 2
fi

copied=0
unavailable=0
failed=0
collect() {
 src="$1"
 relative="$2"
 if [ -L "$src" ] || [ ! -f "$src" ] || [ ! -r "$src" ]; then
  echo "UNAVAILABLE $relative" >> "$manifest"
  unavailable=$((unavailable+1))
  return
 fi
 dst="$out/$relative"
 if mkdir -p "${dst%/*}" && cp "$src" "$dst"; then
  echo "COPIED $relative" >> "$manifest"
  copied=$((copied+1))
 else
  echo "COPY_FAILED $relative" >> "$manifest"
  failed=$((failed+1))
 fi
}

for src in "/data/hmgapi/public/reports/"*.json; do
 [ -f "$src" ] || continue
 collect "$src" "reports/${src##*/}"
done
maps_found=0
for mapbase in "/data/hmgapi/maps/"*/gl3hev/kor/260902; do
 [ -d "$mapbase" ] || continue
 maps_found=$((maps_found+1))
 map_relative=${mapbase#"/data/hmgapi/maps/"}
 collect "$mapbase/car.json" "maps/$map_relative/car.json"
 collect "$mapbase/framework.json" "maps/$map_relative/framework.json"
done
if [ "$maps_found" -eq 0 ]; then
 echo 'UNAVAILABLE maps/gl3hev/kor/260902' >> "$manifest"
 unavailable=$((unavailable+1))
fi
collect "/data/hmgapi/state/map-check.json" state/map-check.json
collect "/data/hmgapi/installed.txt" installed.txt
collect "/data/hmgapi/logs/hmgapi/log.log" logs/hmgapi.log
collect "/data/hmgapi/logs/hmgapi/vehicle.log" logs/vehicle.log

# Existing OEM buffer only. Restrict both the log tag and the retained messages.
mkdir -p "$out/logs" || exit 1
{
 logcat -b system -d -v brief -s SystemService:D '*:S'
 printf '%s\n' "$?" > "$out/logs/oem-reader-exit.txt"
} 2> "$out/logs/oem-reader-errors.txt" | {
 oem_lines=0
 while IFS= read -r record || [ -n "$record" ]; do
  case "$record" in
   *SYS_SML_STATUS_M_RESP:*|*'[SystemService.setSMLRGB] red:'*|*'[SystemService.setSMLConfiguration] mode:'*)
    printf '%s\n' "$record"
    oem_lines=$((oem_lines+1))
    ;;
  esac
 done
 printf '%s\n' "$oem_lines" > "$out/logs/oem-sml-count.txt"
} > "$out/logs/oem-sml.log"
read -r oem_exit < "$out/logs/oem-reader-exit.txt" || oem_exit=unknown
read -r oem_lines < "$out/logs/oem-sml-count.txt" || oem_lines=0
{
 echo "copied_files=$copied"
 echo "unavailable_files=$unavailable"
 echo "copy_failures=$failed"
 echo "matching_map_folders=$maps_found"
 echo "oem_logcat_exit=$oem_exit"
 echo "oem_sml_log_lines=$oem_lines"
 if [ "$copied" -eq 0 ] && [ "$oem_lines" -eq 0 ]; then
  echo 'collection=no_existing_files'
 elif [ "$failed" -ne 0 ]; then
  echo 'collection=partial_copy_failure'
 elif [ "$oem_exit" != 0 ]; then
  echo 'collection=partial_oem_reader_failure'
 else
  echo 'collection=finished'
 fi
 echo 'Inspect available files before judging Hook or event status.'
} >> "$manifest"
sync
[ "$failed" -eq 0 ] && [ "$oem_exit" = 0 ] && { [ "$copied" -gt 0 ] || [ "$oem_lines" -gt 0 ]; }
