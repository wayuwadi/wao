# WAO Mobile

Android / iOS 共用 Vue 3 + Capacitor 8。代码在主仓库 `wao-mobile/`，独立于 Web 和桌面端构建。

```sh
cd wao-mobile
npm ci
npm run dev             # http://127.0.0.1:5178，本地热更新，代理 WAO HTTPS API
npm run check           # 用户链路单测 + TypeScript + 生产构建
npm run doctor          # 检查本地原生环境
npm run android         # 构建/同步并在 Android 设备运行
npm run ios             # 构建/同步并在 iOS 设备运行（需 Xcode）
npm run open:ios        # 选择 Personal Team，进行个人签名
```

本地原生环境：Node 22.12+、Android SDK 36 / JDK 21、macOS + Xcode 26。不必为了写 UI 先装齐所有原生环境；浏览器即可热更新，正式产物在 GitHub Actions 生成。

## 迭代发布
功能分支开发 → `npm run check` → 一个需求一个 PR → 合并 main。仅移动端目录改动才触发 `WAO Mobile Release` 自动双端构建，产物在 Actions 保留 30 天。

正式内测版本在最新已合并 main 的干净工作区运行：

```sh
npm run release -- 1.0.1
```

脚本检查远端 main、工作区、版本递增和测试后推送 `mobile-v1.0.1` 标签。CI 写入双端版本、签名 Android、构建 iOS 模拟器和未签名设备 IPA，两个平台成功后才发布私有仓库预发布 Release（含 SHA-256）。失败不会发布半套产物。标签版本是发布真相；`package.json` 用于日常开发基线。已公开的同版本产物不可覆盖，修复用新版本。

Android 签名 Secrets：`MOBILE_ANDROID_KEYSTORE_BASE64`、`MOBILE_ANDROID_KEYSTORE_PASSWORD`、`MOBILE_ANDROID_KEY_ALIAS`、`MOBILE_ANDROID_KEY_PASSWORD`。签名只在 CI 临时目录解密，退出时清理。必须备份原始密钥，不能每次 CI 随机生成，否则无法覆盖升级。

iOS 首版无需 Apple Secrets，交付的 IPA 由用户个人签名。未来 TestFlight 是独立后续配置，不能把 unsigned IPA 改名当作已签名版本。见 [安装说明](docs/INSTALL.md)。

## 可维护边界
- `src/api/`：后端协议和系统 HTTP 会话；密码不持久化，不做非幂等自动重试。
- `src/domain/`：类型、日期、小记结构处理。
- `src/composables/`：工作区、账号隔离草稿，防止旧账号请求回填新账号。
- `src/components/`：图标、任务行、草稿编辑器。
- `src/views/`：四个移动页；Vue Router 管理页面，Android 返回先收起编辑器。
- `src/tokens.css` / `style.css`：颜色、排版、触控区、安全区。
- `android/`、`ios/`：原生工程纳入版本管理；升级 Capacitor 后执行 `npm run sync` 并复核 diff。

任务复用 `/api/tasks`；日历按 `due` 日期组织，不擅自替换任务完整对象；小记复用 `kb-notes`，编辑携带 `expectedRev`。仅纯文本小记开放正文编辑，以免破坏既有富文本。没有离线假数据或自动后台写入队列；本机草稿不是云端已保存。

目前原生安装/个人签名/覆盖升级需真实设备验收，CI 编译不能代替。1.0 不申请相机、定位、麦克风或系统日历权限。
