# Repository Guidelines

## 项目概述

**adblive** 是一个 Android 无线 ADB 保护应用，双层防护：

- **主动拦截（Shield）**：Xposed/LSPosed 模块，hook system_server 拦截关闭无线 ADB 和杀 adbd 的操作
- **被动守护（Guard）**：root 授权后部署 shell 守护脚本，开机自启，10 秒轮询自动恢复
- **管理界面**：Flutter + Rust 应用，暗色终端风格 UI

## 项目结构

```
adblive/
├── adblive-xposed/                  # Xposed 模块（独立 APK）
│   ├── app/src/main/kotlin/com/adblive/shield/
│   │   ├── Entry.kt                 # 模块入口，按进程分发
│   │   ├── SettingsInterceptor.kt   # 拦截 adb_wifi_enabled 写入（7 条路径）
│   │   └── ProcessInterceptor.kt    # 拦截杀 adbd（/proc/pid/comm 检测）
│   ├── app/src/main/assets/
│   │   ├── watchdog.sh              # 被动守护脚本（service.d 开机自启）
│   │   ├── xposed_init              # 入口声明
│   │   └── xposed_scope             # 作用域声明
│   └── build.gradle.kts
├── adblive-app/                     # Flutter + Rust 管理应用
│   ├── lib/
│   │   ├── main.dart                # 入口，暗色主题
│   │   ├── pages/dashboard.dart     # 主面板（ShieldCard 状态展示）
│   │   ├── widgets/shield_card.dart # 状态卡片组件
│   │   ├── widgets/log_panel.dart   # 日志面板组件
│   │   └── extensions.dart          # Dart 扩展方法
│   ├── rust/src/api/
│   │   ├── root.rs                  # root 检测、su 执行
│   │   ├── adb.rs                   # ADB 状态查询、开启
│   │   └── guard.rs                 # 守护脚本部署/启停/检测
│   ├── pubspec.yaml
│   └── rust/Cargo.toml
├── AGENTS.md
└── .gitignore
```

## 技术栈

| 组件 | 技术 |
|---|---|
| UI + 业务逻辑 | Flutter + Rust（flutter_rust_bridge） |
| Xposed 模块 | Kotlin（LSPosed 要求，必须 Kotlin/Java） |
| 被动守护 | Shell 脚本（service.d 开机自启，固定端口 5555） |
| 构建 | Gradle 8.x（Xposed）/ Flutter SDK（App） |
| JDK | 17 |
| 最低 SDK | 30（Android 11），目标 SDK 36 |

## Xposed 模块设计

### 入口分发（Entry.kt）

`handleLoadPackage` 按进程名分发到不同 Guard：

| 进程 | Guard |
|---|---|
| `system_server` | SettingsInterceptor + ProcessInterceptor |
| `com.android.settings` | SettingsInterceptor |
| `com.android.providers.settings` | SettingsInterceptor |
| `android`（非 system_server） | SettingsInterceptor + ProcessInterceptor |

支持 kill switch：`/data/local/tmp/adblive_shield_off` 存在时跳过所有 hook。

### SettingsInterceptor

覆盖所有写入 `adb_wifi_enabled` 的路径：

1. `Settings.Global.putInt` / `putString`（2 种重载）
2. `ContentProvider.Transport.call`（shell settings put 走此路径）
3. `SettingsProvider.call`（3/4 参数版）
4. `SettingsProvider.update` / `insert`（直接 CRUD）

### ProcessInterceptor

拦截 `Process.killProcess`、`killProcessQuiet`、`killProcessGroup`，通过 `/proc/pid/comm` 判断目标是否为 adbd。ConcurrentHashMap 缓存 PID→进程名，128 条淘汰上限。

## 构建

### Xposed 模块

```bash
cd adblive-xposed
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew assembleRelease
```

### Flutter 应用

```bash
cd adblive-app
flutter pub get
flutter run
flutter build apk --release
```

## UI 设计

暗色终端风格（`#0A0E14` 背景），monospace 字体，ShieldCard 状态卡片 + LogPanel 日志面板。

## 代码风格

- **Kotlin**：4 空格缩进，object 单例模式
- **Rust**：`cargo fmt`，`cargo clippy`
- **Dart**：`dart format`，effective dart
- **Shell**：`shellcheck`，2 空格缩进
- **命名**：Kotlin/Dart → `camelCase`，Rust → `snake_case`

## Git 提交

祈使语气，可选前缀：`feat:`、`fix:`、`refactor:`、`docs:`

## 注意事项

- LSPosed 2.1.1 只识别 legacy 模块格式（`assets/xposed_init`），不要改成现代格式
- 模块作用域只勾选 **System Framework + Settings**
- 固定端口 **5555**
- Root 权限由 KernelSU 管理
- 首次启用模块时 LSPosed 会弹出重启按钮，点击重启即可激活

