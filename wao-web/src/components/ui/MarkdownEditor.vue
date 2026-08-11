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
 *
 * 传入 `peers` 时会在左侧落一条协作者轨道：每个人的头像贴在他光标所在的那一行，
 * 正在输入的还会给那一行加一道颜色条（见 ADR-017）。不传就是普通编辑器，行为不变。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import Placeholder from '@tiptap/extension-placeholder'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { Markdown } from '@tiptap/markdown'

/** 协作者标记：把某个人钉在他光标所在的那一行（顶层块序号）。 */
export interface EditorPeer {
  id: string
  name: string
  initial: string
  color: string
  line: number
  editing: boolean
}

const props = withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    /** 编辑区最小高度（px）。 */
    minHeight?: number
    /** 只读展示，不可编辑。 */
    readonly?: boolean
    /** 文档正文等沉浸场景不显示输入框边框与底色。 */
    bare?: boolean
    /** 此刻也在这篇文档里的人；空数组即不显示协作轨道。 */
    peers?: EditorPeer[]
  }>(),
  { placeholder: '写点什么…支持 Markdown 快捷输入', minHeight: 84, readonly: false, bare: false, peers: () => [] },
)
const emit = defineEmits<{
  'update:modelValue': [value: string]
  /** 需要把图片存起来并换回可引用地址；返回 null 表示上传失败。 */
  'upload-image': [file: File, done: (url: string | null, name: string) => void]
  /** 自己的光标落在第几个顶层块上（-1 = 没有落点），供上报协同在场。 */
  'caret-line': [line: number]
}>()

const previewImage = ref<{ src: string; alt: string } | null>(null)

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
    handleClick: (_view, _pos, event) => {
      const target = event.target
      if (target instanceof HTMLImageElement) {
        previewImage.value = { src: target.currentSrc || target.src, alt: target.alt }
        return true
      }
      return false
    },
  },
  onUpdate: ({ editor: e }) => {
    emit('update:modelValue', e.getMarkdown())
    scheduleMeasure()
  },
  onSelectionUpdate: ({ editor: e }) => emit('caret-line', caretLine(e)),
  onBlur: () => flushExternal(),
})

// ---- 协作者行标记 ----

const shell = ref<HTMLElement | null>(null)
const imagePicker = ref<HTMLInputElement | null>(null)
/** 协作者头像在编辑区里的纵向位置（顶层块序号 → px）。 */
const lineTops = ref<Record<number, number>>({})

/** 光标所在的顶层块序号；取不到时给 -1（只在查看）。 */
function caretLine(e: { state: { selection: { from: number }; doc: { resolve: (pos: number) => { index: (depth: number) => number } } } }): number {
  try {
    return e.state.doc.resolve(e.state.selection.from).index(0)
  } catch {
    return -1
  }
}

/**
 * 量一遍每个协作者所在行在编辑区里的 y 坐标。
 *
 * <p>只量协作者真正落在的那几行 —— 文档几百行时挨个量 DOM 会明显掉帧，而同时在线的人
 * 通常只有个位数。
 */
function measure() {
  const view = editor.value?.view
  const doc = editor.value?.state.doc
  if (!view || !doc) return
  const wanted = [...new Set(props.peers.map((p) => p.line))].filter((l) => l >= 0 && l < doc.childCount)
  const next: Record<number, number> = {}
  for (const line of wanted) {
    let pos = 0
    for (let i = 0; i < line; i++) pos += doc.child(i).nodeSize
    const dom = view.nodeDOM(pos)
    if (dom instanceof HTMLElement) next[line] = dom.offsetTop
  }
  lineTops.value = next
}
function scheduleMeasure() {
  nextTick(measure)
}

/** 有位置可画的协作者标记（同一行上多个人依次错开，像飞书那样叠着放）。 */
const peerMarks = computed(() => {
  const perLine: Record<number, number> = {}
  return props.peers
    .filter((p) => lineTops.value[p.line] != null)
    .map((p) => {
      const seen = perLine[p.line] ?? 0
      perLine[p.line] = seen + 1
      return { ...p, top: lineTops.value[p.line]!, offset: seen * 14 }
    })
})

watch(() => props.peers, scheduleMeasure, { deep: true })
onMounted(() => {
  scheduleMeasure()
  window.addEventListener('resize', scheduleMeasure)
})

/**
 * 从剪贴板 / 拖拽数据里挑出图片交给父组件上传。
 *
 * @return 是否已经接管这次事件（接管后 ProseMirror 不再走默认粘贴逻辑）
 */
function consumeImages(dt: DataTransfer | null | undefined): boolean {
  const files = Array.from(dt?.files ?? []).filter((f) => f.type.startsWith('image/'))
  if (!files.length) return false
  insertImages(files)
  return true
}

type UploadedImage = { url: string; name: string }

/**
 * 同一批图片并行上传，但等整批完成后按剪贴板 / 选择器里的原顺序插入。
 *
 * <p>上传耗时不稳定，逐张回调就插入会让后完成的第一张跑到最后；批次之间再用一条
 * Promise 队列串起来，连续粘贴两批图片时也不会互相穿插。
 */
let imageInsertionQueue = Promise.resolve()
function insertImages(files: File[]) {
  const uploaded = Promise.all(files.map((file) => new Promise<UploadedImage | null>((resolve) => {
    emit('upload-image', file, (url, name) => resolve(url ? { url, name } : null))
  })))
  imageInsertionQueue = imageInsertionQueue.then(async () => {
    const images = (await uploaded).filter((item): item is UploadedImage => item != null)
    const currentEditor = editor.value
    if (!images.length || !currentEditor) return
    currentEditor.chain().focus().insertContent(images.map((image) => ({
      type: 'image',
      attrs: { src: image.url, alt: image.name },
    }))).run()
  })
}

/** 打开系统图片选择器；不设置 capture，以便手机优先提供图册/文件选择。 */
function chooseImage() {
  imagePicker.value?.click()
}

/** 选择后的图片复用粘贴、拖入时的真实图床上传与内联插入链路。 */
function onImagePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? []).filter((file) => file.type.startsWith('image/'))
  // 清空值，允许连续选择同一张图片。
  input.value = ''
  if (files.length) insertImages(files)
}

/**
 * 外部值回灌 —— 只在编辑器没有焦点时做。
 *
 * <p>等于自己刚发出的内容时不动，否则光标会跳。**正在输入时也一律不灌**：语音听写会持续
 * 往同一个位置追加文本，中途被 setContent 换掉整篇内容，听写引擎会把这一段从头再念一遍，
 * 结果就是内容重复。错过的外部更新记在 {@link pendingExternal}，等失焦再补上。
 */
let pendingExternal: string | null = null
function applyExternal(next: string) {
  const e = editor.value
  if (!e || next === e.getMarkdown()) return
  if (e.isFocused) {
    pendingExternal = next
    return
  }
  pendingExternal = null
  e.commands.setContent(next, { contentType: 'markdown' })
}
/** 失焦时把输入期间错过的外部更新补灌回来。 */
function flushExternal() {
  const next = pendingExternal
  pendingExternal = null
  if (next != null && editor.value && next !== editor.value.getMarkdown()) {
    editor.value.commands.setContent(next, { contentType: 'markdown' })
  }
}
watch(() => props.modelValue, applyExternal)
watch(
  () => props.readonly,
  (ro) => editor.value?.setEditable(!ro),
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', scheduleMeasure)
  editor.value?.destroy()
})

</script>

<template>
  <div ref="shell" class="md" :class="{ ro: readonly, collab: peers.length > 0, bare }">
    <input ref="imagePicker" class="image-picker" type="file" accept="image/*" multiple tabindex="-1" aria-hidden="true" @change="onImagePicked" />
    <div v-if="peerMarks.length" class="peer-track" aria-hidden="true">
      <template v-for="m in peerMarks" :key="m.id">
        <span v-if="m.editing" class="peer-line" :style="{ top: m.top + 'px', background: m.color }"></span>
        <span
          class="peer-av"
          :class="{ typing: m.editing }"
          :style="{ top: m.top + 'px', left: m.offset + 'px', background: m.color }"
          :title="`${m.name} ${m.editing ? '正在编辑这一行' : '在这一行'}`"
        >{{ m.initial }}</span>
      </template>
    </div>
    <div class="md-editor-wrap">
      <EditorContent :editor="editor" class="md-body" :style="{ minHeight: minHeight + 'px' }" />
      <button v-if="!readonly" type="button" class="image-upload" title="从图册选择图片" aria-label="从图册选择图片" @mousedown.prevent @click="chooseImage">
        <i class="ri-image-add-line"></i><span>图片</span>
      </button>
    </div>
    <div v-if="previewImage" class="image-lightbox" role="dialog" aria-modal="true" :aria-label="previewImage.alt || '图片预览'" @click.self="previewImage = null">
      <button type="button" class="image-lightbox-close" aria-label="关闭图片预览" @click="previewImage = null"><i class="ri-close-line"></i></button>
      <img :src="previewImage.src" :alt="previewImage.alt" />
    </div>
  </div>
</template>

<style scoped>
.md {
  width: 100%;
  position: relative;
}
.image-picker { display: none; }
.image-lightbox {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: grid;
  place-items: center;
  padding: 32px;
  background: rgba(15, 18, 28, 0.82);
  cursor: zoom-out;
}
.image-lightbox img {
  max-width: min(96vw, 1600px);
  max-height: 90vh;
  object-fit: contain;
  border-radius: 8px;
  box-shadow: 0 18px 56px rgba(0, 0, 0, 0.38);
  cursor: default;
}
.image-lightbox-close {
  position: fixed;
  top: 16px;
  right: 18px;
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  font-size: 20px;
  cursor: pointer;
}
.image-lightbox-close:hover { background: rgba(255, 255, 255, 0.28); }
.md-editor-wrap { position: relative; }
/* 协作轨道占住左侧一条窄边，正文相应右移，头像不会压到字上 */
.md.collab {
  padding-left: 26px;
}
.peer-track {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 26px;
  pointer-events: none;
}
.peer-av {
  position: absolute;
  width: 17px;
  height: 17px;
  border-radius: 50%;
  color: #fff;
  font-size: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #fff;
  transition: top 0.14s ease;
  pointer-events: auto;
}
.peer-av.typing {
  animation: waoPulse 1.6s infinite;
}
/* 正在被别人输入的那一行，左侧压一道他的颜色 */
.peer-line {
  position: absolute;
  left: 22px;
  width: 2px;
  height: 1.7em;
  border-radius: 2px;
  opacity: 0.55;
  transition: top 0.14s ease;
}
.md-body {
  border: 1px solid transparent;
  border-radius: 9px;
  transition: border-color 0.12s;
}
.image-upload {
  position: absolute;
  right: 8px;
  bottom: 7px;
  z-index: 1;
  height: 26px;
  padding: 0 8px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--wao-divider);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.94);
  color: var(--wao-text-weak);
  font-size: 11px;
  cursor: pointer;
}
.image-upload:hover { border-color: var(--wao-primary); color: var(--wao-primary); }
.image-upload i { font-size: 13px; }
.md.bare .image-upload { right: 0; }
.md:not(.ro) .md-body:hover {
  border-color: var(--wao-divider);
}
.md:not(.ro) .md-body:focus-within {
  border-color: var(--wao-line-control);
  background: var(--wao-bg-card);
}
.md.bare .md-body,
.md.bare:not(.ro) .md-body:hover,
.md.bare:not(.ro) .md-body:focus-within {
  border-color: transparent;
  background: transparent;
}
.md.bare .md-body :deep(.tiptap) {
  padding-left: 0;
  padding-right: 0;
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
