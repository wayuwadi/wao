---
name: wao-development-connections
description: Reuse the user's authorized development service sessions and locate WAO vault entries when a CLI, MCP, or app requires credentials or QR login.
---

# 复用开发服务授权

1. 确认服务、环境和账号用途。星狮 DevOps 用 `wao connections devops status` 检查 WAO 密码本和本机授权，查询可访问环境时可调用 `devops_environments`。团队 CLI 的后续操作需要本机授权时先运行 `wao connections devops restore`。仅在这些命令返回 `AUTH_REQUIRED` 时，让本人通过团队 CLI 扫码并用 `wao connections devops sync` 保存。WAO 权限、网络和设备密钥错误分别处理。Codeup 用 `codeup_pr_status` 或 `wao connections codeup status PR编号` 验证本机已有授权。
2. 有已知条目名时，使用目标 WAO 的 `wao vault find '准确条目名' --output json`。同名多账号可加 `--account`。`hasSecret` 仅表示凭据已保存，验证有效性仍由目标服务负责。授权缺失时请本人在能力中心选择所需的密码本 CLI 权限。
3. 凭据缺失时，让本人通过原客户端授权，或在内置终端运行 `wao vault save '准确条目名' --account '账号'` 隐藏输入。Mac 上本人可用 `--from-codeup-keychain` 把已有 Codeup CLI 授权存入 WAO，按终端显示的目的地址确认。其它服务如有受信任的正式导出程序，可将凭据直接通过管道交给 `--secret-stdin`。
4. 向模型和用户回报服务及条目标识、认证状态和业务结果。技能只记录映射和正常入口。密码、Cookie、令牌、设备码和可登录的回调链接留在服务指定的身份验证或安全输入流程。把重新登录交给原客户端，由本人扫码或完成 MFA；登录成功后接续已授权的原操作。
