# ADR-004：CI/CD 通过云服务器跳板访问家庭节点

## 状态：已采纳

## 上下文

GitHub-hosted Runner 的出口地址不固定。若流水线直接 SSH 到家庭节点，就需要把 22 端口暴露给大范围公网来源，与项目的最小暴露原则冲突。云服务器拥有固定公网地址，并与两台家庭节点加入同一 Tailnet。

## 决策

- GitHub Actions 只通过公网 SSH 访问云服务器。
- 主、从节点使用 Tailscale IPv4 作为 `MASTER_HOST`、`SLAVE_HOST`。
- `scp` 和 `ssh` 使用 OpenSSH `ProxyJump` 经云服务器进入 Tailnet。
- GitHub Secret `DEPLOY_SSH_KNOWN_HOSTS` 固定保存云服务器及两个 Tailscale 目标的 SSH 主机公钥，不在流水线运行时执行未验证的 `ssh-keyscan`。
- 同一专用 ED25519 部署公钥授权到三台节点；私钥只进入 GitHub Actions Secret。

## 后果

- 家庭节点无需向 GitHub Runner 开放 SSH，攻击面更小。
- 云服务器不可用时 CD 暂停，但线上服务本身不依赖 CD。
- 节点重装导致 SSH 主机密钥变化时，必须人工核验后更新 `DEPLOY_SSH_KNOWN_HOSTS`。
