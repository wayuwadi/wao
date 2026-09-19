# 本地开发体检

在仓库根目录执行，使用 Python 3 标准库，无须安装第三方 Python 包：

```bash
python3 deploy/scripts/dev-doctor.py --env-file .env.local-cloud --web-url http://127.0.0.1:5174
```

`.env.local-cloud` 是自己已有且被 Git 忽略的私有连接配置；工具不会创建该文件，也不会自动读取生产 `.env`。未指定 `--env-file` 时仅使用当前进程变量。已有进程变量优先于文件；文件中的值只当文字读取，不执行 shell、命令替换或变量展开。含空格和 `#` 的值须加引号。

## 检查结果

| 检查 | 通过说明 | 失败时处理 |
| --- | --- | --- |
| Java | 版本满足根 POM 的最低要求 | 配置已有 JDK 的 `JAVA_HOME`，优先 Java 17；CPU 架构不匹配时换用本机兼容版本 |
| Maven | 3.9+ 且能在当前 Java 环境执行 | 补齐 PATH，或指定 `--maven-bin` |
| Node | 满足 `wao-web/package.json` 的 `engines.node` | 配置符合版本范围的 Node，或指定 `--node-bin` |
| 前端依赖 | 找到 Vite 安装 | 在 `wao-web` 执行 `npm ci`；独立 worktree 单独处理依赖入口 |
| 数据库配置 | 必需变量齐全且不是 `<...>` 占位值 | 提供 `MYSQL_HOST/DB_NAME/DB_USER/DB_PASS`，端口默认 3306 |
| 数据库端口 | TCP 可连接 | 核对既有 SSH 隧道和连接端口；工具不启动隧道 |
| 本地 API | `/actuator/health` 为 UP，且 `/api/hello` 身份为 `wao-server` | 检查本地启动日志、数据库连接和 API 端口 |
| 前端入口 | 返回 WAO HTML | 检查 Vite 端口；页面读取不代表浏览器交互通过 |

默认业务服务为 `http://127.0.0.1:18080`，`--api-url` 可以指定其他本地端口。`--web-url` 可省略，省略时明确显示跳过。HTTP 源只允许 `127.0.0.1`、`localhost` 或 `::1`，不接受账号密码、路径、查询参数或 HTTPS 远程地址；请求不使用代理、不携带 Cookie、不跟随跳转。

数据库端口探测不发送 SQL 或密码，**不能验证数据库身份、账号权限或查询成功**。启动服务、构建、单测和真实用户链路验收仍按项目交付规则分别执行。

## 独立工作区与非默认工具路径

```bash
python3 deploy/scripts/dev-doctor.py \
  --env-file /absolute/path/to/private/.env.local-cloud \
  --java-home /absolute/path/to/jdk-17 \
  --maven-bin /absolute/path/to/apache-maven/bin/mvn \
  --node-bin /absolute/path/to/node \
  --api-url http://127.0.0.1:18080 \
  --web-url http://127.0.0.1:5174
```

传入实际已安装路径；工具只读取版本，不安装或修改配置。`--java-home` 同时供本轮 Maven 版本检查使用，不更改当前终端的环境。

## 离线和机器可读输出

```bash
python3 deploy/scripts/dev-doctor.py --offline --json
python3 -m unittest discover -s deploy/scripts -p test_dev_doctor.py -v
```

`--offline` 跳过全部网络请求，仍报告缺少的本地运行时和连接参数。JSON 包含稳定的 `checks[].id/status/message/action`、`summary` 和 `exitCode`。必需项失败退出 1，检查通过退出 0，参数错误退出 2；跳过项不会伪报通过。单项超时默认 4 秒，可用 `--timeout` 设置 0.1—15 秒。

输出不包含连接变量值、HTTP 正文或子进程错误原文，适合提供给后续开发任务诊断。不要附上私有环境文件。
