# 自动 Git 更新记录验收

普通本地网页构建读取完整 Git HEAD；发布时 `WAO_GIT_SHA` 必须等于 HEAD，历史不足或 SHA 不匹配会停止构建。Jenkins 在宿主导出 TSV 给无 Git 的 Node 容器；`WAO_GIT_HISTORY` 只用于该可信构建输入，禁止复用旧版导出。

```powershell
node --test wao-web/scripts/git-release.node.mjs wao-web/scripts/release-highlights.node.mjs
npm --prefix wao-web run test:unit -- --run src/__tests__/GitCommitHistory.spec.ts src/__tests__/gitReleaseApi.spec.ts src/__tests__/ReleaseHistory.spec.ts src/__tests__/VersionUpdateNotice.spec.ts
npm --prefix wao-web run build
```

有 POSIX shell 的环境另运行 `sh deploy/jenkins/test-ci-change-plan.sh`，验证文档、测试批次发布网页但不重启后端。游戏范围仍独立，主站为提交记录总是构建。

本地正常登录后打开 `/features#recent-updates`，核对当前 SHA/最新标题、查看更多提交、保留旧精选、顶栏打开和体验入口；窄屏文字换行无横向溢出。刷新后记录不丢失。真实 UI 不能以组件测试替代。

发布验收读取目标制品的 `git-history.json` 和既有 `pluginWebUpdateNotice/web_version_by_plugin.json`：首条提交=部署 SHA、说明 id=`git-release-<SHA>`，标题来自该批次，静态摘要一致。保留后端实际版本与健康状态，不根据前端 Git 记录声称后端已升级。

每次 main 批次（包括纯文档与测试）均刷新静态记录；未发布提交在旧制品不可见，不能伪称已上线。Jenkins 尚未恢复时沿用既有 Windows 发布协调者；此工具不创建新后台发布任务。
