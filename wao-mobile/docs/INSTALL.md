# WAO 移动端内测安装

## 1.2.0：仅恢复 iOS 小记采集

此轮按 ADR-323 只构建 iOS 主应用和 WAO 分享扩展。Android 与旧双平台公开下载发布保持禁用，下面旧版本下载说明不代表 1.2.0 已发布。Actions「WAO iOS Notes Build」的制品是**未签名 IPA**，不能直接安装，也不是 TestFlight 包。

Windows 可完成代码与未签名构建下载；安装前仍需确定你实际使用的 Apple 签名路径。若使用 Mac/Xcode：`npm ci && npm run sync:ios && npm run open:ios`，App 和 WAOShare 两个 target 必须使用同一 Team。为该 Team 配置 App Group `group.cn.wayuwa.wao.notes`，以及共享 Keychain `$(AppIdentifierPrefix)cn.wayuwa.wao.notes`；两者必须同时出现在主应用和扩展的正确签名中。对未签名包重新签名时，还需将两个 Info.plist 的 `WAOSharedKeychainGroup` 更新为实际签名的 AppIdentifierPrefix；只改 entitlements 不够。推荐在 Xcode 选择 Team 后重新构建。若标识被占用，应同时修改两个 target、共享 entitlements 和 CaptureStore 的 App Group 常量，之后保持稳定。个人签名工具若移除扩展或 App Group / Keychain 权限，不能据此宣称分享功能可用。

首次打开使用已有 WAO 邮箱密码登录。之后在微信读书复制原文，再打开 WAO 点击「导入」并允许系统粘贴；或在「分享到其他」中选择 WAO（必要时在「更多 / 编辑」启用），核对实际收到的文字、链接或图片。录音会影响正在播放的听书；结束、来电、切到后台或满 60 秒会停止并保留音频，自动转写结果接在感想下方。拒绝识别权限或离线失败可重试，未保存文字前不删除录音。

当前不预设你拥有付费账号、Mac 或签名工具；签名安装、微信读书实际载荷、iPhone 12 上的键盘/安全区与听书恢复仍需真机验收。

## Android
下载 `WAO-X.Y.Z-android.apk`，在手机按系统提示允许当前来源安装。后续下载新版覆盖安装，不要先卸载。
固定应用 ID 为 `cn.wayuwa.wao`。签名不变，云端数据及应用本机草稿可随正常覆盖更新保留。卸载会删除本机草稿。
AAB 是未来应用商店上传产物，不能直接点击安装。

## iOS：用户个人签名
1.1.x 的历史内测采用个人签名；1.2.0 必须先满足上面主应用、扩展、App Group 与共享 Keychain 的签名条件，再选择实际可用的安装工具。Apple ID、密码和签名操作由你在可信工具中完成，不要放入仓库、CI 或聊天。
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
# WAO 内的松风江湖（1.1.0）

安装本次 WAO 更新后，在「今日」点击「进入游戏」。游戏窗口顶部「返回 WAO」可退出，Android 也支持系统返回。游戏使用独立账号；WAO 登录不会自动创建或绑定游戏账号。以后再次打开会加载同一正式游戏服务的版本，不需要另装游戏 App。

Android 最低支持 Android 9（API 28）。iOS 包仍需个人签名。原生关闭、重开登录、横屏与覆盖安装的真机验收结果单独记录，不以浏览器测试代替。
