#!/system/bin/sh
if [ -f /data/adb/adblive_guard_disabled ]; then
    exit 0
fi

PORT=$(getprop service.adb.tcp.port 2>/dev/null)
[ -f /data/adb/adblive_guard_port ] && PORT=$(cat /data/adb/adblive_guard_port 2>/dev/null)
[ -z "$PORT" ] && PORT=5555

echo $$ > /data/local/tmp/adblive_guard.pid
echo -1000 > /proc/$$/oom_score_adj 2>/dev/null
renice -n -20 -p $$ >/dev/null 2>&1

restore() {
    if [ -f /data/adb/adblive_guard_disabled ]; then
        log -t adblive_guard "disabled, exiting"
        exit 0
    fi
    if [ "$(settings get global adb_wifi_enabled 2>/dev/null)" != "1" ]; then
        settings put global adb_wifi_enabled 1 2>/dev/null
        log -t adblive_guard "re-enabled adb_wifi_enabled"
    fi
    if ! pidof adbd >/dev/null 2>&1 || ! ss -tln 2>/dev/null | grep -E ":$PORT([[:space:]]|$)"; then
        exec 9>/data/local/tmp/adblive_guard.lock
        if flock -n 9 2>/dev/null; then
            setprop service.adb.tcp.port $PORT
            stop adbd 2>/dev/null
            start adbd 2>/dev/null
            log -t adblive_guard "restarted adbd on port $PORT"
            sleep 3
        fi
    fi
}

while true; do
    restore
    sleep 10
done
