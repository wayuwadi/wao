# ADR-099：主站交付迁移到 Linux 自建 Jenkins

## 状态

已采纳。主站 `main` 的测试、打包和生产发布不再使用 GitHub 托管 Runner；桌面端和 iOS 原生构建不在本次迁移范围。

## 背景

私有仓库的 GitHub Actions 月度额度已经成为持续发布瓶颈，而 Linux 主节点有 20 核、32GB 内存，具备承载
小团队 Jenkins 控制器和单并发构建的容量。应用仍保存在 GitHub，生产交付需要继续遵守 ADR-097 的双节点
严格成功和滚动摘流约束。

## 决策

- Jenkins LTS 原生安装在 Linux 主节点，使用 Java 21、一个 executor 和受信任的 `main` Pipeline；不执行 fork/PR 中的生产代码。
- GitHub push 通过带 HMAC secret 的 `/github-webhook/` 触发。Jenkins 只监听 Linux loopback；阿里云 Nginx
  通过 Linux 主动建立的反向 SSH 隧道转发这一条精确路径，不公开 Jenkins UI。
- Maven 与 Node 工具链使用固定容器，四个应用镜像只构建一次并以完整 Git SHA 标记。发布顺序保持
  Windows → Linux → 云端，任一节点失败都不能伪装成整体成功。
- Windows 与云节点使用新的 Jenkins 专用 SSH 密钥和独立 `known_hosts`。GitHub PAT 仅限本仓库内容读取和
  commit status 写入；生产环境、证书与飞书配置继续取自 Linux 主节点的私有部署目录。
- Jenkinsfile、JCasC、插件清单、安装、备份和验收脚本进入仓库；真实 PAT、Webhook secret、SSH 私钥、
  known_hosts 和管理员密码只保存在 `/etc/jenkins/secrets`。
- Jenkins 每日仅在 executor 空闲时做 Linux 本地备份，保留七份。该选择不承诺 Linux 物理磁盘损坏后的异机恢复。

## 后果

主站每次 `main` push 不再消耗 GitHub Actions 分钟，GitHub 继续承担代码托管、Webhook 和提交状态展示。
Jenkins 与生产 Docker 共用主机意味着受信任的 `main` 对该主机具有高权限，因此分支写权限、Jenkins UI 和
凭据范围必须保持最小化。macOS 安装包仍需 macOS 原生环境，待后续增加专用构建节点后再迁移。
