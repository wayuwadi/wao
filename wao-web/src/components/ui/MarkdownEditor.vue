<script setup lang="ts">
/**
 * 所见即所得的描述编辑器 —— 全项目文字描述输入的统一控件。
 *
 * 没有「编辑 / 预览」两态：敲 `## ` 当场变标题、`- ` 当场变列表，看到的就是最终样子
 * （对齐飞书文档的手感）。底层是 Tiptap/ProseMirror，中文输入法的合成事件、撤销栈、
 * 选区都由它处理；对外仍然以 Markdown 收发，落库格式不变（见 ADR-015）。
 *
 * 粘贴 / 拖入图片会上传到图床，并**内联插入到光标处**，不再堆到底部的附件区。
 *
 * 刻意不提供格式工具条：所有格式都能用 Markdown 快捷输入敲出来，一排按钮只是
 * 噪音，去掉后描述区更干净。
 */
import { onBeforeUnmount, watch } from 'vue'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import Placeholder from '@tiptap/extension-placeholder'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { Markdown } from '@tiptap/markdown'

const props = withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    /** 编辑区最小高度（px）。 */
    minHeight?: number
    /** 只读展示，不可编辑。 */
    readonly?: boolean
  }>(),
  { placeholder: '写点什么…支持 Markdown 快捷输入', minHeight: 84, readonly: false },
)
const emit = defineEmits<{
  'update:modelValue': [value: string]
  /** 需要把图片存起来并换回可引用地址；返回 null 表示上传失败。 */
  'upload-image': [file: File, done: (url: string | null, name: string) => void]
}>()

const editor = useEditor({
  content: props.modelValue,
  contentType: 'markdown',
  editable: !props.readonly,
  extensions: [
    StarterKit.configure({
      // 描述场景用不到大标题，限制成 h2–h4，避免一份描述里出现比页面标题还大的字
      heading: { levels: [2, 3, 4] },
      link: { openOnClick: false, HTMLAttributes: { rel: 'noopener noreferrer', target: '_blank' } },
    }),
    Image.configure({ inline: false, HTMLAttributes: { class: 'md-img' } }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Placeholder.configure({ placeholder: () => props.placeholder }),
    Markdown,
  ],
  editorProps: {
    handlePaste: (_view, event) => consumeImages(event.clipboardData),
    handleDrop: (_view, event) => consumeImages((event as DragEvent).dataTransfer),
  },
  onUpdate: ({ editor: e }) => emit('update:modelValue', e.getMarkdown()),
})

/**
 * 从剪贴板 / 拖拽数据里挑出图片交给父组件上传。
 *
 * @return 是否已经接管这次事件（接管后 ProseMirror 不再走默认粘贴逻辑）
 */
function consumeImages(dt: DataTransfer | null | undefined): boolean {
  const files = Array.from(dt?.files ?? []).filter((f) => f.type.startsWith('image/'))
  if (!files.length) return false
  files.forEach(insertImage)
  return true
}

/** 上传一张图片并把它插到当前光标处。 */
function insertImage(file: File) {
  emit('upload-image', file, (url, name) => {
    if (!url || !editor.value) return
    editor.value.chain().focus().setImage({ src: url, alt: name }).run()
  })
}

// 外部改了值（切换任务、撤销等）才回灌；等于自己刚发出的内容时不动，否则光标会跳。
watch(
  () => props.modelValue,
  (next) => {
    const e = editor.value
    if (e && next !== e.getMarkdown()) e.commands.setContent(next, { contentType: 'markdown' })
  },
)
watch(
  () => props.readonly,
  (ro) => editor.value?.setEditable(!ro),
)

onBeforeUnmount(() => editor.value?.destroy())

</script>

<template>
  <div class="md" :class="{ ro: readonly }">
    <EditorContent :editor="editor" class="md-body" :style="{ minHeight: minHeight + 'px' }" />
  </div>
</template>

<style scoped>
.md {
  width: 100%;
  position: relative;
}
.md-body {
  border: 1px solid transparent;
  border-radius: 9px;
  transition: border-color 0.12s;
}
.md:not(.ro) .md-body:hover {
  border-color: var(--wao-divider);
}
.md:not(.ro) .md-body:focus-within {
  border-color: var(--wao-line-control);
  background: #fff;
}

/* ProseMirror 的可编辑区在 :deep 里 —— 正文排版与预览态保持一致 */
.md-body :deep(.tiptap) {
  outline: none;
  padding: 7px 9px;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--wao-text-2);
  min-height: inherit;
}
.md.ro .md-body :deep(.tiptap) {
  padding: 0;
}
.md-body :deep(.tiptap > *:first-child) {
  margin-top: 0;
}
.md-body :deep(.tiptap > *:last-child) {
  margin-bottom: 0;
}
.md-body :deep(p) {
  margin: 0 0 8px;
}
/* 空文档时显示 placeholder */
.md-body :deep(p.is-editor-empty:first-child::before) {
  content: attr(data-placeholder);
  float: left;
  height: 0;
  pointer-events: none;
  color: var(--wao-text-faint);
}
.md-body :deep(h2),
.md-body :deep(h3),
.md-body :deep(h4) {
  margin: 14px 0 6px;
  font-weight: 700;
  color: var(--wao-text);
  line-height: 1.4;
}
.md-body :deep(h2) {
  font-size: 15px;
}
.md-body :deep(h3) {
  font-size: 14px;
}
.md-body :deep(h4) {
  font-size: 13.5px;
}
.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}
.md-body :deep(li) {
  margin: 2px 0;
}
.md-body :deep(li p) {
  margin: 0;
}
.md-body :deep(ul[data-type='taskList']) {
  list-style: none;
  padding-left: 2px;
}
.md-body :deep(ul[data-type='taskList'] li) {
  display: flex;
  align-items: flex-start;
  gap: 7px;
}
.md-body :deep(ul[data-type='taskList'] li > label) {
  margin-top: 3px;
}
.md-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: var(--wao-divider);
  border-radius: 4px;
  padding: 1px 5px;
}
.md-body :deep(pre) {
  margin: 0 0 8px;
  background: var(--wao-bg-sub);
  border: 1px solid var(--wao-divider);
  border-radius: 8px;
  padding: 10px 12px;
  overflow-x: auto;
}
.md-body :deep(pre code) {
  background: none;
  padding: 0;
  font-size: 12px;
  line-height: 1.7;
}
.md-body :deep(blockquote) {
  margin: 0 0 8px;
  padding: 2px 0 2px 12px;
  border-left: 3px solid var(--wao-line);
  color: var(--wao-text-3);
}
.md-body :deep(a) {
  color: var(--wao-primary-text);
  text-decoration: none;
}
.md-body :deep(a:hover) {
  text-decoration: underline;
}
.md-body :deep(img) {
  max-width: 100%;
  border-radius: 8px;
  display: block;
  margin: 8px 0;
}
.md-body :deep(img.ProseMirror-selectednode) {
  outline: 2px solid var(--wao-primary);
}
.md-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--wao-divider);
  margin: 12px 0;
}
</style>
