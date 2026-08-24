# ADR-106：节点滚动发布前排空旧 Nginx worker

## 状态

已接受（2026-08-24）。

## 背景

严格双节点发布会通过重新渲染 upstream 配置依次摘除 Windows 从节点和 Linux 主节点。Nginx 的优雅重载会允许旧 worker 继续处理已建立的 HTTP/2 和 WebSocket 长连接；旧 worker 也继续使用重载前的 upstream 状态。

2026-08-24 的两次真实严格发布中，云端旧 worker 在发布五分钟后仍处于 `shutting down`，并持续把长连接请求按旧配置送到正在更新的 Linux 主节点，造成短暂 5xx。仅检查新连接或 `nginx -t` 无法发现该问题。

## 决策

1. upstream 状态脚本在重载前精确记录当前 master 下的 worker PID。
2. 重载后最多等待 10 秒让这批旧 worker 自然退出；超时时仅对已记录且仍属于同一 master 的旧 worker 发送 `TERM`。
3. 只有旧 worker 已退出，摘流调用才返回成功；无法退出时发布失败关闭，不继续停止节点。
4. 强制退出不重启 Nginx master，不影响新 worker 和当前健康节点；长连接由客户端重连到新 worker。

## 后果

节点更新不再与携带旧路由的 Nginx worker 重叠，公网请求只会进入未被摘除的健康节点。代价是每次切流最多增加 10 秒等待，极长的 HTTP/2 或 WebSocket 连接可能发生一次重连，但不会把新请求继续送往即将停止的节点。
