# WAO Jenkins

WAO 主站由 Linux2 主节点上的单节点 Jenkins LTS 构建和发布。Linux1 从节点只接收已经构建好的源码包、
镜像归档和私有配置；阿里云托管静态前端、Nginx、受 WAO 会话保护的 Jenkins UI 入口和唯一公开的
GitHub webhook 路由。

## 一次性准备

在 Linux 主节点的最新 WAO checkout 中执行：

```sh
sudo sh deploy/jenkins/prepare-controller-secrets.sh
```

脚本输出三把公钥。把 Linux1 从节点公钥加入 `wage` 的 OpenSSH `authorized_keys`，把云公钥加入云部署账号，
把 GitHub SCM 公钥作为独立的 Authentication key 加入用于 `wayuwacn/wao` 的 GitHub 账号。
私钥始终留在 `/etc/jenkins/secrets`。

Linux1 的发布阶段直接使用控制器本地的 `slave-ed25519` 与 `slave-known-hosts`，两者必须保持
`root:jenkins 0640`；这样首次切换拓扑的构建不依赖构建结束后才由 JCasC 注册的新凭据 ID。云节点继续通过
Jenkins Credentials 绑定，密钥内容不会进入环境变量或构建日志。

人工核对两台目标机器控制台上显示的 SSH 指纹后，把完整 `known_hosts` 行分别保存为：

```text
/etc/jenkins/secrets/slave-known-hosts
/etc/jenkins/secrets/cloud-known-hosts
```

仓库策略禁用了 Deploy key，因此 SCM 使用账号级但仅供 Jenkins 保存的独立密钥；Credential Store 只把它绑定到
受信任 `main` 的仓库检出，不提供给流水线步骤。通过 GitHub 官方 Meta API 取得 SSH 主机公钥并与实际握手结果
核对后，把 `github.com` 的完整行保存为
`/etc/jenkins/secrets/github-known-hosts`。安装脚本会复制它到 Jenkins 用户的 OpenSSH `known_hosts`，SCM
使用 `github-scm-ed25519` 专用凭据和 `git@github.com:wayuwacn/wao.git` 拉取代码，不再依赖易受代理 TLS
中断影响的 HTTPS 检出。

在 GitHub 创建仅限 `wayuwacn/wao` 的细粒度 PAT，权限为 `Contents: read`、
`Commit statuses: read/write`，将令牌直接写入 `/etc/jenkins/secrets/github-pat`，不要粘贴到命令历史或日志。
PAT 只导入 Credential Store，用于 GitHub API 和提交状态回写，不再参与仓库检出。
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

安装脚本只接受本机端口，并为 Git 检出、GitHub 状态回写和 Jenkins 下载设置代理；Linux1、Linux2、
阿里云以及 Jenkins 本机地址均通过 `NO_PROXY` 直连。代理进程必须在 Jenkins 启动和构建期间持续运行。

这里的临时分支只用于合并前影子构建。首次手工构建必须设置 `DEPLOY_PRODUCTION=false`；验证通过后合并 PR，
再从 `main` checkout 重跑安装脚本（不设置 `WAO_JENKINS_SCM_BRANCH`）并确认任务 SCM 已切回 `main`。
生产 Webhook 只能在 SCM 为 `main` 时启用。

Jenkins UI 只监听 `127.0.0.1:8081`，并使用 `/jenkins` context path。日常可在登录 WAO 后从
左下角「链接 → Jenkins」打开 `https://wao.wayuwa.cn/jenkins/`；Nginx 通过内部鉴权子请求传递 WAO 成员 id
和角色组，Jenkins 不再显示第二个登录页。普通成员只读，WAO 管理员和超级管理员保留管理权限。
控制器通过官方简体中文本地化与 Locale 插件统一固定为 `zh_CN`，不依赖浏览器语言；插件没有翻译的专有名词
和历史构建日志仍保留原文。

云入口不可用时，可在自己的电脑建立应急隧道：

```sh
ssh -L 8081:127.0.0.1:8081 wadi@linux2.wayuwa.cn
```

直接打开 `http://127.0.0.1:8081/jenkins/` 不包含受信任身份头，不再作为日常登录方式。故障处理优先恢复
WAO 会话和云 Nginx 链路；`/etc/jenkins/secrets/admin-password` 仅保留为首次认证域迁移回退，不提供给成员，
也不复制到仓库、命令历史或日志。

## GitHub webhook

仓库 Webhook 使用：

```text
Payload URL: https://wao.wayuwa.cn/github-webhook/
Content type: application/json
Secret: /etc/jenkins/secrets/github-webhook-secret 的内容
Events: Just the push event
```

云 Nginx 把这个精确路径改写到 Jenkins 的 `/jenkins/github-webhook/`。同一个 `127.0.0.1:18081` 由 Linux
主节点发起的反向 SSH 隧道提供；其余 `/jenkins/` 请求必须先通过 WAO 会话校验，再经过 Jenkins 自身登录。

首次 Jenkins 手工生产发布会把带 Webhook 路由的新 Nginx 模板原子发布到云节点。随后创建 GitHub Webhook，
使用 GitHub 的 Test delivery 验证签名请求，再执行完整验收：

```sh
sudo sh deploy/jenkins/verify-controller.sh
```

## 流水线

`wao-main-cd` 会先等待 300 秒，把窗口内相邻的 main push 合并成一个批次；已有构建执行时，后续 push 继续由
Jenkins 合并到唯一待执行项，不会中断正在进行的 migration 或滚动发布。`Jenkinsfile` 从 `main` 拉取最新代码，
以线上前端真实版本作为批次基线并生成可审计的变更范围：只运行受影响 Maven 模块和前端的测试，
后端、WAO Web、Agent Web 三路并行；运行时代码变更仍构建完整的四个 SHA 镜像和两个前端，并执行
Linux1 从节点 → Linux2 主节点 → 云端严格滚动发布、线上验收、GitHub commit status 和飞书通知。纯文档提交只完成
输入校验并明确通知“无需部署”；只改自动化测试时仅运行对应模块测试。两者都不生成制品或触碰生产节点。
无法识别的新路径自动回退全量验证和发布。变更规划结果不在 Declarative `environment` 中预声明，避免 Jenkins
阻止运行时规划覆盖默认值；字段仍使用显式白名单赋值，不需要放宽 Groovy 沙箱或人工批准脚本签名。

- `DEPLOY_PRODUCTION=false`：对需要发布的变更执行完整影子构建，不接触生产节点；纯文档仍只做校验。
- `BOOTSTRAP_SLAVE=true`：显式生成 GTID 快照并重建 Linux1 从库；正常发布禁止开启。
- `ALLOW_SLAVE_OFFLINE=true`：仅当 Linux1 在部署前预检不可达时，保持从节点摘流并继续发布 Linux2 与云端；
  Linux1 已通过预检后的任何部署失败仍然 fail-closed。不得与 `BOOTSTRAP_SLAVE` 同时开启。
- 同一任务不并发，新 push 排队且不取消正在进行的 migration 或发布。
- GitHub 状态会写到线上版本之后、本批次头之前的每个提交；失败不会推进线上基线，后继成功批次会把其中已经随同
  上线的祖先提交更新为成功。成功批次不发飞书卡片；失败批次只发一张包含原因与详情入口的卡片。
- GitHub commit status 会对瞬时网络/TLS 错误自动重试；重试耗尽只记录告警，不得把已经通过生产验收的构建
  改判为失败。输入 SHA 或状态值不合法仍立即失败，以便暴露流水线配置错误。
- 失败时 GitHub commit status 会写明失败阶段，方便没有 Jenkins UI 会话的自动任务先定位门禁；完整命令输出仍只
  保留在受 WAO 会话保护的 Jenkins 控制台，不把日志或凭据复制到公开状态描述。
- 生产发布验证结束后，流水线会比较仓库内 JCasC、systemd override 和隧道 unit；有变化时只覆盖这三个
  受版本控制的文件，执行 `daemon-reload`，再请求 Jenkins `safeRestart`。重启会等当前构建和通知结束，
  不会在部署中途杀掉控制器。
- 重启后可用 `curl --head https://wao.wayuwa.cn/jenkins-health` 验证 `/jenkins` context path 已生效；该
  精确路由只接受 HEAD、没有响应正文，不绕过 `/jenkins/` 的 WAO 会话门禁。

## 密钥与恢复

JCasC 只提交非敏感结构；启动时用 `readFile` 从 `/etc/jenkins/secrets` 导入 Credential Store。
每日 02:30 在执行器空闲时停止 Jenkins，备份 `JENKINS_HOME` 与 `/etc/jenkins` 到
`/var/backups/jenkins`，保存 SHA-256 并保留最近 7 份。备份不包含 workspace、Maven/npm 缓存。

恢复时先安装相同 Jenkins LTS 与 `plugins.txt` 中的插件，再恢复归档；`secrets/master.key` 与
`credentials.xml` 必须来自同一份备份。
