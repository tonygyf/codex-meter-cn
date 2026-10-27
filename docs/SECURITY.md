# 安全与隐私

- 手机只接收自有快照网关的 READ_TOKEN。WRITE_TOKEN 仅用于采集器上传；Worker 要求两个 Secret 不同且至少 32 字符。请自行用密码学随机生成值，长度检查不能证明熵。
- 采集器不打开 `auth.json`，由 Codex CLI 使用它已有的本地登录；不自动登录、不收集或转发 ChatGPT 凭证。RPC 错误只显示通用提示，避免账号详情进入日志。
- 快照字段白名单、版本、百分比、时间和大小校验覆盖 Worker、Python、Dart 和 Kotlin；不存原始 app-server 响应。
- Android 设置整体使用 Android Keystore AES-GCM 加密。快照本身不包含身份凭证；SharedPreferences 私有存储。系统备份关闭，不把令牌提供给小组件或日志。
- 网络默认 HTTPS，拒绝跳转，防止将令牌转发给另一地址。HTTP 仅用于 loopback 和模拟器宿主调试；采集器默认仅监听 127.0.0.1。需要跨设备直连时，自己提供 HTTPS 终结层，不直接公开 Python HTTP 端口或 app-server。
- 演示模式在手机中不会运行后台网络请求或安排通知。采集器 `--demo` 是测试数据源，需由操作者识别，避免上传到真实部署。
- `.env`、`.dev.vars`、`*.private.json`、私钥、签名库、Android `local.properties`、构建目录均忽略。只提供占位模板；不要把真实配置写进 README、提交消息或 Actions 变量文件。
- Worker 的 `/health` 公开但不包含账号和额度。快照接口需要 Bearer 令牌，响应 `Cache-Control: no-store`；不启用浏览器 CORS。

## 威胁边界

Bearer 令牌泄露即允许对应的读或写权限；请轮换 Worker Secret、采集器环境与手机设置。一个部署仅支持一个账号及单写者，不支持公共共享、多租户或服务端账号绑定。KV 最终一致性、宿主机被入侵、root 手机、用户自行配置的代理或 TLS 不在本 MVP 解决范围内。

当前未添加公网防刷/WAF规则。计划公开网关时，由部署者按需求配置 Cloudflare 访问限制及费用告警。不要把读取令牌硬编码进 APK。

## 发布前检查

确认仓库名称与公开授权；检查 `git status --ignored`；确认未跟踪真实令牌、`.dev.vars`、`auth.json` 和签名材料；轮换曾外泄的值。GitHub Actions 仅构建开发 APK，不持有 Cloudflare 凭证。
