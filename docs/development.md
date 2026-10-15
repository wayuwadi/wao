# 独立安装和开发

按根目录 [README](../README.md) 生成私有 `.env`，启动本机依赖、业务服务、网关和浏览器界面。每套安装使用独立数据库、账号和随机密钥，不复制维护者的生产数据或授权。

无 Docker 时可自行安装 MySQL 9、Redis 7 和 Nacos 2.4.3，在 `.env` 中填写本机地址与自己的凭据。MySQL 账号需要独立 schema 的建表和 Flyway 迁移权限。

`python scripts/local-dev.py init` 不覆盖已有配置；`services` 启动 Compose；`server`、`gateway`、`web` 各启动一个前台进程，便于查看日志和正常停止。端口被占用时先查明进程，不停止其他服务。

后端测试使用隔离 H2 或 Testcontainers，不应指向生产。Docker 不可用时 MySQL 合约测试可能跳过，提交说明须如实列出。

页面与保存功能修改覆盖加载、空状态、失败重试、草稿、刷新和并发修改；运行相关测试、类型检查和构建。界面规范见 [设计系统](design/wao-ui-system.md)。

CLI 使用自己的 `WAO_URL` 和令牌，见 [CLI README](../wao-cli/README.md)。外部账号需要在自己的安装中单独配置，不能把演示数据当作接入成功。
