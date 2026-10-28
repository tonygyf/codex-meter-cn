# 手机直接登录和额度读取：可行性记录

2026-10-28；用户希望不依赖电脑定期采集。此文件记录调研，不表示功能已经实现。

## 已确认

- OpenAI 提供面向开源和本地运行应用的「Sign in with ChatGPT」流程，可在浏览器中由用户授权；文档的开源客户端流程使用 PKCE、state、nonce 和本机 127.0.0.1 回调。授权能力面向身份和符合条件的 AI 请求，不能据此假设可访问所有账户数据。
- 官方 Codex app-server 提供 `account/rateLimits/read`，返回额度窗口；它的 `account/login/start` 提供实验性的 `chatgptAuthTokens` 模式。该模式、登录流程与实际额度接口组合是否适用于手机上的本项目尚未测试。
- 当前 Python 采集器在电脑上运行 Codex app-server 已读到 5 小时和每周窗口；手机现有版本尚不能登录 ChatGPT。
- 当前 Worker 只接收并返回快照，没有 app-server 运行环境，也没有 GPT 凭证；单独部署 Worker 不会自动更新额度。

## 待验证

1. 官方开源应用登录流程是否允许并适合只读额度的使用目的，手机系统浏览器的回调及账户权限是否可用。
2. 用户授权所得令牌能否通过官方支持的调用方式读取所需 Codex 额度；不能从 AI 推理授权推断额度读取权限。
3. app-server 是否能在 Android 环境中以受支持的方式运行、如何更新发行文件及满足许可证；Flutter + Worker 本身不等于可运行 app-server。
4. Android 后台限制下如何做定期刷新、处理令牌轮换和安全存储；联网路径、服务可达性和电池限制需要真机验证。

如果没有得到官方支持的直接额度读取路径，不能承诺手机直接登录后自动同步。备选是用户自己的常开机器运行采集器 + Worker；也可以继续以现有电脑采集作临时方案。

## 来源

- [Sign in with ChatGPT：开源应用](https://developers.openai.com/siwc/token-sharing-open-source)
- [注册与登录](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Codex app-server 文档](https://developers.openai.com/codex/app-server/)

本次 Cloudflare 授权已完成；尚未创建 KV、部署 Worker 或访问真实 GPT 登录令牌。

## 2026-10-28：Worker 持久登录方向

用户希望新手机通过自有域名连接 Worker，Worker 自行完成 ChatGPT 登录持久化和 Codex 额度读取，无需电脑持续运行。官方 app-server 公开了额度 RPC，但尚未查到面向 Worker 的公开、稳定、可直接读取对应窗口的 HTTP API。Codex 开源客户端中能看到内部后端调用路径，不能因此视为对第三方远程服务稳定开放或默认具有 OAuth 访问权限。正式登录流程的文档注明，远程托管应用的 ChatGPT plan usage 需要走申请流程；是否适用于此只读用途仍需明确。不得把尚未验证的内部接口或令牌授权当作已经完成。

Cloudflare Custom Domain 需要用户拥有的活动 zone；绑定时 Cloudflare 创建 DNS 记录和证书。当前 Wrangler 的受限授权不含 zone 权限，执行域名操作时可能需要用户另行授权。国内实际连通性必须从用户手机网络测试，域名本身不保证可达。

本次已在新 Android 手机安装并启动现有 App；Worker 数据源设置支持仅输入域名。用户已提供目标域名，公开 DNS 暂无 A、AAAA、CNAME 或 TXT 记录，但尚未取得 Cloudflare zone 权限核查控制台记录。Worker 登录和独立云端采集尚未实现或部署。

新增 `services/worker/src/experimental-codex.ts`：依据 Codex 客户端源码的后端响应格式实现实验性转换与固定地址请求，4 项测试使用模拟响应验证字段白名单、异常、拒绝重定向和大小限制；模块未接入 Worker 路由，也未用真实凭证请求。尚未验证 OpenAI 对远程应用的 OAuth 授权、令牌持久化和续期，不能当作可登录的功能发布。

- [Cloudflare Worker 自定义域名](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)
