if [ "$1" != "manual" ] && [ "$(cat /data/adb/adblive_boot_enabled 2>/dev/null)" = "0" ]; then
    log -t adblive_guard "boot auto-start disabled, exiting"
    exit 0
fi

app_gone() {
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

release_fixed_port() {
    OUR_PORT=$(cat /data/adb/adblive_guard_port 2>/dev/null)
    [ -n "$OUR_PORT" ] || return
    CUR=$(getprop service.adb.tcp.port 2>/dev/null)
    [ "$CUR" = "$OUR_PORT" ] || return
    [ "$CUR" = "5555" ] || return
    setprop service.adb.tcp.port 0 2>/dev/null
    stop adbd 2>/dev/null
    start adbd 2>/dev/null
    log -t adblive_guard "released fixed port $OUR_PORT"
}

release_wifi_setting() {
    # 模块（system_server 里的盾）对"盾是否关闭"有 5s 缓存：刚删掉 armed 文件后，
    # 我们自己的 settings 写入仍可能被自己的盾拦掉。所以重试到真写进去为止。
    i=0
    while [ $i -lt 10 ]; do
        [ "$(settings get global adb_wifi_enabled 2>/dev/null)" = "0" ] && return
        settings put global adb_wifi_enabled 0 2>/dev/null
        sleep 2
        i=$((i+1))
    done
    log -t adblive_guard "restored adb_wifi_enabled=0"
}

cleanup_guard() {
    # 清掉旧版本可能留下的 inotifyd 监视进程（只删 PID 文件不杀进程会残留）
    W=$(cat /data/local/tmp/adblive_guard.watch 2>/dev/null)
    [ -n "$W" ] && kill -9 "$W" 2>/dev/null
    pkill -9 -f '[a]dblive_uninstalled' 2>/dev/null
    # 先拆盾：armed 时盾会拦掉非本应用的写入，下面恢复设置会被自己拦
    rm -f /data/system/adblive_shield_armed /data/local/tmp/adblive_shield_armed /data/adb/adblive_shield_armed
    release_fixed_port
    release_wifi_setting
    rm -f /data/adb/service.d/99_adblive_guard.sh
    rm -f /data/system/adblive_shield_off /data/local/tmp/adblive_shield_off /data/adb/adblive_shield_off
    rm -f /data/local/tmp/adblive_b64.tmp
    rm -f /data/adb/adblive_boot_enabled
    rm -f /data/adb/adblive_guard_port /data/adb/adblive_guard_disabled
    rm -f /data/local/tmp/adblive_guard.pid
    rm -rf /data/local/tmp/adblive_guard.lock
    rm -f /data/adb/adblive_user_disabled_adb
    rm -f /data/local/tmp/adblive_uninstalled.sh /data/local/tmp/adblive_guard.watch
}

guard_alive() {
    p="$1"
    [ -n "$p" ] || return 1
    kill -0 "$p" 2>/dev/null || return 1
    grep -q '99_adblive_guard\.sh' "/proc/$p/cmdline" 2>/dev/null || return 1
    grep -q '^State:[[:space:]]*Z' "/proc/$p/status" 2>/dev/null && return 1
    return 0
}

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

# 卸载检测：靠 restore() 里的 app_gone() 轮询（最长 10s）。
# 曾经用 inotifyd 做"事件驱动卸载自毁"，但实测两条都不成立，已移除：
#   1) 从 App 命名空间起的 inotifyd 收不到自己数据目录被删的事件（数据目录是 bind mount）；
#   2) 它是个 root 常驻进程，cleanup_guard() 只删 PID 文件不杀进程，反倒会留下僵尸 inotifyd。
# 旧版本留下的 inotifyd 在这里顺手清掉。

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
        log -t adblive_guard "app uninstalled, cleaning up"
        cleanup_guard
        exit 0
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
