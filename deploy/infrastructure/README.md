# 基础设施采集

在 Linux2 执行 `sudo sh deploy/infrastructure/install.sh`。Jenkins 主节点发布时更新代码，SQLite 与快照位于独立的 `/var/lib/wao-infrastructure`，不会被应用容器滚动发布清除。

采集器和 HTTP 仅监听 `127.0.0.1:18782`。反向 SSH 服务把云端同名 loopback 端口转发回来，Nginx 对公开 `/api/infrastructure/overview` 的每次请求校验 WAO 管理员会话。配置是固定三个目的地的命令数组；接口没有执行命令、修改配置或增加节点的能力。沿用既有受限部署身份及严格主机密钥验证，不复制密钥。

每 60 秒读一次，每台机器最多等待 40 秒。趋势保留 7 天，清单保留最后成功结果，失联、未知和资源缺少读取权限分别显式展示。Docker、systemd 的资源数据可能重叠，不能累加替代主机指标；systemd 的 CPU 由相邻采样计数求差，首次为空。

检查：`systemctl status wao-infrastructure wao-infrastructure-tunnel`、`curl http://127.0.0.1:18782/health`。快照和数据库仅本机受限目录可读。故障时重启采集器即可，不需重启 WAO。停用：停止两个服务并撤除公开路由；保留数据目录可恢复历史。

验证：`python3 -m unittest discover -s deploy/infrastructure -p 'test_*.py'`。
