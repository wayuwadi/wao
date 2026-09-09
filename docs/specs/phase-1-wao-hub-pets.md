# WAO-HUB 与宠物仓库

任务来源：https://wao.wayuwa.cn/api/share/tasks/t1788926199278.md?openOnly=true

## 用户结果
- `/hub` 为可扩展仓库入口，目前包含 Skill 与 Pet；原 `/skills` 与 `/wao/skills` 继续工作。
- `/pets` 游客浏览、搜索和打开公开详情；登录成员复用 Skill 公开作者身份，在 `/pets/create` 上传 ZIP，或填写名称、标识、描述并上传自己制作的完整图集来创建宠物。
- 创建先保存私人包，作者明确确认拥有分享权限后公开；私人和下架资源的详情、图片和下载均不可匿名读取。包不可变；再次上传为独立条目，不覆盖他人的固定下载链接。
- `/pets/:id` 提供真实图集动画预览、来源、授权、SHA-256、下载链接、可复制给 Codex 的安装指令和手动教程。安装写入用户自己的 `CODEX_HOME/pets/<id>`，已有目录不得静默覆盖。
- 创建草稿在当前账号浏览器 IndexedDB 保存（含文件）；加载/保存/失败重试/空列表明确呈现；刷新不覆盖编辑内容。

## 包契约与边界
参考开源实现 https://github.com/astandrik/codex-pets ，只参考格式和机制，不引入其服务、素材或代码。尚无官方文档支持所有 Codex 客户端，界面明确以支持自定义宠物的桌面客户端为适用范围。

ZIP 可含一层根目录；仅允许 pet.json、spritesheet.png 或 spritesheet.webp，以及可选 README.md、LICENSE。拒绝路径穿越、符号链接、加密、重复路径、未知文件、超量文件及解压炸弹。包最大 10 MiB，解压最大 12 MiB。pet.json 只保留已验证字段；id 为小写英文数字连字符，displayName/description 有长度限制。v1 为 1536×1872，v2 为 1536×2288；均为 8 列、192×208 单元。PNG 验证可解码，WebP 验证 RIFF/chunk 和尺寸，不执行包内任何内容。

包、图集与元数据保存在共享 MySQL，沿用 ADR-094 的共享不可变包原则，避免滚动发布时节点本地文件不一致。公开作者复用 skill_publisher，不公开内部成员 id。每次发布须说明素材来源、授权并确认拥有分享权，不自动把项目内第三方张三丰素材上架。创作指由真实图集制作可安装包；不提供没有真实图像服务支撑的 AI 生成占位。

## API
- GET `/api/public/pets?keyword=&page=`：公开列表，每页 24 项。
- GET `/api/public/pets/{id}`、`/{id}/sprite`、`/{id}/download`：仅公开包。
- GET `/api/pets/mine`：当前作者的私人、公开和下架包。
- POST `/api/pets`：multipart 的 archive，或 manifest + sprite；source、license 必填，返回私人包。
- PUT `/api/pets/{id}/visibility`：JSON `{listed, rightsConfirmed}`，作者操作；公开须确认权限。
- GET `/api/pets/{id}/sprite`、`/{id}/download`：作者预览与下载。
JSON 响应使用 ApiResponse；二进制返回正确类型、附件名、nosniff 和 SHA-256。下载固定 UUID 与摘要，公开状态变更不改变内容。
