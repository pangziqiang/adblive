#!/system/bin/sh
# adblive passive guard - watchdog.sh

# 开机自启控制：仅开机时（init 拉起、无 manual 参数）检查 boot_enabled 标记。
# 标记为 0 → 不开机自启，直接退出；app 手动启动（带 manual 参数）不受此限制。
if [ "$1" != "manual" ] && [ "$(cat /data/adb/adblive_boot_enabled 2>/dev/null)" = "0" ]; then
    log -t adblive_guard "boot auto-start disabled, exiting"
    exit 0
fi

app_gone() {
    # 开机早期 PackageManager 未就绪，pm path 可能误报 app 已卸载 → 禁止此时自毁。
    # 仅当系统完全开机后才可信，并重试 pm path 排除瞬时不稳（B1 修复）。
    if [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; then
        return 1
    fi
    for _ in 1 2 3; do
        if pm path com.adblive.app >/dev/null 2>&1; then
            return 1
        fi
        sleep 2
    done
    return 0
}

cleanup_guard() {
    rm -f /data/adb/service.d/99_adblive_guard.sh
    rm -f /data/system/adblive_shield_armed /data/local/tmp/adblive_shield_armed /data/adb/adblive_shield_armed
    rm -f /data/system/adblive_shield_off /data/local/tmp/adblive_shield_off /data/adb/adblive_shield_off
    rm -f /data/local/tmp/adblive_bypass
    rm -f /data/local/tmp/adblive_b64.tmp
    rm -f /data/adb/adblive_boot_enabled
    rm -f /data/adb/adblive_guard_port /data/adb/adblive_guard_disabled
    rm -f /data/local/tmp/adblive_guard.pid
    rm -rf /data/local/tmp/adblive_guard.lock
    rm -f /data/adb/adblive_user_disabled_adb
}

disable_shield_hooks() {
    rm -f /data/system/adblive_shield_armed /data/local/tmp/adblive_shield_armed /data/adb/adblive_shield_armed
}

# true if $1 is a live watchdog process (not a recycled PID / zombie)
guard_alive() {
    p="$1"
    [ -n "$p" ] || return 1
    kill -0 "$p" 2>/dev/null || return 1
    grep -q '99_adblive_guard\.sh' "/proc/$p/cmdline" 2>/dev/null || return 1
    grep -q '^State:[[:space:]]*Z' "/proc/$p/status" 2>/dev/null && return 1
    return 0
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

PORT=$(getprop service.adb.tcp.port 2>/dev/null)
[ -f /data/adb/adblive_guard_port ] && PORT=$(cat /data/adb/adblive_guard_port 2>/dev/null)
[ -z "$PORT" ] && PORT=5555

LOCKDIR=/data/local/tmp/adblive_guard.lock
if ! mkdir $LOCKDIR 2>/dev/null; then
    if [ -f $LOCKDIR/pid ] && guard_alive "$(cat $LOCKDIR/pid 2>/dev/null)"; then
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
    if guard_alive "$OLD"; then
        exit 0
    fi
fi

echo $$ > /data/local/tmp/adblive_guard.pid
echo $$ > /sys/fs/cgroup/system/cgroup.procs 2>/dev/null
echo $$ > /sys/fs/cgroup/cgroup.procs 2>/dev/null
echo -1000 > /proc/$$/oom_score_adj 2>/dev/null
renice -n -20 -p $$ >/dev/null 2>&1

SHIELD_ACTIVE=0
if [ -f /data/system/adblive_shield_armed ]; then
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
    if [ -f /data/system/adblive_shield_armed ]; then
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
    if [ -f /data/adb/adblive_user_disabled_adb ]; then
        return
    fi
    PORT=$(getprop service.adb.tcp.port 2>/dev/null)
    [ -f /data/adb/adblive_guard_port ] && PORT=$(cat /data/adb/adblive_guard_port 2>/dev/null)
    [ -z "$PORT" ] && PORT=5555
    if [ "$(settings get global adb_wifi_enabled 2>/dev/null)" != "1" ]; then
        settings put global adb_wifi_enabled 1 2>/dev/null
        log -t adblive_guard "re-enabled adb_wifi_enabled"
    fi
    NEED_RESTART=0
    if ! pidof adbd >/dev/null 2>&1; then
        NEED_RESTART=1
    elif [ "$(getprop service.adb.tcp.port 2>/dev/null)" != "$PORT" ]; then
        setprop service.adb.tcp.port $PORT
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
