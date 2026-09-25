# WAO 私有对象存储上线顺序

决策与回退条件见 [ADR-283](../../docs/adr/283-s3-object-storage-and-image-variants.md)。所有操作使用现有 Linux1 / Linux2 / 云节点的 WireGuard `10.66.0.3` / `.2` / `.1`，对象 API 和集群 RPC 仅绑定该网卡，不对公网开放。固定 Garage `v2.4.1`，三节点各分配不超过 20 GiB 容量；云节点目前是最小磁盘。因 Linux2 无法访问 Docker Hub，从 [Garage 官方二进制发布页](https://garagehq.deuxfleurs.fr/_releases.html) 获取同一版本的 `linux/amd64` 静态二进制，核对三台 SHA-256 相同后打包为本地 `wao-garage:v2.4.1-local` 镜像。`garage.toml.example` 是格式参考，不保存真实密钥。

1. 在三台机器的持久磁盘上准备 `GARAGE_ROOT` 下的 `meta`、`data` 和权限 0600 的 `garage.toml`。生成一次共享 RPC 密钥并安全分发；每台配置自己的 WireGuard 地址。Linux1/2 将静态二进制封装为本地 Docker 镜像，再用 `GARAGE_ROOT=... docker compose -f garage-compose.yml up -d` 启动，容器内数据目录为 `/var/lib/garage`；云节点没有 Docker，使用同一二进制和 `wao-garage.service` 以专用非 root 用户启动，云节点的配置需把 `metadata_dir`、`data_dir` 指向 `/opt/wao-garage/meta`、`/opt/wao-garage/data`。`garage status` 应显示三个健康节点。
2. 只从 WireGuard 网络互联节点，按官方 CLI `garage node connect` 建立互信，给三节点分配独立 zone 和各 20 GiB 容量，复核 `garage layout show` 后 `garage layout apply`。创建私有 `wao-assets` bucket 和仅对它有读写权限的应用 key。不要开启匿名访问。备份密钥与应用密钥分开。
3. 先做对象写入、跨节点读回、SHA-256 核对、单节点离线读回与恢复测试。记录 S3 服务恢复时间；单节点失效不应丢失已确认写入的对象。异地备份按 S3 API 导出完整对象字节和清单；灾难恢复时在新集群重建私有 bucket、访问密钥与布局，再导入对象，不依赖旧集群元数据目录。
4. 先发布服务端兼容代码和 V166 migration，保持 `WAO_STORAGE_MODE=mysql`。确认两个节点都运行兼容代码后，在两节点私有环境中设置同一 bucket、key、region，分别把 endpoint 指向本机 WireGuard `http://10.66.0.2:3900` 与 `http://10.66.0.3:3900`。先设 `WAO_STORAGE_MODE=s3`、`WAO_STORAGE_RETAIN_MYSQL=true`，双写观察与回退通过后才可改为 `false`。旧 URL、权限和一次性下载票据必须逐项验证。
5. 在 Linux2 使用专用 venv 安装 `requirements.txt`（部署复制为 `/usr/local/lib/wao-backup/storage-requirements.txt`）：`python3 -m venv /usr/local/lib/wao-backup/.venv`，再用该 venv 的 `pip install -r ...`。在私有运行环境中设置 `MYSQL_WRITER_HOST`、`MYSQL_WRITER_PORT` 指向当前 WAO 权威写主；Linux2 的 `MYSQL_HOST` 是原生返回副本，不能作为回填写入目标。`migrate_assets.py` 默认只读列出待迁移数；传 `--apply` 才复制对象并 CAS 标记，且会拒绝只读副本。每件均在回读后核对长度和 SHA-256。中断后直接重跑。`--verify-only` 全量检查对象与仍保留的 MySQL 原件一致。出现失败行不执行清理。
6. S3 模式的 Linux2 加密离线备份先验证 `wao-return` 复制追平，再把私有 bucket 中的全部对象和 SHA-256 清单一同归档；缺少 venv 或复制落后会使备份明确失败。可读 WebDAV 导出在 MySQL 正文清理后从私有 bucket 读取并重新校验摘要。必须做一次对象备份的离线恢复演练。
7. **不要直接把 `content` 清空。** 恢复备份和可读导出改造必须先实际通过，完成异地恢复演练，并取得生产数据清理授权；同时确认 Linux2 的 MySQL 复制恢复正常。清理须逐批、有快照、有回退、每批重跑全量对象检查和真实 URL 验收。

Garage 官方部署说明：[三节点集群](https://garagehq.deuxfleurs.fr/documentation/cookbook/real-world/)。
