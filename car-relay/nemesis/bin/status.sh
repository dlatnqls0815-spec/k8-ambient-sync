#!/system/bin/sh
# Shows the relay's latest log line (NEMESIS Manager displays script output as a toast).
log=/data/nemesis/logs/k8ambient.relay/relay.log
last=
if [ -f "$log" ]; then
    while read -r line; do
        last=$line
    done < "$log"
fi
echo "${last:-relay log empty}"
