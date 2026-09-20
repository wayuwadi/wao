# 三节点私网运行与恢复

Linux1配置：`~/.config/wao-wireguard/`，目录0700、私钥和wg-wao.conf为0600。
工具镜像`wao-wireguard-tools:1`。`interface.sh`对应仓库linux1-mesh-interface.sh。
用户单元wao-private-mesh.service保持接口，wao-private-mesh-endpoint.timer每分钟更新端点。
用户linger保持原启用状态；Docker未就绪时服务每15秒重试。

Linux2/云机：沿用`/etc/wireguard/wg0.conf`，原配置受限备份在
`/etc/wao-node-mesh/wg0.before.conf`；public-topology.json只包含公钥和地址，也留在各机受限目录。
Linux2安装`/usr/local/lib/wao-network/refresh-mesh-endpoint.py`与wao-linux2-mesh-endpoint.timer，
root wg-quick@wg0原本已设为开机启用。保存后的AllowedIPs会在wg-quick启动时恢复指定LAN主机路由。

## 检查

```bash
# Linux1（普通用户）
systemctl --user status wao-private-mesh.service
systemctl --user status wao-private-mesh-endpoint.service
ip route get 192.168.31.180
ping -c 3 10.66.0.2

# Linux2（root）
wg show wg0 latest-handshakes
wg show wg0 endpoints
ip route get 192.168.2.180
systemctl status wao-linux2-mesh-endpoint.service
```

不要把`wg showconf`、私钥、容器完整Env或私有wg配置粘贴到聊天/工单。
握手新鲜度之外，必须从真实应用容器验证目标服务端口及认证。
业务接入前，本阶段只改变网络；现有Redis复制和Sentinel公告仍按原配置运行。

## 回退本阶段

先检查是否有后续业务已依赖私网；有依赖时先按对应业务步骤摘流并恢复其旧连接配置。
Linux1停止地址刷新timer和private-mesh.service，后者只移除wg-wao和两个专用fwmark规则。
Linux2及云机在维护窗口用受限原配置备份恢复wg0配置，再只移除本阶段新增的LAN主机路由。
不得把整个wg0直接关闭，它原来就承载云机/Linux2间的业务连接。
禁止删除其他peer或修改默认路由；恢复前后验证原有10.66.0.1↔10.66.0.2通信。

备份文件包含私钥，实际恢复命令在本机运行并保持文件0600，不输出文件内容。
本次未修改路由器、未扩大数据库或Sentinel的公网端口暴露。
