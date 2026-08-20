# ADBLive TODO

## 已完成

### Bug 修复（上一轮）
- [x] #2 hookPackageRemovedCleanup rm 无权限 — 改用 su -c
- [x] #3 toggleAdb setPref 提前执行 — 移到 Thread 内
- [x] #4 isShieldDisabled 热路径文件 IO — volatile 内存标记
- [x] #5 hookPackageRemovedCleanup 死代码删除
- [x] #6 hookPackageRemovedCleanup waitFor 超时 — 3s
- [x] #7 XposedStatus SP 误判 — 7天改1天

### 体验优化（上一轮）
- [x] #8 toggleShield 后立即刷新内存标记
- [x] #9 watchdog 启动时 shield standby 模式
- [x] #10 deployAndStart 临时文件写入
- [x] #11 readCurrentPort 读 port file
- [x] #12 toggleGuard off 后加 500ms 延迟
- [x] #13 isShieldDisabled 日志精简
- [x] #14 SettingsGuard 启动日志修正

### 卸载无残留（本轮）
- [x] 排查旧版三位置残留（`/data/system`、`/data/adb`、`/data/local/tmp` 的 `adblive_shield_off`）
- [x] watchdog `cleanup_guard()`/`disable_shield_hooks()` 补齐 `/data/adb` 盾文件清理
- [x] 模块 `Entry.cleanupSystemShieldResidue()` 卸载后清 `/data/system` 盾文件（看门狗未跑时兜底）
- [x] `ShieldStateFile` 盾状态只写 `/data/system`，杜绝 `/data/adb`、`/data/local/tmp` 新遗留
- [x] `MainActivity` 启动带 root 一次性清扫旧版残留盾文件
- [x] 实测：小米14 卸载残留手动清空，安装最新版后环境干净

---

## 待讨论优化

### 简化方向（用户反馈：功能不多但维护/升级麻烦）
- [x] **S0 升级后脚本不更新（同 B5，已修）** — 详见 B5
- [ ] **S1 守护脚本分支过多** — watchdog 承担开机自启/卸载自毁/停止信号/用户意志/盾待机/pid锁/端口，每加需求堆分支；评估合并精简、减少状态文件，保持“卸载零残留 + 尊重用户意志”硬要求

### 功能 2 — 无线 ADB 开关
- [x] G1 Guard 端口不热更新 — restore() 每轮重读 prop + port file
- [x] G2 Guard 重启条件 OR 太激进 — ss 不可靠导致不必要重启
- [x] G3 app_gone() 路径不全 — 部分 ROM 匹配不到

### 功能 4 — 被动守护
- [x] G7 cleanup 删 shield 文件 — 守护自毁意外开启 shield

### 功能 6 — 开机自启
- [x] G8 BootReceiver 死代码 — getprop 没返回值处理
- [x] G9 BootReceiver 不检查 guard 运行状态 — 重复 deploy

### 体验
- [x] G4 自动部署太频繁 — 应先 isGuardRunning() 再 deploy
- [x] G10 ADB 关闭被 shield 拦截时 UX 不直观
---

## 用户意图尊重（ADB 开关最高权限）

- [x] **U1 toggleAdb 去掉 shield 拦截** — 删掉 if (!on && shieldOn) 那段判断，用户关 ADB 直接放行
- [x] **U2 guard 加意图文件** — 用户关 ADB 时写 /data/adb/adblive_user_disabled_adb，guard 检测到跳过恢复
- [x] **U3 用户开 ADB 时删意图文件** — 恢复 guard 正常守护
- [x] **U4 BootReceiver 检测意图文件** — 有文件则跳过自动开启 ADB
- [x] **U5 watchdog 清理意图文件** — app 卸载时 cleanup_guard 顺便删意图文件

### 三个开关各管各的
- ADB 开关 = 本次开不开 ADB（用户最高意志）
- 主动守护 = 拦不拦系统杀 adbd
- 被动守护 = ADB 意外关了拉不拉回来（尊重意图文件）
- 开机自启 = 下次开机要不要自动开 ADB + 部署守护
## UI 状态显示优化

- [ ] **S1 root 授权后 UI 闪烁** — maybeAutoEnableGuard 完成后调 refresh() 刷新界面，避免 ADB 先闪关再变开
- [x] **S2 deployAndStart + enableWirelessAdbNow 重复开 ADB** — deployAndStart 已经开了 ADB，enableWirelessAdbNow 重复，砍掉
- [x] **S3 连续开关 UI 抖动（实测）** — 快速连续开关（≥2 次）时，第 4 个"开"动作圆圈先到开位→回关→再回开。根因：`toggleAdb` 无串行/去重，每次点击各开一个 Thread，`Thread.sleep(1000)` + 回读后各线程**乱序覆写** `swAdb.isChecked`（陈旧状态覆盖最新），且 setprop/settings put/stop-start adbd 竞态。待处理：toggleAdb 加串行队列或丢弃过期结果，只在最后一次动作后回读更新 UI

## 回归发现的问题（待修）
- [x] **B1 守护开机自毁（已修，重启验证通过）** — 根因：service.d 开机早期执行看门狗，PackageManager 未就绪，`app_gone()` 的 `! pm path` 误判 app 已卸载 → `cleanup_guard()` 自毁删脚本+intent+pid。修复：`app_gone()` 加开机保护——`getprop sys.boot_completed`≠1 时不判定自毁，且重试 `pm path` 3 次（间隔 2s）排除瞬时不稳。实测重启后守护存活（PID 正常）、脚本与 intent file 保留、ADB 保持关闭。
- [ ] **B2 MIUI 限制 BootReceiver 开机自启（实测，回归 4.3）** — logcat 见 `BroadcastQueueInjector: Unable to launch app com.adblive.app ... process is not permitted to auto start`，MIUI 未授予自启权限时 BOOT_COMPLETED 广播不会拉起 App，BootReceiver 不执行（开机补部署/开 ADB 逻辑失效）。当前靠 `service.d` 守护自启为主路径，功能不受影响；但若要 BootReceiver 生效需用户在 MIUI 授权自启。待处理：在 UI/文档提示用户开启自启权限，或评估是否需要
- [x] **B5 升级后守护脚本不更新（实测，已修）** — 安装新 APK 后旧守护进程仍在跑，`maybeAutoEnableGuard` 因 `isGuardRunning()` 判存活而跳过重部署，磁盘守护脚本仍为旧版（例如缺 `boot_enabled` 开机检查），导致新逻辑不生效，需手动杀进程/重部署/重启。修复：拆出 `refreshScript()`，`maybeAutoEnableGuard` 在守护运行中也刷新脚本文件；`deployAndStart` 复用。实测：改旧脚本后重启 app → 自动还原新版，守护进程不重启
- [x] **B4 卸载残留时序边界（已事件驱动修复）** — 卸载后 10s 内重装时看门狗轮询看不到 app 消失，守护/盾文件残留。修复：①守护脚本加 inotifyd 监视 `/data/data/com.adblive.app:d`，数据目录被删（=卸载）瞬间触发自毁清全部（含盾文件）；②Xposed 加 `ACTION_PACKAGE_REMOVED` 钩子，卸载瞬间在 system_server 清盾文件（守护未运行时兜底）。实测：卸载→<2s 重装，inotify 触发，守护+盾文件全清，零残留

## UI 体验原则

- [ ] **所有状态切换必须优雅** — 不能出现卡顿、闪屏、生硬跳变
  - 卡片边框颜色变化用 transition 动画
  - 开关状态变更时先禁用 listener 防止触发回调（已有 isPressed 检查）
  - 日志追加平滑，不突然跳动
  - 进度条出现/消失有淡入淡出
  - 布局不因内容增减产生位移跳变
