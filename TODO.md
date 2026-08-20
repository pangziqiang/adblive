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

---

## 待讨论优化

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

- [ ] **U1 toggleAdb 去掉 shield 拦截** — 删掉 if (!on && shieldOn) 那段判断，用户关 ADB 直接放行
- [ ] **U2 guard 加意图文件** — 用户关 ADB 时写 /data/adb/adblive_user_disabled_adb，guard 检测到跳过恢复
- [ ] **U3 用户开 ADB 时删意图文件** — 恢复 guard 正常守护
- [ ] **U4 BootReceiver 检测意图文件** — 有文件则跳过自动开启 ADB
- [ ] **U5 watchdog 清理意图文件** — app 卸载时 cleanup_guard 顺便删意图文件

### 三个开关各管各的
- ADB 开关 = 本次开不开 ADB（用户最高意志）
- 主动守护 = 拦不拦系统杀 adbd
- 被动守护 = ADB 意外关了拉不拉回来（尊重意图文件）
- 开机自启 = 下次开机要不要自动开 ADB + 部署守护
## UI 状态显示优化

- [ ] **S1 root 授权后 UI 闪烁** — maybeAutoEnableGuard 完成后调 refresh() 刷新界面，避免 ADB 先闪关再变开
- [ ] **S2 deployAndStart + enableWirelessAdbNow 重复开 ADB** — deployAndStart 已经开了 ADB，enableWirelessAdbNow 重复，砍掉
## UI 体验原则

- [ ] **所有状态切换必须优雅** — 不能出现卡顿、闪屏、生硬跳变
  - 卡片边框颜色变化用 transition 动画
  - 开关状态变更时先禁用 listener 防止触发回调（已有 isPressed 检查）
  - 日志追加平滑，不突然跳动
  - 进度条出现/消失有淡入淡出
  - 布局不因内容增减产生位移跳变
