# ADR-220：个人表情 PKCE 授权使用配套的 v2 令牌端点

- 日期：2026-09-09
- 状态：已采纳
- 补充 ADR-215，修订 ADR-217 的“保留现有 OAuth 地址”决策。

## 问题与证据

成员完成飞书同意授权后，WAO 仍显示授权失败。2026-09-09 13:12:48 的生产诊断 `14928720` 对应 `invalid_grant / HTTP 400 / code=20049`。飞书官方错误表将 20049 定义为 PKCE 校验失败，不能据此要求成员继续添加权限或把刚完成的授权笼统归因为过期。

当前授权入口是 `https://accounts.feishu.cn/open-apis/authen/v1/authorize`，WAO 原先向 `https://accounts.feishu.cn/oauth/v3/token` 兑换令牌。官方“获取授权码”文档的 `code_challenge` 说明明确要求该接口的 PKCE 暂时搭配 v2 获取令牌，后续支持最新令牌端点。虽然令牌文档的一般迁移说明推荐 v3，本流程优先遵循授权入口的具体兼容性说明。真实授权成功仍须部署后由成员重新同意验证。

来源：
- [获取授权码：code_challenge 兼容性](https://open.feishu.cn/document/common-capabilities/sso/api/obtain-oauth-code)
- [获取 user_access_token（v2 版本）：端点与 20049](https://open.feishu.cn/document/authentication-management/access-token/get-user-access-token)

## 决策与兼容

- 将个人表情令牌兑换与刷新默认端点改为 `https://open.feishu.cn/open-apis/authen/v2/oauth/token`，保留 JSON 请求和可配置的 `feishu.reactions.token-url`。两台生产节点已核对没有覆盖此配置。
- 保留 S256 challenge、原始 verifier、会话与应用绑定、一次性 state、真实用户身份和 scope 检查、凭证加密、结果页防重复兑换。失败不移除 PKCE，也不拿同一授权码跨端点重试。
- 将 20049 单独分类为 `pkce_mismatch`，页面提示管理员检查接口配对，不再误导成员重复添加权限。日志仍仅记录固定分类、数值状态、业务码和本地诊断号。
- 普通登录、已有凭证存储结构、成员反馈和消息身份边界不变。无需 migration；不代替成员点击 OAuth 同意。

## 验证

回归覆盖生产默认 v2 地址上的首次兑换和刷新、challenge 与会话 verifier 的 SHA-256/Base64URL 对应关系、兑换提交同一个 verifier 与 callback、20049 安全分类和禁止重复消费、凭证加密与权限校验。生产验收须确认双节点版本和新授权的成功结果；没有成员重新同意时，不把模拟接口测试或构建成功作为完整授权验收。
