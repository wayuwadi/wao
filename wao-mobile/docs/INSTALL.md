# WAO 移动端内测安装

## Android
下载 `WAO-X.Y.Z-android.apk`，在手机按系统提示允许当前来源安装。后续下载新版覆盖安装，不要先卸载。
固定应用 ID 为 `cn.wayuwa.wao`。签名不变，云端数据及应用本机草稿可随正常覆盖更新保留。卸载会删除本机草稿。
AAB 是未来应用商店上传产物，不能直接点击安装。

## iOS：用户个人签名
当前没有 Apple Developer 付费账号，不要求 TestFlight。下载 `WAO-X.Y.Z-ios-unsigned.ipa`，使用你已有的个人签名工具导入、签名并安装。Apple ID、密码和签名操作由你在可信工具中完成，不要放入仓库、CI 或聊天。
此 IPA 未签名，不能直接点击安装。需要续签时保持同一签名身份与最终 Bundle ID；更换工具/身份导致 Bundle ID 变化会安装成不同应用，原应用的本机草稿不会自动迁移。云端内容重新登录后可取回。
若使用完整 Xcode：`npm ci && npm run sync && npm run open:ios`，在 App target → Signing & Capabilities 选择你的 Personal Team 和设备，再运行；如果默认 Bundle ID 被占用，按你个人签名工具固定一个专用 ID，以后持续保持。
签名有效期与免费账号限制遵循 Apple 和所用工具的当前规则。未签名产物不等于已完成真机验收。

## 登录和验收
使用已绑定邮箱密码的 WAO 账号。没有邮箱密码的用户先在 Web「设置 → 账户与登录」绑定。
1. 手机登录，创建一条待办，确认 Web 同步可见；完成后刷新 Web。
2. 在日历选择该待办截止日期，确认可见。
3. 创建小记并在 Web 检查；编辑未保存时收起并重开，确认草稿恢复。
4. 用下一版覆盖安装，再确认登录和草稿。网络中断时保存按钮失败应保留内容。
旧格式/富文本小记保持只读以保护原文，正文编辑请用 Web；移动新建的纯文本小记可双端编辑。

## 发布渠道
安装包位于私有 GitHub 仓库 Release，下载需要仓库访问权限。不要为分享两人内测包把整个仓库公开。
后续接入 Apple Developer 后可增加 TestFlight；首版不含 App Store/Google Play 公开上架，也不执行应用内远程代码热更新。

## 站内下载

访问 https://wao.wayuwa.cn/download 无需 GitHub 登录即可下载最新 APK 与未签名 IPA。页面会展示真实版本与 SHA-256。移动端版本发布成功后自动更新站内清单，无需重新发布 WAO 主站。若 GitHub Release 已完成而云端上传失败，重新运行失败的 `publish` job 即可重试；相同版本只能复用相同制品，不能更换包内容。
