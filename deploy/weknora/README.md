# WeKnora 独立运行单元

源码基线：Tencent/WeKnora v0.8.0，commit `1edcd54b43606d9079bb36650efe3f68707a79ea`。
Linux2 数据根目录 `/opt/wao-weknora`；`upstream/` 保存该 tag 的运行配置，`frontend-dist/` 保存使用
`prepare-frontend.py upstream/frontend` 后执行 `npm ci && npm run build -- --base=/weknora/` 的产物。

初始化：`python3 bootstrap.py /opt/wao-weknora`。环境文件仅首次创建，后续不得覆盖。
在 `upstream/` 使用 `docker compose -p wao-weknora -f docker-compose.yml -f ../compose.override.yml pull`，
再使用相同参数执行 `up -d`。前端宿主端口 18781，后端 18780，均仅监听 127.0.0.1。
安装本目录的隧道服务，云端 WAO Nginx 模板负责已登录用户的路由。

首次通过 WAO 菜单进入后建立 WeKnora 账号，在模型管理中配置对话和 Embedding API；OCR、Rerank 按需。
不在 Git、终端日志或前端构建环境中写模型密钥。

本地模型方案见 ADR-282。现有 Linux2 运行单元只承载 WeKnora；Ollama 在用户的 Mac 上运行，
经 SSH 反向隧道和只监听 WeKnora Docker 网关的受限转发接入。Mac 关机、休眠或断网时，
依赖本地模型的新文档向量化和「薇娜拉」问答会暂停；服务恢复后核对失败任务并重试。
本机浏览器管理页为 `http://127.0.0.1:18782/`，只绑定回环地址，可管理 Ollama、隧道及已安装模型。
不得将未认证的 Ollama HTTP 端口直接暴露到局域网或公网。

薇娜拉实际检索「薇娜拉 · 知识库与小记」派生库：它只镜像 WAO 已成功同步的原始知识库与小记，不含重复原文很多的 Codex 工作记录。完整的「WAO 同步知识库」仍保留所有已同步资料及其向量。Mac 登录后及之后每小时，`cn.wayuwa.wao-weknora-repair` LaunchAgent 在本地 Ollama 与受限隧道可用时重试失败的嵌入任务、按来源标识和正文指纹幂等更新派生库，并核对薇娜拉的检索范围。临时操作密钥在 Linux2 生成并于调用结束吊销；本机不保存 WeKnora 密钥。宿主机专用的脚本和 LaunchAgent 配置放在本机 `weknora-local/` 与 `~/Library/Application Support/WAO-WeKnora/`，不进入仓库。

升级先备份 PostgreSQL（pg_dump）、data-files 和 redis-data 卷及整个私有配置目录（含 AES/JWT 密钥）。
停止写入后取得一致备份；通过加密 SSH 传输到云端受限备份目录（文件 600，目录 700）。恢复在相同版本空环境中导入数据库、还原文件卷和私有配置，再启动。当前备份边界见 [Linux2 单节点异地备份手册](../../docs/runbooks/linux2-only-cloud-backup.md)。
已迁移数据库的回退必须恢复配套备份，不仅是切回镜像。WAO 日常 CD 不自动升级这个运行单元。
# WAO 内容同步运维补充

管理入口为 `/apps/weknora?tab=sync`。同步在 WAO 应用中执行，通过 MySQL 命名锁跨节点串行，通过受限机器 API 操作 Linux2 的单个 WeKnora。

先在 WeKnora 创建独立 Wiki 库（可同时启用向量索引），再在 Linux2 以 root 运行 `provision-sync-key.py --kb-id UUID --tenant-id ID --owner-id USER_UUID`。凭据仅授予该库读取和写入，明文留在 root 私有文件 `/opt/wao-weknora/upstream/weknora-sync.private.json`，不可提交 Git。上线 migration 后运行 `configure-wao-sync.py --owner-member-id MEMBER --modules docs,tasks,...` 首次初始化；已有配置不会被覆盖。既有库启用向量索引后，按 WeKnora 官方重解析 API 为原有文档建立向量，并核对实际向量维度与问答来源；仅切换模型配置不会回填旧文档。

默认每天北京时间 02:00，每轮最多 20 篇变化内容；实际计划时间以 WAO 内容同步页面为准。首次历史资料会分多轮，手动按钮可继续下一轮。传输成功、向量索引及 Wiki 异步生成分别验收。首次创建结果不确定时先核对远端，不盲目重复 POST；uncertain 记录需结合远端和服务日志核对处理。

撤销时先在 WAO 暂停夜间计划，再在 WeKnora API 集成中撤销「WAO 内容同步」Key。不会删除源数据和手工知识文档。已有映射时不能直接改绑知识库。
