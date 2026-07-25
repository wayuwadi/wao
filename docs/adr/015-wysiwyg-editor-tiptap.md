# ADR-015：描述编辑器改为 Tiptap 所见即所得，存储仍是 Markdown

## 状态

已采纳。修订 [ADR-012](012-markdown-rendering-and-image-store.md) 中「默认预览态、点击进入编辑态」的交互决策；该 ADR 里 marked + DOMPurify 的净化方案继续对**只读渲染**（分享页）有效。

## 上下文

ADR-012 的 `MarkdownEditor` 是「两态」设计：默认渲染预览，点击切成 textarea 编辑源码，失焦切回预览。实际用下来有两个问题：

1. **写的时候看到的是源码，不是结果。** 用户的原话是想要「飞书文档那样的，所见即所得」——敲 `## ` 当场就变标题，而不是先写一堆标记再切过去看效果。两态切换本身也是额外的心智负担：光标停在哪、什么时候会跳回预览。
2. **粘贴的图片进了附件区。** 图被塞进 `task.images` 数组、渲染成描述下方的缩略图网格，而不是出现在用户按下 Ctrl+V 的那个位置。对「先写一段、贴张图、再写一段」这种写法完全不成立。

自研 contenteditable 不是可行选项：中文输入法的合成事件（`compositionstart`/`compositionend`）、选区与光标恢复、撤销栈这几件事都极易出细碎 bug，而且是那种"打中文打到一半光标跳走"的、用户天天遇到的 bug。

## 决策

- 引入 **Tiptap 3**（ProseMirror 内核）+ `@tiptap/markdown`，替换两态编辑器。ProseMirror 是 Notion / 飞书这类编辑器的通用底座，IME、撤销、选区都已经处理好。
- **存储格式不变，仍然是 Markdown。** 组件对外的 `v-model` 收发 Markdown 字符串，内部用 `contentType: 'markdown'` 装载、`editor.getMarkdown()` 序列化。这样 `/api/share/tasks/{id}.md`、AI 直读、以及所有已有描述数据都不受影响，也不需要数据迁移。
- **图片内联**：粘贴 / 拖入 / 工具条插入的图片先上传到图床，拿到 `/api/images/{id}/raw` 后作为 `![]()` 插到当前光标处。组件通过 `upload-image` 事件把 `File` 和一个回调交给父组件，自己不关心存到哪。
- `task.images` / `sub.images` 两个附件数组**保留但不再写入**：历史数据里已有的图片继续在原位置展示，新粘贴的图一律进正文。
- 编辑器**异步加载**（`defineAsyncComponent`）。ProseMirror 这一坨 gzip 约 139KB，只有打开任务详情或新建任务弹窗时才需要，不应该压在首屏包里。

## 后果

- 首屏包实际**变小了**：`index` chunk 从 60KB gzip 降到 35KB，因为编辑器连同 marked/DOMPurify 一起被拆进按需加载的 `MarkdownEditor` chunk（139KB gzip）。代价是第一次打开任务抽屉时要多下载一个 chunk。
- 依赖数量明显增加（`@tiptap/*` 8 个包 + `prosemirror-*` 传递依赖）。这是换取一个正确的中文富文本编辑器的合理代价，自研的等价物既做不到这个质量，维护成本也更高。
- `utils/markdown.ts` 缩减为只保留 `renderMarkdown`（分享页只读渲染仍需要它，且仍需 DOMPurify 净化）；`applyMarkdownCommand` 和 `stripMarkdown` 随两态编辑器一起删除。
- Tiptap 的 Markdown 序列化在极端语法（嵌套表格、脚注、HTML 内联块）上不保证与原文逐字节一致——描述场景用不到这些，但如果以后要把知识库文档也迁过来，需要先验证往返保真度。
- 只读展示（分享页）**没有**换成 Tiptap，仍走 `renderMarkdown` + `v-html`：那里不需要编辑能力，多加载 139KB 不划算。
