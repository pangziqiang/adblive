#!/system/bin/sh
# ADBLive 一次性卸载清理器
#
# 存在的唯一目的：App 用 root 设过 service.adb.tcp.port=5555、但没开被动守护时，
# 卸载那一刻没有任何 root 侧进程能复位这个属性（system_server 受 SELinux 限制，
# adbd_config_prop 只有 adbd/init 能写）。没有它，卸载后 5555 会一直监听、能用
# adb connect 连进来，残留到下次重启。
#
# 设计：轮询检测 App 是否还在，不在就复位端口 + 清全部状态文件 + 自删自退。
# 不能用 inotifyd：从 App 命名空间起的 inotifyd 收不到自己数据目录被删的事件（实测）。
# PID 由脚本自己写：外面 setsid 会 fork，$! 拿到的是已退出的父进程。

log -t adblive_cleaner "cleaner start pid=$$" 2>/dev/null

echo $$ > /data/local/tmp/adblive_cleaner.pid

# 尽量别被 LMK 杀掉：清理器必须活到"App 被卸载"那一刻
echo -1000 > /proc/$$/oom_score_adj 2>/dev/null

# 没 root 就什么也做不了，别留一个没用的进程
if [ "$(id -u 2>/dev/null)" != "0" ]; then
    log -t adblive_cleaner "not root (uid=$(id -u 2>/dev/null)), aborting" 2>/dev/null
    rm -f /data/local/tmp/adblive_cleaner.sh /data/local/tmp/adblive_cleaner.pid /data/local/tmp/adblive_cleaner.ver
    exit 1
fi

app_gone() {
    if [ -f /data/system/packages.list ] &&
       grep -q '^com\.adblive\.app ' /data/system/packages.list 2>/dev/null; then
        return 1
    fi
    pm path com.adblive.app >/dev/null 2>&1 && return 1
    return 0
}

while ! app_gone; do
    sleep 3
done

log -t adblive_cleaner "app gone, cleaning up" 2>/dev/null

# 0. 先拆盾：武装状态文件还在的话，下面写 adb_wifi_enabled=0 会被我们自己的盾拦掉
#    （system_server 里的模块有 5s 盾状态缓存，所以下面还要重试）
rm -f /data/system/adblive_shield_armed /data/system/adblive_shield_off
rm -f /data/adb/adblive_shield_armed /data/adb/adblive_shield_off
rm -f /data/local/tmp/adblive_shield_armed /data/local/tmp/adblive_shield_off

# 1. 杀掉被动守护 + 旧版本遗留的 inotifyd 监视进程
GPID=$(cat /data/local/tmp/adblive_guard.pid 2>/dev/null)
[ -n "$GPID" ] && setsid kill -9 -"$GPID" 2>/dev/null
WPID=$(cat /data/local/tmp/adblive_guard.watch 2>/dev/null)
[ -n "$WPID" ] && kill -9 "$WPID" 2>/dev/null
pkill -9 -f '[a]dblive_uninstalled' 2>/dev/null

# 2. 释放我们设的固定端口（只在 5555 确实还是我们设的值时才动）
OUR_PORT=$(cat /data/adb/adblive_guard_port 2>/dev/null)
[ -n "$OUR_PORT" ] || OUR_PORT=5555
CUR_PORT=$(getprop service.adb.tcp.port 2>/dev/null)
if [ "$CUR_PORT" = "$OUR_PORT" ] && [ "$CUR_PORT" = "5555" ]; then
    setprop service.adb.tcp.port 0
    stop adbd 2>/dev/null
    start adbd 2>/dev/null
    log -t adblive_cleaner "released fixed port 5555" 2>/dev/null
fi

# 3. 还原系统无线调试开关
#    模块（system_server 里的盾）对"盾是否关闭"有 5s 缓存，刚删掉 armed 文件后
#    写入仍可能被拦，所以重试到真写进去为止（最多 ~20s）。
i=0
while [ $i -lt 10 ]; do
    [ "$(settings get global adb_wifi_enabled 2>/dev/null)" = "0" ] && break
    settings put global adb_wifi_enabled 0 2>/dev/null
    sleep 2
    i=$((i+1))
done

# 4. 删光所有状态文件
rm -f /data/adb/service.d/99_adblive_guard.sh
rm -f /data/adb/adblive_guard_port /data/adb/adblive_guard_disabled /data/adb/adblive_boot_enabled
rm -f /data/adb/adblive_user_disabled_adb
rm -f /data/local/tmp/adblive_guard.pid /data/local/tmp/adblive_guard.watch
rm -rf /data/local/tmp/adblive_guard.lock
rm -f /data/local/tmp/adblive_b64.tmp /data/local/tmp/adblive_uninstalled.sh

# 5. 自删自退
rm -f /data/local/tmp/adblive_cleaner.pid /data/local/tmp/adblive_cleaner.sh /data/local/tmp/adblive_cleaner.ver
log -t adblive_cleaner "cleaner done" 2>/dev/null
exit 0
