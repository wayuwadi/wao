# ADR-002：跨节点通信使用 Tailscale 私网

## 状态

已采纳。

## 决策

主节点、从节点与云服务器通过 Tailscale 地址进行 Gateway、Nacos、MySQL 复制、Redis 复制和 Sentinel 通信。动态公网域名仅用于管理回源和紧急回退。

## 后果

数据服务无需暴露公网；部署前必须盘点三台机器的 Tailscale 地址和防火墙规则。Tailscale 故障时允许临时切换到受防火墙限制的 DDNS 地址。
