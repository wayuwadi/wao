# ADR-203：JumpServer 从节点使用既有反向 SSH 隧道

日期：2026-09-06。状态：采纳。补充 ADR-143、ADR-158。

## 问题

Jenkins #274 在 Linux1、Linux2 更新成功后，JumpServer 专用密钥检查仍连接
`linux1.wayuwa.cn:22`，因连接被拒绝阻断云前端更新。发布本身已按 ADR-158 使用
Linux2 的 `127.0.0.1:2201`，但资产注册与验收丢失了这个传输地址和端口。

## 决策

- 主发布将实际 `SLAVE_SSH_HOST/PORT` 传入 JumpServer 资产配置。非 loopback 目标保持直接 SSH；
  loopback 目标通过 Linux2 SSH 网关转发，从容器访问 Linux2 上已有的反向隧道。
- 使用固定版本 v4.10.19 的原生 `Zone`、`Gateway`、`Protocol` 模型。网关地址是既有 Linux2，
  使用 JumpServer 原有专用维护密钥，不使用 Jenkins 私钥，不开放新的公网或 Docker 监听。
- 仅从节点归属此专用网络区域；三项用户资产与娃哥、娃弟授权保持原范围，网关不加入资产授权。
  切回直连时清除从节点区域并停用自动维护的网关，不删除审计记录。
- 主机侧检查按配置端口连接三项资产，保留已有主机密钥校验。随后在 JumpServer Core 内按已保存的
  资产、账户、网关建立真实 SSH 连接；容器检查只接受主机侧已校验的主机密钥。
  任何账户、路由、主机密钥、转发或远程命令失败都阻断发布，不能跳过验收继续云部署。

## 验收

本地覆盖直接连接、非默认端口、网关转发、密码账户拒绝、网关失败、目标密钥失败、远程退出失败，
以及 shell 探针端口传递和失败传播。线上要求同批完成三节点部署、JumpServer 持久化路由连接、
SSO、线上版本和今日节奏页面操作验收。

模型依据（固定版本官方源码）：
[Zone](https://github.com/jumpserver/jumpserver/blob/v4.10.19/apps/assets/models/zone.py)、
[Gateway](https://github.com/jumpserver/jumpserver/blob/v4.10.19/apps/assets/models/gateway.py)、
[Asset.gateway](https://github.com/jumpserver/jumpserver/blob/v4.10.19/apps/assets/models/asset/common.py)。
