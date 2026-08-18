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
import { BubbleMenu } from '@tiptap/vue-3/menus'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import Placeholder from '@tiptap/extension-placeholder'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { Markdown } from '@tiptap/markdown'
import { Extension } from '@tiptap/core'
import { polishDocumentText } from '@/api/assistant'

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
    /** 文档正文允许一级标题；任务描述等场景仍从二级标题开始。 */
    allowH1?: boolean
    /** 此刻也在这篇文档里的人；空数组即不显示协作轨道。 */
    peers?: EditorPeer[]
    /** 文档正文允许选中文字后调用真实本地 Codex 润色。 */
    polishable?: boolean
  }>(),
  {
    placeholder: '写点什么…支持 Markdown 快捷输入',
    minHeight: 84,
    readonly: false,
    bare: false,
    allowH1: false,
    peers: () => [],
    polishable: false,
  },
)
const emit = defineEmits<{
  'update:modelValue': [value: string]
  /** 需要把图片存起来并换回可引用地址；返回 null 表示上传失败。 */
  'upload-image': [file: File, done: (url: string | null, name: string) => void]
  /** 自己的光标落在第几个顶层块上（-1 = 没有落点），供上报协同在场。 */
  'caret-line': [line: number]
}>()

const previewImage = ref<{ src: string; alt: string } | null>(null)

/**
 * Tab 留在编辑器内：列表项缩进 / 反缩进，普通段落插入一个真实制表符。
 * 返回 true 会阻止浏览器把焦点跳到编辑器后的“图片”按钮。
 */
const TabIndent = Extension.create({
  name: 'tabIndent',
  addKeyboardShortcuts() {
    return {
      Tab: () => {
        if (this.editor.isActive('taskItem')) return this.editor.commands.sinkListItem('taskItem') || true
        if (this.editor.isActive('listItem')) return this.editor.commands.sinkListItem('listItem') || true
        return this.editor.commands.insertContent('\t') || true
      },
      'Shift-Tab': () => {
        if (this.editor.isActive('taskItem')) return this.editor.commands.liftListItem('taskItem') || true
        if (this.editor.isActive('listItem')) return this.editor.commands.liftListItem('listItem') || true
        return true
      },
    }
  },
})

interface PolishDialog {
  from: number
  to: number
  original: string
  sourceDocument: string
  inline: boolean
  marks: { type: string; attrs?: Record<string, unknown> }[]
  polished: string
  loading: boolean
  error: string
}
const polishDialog = ref<PolishDialog | null>(null)

const editor = useEditor({
  content: props.modelValue,
  contentType: 'markdown',
  editable: !props.readonly,
  extensions: [
    StarterKit.configure({
      // 只有知识库文档开放 h1；描述场景仍限制成 h2–h4，避免压过页面标题。
      heading: { levels: props.allowH1 ? [1, 2, 3, 4] : [2, 3, 4] },
      link: {
        openOnClick: false,
        HTMLAttributes: { rel: 'noopener noreferrer', target: '_blank' },
      },
    }),
    Image.configure({ inline: false, HTMLAttributes: { class: 'md-img' } }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Placeholder.configure({ placeholder: () => props.placeholder }),
    Markdown,
    TabIndent,
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

/** 只在可编辑文档有非空文字选区时展示润色入口。 */
function shouldShowPolish({
  from,
  to,
  state,
}: {
  from: number
  to: number
  state: { doc: { textBetween: (from: number, to: number, separator?: string) => string } }
}) {
  return (
    props.polishable &&
    !props.readonly &&
    from !== to &&
    state.doc.textBetween(from, to, '\n').trim().length > 0
  )
}

/**
 * 把当前选区转成适合交给 Codex 的文字：单段只取纯文本，多块选区保留 Markdown 结构。
 * 同时冻结选区与整篇正文，避免等待 AI 时的协同更新导致结果替换到错误位置。
 */
async function startPolish() {
  const e = editor.value
  if (!e) return
  const { from, to, $from } = e.state.selection
  if (from === to) return
  const inline = $from.sameParent(e.state.doc.resolve(to)) && $from.parent.isTextblock
  const original = inline
    ? e.state.doc.textBetween(from, to, '\n')
    : (e.markdown?.serialize({
        type: 'doc',
        content: e.state.doc.slice(from, to).content.toJSON(),
      }) ?? e.state.doc.textBetween(from, to, '\n'))
  const dialog: PolishDialog = {
    from,
    to,
    original: original.trim(),
    sourceDocument: e.getMarkdown(),
    inline,
    marks: $from.marks().map((mark) => mark.toJSON()),
    polished: '',
    loading: false,
    error: '',
  }
  polishDialog.value = dialog
  // ref 会把对象转成响应式代理；后续异步状态必须写代理，界面才能及时更新。
  const current = polishDialog.value
  if (!current.original) {
    current.error = '请先选中需要润色的文字'
    return
  }
  if (current.original.length > 4000) {
    current.error = '一次最多润色 4000 字，请缩小选区后重试'
    return
  }
  await requestPolish(current)
}

async function requestPolish(dialog: PolishDialog) {
  dialog.loading = true
  dialog.error = ''
  try {
    const result = await polishDocumentText(dialog.original)
    if (polishDialog.value !== dialog) return
    dialog.polished = result.polished.trim()
    if (!dialog.polished) dialog.error = 'AI 没有返回可用内容，请重试'
  } catch (cause) {
    if (polishDialog.value !== dialog) return
    dialog.error = cause instanceof Error ? cause.message : '润色失败，请重试'
  } finally {
    if (polishDialog.value === dialog) dialog.loading = false
  }
}

async function retryPolish() {
  if (polishDialog.value) await requestPolish(polishDialog.value)
}

function keepOriginal() {
  polishDialog.value = null
}

/** 用户明确选择“替换选区”后才修改正文；修改会走既有 update:modelValue 与自动保存链路。 */
function applyPolish() {
  const e = editor.value
  const dialog = polishDialog.value
  if (!e || !dialog || !dialog.polished) return
  if (e.getMarkdown() !== dialog.sourceDocument) {
    dialog.error = '等待润色期间文档已发生变化。为避免覆盖新内容，请保留原文后重新选择。'
    return
  }
  const replacement = dialog.inline
    ? {
        type: 'text',
        text: dialog.polished.replace(/\s*\n+\s*/g, ' '),
        marks: dialog.marks.length ? dialog.marks : undefined,
      }
    : dialog.polished
  const options = dialog.inline
    ? undefined
    : { contentType: 'markdown' as const, updateSelection: true }
  e.chain()
    .focus()
    .insertContentAt({ from: dialog.from, to: dialog.to }, replacement, options)
    .run()
  polishDialog.value = null
}

// ---- 协作者行标记 ----

const shell = ref<HTMLElement | null>(null)
const imagePicker = ref<HTMLInputElement | null>(null)
/** 协作者头像在编辑区里的纵向位置（顶层块序号 → px）。 */
const lineTops = ref<Record<number, number>>({})

/** 光标所在的顶层块序号；取不到时给 -1（只在查看）。 */
function caretLine(e: {
  state: {
    selection: { from: number }
    doc: { resolve: (pos: number) => { index: (depth: number) => number } }
  }
}): number {
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
  const wanted = [...new Set(props.peers.map((p) => p.line))].filter(
    (l) => l >= 0 && l < doc.childCount,
  )
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
  if (files.length) {
    insertImages(files)
    return true
  }

  // 部分应用（尤其是文档和截图工具）复制图片时不放 File，只放一段
  // <img src="data:image/...;base64,..."> HTML。如果放行给 ProseMirror，整串 Base64
  // 会进入 Markdown，一张小图就可能占数万字。只在剪贴板内容是纯图片时
  // 接管，避免复制图文混排内容时丢掉其中的文字。
  const embedded = embeddedImageFiles(dt)
  if (!embedded.length) return false
  insertImages(embedded)
  return true
}

/** 把只含 Base64 图片的剪贴板 HTML 还原成 File，复用统一图床上传链路。 */
function embeddedImageFiles(dt: DataTransfer | null | undefined): File[] {
  const html = dt?.getData?.('text/html')?.trim() ?? ''
  if (!html) return []
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const images = Array.from(doc.body.querySelectorAll('img[src^="data:image/"]'))
  if (!images.length) return []

  const textCheck = doc.body.cloneNode(true) as HTMLElement
  textCheck.querySelectorAll('img').forEach((image) => image.remove())
  if (textCheck.textContent?.trim()) return []

  const files = images.map((image, index) =>
    dataImageFile(image.getAttribute('src') ?? '', image.getAttribute('alt') ?? '', index),
  )
  return files.every((file): file is File => file != null) ? files : []
}

/** 仅接受 Base64 图片 data URL；解码失败时交回默认粘贴流程。 */
function dataImageFile(src: string, alt: string, index: number): File | null {
  const match = /^data:(image\/[a-z0-9.+-]+);base64,([a-z0-9+/=\s]+)$/i.exec(src)
  if (!match) return null
  try {
    const binary = atob(match[2]!.replace(/\s/g, ''))
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0))
    const mime = match[1]!.toLowerCase()
    const subtype =
      mime
        .split('/')[1]!
        .replace(/^jpeg$/, 'jpg')
        .replace(/[^a-z0-9]+/g, '') || 'png'
    const baseName = alt
      .trim()
      .replace(/[\\/:*?"<>|]/g, '-')
      .slice(0, 80)
    return new File([bytes], baseName || `clipboard-image-${index + 1}.${subtype}`, { type: mime })
  } catch {
    return null
  }
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
  const uploaded = Promise.all(
    files.map(
      (file) =>
        new Promise<UploadedImage | null>((resolve) => {
          emit('upload-image', file, (url, name) => resolve(url ? { url, name } : null))
        }),
    ),
  )
  imageInsertionQueue = imageInsertionQueue.then(async () => {
    const images = (await uploaded).filter((item): item is UploadedImage => item != null)
    const currentEditor = editor.value
    if (!images.length || !currentEditor) return
    currentEditor
      .chain()
      .focus()
      .insertContent(
        images.map((image) => ({
          type: 'image',
          attrs: { src: image.url, alt: image.name },
        })),
      )
      .run()
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
    <input
      ref="imagePicker"
      class="image-picker"
      type="file"
      accept="image/*"
      multiple
      tabindex="-1"
      aria-hidden="true"
      @change="onImagePicked"
    />
    <div v-if="peerMarks.length" class="peer-track" aria-hidden="true">
      <template v-for="m in peerMarks" :key="m.id">
        <span
          v-if="m.editing"
          class="peer-line"
          :style="{ top: m.top + 'px', background: m.color }"
        ></span>
        <span
          class="peer-av"
          :class="{ typing: m.editing }"
          :style="{ top: m.top + 'px', left: m.offset + 'px', background: m.color }"
          :title="`${m.name} ${m.editing ? '正在编辑这一行' : '在这一行'}`"
          >{{ m.initial }}</span
        >
      </template>
    </div>
    <div class="md-editor-wrap">
      <BubbleMenu
        v-if="editor && polishable"
        :editor="editor"
        :should-show="shouldShowPolish"
        :options="{ placement: 'top', offset: 8 }"
      >
        <button
          type="button"
          class="polish-trigger"
          aria-label="润色选中文字"
          @mousedown.prevent
          @click="startPolish"
        >
          <i class="ri-magic-line"></i><span>润色</span>
        </button>
      </BubbleMenu>
      <EditorContent :editor="editor" class="md-body" :style="{ minHeight: minHeight + 'px' }" />
      <button
        v-if="!readonly"
        type="button"
        class="image-upload"
        title="从图册选择图片"
        aria-label="从图册选择图片"
        @mousedown.prevent
        @click="chooseImage"
      >
        <i class="ri-image-add-line"></i><span>图片</span>
      </button>
    </div>
    <div
      v-if="previewImage"
      class="image-lightbox"
      role="dialog"
      aria-modal="true"
      :aria-label="previewImage.alt || '图片预览'"
      @click.self="previewImage = null"
    >
      <button
        type="button"
        class="image-lightbox-close"
        aria-label="关闭图片预览"
        @click="previewImage = null"
      >
        <i class="ri-close-line"></i>
      </button>
      <img :src="previewImage.src" :alt="previewImage.alt" />
    </div>
    <div v-if="polishDialog" class="polish-mask" @click.self="keepOriginal">
      <section class="polish-dialog" role="dialog" aria-modal="true" aria-labelledby="polish-title">
        <header>
          <div>
            <small>AI 文档助手</small>
            <h2 id="polish-title">对照后再决定是否替换</h2>
          </div>
          <button
            type="button"
            class="polish-close"
            aria-label="关闭并保留原文"
            @click="keepOriginal"
          >
            <i class="ri-close-line"></i>
          </button>
        </header>

        <div class="polish-compare">
          <article>
            <div class="polish-card-title"><span>原文</span><em>当前文档保持不变</em></div>
            <pre>{{ polishDialog.original }}</pre>
          </article>
          <article>
            <div class="polish-card-title"><span>润色结果</span><em>确认后才会写入</em></div>
            <div v-if="polishDialog.loading" class="polish-state">
              <i class="ri-loader-4-line"></i><span>本地 Codex 正在润色…</span>
            </div>
            <pre v-else-if="polishDialog.polished">{{ polishDialog.polished }}</pre>
            <div v-else class="polish-state">
              <i class="ri-quill-pen-line"></i><span>暂时没有候选内容</span>
            </div>
          </article>
        </div>

        <div v-if="polishDialog.error" class="polish-error" role="alert">
          <i class="ri-error-warning-line"></i><span>{{ polishDialog.error }}</span>
          <button
            v-if="polishDialog.original.length <= 4000"
            type="button"
            :disabled="polishDialog.loading"
            @click="retryPolish"
          >
            重试
          </button>
        </div>

        <footer>
          <button type="button" class="polish-keep" @click="keepOriginal">保留原文</button>
          <button
            type="button"
            class="polish-retry"
            :disabled="polishDialog.loading"
            @click="retryPolish"
          >
            <i class="ri-refresh-line"></i>重新润色
          </button>
          <button
            type="button"
            class="polish-apply"
            :disabled="polishDialog.loading || !polishDialog.polished || !!polishDialog.error"
            @click="applyPolish"
          >
            <i class="ri-check-line"></i>替换选区
          </button>
        </footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.md {
  width: 100%;
  position: relative;
}
.image-picker {
  display: none;
}
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
.image-lightbox-close:hover {
  background: rgba(255, 255, 255, 0.28);
}
.polish-trigger {
  min-height: 34px;
  padding: 0 13px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  border-radius: 9px;
  background: #292b35;
  box-shadow: 0 7px 22px rgba(18, 20, 28, 0.24);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}
.polish-trigger:hover {
  background: #65558f;
}
.polish-mask {
  position: fixed;
  inset: 0;
  z-index: 1300;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(24, 25, 32, 0.46);
}
.polish-dialog {
  width: min(760px, 100%);
  max-height: min(720px, calc(100vh - 40px));
  overflow: auto;
  padding: 20px;
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  box-shadow: 0 24px 72px rgba(16, 18, 28, 0.28);
}
.polish-dialog > header,
.polish-dialog > footer,
.polish-card-title,
.polish-error {
  display: flex;
  align-items: center;
}
.polish-dialog > header {
  justify-content: space-between;
  gap: 16px;
}
.polish-dialog header small {
  color: #65558f;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 1px;
}
.polish-dialog header h2 {
  margin: 3px 0 0;
  color: var(--wao-text);
  font-size: 18px;
}
.polish-close {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  cursor: pointer;
}
.polish-compare {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.polish-compare article {
  min-width: 0;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  overflow: hidden;
}
.polish-card-title {
  min-height: 42px;
  padding: 0 13px;
  justify-content: space-between;
  gap: 8px;
  background: var(--wao-bg-sub);
}
.polish-card-title span {
  color: var(--wao-text-2);
  font-size: 12px;
  font-weight: 700;
}
.polish-card-title em {
  color: var(--wao-text-faint);
  font-size: 9px;
  font-style: normal;
}
.polish-compare pre {
  min-height: 180px;
  max-height: 320px;
  margin: 0;
  padding: 14px;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font: inherit;
  font-size: 12.5px;
  line-height: 1.75;
}
.polish-state {
  min-height: 180px;
  padding: 20px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 8px;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.polish-state i {
  color: #65558f;
  font-size: 22px;
}
.polish-state .ri-loader-4-line {
  animation: polishSpin 0.9s linear infinite;
}
.polish-error {
  margin-top: 12px;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  font-size: 11px;
}
.polish-error span {
  flex: 1;
}
.polish-error button {
  border: 0;
  background: transparent;
  color: inherit;
  font-weight: 700;
  cursor: pointer;
}
.polish-dialog > footer {
  margin-top: 16px;
  justify-content: flex-end;
  gap: 8px;
}
.polish-dialog > footer button {
  min-height: 36px;
  padding: 0 14px;
  border-radius: 9px;
  font-size: 11.5px;
  cursor: pointer;
}
.polish-dialog > footer button:disabled {
  opacity: 0.45;
  cursor: default;
}
.polish-keep,
.polish-retry {
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}
.polish-apply {
  border: 0;
  background: #65558f;
  color: #fff;
  font-weight: 700;
}
@keyframes polishSpin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 680px) {
  .polish-mask {
    padding: 10px;
    align-items: end;
  }
  .polish-dialog {
    max-height: calc(100vh - 20px);
    padding: 16px;
    border-radius: 16px 16px 10px 10px;
  }
  .polish-compare {
    grid-template-columns: 1fr;
  }
  .polish-compare pre,
  .polish-state {
    min-height: 110px;
    max-height: 190px;
  }
  .polish-card-title em {
    display: none;
  }
  .polish-dialog > footer {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
  }
  .polish-apply {
    grid-column: 1 / -1;
    grid-row: 1;
  }
}
.md-editor-wrap {
  position: relative;
}
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
.image-upload:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary);
}
.image-upload i {
  font-size: 13px;
}
.md.bare .image-upload {
  right: 0;
}
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
.md-body :deep(h1),
.md-body :deep(h3),
.md-body :deep(h4) {
  margin: 14px 0 6px;
  font-weight: 700;
  color: var(--wao-text);
  line-height: 1.4;
}
.md-body :deep(h1) {
  font-size: 18px;
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
