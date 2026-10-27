# 快照协议 v1

Schema：`packages/protocol/snapshot-v1.schema.json`；模拟样例：`packages/protocol/fixtures/snapshot-v1.json`。Python、TypeScript、Dart 测试共同读取该样例；Kotlin 单元测试验证原生边界。

```json
{
  "schemaVersion": 1,
  "collectedAt": 1793100000,
  "expiresAt": 1793100900,
  "windows": {
    "fiveHour": {"usedPercent": 37, "windowMinutes": 300, "resetsAt": 1793114400},
    "weekly": {"usedPercent": 68, "windowMinutes": 10080, "resetsAt": 1793532000}
  }
}
```

所有时间为 Unix 秒，不是毫秒。百分比为已消耗量，界面用 `100 - usedPercent` 显示剩余量。缺失窗口必须为 `null`，重置时间未知时 `resetsAt` 为 `null`；不能把未知当成 0 消耗。

所有对象禁止额外字段；采集器只映射允许的字段，不转发原始 RPC 内容。`usedPercent` 必须是有限的 0–100 数字。窗口时长固定为 300 / 10080 分钟。版本不认识时拒绝读取，保留已有有效缓存。

额外运行时约束：`collectedAt` 不得超前接收端时钟超过 60 秒；`expiresAt` 晚于采集时间且最多相差 3600 秒；快照最多 16 KiB。Worker 上传拒绝已过期数据；读取允许过期快照，让客户端明确标记。客户端不会用接收时间替换采集时间。

## app-server 映射

1. 启动 `codex app-server` 的 stdio JSON-RPC 连接。
2. 发送 `initialize`，等对应 id 的响应；发送 `initialized` 通知。
3. 发送 `account/rateLimits/read`，忽略不对应的通知，只取对应请求的结果。
4. 若存在 `rateLimitsByLimitId`，只用其中的 `codex`；不存在时用旧的 `rateLimits`。
5. 对 primary / secondary 按 `windowDurationMins` 匹配窗口，而非假定顺序固定。
6. 仅生成本协议字段。账号身份、credits、访问令牌、错误原文均不传给手机或 KV。

参考 [OpenAI 官方 app-server 文档](https://developers.openai.com/codex/app-server/)。实际账号能返回哪些窗口取决于服务和计划；本地未执行真实账号联调。

## 提醒语义

新鲜快照、重置时间未过且剩余量达到阈值时提醒。同窗口同重置时间只提醒一次；重置时间未知时仅提醒一次，直到重新保存设置。重置提醒来自最后一次快照提供的预计时间，文字明确要求联网刷新确认，绝不宣称额度必定恢复。Android 使用非精确 AlarmManager，无精确闹钟权限。
