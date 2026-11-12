# FlClash Modular

这是 [FlClash](https://github.com/chen08209/FlClash) 的 Android 前后端分离改造：界面进程只负责启停与配置，mihomo（Clash.Meta）内核运行在独立的 **`:core` 进程**里，由常驻前台服务承载。把界面从最近任务里划掉后，内核和 TUN 仍继续运行。

## 架构

```
┌─────────────────────────────── App 进程（com.flclash.modular）───────────────────────────────┐
│ Flutter UI  ──  MethodChannel(${packageName}/service, /core)  ──  ServicePlugin              │
│                                                                                             │
│ ServiceState（启停意图仲裁，最新意图优先）  ──  ServiceController（绑定/状态簿记）           │
│                                                      │  AIDL（Binder）                       │
└──────────────────────────────────────────────────────┼──────────────────────────────────────┘
                                                       │
┌──────────────────────────────────────────────────────▼───── :core 进程（模块）──────────────┐
│ CoreModuleService（started 前台服务，AIDL 服务端）                                          │
│   ├─ Go Core（gomobile AAR，libclash.so）                                                   │
│   ├─ VpnService / ProxyService（TUN、通知、流量）                                           │
│   └─ NetworkObserver / ServiceConfig                                                        │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

- 界面进程不再加载 Go 内核，也不再持有 TUN；它只把 `core.*` 方法调用、启停请求和通知参数转发给模块。
- 模块进程用 `started` 的前台服务（`CoreModuleService`，`android:process=":core"`）保活；VPN 服务本身也是前台服务，所以划掉任务不会结束内核。
- 再次打开 App 时，界面进程会向模块查询运行状态并「接管」已有运行（`ServiceState.adoptRunning`），开关直接显示为运行中，不会重启内核。
- 通知栏停止、快捷方式、快捷设置磁贴、Always-on VPN、系统撤销 VPN 等入口全部经过模块：`ServiceState` 仍然是唯一的启停意图所有者，模块只是执行者。

### 模块接口（AIDL）

`android/service/src/main/aidl/com/follow/clash/service/module/IModuleService.aidl`

| 方法 | 用途 |
| --- | --- |
| `invoke(byte[])` | 一次 Core 方法调用（已有 JSON 信封），超过 256 KiB 走文件帧 |
| `quickSetup(initParams, setupParams)` | 初始化 Core 并下发配置 |
| `startRun(optionsJson, notificationJson)` | 启动一次运行（VPN 或代理服务 + Core listener） |
| `stopRun()` | 停止运行 |
| `status()` | `{"running","vpn","startedAt"}` |
| `setNotification(json)` | 通知文案/停止按钮 |
| `registerCallback / unregisterCallback` | Core 事件批次与「运行意外结束」回调 |

大响应由 `ModuleFrames` 落到 `files/module-ipc/`（两个进程同 UID 同目录），绕开 Binder 的 ~1 MiB 事务上限；Core 自身的 spill 机制照旧工作。

## 与上游的差异

| 项目 | 上游 | 本仓库 |
| --- | --- | --- |
| applicationId | `com.follow.clash` | `com.flclash.modular`（可与原版共存安装） |
| 内核位置 | App 进程内 | `:core` 进程（模块服务） |
| 划掉任务 | 内核随进程结束 | 内核继续运行 |
| 界面职责 | 启停 + 全部 Core 调用（同进程） | 启停 + 配置 + 通过 AIDL 转发 Core 调用 |
| 构建 | `build.yaml`（多平台发布） | 新增 `.github/workflows/android.yaml`（APK） |

Flutter/Dart 代码未改动：`lib/core/lib.dart`、`lib/plugins/service.dart` 的通道协议保持不变，Android 侧只是把通道后面的实现换成了模块客户端。

## 构建

GitHub Actions：推送后自动运行 `.github/workflows/android.yaml`，产物在 run 的 `flclash-modular-apk` artifact 中（arm64-v8a / armeabi-v7a / x86_64 三个 APK）。

本地（需要 Flutter 3.47.6、Go 1.27.1、Android NDK r28c、JDK 17）：

```bash
flutter pub get
flutter build apk --release --split-per-abi --dart-define=APP_ENV=stable
```

产物：`build/app/outputs/flutter-apk/app-<abi>-release.apk`。

没有配置签名密钥时，release 使用 debug 签名（可安装，但跨构建升级需要先卸载）。要固定签名，把 keystore 放到 `android/app/keystore.jks`，并在 `android/local.properties` 写入 `storePassword`、`keyAlias`、`keyPassword`。

## 安装与使用

1. 安装 APK（包名 `com.flclash.modular`，可与官方 FlClash 同时安装）。
2. 首次点主页开关：授予 VPN 权限（模块内的 `VpnService` 使用，授权属于整个应用）。
3. 开关打开后，通知栏会出现隧道通知；此时从最近任务划掉 App，代理/TUN 仍工作。
4. 重新打开 App，开关显示运行中，点击关闭即停止模块。

## 已知限制

- 未在真机验证（按需求不做真机操作），构建与静态检查在 CI 完成。
- 部分 ROM 的「强行停止」会连同所有进程一起结束；常规的最近任务划掉不受影响。
- 模块进程与界面进程共享应用数据目录，因此配置、geo 文件、MITM 证书路径保持不变。
- 桌面端、Windows/Linux Helper 等未改动。
