# ADR-012：描述富文本用 marked + DOMPurify；图床图片存主节点 MySQL

## 状态

已采纳。

## 上下文

任务模块的两处需求同时触到了「技术栈未经 ADR 不可更改」这条红线：

1. **所有文字描述要支持 Markdown，且录入后展示预览态而不是源码态。** 项目里原有的 Markdown 能力只有 `wao-web/src/utils/doc.ts`，它是个把文档块数组和简化 Markdown 互转的小函数，只认 `## `、`- `、`> `、`!> ` 四种前缀，不支持粗体、行内代码、代码块、表格、任务列表、链接、图片，也没有任何 HTML 净化。任务描述是用户自由输入的内容，一旦要 `v-html` 渲染，缺少净化就等于开了一个 XSS 入口。

2. **子任务的图片要「上传到我的图床」。** 此前 `/api/images` 是只读接口，返回 `image_item` 表的种子演示数据；任务和子任务的图片都以 `data:` URL 内嵌在 `task.detail_json` 里。3MB 的截图转成 base64 约 4MB，直接把任务记录撑成巨无霸，列表接口一次要拉回全部任务，代价会随使用线性恶化。

## 决策

### Markdown

- 前端新增两个依赖：`marked`（解析）与 `dompurify`（净化），封装在 `wao-web/src/utils/markdown.ts` 里，组件不直接调用二者。
- 净化采用**标签白名单**（段落、标题、列表、表格、代码、链接、图片等），`script` / `iframe` / `form` / 事件属性一律剥除；外链统一补 `target="_blank"` + `rel="noopener noreferrer"`。
- 新增 `MarkdownEditor.vue` 作为全项目文字描述的统一控件：默认渲染预览态，点击进入编辑态，失焦回预览；编辑态的 textarea 随内容自动撑高，并带 Markdown 快捷命令工具条。
- 不自研渲染器：手写 Markdown 解析器要么覆盖不全（用户写了表格却渲染不出来），要么就是在重新实现一个 marked，而净化逻辑自研的风险远高于收益。

### 图床

- 新增 `image_blob` 表（V11 migration）存图片正文与元数据，`POST /api/images` 上传、`GET /api/images/{id}/raw` 直出、`DELETE /api/images/{id}` 删除；任务与子任务里只存 `/api/images/{id}/raw` 这个 URL。
- 图片落 **MySQL**，不落容器本地目录。主从两个节点的 `wao-server` 都连主节点的 MySQL（见 `deploy/docker-compose-slave.yml` 的 `MYSQL_HOST`），所以落库天然满足「图床放在主节点上」，同时避免了本地目录方案必须附带的两件事：给两个 Compose 各挂一个 volume，以及在云 Nginx 上把 `/api/images/**` 定向到主节点（否则上传打到从节点、读取打到主节点就 404）。
- 单张基础设施硬上限 10MB，在 `ImageService.MAX_BYTES`、`spring.servlet.multipart.max-file-size`、Nginx 的 `client_max_body_size 12m`（留 2MB 给 multipart 边界与其余表单字段）三处保持一致；ADR-131 允许用户主动上传设置进一步调低。网关补 `spring.codec.max-in-memory-size: 16MB`，避免默认 256KB 缓冲上限截断转发。
- 后端不可用时（离线演示态）前端回落成 `data:` URL，界面表现一致，只是不持久化。

## 后果

- 前端多了两个运行时依赖。marked 与 dompurify 都是长期维护、体积可控的库；DOMPurify 是浏览器端 HTML 净化的事实标准，自研替代物不是一个划算的选择。
- `utils/doc.ts` 暂时保留：知识库文档仍走「块数组 ↔ 简化 Markdown」那条路，本次不动它。文档编辑器迁移到 `MarkdownEditor` 是后续独立的一步，届时 `doc.ts` 才能退休。
- 图片正文进了 MySQL，备份体积会随图片增长。当前是个人 / 小团队规模，可接受；如果后续图床体量明显增大，应迁到对象存储并让 `GET /api/images/{id}/raw` 转成 302 重定向——届时前端存的 URL 不用改，这是选它作为唯一寻址方式的原因。
- 历史任务里已有的 `data:` URL 图片继续照常显示，不做数据迁移。
