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
