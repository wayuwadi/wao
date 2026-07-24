# ADR-006：Gateway 优先路由同机 WAO Server

## 状态

已采纳。

## 上下文

两个应用实例向 Nacos 注册公网地址。家庭路由器若没有开放或不支持公网 8080 回环，同机 Gateway 使用 `lb://wao-server` 时也可能选中无法访问的公网实例，导致 Gateway 进程健康但 `/api/` 返回 500。

## 决策

- Linux 与 Windows Gateway 的 `WAO_SERVER_URI` 均设为 `http://wao-server:8080`，通过各自 Docker 网络访问同机应用。
- 云 Nginx 继续在两个 TLS Gateway 之间负载均衡，并将 `/api/hello` 作为节点全链路健康依据。
- 两个 WAO Server 继续注册 Nacos，保留服务目录、实例观察和未来拆分微服务的能力。
- Nginx 对幂等请求的 500/502/503/504 和连接错误尝试另一个 Gateway。

## 后果

- 家庭节点无需为 Gateway 服务发现依赖公网 8080，减少路由器回环和动态公网地址带来的故障点。
- 单个节点的 Gateway 与 App 作为一个故障域；节点级高可用由云 Nginx 完成。
- 将来拆分跨节点微服务时，需要重新评估 Nacos 注册地址、私网连通和主动健康检查。
