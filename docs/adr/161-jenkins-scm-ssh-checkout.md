# ADR-161：Jenkins SCM 使用专用 SSH 凭据检出

## 状态

已接受（2026-08-30）。

## 上下文

Linux2 上的 Jenkins 通过本机代理访问 GitHub。生产发布的流水线定义仍使用 HTTPS SCM；代理链路偶发提前
终止 TLS，导致 Jenkins 甚至无法读取 `Jenkinsfile`，内建重试也只能重复同一个失败入口。仓库策略禁用了
Deploy key，因此控制器需要一把账号级、但只在 Jenkins Credential Store 中供 SCM 检出使用的独立 SSH 密钥。

## 决策

- Jenkins 任务通过 `git@github.com:wayuwacn/wao.git` 和账号中独立登记的 `wao-github-scm-ssh` 凭据检出。
- SSH 私钥只从 `/etc/jenkins/secrets/github-scm-ed25519` 导入，不进入仓库、日志或普通环境变量。
- GitHub 主机公钥由运维核验后存入 `/etc/jenkins/secrets/github-known-hosts`，安装和配置更新时以
  `0600` 复制到 Jenkins 用户的 OpenSSH `known_hosts`；禁止关闭主机密钥校验。
- GitHub PAT 继续只承担 API 与 commit status 回写，不再作为 Git SCM 密码。

## 后果

流水线定义和源码检出不再经过易抖动的 HTTPS 代理 TLS 通道，并且 SSH 凭据、主机身份与 API PAT 各自独立。
首次安装需要把 SCM 公钥加入 GitHub 账号的 Authentication keys，并准备经过核验的 GitHub `known_hosts`。
账号密钥本身不是只读凭据，但 Jenkins 只把它绑定在受信任 `main` 的 SCM 检出，不向流水线步骤暴露。

## 回退

只有在 GitHub SSH 长期不可用且已经建立稳定、可观测的 HTTPS 出站链路后，才可通过新 ADR 恢复 HTTPS SCM；
不得通过关闭 SSH 主机密钥校验来回退。
