<script setup lang="ts">
/**
 * 所见即所得的描述编辑器 —— 全项目文字描述输入的统一控件。
 *
 * 没有「编辑 / 预览」两态：敲 `## ` 当场变标题、`- ` 当场变列表，看到的就是最终样子
 * （对齐飞书文档的手感）。底层是 Tiptap/ProseMirror，中文输入法的合成事件、撤销栈、
 * 选区都由它处理；普通描述仍以 Markdown 收发，知识库文档额外输出版本化 JSON
 * 与 Markdown 投影（见 ADR-015、ADR-198）。
 *
 * 粘贴 / 拖入图片会上传到图床，并**内联插入到光标处**，不再堆到底部的附件区。
 *
 * 普通描述场景保持无工具条；`advanced` 文档场景提供可展开工具栏、选区浮层和 `/` 菜单。
 *
 * 传入 `peers` 时会在左侧落一条协作者轨道：每个人的头像贴在他光标所在的那一行，
 * 正在输入的还会给那一行加一道颜色条（见 ADR-017）。不传就是普通编辑器，行为不变。
 */
import { computed, getCurrentInstance, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import { BubbleMenu } from '@tiptap/vue-3/menus'
import { DragHandle } from '@tiptap/extension-drag-handle-vue-3'
import StarterKit from '@tiptap/starter-kit'
import Image, { type ImageOptions } from '@tiptap/extension-image'
import Placeholder from '@tiptap/extension-placeholder'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { Markdown } from '@tiptap/markdown'
import { Extension, mergeAttributes, type Editor } from '@tiptap/core'
import { TableKit } from '@tiptap/extension-table'
import { CellSelection } from '@tiptap/pm/tables'
import { Plugin } from '@tiptap/pm/state'
import { closeHistory } from '@tiptap/pm/history'
import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight'
import { Mathematics } from '@tiptap/extension-mathematics'
import { Color, TextStyle } from '@tiptap/extension-text-style'
import Highlight from '@tiptap/extension-highlight'
import TextAlign from '@tiptap/extension-text-align'
import Underline from '@tiptap/extension-underline'
import { Details, DetailsContent, DetailsSummary } from '@tiptap/extension-details'
import UniqueID from '@tiptap/extension-unique-id'
import { common, createLowlight } from 'lowlight'
import DOMPurify from 'dompurify'
import 'katex/dist/katex.min.css'
import { polishDocumentText } from '@/api/assistant'
import { newPolishRequestId } from '@/api/polishJobs'
import PolishProgress from './PolishProgress.vue'
import { getActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { loadRotationImage } from '@/api/imageRotation'
import { rotateImage } from '@/utils/rotateImage'
import { openEditorSelectionTask, type EditorTaskSource } from '@/composables/editorSelectionTask'
import { copyRichContent, portableClipboardHtml } from '@/utils/richClipboard'
import { toast } from '@/composables/toast'
import { countCharacters } from '@/utils/characterCount'
import { notePreviewSource } from '@/utils/notePreviewImages'
import ArticleImageAction from './ArticleImageAction.vue'
import SelectionImageAction from './SelectionImageAction.vue'
import ImageCopyMenu from './ImageCopyMenu.vue'
import { createDrawing, fetchDrawing, fetchDrawings, type DrawingFileSummary } from '@/api/drawings'
import type { FileItem, RichDocContent, RichDocNode } from '@/types'
import { Callout, Column, Columns, DrawingCard, FileCard, LinkCard, MediaCard, MermaidBlock } from './richEditorExtensions'

const lowlight = createLowlight(common)
const PASTE_COLORS = new Set(['#1f2329', '#646a73', '#d83931', '#de7802', '#2ea121', '#245bdb', '#6425d0', '#fde2e2', '#feead2', '#d9f5d6', '#dbeafe', '#ede2fe'])

/** 合并相邻的默认有序列表，确保在列表前插入新条目后原条目自动顺延。 */
const OrderedListContinuation = Extension.create({
  name: 'orderedListContinuation',
  addProseMirrorPlugins() {
    return [new Plugin({
      appendTransaction: (transactions, _oldState, newState) => {
        if (!transactions.some(transaction => transaction.docChanged)) return null
        let joinPosition: number | null = null
        newState.doc.descendants((node, position, parent, index) => {
          if (joinPosition !== null || node.type.name !== 'orderedList' || !parent || index >= parent.childCount - 1) return
          const next = parent.child(index + 1)
          if (next.type === node.type && node.attrs.start === 1 && next.attrs.start === 1 && node.attrs.type === next.attrs.type) {
            joinPosition = position + node.nodeSize
          }
        })
        return joinPosition === null ? null : newState.tr.join(joinPosition)
      },
    })]
  },
})

/** 清理 Word / 飞书粘贴 HTML：保留语义标签，去掉脚本、事件、任意样式和危险 URL。 */
function sanitizePastedHtml(source: string): string {
  const safe = DOMPurify.sanitize(source, {
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form'],
    FORBID_ATTR: ['srcdoc'],
  })
  const document = new DOMParser().parseFromString(safe, 'text/html')
  document.body.querySelectorAll<HTMLElement>('*').forEach((element) => {
    for (const attribute of [...element.attributes]) {
      if (attribute.name.startsWith('on')) element.removeAttribute(attribute.name)
    }
    const style = element.getAttribute('style') ?? ''
    element.removeAttribute('style')
    const kept: string[] = []
    for (const declaration of style.split(';')) {
      const [rawName, rawValue] = declaration.split(':', 2)
      const name = rawName?.trim().toLowerCase()
      const value = rawValue?.trim().toLowerCase()
      if ((name === 'color' || name === 'background-color') && value && PASTE_COLORS.has(value)) kept.push(`${name}:${value}`)
      if (name === 'text-align' && ['left', 'center', 'right'].includes(value ?? '')) kept.push(`${name}:${value}`)
    }
    if (kept.length) element.setAttribute('style', kept.join(';'))
    for (const name of ['href', 'src']) {
      const value = element.getAttribute(name)
      if (value && /^(?:javascript|data):/i.test(value.trim())) element.removeAttribute(name)
    }
  })
  return document.body.innerHTML
}

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
    /** 小记清单隐藏正文图片时，仍只请求轻量预览。 */
    previewImages?: boolean
    /** 原地编辑首次挂载后将焦点交给正文。 */
    autofocus?: boolean
    /** 文档正文等沉浸场景不显示输入框边框与底色。 */
    bare?: boolean
    /** 文档正文允许一级标题；任务描述等场景仍从二级标题开始。 */
    allowH1?: boolean
    /** 此刻也在这篇文档里的人；空数组即不显示协作轨道。 */
    peers?: EditorPeer[]
    /** 通用选区操作：润色与记录待办绑定显示。 */
    polishable?: boolean
    /** 兼容旧调用；与 polishable 一样开启润色和待办这一组选区操作。 */
    taskable?: boolean
    /** 可选来源；新草稿不传 docId，避免关联尚未保存的文档。 */
    taskSource?: EditorTaskSource
    /** 文档正文允许点击打开、创建和维护链接。 */
    linkControls?: boolean
    /** 文档正文允许按比例或像素长宽调整图片。 */
    imageControls?: boolean
    /** 小记或知识库文档的文章总览图入口；key 用于跨页面恢复生成任务。 */
    articleImageKey?: string
    /** 小记选中文字可生成图片，图片作为正文附件节点保存。 */
    selectionImage?: boolean
    noteTitleOnImage?: boolean
    /** 知识库文档的完整工具栏、斜杠菜单和高级内容块。 */
    advanced?: boolean
    /** 小记使用紧凑工具栏，并隐藏需要进入完整文档继续编辑的重型内容块。 */
    compact?: boolean
    /** 保留斜线与选区能力，但不显示顶部格式工具栏。 */
    hideToolbar?: boolean
    /** 挂到 body 的菜单层级；全屏容器需显式提升，避免菜单被遮挡。 */
    menuZIndex?: number
    /** 知识库页面的常驻顶部工具栏挂载点。 */
    toolbarTarget?: HTMLElement | null
    /** 已持久化的完整 Tiptap JSON；存在时优先于 Markdown 装载。 */
    document?: RichDocNode | null
  }>(),
  {
    placeholder: '写点什么…支持 Markdown 快捷输入',
    minHeight: 84,
    readonly: false,
    previewImages: false,
    autofocus: false,
    bare: false,
    allowH1: false,
    peers: () => [],
    polishable: false,
    taskable: false,
    linkControls: false,
    imageControls: false,
    articleImageKey: '',
    selectionImage: false,
    noteTitleOnImage: false,
    advanced: false,
    compact: false,
    hideToolbar: false,
    menuZIndex: 1000,
    toolbarTarget: null,
    document: null,
  },
)
const emit = defineEmits<{
  'update:modelValue': [value: string]
  /** 需要把图片存起来并换回可引用地址；返回 null 表示上传失败。 */
  'upload-image': [file: File, done: (url: string | null, name: string) => void]
  /** 自己的光标落在第几个顶层块上（-1 = 没有落点），供上报协同在场。 */
  'caret-line': [line: number]
  'update:document': [value: RichDocNode]
  'content-change': [value: RichDocContent]
  'character-count': [value: number]
  'upload-file': [file: File, done: (item: FileItem | null) => void]
  'add-task': [text: string]
  'task-opened': []
  'generated-title': [title: string]
}>()

const previewImage = ref<{ src: string; alt: string } | null>(null)
const imageMenu = ref<{ src: string; x: number; y: number } | null>(null)
function fullImageSource(image: HTMLImageElement): string {
  return image.dataset.fullSrc || image.currentSrc || image.src
}
function openImageMenu(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof HTMLImageElement)) return
  event.preventDefault()
  imageMenu.value = { src: fullImageSource(target), x: event.clientX, y: event.clientY }
}

type ImageDimension = number | `${number}%` | null

/**
 * 图片尺寸继续存进 Markdown，而不是另开一份浏览器状态：用图片 title 的 WAO 元数据
 * 保存百分比或像素长宽，普通 Markdown 阅读器仍能照常显示图片。
 */
const IMAGE_SIZE_PREFIX = 'wao-image-size:'
const IMAGE_SCALES = [25, 50, 75, 100] as const

function parseImageDimension(value: unknown): ImageDimension {
  if (typeof value === 'number' && Number.isFinite(value) && value > 0 && value <= 4000) {
    return Math.round(value)
  }
  if (typeof value !== 'string') return null
  if (/^(25|50|75|100)%$/.test(value)) return value as `${number}%`
  if (!/^\d{1,4}$/.test(value)) return null
  const parsed = Number(value)
  return parsed > 0 && parsed <= 4000 ? parsed : null
}

function decodeImageTitle(value: unknown) {
  const title = typeof value === 'string' ? value : ''
  if (!title.startsWith(IMAGE_SIZE_PREFIX)) {
    return { title: title || null, width: null as ImageDimension, height: null as ImageDimension }
  }
  const match = /^wao-image-size:([^x;]+)x([^;]+)(?:;title=(.*))?$/.exec(title)
  if (!match) return { title: null, width: null as ImageDimension, height: null as ImageDimension }
  const width = match[1] === 'auto' ? null : parseImageDimension(match[1])
  const height = match[2] === 'auto' ? null : parseImageDimension(match[2])
  const valid = (typeof width === 'string' && height == null)
    || (typeof width !== 'string' && typeof height !== 'string' && (width != null || height != null))
  if ((match[1] !== 'auto' && width == null)
    || (match[2] !== 'auto' && height == null)
    || !valid) {
    return { title: null, width: null as ImageDimension, height: null as ImageDimension }
  }
  let originalTitle: string | null = null
  if (match[3]) {
    try {
      originalTitle = decodeURIComponent(match[3])
    } catch {
      originalTitle = null
    }
  }
  return {
    title: originalTitle,
    width,
    height,
  }
}

function encodeImageTitle(width: unknown, height: unknown, title: unknown): string {
  const safeWidth = parseImageDimension(width)
  const safeHeight = parseImageDimension(height)
  const originalTitle = typeof title === 'string' ? title : ''
  if (safeWidth == null && safeHeight == null) return originalTitle
  if (typeof safeHeight === 'string' || (typeof safeWidth === 'string' && safeHeight != null)) {
    return originalTitle
  }
  const suffix = originalTitle ? `;title=${encodeURIComponent(originalTitle)}` : ''
  return `${IMAGE_SIZE_PREFIX}${safeWidth ?? 'auto'}x${safeHeight ?? 'auto'}${suffix}`
}

function imageStyle(attributes: Record<string, unknown>): string {
  const width = parseImageDimension(attributes.width)
  const height = parseImageDimension(attributes.height)
  const styles: string[] = []
  if (attributes.align === 'center') styles.push('margin-left:auto', 'margin-right:auto')
  else if (attributes.align === 'right') styles.push('margin-left:auto', 'margin-right:0')
  else if (attributes.align === 'left') styles.push('margin-left:0', 'margin-right:auto')
  if (typeof width === 'string') {
    styles.push(`width:${width}`, 'height:auto')
  } else if (width != null && height != null) {
    // 窄屏仍由 max-width 收住；aspect-ratio 让按长宽设置的图片等比缩小而不变形。
    styles.push(`width:${width}px`, `aspect-ratio:${width}/${height}`, 'height:auto', 'object-fit:contain')
  } else if (width != null) {
    styles.push(`width:${width}px`, 'height:auto')
  } else if (typeof height === 'number') {
    styles.push(`height:${height}px`, 'width:auto')
  }
  return styles.join(';')
}

/** Tiptap 自带 Image 已有 width/height 属性；这里只补 Markdown 往返和百分比渲染。 */
const DocumentImage = Image.extend<ImageOptions & { previewImages: boolean }>({
  addOptions() {
    return { ...this.parent!(), previewImages: false }
  },
  addAttributes() {
    return { ...this.parent?.(), align: { default: null } }
  },
  parseMarkdown: (token, helpers) => {
    const size = decodeImageTitle(token.title)
    return helpers.createNode('image', {
      src: token.href,
      alt: token.text,
      title: size.title,
      width: size.width,
      height: size.height,
    })
  },
  renderMarkdown: (node) => {
    const src = node.attrs?.src ?? ''
    const alt = node.attrs?.alt ?? ''
    const title = encodeImageTitle(node.attrs?.width, node.attrs?.height, node.attrs?.title)
    return title ? `![${alt}](${src} "${title}")` : `![${alt}](${src})`
  },
  renderHTML({ HTMLAttributes }) {
    const attributes = { ...HTMLAttributes }
    if (this.options.previewImages && typeof attributes.src === 'string') {
      const preview = notePreviewSource(attributes.src)
      if (preview !== attributes.src) {
        attributes['data-full-src'] = attributes.src
        attributes.src = preview
      }
    }
    const style = imageStyle(attributes)
    delete attributes.width
    delete attributes.height
    delete attributes.style
    return [
      'img',
      mergeAttributes(this.options.HTMLAttributes, attributes, style ? { style } : {}),
    ]
  },
})

/**
 * Tab 留在编辑器内：列表项缩进 / 反缩进，普通段落插入一个真实制表符。
 * 返回 true 会阻止浏览器把焦点跳到编辑器后的“图片”按钮。
 */
/** 整表选中时删除表格本身；局部选区仍沿用清空单元格的行为。 */
const WholeTableDeletion = Extension.create({
  name: 'wholeTableDeletion',
  priority: 1100,
  addKeyboardShortcuts() {
    const remove = () => {
      const selection = this.editor.state.selection
      if (!this.editor.isEditable || !(selection instanceof CellSelection)
        || !selection.isRowSelection() || !selection.isColSelection()) return false
      this.editor.view.dispatch(closeHistory(this.editor.state.tr))
      const removed = this.editor.commands.deleteTable()
      this.editor.view.dispatch(closeHistory(this.editor.state.tr))
      return removed
    }
    return { Backspace: remove, Delete: remove }
  },
})

const TabIndent = Extension.create({
  name: 'tabIndent',
  addKeyboardShortcuts() {
    return {
      Tab: () => {
        if (this.editor.isActive('table') || this.editor.isActive('codeBlock')) return false
        if (this.editor.isActive('taskItem')) return this.editor.commands.sinkListItem('taskItem') || true
        if (this.editor.isActive('listItem')) return this.editor.commands.sinkListItem('listItem') || true
        return this.editor.commands.insertContent('\t') || true
      },
      'Shift-Tab': () => {
        if (this.editor.isActive('table') || this.editor.isActive('codeBlock')) return false
        if (this.editor.isActive('taskItem')) return this.editor.commands.liftListItem('taskItem') || true
        if (this.editor.isActive('listItem')) return this.editor.commands.liftListItem('listItem') || true
        return true
      },
    }
  },
})

interface PolishDialog {
  startedAt?: number
  requestId?: string
  progress?: string
  failed?: boolean
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
const polishMinimized = ref(false)
function dismissPolish() {
  if (polishDialog.value?.loading) polishMinimized.value = true
  else keepOriginal()
}
const polishSession = getActivePinia() ? useSessionStore() : null
let polishMounted = true
function polishStorageKey() {
  if (!polishSession?.userId) return ''
  return `wao-polish-selection-v1:${polishSession.userId}:${props.taskSource?.id ?? location.pathname}:${props.placeholder}`
}
function persistPolish(dialog: PolishDialog | null) {
  const key = polishStorageKey()
  if (!key) return
  try {
    if (dialog) localStorage.setItem(key, JSON.stringify({ dialog, savedAt: Date.now() }))
    else localStorage.removeItem(key)
  } catch { /* 浏览器存储受限不阻止本次润色；服务端任务仍保留。 */ }
}
function restorePolish() {
  if (polishDialog.value || !editor.value || !selectionActionsEnabled.value || props.readonly) return
  const key = polishStorageKey()
  if (!key) return
  try {
    const saved = JSON.parse(localStorage.getItem(key) ?? 'null')
    if (!saved) return
    if (Date.now() - saved.savedAt > 24 * 60 * 60_000) { localStorage.removeItem(key); return }
    const dialog = saved.dialog as PolishDialog
    // 通用页面可能同时挂载多个编辑器，只在完整正文匹配时恢复冻结选区。
    if (!dialog.requestId?.match(/^[a-f0-9]{32}$/) || dialog.sourceDocument !== editor.value.getMarkdown()) return
    polishDialog.value = { ...dialog, loading: false }
    if (!dialog.polished && !dialog.failed) void requestPolish(polishDialog.value)
  } catch { /* 无效缓存不覆盖正文。 */ }
}
const editorReady = ref(!props.advanced)
const characterCount = ref(0)
function updateCharacterCount(e: Editor) {
  // 从正文读取，避免把 Markdown 标记、图片地址和附件元数据计入创作量。
  characterCount.value = countCharacters(e.state.doc.textContent)
  emit('character-count', characterCount.value)
}
const toolbarOpen = ref(false)

const editor = useEditor({
  content: props.document ?? props.modelValue,
  contentType: props.document ? 'json' : 'markdown',
  editable: !props.readonly,
  extensions: [
    OrderedListContinuation,
    StarterKit.configure({
      // 只有知识库文档开放 h1；描述场景仍限制成 h2–h4，避免压过页面标题。
      heading: { levels: props.allowH1 ? [1, 2, 3, 4] : [2, 3, 4] },
      codeBlock: false,
      underline: false,
      link: {
        // 可编辑态由下方 handleClick 统一“选中整段 + 安全新开窗口”，避免插件与编辑菜单各开一次。
        openOnClick: false,
        enableClickSelection: false,
        HTMLAttributes: { rel: 'noopener noreferrer', target: '_blank' },
      },
    }),
    DocumentImage.configure({ inline: false, previewImages: props.previewImages, HTMLAttributes: { class: 'md-img' } }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Placeholder.configure({ placeholder: () => props.placeholder }),
    Markdown,
    TabIndent,
    WholeTableDeletion,
    Underline,
    TextStyle,
    Color,
    Highlight.configure({ multicolor: true }),
    TextAlign.configure({ types: ['heading', 'paragraph'] }),
    TableKit.configure({ table: { resizable: true } }),
    CodeBlockLowlight.configure({ lowlight, enableTabIndentation: true, tabSize: 2 }),
    Mathematics.configure({ katexOptions: { throwOnError: false, strict: 'warn' } }),
    Details.configure({ persist: false }),
    DetailsSummary,
    DetailsContent,
    UniqueID.configure({
      attributeName: 'uid',
      types: props.advanced
        ? ['paragraph', 'heading', 'blockquote', 'codeBlock', 'callout', 'columns', 'table',
          'horizontalRule', 'details', 'fileCard', 'mediaCard', 'linkCard', 'drawingCard', 'mermaid']
        : [],
    }),
    Callout,
    Columns,
    Column,
    FileCard,
    MediaCard,
    LinkCard,
    DrawingCard,
    MermaidBlock,
  ],
  editorProps: {
    handleDOMEvents: {
      copy: (view, event) => {
        if (!event.clipboardData || view.state.selection.empty) return false
        const slice = view.state.selection.content()
        const { dom } = view.serializeForClipboard(slice)
        const markdown = editor.value?.markdown?.serialize({ type: 'doc', content: slice.content.toJSON() })
          || view.state.doc.textBetween(view.state.selection.from, view.state.selection.to, '\n')
        event.clipboardData.setData('text/html', portableClipboardHtml(dom.innerHTML))
        event.clipboardData.setData('text/plain', markdown)
        event.preventDefault()
        return true
      },
    },
    transformPastedHTML: sanitizePastedHtml,
    handlePaste: (_view, event) => consumeImages(event.clipboardData),
    handleDrop: (_view, event) => consumeImages((event as DragEvent).dataTransfer),
    handleClick: (_view, pos, event) => {
      const target = event.target
      if (target instanceof HTMLImageElement) {
        if (props.imageControls && !props.readonly) {
          // 用户在尺寸输入框或“应用”上操作后，ProseMirror 仍保留原来的
          // NodeSelection，但浏览器焦点已离开编辑器。此时只重复设置相同选区不会产生
          // 可让 BubbleMenu 重新显示的状态变化，所以点击图片时必须同时取回焦点。
          editor.value?.chain().focus().setNodeSelection(pos).run()
          return true
        }
        previewImage.value = { src: fullImageSource(target), alt: target.alt }
        return true
      }
      return false
    },
    handleDoubleClick: (_view, _pos, event) => {
      const target = event.target
      if (!(target instanceof HTMLImageElement)) return false
      previewImage.value = { src: fullImageSource(target), alt: target.alt }
      return true
    },
  },
  onCreate: ({ editor: e }) => nextTick(() => {
    updateCharacterCount(e)
    editorReady.value = true
    if (props.autofocus && !props.readonly && !e.isDestroyed) e.commands.focus('start', { scrollIntoView: false })
  }),
  onTransaction: ({ editor: e, transaction }) => {
    if (transaction.docChanged) updateCharacterCount(e)
  },
  onUpdate: ({ editor: e }) => {
    tableMenu.value = null
    selectionDismissed.value = false
    if (!editorReady.value) return
    const markdown = e.getMarkdown()
    const document = e.getJSON() as RichDocNode
    emit('update:modelValue', markdown)
    emit('update:document', document)
    emit('content-change', { version: 1, document, markdown })
    syncSelectionControls(e)
    scheduleMeasure()
    updateSlashMenu(e)
  },
  onSelectionUpdate: ({ editor: e }) => {
    tableMenu.value = null
    selectionDismissed.value = false
    emit('caret-line', caretLine(e))
    syncSelectionControls(e)
  },
  onBlur: () => flushExternal(),
})

const selectionMenuEl = ref<HTMLElement | null>(null)
const selectionImageAction = ref<InstanceType<typeof SelectionImageAction> | null>(null)
const selectedText = ref('')
const selectedCharacterCount = computed(() => countCharacters(selectedText.value))
const selectionCanLink = ref(false)
const selectionMenuParent = () => window.document.body
const selectionDismissed = ref(false)
const tableMenu = ref<{ x: number; y: number; ready: boolean } | null>(null)
const tableMenuEl = ref<HTMLElement | null>(null)

function multipleCells(e: Editor) {
  const selection = e.state.selection
  return selection instanceof CellSelection && selection.$anchorCell.pos !== selection.$headCell.pos
}

type TableCommand = 'addRowBefore' | 'addRowAfter' | 'addColumnBefore' | 'addColumnAfter'
  | 'deleteRow' | 'deleteColumn' | 'toggleHeaderRow' | 'mergeCells' | 'splitCell' | 'deleteTable'
const tableActions: { command: TableCommand; label: string; icon: string; danger?: boolean }[] = [
  { command: 'addRowBefore', label: '向上插入行', icon: 'ri-insert-row-top' },
  { command: 'addRowAfter', label: '向下插入行', icon: 'ri-insert-row-bottom' },
  { command: 'addColumnBefore', label: '向左插入列', icon: 'ri-insert-column-left' },
  { command: 'addColumnAfter', label: '向右插入列', icon: 'ri-insert-column-right' },
  { command: 'mergeCells', label: '合并单元格', icon: 'ri-merge-cells-horizontal' },
  { command: 'splitCell', label: '拆分单元格', icon: 'ri-split-cells-horizontal' },
  { command: 'toggleHeaderRow', label: '切换表头', icon: 'ri-table-line' },
  { command: 'deleteRow', label: '删除行', icon: 'ri-delete-row', danger: true },
  { command: 'deleteColumn', label: '删除列', icon: 'ri-delete-column', danger: true },
  { command: 'deleteTable', label: '删除表格', icon: 'ri-delete-bin-line', danger: true },
]

function hideSelectionMenu() {
  selectionDismissed.value = true
  editor.value?.commands.setMeta('documentSelectionMenu', 'hide')
}

function closeTableMenu(restoreFocus = false) {
  if (!tableMenu.value) return
  tableMenu.value = null
  hideSelectionMenu()
  if (restoreFocus) editor.value?.commands.focus()
}

function runTableAction(command: TableCommand) {
  const e = editor.value
  if (!e || props.readonly || !e.can()[command]()) return
  tableMenu.value = null
  e.view.dispatch(closeHistory(e.state.tr))
  e.chain().focus()[command]().run()
  e.view.dispatch(closeHistory(e.state.tr))
  hideSelectionMenu()
}

/** 右键以实际命中的单元格为目标；在已有多选范围内右键保留整组选区。 */
async function openTableMenu(event: MouseEvent) {
  const e = editor.value
  const cell = event.target instanceof Element ? event.target.closest('td, th') : null
  if (!e || !props.advanced || props.readonly || !cell || !e.view.dom.contains(cell)) return
  event.preventDefault()
  const $pos = e.state.doc.resolve(e.view.posAtDOM(cell, 0))
  let cellPos: number | null = null
  for (let depth = $pos.depth; depth > 0; depth--) {
    if (['tableCell', 'tableHeader'].includes($pos.node(depth).type.name)) {
      cellPos = $pos.before(depth)
      break
    }
  }
  if (cellPos == null) return
  let selected = false
  if (e.state.selection instanceof CellSelection) {
    e.state.selection.forEachCell((_node, pos) => { if (pos === cellPos) selected = true })
  }
  if (!selected) e.commands.setCellSelection({ anchorCell: cellPos })
  hideSelectionMenu()
  const menu = { x: event.clientX, y: event.clientY, ready: false }
  tableMenu.value = menu
  await nextTick()
  if (!tableMenu.value || !tableMenuEl.value) return
  const rect = tableMenuEl.value.getBoundingClientRect()
  tableMenu.value = {
    x: Math.max(8, Math.min(menu.x, window.innerWidth - rect.width - 8)),
    y: Math.max(8, Math.min(menu.y, window.innerHeight - rect.height - 8)),
    ready: true,
  }
  await nextTick()
  tableMenuEl.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
}

function onTableMenuKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' || event.key === 'Tab') {
    event.preventDefault()
    closeTableMenu(true)
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const buttons = [...(tableMenuEl.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
    : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length
  buttons[next]?.focus()
}

function dismissTableMenu(event: Event) {
  if (event.target instanceof Node && tableMenuEl.value?.contains(event.target)) return
  closeTableMenu()
}

function preserveTableSelection(event: MouseEvent) {
  if (event.button === 2 && editor.value?.state.selection instanceof CellSelection
    && event.target instanceof Element && event.target.closest('td, th')) event.preventDefault()
}

/** 所有文字与块操作共享一个浮层，图片使用互斥的尺寸编辑器。 */
function shouldShowSelection({ editor: e, from, to, state }: MenuContext) {
  if (props.readonly || selectionDismissed.value || tableMenu.value || e.isActive('image') || linkDialog.value || polishDialog.value || slashOpen.value) return false
  if (!e.isFocused && !selectionMenuEl.value?.contains(document.activeElement)) return false
  if (e.state.selection instanceof CellSelection) return props.advanced && multipleCells(e)
  if (e.isActive('table') && from === to) return false
  const text = from !== to && state.doc.textBetween(from, to, '\n').trim().length > 0
  return text || (props.linkControls && e.isActive('link'))
    || (props.advanced && e.isActive('codeBlock'))
}

const component = getCurrentInstance()
const selectionActionsEnabled = computed(() => props.polishable || props.taskable)
function startSelectionImage() {
  const e = editor.value
  if (!e || props.readonly || !props.selectionImage) return
  const { from, to } = e.state.selection
  if (from === to) return
  selectionImageAction.value?.open(e.state.doc.textBetween(from, to, '\n').trim())
}
function insertSelectionImage(url: string) {
  const e = editor.value
  if (!e || e.isDestroyed || props.readonly) return false
  // 图像节点仍随富文本保存；小记编辑和预览把全部图像集中显示在右侧。
  return e.chain().insertContentAt(e.state.doc.content.size, { type: 'image', attrs: { src: url, alt: '选段生成图' } }).run()
}
function removeImageAt(index: number) {
  const e = editor.value
  if (!e || props.readonly || index < 0) return false
  let seen = 0
  let position = -1
  let size = 0
  e.state.doc.descendants((node, pos) => {
    if (node.type.name === 'image' && seen++ === index) { position = pos; size = node.nodeSize; return false }
    return position < 0
  })
  return position >= 0 && e.commands.deleteRange({ from: position, to: position + size })
}
let preparingTask = false
async function addSelectionTask() {
  const e = editor.value
  if (!e || props.readonly || !selectionActionsEnabled.value || preparingTask) return
  const { from, to } = e.state.selection
  const text = e.state.doc.textBetween(from, to, '\n').trim()
  if (from === to || !text) return
  preparingTask = true
  try {
    // 保留已有宿主事件扩展；没有处理器的编辑器直接使用通用待办表单。
    if (component?.vnode.props?.onAddTask) emit('add-task', text)
    else await openEditorSelectionTask(text, props.taskSource)
    e.commands.setTextSelection(to)
    e.commands.blur()
    emit('task-opened')
  } catch (cause) {
    toast(cause instanceof Error ? cause.message : '暂时无法准备待办，请重试；正文未改变', 'ri-error-warning-line')
  } finally {
    preparingTask = false
  }
}

/**
 * 把当前选区转成适合交给 AI 的文字：单段只取纯文本，多块选区保留 Markdown 结构。
 * 同时冻结选区与整篇正文，避免等待 AI 时的协同更新导致结果替换到错误位置。
 */
async function startPolish() {
  if (polishDialog.value) { polishMinimized.value = false; return }
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
  e.commands.blur()
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

const polishPreview = ref('')

async function requestPolish(dialog: PolishDialog) {
  polishPreview.value = ''
  dialog.startedAt ??= Date.now()
  polishMinimized.value = false
  dialog.requestId ??= newPolishRequestId()
  const owner = polishSession?.userId
  dialog.loading = true
  dialog.error = ''
  dialog.progress = '正在提交润色任务…'
  persistPolish(dialog)
  try {
    const result = await polishDocumentText(dialog.original, {
      requestId: dialog.requestId,
      isActive: () => polishMounted && polishDialog.value === dialog && polishSession?.userId === owner,
      onProgress: (job) => {
        polishPreview.value = job.preview ?? ''
        dialog.progress = job.message
        dialog.failed = job.status === 'FAILED'
        persistPolish(dialog)
      },
    })
    if (polishDialog.value !== dialog) return
    dialog.polished = result.polished.trim()
    if (!dialog.polished) dialog.error = 'AI 没有返回可用内容，请重试'
  } catch (cause) {
    if (polishDialog.value !== dialog) return
    polishPreview.value = ''
    dialog.error = cause instanceof Error ? cause.message : '润色失败，请重试'
  } finally {
    if (polishDialog.value === dialog) { dialog.loading = false; persistPolish(dialog) }
  }
}

async function retryPolish() {
  if (polishDialog.value) {
    // 网络错误重查同一任务；终态失败或主动重新润色才创建新任务。
    if (polishDialog.value.failed || polishDialog.value.polished) {
      polishDialog.value.requestId = newPolishRequestId()
      polishDialog.value.startedAt = Date.now()
      polishDialog.value.failed = false
    }
    await requestPolish(polishDialog.value)
  }
}

function keepOriginal() {
  persistPolish(null)
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
  persistPolish(null)
  polishDialog.value = null
}

watch([() => polishSession?.userId, () => props.taskSource?.id], () => {
  polishDialog.value = null
  void nextTick(restorePolish)
})
watch([editor, () => props.modelValue], () => { void nextTick(restorePolish) })
onBeforeUnmount(() => { polishMounted = false })

// ---- 文档链接与图片尺寸 ----

interface MenuContext {
  editor: Editor
  from: number
  to: number
  state: Editor['state']
}

interface LinkDialog {
  from: number
  to: number
  name: string
  url: string
  marks: { type: string; attrs?: Record<string, unknown> }[]
  error: string
}

const linkDialog = ref<LinkDialog | null>(null)
const linkSelectionActive = ref(false)
const imageWidthInput = ref('')
const imageHeightInput = ref('')
const imageAltInput = ref('')
const imageTitleInput = ref('')
const selectedImageScale = ref<number | null>(null)
const imageSizeError = ref('')

function syncSelectionControls(e: Editor) {
  const { from, to } = e.state.selection
  selectedText.value = from === to || e.state.selection instanceof CellSelection ? '' : e.state.doc.textBetween(from, to, '\n').trim()
  selectionCanLink.value = shouldShowLink({ editor: e, from, to, state: e.state })
  linkSelectionActive.value = e.isActive('link')
  if (!e.isActive('image')) return
  const attrs = e.getAttributes('image')
  const width = parseImageDimension(attrs.width)
  const height = parseImageDimension(attrs.height)
  selectedImageScale.value = typeof width === 'string' ? Number(width.slice(0, -1)) : null
  imageWidthInput.value = typeof width === 'number' ? String(width) : ''
  imageHeightInput.value = typeof height === 'number' ? String(height) : ''
  imageAltInput.value = typeof attrs.alt === 'string' ? attrs.alt : ''
  imageTitleInput.value = typeof attrs.title === 'string' ? attrs.title : ''
  imageSizeError.value = ''
}

/** 只给同一文本块内的选区提供链接维护，跨段选区不能成为一个 HTML 链接。 */
function shouldShowLink({ editor: e, from, to, state }: MenuContext) {
  if (!(props.linkControls || props.advanced) || props.readonly) return false
  if (state.selection instanceof CellSelection) return false
  if (e.isActive('image')) return false
  if (e.isActive('link')) return true
  return from !== to && state.doc.resolve(from).sameParent(state.doc.resolve(to))
}

function shouldShowImage({ editor: e }: MenuContext) {
  return props.imageControls && !props.readonly && e.isActive('image')
}

function openLinkEditor() {
  const e = editor.value
  if (!e) return
  if (e.isActive('link')) e.commands.extendMarkRange('link')
  const { from, to } = e.state.selection
  if (from === to || !e.state.doc.resolve(from).sameParent(e.state.doc.resolve(to))) return
  const attrs = e.getAttributes('link')
  linkDialog.value = {
    from,
    to,
    name: e.state.doc.textBetween(from, to, ''),
    url: typeof attrs.href === 'string' ? attrs.href : '',
    marks: e.state.doc
      .resolve(from)
      .marks()
      .filter((mark) => mark.type.name !== 'link')
      .map((mark) => mark.toJSON()),
    error: '',
  }
  e.commands.blur()
}

/** 补全常见裸域名，同时拒绝会执行脚本或读取本地数据的协议。 */
function normalizedLinkUrl(raw: string): string | null {
  const value = raw.trim()
  const unsafeSpacing = [...value].some((character) => {
    const code = character.charCodeAt(0)
    return /\s/u.test(character) || code < 0x20 || code === 0x7f
  })
  if (!value || unsafeSpacing) return null
  if (/^[\w.-]+\.[a-z]{2,}(?:[/:?#].*)?$/i.test(value)) return `https://${value}`
  if (/^(\/|#|\.\.?\/)/.test(value)) return value
  if (/^mailto:[^@]+@[^@]+$/i.test(value)) return value
  if (/^tel:\+?[0-9().-]+$/i.test(value)) return value
  try {
    const parsed = new URL(value)
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') return value
  } catch {
    // 不是可解析的绝对 HTTP(S) 地址。
  }
  return null
}

function saveLink() {
  const e = editor.value
  const dialog = linkDialog.value
  if (!e || !dialog) return
  const name = dialog.name.trim()
  const href = normalizedLinkUrl(dialog.url)
  if (!name) {
    dialog.error = '显示名称不能为空'
    return
  }
  if (!href) {
    dialog.error = '请输入 http(s)、邮件、电话或站内相对地址'
    return
  }
  const inserted = e
    .chain()
    .focus()
    .insertContentAt(
      { from: dialog.from, to: dialog.to },
      {
        type: 'text',
        text: name,
        marks: [
          ...dialog.marks,
          {
            type: 'link',
            attrs: { href, target: '_blank', rel: 'noopener noreferrer' },
          },
        ],
      },
    )
    .setTextSelection(dialog.from + name.length)
    .run()
  if (!inserted) {
    dialog.error = '链接没有写入，请重试'
    return
  }
  linkDialog.value = null
}

function removeActiveLink() {
  const e = editor.value
  if (!e || !e.isActive('link')) return
  e.chain().focus().extendMarkRange('link').unsetLink().run()
  linkDialog.value = null
}

function openActiveLink() {
  const href = editor.value?.getAttributes('link').href
  const safeHref = typeof href === 'string' ? normalizedLinkUrl(href) : null
  if (safeHref) window.open(safeHref, '_blank', 'noopener,noreferrer')
}

/** 可编辑区原生点击链接：阻止当前页跳转，选中整段供返回后编辑，并安全新开窗口。 */
function onEditorContentClick(event: MouseEvent) {
  const target = event.target
  // EditorContent 的外层会撑满编辑框。点击正文末尾以下的空白区域时，
  // 也要把光标交给真正的 ProseMirror 编辑节点。
  if (target === event.currentTarget && !props.readonly) {
    editor.value?.chain().focus('end').run()
    return
  }
  const link = target instanceof Element ? target.closest<HTMLAnchorElement>('a[href]') : null
  const e = editor.value
  if (!props.linkControls || !link || !e) return
  event.preventDefault()
  const href = normalizedLinkUrl(link.getAttribute('href') ?? '')
  if (!href) return
  try {
    const from = e.view.posAtDOM(link, 0)
    const to = e.view.posAtDOM(link, link.childNodes.length)
    e.chain().setTextSelection({ from, to }).extendMarkRange('link').run()
  } catch {
    // 即使第三方节点视图无法反查位置，打开链接仍是确定且安全的用户动作。
  }
  window.open(href, '_blank', 'noopener,noreferrer')
}

function setImageScale(percent: 25 | 50 | 75 | 100) {
  const e = editor.value
  if (!e?.isActive('image')) return
  e.chain().updateAttributes('image', { width: `${percent}%`, height: null }).run()
  selectedImageScale.value = percent
  imageWidthInput.value = ''
  imageHeightInput.value = ''
  imageSizeError.value = ''
}

const imageRotating = ref(false)
async function rotateSelectedImage(clockwise: boolean) {
  const e = editor.value
  if (!e?.isActive('image') || imageRotating.value) return
  const position = e.state.selection.from
  const original = e.state.doc.nodeAt(position)
  if (original?.type.name !== 'image' || typeof original.attrs.src !== 'string') return
  imageRotating.value = true
  imageSizeError.value = ''
  // Map the node through intervening typing; a late upload must never replace another image.
  let mappedPosition = position
  let removed = false
  const track = ({ transaction }: { transaction: import('@tiptap/pm/state').Transaction }) => {
    const mapped = transaction.mapping.mapResult(mappedPosition, 1)
    mappedPosition = mapped.pos
    removed ||= mapped.deleted
  }
  e.on('transaction', track)
  try {
    const file = await rotateImage(await loadRotationImage(original.attrs.src), clockwise)
    const url = await new Promise<string | null>(resolve => emit('upload-image', file, value => resolve(value)))
    if (!url) throw new Error('旋转图片上传失败，请重试；原图仍保留')
    const current = e.state.doc.nodeAt(mappedPosition)
    if (e.isDestroyed || removed || current?.type.name !== 'image' || current.attrs.src !== original.attrs.src) {
      throw new Error('原图片已被修改，本次旋转未覆盖新内容')
    }
    const attrs = { ...current.attrs, src: url, width: null, height: null }
    e.view.dispatch(e.state.tr.setNodeMarkup(mappedPosition, undefined, attrs))
  } catch (error) { imageSizeError.value = error instanceof Error ? error.message : '旋转失败，请重试' }
  finally { e.off('transaction', track); imageRotating.value = false }
}

function applyImageDimensions() {
  const e = editor.value
  if (!e?.isActive('image')) return
  const width = imageWidthInput.value.trim()
  const height = imageHeightInput.value.trim()
  const parsedWidth = width ? parseImageDimension(width) : null
  const parsedHeight = height ? parseImageDimension(height) : null
  if ((!width && !height) || (width && typeof parsedWidth !== 'number') || (height && typeof parsedHeight !== 'number')) {
    imageSizeError.value = '长宽至少填写一项，范围为 1–4000 像素'
    return
  }
  e.chain().updateAttributes('image', { width: parsedWidth, height: parsedHeight }).run()
  selectedImageScale.value = null
  imageSizeError.value = ''
}

function clearImageSize() {
  const e = editor.value
  if (!e?.isActive('image')) return
  e.chain().updateAttributes('image', { width: null, height: null }).run()
  selectedImageScale.value = null
  imageWidthInput.value = ''
  imageHeightInput.value = ''
  imageSizeError.value = ''
}

function applyImageDescription() {
  editor.value?.chain().focus().updateAttributes('image', {
    alt: imageAltInput.value.trim(),
    title: imageTitleInput.value.trim() || null,
  }).run()
}

function openSelectedImage() {
  const attrs = editor.value?.getAttributes('image')
  if (typeof attrs?.src === 'string' && attrs.src) {
    previewImage.value = { src: attrs.src, alt: typeof attrs.alt === 'string' ? attrs.alt : '' }
  }
}

// ---- 协作者行标记 ----

const shell = ref<HTMLElement | null>(null)
const imagePicker = ref<HTMLInputElement | null>(null)
const filePicker = ref<HTMLInputElement | null>(null)
const slashOpen = ref(false)
const slashMenuEl = ref<HTMLElement | null>(null)
const slashPosition = ref({ left: '12px', top: '12px', maxHeight: '360px' })
function positionSlashMenu(anchor?: HTMLElement) {
  const e = editor.value
  if (!e) return
  let rect
  try { rect = anchor?.getBoundingClientRect() ?? e.view.coordsAtPos(e.state.selection.from) }
  catch { rect = shell.value?.getBoundingClientRect() }
  if (!rect) return
  const width = Math.min(340, window.innerWidth - 24)
  const below = window.innerHeight - rect.bottom - 20
  const above = rect.top - 20
  const height = Math.max(48, Math.min(360, Math.max(below, above)))
  slashPosition.value = {
    left: `${Math.max(12, Math.min(rect.left, window.innerWidth - width - 12))}px`,
    top: `${Math.max(12, below >= height ? rect.bottom + 8 : rect.top - height - 8)}px`,
    maxHeight: `${height}px`,
  }
}
function dismissSlashMenu(event: Event) {
  if (!slashOpen.value) return
  const target = event.target as Node | null
  if (target && slashMenuEl.value?.contains(target)) return
  if (event.type === 'pointerdown' && target && (shell.value?.contains(target) || props.toolbarTarget?.contains(target))) return
  slashOpen.value = false
}

const slashFromInput = ref(false)
const slashQuery = ref('')
const slashIndex = ref(0)
watch(slashIndex, () => nextTick(() => slashMenuEl.value?.querySelector('.active')?.scrollIntoView?.({ block: 'nearest' })))
const composing = ref(false)
const findOpen = ref(false)
const helpOpen = ref(false)
const findText = ref('')
const replaceText = ref('')
const findStatus = ref('')
const drawingsOpen = ref(false)
const drawingsLoading = ref(false)
const drawingsError = ref('')
const drawings = ref<DrawingFileSummary[]>([])
interface PendingUpload { id: string; file: File; state: 'uploading' | 'failed' }
const pendingUploads = ref<PendingUpload[]>([])
const COLOR_OPTIONS = ['#1f2329', '#646a73', '#d83931', '#de7802', '#2ea121', '#245bdb', '#6425d0']
const HIGHLIGHT_OPTIONS = ['#fde2e2', '#feead2', '#d9f5d6', '#dbeafe', '#ede2fe']
const CODE_LANGUAGES = ['plaintext', 'bash', 'json', 'yaml', 'java', 'javascript', 'typescript', 'html', 'css', 'sql', 'python', 'markdown']

interface SlashAction {
  id: string
  label: string
  hint: string
  icon: string
  run: () => void
  full?: boolean
}

function insertBlock(content: Record<string, unknown>) {
  editor.value?.chain().focus().insertContent(content).run()
}

function insertCallout(kind = 'info') {
  insertBlock({ type: 'callout', attrs: { kind }, content: [{ type: 'paragraph' }] })
}

function insertColumns(count: 2 | 3) {
  insertBlock({
    type: 'columns',
    content: Array.from({ length: count }, () => ({
      type: 'column', content: [{ type: 'paragraph' }],
    })),
  })
}

function insertDetails() {
  insertBlock({
    type: 'details',
    content: [
      { type: 'detailsSummary', content: [{ type: 'text', text: '折叠标题' }] },
      { type: 'detailsContent', content: [{ type: 'paragraph' }] },
    ],
  })
}

function insertMath(block: boolean) {
  const latex = window.prompt(block ? '输入块级 LaTeX 公式' : '输入行内 LaTeX 公式', 'E = mc^2')
  if (latex == null) return
  if (block) editor.value?.chain().focus().insertBlockMath({ latex }).run()
  else editor.value?.chain().focus().insertInlineMath({ latex }).run()
}

function insertMermaid(type: 'flowchart' | 'sequence' | 'mindmap' = 'flowchart') {
  const source = type === 'sequence'
    ? 'sequenceDiagram\n  participant A as 用户\n  participant B as WAO\n  A->>B: 发起请求\n  B-->>A: 返回结果'
    : type === 'mindmap'
      ? 'mindmap\n  root((主题))\n    分支一\n    分支二'
      : 'flowchart TD\n  A[开始] --> B{判断}\n  B -->|是| C[完成]\n  B -->|否| D[继续]'
  insertBlock({ type: 'mermaid', attrs: { source } })
}

function insertTable() {
  editor.value?.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()
}

function insertLinkCard() {
  const raw = window.prompt('输入 HTTPS 或 WAO 站内地址')?.trim()
  if (!raw) return
  const url = normalizedLinkUrl(raw)
  if (!url || (!url.startsWith('https://') && !url.startsWith('/'))) return
  const title = window.prompt('卡片显示名称', raw)?.trim() || raw
  let host = url
  try { host = new URL(url, window.location.origin).host } catch { /* 站内相对地址 */ }
  insertBlock({ type: 'linkCard', attrs: { title, url, name: host } })
}

function openDrawings() {
  drawingsOpen.value = true
  drawingsError.value = ''
  drawingsLoading.value = true
  void fetchDrawings()
    .then((items) => (drawings.value = items))
    .catch(() => (drawingsError.value = '画板列表加载失败，请重试'))
    .finally(() => (drawingsLoading.value = false))
}

async function createDrawingSnapshot(item: DrawingFileSummary): Promise<string> {
  try {
    const drawing = await fetchDrawing(item.id)
    const scene = JSON.parse(drawing.sceneJson) as { elements?: unknown[]; appState?: Record<string, unknown>; viewBackgroundColor?: string; files?: Record<string, unknown> }
    if (!scene.elements?.length) return ''
    const { exportToBlob } = await import('@excalidraw/excalidraw')
    const blob = await exportToBlob({
      elements: scene.elements as never,
      appState: { ...(scene.appState ?? {}), viewBackgroundColor: scene.viewBackgroundColor ?? scene.appState?.viewBackgroundColor ?? '#ffffff', exportBackground: true } as never,
      files: (scene.files ?? {}) as never,
      mimeType: 'image/png',
    })
    const file = new File([blob], `${item.title || '画板'}.png`, { type: 'image/png' })
    return await new Promise<string>((resolve) => {
      emit('upload-image', file, (url) => resolve(url ?? ''))
    })
  } catch {
    return ''
  }
}

async function insertDrawing(item: DrawingFileSummary) {
  drawingsLoading.value = true
  const snapshot = await createDrawingSnapshot(item)
  insertBlock({
    type: 'drawingCard',
    attrs: { id: item.id, title: item.title, snapshot, url: `/drawings?drawing=${encodeURIComponent(item.id)}` },
  })
  drawingsLoading.value = false
  drawingsOpen.value = false
}

async function createAndInsertDrawing() {
  drawingsLoading.value = true
  drawingsError.value = ''
  try {
    const item = await createDrawing('文档画板')
    await insertDrawing(item)
  } catch {
    drawingsError.value = '新建画板失败，请重试'
  } finally {
    drawingsLoading.value = false
  }
}

function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  for (const file of files) {
    const upload: PendingUpload = { id: crypto.randomUUID(), file, state: 'uploading' }
    pendingUploads.value.push(upload)
    runFileUpload(upload.id)
  }
}

function runFileUpload(uploadId: string) {
  const upload = pendingUploads.value.find((item) => item.id === uploadId)
  if (!upload) return
  upload.state = 'uploading'
  emit('upload-file', upload.file, (item) => {
    if (!item) {
      upload.state = 'failed'
      return
    }
    const mime = item.contentType || upload.file.type || 'application/octet-stream'
    const media = mime.startsWith('audio/') || mime.startsWith('video/')
    const id = item.id ?? ''
    insertBlock({
      type: media ? 'mediaCard' : 'fileCard',
      attrs: {
        id,
        name: item.displayName || item.name,
        title: item.displayName || item.name,
        size: item.size,
        mime,
        url: media && id ? `/api/files/${encodeURIComponent(id)}/stream` : item.link,
      },
    })
    pendingUploads.value = pendingUploads.value.filter((entry) => entry.id !== uploadId)
  })
}

function clearFormatting() {
  editor.value?.chain().focus().unsetAllMarks().clearNodes().run()
}

function runFind(next = true) {
  const needle = findText.value
  const e = editor.value
  if (!needle || !e) {
    findStatus.value = ''
    return
  }
  const matches: number[] = []
  e.state.doc.descendants((node, pos) => {
    if (!node.isText || !node.text) return
    let offset = node.text.indexOf(needle)
    while (offset >= 0) {
      matches.push(pos + offset)
      offset = node.text.indexOf(needle, offset + Math.max(1, needle.length))
    }
  })
  const start = next ? e.state.selection.to : 0
  const from = matches.find((position) => position >= start) ?? matches[0]
  if (from == null) {
    findStatus.value = '未找到'
    return
  }
  const to = Math.min(e.state.doc.content.size, from + needle.length)
  e.chain().focus().setTextSelection({ from, to }).scrollIntoView().run()
  findStatus.value = `${matches.indexOf(from) + 1} / ${matches.length}`
}

function replaceCurrent() {
  const e = editor.value
  if (!e || !findText.value) return
  const { from, to } = e.state.selection
  if (e.state.doc.textBetween(from, to, '\n') === findText.value) {
    e.chain().focus().insertContent(replaceText.value).run()
  }
  runFind()
}

const slashActions = computed<SlashAction[]>(() => [
  { id: 'h1', label: '一级标题', hint: '章节主标题', icon: 'ri-h-1', run: () => editor.value?.chain().focus().setHeading({ level: 1 }).run() },
  { id: 'h2', label: '二级标题', hint: '章节标题', icon: 'ri-h-2', run: () => editor.value?.chain().focus().setHeading({ level: 2 }).run() },
  { id: 'h3', label: '三级标题', hint: '子章节标题', icon: 'ri-h-3', run: () => editor.value?.chain().focus().setHeading({ level: 3 }).run() },
  { id: 'h4', label: '四级标题', hint: '细分章节标题', icon: 'ri-h-4', run: () => editor.value?.chain().focus().setHeading({ level: 4 }).run() },
  { id: 'paragraph', label: '正文', hint: '普通段落', icon: 'ri-text', run: () => editor.value?.chain().focus().setParagraph().run() },
  { id: 'bullet', label: '无序列表', hint: '项目清单', icon: 'ri-list-unordered', run: () => editor.value?.chain().focus().toggleBulletList().run() },
  { id: 'number', label: '有序列表', hint: '步骤清单', icon: 'ri-list-ordered-2', run: () => editor.value?.chain().focus().toggleOrderedList().run() },
  { id: 'task', label: '任务列表', hint: '可勾选事项', icon: 'ri-list-check-3', run: () => editor.value?.chain().focus().toggleTaskList().run() },
  { id: 'quote', label: '引用', hint: '引用一段文字', icon: 'ri-double-quotes-l', run: () => editor.value?.chain().focus().toggleBlockquote().run() },
  { id: 'callout-info', label: '信息提示块', hint: '补充说明与背景', icon: 'ri-information-line', run: () => insertCallout('info'), full: true },
  { id: 'callout-success', label: '成功提示块', hint: '已完成或推荐做法', icon: 'ri-checkbox-circle-line', run: () => insertCallout('success'), full: true },
  { id: 'callout-warning', label: '警告提示块', hint: '风险与注意事项', icon: 'ri-alert-line', run: () => insertCallout('warning'), full: true },
  { id: 'callout-danger', label: '危险提示块', hint: '高风险或禁止事项', icon: 'ri-error-warning-line', run: () => insertCallout('danger'), full: true },
  { id: 'table', label: '表格', hint: '3 × 3 可调整表格', icon: 'ri-table-2', run: insertTable, full: true },
  { id: 'code', label: '代码块', hint: '带语言与高亮', icon: 'ri-code-box-line', run: () => editor.value?.chain().focus().toggleCodeBlock().run(), full: true },
  { id: 'math-inline', label: '行内公式', hint: 'LaTeX 行内公式', icon: 'ri-functions', run: () => insertMath(false), full: true },
  { id: 'math-block', label: '块级公式', hint: '独立展示 LaTeX 公式', icon: 'ri-functions', run: () => insertMath(true), full: true },
  { id: 'diagram-flowchart', label: '流程图', hint: 'Mermaid 流程可视化', icon: 'ri-flow-chart', run: () => insertMermaid('flowchart'), full: true },
  { id: 'diagram-sequence', label: '时序图', hint: 'Mermaid 交互时序', icon: 'ri-git-branch-line', run: () => insertMermaid('sequence'), full: true },
  { id: 'diagram-mindmap', label: '思维导图', hint: 'Mermaid 层级脑图', icon: 'ri-node-tree', run: () => insertMermaid('mindmap'), full: true },
  { id: 'columns-2', label: '双栏', hint: '窄屏自动纵向排列', icon: 'ri-layout-column-line', run: () => insertColumns(2), full: true },
  { id: 'columns-3', label: '三栏', hint: '三列内容，窄屏自动纵排', icon: 'ri-layout-grid-line', run: () => insertColumns(3), full: true },
  { id: 'details', label: '折叠块', hint: '收起补充内容', icon: 'ri-arrow-right-s-line', run: insertDetails, full: true },
  { id: 'image', label: '图片', hint: '上传、粘贴或拖入', icon: 'ri-image-add-line', run: chooseImage },
  { id: 'file', label: '附件 / 音视频', hint: '上传到 WAO 文件库', icon: 'ri-attachment-2', run: () => filePicker.value?.click(), full: true },
  { id: 'link-card', label: '链接卡片', hint: '安全展示外部地址', icon: 'ri-links-line', run: insertLinkCard, full: true },
  { id: 'drawing', label: '画板', hint: '引用个人 Excalidraw 草图', icon: 'ri-shape-line', run: openDrawings, full: true },
  { id: 'divider', label: '分隔线', hint: '分隔章节', icon: 'ri-separator', run: () => editor.value?.chain().focus().setHorizontalRule().run() },
].filter((action) => (props.advanced && !props.compact) || !action.full))

const filteredSlashActions = computed(() => {
  const query = slashQuery.value.trim().toLowerCase()
  return slashActions.value.filter((item) => !query || `${item.label} ${item.hint} ${item.id}`.toLowerCase().includes(query))
})

function updateSlashMenu(e: Editor) {
  if (!props.advanced || composing.value) return
  const { $from } = e.state.selection
  const text = $from.parent.textBetween(0, $from.parentOffset, undefined, '\ufffc')
  const match = /^\/([^\s/]*)$/.exec(text)
  if (match) positionSlashMenu()
  slashOpen.value = !!match
  slashFromInput.value = !!match
  slashQuery.value = match?.[1] ?? ''
  slashIndex.value = 0
}

function chooseSlash(action: SlashAction) {
  const e = editor.value
  if (!e) return
  if (slashFromInput.value) {
    const { $from } = e.state.selection
    const from = $from.start()
    e.chain().focus().deleteRange({ from, to: e.state.selection.from }).run()
  }
  slashOpen.value = false
  slashFromInput.value = false
  action.run()
}

function toggleInsertMenu(event?: MouseEvent) {
  positionSlashMenu(event?.currentTarget as HTMLElement | undefined)
  slashFromInput.value = false
  slashQuery.value = ''
  slashIndex.value = 0
  slashOpen.value = !slashOpen.value
}

function onEditorKeydown(event: KeyboardEvent) {
  if (event.isComposing || composing.value) return
  if (event.key === 'Escape') {
    closeTableMenu()
    hideSelectionMenu()
    slashOpen.value = false
    findOpen.value = false
    helpOpen.value = false
    return
  }
  if ((event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) && editor.value?.isActive('table')) {
    const cell = editor.value.view.domAtPos(editor.value.state.selection.from).node
    const element = (cell instanceof Element ? cell : cell.parentElement)?.closest('td, th')
    if (element) {
      event.preventDefault()
      const rect = element.getBoundingClientRect()
      const context = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: rect.left, clientY: rect.bottom })
      element.dispatchEvent(context)
    }
    return
  }
  if ((event.ctrlKey || event.metaKey) && event.key === '/' && props.advanced) {
    event.preventDefault()
    helpOpen.value = !helpOpen.value
    return
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && props.linkControls) {
    event.preventDefault()
    openLinkEditor()
    return
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'f' && props.advanced) {
    event.preventDefault()
    findOpen.value = true
    nextTick(() => shell.value?.querySelector<HTMLInputElement>('.find-panel input')?.focus())
    return
  }
  if (!slashOpen.value || !filteredSlashActions.value.length) return
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    const delta = event.key === 'ArrowDown' ? 1 : -1
    slashIndex.value = (slashIndex.value + delta + filteredSlashActions.value.length) % filteredSlashActions.value.length
  } else if (event.key === 'Enter') {
    event.preventDefault()
    chooseSlash(filteredSlashActions.value[slashIndex.value]!)
  } else if (event.key === 'Escape') {
    event.preventDefault()
    slashOpen.value = false
  }
}

function setBlockType(value: string) {
  const chain = editor.value?.chain().focus()
  if (!chain) return
  if (value === 'paragraph') chain.setParagraph().run()
  else chain.setHeading({ level: Number(value.slice(1)) as 1 | 2 | 3 | 4 }).run()
}

function setCodeLanguage(event: Event) {
  const language = (event.target as HTMLSelectElement).value
  editor.value?.chain().focus().updateAttributes('codeBlock', { language }).run()
}

async function copyCodeBlock() {
  const e = editor.value
  if (!e) return
  const { $from } = e.state.selection
  for (let depth = $from.depth; depth > 0; depth--) {
    const node = $from.node(depth)
    if (node.type.name === 'codeBlock') {
      await navigator.clipboard.writeText(node.textContent)
      return
    }
  }
}

function toggleLinkFromToolbar() {
  if (editor.value?.isActive('link')) openLinkEditor()
  else openLinkEditor()
}

function duplicateSelectedBlock() {
  const e = editor.value
  if (!e) return
  const { $from } = e.state.selection
  const depth = Math.max(1, $from.depth)
  const node = $from.node(depth)
  const pos = $from.before(depth)
  e.chain().focus().insertContentAt(pos + node.nodeSize, node.toJSON()).run()
}

function deleteSelectedBlock() {
  const e = editor.value
  if (!e) return
  const { $from } = e.state.selection
  const depth = Math.max(1, $from.depth)
  const node = $from.node(depth)
  const from = $from.before(depth)
  e.chain().focus().deleteRange({ from, to: from + node.nodeSize }).run()
}
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
  document.addEventListener('pointerdown', dismissSlashMenu, true)
  document.addEventListener('scroll', dismissSlashMenu, true)
  window.addEventListener('resize', dismissSlashMenu)
  document.addEventListener('pointerdown', dismissTableMenu, true)
  document.addEventListener('scroll', dismissTableMenu, true)
  window.addEventListener('resize', dismissTableMenu)
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

/** 将总览图放在当前正文最前面；重复生成时替换上一张总览图。 */
function insertArticleImageAtStart(url: string): boolean {
  const e = editor.value
  if (!e || props.readonly || !/^\/api\/images\/[A-Za-z0-9_-]+\/raw$/.test(url)) return false
  const first = e.state.doc.firstChild
  const range = first?.type.name === 'image' && first.attrs.alt === '文章总览图'
    ? { from: 0, to: first.nodeSize } : 0
  return e.commands.insertContentAt(range, { type: 'image', attrs: { src: url, alt: '文章总览图', width: '50%', height: null, align: 'center' } })
}

function currentArticleImage(): string | null {
  const first = editor.value?.state.doc.firstChild
  return first?.type.name === 'image' && first.attrs.alt === '文章总览图' ? String(first.attrs.src) : null
}

function articleText(): string { return editor.value?.state.doc.textContent ?? '' }

/**
 * 外部值回灌 —— 只在编辑器没有焦点时做。
 *
 * <p>等于自己刚发出的内容时不动，否则光标会跳。**正在输入时也一律不灌**：语音听写会持续
 * 往同一个位置追加文本，中途被 setContent 换掉整篇内容，听写引擎会把这一段从头再念一遍，
 * 结果就是内容重复。错过的外部更新记在 {@link pendingExternal}，等失焦再补上。
 */
let pendingExternal: string | null = null
let pendingExternalDocument: RichDocNode | null = null
function applyExternal(next: string) {
  const e = editor.value
  if (!e || next === e.getMarkdown()) return
  if (e.isFocused) {
    pendingExternal = next
    return
  }
  pendingExternal = null
  e.commands.setContent(next, { contentType: 'markdown', emitUpdate: false })
}
function applyExternalDocument(next: RichDocNode | null) {
  const e = editor.value
  if (!e || !next || JSON.stringify(next) === JSON.stringify(e.getJSON())) return
  if (e.isFocused) {
    pendingExternalDocument = next
    return
  }
  pendingExternalDocument = null
  e.commands.setContent(next, { emitUpdate: false })
}
/** 失焦时把输入期间错过的外部更新补灌回来。 */
function flushExternal() {
  const nextDocument = pendingExternalDocument
  pendingExternalDocument = null
  if (nextDocument && editor.value && JSON.stringify(nextDocument) !== JSON.stringify(editor.value.getJSON())) {
    editor.value.commands.setContent(nextDocument, { emitUpdate: false })
    pendingExternal = null
    return
  }
  const next = pendingExternal
  pendingExternal = null
  if (next != null && editor.value && next !== editor.value.getMarkdown()) {
    editor.value.commands.setContent(next, { contentType: 'markdown', emitUpdate: false })
  }
}
/** Explicit send acknowledgement; never discard text typed while the request was pending. */
function clearSentContent(expected: string): boolean {
  const e = editor.value
  if (!e || e.getMarkdown() !== expected) return false
  pendingExternal = null
  pendingExternalDocument = null
  e.commands.clearContent(true)
  return true
}
/** 将用户主动插入的附件或语音追加到最新内容，避免聚焦时后台回灌延迟。 */
function appendMarkdown(text: string): boolean {
  const e = editor.value
  if (!e) return false
  return e.commands.insertContentAt(e.state.doc.content.size, text, { contentType: 'markdown' })
}
async function copyFormatted() {
  const e = editor.value
  if (!e) return
  const ok = await copyRichContent(e.getHTML(), e.getMarkdown())
  toast(ok ? '已复制，支持富文本与 Markdown 粘贴' : '复制失败，请手动选择复制', ok ? 'ri-file-copy-line' : 'ri-error-warning-line')
}
defineExpose({ clearSentContent, appendMarkdown, copyFormatted, removeImageAt })
watch(() => props.modelValue, applyExternal)
watch(() => props.document, applyExternalDocument, { deep: true })
watch(
  () => props.readonly,
  // 只读切换不是正文修改；不能回发 content-change 把父组件的保存中状态重置为 idle。
  (ro) => { closeTableMenu(); editor.value?.setEditable(!ro, false) },
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', scheduleMeasure)
  document.removeEventListener('pointerdown', dismissSlashMenu, true)
  document.removeEventListener('scroll', dismissSlashMenu, true)
  window.removeEventListener('resize', dismissSlashMenu)
  document.removeEventListener('pointerdown', dismissTableMenu, true)
  document.removeEventListener('scroll', dismissTableMenu, true)
  window.removeEventListener('resize', dismissTableMenu)
  editor.value?.destroy()
})
</script>

<template>
  <div ref="shell" class="md" :class="{ ro: readonly, collab: peers.length > 0, bare }" @contextmenu="openImageMenu">
    <ImageCopyMenu v-if="imageMenu" v-bind="imageMenu" @close="imageMenu = null" />
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
    <input
      ref="filePicker"
      class="image-picker"
      type="file"
      multiple
      tabindex="-1"
      aria-hidden="true"
      @change="onFilePicked"
    />
    <Teleport :to="toolbarTarget || 'body'" :disabled="!toolbarTarget">
    <div v-if="advanced && !hideToolbar && !readonly && editor" class="editor-tools" :class="{ docked: toolbarTarget }">
      <button v-if="!toolbarTarget" type="button" class="toolbar-toggle" :aria-expanded="toolbarOpen" :title="toolbarOpen ? '收起编辑工具' : '展开编辑工具'" @mousedown.prevent @click="toolbarOpen = !toolbarOpen">
        <i class="ri-equalizer-line" aria-hidden="true"></i>
        <span>编辑工具</span>
        <i :class="toolbarOpen ? 'ri-arrow-up-s-line' : 'ri-arrow-down-s-line'" aria-hidden="true"></i>
      </button>
    <div v-show="toolbarTarget || toolbarOpen" class="advanced-toolbar" role="toolbar" aria-label="文档格式工具栏">
      <button type="button" title="撤销" :disabled="!editor.can().undo()" @click="editor.chain().focus().undo().run()"><i class="ri-arrow-go-back-line"></i></button>
      <button type="button" title="重做" :disabled="!editor.can().redo()" @click="editor.chain().focus().redo().run()"><i class="ri-arrow-go-forward-line"></i></button>
      <span class="tool-sep"></span>
      <select aria-label="段落样式" :value="editor.isActive('heading', { level: 1 }) ? 'h1' : editor.isActive('heading', { level: 2 }) ? 'h2' : editor.isActive('heading', { level: 3 }) ? 'h3' : editor.isActive('heading', { level: 4 }) ? 'h4' : 'paragraph'" @change="setBlockType(($event.target as HTMLSelectElement).value)">
        <option value="paragraph">正文</option><option value="h1">一级标题</option><option value="h2">二级标题</option><option value="h3">三级标题</option><option value="h4">四级标题</option>
      </select>
      <button type="button" title="粗体" :class="{ active: editor.isActive('bold') }" @click="editor.chain().focus().toggleBold().run()"><i class="ri-bold"></i></button>
      <button type="button" title="斜体" :class="{ active: editor.isActive('italic') }" @click="editor.chain().focus().toggleItalic().run()"><i class="ri-italic"></i></button>
      <button type="button" title="下划线" :class="{ active: editor.isActive('underline') }" @click="editor.chain().focus().toggleUnderline().run()"><i class="ri-underline"></i></button>
      <button type="button" title="删除线" :class="{ active: editor.isActive('strike') }" @click="editor.chain().focus().toggleStrike().run()"><i class="ri-strikethrough"></i></button>
      <button type="button" title="行内代码" :class="{ active: editor.isActive('code') }" @click="editor.chain().focus().toggleCode().run()"><i class="ri-code-line"></i></button>
      <details class="tool-palette"><summary title="文字颜色"><i class="ri-font-color"></i></summary><div><button v-for="color in COLOR_OPTIONS" :key="color" type="button" :style="{ background: color }" :aria-label="`文字颜色 ${color}`" @click="editor.chain().focus().setColor(color).run()"></button></div></details>
      <details class="tool-palette"><summary title="高亮颜色"><i class="ri-mark-pen-line"></i></summary><div><button v-for="color in HIGHLIGHT_OPTIONS" :key="color" type="button" :style="{ background: color }" :aria-label="`高亮颜色 ${color}`" @click="editor.chain().focus().toggleHighlight({ color }).run()"></button></div></details>
      <span class="tool-sep"></span>
      <button type="button" title="左对齐" :class="{ active: editor.isActive({ textAlign: 'left' }) }" @click="editor.chain().focus().setTextAlign('left').run()"><i class="ri-align-left"></i></button>
      <button type="button" title="居中" :class="{ active: editor.isActive({ textAlign: 'center' }) }" @click="editor.chain().focus().setTextAlign('center').run()"><i class="ri-align-center"></i></button>
      <button type="button" title="右对齐" :class="{ active: editor.isActive({ textAlign: 'right' }) }" @click="editor.chain().focus().setTextAlign('right').run()"><i class="ri-align-right"></i></button>
      <button type="button" title="无序列表" :class="{ active: editor.isActive('bulletList') }" @click="editor.chain().focus().toggleBulletList().run()"><i class="ri-list-unordered"></i></button>
      <button type="button" title="有序列表" :class="{ active: editor.isActive('orderedList') }" @click="editor.chain().focus().toggleOrderedList().run()"><i class="ri-list-ordered-2"></i></button>
      <button type="button" title="任务列表" :class="{ active: editor.isActive('taskList') }" @click="editor.chain().focus().toggleTaskList().run()"><i class="ri-list-check-3"></i></button>
      <button type="button" title="链接" @click="toggleLinkFromToolbar"><i class="ri-link"></i></button>
      <button type="button" title="图片" @click="chooseImage"><i class="ri-image-add-line"></i></button>
      <button v-if="!compact" type="button" title="表格" @click="insertTable"><i class="ri-table-2"></i></button>
      <button v-if="editor.isActive('table')" type="button" title="删除整个表格（可撤销）" aria-label="删除当前表格" @mousedown.prevent @click="runTableAction('deleteTable')"><i class="ri-delete-bin-line" aria-hidden="true"></i><span>删除表格</span></button>
      <button type="button" title="更多内容块（输入 / 也可打开）" @click="toggleInsertMenu"><i class="ri-add-circle-line"></i></button>
      <button type="button" title="查找替换（Ctrl/⌘+F）" @click="findOpen = !findOpen"><i class="ri-search-line"></i></button>
      <button type="button" title="清除格式" @click="clearFormatting"><i class="ri-format-clear"></i></button>
      <button type="button" title="快捷键帮助（Ctrl/⌘+/）" @click="helpOpen = true"><i class="ri-question-line"></i></button>
      <template v-if="$slots['toolbar-options']"><span class="tool-sep"></span><slot name="toolbar-options" /></template>
    </div>
    </div>
    </Teleport>
    <form v-if="advanced && findOpen" class="find-panel" @submit.prevent="runFind()">
      <input v-model="findText" placeholder="查找" aria-label="查找内容" />
      <input v-model="replaceText" placeholder="替换为" aria-label="替换内容" />
      <button type="submit">下一个</button><button type="button" @click="replaceCurrent">替换</button>
      <span>{{ findStatus }}</span><button type="button" aria-label="关闭查找替换" @click="findOpen = false"><i class="ri-close-line"></i></button>
    </form>
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
      <!-- BubbleMenu 会把自己的根节点移出原位置；保留 Vue 管理的外壳，
           避免保存时切换 readonly 后 v-if 在已脱离 DOM 的节点上插入占位符。 -->
      <div v-if="editor && (advanced || linkControls || polishable || taskable || selectionImage) && !readonly" class="bubble-menu-anchor">
        <BubbleMenu
          :editor="editor"
          plugin-key="documentSelectionMenu"
          style="z-index: 40"
          :append-to="selectionMenuParent"
          :should-show="shouldShowSelection"
          :options="{ strategy: 'fixed', placement: 'top', offset: 8, flip: true, shift: { padding: 8 } }"
        >
          <div ref="selectionMenuEl" class="selection-menu" role="toolbar" :aria-label="multipleCells(editor) ? '选中单元格操作' : '选中文字操作'" @keydown.esc="hideSelectionMenu(); editor.commands.focus()">
            <button type="button" title="添加到待办" aria-label="添加到待办" v-if="selectionActionsEnabled && selectedText" class="selection-task" @mousedown.prevent @click="addSelectionTask"><i class="ri-checkbox-circle-line" aria-hidden="true"></i></button>
            <button type="button" title="润色选中文字" aria-label="润色选中文字" v-if="selectionActionsEnabled && selectedText" @mousedown.prevent @click="startPolish"><i class="ri-magic-line" aria-hidden="true"></i></button>
            <button type="button" class="selection-generate" title="根据选中文字生图" aria-label="生图" v-if="selectionImage && selectedText" @mousedown.prevent @click="startSelectionImage"><i class="ri-image-add-line" aria-hidden="true"></i><span>生图</span></button>
            <button v-if="selectionCanLink" type="button" :title="linkSelectionActive ? '编辑链接' : '添加链接'" :aria-label="linkSelectionActive ? '编辑链接' : '添加链接'" @mousedown.prevent @click="openLinkEditor"><i class="ri-link" aria-hidden="true"></i></button>
            <button type="button" title="在新窗口打开链接" aria-label="在新窗口打开链接" v-if="selectionCanLink && linkSelectionActive" @mousedown.prevent @click="openActiveLink"><i class="ri-external-link-line" aria-hidden="true"></i></button>
            <button type="button" title="移除链接" aria-label="移除链接" v-if="selectionCanLink && linkSelectionActive" @mousedown.prevent @click="removeActiveLink"><i class="ri-link-unlink-m" aria-hidden="true"></i></button>
            <template v-if="advanced && selectedText"><span class="selection-divider" aria-hidden="true"></span>
            <button type="button" title="粗体" aria-label="粗体" :aria-pressed="editor.isActive('bold')" @mousedown.prevent @click="editor.chain().focus().toggleBold().run()"><i class="ri-bold" aria-hidden="true"></i></button>
            <button type="button" title="斜体" aria-label="斜体" :aria-pressed="editor.isActive('italic')" @mousedown.prevent @click="editor.chain().focus().toggleItalic().run()"><i class="ri-italic" aria-hidden="true"></i></button>
            <button type="button" title="下划线" aria-label="下划线" :aria-pressed="editor.isActive('underline')" @mousedown.prevent @click="editor.chain().focus().toggleUnderline().run()"><i class="ri-underline" aria-hidden="true"></i></button>
            <button type="button" title="删除线" aria-label="删除线" :aria-pressed="editor.isActive('strike')" @mousedown.prevent @click="editor.chain().focus().toggleStrike().run()"><i class="ri-strikethrough" aria-hidden="true"></i></button>
            <button type="button" title="行内代码" aria-label="行内代码" :aria-pressed="editor.isActive('code')" @mousedown.prevent @click="editor.chain().focus().toggleCode().run()"><i class="ri-code-line" aria-hidden="true"></i></button>
            <button type="button" class="selection-highlight" title="高亮背景颜色" aria-label="高亮背景颜色" :aria-pressed="editor.isActive('highlight')" @mousedown.prevent @click="editor.chain().focus().toggleHighlight({ color: '#fde2e2' }).run()"><i class="ri-mark-pen-line" aria-hidden="true"></i></button>
            <button type="button" title="清除格式" aria-label="清除格式"  @mousedown.prevent @click="clearFormatting"><i class="ri-format-clear" aria-hidden="true"></i></button>
            </template>
            <template v-if="advanced && multipleCells(editor)"><span class="selection-divider" aria-hidden="true"></span>
            <button v-for="action in tableActions" :key="action.command" type="button" :title="action.label" :aria-label="action.label" :disabled="!editor.can()[action.command]()" @mousedown.prevent @click="runTableAction(action.command)"><i :class="action.icon" aria-hidden="true"></i></button>
            </template>
            <template v-if="advanced && editor.isActive('codeBlock')"><span class="selection-divider" aria-hidden="true"></span>
              <label class="selection-language" title="选择代码语言"><i class="ri-code-box-line" aria-hidden="true"></i><select aria-label="代码语言" :value="editor.getAttributes('codeBlock').language || 'plaintext'" @change="setCodeLanguage"><option v-for="language in CODE_LANGUAGES" :key="language" :value="language">{{ language }}</option></select></label>
            <button type="button" title="复制代码" aria-label="复制代码"  @mousedown.prevent @click="copyCodeBlock"><i class="ri-file-copy-line" aria-hidden="true"></i></button>
            </template>
          </div>
        </BubbleMenu>
      </div>
      <div v-if="editor && imageControls" class="bubble-menu-anchor">
        <BubbleMenu
          :editor="editor"
          plugin-key="documentImageMenu"
          :should-show="shouldShowImage"
          :options="{ placement: 'bottom', offset: 8 }"
        >
          <form class="image-size-toolbar" aria-label="调整图片大小" @submit.prevent="applyImageDimensions">
            <div class="image-scale-options">
              <button
                v-for="percent in IMAGE_SCALES"
                :key="percent"
                type="button"
                :class="{ active: selectedImageScale === percent }"
                @click="setImageScale(percent)"
              >
                {{ percent }}%
              </button>
            </div>
            <div class="image-pixel-fields">
              <label>宽 <input v-model="imageWidthInput" type="number" min="1" max="4000" inputmode="numeric" aria-label="图片宽度（像素）" /></label>
              <span>×</span>
              <label>高 <input v-model="imageHeightInput" type="number" min="1" max="4000" inputmode="numeric" aria-label="图片高度（像素）" /></label>
              <button type="submit">应用</button>
            </div>
            <div class="image-description-fields">
              <label>替代文字<input v-model="imageAltInput" maxlength="300" placeholder="为无法查看图片的人说明内容" /></label>
              <label>图片标题<input v-model="imageTitleInput" maxlength="300" placeholder="可选" /></label>
              <button type="button" @click="applyImageDescription">应用说明</button>
            </div>
            <div class="image-align-actions">
              <span>对齐</span>
              <button type="button" @click="editor?.chain().focus().updateAttributes('image', { align: 'left' }).run()">左</button>
              <button type="button" @click="editor?.chain().focus().updateAttributes('image', { align: 'center' }).run()">中</button>
              <button type="button" @click="editor?.chain().focus().updateAttributes('image', { align: 'right' }).run()">右</button>
            </div>
            <div v-if="imageSizeError" class="image-size-error" role="alert">{{ imageSizeError }}</div>
            <div class="image-size-actions">
              <button type="button" :disabled="imageRotating" @click="rotateSelectedImage(false)">向左旋转 90°</button>
              <button type="button" :disabled="imageRotating" @click="rotateSelectedImage(true)">{{ imageRotating ? '正在旋转并上传…' : '向右旋转 90°' }}</button>
              <button type="button" @click="clearImageSize">原始大小</button>
              <button type="button" @click="openSelectedImage"><i class="ri-zoom-in-line"></i>查看原图</button>
            </div>
          </form>
        </BubbleMenu>
      </div>
      <EditorContent
        :editor="editor"
        class="md-body"
        @contextmenu="openTableMenu"
        @mousedown.capture="preserveTableSelection"
        :style="{ minHeight: minHeight + 'px' }"
        @keydown.capture="onEditorKeydown"
        @compositionstart="composing = true"
        @compositionend="composing = false; editor && updateSlashMenu(editor)"
        @click="onEditorContentClick"
      />
      <DragHandle v-if="editor && advanced && !readonly" :editor="editor" class="block-drag-handle">
        <i class="ri-draggable"></i>
        <div class="block-actions">
          <button type="button" @click="duplicateSelectedBlock">复制块</button>
          <button type="button" @click="deleteSelectedBlock">删除块</button>
        </div>
      </DragHandle>
      <Teleport to="body">
      <div v-if="slashOpen && !readonly" ref="slashMenuEl" class="slash-menu" :style="{ ...slashPosition, zIndex: menuZIndex }" role="listbox" aria-label="插入内容块">
        <button v-for="(action, index) in filteredSlashActions" :key="action.id" type="button" :class="{ active: slashIndex === index }" role="option" :aria-selected="slashIndex === index" @mousedown.prevent @click="chooseSlash(action)">
          <i :class="action.icon"></i><span><b>{{ action.label }}</b><small>{{ action.hint }}</small></span>
        </button>
        <div v-if="!filteredSlashActions.length" class="slash-empty">没有匹配的内容块</div>
      </div>
      </Teleport>
      <div v-if="pendingUploads.length" class="upload-queue" aria-live="polite">
        <div v-for="upload in pendingUploads" :key="upload.id" :class="upload.state">
          <i :class="upload.state === 'uploading' ? 'ri-loader-4-line spin' : 'ri-error-warning-line'"></i>
          <span><b>{{ upload.file.name }}</b><small>{{ upload.state === 'uploading' ? '正在上传，正文可继续编辑' : '上传失败，本地文件尚未插入' }}</small></span>
          <button v-if="upload.state === 'failed'" type="button" @click="runFileUpload(upload.id)">重试</button>
          <button v-if="upload.state === 'failed'" type="button" @click="pendingUploads = pendingUploads.filter((item) => item.id !== upload.id)">移除</button>
        </div>
      </div>
      <div v-if="!readonly && !articleImageKey" class="image-actions">
        <button type="button" class="image-upload" title="从图册选择图片"
          aria-label="从图册选择图片" @mousedown.prevent @click="chooseImage">
          <i class="ri-image-add-line"></i><span>图片</span>
        </button>
      </div>
    </div>
    <div v-if="articleImageKey" class="article-footer" aria-label="文章编辑操作">
      <div class="article-footer-left">
        <div v-if="!readonly" class="article-image-actions">
          <ArticleImageAction :scope-key="articleImageKey"
            :get-text="articleText" :insert-image="insertArticleImageAtStart" :get-current-image="currentArticleImage"
            :generate-title="noteTitleOnImage" @generated-title="emit('generated-title', $event)" />
          <button type="button" class="image-upload" title="从图册选择图片"
            aria-label="从图册选择图片" @mousedown.prevent @click="chooseImage">
            <i class="ri-image-add-line"></i><span>图片</span>
          </button>
        </div>
        <slot name="footer-tags" />
      </div>
      <div class="character-count article-count" title="正文字符数（含标点，不含空白、格式标记及图片附件地址）" aria-label="当前正文字数">
        <template v-if="selectedCharacterCount">已选中 {{ selectedCharacterCount.toLocaleString('zh-CN') }} 字 · 全文 </template>{{ characterCount.toLocaleString('zh-CN') }} 字
      </div>
    </div>
    <SelectionImageAction v-if="selectionImage && articleImageKey && !readonly" ref="selectionImageAction" :scope-key="articleImageKey" :insert-image="insertSelectionImage" />
    <Teleport v-else :to="toolbarTarget || 'body'" :disabled="!toolbarTarget">
      <div class="character-count" :class="{ docked: toolbarTarget }" title="正文字符数（含标点，不含空白、格式标记及图片附件地址）" aria-label="当前正文字数">
        <template v-if="selectedCharacterCount">已选中 {{ selectedCharacterCount.toLocaleString('zh-CN') }} 字 · 全文 </template>{{ characterCount.toLocaleString('zh-CN') }} 字
      </div>
    </Teleport>
    <div v-if="drawingsOpen" class="link-dialog-mask" @click.self="drawingsOpen = false">
      <section class="link-dialog drawing-dialog" role="dialog" aria-modal="true" aria-label="插入画板">
        <header><div><small>个人画板</small><h2>引用 Excalidraw 草图</h2></div><button type="button" aria-label="关闭" @click="drawingsOpen = false"><i class="ri-close-line"></i></button></header>
        <div v-if="drawingsLoading" class="drawing-state">正在加载…</div>
        <div v-else-if="drawingsError" class="drawing-state error">{{ drawingsError }} <button type="button" @click="openDrawings">重试</button></div>
        <div v-else class="drawing-list"><button v-for="item in drawings" :key="item.id" type="button" @click="insertDrawing(item)"><i class="ri-shape-line"></i><span><b>{{ item.title }}</b><small>修订 {{ item.revision }}</small></span></button><p v-if="!drawings.length">还没有画板，可以直接新建一个。</p></div>
        <footer><button type="button" class="link-save" @click="createAndInsertDrawing"><i class="ri-add-line"></i>新建并插入</button></footer>
      </section>
    </div>
    <div v-if="helpOpen" class="link-dialog-mask" @click.self="helpOpen = false">
      <section class="link-dialog shortcut-dialog" role="dialog" aria-modal="true" aria-labelledby="shortcut-dialog-title">
        <header><div><small>文档快捷键</small><h2 id="shortcut-dialog-title">更快地编辑内容</h2></div><button type="button" aria-label="关闭快捷键帮助" @click="helpOpen = false"><i class="ri-close-line"></i></button></header>
        <div class="shortcut-grid">
          <span><b>Ctrl/⌘ + B</b><small>粗体</small></span><span><b>Ctrl/⌘ + I</b><small>斜体</small></span>
          <span><b>Ctrl/⌘ + U</b><small>下划线</small></span><span><b>Ctrl/⌘ + K</b><small>添加或编辑链接</small></span>
          <span><b>Ctrl/⌘ + Z</b><small>撤销</small></span><span><b>Ctrl/⌘ + Shift + Z</b><small>重做</small></span>
          <span><b>Ctrl/⌘ + F</b><small>查找与替换</small></span><span><b>/</b><small>插入内容块</small></span>
          <span><b>Tab / Shift + Tab</b><small>列表缩进 / 减少缩进</small></span><span><b>Ctrl/⌘ + /</b><small>打开本帮助</small></span>
        </div>
      </section>
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
    <div v-if="linkDialog" class="link-dialog-mask" @click.self="linkDialog = null">
      <form class="link-dialog" role="dialog" aria-modal="true" aria-labelledby="link-dialog-title" @submit.prevent="saveLink">
        <header>
          <div>
            <small>文档超链接</small>
            <h2 id="link-dialog-title">维护链接名称与地址</h2>
          </div>
          <button type="button" aria-label="关闭链接编辑" @click="linkDialog = null"><i class="ri-close-line"></i></button>
        </header>
        <label>显示名称<input v-model="linkDialog.name" maxlength="500" autofocus /></label>
        <label>URL<input v-model="linkDialog.url" maxlength="2048" inputmode="url" placeholder="https://example.com/page" /></label>
        <div v-if="linkDialog.error" class="link-dialog-error" role="alert">{{ linkDialog.error }}</div>
        <footer>
          <button v-if="linkSelectionActive" type="button" class="link-remove" @click="removeActiveLink">移除链接</button>
          <button type="button" class="link-cancel" @click="linkDialog = null">取消</button>
          <button type="submit" class="link-save">保存链接</button>
        </footer>
      </form>
    </div>
    <button v-if="polishDialog && polishMinimized" type="button" class="polish-resume" @click="polishMinimized = false" role="status">
      {{ polishDialog.loading ? '润色正在后台处理 · 查看进度' : polishDialog.error ? '润色遇到问题 · 查看并重试' : '润色已完成 · 查看结果' }}
    </button>
    <div v-if="polishDialog && !polishMinimized" class="polish-mask" @click.self="dismissPolish">
      <section class="polish-dialog" role="dialog" aria-modal="true" aria-labelledby="polish-title">
        <header>
          <div>
            <small>AI 文档助手</small>
            <h2 id="polish-title">对照后再决定是否替换</h2>
          </div>
          <button
            type="button"
            class="polish-close"
            :aria-label="polishDialog.loading ? '收起并继续后台润色' : '关闭并保留原文'"
            @click="dismissPolish"
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
            <div class="polish-card-title"><span>润色结果</span><em>{{ polishDialog.loading && polishPreview ? '正在生成，完成后可替换' : '确认后才会写入' }}</em></div>
            <pre v-if="polishDialog.loading && polishPreview">{{ polishPreview }}<span class="streaming-indicator"> ▍</span></pre>
            <div v-if="polishDialog.loading && !polishPreview" class="polish-state">
              <PolishProgress :message="polishDialog.progress" :started-at="polishDialog.startedAt" />
            </div>
            <pre v-else-if="!polishDialog.loading && polishDialog.polished">{{ polishDialog.polished }}</pre>
            <div v-else-if="!polishDialog.loading" class="polish-state">
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
          <button type="button" class="polish-keep" @click="dismissPolish">{{ polishDialog.loading ? '收起，继续做其他事' : '保留原文' }}</button>
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
  <Teleport to="body">
    <div v-if="tableMenu && editor && !readonly" ref="tableMenuEl" class="table-context-menu" role="menu" aria-label="表格操作" :style="{ left: `${tableMenu.x}px`, top: `${tableMenu.y}px`, visibility: tableMenu.ready ? 'visible' : 'hidden', zIndex: menuZIndex }" @keydown="onTableMenuKeydown" @contextmenu.prevent>
      <button v-for="action in tableActions" :key="action.command" type="button" role="menuitem" :class="{ danger: action.danger }" :disabled="!editor.can()[action.command]()" @mousedown.prevent @click="runTableAction(action.command)"><i :class="action.icon" aria-hidden="true"></i>{{ action.label }}</button>
    </div>
  </Teleport>
  </div>
</template>

<style scoped>
.character-count { position:sticky; bottom:0; z-index:1; flex-shrink:0; width:fit-content; max-width:100%; margin-left:auto; padding:4px 8px; border-radius:4px; background:var(--wao-bg-card,#fff); color:var(--wao-text-weak,#646a73); font-size:12px; line-height:20px; font-variant-numeric:tabular-nums; }
.character-count.docked { position:static; padding:3px 16px; }
.polish-resume {
  position: sticky;
  bottom: 12px;
  z-index: 5;
  display: block;
  margin: 12px 0 0 auto;
  max-width: 100%;
  border: 1px solid #c4b5fd;
  border-radius: 10px;
  padding: 10px 14px;
  background: #f5f3ff;
  color: #5b21b6;
  cursor: pointer;
}
.table-context-menu { position:fixed; z-index:1000; width:210px; max-width:calc(100vw - 16px); max-height:calc(100vh - 16px); overflow-y:auto; padding:6px; border:1px solid var(--wao-line-control); border-radius:10px; background:var(--wao-bg-card,#fff); box-shadow:0 8px 28px #0002; }
.table-context-menu button { display:flex; align-items:center; gap:10px; width:100%; border:0; border-radius:6px; padding:8px 10px; background:transparent; color:var(--wao-text); font:inherit; font-size:13px; text-align:left; cursor:pointer; }
.table-context-menu button:hover,.table-context-menu button:focus-visible { background:var(--wao-bg-sub); outline:2px solid var(--wao-line-control); outline-offset:-2px; }
.table-context-menu button:disabled { opacity:.35; cursor:default; }
.table-context-menu button.danger { color:var(--wao-danger); }
.md-body :deep(td),.md-body :deep(th) { position:relative; }
.md-body :deep(.selectedCell)::after { content:''; position:absolute; inset:0; background:rgba(36,91,219,.13); pointer-events:none; }
.md-body :deep(.column-resize-handle) { position:absolute; right:-2px; top:0; bottom:0; width:4px; background:var(--wao-primary); pointer-events:none; }
.md-body :deep(.resize-cursor) { cursor:col-resize; }
.md {
  width: 100%;
  position: relative;
}
.editor-tools {
  position: sticky;
  top: 0;
  z-index: 8;
  margin-bottom: 6px;
}
.editor-tools.docked { position: relative; margin: 0; }
.docked .advanced-toolbar { margin: 0; border: 0; border-bottom: 1px solid var(--wao-divider); border-radius: 0; box-shadow: none; padding: 5px 12px; }
.toolbar-toggle {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-left: auto;
  padding: 4px 8px;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font-size: 12px;
  cursor: pointer;
}
.toolbar-toggle:hover,
.toolbar-toggle[aria-expanded="true"] {
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
}
.advanced-toolbar {
  margin-top: 6px;
  min-height: 42px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
  padding: 5px 6px;
  border: 1px solid var(--wao-line-control);
  border-radius: 10px;
  background: color-mix(in srgb, var(--wao-bg-card) 96%, transparent);
  box-shadow: 0 4px 16px rgba(20, 23, 35, 0.06);
}
.advanced-toolbar button,
.advanced-toolbar select,
.tool-palette summary {
  flex: 0 0 auto;
  height: 30px;
  min-width: 30px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text-2);
  cursor: pointer;
}
.advanced-toolbar button:hover,
.advanced-toolbar button.active,
.tool-palette summary:hover {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.advanced-toolbar button:disabled { opacity: .35; cursor: default; }
.advanced-toolbar select { min-width: 76px; padding: 0 7px; }
.tool-sep { width: 1px; height: 20px; margin: 0 3px; background: var(--wao-divider); }
.tool-palette { position: relative; }
.tool-palette summary { display: grid; place-items: center; list-style: none; }
.tool-palette summary::-webkit-details-marker { display: none; }
.tool-palette > div {
  position: absolute;
  z-index: 30;
  top: 34px;
  left: 0;
  width: 154px;
  padding: 8px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
}
.tool-palette > div button { width: 28px; min-width: 28px; height: 28px; border: 2px solid var(--wao-bg-card); box-shadow: 0 0 0 1px var(--wao-line-control); }
.find-panel {
  position: sticky;
  top: 43px;
  z-index: 7;
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 7px;
  border: 1px solid var(--wao-line-control);
  border-top: 0;
  border-radius: 0 0 9px 9px;
  background: var(--wao-bg-card);
  box-shadow: 0 8px 20px rgba(20, 23, 35, .08);
}
.find-panel input { min-width: 100px; flex: 1; height: 30px; border: 1px solid var(--wao-line-control); border-radius: 6px; padding: 0 8px; background: var(--wao-bg-card); color: var(--wao-text); }
.find-panel button { height: 30px; border: 0; border-radius: 6px; padding: 0 9px; background: var(--wao-primary-soft); color: var(--wao-primary-text); cursor: pointer; white-space: nowrap; }
.find-panel span { color: var(--wao-text-weak); font-size: 11px; white-space: nowrap; }
.selection-menu { display: flex; align-items: center; gap: 1px; width: max-content; max-width: min(560px, calc(100vw - 24px)); overflow-x: auto; overscroll-behavior: contain; scrollbar-width: thin; scrollbar-color: #747783 transparent; padding: 4px; border-radius: 9px; background: #292b35; color: #fff; box-shadow: 0 6px 20px rgba(0,0,0,.22); }
.selection-menu button,.selection-language { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 30px; width: 30px; height: 32px; padding: 0; border: 0; border-radius: 5px; background: transparent; color: inherit; font-size: 16px; cursor: pointer; }
.selection-menu button:hover,.selection-language:hover,.selection-menu button[aria-pressed="true"] { background: rgba(255,255,255,.16); }
.selection-menu button.selection-generate { flex-basis: auto; width: auto; gap: 4px; padding: 0 8px; font-size: 12px; }
.selection-menu :focus-visible { outline: 2px solid #9dbbff; outline-offset: -2px; }
.selection-divider { flex: 0 0 1px; height: 17px; background: rgba(255,255,255,.22); margin: 0 4px; }
.selection-language { position: relative; }
.selection-language select { position: absolute; inset: 0; width: 100%; opacity: 0; cursor: pointer; }
.context-toolbar { display:flex; gap:3px; padding:5px; border-radius:9px; background:#292b35; box-shadow:0 8px 24px rgba(0,0,0,.24); }
.context-toolbar button,.context-toolbar select { height:30px; border:0; border-radius:6px; padding:0 8px; background:rgba(255,255,255,.08); color:#fff; cursor:pointer; font-size:11px; }.context-toolbar button:hover { background:rgba(255,255,255,.17); }.context-toolbar button.danger { color:#ffb7b7; }.context-toolbar select option { background:var(--wao-bg-card); color:var(--wao-text); }
.slash-menu {
  position: fixed;
  box-sizing: border-box;
  width: min(340px, calc(100vw - 24px));
  max-height: 360px;
  overflow: auto;
  padding: 6px;
  border: 1px solid var(--wao-line-control);
  border-radius: 11px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
}
.slash-menu > button { width: 100%; display: flex; align-items: center; gap: 10px; padding: 8px 9px; border: 0; border-radius: 8px; background: transparent; color: var(--wao-text-2); text-align: left; cursor: pointer; }
.slash-menu > button:hover,.slash-menu > button.active { background: var(--wao-primary-soft); }
.slash-menu > button > i { width: 28px; height: 28px; display: grid; place-items: center; border-radius: 7px; background: var(--wao-bg-sub); font-size: 16px; }
.slash-menu span { min-width: 0; display: flex; flex-direction: column; }
.slash-menu b { font-size: 12px; }.slash-menu small,.slash-empty { color: var(--wao-text-weak); font-size: 10.5px; }.slash-empty { padding: 16px; text-align: center; }
.upload-queue { margin:8px 0; display:grid; gap:6px; }.upload-queue > div { display:flex; align-items:center; gap:8px; padding:8px 10px; border:1px solid var(--wao-line); border-radius:8px; background:var(--wao-bg-sub); }.upload-queue > div.failed { border-color:color-mix(in srgb,var(--wao-danger-strong) 35%,var(--wao-line)); }.upload-queue span { min-width:0; flex:1; display:flex; flex-direction:column; }.upload-queue b { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:11px; }.upload-queue small { color:var(--wao-text-weak); font-size:10px; }.upload-queue button { border:0; background:transparent; color:var(--wao-primary-text); font-size:11px; cursor:pointer; }
.block-drag-handle { z-index: 9; display: flex; align-items: center; gap: 3px; padding: 3px; border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-weak); box-shadow: 0 4px 12px rgba(20,23,35,.1); cursor: grab; }
.block-actions { display: none; position: absolute; left: 100%; top: 0; padding-left: 4px; }
.block-drag-handle:hover .block-actions { display: flex; }
.block-actions button { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); color: var(--wao-text-2); padding: 5px 7px; white-space: nowrap; cursor: pointer; }
.drawing-dialog { max-height: min(600px, calc(100vh - 40px)); overflow: auto; }
.drawing-state { padding: 28px; text-align: center; color: var(--wao-text-weak); }.drawing-state.error { color: var(--wao-danger-strong); }
.drawing-list { margin-top: 12px; display: grid; gap: 6px; }
.drawing-list > button { display: flex; align-items: center; gap: 10px; width: 100%; padding: 10px; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card); color: var(--wao-text-2); text-align: left; cursor: pointer; }
.drawing-list > button:hover { border-color: var(--wao-primary); background: var(--wao-primary-soft); }.drawing-list > button > i { font-size: 20px; }.drawing-list span { display:flex; flex-direction:column; }.drawing-list small { color:var(--wao-text-weak); }
.shortcut-dialog { max-width: 620px; }
.shortcut-grid { margin-top: 16px; display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; }
.shortcut-grid span { min-width:0; display:flex; align-items:center; justify-content:space-between; gap:12px; padding:10px 11px; border:1px solid var(--wao-line); border-radius:9px; background:var(--wao-bg-sub); }
.shortcut-grid b { color:var(--wao-text-2); font:600 11px ui-monospace,SFMono-Regular,Menlo,monospace; }.shortcut-grid small { color:var(--wao-text-weak); font-size:10.5px; text-align:right; }
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
.link-toolbar,
.image-size-toolbar {
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 10px;
  background: #292b35;
  box-shadow: 0 9px 28px rgba(18, 20, 28, 0.28);
  color: #fff;
}
.link-toolbar {
  min-height: 36px;
  padding: 4px;
  display: flex;
  align-items: center;
  gap: 2px;
}
.link-toolbar button {
  min-width: 30px;
  height: 30px;
  padding: 0 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #fff;
  font-size: 11px;
  cursor: pointer;
}
.link-toolbar button:hover,
.image-size-toolbar button:hover,
.image-size-toolbar button.active {
  background: rgba(255, 255, 255, 0.14);
}
.image-size-toolbar {
  width: min(390px, calc(100vw - 20px));
  padding: 9px;
}
.image-scale-options,
.image-pixel-fields,
.image-size-actions {
  display: flex;
  align-items: center;
  gap: 5px;
}
.image-description-fields { margin-top:7px; display:grid; grid-template-columns:1fr 1fr auto; gap:5px; align-items:end; }
.image-description-fields label { display:flex; flex-direction:column; gap:3px; color:rgba(255,255,255,.7); font-size:9.5px; }
.image-description-fields input { min-width:0; height:28px; border:1px solid rgba(255,255,255,.18); border-radius:6px; padding:0 6px; background:rgba(255,255,255,.08); color:#fff; font:inherit; }
.image-description-fields button,.image-align-actions button { height:28px; border:0; border-radius:6px; padding:0 8px; background:rgba(255,255,255,.09); color:#fff; cursor:pointer; }
.image-align-actions { margin-top:7px; display:flex; align-items:center; justify-content:flex-end; gap:5px; font-size:9.5px; color:rgba(255,255,255,.7); }
.image-scale-options button,
.image-size-actions button,
.image-pixel-fields > button {
  min-height: 28px;
  border: 0;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.07);
  color: #fff;
  font-size: 10.5px;
  cursor: pointer;
}
.image-scale-options button {
  flex: 1;
}
.image-pixel-fields {
  margin-top: 7px;
}
.image-pixel-fields label {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: center;
  gap: 4px;
  color: rgba(255, 255, 255, 0.7);
  font-size: 10px;
}
.image-pixel-fields input {
  width: 100%;
  min-width: 0;
  height: 28px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  padding: 0 6px;
  font: inherit;
}
.image-pixel-fields > span {
  color: rgba(255, 255, 255, 0.5);
}
.image-pixel-fields > button {
  padding: 0 9px;
  background: #65558f;
  font-weight: 650;
}
.image-size-error {
  margin-top: 6px;
  color: #ffb7b7;
  font-size: 9.5px;
}
.image-size-actions {
  justify-content: flex-end;
  margin-top: 7px;
}
.image-size-actions button {
  padding: 0 8px;
}
.link-dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 1300;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(24, 25, 32, 0.46);
}
.link-dialog {
  width: min(500px, 100%);
  padding: 20px;
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  box-shadow: 0 24px 72px rgba(16, 18, 28, 0.28);
}
.link-dialog header,
.link-dialog footer {
  display: flex;
  align-items: center;
}
.link-dialog header {
  justify-content: space-between;
  gap: 12px;
}
.link-dialog header small {
  color: var(--wao-area-finance);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 1px;
}
.link-dialog h2 {
  margin: 3px 0 0;
  color: var(--wao-text);
  font-size: 18px;
}
.link-dialog header > button {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  cursor: pointer;
}
.link-dialog label {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--wao-text-2);
  font-size: 11px;
  font-weight: 650;
}
.link-dialog label input {
  width: 100%;
  height: 38px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 0 10px;
  font: inherit;
  font-weight: 400;
}
.link-dialog-error {
  margin-top: 10px;
  color: var(--wao-danger-strong);
  font-size: 10.5px;
}
.link-dialog footer {
  justify-content: flex-end;
  gap: 7px;
  margin-top: 16px;
}
.link-dialog footer button {
  min-height: 34px;
  padding: 0 12px;
  border-radius: 8px;
  font-size: 11px;
  cursor: pointer;
}
.link-cancel,
.link-remove {
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}
.link-remove {
  margin-right: auto;
  color: var(--wao-danger-strong);
}
.link-save {
  border: 0;
  background: #65558f;
  color: #fff;
  font-weight: 700;
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
  color: var(--wao-area-finance);
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
  color: var(--wao-area-finance);
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
.article-footer {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 12px;
  flex-shrink: 0;
  min-width: 0;
  padding: 8px 0 0;
}
.article-footer-left,
.article-image-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}
.article-footer-left {
  flex: 1 1 auto;
}
.article-count {
  position: static;
  margin-left: auto;
  padding-right: 0;
  white-space: nowrap;
}
.image-actions {
  position: absolute;
  right: 8px;
  bottom: 7px;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 5px;
}
.image-upload {
  height: 26px;
  padding: 0 8px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--wao-divider);
  border-radius: 6px;
  background: color-mix(in srgb, var(--wao-bg-card) 94%, transparent);
  color: var(--wao-text-weak);
  font-size: 11px;
  cursor: pointer;
}
.image-upload:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary-text);
}
.image-upload i {
  font-size: 13px;
}
.md.bare .image-actions {
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
/*
 * 嵌套有序列表不能继续全用 1、2、3：子级的 1 会夹在父级 1、2 之间，
 * 看起来像父级顺序被打乱。按层级循环数字、字母、罗马数字，结构和 Markdown
 * 存储都不变，只让层级关系一眼可辨。
 */
.md-body :deep(ol) {
  list-style-type: decimal;
}
.md-body :deep(ol ol) {
  list-style-type: lower-alpha;
}
.md-body :deep(ol ol ol) {
  list-style-type: lower-roman;
}
.md-body :deep(ol ol ol ol) {
  list-style-type: decimal;
}
.md-body :deep(ol ol ol ol ol) {
  list-style-type: lower-alpha;
}
.md-body :deep(ol ol ol ol ol ol) {
  list-style-type: lower-roman;
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
.md-body :deep(aside[data-callout]) { margin: 12px 0; padding: 10px 13px; border-left: 4px solid var(--wao-primary); border-radius: 8px; background: var(--wao-primary-soft); }
.md-body :deep(aside[data-callout='success']) { border-color:var(--wao-success); background:var(--wao-success-bg); }.md-body :deep(aside[data-callout='warning']) { border-color:var(--wao-warning); background:var(--wao-warning-bg); }.md-body :deep(aside[data-callout='danger']) { border-color:var(--wao-danger); background:var(--wao-danger-bg); }
.md-body :deep(div[data-columns]) { display:grid; grid-template-columns:repeat(var(--columns, 2),minmax(0,1fr)); gap:14px; margin:12px 0; }
.md-body :deep(div[data-columns='2']) { grid-template-columns:repeat(2,minmax(0,1fr)); }.md-body :deep(div[data-columns='3']) { grid-template-columns:repeat(3,minmax(0,1fr)); }
.md-body :deep(section[data-column]) { min-width:0; padding:10px; border:1px dashed var(--wao-line-control); border-radius:8px; }
.md-body :deep(.tableWrapper) { overflow-x:auto; margin:12px 0; }.md-body :deep(table) { border-collapse:collapse; width:100%; min-width:440px; }.md-body :deep(th),.md-body :deep(td) { min-width:90px; padding:7px 9px; border:1px solid var(--wao-line-control); vertical-align:top; }.md-body :deep(th) { background:var(--wao-bg-sub); }
.md-body :deep(details) { margin:12px 0; padding:8px 11px; border:1px solid var(--wao-line-control); border-radius:9px; }.md-body :deep(summary) { cursor:pointer; font-weight:650; }
@media (max-width: 680px) {
  .advanced-toolbar { top: 0; border-radius: 8px; }
  .find-panel { flex-wrap: wrap; top: 43px; }
  .find-panel span { display:none; }
  .md-body :deep(div[data-columns]) { grid-template-columns:1fr !important; }
  .shortcut-grid { grid-template-columns:1fr; }
}
</style>
