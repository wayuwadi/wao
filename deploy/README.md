# 部署工具

当前维护的生产构建入口是 `jenkins/windows-build-deploy.ps1`，运行状态和凭据由服务器管理。使用方法、迁移兼容和回退步骤见 [部署说明](../docs/deployment.md)。

仓库中的旧 Linux、Docker 多节点、Runner 和原生客户端脚本仅保留历史源码，不作为当前生产操作入口。不要根据旧脚本创建或删除节点、数据卷或数据库。
