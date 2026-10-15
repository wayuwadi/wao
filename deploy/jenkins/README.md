# Windows Jenkins 构建

当前入口为 `windows-build-deploy.ps1`，配合 `WindowsBuildInputs.psm1` 校验版本映射与私有迁移资源。

维护者提供独立源码目录和私有运行根目录，先用 `-StageOnly` 验证制品，再交给既有运行协调器发布。此脚本不删除数据库、上传文件或旧制品。

完整配置、备份和回退约定见 [部署说明](../../docs/deployment.md)。旧 Linux 脚本保留历史源码，不作为现行入口。
