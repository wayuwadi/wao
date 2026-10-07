# Pet CLI 上传与分享

2026-10-07。用户要求通过 WAO CLI 把自制宠物上传到本人 Pet 仓库并分享。

## 用户结果

CLI 0.3.0 提供 `wao pets list`、`upload --file pet.zip --source 来源 --license ALL-RIGHTS-RESERVED`、`share ID --yes`、`unshare ID`。上传复用现有包检查和作者身份，先私人保存；同作者同规范化包、来源、许可证重试返回原记录，不改变原可见性。公开分享与上传分离，交互输入 yes 或显式 --yes 表示确认分享权及公开下载。分享后匿名读取真实元数据和固定 ZIP，核对规范化包的大小与 SHA-256；失败保留实际公开状态，不能伪报分享完成。

## 授权与 API

能力中心新建逐项连接可独立选择 `list_my_pets`、`upload_pet`、`share_pet`。旧整体令牌和现有快照不新增任何权限，日常预设不自动加入 Pet。Pet 仅归当前成员公开作者，不使用项目或知识库范围，也不暴露设备签发或作者身份管理。

`GET /api/pets/cli-access?operation=...` 接受以上三项，检查工作区 Bearer 且 no-store；CLI 在发出文件前调用。`GET /api/pets/mine`、`POST /api/pets`、`PUT /api/pets/{id}/visibility` 分别执行对应操作检查。浏览器无 Authorization 时仍使用原会话；带无效或无权限 Bearer 时拒绝，不能回落到 Cookie。私有文件下载、其它路径和令牌签发不对设备开放。MCP 保持原目录，不以 JSON 搬运二进制图集。

上传 ZIP 上限 10 MiB。source 为 1–500 字；许可证仅 MIT、Apache-2.0、CC-BY-4.0、CC0-1.0、ALL-RIGHTS-RESERVED。包格式、路径、解压限制、固定存储和公开状态沿用 `phase-1-wao-hub-pets.md`。鉴权先于读取 multipart 内容。控制器与 AuthInterceptor 只放行精确 Pet 方法/路径到独立 Bearer 检查。

## 回执与验证

写请求前本机落盘回执；上传默认标识由工作区地址、输入包摘要、来源和许可证决定。已有回执拒绝再次写入，未知结果先 `pets list` 核对；不自动重试或覆盖。分享回执保存实际 listed、返回卡片和匿名核验结果。凭据请求及匿名下载拒绝重定向，终端不输出令牌，读取下载内容限 10 MiB。

验证覆盖新旧令牌、单项权限、他人所有权、浏览器兼容、权限检查先于文件读取、公开与下架、上传回执、匿名 hash 不符与成功、命令错误退出、能力目录和预设不扩权。最终须通过真实成员上传并公开读取最终包后，才报告可分享。
