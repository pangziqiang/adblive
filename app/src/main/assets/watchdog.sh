app_gone() {
    # G3: multi-path check for broader ROM compatibility
    [ -z "$(ls -d /data/app/*/com.adblive.app* 2>/dev/null)" ] &&     [ ! -d /data/data/com.adblive.app ] &&     [ ! -d /data/user/0/com.adblive.app ]
}

cleanup_guard() {
    rm -f /data/adb/service.d/99_adblive_guard.sh
    rm -f /data/adb/adblive_guard_port /data/adb/adblive_guard_disabled
    rm -f /data/local/tmp/adblive_guard.pid
    rm -rf /data/local/tmp/adblive_guard.lock
    rm -f /data/adb/adblive_user_disabled_adb
}

disable_shield_hooks() {
    touch /data/system/adblive_shield_off 2>/dev/null
    touch /data/adb/adblive_shield_off 2>/dev/null
    touch /data/local/tmp/adblive_shield_off 2>/dev/null
}

if [ -f /data/adb/adblive_guard_disabled ]; then
    exit 0
fi

if [ -f /data/data/com.adblive.app/files/guard_stop ]; then
    log -t adblive_guard "stop signal found, self-cleanup"
    cleanup_guard
    exit 0
fi

if app_gone; then
    log -t adblive_guard "app uninstalled, disabling shield hooks and cleaning up"
    disable_shield_hooks
    cleanup_guard
    exit 0
fi

# G1: initial port read (will be refreshed each cycle)
PORT=$(getprop service.adb.tcp.port 2>/dev/null)
[ -f /data/adb/adblive_guard_port ] && PORT=$(cat /data/adb/adblive_guard_port 2>/dev/null)
[ -z "$PORT" ] && PORT=5555

LOCKDIR=/data/local/tmp/adblive_guard.lock
if ! mkdir $LOCKDIR 2>/dev/null; then
    if [ -f $LOCKDIR/pid ] && kill -0 "$(cat $LOCKDIR/pid 2>/dev/null)" 2>/dev/null && ! grep -q '^State:.Z' /proc/$(cat $LOCKDIR/pid 2>/dev/null)/status 2>/dev/null; then
        log -t adblive_guard "another instance running, exiting"
        exit 0
    fi
    rm -rf $LOCKDIR 2>/dev/null
    mkdir $LOCKDIR 2>/dev/null || exit 0
fi
echo $$ > $LOCKDIR/pid
trap 'rm -rf $LOCKDIR' EXIT

if [ -f /data/local/tmp/adblive_guard.pid ]; then
    OLD=$(cat /data/local/tmp/adblive_guard.pid 2>/dev/null)
    if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
        exit 0
    fi
fi

echo $$ > /data/local/tmp/adblive_guard.pid
echo $$ > /sys/fs/cgroup/system/cgroup.procs 2>/dev/null
echo $$ > /sys/fs/cgroup/cgroup.procs 2>/dev/null
echo -1000 > /proc/$$/oom_score_adj 2>/dev/null
renice -n -20 -p $$ >/dev/null 2>&1

SHIELD_ACTIVE=0
if [ -f /data/system/adblive_shield_off ] || [ -f /data/adb/adblive_shield_off ] || [ -f /data/local/tmp/adblive_shield_off ]; then
    SHIELD_ACTIVE=1
    log -t adblive_guard "shield active at startup, entering standby mode"
fi

restore() {
    if [ -f /data/adb/adblive_guard_disabled ]; then
        log -t adblive_guard "disabled, exiting"
        exit 0
    fi
    if [ -f /data/data/com.adblive.app/files/guard_stop ]; then
        log -t adblive_guard "stop signal found, self-cleanup"
        cleanup_guard
        exit 0
    fi
    if app_gone; then
        log -t adblive_guard "app uninstalled, disabling shield hooks and cleaning up"
        disable_shield_hooks
        cleanup_guard
        exit 0
    fi
    if [ -f /data/system/adblive_shield_off ] || [ -f /data/adb/adblive_shield_off ] || [ -f /data/local/tmp/adblive_shield_off ]; then
        if [ "$SHIELD_ACTIVE" = "0" ]; then
            log -t adblive_guard "shield activated, entering standby"
            SHIELD_ACTIVE=1
        fi
        return
    fi
    if [ "$SHIELD_ACTIVE" = "1" ]; then
        log -t adblive_guard "shield deactivated, resuming active guard"
        SHIELD_ACTIVE=0
    fi
    # User intent: if user manually disabled ADB, don't restore it
    if [ -f /data/adb/adblive_user_disabled_adb ]; then
        return
    fi
    # G1: re-read port each cycle in case user changed it
    PORT=$(getprop service.adb.tcp.port 2>/dev/null)
    [ -f /data/adb/adblive_guard_port ] && PORT=$(cat /data/adb/adblive_guard_port 2>/dev/null)
    [ -z "$PORT" ] && PORT=5555
    # G2: restore adb_wifi_enabled if it was turned off
    if [ "$(settings get global adb_wifi_enabled 2>/dev/null)" != "1" ]; then
        settings put global adb_wifi_enabled 1 2>/dev/null
        log -t adblive_guard "re-enabled adb_wifi_enabled"
    fi
    # G2: only restart adbd when it's dead or port prop is wrong
    # no ss check (unreliable on some ROMs), no port listening check
    NEED_RESTART=0
    if ! pidof adbd >/dev/null 2>&1; then
        NEED_RESTART=1
    elif [ "$(getprop service.adb.tcp.port 2>/dev/null)" != "$PORT" ]; then
        setprop service.adb.tcp.port $PORT
        # prop changed, restart adbd to pick up new port
        NEED_RESTART=1
    fi
    if [ "$NEED_RESTART" = "1" ]; then
        stop adbd 2>/dev/null
        start adbd 2>/dev/null
        log -t adblive_guard "restarted adbd on port $PORT"
        sleep 3
    fi
}

while true; do
    restore
    sleep 10
done

