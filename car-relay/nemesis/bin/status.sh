#!/system/bin/sh
# Shows the relay's latest log line and the overspeed recording size
# (NEMESIS Manager displays script output as a toast).
dir=/data/nemesis/logs/k8ambient.relay
log=$dir/relay.log
last=
if [ -f "$log" ]; then
    while read -r line; do
        last=$line
    done < "$log"
fi
echo "${last:-relay log empty}"
rec=0
for f in "$dir"/overspeed*.log; do
    [ -f "$f" ] && rec=$((rec + 1))
done
echo "recording files: $rec"
