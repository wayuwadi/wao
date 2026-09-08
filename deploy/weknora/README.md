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

升级先备份 PostgreSQL（pg_dump）、data-files 和 redis-data 卷及整个私有配置目录（含 AES/JWT 密钥）。
停止写入后取得一致备份；通过加密 SSH 传输到 Linux1 的权限受限备份目录（文件 600，目录 700）。恢复在相同版本空环境中导入数据库、还原文件卷和私有配置，再启动。
已迁移数据库的回退必须恢复配套备份，不仅是切回镜像。WAO 日常 CD 不自动升级这个运行单元。
# WAO 内容同步运维补充

管理入口为 `/apps/weknora?tab=sync`。同步在 WAO 应用中执行，通过 MySQL 命名锁跨节点串行，通过受限机器 API 操作 Linux2 的单个 WeKnora。

先在 WeKnora 创建独立 Wiki-only 库，再在 Linux2 以 root 运行 `provision-sync-key.py --kb-id UUID --tenant-id ID --owner-id USER_UUID`。凭据仅授予该库读取和写入，明文留在 root 私有文件 `/opt/wao-weknora/upstream/weknora-sync.private.json`，不可提交 Git。上线 migration 后运行 `configure-wao-sync.py --owner-member-id MEMBER --modules docs,tasks,...` 首次初始化；已有配置不会被覆盖。

默认每天北京时间 02:00，每轮最多 20 篇变化内容。首次历史资料会分多轮，手动按钮可继续下一轮。传输成功与 Wiki 异步生成分别验收。首次创建结果不确定时先核对远端，不盲目重复 POST；uncertain 记录需结合远端和服务日志核对处理。

撤销时先在 WAO 暂停夜间计划，再在 WeKnora API 集成中撤销「WAO 内容同步」Key。不会删除源数据和手工知识文档。已有映射时不能直接改绑知识库。
