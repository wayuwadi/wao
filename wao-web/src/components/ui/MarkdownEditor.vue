<script setup lang="ts">
/**
 * Markdown 描述编辑器 —— 全项目文字描述输入的统一控件。
 *
 * 默认渲染预览态；点击进入编辑态，失焦回到预览。编辑态的 textarea 随内容自动撑高
 * （不再固定 rows），并提供 Markdown 快捷命令工具条。粘贴图片时抛 paste-image
 * 交给父组件决定存到哪（任务图片 / 图床）。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { applyMarkdownCommand, renderMarkdown } from '@/utils/markdown'

const props = withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    /** 编辑态最小高度（px）。 */
    minHeight?: number
    /** 传 true 时常驻编辑态（新建弹窗这类一开始就要写字的场景）。 */
    alwaysEdit?: boolean
    /** 只读预览，不可进入编辑（分享页）。 */
    readonly?: boolean
  }>(),
  { placeholder: '支持 Markdown…', minHeight: 72, alwaysEdit: false, readonly: false },
)
const emit = defineEmits<{
  'update:modelValue': [value: string]
  'paste-image': [file: File]
}>()

const editing = ref(props.alwaysEdit)
const area = ref<HTMLTextAreaElement | null>(null)
const html = computed(() => renderMarkdown(props.modelValue))

/** 随内容重算高度：先归零再取 scrollHeight，否则删字时不会回缩。 */
function autoGrow() {
  const el = area.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.max(props.minHeight, el.scrollHeight) + 'px'
}
watch(() => props.modelValue, () => nextTick(autoGrow))

async function enterEdit(caretToEnd = true) {
  if (props.readonly || editing.value) return
  editing.value = true
  await nextTick()
  autoGrow()
  const el = area.value
  if (el && caretToEnd) {
    el.focus()
    el.setSelectionRange(el.value.length, el.value.length)
  }
}
function leaveEdit() {
  if (!props.alwaysEdit) editing.value = false
}
function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLTextAreaElement).value)
  autoGrow()
}
function cmd(name: Parameters<typeof applyMarkdownCommand>[3]) {
  const el = area.value
  if (!el) return
  const next = applyMarkdownCommand(props.modelValue, el.selectionStart, el.selectionEnd, name)
  emit('update:modelValue', next.text)
  nextTick(() => {
    el.focus()
    el.setSelectionRange(next.start, next.end)
    autoGrow()
  })
}
/** Tab 缩进（列表嵌套），Esc 退出编辑回到预览。 */
function onKeydown(e: KeyboardEvent) {
  const el = area.value
  if (!el) return
  if (e.key === 'Escape' && !props.alwaysEdit) {
    e.stopPropagation()
    el.blur()
    return
  }
  if (e.key === 'Tab') {
    e.preventDefault()
    const at = el.selectionStart
    emit('update:modelValue', props.modelValue.slice(0, at) + '  ' + props.modelValue.slice(el.selectionEnd))
    nextTick(() => el.setSelectionRange(at + 2, at + 2))
  }
}
function onPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  for (const it of items) {
    if (it.kind === 'file') {
      const f = it.getAsFile()
      if (f?.type.startsWith('image/')) {
        emit('paste-image', f)
        e.preventDefault()
      }
    }
  }
}

const TOOLS = [
  { key: 'h2', icon: 'ri-h-2', title: '标题' },
  { key: 'bold', icon: 'ri-bold', title: '粗体' },
  { key: 'italic', icon: 'ri-italic', title: '斜体' },
  { key: 'code', icon: 'ri-code-line', title: '行内代码' },
  { key: 'ul', icon: 'ri-list-unordered', title: '无序列表' },
  { key: 'ol', icon: 'ri-list-ordered', title: '有序列表' },
  { key: 'todo', icon: 'ri-checkbox-line', title: '待办项' },
  { key: 'quote', icon: 'ri-double-quotes-l', title: '引用' },
  { key: 'link', icon: 'ri-link', title: '链接' },
] as const

defineExpose({ enterEdit })
</script>

<template>
  <div class="md">
    <div v-if="editing" class="md-edit">
      <div class="md-tools">
        <button v-for="t in TOOLS" :key="t.key" class="md-tool" :title="t.title" @mousedown.prevent="cmd(t.key)">
          <i :class="t.icon"></i>
        </button>
        <span class="md-spacer"></span>
        <span class="md-hint">Markdown · Esc 预览</span>
      </div>
      <textarea
        ref="area"
        class="md-area"
        :value="modelValue"
        :placeholder="placeholder"
        :style="{ minHeight: minHeight + 'px' }"
        @input="onInput"
        @keydown="onKeydown"
        @paste="onPaste"
        @blur="leaveEdit"
      ></textarea>
    </div>

    <div v-else-if="html" class="md-view" :class="{ ro: readonly }" @click="enterEdit()" v-html="html"></div>
    <div v-else class="md-empty" :class="{ ro: readonly }" @click="enterEdit()">
      {{ readonly ? '暂无描述' : placeholder }}
    </div>
  </div>
</template>

<style scoped>
.md {
  width: 100%;
}
.md-edit {
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  background: #fff;
  overflow: hidden;
}
.md-tools {
  display: flex;
  align-items: center;
  gap: 1px;
  padding: 4px 6px;
  border-bottom: 1px solid var(--wao-divider);
  background: var(--wao-bg-sub-2);
}
.md-spacer {
  flex: 1;
}
.md-hint {
  font-size: 10.5px;
  color: var(--wao-text-faint);
  padding-right: 2px;
}
.md-tool {
  width: 24px;
  height: 24px;
  border: none;
  background: none;
  border-radius: 5px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.md-tool:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-primary);
}
.md-tool i {
  font-size: 14px;
}
.md-area {
  display: block;
  width: 100%;
  border: none;
  outline: none;
  resize: none;
  overflow: hidden;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--wao-text-2);
  background: #fff;
  padding: 8px 10px;
  font-family: inherit;
}
.md-view,
.md-empty {
  border: 1px solid transparent;
  border-radius: 9px;
  padding: 7px 9px;
  margin: 0 -9px;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--wao-text-2);
  cursor: text;
}
.md-view:hover:not(.ro),
.md-empty:hover:not(.ro) {
  background: var(--wao-bg-hover);
}
.md-view.ro,
.md-empty.ro {
  cursor: default;
  margin: 0;
  padding: 0;
}
.md-empty {
  color: var(--wao-text-faint);
}

/* 预览态排版 —— v-html 内容不受 scoped 约束，用 :deep 命中 */
.md-view :deep(> *:first-child) {
  margin-top: 0;
}
.md-view :deep(> *:last-child) {
  margin-bottom: 0;
}
.md-view :deep(p) {
  margin: 0 0 8px;
}
.md-view :deep(h1),
.md-view :deep(h2),
.md-view :deep(h3),
.md-view :deep(h4) {
  margin: 14px 0 6px;
  font-weight: 700;
  color: var(--wao-text);
  line-height: 1.4;
}
.md-view :deep(h1) {
  font-size: 17px;
}
.md-view :deep(h2) {
  font-size: 15px;
}
.md-view :deep(h3) {
  font-size: 14px;
}
.md-view :deep(h4) {
  font-size: 13.5px;
}
.md-view :deep(ul),
.md-view :deep(ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}
.md-view :deep(li) {
  margin: 2px 0;
}
.md-view :deep(li input[type='checkbox']) {
  margin-right: 6px;
  vertical-align: -1px;
}
.md-view :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: var(--wao-divider);
  border-radius: 4px;
  padding: 1px 5px;
}
.md-view :deep(pre) {
  margin: 0 0 8px;
  background: var(--wao-bg-sub);
  border: 1px solid var(--wao-divider);
  border-radius: 8px;
  padding: 10px 12px;
  overflow-x: auto;
}
.md-view :deep(pre code) {
  background: none;
  padding: 0;
  font-size: 12px;
  line-height: 1.7;
}
.md-view :deep(blockquote) {
  margin: 0 0 8px;
  padding: 2px 0 2px 12px;
  border-left: 3px solid var(--wao-line);
  color: var(--wao-text-3);
}
.md-view :deep(a) {
  color: var(--wao-primary-text);
  text-decoration: none;
}
.md-view :deep(a:hover) {
  text-decoration: underline;
}
.md-view :deep(img) {
  max-width: 100%;
  border-radius: 8px;
  display: block;
  margin: 6px 0;
}
.md-view :deep(hr) {
  border: none;
  border-top: 1px solid var(--wao-divider);
  margin: 12px 0;
}
.md-view :deep(table) {
  border-collapse: collapse;
  margin: 0 0 8px;
  font-size: 12.5px;
  display: block;
  overflow-x: auto;
}
.md-view :deep(th),
.md-view :deep(td) {
  border: 1px solid var(--wao-divider);
  padding: 5px 9px;
  text-align: left;
}
.md-view :deep(th) {
  background: var(--wao-bg-sub-2);
  font-weight: 600;
}
</style>
