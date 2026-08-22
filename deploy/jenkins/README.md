# WAO Jenkins

WAO 主站由 Linux 主节点上的单节点 Jenkins LTS 构建和发布。Windows 只接收已经构建好的源码包、
镜像归档和私有配置；阿里云继续托管静态前端、Nginx 和唯一公开的 GitHub webhook 路由。

## 一次性准备

在 Linux 主节点的最新 WAO checkout 中执行：

```sh
sudo sh deploy/jenkins/prepare-controller-secrets.sh
```

脚本只输出两把公钥。把 Windows 公钥加入 `wage` 的 OpenSSH `authorized_keys`，把云公钥加入云部署账号。
私钥始终留在 `/etc/jenkins/secrets`。

人工核对两台目标机器控制台上显示的 SSH 指纹后，把完整 `known_hosts` 行分别保存为：

```text
/etc/jenkins/secrets/windows-known-hosts
/etc/jenkins/secrets/cloud-known-hosts
```

在 GitHub 创建仅限 `wayuwacn/wao` 的细粒度 PAT，权限为 `Contents: read`、
`Commit statuses: read/write`，将令牌直接写入 `/etc/jenkins/secrets/github-pat`，不要粘贴到命令历史或日志。
安装脚本会为 `jenkins` 用户配置仅匹配 `https://github.com` 的 Git credential helper；helper 运行时直接读取
这个文件，Token 不进入 Git 配置、进程参数或控制台输出。Credential Store 中的同一 PAT 只用于 GitHub API
和提交状态回写。
随后运行：

```sh
sudo WAO_JENKINS_SCM_BRANCH=codex/jenkins-cicd sh deploy/jenkins/install-controller.sh
sudo VERIFY_PUBLIC_WEBHOOK=false sh deploy/jenkins/verify-controller.sh
```

如果 Linux 到 GitHub 的直连不稳定，而主机已有只监听本机的 HTTP/mixed 代理，可在安装时传入端口。例如
Clash Verge 默认在本机 `7897` 提供代理时：

```sh
sudo WAO_JENKINS_SCM_BRANCH=codex/jenkins-cicd \
  WAO_JENKINS_PROXY_PORT=7897 sh deploy/jenkins/install-controller.sh
```

安装脚本只接受本机端口，并为 Git 检出、GitHub 状态回写和 Jenkins 下载设置代理；Linux、Windows、
阿里云以及 Jenkins 本机地址均通过 `NO_PROXY` 直连。代理进程必须在 Jenkins 启动和构建期间持续运行。

这里的临时分支只用于合并前影子构建。首次手工构建必须设置 `DEPLOY_PRODUCTION=false`；验证通过后合并 PR，
再从 `main` checkout 重跑安装脚本（不设置 `WAO_JENKINS_SCM_BRANCH`）并确认任务 SCM 已切回 `main`。
生产 Webhook 只能在 SCM 为 `main` 时启用。

Jenkins UI 只监听 `127.0.0.1:8081`。管理时在自己的电脑建立隧道：

```sh
ssh -L 8081:127.0.0.1:8081 wadi@linux2.wayuwa.cn
```

再打开 `http://127.0.0.1:8081`。管理员密码在 Linux 的
`/etc/jenkins/secrets/admin-password`，不要复制到仓库。

## GitHub webhook

仓库 Webhook 使用：

```text
Payload URL: https://wao.wayuwa.cn/github-webhook/
Content type: application/json
Secret: /etc/jenkins/secrets/github-webhook-secret 的内容
Events: Just the push event
```

云 Nginx 只代理这个精确路径到 `127.0.0.1:18081`。该端口由 Linux 主节点发起的反向 SSH 隧道提供；
Jenkins UI、CLI 和其他 API 不通过云节点暴露。

首次 Jenkins 手工生产发布会把带 Webhook 路由的新 Nginx 模板原子发布到云节点。随后创建 GitHub Webhook，
使用 GitHub 的 Test delivery 验证签名请求，再执行完整验收：

```sh
sudo sh deploy/jenkins/verify-controller.sh
```

## 流水线

`Jenkinsfile` 默认从 `main` 拉取代码并执行：验证、后端测试、两个前端测试与构建、四个 SHA 镜像构建、
Windows → Linux → 云端严格滚动发布、线上验收、GitHub commit status 和飞书通知。

- `DEPLOY_PRODUCTION=false`：完整影子构建，不接触生产节点。
- `BOOTSTRAP_WINDOWS=true`：显式生成 GTID 快照并重建 Windows 从库；正常发布禁止开启。
- 同一任务不并发，新 push 排队且不取消正在进行的 migration 或发布。

## 密钥与恢复

JCasC 只提交非敏感结构；启动时用 `readFile` 从 `/etc/jenkins/secrets` 导入 Credential Store。
每日 02:30 在执行器空闲时停止 Jenkins，备份 `JENKINS_HOME` 与 `/etc/jenkins` 到
`/var/backups/jenkins`，保存 SHA-256 并保留最近 7 份。备份不包含 workspace、Maven/npm 缓存。

恢复时先安装相同 Jenkins LTS 与 `plugins.txt` 中的插件，再恢复归档；`secrets/master.key` 与
`credentials.xml` 必须来自同一份备份。
