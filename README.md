# Codex Meter CN

中文优先的 Android Codex 额度查看器。Flutter + Dart 客户端，TypeScript Cloudflare Worker + KV 快照网关，Python 标准库采集器。当前为 **0.1.0 MVP 源码**，不是已上线服务，与 OpenAI 无隶属关系。

## 已实现

- 演示、直连采集器、自部署 Worker 三种模式；两个真实数据源共用 `/v1/snapshot` 协议。
- 5 小时 / 每周剩余额度、重置倒计时、未知窗口提示。
- 本地离线快照、过期提示；重置时间到达后等待新快照，不自行恢复到 100%。
- Android Keystore 加密保存数据源设置和只读令牌；读写令牌隔离。
- Kotlin 紧凑桌面小组件、WorkManager 后台刷新、本地低额度和预计重置提醒。
- Python app-server 只读采集、模拟采集、直连 HTTP 网关、上传 Worker。
- 统一版本化 JSON、测试、GitHub Actions 测试及 debug APK 构建。

## 先运行，不需要 Cloudflare

需要 Flutter 3.35.3 / Dart 3.9.2、Android SDK、JDK 17（本机 Android Studio 的 JDK 21 也可尝试）。

```sh
cd apps/mobile
flutter pub get
flutter test
flutter run
```

应用默认“演示模式”，明确标记模拟数据，不联网、不安排提醒。真实数据源通过右上角“数据源与提醒”设置，不需要修改 Dart 源码。

### 用模拟采集器打通直连

在仓库根目录的 PowerShell 中运行：

```powershell
# 每次自行生成。值只存在当前终端环境，切勿粘贴到仓库或截图中。
$env:METER_READ_TOKEN = python -c "import secrets; print(secrets.token_urlsafe(32))"
python tools/collector/collector.py --demo --serve
```

Android 模拟器填写 `http://10.0.2.2:8788/v1/snapshot`，只读令牌填写该终端的 `METER_READ_TOKEN`。真实手机通过 USB 调试时，可自行执行 `adb reverse tcp:8788 tcp:8788`，然后填写 `http://127.0.0.1:8788/v1/snapshot`。这里只提供操作说明，本次开发未操作设备或端口转发。

HTTP 仅允许 loopback 和 Android 模拟器宿主地址；跨设备、局域网与公网使用你自己的 HTTPS 地址。不要直接暴露 app-server 或把 ChatGPT 的访问令牌填到手机。

### 换成真实 Codex 额度

先在采集机器上安装 Codex CLI 并自行完成 `codex login`，再去掉 `--demo`：

```powershell
python tools/collector/collector.py --serve
# 仅采集一次，用于检查本机 app-server 是否支持读取额度：
python tools/collector/collector.py --once
```

采集器启动独立 `codex app-server` 子进程，执行初始化握手和 `account/rateLimits/read`，随后退出子进程。不会发送推理任务、修改已有会话或读取/上传 `auth.json`。使用 CLI 自己已有的登录状态；未登录或额度不可用时报告失败，保留内存里的旧快照且不延长有效期。

## Cloudflare 稍后接入

接入清单和所有待填项在 [docs/CLOUDFLARE_SETUP.md](docs/CLOUDFLARE_SETUP.md)。当前 KV ID 与域名是占位，不可直接部署。README 中没有真实令牌。

```sh
cd services/worker
npm ci
npm run check
npm test
```

没有执行 `wrangler login`、部署、GitHub 推送或创建远程仓库。公开发布前，需要你确认 GitHub 仓库名、账号及授权。

## 目录

```text
apps/mobile/                 Flutter 中文客户端 + Android Kotlin 适配
  lib/                       额度模型、网络读取、界面与设置
  android/app/src/main/      Keystore、提醒、后台刷新、小组件
  test/                      协议、界面、真实本机 HTTP 测试
services/worker/             TypeScript Worker、KV 读写、独立令牌
tools/collector/             Python app-server 采集器和直连网关
packages/protocol/           JSON Schema 与跨语言共用模拟样例
docs/                       接入、安全、协议、环境与实际完成情况
.github/workflows/ci.yml      Python / Worker 测试及 Android debug APK
```

## 验证与构建

```sh
python -m unittest discover -s tools/collector/tests -v
cd services/worker
npm ci
npm run check
npm test
cd ../../apps/mobile
flutter pub get
flutter analyze
flutter test
cd android
./gradlew :app:testDebugUnitTest
cd ..
flutter build apk --debug
```

Windows 下用 `android/gradlew.bat`。本地命令与实际结果见 [docs/STATUS.md](docs/STATUS.md)。GitHub Actions 上传 APK 到工作流 Artifacts，不创建 Release、不部署 Worker。当前使用开发签名，仅用于 MVP 试用；正式发布的签名配置见 [docs/ANDROID_SETUP.md](docs/ANDROID_SETUP.md)。

## 边界

Android 后台刷新并非实时；系统省电可延迟任务及提醒。小组件是本地快照视图，不直接连接 ChatGPT，数据源断网时仍保留最近数值并标记过期。Worker 可达性取决于你的网络、域名和服务配置，不保证任一网络均可访问。

MVP 只展示 `codex` 桶里时长为 300 / 10080 分钟的窗口，其他时长显示未知，不硬套成 5 小时或每周。当前一个部署服务一个账号、一个采集器；KV 是最终一致存储，不能用于强一致多写者场景。

许可证为 MIT，依赖与复用检查见 [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)。
