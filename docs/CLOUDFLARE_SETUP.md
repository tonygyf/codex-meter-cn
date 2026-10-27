# Cloudflare 接入占位与自部署步骤

本文件留给项目所有者后续填写，不在此记录真实 Secret。当前未连接你的 Cloudflare 账号。

## 待填清单

- [ ] 确认 Worker 名称（本地占位 `codex-meter-cn`）和所属 Cloudflare 账号。
- [ ] 创建 KV Namespace，把 ID 填入 `services/worker/wrangler.jsonc` 的 `kv_namespaces[0].id`。
- [ ] 分别生成两个不同的高熵令牌，各至少 32 字符（推荐 `secrets.token_urlsafe(32)`）。
- [ ] Worker Secret `READ_TOKEN`：只交给手机和你自己使用。
- [ ] Worker Secret `WRITE_TOKEN`：只交给采集器使用。
- [ ] 部署后确认自己的 HTTPS 域名可访问，不在本项目自动改 DNS、代理、路由或服务器防火墙。
- [ ] 在手机设置中填写完整 HTTPS `/v1/snapshot` URL 和 READ_TOKEN。
- [ ] 在采集机设置 METER_UPLOAD_URL 和 METER_WRITE_TOKEN，启动采集器。

## Worker

在 `services/worker` 中手动执行：

```sh
npm ci
npx wrangler login
npx wrangler kv namespace create SNAPSHOTS
# 按命令输出修改 wrangler.jsonc 的 KV ID
npx wrangler secret put READ_TOKEN
npx wrangler secret put WRITE_TOKEN
npm run check
npm test
npm run deploy
```

令牌交互输入，不要加入 `vars`、命令行参数、GitHub Actions 日志或提交文件。真实域名占位：`https://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev/v1/snapshot`。可以自行绑定自定义域名，但当前项目不会改任何网络设置。

## 采集器上传

PowerShell：

```powershell
$env:METER_UPLOAD_URL = 'https://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev/v1/snapshot'
$env:METER_WRITE_TOKEN = Read-Host '输入单独的 Worker WRITE_TOKEN'
python tools/collector/collector.py
```

执行一次然后退出用 `--once`；需要同时直连时另设独立 `METER_READ_TOKEN` 并加 `--serve`。`--demo` 会上传模拟值，仅用于空白测试部署，禁止误当真实额度。采集器默认每 300 秒采集，快照有效期 900 秒。采集失败不产生新的时间戳。

Linux/macOS 可用 `read -rs METER_WRITE_TOKEN; export METER_WRITE_TOKEN` 设置环境变量，避免写在 shell 历史。长期运行服务的环境文件须置于仓库外并限制权限。`.env.example` 仅说明字段，Python 不自动加载它。

## 接口验收

| 请求 | 认证 | 预期 |
| --- | --- | --- |
| GET /health | 无 | 200，仅返回健康状态及版本 |
| GET /v1/snapshot | Bearer READ_TOKEN | 有快照 200；无快照 404 |
| PUT /v1/snapshot | Bearer WRITE_TOKEN | 合法且未过期的快照 200 |
| 用读取令牌执行 PUT | READ_TOKEN | 401 |
| 用写入令牌执行 GET | WRITE_TOKEN | 401 |
| 令牌相同或少于 32 字符 | 任意 | 503，要求修复配置 |

Worker 不延长过期快照的时间，不把登录信息写入 KV，不记录请求体或 Authorization。KV 保留最近快照供离线诊断；它不是强一致数据库，跨地域可能短时读到旧值。当前约定只运行一个采集器写同一部署。需要多账号、严格有序写入或审计时再扩展协议与存储。

## 本机 Worker 模拟

复制 `.dev.vars.example` 为 `.dev.vars`，用自己生成的测试令牌替换，然后运行 `npm run dev`。默认 Wrangler 端口通常为 8787，快照路径仍是 `/v1/snapshot`；客户端可通过 loopback / 模拟器地址测试。`.dev.vars` 已忽略，不可提交。

## 上线前由所有者确认

域名可达性、Cloudflare 配额、KV 费用、令牌轮换、个人账号隐私、APK 签名。当前 GitHub 工作流不包含部署凭证，也不会自动发布 Worker。
