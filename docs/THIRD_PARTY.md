# 来源、可复用源码与许可证检查

检查日期：2026-10-27。项目以全新 Monorepo 实现，没有复制已有私有项目或第三方额度应用源码。

## 本地检查

只读检查了已登记项目路径与常见源码目录。`D:/typer/android_demo` 和 `Documents/GitHub` 未返回项目源码，登记的 FaceCheck、omniattend-core、vpnapp/vpnapp2 未作为代码输入。没有递归访问训练数据、修改训练项目、操作容器或服务器网络。

本地 Flutter SDK 3.35.3 的 LICENSE 为 BSD-3-Clause，使用其 `flutter create --platforms=android` 模板生成 Android 工程。原始许可证已保留在 `docs/licenses/Flutter-BSD-3-Clause.txt`。

## 公开项目核对

| 来源 | 检查结果 | 本项目使用方式 |
| --- | --- | --- |
| [openai/codex LICENSE](https://github.com/openai/codex/blob/main/LICENSE) | Apache-2.0 | 仅通过已安装 CLI 的 app-server 协议交互；不复制源码、不分发 CLI |
| [BenItBuhner/Codex-Meter LICENSE](https://github.com/BenItBuhner/Codex-Meter/blob/main/LICENSE) | MIT，Copyright 2026 Bennett | 作为潜在同类项目核对许可证，未复制代码或资源；不能确定就是上一段对话截图中的应用 |
| [官方 app-server 文档](https://developers.openai.com/codex/app-server/) | 官方接口说明 | 依据公开方法与字段独立实现 JSON-RPC 客户端 |

## 直接依赖

| 依赖 | 用途 | 许可证 |
| --- | --- | --- |
| Flutter / Dart SDK | 手机界面、网络与测试 | BSD-3-Clause，SDK 自带 notices |
| flutter_lints | 开发检查 | BSD-3-Clause |
| AndroidX WorkManager / Core | 后台任务、通知 | Apache-2.0 |
| TypeScript | Worker 编译检查 | Apache-2.0 |
| tsx | Worker 测试执行 | MIT |
| Wrangler / workers-types | Cloudflare 开发工具与类型 | MIT OR Apache-2.0 |
| @types/node | 测试类型 | MIT |
| JUnit 4 | Kotlin 单元测试 | EPL-1.0 |
| org.json:json | JVM 原生协议测试 | 公有领域（此测试依赖不打入 APK） |

Node 直接依赖许可证字段已读取安装后的 package.json；Flutter SDK 原文已读取。所有锁文件提交以便复现；间接依赖由上游许可证约束，正式发行时应基于最终依赖树生成完整 notices / SBOM。Android 调试 APK 不等于完成商店发布合规审核。

项目原创源码使用根目录 MIT LICENSE；上游各自的许可证仍有效。没有使用第三方图片、品牌图标或字体资源，启动图标仍为 Flutter 默认占位，正式发行前可替换。
