# 数字成员图库

2026-10-07。功能 id：`digital-members`，浏览器入口：`/digital-members`。依据 [ADR-330](../adr/330-digital-member-library-and-image-identity.md)，Demo 与扩展设计见 [技术设计](../plans/digital-characters-design-2026-10-07.md)。

## 本次闭环

当前账号独立管理人物、家人和宠物。姓名、关系、性别、识别特征由用户确认；`unknown` 不通过照片或名字推断。账号基础性别与本人绑定成员同步，修改会推进各自 revision。创建家人档案不创建账号，不自动绑定另一个登录成员。没有上传真人照片时不声称已复刻真人外貌。

成员下有私有参考照片和多个形象（Look）；每个形象可追加不可变版本。候选版本经人工确认后才可用于本次指定或本人默认。追加版本不会替换旧版本或旧小记配图。本人只能绑定人物；每个账号最多一个本人默认。主参考由用户显式选择。归档解绑本人、阻止后续使用，保留历史资产。

图库是主要管理界面。小记总览配图旁仅有一个数字形象设置图标，展开后选择“本人默认 / 本次指定 / 不出现具体人物”、单个已确认成员版本和管理入口。新建小记的本次指定会随保存进入后台自动配图；旧调用者继续使用账号本人默认。默认本人仅在文章涉及本人生活场景时使用；引用或他人经历不得替换成本人。没有默认形象时仍带入已确认的账号基础性别；未知则不猜测。

## 存储和并发

八张新增私有表见 [字段说明](../database/digital-member-library.md)。所有身份均由 `CurrentMember` 取得，不接受请求里的 owner。基础资料、主参考、归档、账号绑定和基础性别保存必须提供 `expectedRevision`；不匹配返回 409。创建成员/绑定/照片/版本/入队/结果保存按拥有者 profile 行锁序列化，跨设备不能覆盖另一个草稿。前端按成员和 subject 保存本机草稿，重新读取保留旧草稿和旧版本；冲突须显式选择账号值或以最新值为基础继续编辑。

照片原件和规范化输入保存在 `digital_asset`，不进入公共图床。仅支持解码后的 PNG、JPEG：原件 ≤10MB，边长 ≤8192，像素 ≤2000 万；先读尺寸再解码。JPEG EXIF 方向转为实际像素，最长边缩至 2048，再保存不含元数据的 PNG。预览仅返回规范化图片，鉴权失败不可读；`Cache-Control: no-store`、`nosniff`。被主参考、版本或历史任务引用的照片不可删除。完成的小记输出仍沿用已有图床和分享机制。

## HTTP 契约

全部返回 `ApiResponse<T>`，原图例外返回鉴权字节；GET 不入队。

| 方法与路径 | 输入 / 结果 |
| --- | --- |
| GET `/api/digital-members` | subjects、binding、profile、capability；仅当前成员 |
| POST `/api/digital-members` | name、kind(human/pet)、relation、gender(male/female/unknown)、traits → Subject |
| GET/PATCH `/api/digital-members/{id}` | PATCH 可改 name/relation/gender/traits/state/mainAssetId，expectedRevision 必填；空字符串清主参考 |
| POST `/api/digital-members/{id}/assets` | multipart file、role(reference/portrait) → Asset |
| GET `/api/digital-assets/{id}/raw` | 当前成员的规范化私有 PNG |
| DELETE `/api/digital-assets/{id}` | 只允许无引用图片 |
| POST `/api/digital-members/{id}/looks` | name、style、assetIds(1–4)、portraitAssetId(可空) → 含候选版本的 Look |
| POST `/api/digital-looks/{id}/versions` | assetIds、portraitAssetId、expectedRevision → 新版本追加后的 Look |
| POST `/api/digital-versions/{id}/confirm` | 幂等确认；返回 Subject |
| GET/PUT `/api/me/digital-member` | subjectId、versionId(可空)、expectedRevision → Binding；均为空解绑 |
| GET/PUT `/api/me/profile` | gender、expectedRevision → Profile |
| GET `/api/assistant/image-capabilities` | 实际主机参考图是否启用、说明 |
| POST `/api/digital-members/{id}/generate` | name、style、assetIds、32位requestId → Generation，异步私有候选 |
| GET `/api/digital-members/{id}/generations/{runId}` | QUEUED/RUNNING/SUCCEEDED/FAILED、message、lookId、versionId |

Subject 含 `assets`、`looks`(含versions)、近100条 `history`；每项 history 关联 runId、versionId、mode、真实运行状态、createdAt。列表不返回图像二进制或内部快照。服务器不按客户端提供的路径读取文件。

`POST /api/assistant/article-images` 追加可选 `identitySelection={mode,subjectId?,versionId?}`。default/省略沿用本人默认；explicit 必须提供所属成员的 confirmed 版本；none 不出现人物或宠物。旧请求字段和回复兼容。任务入队保存主体基础事实、主版本、资产 ID/哈希及载荷摘要。执行时只读取冻结照片；默认形象后来改变不影响排队任务。同 requestId 同客户端输入复用运行；不同输入返回409，不重复调用模型。

## 执行出口与扩展

沿用 ADR-306 的已登录本机 Codex、只读沙箱、独立目录、领取 CAS、超时、恢复和终态保护；不恢复旧 Runner，不新增付费 API 回退。最多4张鉴权图片通过 `--image` 传入，提示词要求实际使用图片工具参考输入。新 `DIGITAL_IMAGE` 目的的结果原子存为私有 candidate；小记 `ARTICLE_IMAGE` 仍存既有公共图床。原始提示词终态清除，身份快照作为私有溯源保留。生成失败不确认、不替换默认。

`wao.ai.local-notes.reference-images-enabled` 默认 false。主机核验实际 CLI 的参考生成后显式开启，并须本机执行器 ready 才显示可用；启用配置不代替调用验收。可用时也不承诺每次人脸完全一致，需要人工确认。原照片还没提供时，本次只验收合成参考图链路。

本次可用维度为参考图片生成、上传形象和版本管理。视频、声音、动作、3D、外部平台模型 ID / 权重不假装已接通。未来以 Subject→Look版本→Capability→ProviderBinding 扩展，按供应商实际的人/非人支持范围验证能力；不把不同平台的训练资产当作可直接互换。

## CLI / MCP

此次只开放浏览器会话 API。旧 workspace token 对文档/项目的授权不包括真实家人照片、基础性别或头像生成消费；不因新增 API 自动扩权，也没有使用 bearer 绕过会话。将来增加终端图库命令时必须先设计单独的私有形象读写/生成 scope、新授权和资产二进制传输，届时同步 CLI 命令及权限测试。

## 验收

检查拥有者隔离、匿名拒绝、伪图拒绝、EXIF方向、不可变版本、确认和跨主体错误、CAS冲突、防丢、归档/删除引用保护、冻结参考哈希、重试同输入与不同输入冲突。真实本地浏览器复用既有 MySQL 和真实合成账号；新的图库表可以采用连接内临时表隔离生产，不能据此声称验证服务重启后持久性。真实图片调用须独立检查候选和小记输出、版本关联与私有图片访问拒绝。生产迁移与上线另核实际版本/入口/资源/ready。

小记新建 `CreateNoteRequest` 和待办 `note-operations` 同步接受可选 identitySelection，新建小记工具栏用同一设置图标。非默认选择按人工保存 cycle 留在 `digital_note_identity`；自动阶段和失败重试取该轮选择，具体参考资产在图片任务入队冻结。编辑已有小记不自动替换已有配图。AI 对话转草稿沿用其既有不自动配图语义，页面明确保存后可手动配图。

2026-10-07 本地真实链路：复用既有 MySQL、真实合成验收账号和本机 Codex CLI 0.162.0-alpha.2。合成几何参考生成私有候选、人工确认、指定版本手动配图均完成；同请求号复用、不同正文409、匿名私有图401、历史版本关联均核验。两篇新建小记保存后自动配图均 DONE：explicit 保留合成成员特征，none 只呈现场景物件；输出已经目视检查。浏览器覆盖双页签资料冲突、草稿恢复、主参考、确认默认、归档解绑与恢复。合成图片不代表真人相似度验收，窄视口不代表实体 iPhone 验收。

发布前另核现有公网执行器版本指针、可执行文件哈希与实际 CLI 0.160.1；使用同一执行封装和合成参考独立生成成功，返回 PNG 已目视确认主体特征。启用参考能力不升级现有公网 CLI 或改变版本指针。

2026-10-10 补充：[小记配图按内容选用本人形象](note-image-contextual-persona-2026-10-10.md)。默认选择先判断正文是否需要本人；无人物分支不读取或传入人物参考图片。本次指定和禁用人物保持原有语义。
