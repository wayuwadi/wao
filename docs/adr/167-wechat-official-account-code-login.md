# ADR-167：微信公众号带参二维码与验证码登录

## 状态

已采纳。

## 背景

任务 `t1788141345928` 希望接入截图中的“微信扫码关注公众号，再发送页面数字登录”。WAO 原有微信入口只实现
微信开放平台“网站应用”的 `snsapi_login` OAuth，需要另行申请网站应用、审核回调域，而且登录页因生产尚无该
资质没有展示入口。把普通公众号二维码或静态图片当作登录结果无法验证发送者，也无法把微信用户可靠关联到发起
登录的浏览器。

## 决策

- 保留原有微信开放平台网站应用 OAuth，供已有账号绑定使用；登录页新增独立的微信公众号入口，不混用两类应用的
  App ID、App Secret 或回调配置。
- 服务端调用微信官方 `cgi-bin/token` 与 `cgi-bin/qrcode/create` 创建五分钟有效的 `QR_STR_SCENE` 带参二维码，
  同时生成四位页面验证码。二维码 ticket、场景、扫码 Open ID 与状态只短时保存在共享 Redis，不落业务库。
- 认证公众号把明文消息回调配置为 `/api/auth/wechat/official/callback`。服务端按公众号 Token 校验 SHA-1 签名，
  只在同一个 Open ID 已扫描本次场景二维码后接受其四位文本验证码；回调 XML 禁用 DTD 与外部实体。
- 浏览器发起会话时把不可猜测会话 id 绑定到自身 HttpSession，轮询不能消费别的浏览器会话。验证完成后优先用
  公众号用户资料里的 Union ID 复用既有微信身份；资料接口权限不足时只使用回调已验证的 Open ID，不伪造昵称。
- 公众号配置不完整时仍展示微信入口，但明确说明缺少认证公众号配置；不展示静态二维码、演示验证码或假成功。
  飞书和邮箱登录不受影响。

## 配置与验收边界

> 后续变更：日常配置入口已迁移到“系统设置 → 系统登录配置”，数据库加密存储并兼容本节环境变量回退，见
> [ADR-168](168-system-managed-wechat-login-configuration.md)。

生产需要认证公众号具备生成带参数二维码和接收消息的真实权限，并在私有环境文件中配置
`WECHAT_OFFICIAL_LOGIN_ENABLED`、`WECHAT_OFFICIAL_APP_ID`、`WECHAT_OFFICIAL_APP_SECRET`、
`WECHAT_OFFICIAL_TOKEN`。微信后台服务器地址使用
`https://wao.wayuwa.cn/api/auth/wechat/official/callback`，消息加解密方式先选择明文模式。

代码可用模拟官方响应验证签名、带参二维码、扫码与验证码状态机；只有真实配置完成后，才能把微信内关注、发送
验证码、浏览器获得 WAO 会话作为生产验收。官方依据：[生成带参数的二维码](https://developers.weixin.qq.com/doc/offiaccount/Account_Management/Generating_a_Parametric_QR_Code.html)、
[接入指南](https://developers.weixin.qq.com/doc/offiaccount/Basic_Information/Access_Overview.html)、
[文本消息](https://developers.weixin.qq.com/doc/offiaccount/Message_Management/Receiving_standard_messages.html)。

## 后果

用户不需要微信开放平台网站应用即可按公众号交互登录；首次微信身份仍进入现有成员认领流程。四位验证码不是单独
的身份凭据，必须和带参二维码产生的同一 Open ID 扫码事件同时成立，因此不会把任意四位文本当成登录授权。
