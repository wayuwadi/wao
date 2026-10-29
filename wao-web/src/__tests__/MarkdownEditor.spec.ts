import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'
import ImageCopyMenu from '@/components/ui/ImageCopyMenu.vue'
import { polishDocumentText } from '@/api/assistant'
import type { RichDocNode } from '@/types'
import type { Editor } from '@tiptap/core'
import { BubbleMenu } from '@tiptap/vue-3/menus'
import { CellSelection } from '@tiptap/pm/tables'
import { AllSelection } from '@tiptap/pm/state'
import { listIndentStyle } from '@/utils/listIndent'
import { createPinia, setActivePinia } from 'pinia'
import { useUiStore } from '@/stores/ui'
import { loadRotationImage } from '@/api/imageRotation'
import { rotateImage } from '@/utils/rotateImage'
import { fetchPreference, savePreference } from '@/api/favorites'
import { fetchNoteAiSettings, saveNoteAiSettings } from '@/api/noteAiSettings'
import { startArticleImage, fetchArticleImage } from '@/api/articleImages'
import { freezeNoteAiInput, retainNoteAiResult, type NoteAiOrigin } from '@/api/noteOriginals'
vi.mock('@/api/noteOriginals', () => ({ freezeNoteAiInput: vi.fn().mockResolvedValue({ snapshotId: 'snapshot' }), retainNoteAiResult: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@/api/imageRotation', () => ({ loadRotationImage: vi.fn() }))
vi.mock('@/utils/rotateImage', () => ({ rotateImage: vi.fn() }))

vi.mock('@/api/assistant', () => ({
  polishDocumentText: vi.fn<(text: string) => Promise<{ polished: string }>>(),
}))
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
vi.mock('@/api/noteAiSettings', () => ({ fetchNoteAiSettings: vi.fn(), saveNoteAiSettings: vi.fn() }))
vi.mock('@/api/articleImages', () => ({ startArticleImage: vi.fn(), fetchArticleImage: vi.fn() }))
vi.mock('@/api/codexModels', () => ({ fetchCodexModels: vi.fn().mockResolvedValue({ models: [{ id: 'gpt-6-astra', label: 'GPT-6 Astra', reasoningEfforts: ['low', 'medium', 'high'] }, { id: 'gpt-6-luna', label: 'GPT-6 Luna', reasoningEfforts: ['low', 'medium', 'high'] }], stale: false, message: '自动刷新', updatedAt: '2026-10-03' }) }))
vi.mock('@/api/textPolish', () => ({ fetchTextPolishWorkspace: vi.fn().mockResolvedValue({ prompt: '默认正文润色方法', records: [] }), saveTextPolishPrompt: vi.fn().mockResolvedValue('默认正文润色方法') }))

/** Tiptap 需要真实 DOM 与一帧时间来完成挂载。 */
async function mountEditor(props: {
  modelValue: string
  searchTerm?: string
  searchRequest?: string
  readonly?: boolean
  autofocus?: boolean
  initialPosition?: number
  previewImages?: boolean
  imageReferences?: boolean
  noteMediaCopy?: boolean
  activeImageReference?: number | null
  placeholder?: string
  allowH1?: boolean
  peers?: {
    id: string
    name: string
    initial: string
    color: string
    line: number
    editing: boolean
  }[]
  polishable?: boolean
  taskable?: boolean
  linkControls?: boolean
  imageControls?: boolean
  imageContextControls?: boolean
  selectionImage?: boolean
  menuZIndex?: number
  slashCommands?: boolean
  advanced?: boolean
  articleImageKey?: string
  noteTitleOnImage?: boolean
  compact?: boolean
  toolbarTarget?: HTMLElement | null
  document?: RichDocNode | null
  noteAiOrigin?: NoteAiOrigin
}) {
  // jsdom 的 Range 缺少布局 API；BubbleMenu 只需要一个稳定空矩形完成挂载。
  if (!Range.prototype.getClientRects) {
    Object.defineProperty(Range.prototype, 'getClientRects', {
      configurable: true,
      value: () => [],
    })
  }
  if (!Range.prototype.getBoundingClientRect) {
    Object.defineProperty(Range.prototype, 'getBoundingClientRect', {
      configurable: true,
      value: () => ({ top: 0, right: 0, bottom: 0, left: 0, width: 0, height: 0, x: 0, y: 0 }),
    })
  }
    const w = mount(MarkdownEditor, { props, attachTo: document.body })
  await nextTick()
  await new Promise((r) => setTimeout(r, 0))
  return w
}

describe('代码块', () => {
  it('SQL 高亮、语言切换、撤销与序列化保留原文', async () => {
    const source = "-- 示例\nSELECT name, 42 FROM demo WHERE name = '<tag>';"
    const w = await mountEditor({ modelValue: '```sql\n' + source + '\n```', advanced: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    expect(w.get('.hljs-keyword').text()).toBe('SELECT')
    expect(w.get('.hljs-string').text()).toBe("'<tag>'")
    expect(w.get('pre code').text()).toBe(source)
    const original = e.getJSON()
    await w.get('select[aria-label="代码语言"]').setValue('plaintext')
    expect(w.find('.hljs-keyword').exists()).toBe(false)
    expect(e.getJSON().content?.[0]?.attrs?.language).toBe('plaintext')
    expect(e.state.doc.textContent).toBe(source)
    e.commands.undo(); await nextTick()
    expect(e.getJSON()).toEqual(original)
    expect(e.getHTML()).not.toContain('自动换行')
    expect(e.getHTML()).not.toContain('code-block-head')
    await w.get('button[aria-pressed]').trigger('click')
    expect(w.get('.wao-code-block').classes()).toContain('code-wrap')
    expect(e.getJSON()).toEqual(original)
    w.unmount()
  })

  it('只读代码可复制原文，失败可重试且不修改文档', async () => {
    const writeText = vi.fn().mockRejectedValueOnce(new Error('denied')).mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const w = await mountEditor({ modelValue: '```sql\nSELECT 1;\n```', readonly: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    expect(w.find('select[aria-label="代码语言"]').exists()).toBe(false)
    await w.get('[aria-label="复制代码"]').trigger('click'); await flushPromises()
    expect(w.text()).toContain('复制失败，请重试')
    await w.get('[aria-label="复制代码"]').trigger('click'); await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith('SELECT 1;')
    expect(w.text()).toContain('已复制')
    expect(e.getJSON()).toEqual(original)
    w.unmount()
  })
})

describe('异步挂载焦点保护', () => {
  it('编辑器看图方向键切换，Esc 先关闭图片且不改正文，焦点返回入口', async () => {
    const w = await mountEditor({ modelValue: '', advanced: true, imageContextControls: true, document: { type: 'doc', content: [
      { type: 'image', attrs: { src: '/api/images/one/raw', alt: '第一张' } },
      { type: 'image', attrs: { src: '/api/images/two/raw', alt: '第二张' } },
    ] } })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const before = e.getJSON()
    const trigger = w.findAll('.note-image-fullscreen')[0]!
    ;(trigger.element as HTMLButtonElement).focus()
    await trigger.trigger('click'); await flushPromises()
    expect(window.document.activeElement).toBe(w.get('.image-lightbox-close').element)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }))
    await flushPromises()
    expect(w.get('.image-lightbox img').attributes('src')).toBe('/api/images/two/raw')
    expect((w.vm as unknown as { dismissTransientUi: () => boolean }).dismissTransientUi()).toBe(true)
    await flushPromises()
    expect(w.find('.image-lightbox').exists()).toBe(false)
    expect(window.document.activeElement).toBe(trigger.element)
    expect(e.getJSON()).toEqual(before)
    w.unmount()
  })
  it('图片引用选中后可 Tab 缩进，前后输入文字保留图片并可撤销', async () => {
    const w = await mountEditor({ modelValue: '', advanced: true, imageReferences: true, imageContextControls: true, imageControls: true,
      document: { type: 'doc', content: [{ type: 'image', attrs: { src: '/api/images/one/raw', alt: '示例' } }] } })
    const e = (w.vm as unknown as { editor: Editor }).editor
    await w.get('[data-image-reference="1"]').trigger('click')
    expect(e.isActive('image')).toBe(true)
    await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(e.state.selection.$from.nodeAfter?.attrs.indent).toBe(1)
    await w.get('[aria-label="在图片引用前输入文字"]').trigger('click')
    e.commands.insertContent('前面的文字')
    await w.get('[aria-label="在图片引用后输入文字"]').trigger('click')
    e.commands.insertContent('后面的文字')
    const nodes = e.getJSON().content!
    expect(nodes[0]?.content?.[0]).toMatchObject({ type: 'text', text: '前面的文字' })
    expect(nodes[1]?.type).toBe('image')
    expect(nodes[1]?.attrs?.src).toBe('/api/images/one/raw')
    expect(nodes[2]?.content?.[0]).toMatchObject({ type: 'text', text: '后面的文字' })
    expect(e.getMarkdown()).toContain('前面的文字')
    expect(e.getMarkdown()).toContain('后面的文字')
    expect(e.commands.undo()).toBe(true)
    w.unmount()
  })
  it('编辑图片引用动态切换不重建编辑器，光标、属性、修改及撤销保留', async () => {
    setActivePinia(createPinia())
    const document: RichDocNode = { type: 'doc', content: [
      { type: 'paragraph', content: [{ type: 'text', text: '之前' }] },
      { type: 'image', attrs: { src: '/api/images/same/raw', alt: '第一张', width: '50%', height: null } },
      { type: 'blockquote', content: [{ type: 'image', attrs: { src: '/api/images/same/raw', alt: '第二张', height: 200 } }] },
    ] }
    const w = await mountEditor({ modelValue: '', document, advanced: true, imageReferences: true, imageContextControls: true, previewImages: true, imageControls: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    expect(w.findAll('img.md-img')).toHaveLength(0)
    expect(w.findAll('[data-image-reference]').map(x => x.text())).toEqual(['图 1 · 第一张', '图 2 · 第二张'])
    e.commands.setTextSelection(2)
    e.commands.insertContent('新增')
    const before = e.getJSON(), markdown = e.getMarkdown(), selection = e.state.selection.toJSON()
    const emissions = w.emitted('content-change')?.length
    await w.setProps({ imageReferences: false })
    expect(w.findAll('img.md-img')).toHaveLength(2)
    await w.setProps({ imageReferences: true })
    expect((w.vm as unknown as { editor: Editor }).editor).toBe(e)
    expect(e.getJSON()).toEqual(before)
    expect(e.getMarkdown()).toBe(markdown)
    expect(e.state.selection.toJSON()).toEqual(selection)
    expect(w.emitted('content-change')?.length).toBe(emissions)
    expect(e.commands.undo()).toBe(true)
    expect(e.state.doc.textContent).toBe('之前')
    const images: number[] = []; e.state.doc.descendants((node, pos) => { if (node.type.name === 'image') images.push(pos) })
    e.commands.setNodeSelection(images[0]!); e.commands.deleteSelection()
    await nextTick()
    expect(w.findAll('[data-image-reference]').map(x => x.text())).toEqual(['图 1 · 第二张'])
    e.commands.undo(); await nextTick()
    expect(w.findAll('[data-image-reference]')).toHaveLength(2)
    await w.get('[data-image-reference="1"]').trigger('contextmenu', { clientX: 50, clientY: 60 })
    expect(e.isActive('image')).toBe(true)
    expect(window.document.body.querySelector('.note-image-menu')).not.toBeNull()
    w.unmount()
  })
  it('阅读图号只改变图片视图，重复图片与嵌套图片保留原文位置和复制内容', async () => {
    const document: RichDocNode = { type: 'doc', content: [
      { type: 'paragraph', content: [{ type: 'text', text: '之前' }] },
      { type: 'image', attrs: { src: '/api/images/same/raw', alt: '第一次', width: '50%', height: null } },
      { type: 'blockquote', content: [{ type: 'image', attrs: { src: '/api/images/same/raw', alt: '第二次', height: 200 } }, { type: 'paragraph', content: [{ type: 'text', text: '之后' }] }] },
    ] }
    const ordinary = await mountEditor({ modelValue: '', document, readonly: true })
    const references = await mountEditor({ modelValue: '', document, readonly: true, imageReferences: true })
    const originalEditor = (ordinary.vm as unknown as { editor: Editor }).editor
    const e = (references.vm as unknown as { editor: Editor }).editor
    expect(e.getJSON()).toEqual(originalEditor.getJSON())
    expect(e.getMarkdown()).toBe(originalEditor.getMarkdown())
    expect(references.findAll('img.md-img')).toHaveLength(0)
    const buttons = references.findAll('[data-image-reference]')
    expect(buttons.map(button => button.text())).toEqual(['图 1 · 第一次', '图 2 · 第二次'])
    await buttons[1]!.trigger('click')
    expect(references.emitted('image-reference')).toEqual([[2]])
    await references.setProps({ activeImageReference: 2 })
    expect(buttons[1]!.attributes('aria-current')).toBe('true')
    ;(references.vm as unknown as { focusImageReference: (index: number) => boolean }).focusImageReference(1)
    expect(window.document.activeElement).toBe(buttons[0]!.element)
    expect(references.emitted('content-change')).toBeUndefined()
    expect(references.emitted('upload-image')).toBeUndefined()
    ordinary.unmount(); references.unmount()
  })

  it('阅读内容替换后按新的出现顺序重新编号，历史段落内图片的前后文字不丢失', async () => {
    const w = await mountEditor({ modelValue: '![原图](/api/images/old/raw)', readonly: true, imageReferences: true })
    await w.setProps({ document: { type: 'doc', content: [{ type: 'paragraph', content: [
      { type: 'text', text: '图片前' }, { type: 'image', attrs: { src: '/api/images/one/raw', alt: '一' } },
      { type: 'text', text: '图片后' },
    ] }, { type: 'image', attrs: { src: '/api/images/two/raw', alt: '二' } }] } })
    expect(w.findAll('[data-image-reference]').map(button => button.attributes('data-image-reference'))).toEqual(['1', '2'])
    expect(w.find('.tiptap').text()).toContain('图片前图 1 · 一图片后图 2 · 二')
    expect(w.emitted('content-change')).toBeUndefined()
    w.unmount()
  })
  it('异步编辑器创建后恢复富文本光标位置，插入发生在原来的文字中', async () => {
    const w = await mountEditor({ modelValue: '甲乙丙丁', initialPosition: 3 })
    const e = (w.vm as unknown as { editor: Editor }).editor
    expect(e.state.selection.from).toBe(3)
    e.commands.insertContent('定位')
    expect(e.getText()).toBe('甲乙定位丙丁')
    w.unmount()
  })
  it('历史段落内的块图片加载后保留前后文字、格式与图片属性，不在只读时写回', async () => {
    const legacy: RichDocNode = { type: 'doc', content: [{ type: 'paragraph', content: [
      { type: 'text', text: '图片前', marks: [{ type: 'bold' }] },
      { type: 'image', attrs: { src: 'https://example.com/a.png', alt: '历史图片', width: '50%', indent: 2 } },
      { type: 'text', text: '图片后' },
    ] }] }
    const w = await mountEditor({ modelValue: '', document: legacy, advanced: true, readonly: true })
    await new Promise(resolve => setTimeout(resolve, 40))
    const editor = (w.vm as unknown as { editor: Editor }).editor
    expect(() => editor.state.doc.check()).not.toThrow()
    expect(w.get('strong').text()).toBe('图片前')
    expect(w.get('img').attributes('alt')).toBe('历史图片')
    expect(editor.getJSON().content!.map(node => node.type)).toEqual(['paragraph', 'image', 'paragraph'])
    expect(editor.getJSON().content![1]!.attrs).toMatchObject({ width: '50%', indent: 2 })
    expect(w.emitted('content-change')).toBeUndefined()
    expect(legacy.content![0]!.content).toHaveLength(3)
    w.unmount()
  })
  it('同行 Markdown 图片与前后文字加载为合法结构', async () => {
    const w = await mountEditor({ modelValue: '前文 ![图片](https://example.com/a.png) 后文', advanced: true })
    await new Promise(resolve => setTimeout(resolve, 40))
    const editor = (w.vm as unknown as { editor: Editor }).editor
    expect(() => editor.state.doc.check()).not.toThrow()
    expect(editor.state.doc.textContent).toContain('前文')
    expect(editor.state.doc.textContent).toContain('后文')
    expect(w.get('img').attributes('alt')).toBe('图片')
    await w.setProps({ modelValue: '更新前 ![新图](https://example.com/b.png) 更新后' })
    expect(() => editor.state.doc.check()).not.toThrow()
    expect(w.get('img').attributes('alt')).toBe('新图')
    await w.setProps({ document: { type: 'doc', content: [{ type: 'bulletList', content: [{ type: 'listItem', content: [
      { type: 'paragraph', content: [{ type: 'image', attrs: { src: 'https://example.com/c.png', alt: '列表图片' } }, { type: 'text', text: '图后条目' }] },
    ] }] }] } })
    expect(() => editor.state.doc.check()).not.toThrow()
    expect(w.get('li img').attributes('alt')).toBe('列表图片')
    expect(w.get('li').text()).toBe('图后条目')
    w.unmount()
  })
  it('接物追加保留已有富文本，外部 HTML 和 Markdown 按原文保存，只读时拒绝追加', async () => {
    const w = await mountEditor({ modelValue: '**已有草稿**' })
    expect(w.vm.appendPlainText('<img src=x onerror=alert(1)>\n# 原样保留')).toBe(true)
    const editor = (w.vm as unknown as { editor: Editor }).editor
    expect(editor.state.doc.textContent).toBe('已有草稿<img src=x onerror=alert(1)># 原样保留')
    expect(editor.getJSON().content![0]!.content![0]!.marks).toEqual([{ type: 'bold' }])
    expect(w.find('img').exists()).toBe(false)
    await w.setProps({ readonly: true })
    expect(w.vm.appendPlainText('不可追加')).toBe(false)
    w.unmount()
  })
  it('正文命中只定位选区，不改内容或触发保存，只读模式不提供替换', async () => {
    const w = await mountEditor({ modelValue: '前文 桂**花**路线 后文', readonly: true, advanced: true, searchTerm: '桂花' })
    await flushPromises()
    const editor = (w.vm as unknown as { editor: Editor }).editor
    expect(editor.state.doc.textBetween(editor.state.selection.from, editor.state.selection.to)).toBe('桂花')
    expect(w.findAll('.content-search-hit').map(node => node.text()).join('')).toBe('桂花')
    expect(w.emitted('content-change')).toBeUndefined()
    expect(w.find('[aria-label="替换内容"]').exists()).toBe(false)
    expect(w.text()).toContain('1 / 1')
    w.unmount()
  })
  it('标题已获得焦点时，正文就绪不能抢走焦点或选区', async () => {
    const title = document.createElement('input')
    title.value = '继续修改标题'
    document.body.appendChild(title)
    title.focus()
    title.setSelectionRange(2, 4)
    const w = await mountEditor({ modelValue: '原正文', autofocus: true })
    await new Promise(resolve => setTimeout(resolve, 40))
    expect(document.activeElement).toBe(title)
    expect([title.selectionStart, title.selectionEnd]).toEqual([2, 4])
    w.unmount()
    title.remove()
  })

  it('没有其他输入焦点时仍默认进入正文', async () => {
    const w = await mountEditor({ modelValue: '原正文', autofocus: true })
    await new Promise(resolve => setTimeout(resolve, 40))
    expect(document.activeElement).toBe(w.get('.tiptap').element)
    w.unmount()
  })

  it('挂载等待期间转到其他控件后，不再自动聚焦正文', async () => {
    const button = document.createElement('button')
    document.body.appendChild(button)
    const pending = mountEditor({ modelValue: '原正文', autofocus: true })
    button.focus()
    const w = await pending
    await new Promise(resolve => setTimeout(resolve, 40))
    expect(document.activeElement).toBe(button)
    w.unmount()
    button.remove()
  })
})

describe('图片 Tab 缩进', () => {
  it('点击正文后的图片不误选相邻空段落，连续缩进有界且不丢图', async () => {
    const w = await mountEditor({ modelValue: '前文\n\n![图](/api/images/tab-test/raw)\n\n后文', imageControls: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const click = new MouseEvent('click', { bubbles: true })
    Object.defineProperty(click, 'target', { value: w.get('.md-body img').element })
    e.view.someProp('handleClick', handler => handler(e.view, e.state.doc.content.size - 1, click))
    for (let i = 0; i < 10; i++) await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(e.getJSON().content?.find(n => n.type === 'image')?.attrs?.indent).toBe(8)
    expect(w.findAll('.md-body img')).toHaveLength(1)
    expect(e.getMarkdown()).toContain('前文')
    expect(e.getMarkdown()).toContain('后文')
    const markdown = e.getMarkdown()
    for (let i = 0; i < 10; i++) await w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.getJSON().content?.find(n => n.type === 'image')?.attrs?.indent).toBe(0)
    w.unmount()
    const restored = await mountEditor({ modelValue: markdown })
    expect(restored.get('.md-body img').attributes('data-image-indent')).toBe('8')
    restored.unmount()
  })

  it('选中图片后 Tab 只改变缩进，支持反缩进、撤销和 Markdown 往返', async () => {
    const w = await mountEditor({ modelValue: '![验收图](/api/images/tab-test/raw "wao-image-size:50%xauto;title=%E6%A0%87%E9%A2%98")\n\n保留后文', imageControls: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.setNodeSelection(0)
    await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(w.findAll('.md-body img')).toHaveLength(1)
    expect(e.getJSON().content?.[0]?.attrs).toMatchObject({ src: '/api/images/tab-test/raw', indent: 1, width: '50%', title: '标题' })
    expect(e.state.selection.from).toBe(0)
    expect(w.get('.md-body img').attributes('data-image-indent')).toBe('1')
    const markdown = e.getMarkdown()
    await w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.getJSON().content?.[0]?.attrs?.indent).toBe(0)
    e.commands.undo()
    expect(e.getJSON().content?.[0]?.attrs?.indent).toBe(1)
    expect(e.getMarkdown()).toContain('保留后文')
    w.unmount()
    const restored = await mountEditor({ modelValue: markdown, readonly: true })
    expect(restored.get('.md-body img').attributes('data-image-indent')).toBe('1')
    expect(restored.get('.md-body img').attributes('src')).toBe('/api/images/tab-test/raw')
    restored.unmount()
  })
})

describe('正文选区安全缩进', () => {
  const indent = '\u00a0\u00a0'
  function positionOf(e: Editor, text: string) {
    let result = -1
    e.state.doc.descendants((node, pos) => {
      if (node.isText && node.text?.includes(text)) result = pos + node.text.indexOf(text)
    })
    if (result < 0) throw new Error(`未找到正文：${text}`)
    return result
  }
  const tab = (w: Awaited<ReturnType<typeof mountEditor>>, backwards = false) =>
    w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: backwards })

  it('选中一段中的文字缩进整行，保留粗体、链接及反向选区，单次撤销和重做', async () => {
    const w = await mountEditor({ modelValue: '前文 **加粗文字** [链接](https://example.com) 后文' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    const from = positionOf(e, '加粗文字'), to = from + 4
    e.commands.setTextSelection({ from: to, to: from })
    await tab(w)
    expect(e.state.doc.textContent).toBe(indent + '前文 加粗文字 链接 后文')
    expect(e.state.doc.textBetween(e.state.selection.from, e.state.selection.to)).toBe('加粗文字')
    expect(e.state.selection.anchor).toBe(to + 2)
    expect(e.state.selection.head).toBe(from + 2)
    expect(e.getJSON().content![0]!.content!.filter(node => node.marks)).toEqual(original.content![0]!.content!.filter(node => node.marks))
    const indented = e.getJSON()
    e.commands.undo()
    expect(e.getJSON()).toEqual(original)
    e.commands.redo()
    expect(e.getJSON()).toEqual(indented)
    await tab(w, true)
    expect(e.getJSON()).toEqual(original)
    w.unmount()
  })

  it('跨段选区逐行缩进，末端下一段开头不受影响，可连续反缩进且不会删正文', async () => {
    const w = await mountEditor({ modelValue: '第一段\n\n第二段\n\n第三段' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.setTextSelection({ from: positionOf(e, '第一段') + 1, to: positionOf(e, '第三段') })
    await tab(w)
    expect(e.getJSON().content?.map(node => node.content?.map(child => 'text' in child ? child.text : '').join(''))).toEqual([indent + '第一段', indent + '第二段', '第三段'])
    await tab(w, true)
    await tab(w, true)
    expect(e.state.doc.textContent).toBe('第一段第二段第三段')
    expect(e.state.doc.childCount).toBe(3)
    w.unmount()
  })

  it('硬换行按实际选中行缩进，光标和末端边界保持', async () => {
    const w = await mountEditor({ modelValue: '第一行  \n第二行  \n第三行' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.setTextSelection({ from: positionOf(e, '第二行') + 1, to: positionOf(e, '第三行') })
    await tab(w)
    expect(e.state.doc.textContent).toBe('第一行' + indent + '第二行第三行')
    expect(w.findAll('.md-body br')).toHaveLength(2)
    await tab(w, true)
    expect(e.state.doc.textContent).toBe('第一行第二行第三行')
    e.commands.setTextSelection(positionOf(e, '第二行') + 2)
    await tab(w)
    expect(e.state.selection.$from.parentOffset).toBe(8)
    expect(e.state.doc.textContent).toBe('第一行' + indent + '第二行第三行')
    w.unmount()
  })

  it('跨正文、列表、图片和代码的全选保留所有节点与属性，一次撤销还原', async () => {
    const w = await mountEditor({ modelValue: '前文 **粗体**\n\n- 列表甲\n- 列表乙\n\n![图](/api/images/mixed/raw "wao-image-size:50%xauto;title=%E5%9B%BE")\n\n```js\nconst x = 1\nconsole.log(x)\n```\n\n后文', imageControls: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    e.view.dispatch(e.state.tr.setSelection(new AllSelection(e.state.doc)))
    await tab(w)
    const changed = e.getJSON()
    expect(changed.content?.map(node => node.type)).toEqual(original.content?.map(node => node.type))
    expect(changed.content?.find(node => node.type === 'image')?.attrs).toMatchObject({ indent: 1, width: '50%', title: '图', src: '/api/images/mixed/raw' })
    expect(e.state.doc.textContent.replace(/[\u00a0 ]/g, '')).toBe('前文粗体列表甲列表乙constx=1\nconsole.log(x)后文')
    expect(w.findAll('.md-body li')).toHaveLength(2)
    expect(w.findAll('.md-body img')).toHaveLength(1)
    const codeText = changed.content?.find(node => node.type === 'codeBlock')?.content?.[0]
    expect(codeText && 'text' in codeText ? codeText.text : '').toBe('  const x = 1\n  console.log(x)')
    e.commands.undo()
    expect(e.getJSON()).toEqual(original)
    e.commands.redo()
    expect(e.getJSON()).toEqual(changed)
    await tab(w, true)
    expect(e.getJSON()).toEqual(original)
    w.unmount()
  })

  it('Markdown 与富文本回读都保留普通行缩进、格式和混合图片，不把正文变成代码', async () => {
    const w = await mountEditor({ modelValue: '## 标题\n\n**正文**\n\n- 项目\n\n![图片](/api/images/roundtrip/raw)' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.view.dispatch(e.state.tr.setSelection(new AllSelection(e.state.doc)))
    await tab(w)
    const changes = w.emitted('content-change')!
    const rich = (changes[changes.length - 1] as [{ document: RichDocNode; markdown: string }])[0]
    expect(rich.markdown).toContain(indent)
    const json = e.getJSON()
    w.unmount()
    const markdownRestored = await mountEditor({ modelValue: rich.markdown })
    const restored = (markdownRestored.vm as unknown as { editor: Editor }).editor
    expect(restored.getJSON()).toEqual(json)
    expect(restored.isActive('codeBlock')).toBe(false)
    markdownRestored.unmount()
    const jsonRestored = await mountEditor({ modelValue: rich.markdown, document: rich.document })
    expect((jsonRestored.vm as unknown as { editor: Editor }).editor.getJSON()).toEqual(json)
    jsonRestored.unmount()
  })

  it('代码内部分选区只缩进所选逻辑行，跨出代码块仍保留后文节点', async () => {
    const w = await mountEditor({ modelValue: '```js\nalpha()\nbeta()\ngamma()\n```\n\n后文' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    e.commands.setTextSelection({ from: positionOf(e, 'alpha') + 2, to: positionOf(e, 'gamma') })
    await tab(w)
    expect(e.state.doc.firstChild?.textContent).toBe('  alpha()\n  beta()\ngamma()')
    await tab(w, true)
    expect(e.getJSON()).toEqual(original)
    e.commands.setTextSelection({ from: positionOf(e, 'beta') + 2, to: positionOf(e, '后文') + 2 })
    await tab(w)
    expect(e.state.doc.firstChild?.textContent).toBe('alpha()\n  beta()\n  gamma()')
    expect(e.state.doc.lastChild?.textContent).toBe(indent + '后文')
    expect(e.state.doc.lastChild?.type.name).toBe('paragraph')
    w.unmount()
  })

  it('代码光标 Tab 在原处输入，单次撤销保留前一次输入，Shift-Tab 删除所在行前缀', async () => {
    const w = await mountEditor({ modelValue: '```js\nalpha()\n  beta()\n```' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.setTextSelection(positionOf(e, 'alpha') + 2)
    e.commands.insertContent('x')
    const typed = e.getJSON()
    const caret = e.state.selection.from
    await tab(w)
    expect(e.state.doc.textContent).toBe('alx  pha()\n  beta()')
    expect(e.state.selection.from).toBe(caret + 2)
    e.commands.undo()
    expect(e.getJSON()).toEqual(typed)
    e.commands.setTextSelection(positionOf(e, 'beta') + 2)
    await tab(w, true)
    expect(e.state.doc.textContent).toBe('alxpha()\nbeta()')
    expect(e.state.selection.$from.parentOffset).toBe(11)
    w.unmount()
  })

  it('跨表格混合选区保持单元格、行列和格式，图片单独选中不触发列表层级操作', async () => {
    const w = await mountEditor({ modelValue: '前文\n\n| 甲 | 乙 |\n| --- | --- |\n| **一** | 二 |\n\n后文' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.setTextSelection({ from: positionOf(e, '前文'), to: positionOf(e, '后文') + 2 })
    const original = e.getJSON()
    await tab(w)
    expect(w.findAll('tr')).toHaveLength(2)
    expect(w.findAll('th, td')).toHaveLength(4)
    expect(w.get('strong').text()).toBe('一')
    expect(e.state.doc.textContent).toContain('甲')
    expect(e.state.doc.textContent).toContain('后文')
    await tab(w, true)
    expect(e.getJSON()).toEqual(original)
    w.unmount()

    const list = await mountEditor({ modelValue: '', document: { type: 'doc', content: [{ type: 'bulletList', content: [
      { type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: '列表正文' }] }, { type: 'image', attrs: { src: '/api/images/list/raw' } }] },
    ] }] } })
    const listEditor = (list.vm as unknown as { editor: Editor }).editor
    let image = -1
    listEditor.state.doc.descendants((node, position) => { if (node.type.name === 'image') image = position })
    listEditor.commands.setNodeSelection(image)
    await tab(list)
    expect(listEditor.state.selection.from).toBe(image)
    expect(listEditor.state.selection.$from.node(1).type.name).toBe('bulletList')
    expect(listEditor.state.doc.firstChild?.firstChild?.child(1).attrs.indent).toBe(1)
    expect(listEditor.state.doc.textContent).toBe('列表正文')
    await tab(list, true)
    expect(listEditor.state.doc.firstChild?.firstChild?.child(1).attrs.indent).toBe(0)
    list.unmount()
  })

  it('单一列表沿用层级操作，首项整体缩进不删选区；待办仍可下沉和提升', async () => {
    for (const source of ['- 第一项\n- 第二项', '- [ ] 第一项\n- [x] 第二项']) {
      const w = await mountEditor({ modelValue: source })
      const e = (w.vm as unknown as { editor: Editor }).editor
      const original = e.getJSON()
      e.commands.setTextSelection({ from: positionOf(e, '第一项'), to: positionOf(e, '第一项') + 3 })
      await tab(w)
      expect(e.state.doc.firstChild?.attrs.indent).toBe(1)
      expect(e.state.doc.textContent).toBe('第一项第二项')
      await tab(w, true)
      expect(e.getJSON()).toEqual(original)
      e.commands.setTextSelection({ from: positionOf(e, '第二项'), to: positionOf(e, '第二项') + 3 })
      await tab(w)
      expect(e.state.doc.firstChild?.childCount).toBe(1)
      expect(e.state.doc.textContent).toBe('第一项第二项')
      await tab(w, true)
      expect(e.getJSON()).toEqual(original)
      w.unmount()
    }
  })

  it('当前行缩进有界，Shift-Tab 可移除历史 Tab 和空格；后续输入撤销不合并缩进', async () => {
    const w = await mountEditor({ modelValue: '正文' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.setTextSelection(2)
    for (let count = 0; count < 10; count++) await tab(w)
    expect(e.state.doc.textContent).toBe(indent.repeat(8) + '正文')
    for (let count = 0; count < 10; count++) await tab(w, true)
    expect(e.state.doc.textContent).toBe('正文')
    e.commands.insertContent('\t')
    e.commands.setTextSelection(1)
    e.commands.insertContent('\t ')
    await tab(w, true)
    expect(e.state.doc.textContent.startsWith('\t')).toBe(false)
    await tab(w)
    const previous = e.getJSON()
    e.commands.insertContent('继续写')
    e.commands.undo()
    expect(e.getJSON()).toEqual(previous)
    w.unmount()
  })

  it('只读正文、代码、列表、表格和图片的 Tab 与 Shift-Tab 不改文档', async () => {
    const w = await mountEditor({ modelValue: '正文\n\n- 列表\n\n![图](/api/images/readonly/raw)\n\n```js\ncode()\n```\n\n| 甲 | 乙 |\n| --- | --- |\n| 一 | 二 |', readonly: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    for (const text of ['正文', '列表', 'code()', '甲']) {
      const from = positionOf(e, text)
      e.commands.setTextSelection({ from, to: from + text.length })
      const changes = w.emitted('content-change')?.length ?? 0
      await tab(w)
      await tab(w, true)
      expect(e.getJSON()).toEqual(original)
      expect(w.emitted('content-change')?.length ?? 0).toBe(changes)
    }
    let image = -1
    e.state.doc.descendants((node, pos) => { if (node.type.name === 'image') image = pos })
    e.commands.setNodeSelection(image)
    const changes = w.emitted('content-change')?.length ?? 0
    await tab(w)
    expect(e.getJSON()).toEqual(original)
    expect(w.emitted('content-change')?.length ?? 0).toBe(changes)
    w.unmount()
  })
})

describe('列表标记与真实层级缩进', () => {
  function positionOf(e: Editor, text: string) {
    let position = -1
    e.state.doc.descendants((node, pos) => { if (node.isText && node.text?.includes(text)) position = pos + node.text.indexOf(text) })
    if (position < 0) throw new Error(`未找到 ${text}`)
    return position
  }
  const sources = [
    '- 第一项\n- 第二项\n- 第三项\n- 第四项',
    '1. 第一项\n2. 第二项\n3. 第三项\n4. 第四项',
    '- [ ] 第一项\n- [x] 第二项\n- [ ] 第三项\n- [x] 第四项',
  ]

  it.each(sources)('选中多个兄弟项真正挂到前项，反向选区及一次撤销/重做保留：%s', async source => {
    const w = await mountEditor({ modelValue: source })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    e.commands.setTextSelection({ from: positionOf(e, '第三项') + 3, to: positionOf(e, '第二项') })
    await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    const list = e.state.doc.firstChild!
    expect(list.childCount).toBe(2)
    expect(list.firstChild!.lastChild!.type).toBe(list.type)
    expect(list.firstChild!.lastChild!.childCount).toBe(2)
    expect(e.state.doc.firstChild?.textContent).toBe('第一项第二项第三项第四项')
    expect(e.state.selection.anchor).toBeGreaterThan(e.state.selection.head)
    expect(e.state.doc.textBetween(e.state.selection.from, e.state.selection.to)).toBe('第二项第三项')
    const nested = e.getJSON()
    e.commands.undo()
    expect(e.getJSON()).toEqual(original)
    e.commands.redo()
    expect(e.getJSON()).toEqual(nested)
    await w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.getJSON()).toEqual(original)
    w.unmount()
  })

  it.each(sources)('首项或整组选区连标记整体移动，Markdown/JSON回读一致且不造空父项：%s', async source => {
    const w = await mountEditor({ modelValue: source })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    e.commands.selectAll()
    await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(e.state.doc.firstChild?.attrs.indent).toBe(1)
    expect(e.state.doc.firstChild?.childCount).toBe(4)
    const list = w.find('.md-body > .tiptap > ul, .md-body > .tiptap > ol')
    // jsdom 不解析 margin-left:min()；真实样式由有界属性生成，浏览器验证标记实际位移。
    expect(list.attributes('data-list-indent')).toBe('1')
    expect(listIndentStyle(list.attributes('data-list-indent'))).toBe('margin-left:min(24px,50%)')
    expect(e.state.doc.firstChild?.textContent).toBe('第一项第二项第三项第四项')
    const snapshot = e.getJSON(), markdown = e.getMarkdown()
    expect(markdown).toContain('[wao-list-indent]: # "1"')
    const restored = await mountEditor({ modelValue: markdown })
    expect((restored.vm as unknown as { editor: Editor }).editor.getJSON()).toEqual(snapshot)
    expect(restored.text()).not.toContain('wao-list-indent')
    restored.unmount()
    const richRestored = await mountEditor({ modelValue: markdown, document: snapshot as RichDocNode })
    expect((richRestored.vm as unknown as { editor: Editor }).editor.getJSON()).toEqual(snapshot)
    richRestored.unmount()
    await w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.getJSON()).toEqual(original)
    w.unmount()
  })

  it('首项部分选区只移动所选项，编号连续；反缩进后合回原列表', async () => {
    const w = await mountEditor({ modelValue: sources[1]! })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    e.commands.setTextSelection({ from: positionOf(e, '第一项'), to: positionOf(e, '第二项') })
    await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(e.state.doc.childCount).toBe(original.content!.length + 1)
    expect(e.state.doc.child(0).attrs).toMatchObject({ start: 1, indent: 1 })
    expect(e.state.doc.child(1).attrs).toMatchObject({ start: 2, indent: 0 })
    expect(e.state.doc.child(1).childCount).toBe(3)
    expect(e.state.doc.textBetween(e.state.selection.from, e.state.selection.to)).toBe('第一项')
    const restored = await mountEditor({ modelValue: e.getMarkdown() })
    expect((restored.vm as unknown as { editor: Editor }).editor.getJSON()).toEqual(e.getJSON())
    restored.unmount()
    await w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.getJSON()).toEqual(original)
    w.unmount()
  })

  it('跨正文、带子列表的父项及图片只移动一次，旧空白和格式保持', async () => {
    const w = await mountEditor({ modelValue: '前文\n\n- **父项**\n  - 子项\n- \u00a0\u00a0旧空白\n\n![图](/api/images/hierarchy/raw)\n\n后文' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    e.commands.selectAll()
    await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    const list = e.state.doc.child(1)
    expect(list.attrs.indent).toBe(1)
    expect(list.firstChild?.lastChild?.attrs.indent).toBe(0)
    expect(list.textContent).toBe('父项子项\u00a0\u00a0旧空白')
    expect(w.get('strong').text()).toBe('父项')
    expect(e.state.doc.child(2).attrs.indent).toBe(1)
    await w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.getJSON()).toEqual(original)
    w.unmount()
  })

  it('嵌套列表里选中兄弟项继续下沉/提升，已有不同整体偏移的子列表不合并', async () => {
    const w = await mountEditor({ modelValue: '- 父项\n  - 子甲\n  - 子乙\n  - 子丙\n- 后项' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    e.commands.setTextSelection({ from: positionOf(e, '子乙'), to: positionOf(e, '子丙') + 2 })
    await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(e.state.doc.firstChild?.firstChild?.lastChild?.childCount).toBe(1)
    expect(e.state.doc.firstChild?.firstChild?.lastChild?.firstChild?.lastChild?.childCount).toBe(2)
    await w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.getJSON()).toEqual(original)
    w.unmount()
  })

  it('部分反缩进只改变选中项，独立列表属性有界且不同偏移不合并', async () => {
    const w = await mountEditor({ modelValue: sources[1]! })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.selectAll()
    for (let n = 0; n < 10; n++) await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(e.state.doc.firstChild?.attrs.indent).toBe(8)
    e.commands.setTextSelection({ from: positionOf(e, '第二项'), to: positionOf(e, '第二项') + 3 })
    await w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.state.doc.childCount).toBe(4)
    expect([0, 1, 2].map(n => e.state.doc.child(n).attrs.indent)).toEqual([8, 7, 8])
    expect([0, 1, 2].map(n => e.state.doc.child(n).attrs.start)).toEqual([1, 2, 3])
    expect(e.state.doc.textContent.trimEnd()).toBe('第一项第二项第三项第四项')
    const restored = await mountEditor({ modelValue: e.getMarkdown() })
    expect((restored.vm as unknown as { editor: Editor }).editor.getJSON()).toEqual(e.getJSON())
    restored.unmount()
    w.unmount()
  })

  it('已有整体缩进的罗马数字列表仍可下沉/提升，编号与反向选区原样恢复', async () => {
    const w = await mountEditor({ modelValue: '', document: { type: 'doc', content: [
      { type: 'orderedList', attrs: { start: 4, type: 'i', indent: 2 }, content: ['第一项', '第二项'].map(text =>
        ({ type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text }] }] })) },
    ] } })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    e.commands.setTextSelection({ from: positionOf(e, '第二项') + 3, to: positionOf(e, '第二项') })
    const { anchor, head } = e.state.selection
    await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(e.state.doc.firstChild?.attrs).toMatchObject({ start: 4, type: 'i', indent: 2 })
    expect(e.state.doc.firstChild?.firstChild?.lastChild?.attrs.indent).toBe(0)
    expect(e.state.doc.textBetween(e.state.selection.from, e.state.selection.to)).toBe('第二项')
    await w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.getJSON()).toEqual(original)
    expect(e.state.selection.anchor).toBe(anchor)
    expect(e.state.selection.head).toBe(head)
    const restored = await mountEditor({ modelValue: e.getMarkdown() })
    expect((restored.vm as unknown as { editor: Editor }).editor.getJSON()).toEqual(original)
    restored.unmount()
    w.unmount()
  })

  it('嵌套列表首项独立移动可Markdown往返，节点选中整组只移动该子列表', async () => {
    const w = await mountEditor({ modelValue: '- 父项\n  - 子甲\n  - 子乙\n- 后项' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    e.commands.setTextSelection({ from: positionOf(e, '子甲'), to: positionOf(e, '子甲') + 2 })
    await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(e.state.doc.firstChild?.attrs.indent).toBe(0)
    expect(e.state.doc.firstChild?.firstChild?.child(1).attrs.indent).toBe(1)
    const restored = await mountEditor({ modelValue: e.getMarkdown() })
    expect((restored.vm as unknown as { editor: Editor }).editor.getJSON()).toEqual(e.getJSON())
    restored.unmount()
    await w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.getJSON()).toEqual(original)
    let nested = -1
    e.state.doc.descendants((node, position) => { if (node.type.name === 'bulletList' && node.textContent === '子甲子乙') nested = position })
    e.commands.setNodeSelection(nested)
    await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(e.state.doc.firstChild?.attrs.indent).toBe(0)
    expect(e.state.doc.firstChild?.firstChild?.lastChild?.attrs.indent).toBe(1)
    expect(e.state.selection.from).toBe(nested)
    w.unmount()
  })

  it('仅选中列表内代码块仍逐行缩进代码，不操作外层列表', async () => {
    const w = await mountEditor({ modelValue: '', document: { type: 'doc', content: [
      { type: 'bulletList', content: [{ type: 'listItem', content: [
        { type: 'paragraph', content: [{ type: 'text', text: '前文不移动' }] },
        { type: 'codeBlock', attrs: { language: 'js' }, content: [{ type: 'text', text: 'alpha()\nbeta()' }] },
      ] }] },
    ] } })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const original = e.getJSON()
    e.commands.setTextSelection({ from: positionOf(e, 'alpha') + 1, to: positionOf(e, 'beta') + 4 })
    await w.get('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(e.state.doc.firstChild?.attrs.indent).toBe(0)
    expect(e.state.doc.firstChild?.firstChild?.lastChild?.textContent).toBe('  alpha()\n  beta()')
    expect(e.state.doc.firstChild?.firstChild?.firstChild?.textContent).toBe('前文不移动')
    await w.get('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.getJSON()).toEqual(original)
    w.unmount()
  })
})

describe('编辑区空白处', () => {
  it('点击正文下方空白处会聚焦可编辑节点', async () => {
    const wrapper = await mountEditor({ modelValue: '' })
    const body = wrapper.get('.md-body')
    expect(document.activeElement).not.toBe(wrapper.get('.tiptap').element)
    await body.trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(document.activeElement).toBe(wrapper.get('.tiptap').element)
    wrapper.unmount()
  })
})

describe('文章总览配图', () => {
  it('切换小记后不把上一篇生成图认成这一篇可替换的总览图', async () => {
    const w = await mountEditor({ modelValue: '原正文', articleImageKey: 'note:me:first' })
    const vm = w.vm as unknown as { editor: Editor; insertArticleImageAtStart: (url: string) => boolean }
    vm.insertArticleImageAtStart('/api/images/previous/raw')
    await w.setProps({ articleImageKey: 'note:me:second' })
    vm.insertArticleImageAtStart('/api/images/current/raw')
    const markdown = vm.editor.getMarkdown()
    expect(markdown).toContain('/api/images/current/raw')
    expect(markdown).toContain('/api/images/previous/raw')
    expect(markdown).toContain('原正文')
    w.unmount()
  })
  it('迟到总览图插入和替换映射原光标，不跳到图片或覆盖新输入', async () => {
    const w = await mountEditor({ modelValue: '正在输入的新内容。' })
    const vm = w.vm as unknown as { editor: Editor; insertArticleImageAtStart: (url: string) => boolean }
    vm.editor.commands.setTextSelection(6)
    const previous = vm.editor.state.selection.$from.parentOffset
    expect(vm.insertArticleImageAtStart('/api/images/late/raw')).toBe(true)
    expect(vm.editor.state.selection.$from.parent.textContent).toBe('正在输入的新内容。')
    expect(vm.editor.state.selection.$from.parentOffset).toBe(previous)
    vm.editor.commands.insertContent('继续写')
    const updated = vm.editor.state.doc.textContent
    const nextOffset = vm.editor.state.selection.$from.parentOffset
    expect(vm.insertArticleImageAtStart('/api/images/replacement/raw')).toBe(true)
    expect(vm.editor.state.doc.textContent).toBe(updated)
    expect(vm.editor.state.selection.$from.parentOffset).toBe(nextOffset)
    expect(vm.editor.state.selection.$from.parent.type.name).toBe('paragraph')
    w.unmount()
  })
  it('小记只读正文不请求隐藏的原图', async () => {
    const wrapper = await mountEditor({ modelValue: '![文章总览图](/api/images/img1/raw)', readonly: true, previewImages: true })
    expect(wrapper.get('.md-body img').attributes('src')).toBe('/api/images/img1/preview')
    expect(wrapper.get('.md-body img').attributes('data-full-src')).toBe('/api/images/img1/raw')
    await wrapper.get('.md-body img').trigger('contextmenu')
    expect(wrapper.getComponent(ImageCopyMenu).props('src')).toBe('/api/images/img1/raw')
    expect((wrapper.vm as unknown as { editor: Editor }).editor.getMarkdown()).toContain('/api/images/img1/raw')
    wrapper.unmount()
  })
  it('根据正文生成图片并插入开头，保留原文', async () => {
    vi.mocked(fetchPreference).mockResolvedValue('')
    vi.mocked(startArticleImage).mockResolvedValue({ id: 'ac-image', status: 'QUEUED', message: '等待生成', imageUrl: null })
    vi.mocked(fetchArticleImage).mockResolvedValue({ id: 'ac-image', status: 'SUCCEEDED', message: '已生成', imageUrl: '/api/images/img1/raw' })
    const w = await mountEditor({ modelValue: '第一段。\n\n第二段。', articleImageKey: 'note:me:n1' })
    await flushPromises()

    await w.get('.generate-image').trigger('click')
    await flushPromises()

    expect(startArticleImage).toHaveBeenCalledWith('第一段。第二段。', expect.stringContaining('总览图'), 'gpt-6-astra', expect.stringMatching(/^[a-f0-9]{32}$/))
    const updates = w.emitted('update:modelValue') as [string][]
    const latest = updates[updates.length - 1]?.[0]
    expect(latest).toMatch(/^!\[文章总览图\]\(\/api\/images\/img1\/raw "wao-image-size:50%xauto"\)/)
    expect(latest).toContain('第一段。')
    expect(latest).toContain('第二段。')
    const image = (w.vm as unknown as { editor: Editor }).editor.getJSON().content?.[0]
    expect(image?.attrs).toMatchObject({ src: '/api/images/img1/raw', width: '50%', align: 'center' })
    expect(w.get('.md-body img').attributes('style')).toContain('width: 50%')
    expect(w.get('.md-body img').attributes('style')).toContain('margin-left: auto')
    expect(latest).toContain('wao-image-size:50%xauto')
    w.unmount()
  })

  it('个人提示词可单独保存', async () => {
    vi.mocked(fetchPreference).mockResolvedValue('自定义画面')
    vi.mocked(savePreference).mockResolvedValue()
    const w = await mountEditor({ modelValue: '正文', articleImageKey: 'document:me:d1' })
    await flushPromises()
    await w.get('.prompt-settings').trigger('click')
    const input = document.body.querySelector<HTMLTextAreaElement>('.image-prompt-dialog textarea')
    expect(input?.value).toBe('自定义画面')
    if (!input) throw new Error('没有找到个人提示词输入框')
    input.value = '另一种画面'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    document.body.querySelector<HTMLButtonElement>('.image-prompt-dialog button.primary')?.click()
    await flushPromises()
    expect(savePreference).toHaveBeenCalledWith('article-overview-image-prompt-v1', '另一种画面')
    w.unmount()
  })

  it('小记设置集中配置 GPT 模型及三类提示词并按成员偏好保存', async () => {
    setActivePinia(createPinia())
    const settings = { title: true, polish: true, image: true, executionMode: 'parallel' as const, maxConcurrent: 3 as const, model: '', reasoningEffort: 'medium', titlePrompt: '', imagePrompt: '', polishPrompt: '默认正文润色方法', titleEpoch: 0, imageEpoch: 0, polishEpoch: 0 }
    vi.mocked(fetchNoteAiSettings).mockResolvedValue({ settings, version: 'v1', configured: true, availability: '' })
    vi.mocked(saveNoteAiSettings).mockResolvedValue({ settings, version: 'v2', configured: true, availability: '' })
    vi.mocked(fetchPreference).mockResolvedValue('')
    vi.mocked(savePreference).mockResolvedValue()
    const w = await mountEditor({ modelValue: '小记正文', articleImageKey: 'note:me:n1', noteTitleOnImage: true, allowH1: true })
    await flushPromises()
    await w.get('.prompt-settings').trigger('click'); await flushPromises()
    const modal = document.body.querySelector<HTMLElement>('[role="dialog"][aria-label="小记设置"]')!
    expect(modal.querySelector<HTMLSelectElement>('[aria-label="小记处理模型"]')?.value).toBe('')
    expect(modal.textContent).toContain('标题提示词')
    expect(modal.textContent).toContain('正文润色')
    expect(modal.textContent).toContain('配图提示词')
    const select = modal.querySelector<HTMLSelectElement>('[aria-label="小记处理模型"]')!
    select.value = 'gpt-6-luna'
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    const save = [...modal.querySelectorAll('button')].find(button => button.textContent?.includes('保存小记 AI 设置'))!
    save.click()
    await flushPromises()
    expect(saveNoteAiSettings).toHaveBeenCalledWith(expect.objectContaining({ model: 'gpt-6-luna', polishPrompt: '默认正文润色方法' }), 'v1')
    w.unmount()
  })

  it('再次生成时只替换已标记的首图', async () => {
    const w = await mountEditor({ modelValue: '正文' })
    const editor = w.vm as unknown as { insertArticleImageAtStart: (url: string) => boolean }
    expect(editor.insertArticleImageAtStart('/api/images/first/raw')).toBe(true)
    expect(editor.insertArticleImageAtStart('/api/images/second/raw')).toBe(true)
    const updates = w.emitted('update:modelValue') as [string][]
    const latest = updates[updates.length - 1]?.[0]
    expect(latest).toContain('![文章总览图]')
    expect(latest).toContain('/api/images/second/raw')
    expect(latest).not.toContain('/api/images/first/raw')
    expect(latest).toContain('正文')
    expect((w.vm as unknown as { editor: Editor }).editor.getJSON().content?.[0]?.attrs).toMatchObject({ width: '50%', align: 'center' })
    w.unmount()
  })

  it('重新生成先预览，保留原图或确认替换由用户决定', async () => {
    vi.mocked(fetchPreference).mockResolvedValue('')
    vi.mocked(startArticleImage).mockResolvedValue({ id: 'ac-replace', status: 'QUEUED', message: '等待生成', imageUrl: null })
    vi.mocked(fetchArticleImage).mockResolvedValue({ id: 'ac-replace', status: 'SUCCEEDED', message: '已生成', imageUrl: '/api/images/new/raw' })
    const w = await mountEditor({ modelValue: '正文', articleImageKey: 'note:me:replace' })
    const editor = w.vm as unknown as { insertArticleImageAtStart: (url: string) => boolean; editor: Editor }
    expect(editor.insertArticleImageAtStart('/api/images/old/raw')).toBe(true)
    await flushPromises()
    await w.get('.generate-image').trigger('click')
    await flushPromises()
    expect(editor.editor.getJSON().content?.[0]?.attrs?.src).toBe('/api/images/old/raw')
    expect(document.body.querySelector('.replacement-dialog')?.textContent).toContain('保留原图')
    const buttons = document.body.querySelectorAll<HTMLButtonElement>('.replacement-dialog button')
    buttons[0]?.click()
    await flushPromises()
    expect(editor.editor.getJSON().content?.[0]?.attrs?.src).toBe('/api/images/old/raw')
    await w.get('.generate-image').trigger('click')
    await flushPromises()
    document.body.querySelector<HTMLButtonElement>('.replacement-dialog button.primary')?.click()
    await flushPromises()
    expect(editor.editor.getJSON().content?.[0]?.attrs?.src).toBe('/api/images/new/raw')
    w.unmount()
  })
})

/**
 * jsdom 没有实现 DataTransfer，这里给 paste 事件挂一个最小替身。
 *
 * 除了编辑器要用的 `files`，还得补上 `getData` / `types`：没有图片时我们的
 * 处理器会放行，ProseMirror 自己的粘贴逻辑接手后会调它们。
 */
function pasteInto(el: Element, files: File[]) {
  const event = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'clipboardData', {
    value: { files, items: [], types: [], getData: () => '' },
  })
  el.dispatchEvent(event)
}

/** 模拟某些文档应用只把 Base64 <img> 放进剪贴板的行为。 */
function pasteHtmlInto(el: Element, html: string) {
  const event = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'clipboardData', {
    value: {
      files: [],
      items: [],
      types: ['text/html'],
      getData: (type: string) => (type === 'text/html' ? html : ''),
    },
  })
  el.dispatchEvent(event)
}

describe('MarkdownEditor（所见即所得）', () => {
  it('图片嵌入模式右键改名使用原相对地址，清空名称回到默认编号', async () => {
    const w = await mountEditor({ modelValue: '![自定义旧名](/api/images/one/raw)', imageControls: true, imageContextControls: true, previewImages: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    await w.get('.md-body img').trigger('contextmenu', { clientX: 20, clientY: 30 }); await flushPromises()
    const input = document.body.querySelector<HTMLInputElement>('[aria-label="图片名称"]')!
    expect(document.activeElement).toBe(input)
    input.value = ''; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
    document.body.querySelector<HTMLFormElement>('.note-image-menu')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await nextTick()
    expect(e.getJSON().content![0]?.attrs).toMatchObject({ src: '/api/images/one/raw', alt: '' })
    expect(input.value).toBe('图片 1')
    w.unmount()
  })
  it('菜单打开后选中位置改变，不把名称写到另一张相同原图，输入仍可复制保留', async () => {
    const w = await mountEditor({ modelValue: '![image.png](/api/images/one/raw)\n\n![image.png](/api/images/one/raw)', imageControls: true, imageContextControls: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const handle = w.vm as unknown as { renameImageAt: (index: number, event: MouseEvent) => boolean }
    handle.renameImageAt(1, new MouseEvent('click')); await flushPromises()
    const input = document.body.querySelector<HTMLInputElement>('[aria-label="图片名称"]')!
    input.value = '待保存名称'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
    const before = e.getJSON()
    e.commands.setNodeSelection(e.state.doc.firstChild!.nodeSize)
    document.body.querySelector<HTMLFormElement>('.note-image-menu')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await nextTick()
    expect(e.getJSON()).toEqual(before)
    expect(document.body.textContent).toContain('图片位置已变化')
    expect(input.value).toBe('待保存名称')
    e.commands.setTextSelection(0); await nextTick()
    expect(document.body.querySelector<HTMLInputElement>('[aria-label="图片名称"]')?.value).toBe('待保存名称')
    w.unmount()
  })
  it('改名只更新指定图片，保留正文、地址、尺寸与缩进，支持撤销和 Markdown 重载', async () => {
    const original = '开头保留\n\n![image.png](/api/images/one/raw "wao-image-size:50%xauto;indent=1")\n\n中间保留\n\n![image.png](/api/images/one/raw)\n\n结尾保留'
    const w = await mountEditor({ modelValue: original, imageControls: true, imageContextControls: true, imageReferences: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const before = e.getJSON()
    expect(w.text()).toContain('图片 1')
    expect(w.emitted('update:modelValue')).toBeUndefined()
    const handle = w.vm as unknown as { renameImageAt: (index: number, event: MouseEvent) => boolean }
    expect(handle.renameImageAt(2, new MouseEvent('click'))).toBe(true)
    await flushPromises()
    expect(document.body.querySelector<HTMLDetailsElement>('.note-image-menu details')?.open).toBe(false)
    const input = document.body.querySelector<HTMLInputElement>('[aria-label="图片名称"]')!
    input.value = '任务进展 [第 1 周]'; input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    document.body.querySelector<HTMLFormElement>('.note-image-menu')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await nextTick()
    const images = e.getJSON().content!.filter(n => n.type === 'image')
    expect(images[0]).toEqual(before.content!.find(n => n.type === 'image'))
    expect(images[1]?.attrs).toMatchObject({ src: '/api/images/one/raw', alt: '任务进展 [第 1 周]' })
    expect(e.getJSON().content!.filter(n => n.type !== 'image')).toEqual(before.content!.filter(n => n.type !== 'image'))
    expect(w.emitted('upload-image')).toBeUndefined()
    const restored = await mountEditor({ modelValue: e.getMarkdown(), imageReferences: true })
    expect((restored.vm as unknown as { editor: Editor }).editor.getJSON()).toEqual(e.getJSON())
    e.commands.undo()
    expect(e.getJSON()).toEqual(before)
    restored.unmount(); w.unmount()
  })
  it('小记左键不打开设置，右键统一高度可一次撤销且不上传', async () => {
    const w = await mountEditor({ modelValue: '![一](/api/images/one/raw "wao-image-size:50%xauto")\n\n![二](/api/images/two/raw "wao-image-size:50%xauto")', imageControls: true, imageContextControls: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    await w.get('.md-body img').trigger('click')
    expect(w.findComponent(ImageCopyMenu).exists()).toBe(false)
    await w.get('.md-body img').trigger('contextmenu', { clientX: 20, clientY: 30 })
    await flushPromises()
    expect(w.findComponent(ImageCopyMenu).exists()).toBe(true)
    expect(document.body.textContent).not.toContain('向右旋转')
    const uniform = [...document.body.querySelectorAll<HTMLButtonElement>('.note-image-menu button')].find(x => x.textContent?.includes('统一本篇'))!
    const height = document.body.querySelector<HTMLInputElement>('[aria-label="图片高度（像素）"]')!
    height.value = '180'
    height.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    uniform.click()
    await nextTick()
    const images = () => e.getJSON().content!.filter(n => n.type === 'image')
    expect(images().map(n => n.attrs?.height)).toEqual([180, 180])
    expect(images().map(n => n.attrs?.src)).toEqual(['/api/images/one/raw', '/api/images/two/raw'])
    expect(e.getMarkdown()).toContain('wao-image-size:autox180')
    expect(w.emitted('upload-image')).toBeUndefined()
    e.commands.undo()
    expect(images().map(n => n.attrs?.width)).toEqual(['50%', '50%'])
    expect(images().map(n => n.attrs?.height)).toEqual([null, null])
    w.unmount()
  })

  it('小记百分比立即生效，单张与全篇范围独立且菜单保持打开', async () => {
    const w = await mountEditor({ modelValue: '![一](/api/images/one/raw)\n\n![二](/api/images/two/raw)', imageControls: true, imageContextControls: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    await w.get('.md-body img').trigger('contextmenu', { clientX: 20, clientY: 30 })
    await flushPromises()
    const preset = (text: string) => [...document.body.querySelectorAll<HTMLButtonElement>('.note-image-menu .image-scale-options button')].find(b => b.textContent === text)!
    preset('25%').click()
    await nextTick()
    const images = () => e.getJSON().content!.filter(n => n.type === 'image')
    expect(images().map(n => n.attrs?.width)).toEqual(['25%', null])
    expect(document.body.querySelector('.note-image-menu')).not.toBeNull()
    expect(preset('25%').getAttribute('aria-pressed')).toBe('true')
    e.commands.undo()
    await nextTick()
    const all = [...document.body.querySelectorAll<HTMLButtonElement>('.image-size-scope button')].find(b => b.textContent === '本篇全部')!
    all.click()
    await nextTick()
    preset('75%').click()
    await nextTick()
    expect(images().map(n => n.attrs?.width)).toEqual(['75%', '75%'])
    expect(images().map(n => n.attrs?.height)).toEqual([null, null])
    expect(w.emitted('upload-image')).toBeUndefined()
    e.commands.undo()
    expect(images().map(n => n.attrs?.width)).toEqual([null, null])
    w.unmount()
  })

  it('小记固定宽高与回车分行保存到正文结构，全屏按钮读取原图', async () => {
    const w = await mountEditor({ modelValue: '![一](/api/images/one/raw)\n\n![二](/api/images/two/raw)', imageControls: true, imageContextControls: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.setNodeSelection(0)
    await w.get('.md-body').trigger('keydown', { key: 'Enter' })
    expect(e.getJSON().content!.slice(0, 3).map(n => n.type)).toEqual(['image', 'paragraph', 'image'])
    await w.get('.md-body img').trigger('contextmenu', { clientX: 20, clientY: 30 })
    await flushPromises()
    for (const [label, value] of [['图片宽度（像素）', '320'], ['图片高度（像素）', '160']]) {
      const input = document.body.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!
      input.value = value!; input.dispatchEvent(new Event('input', { bubbles: true }))
    }
    await nextTick()
    ;[...document.body.querySelectorAll<HTMLButtonElement>('.note-image-menu button')].find(b => b.textContent?.includes('应用固定尺寸'))!.click()
    await nextTick()
    expect(e.getJSON().content![0]!.attrs).toMatchObject({ width: 320, height: 160 })
    const restored = await mountEditor({ modelValue: e.getMarkdown(), document: e.getJSON() as RichDocNode, imageContextControls: true })
    expect((restored.vm as unknown as { editor: Editor }).editor.getJSON().content!.slice(0, 3).map(n => n.type)).toEqual(['image', 'paragraph', 'image'])
    await w.get('.note-image-fullscreen').trigger('click')
    expect(document.body.querySelector('.image-lightbox img')).toBeTruthy()
    restored.unmount(); w.unmount()
  })
  it('按正文统计字形并在静默载入、编辑与撤销时更新', async () => {
    setActivePinia(createPinia())
    const w = await mountEditor({ modelValue: '**你好** [AB](https://example.com) 👨‍👩‍👧‍👦\n\n![图片](https://example.com/a.png)' })
    const count = () => w.get('[aria-label="当前正文字数"]').text()
    expect(count()).toBe('5 字')
    expect(w.emitted('character-count')?.slice(-1)[0]).toEqual([5])
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.chain().setTextSelection(2).insertContent('！').run()
    await nextTick()
    expect(count()).toBe('6 字')
    e.commands.undo()
    await nextTick()
    expect(count()).toBe('5 字')
    await w.setProps({ modelValue: '新正文\n\n123' })
    expect(count()).toBe('6 字')
    await w.setProps({ modelValue: '', readonly: true })
    expect(count()).toBe('0 字')
    w.unmount()
  })

  it('划选正文时显示选中字数，取消划选后恢复全文数字', async () => {
    const w = await mountEditor({ modelValue: '中文 A 👨‍👩‍👧‍👦！' })
    const count = () => w.get('[aria-label="当前正文字数"]').text()
    const e = (w.vm as unknown as { editor: Editor }).editor
    expect(count()).toBe('5 字')
    e.commands.setTextSelection({ from: 1, to: 3 })
    await nextTick()
    expect(count()).toBe('已选中 2 字 · 全文 5 字')
    e.commands.setTextSelection({ from: 1, to: e.state.doc.content.size - 1 })
    await nextTick()
    expect(count()).toBe('已选中 5 字 · 全文 5 字')
    e.commands.setTextSelection(1)
    await nextTick()
    expect(count()).toBe('5 字')
    w.unmount()
  })

  async function tableEditor() {
    const w = await mountEditor({ modelValue: '', advanced: true, linkControls: true })
    const instance = w.vm as unknown as { editor: Editor; shouldShowSelection: (context: unknown) => boolean }
    instance.editor.commands.insertTable({ rows: 2, cols: 2, withHeaderRow: false })
    await nextTick()
    const cells: number[] = []
    instance.editor.state.doc.descendants((node, pos) => { if (node.type.name === 'tableCell') cells.push(pos) })
    const show = () => {
      instance.editor.view.focus()
      const { state } = instance.editor
      return instance.shouldShowSelection({ editor: instance.editor, state, from: state.selection.from, to: state.selection.to })
    }
    return { w, e: instance.editor, cells, show }
  }

  it('单元格光标及单格选择保持安静，空白多单元格选择才显示表格操作', async () => {
    const { w, e, cells, show } = await tableEditor()
    e.commands.setTextSelection(cells[0]! + 2)
    expect(show()).toBe(false)
    e.commands.setCellSelection({ anchorCell: cells[0]! })
    expect(show()).toBe(false)
    e.commands.setCellSelection({ anchorCell: cells[0]!, headCell: cells[1]! })
    expect(show()).toBe(true)
    await nextTick()
    expect(document.querySelectorAll('.selectedCell')).toHaveLength(2)
    w.unmount()
  })

  it('整表选中后 Backspace 删除结构且可撤销，部分单元格不删除整表', async () => {
    const { w, e, cells } = await tableEditor()
    e.commands.setCellSelection({ anchorCell: cells[0]!, headCell: cells[1]! })
    e.view.dom.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true, cancelable: true }))
    expect(e.getJSON().content?.some(node => node.type === 'table')).toBe(true)
    e.commands.setCellSelection({ anchorCell: cells[0]!, headCell: cells[3]! })
    e.view.dom.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true, cancelable: true }))
    expect(e.getJSON().content?.some(node => node.type === 'table')).toBe(false)
    e.commands.undo()
    expect(e.getJSON().content?.some(node => node.type === 'table')).toBe(true)
    w.unmount()
  })

  it('单个表格光标也能从工具栏删除整个表格并撤销', async () => {
    const { w, e, cells } = await tableEditor()
    e.commands.setTextSelection(cells[0]! + 2)
    await nextTick()
    await vi.waitFor(() => expect(w.find('[aria-label="删除当前表格"]').exists()).toBe(true))
    await w.get('[aria-label="删除当前表格"]').trigger('click')
    expect(e.getJSON().content?.some(node => node.type === 'table')).toBe(false)
    e.commands.undo()
    expect(e.getJSON().content?.some(node => node.type === 'table')).toBe(true)
    w.unmount()
  })

  it('右键命中另一个单元格，禁用不可合并和不可拆分操作；新增行可撤销', async () => {
    const { w, e, cells, show } = await tableEditor()
    e.commands.setTextSelection(cells[0]! + 2)
    await w.findAll('td')[3]!.trigger('contextmenu', { clientX: 10000, clientY: 10000 })
    await flushPromises()
    expect((e.state.selection as CellSelection).$anchorCell.pos).toBe(cells[3])
    expect(show()).toBe(false)
    const menu = document.querySelector<HTMLElement>('.table-context-menu')!
    const buttons = [...menu.querySelectorAll<HTMLButtonElement>('button')]
    expect(buttons.find(b => b.textContent === '合并单元格')!.disabled).toBe(true)
    expect(buttons.find(b => b.textContent === '拆分单元格')!.disabled).toBe(true)
    expect(Number.parseFloat(menu.style.left)).toBeLessThanOrEqual(window.innerWidth - 8)
    buttons.find(b => b.textContent === '向下插入行')!.click()
    await nextTick()
    expect(w.findAll('tr')).toHaveLength(3)
    expect(document.querySelector('.table-context-menu')).toBeNull()
    e.commands.undo()
    await nextTick()
    expect(w.findAll('tr')).toHaveLength(2)
    w.unmount()
  })

  it('已有多选范围内右键保留选区，合并和拆分均保留表格内容', async () => {
    const { w, e, cells } = await tableEditor()
    e.commands.setCellSelection({ anchorCell: cells[0]!, headCell: cells[1]! })
    await w.findAll('td')[1]!.trigger('mousedown', { button: 2 })
    await w.findAll('td')[1]!.trigger('contextmenu')
    await flushPromises()
    expect((e.state.selection as CellSelection).$anchorCell.pos).toBe(cells[0])
    expect((e.state.selection as CellSelection).$headCell.pos).toBe(cells[1])
    const clickMenu = (label: string) => [...document.querySelectorAll<HTMLButtonElement>('.table-context-menu button')].find(b => b.textContent === label)!.click()
    clickMenu('合并单元格')
    await nextTick()
    expect(w.find('td').attributes('colspan')).toBe('2')
    await w.find('td').trigger('contextmenu')
    await flushPromises()
    clickMenu('拆分单元格')
    await nextTick()
    expect(w.findAll('td')).toHaveLength(4)
    w.unmount()
  })

  it('Esc 和点击外部收起右键菜单，只读切换与卸载不残留浮层', async () => {
    const { w } = await tableEditor()
    await w.find('td').trigger('contextmenu')
    await flushPromises()
    document.querySelector('.table-context-menu')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await nextTick()
    expect(document.querySelector('.table-context-menu')).toBeNull()
    await w.find('td').trigger('contextmenu')
    await flushPromises()
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await nextTick()
    expect(document.querySelector('.table-context-menu')).toBeNull()
    await w.find('td').trigger('contextmenu')
    await flushPromises()
    await w.setProps({ readonly: true })
    expect(document.querySelector('.table-context-menu')).toBeNull()
    await w.find('td').trigger('contextmenu')
    expect(document.querySelector('.table-context-menu')).toBeNull()
    w.unmount()
  })

  it('表格 Tab 和 Shift+Tab 切换单元格，Esc 不丢焦点且不改变正文', async () => {
    const { w, e, cells, show } = await tableEditor()
    e.commands.setTextSelection(cells[0]! + 2)
    const before = e.getJSON()
    await w.find('.tiptap').trigger('keydown', { key: 'Tab' })
    expect(e.state.selection.from).toBe(cells[1]! + 2)
    await w.find('.tiptap').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(e.state.selection.from).toBe(cells[0]! + 2)
    e.commands.setCellSelection({ anchorCell: cells[0]!, headCell: cells[1]! })
    expect(show()).toBe(true)
    await w.find('.tiptap').trigger('keydown', { key: 'Escape' })
    expect(show()).toBe(false)
    expect(e.getJSON()).toEqual(before)
    expect(e.isFocused).toBe(true)
    w.unmount()
  })

  it('高级小记保存期间切换只读、失败后恢复编辑不破坏浮层与正文', async () => {
    const w = await mountEditor({ modelValue: '保存后仍可继续编辑', advanced: true, compact: true })
    await w.setProps({ readonly: true })
    await flushPromises()
    expect(w.find('.tiptap').attributes('contenteditable')).toBe('false')
    await w.setProps({ readonly: false })
    await flushPromises()
    expect(w.find('.tiptap').attributes('contenteditable')).toBe('true')
    expect(w.find('.tiptap').text()).toBe('保存后仍可继续编辑')
    expect(w.find('.advanced-toolbar').exists()).toBe(true)
    w.unmount()
  })

  it('没有编辑 / 预览两态——挂载即是可编辑的富文本，标题直接渲染成 h2', async () => {
    const w = await mountEditor({ modelValue: '## 目标\n\n正文' })

    const body = w.find('.md-body').html()
    expect(body).toContain('<h2>目标</h2>')
    expect(body).toContain('contenteditable="true"')
    // 不该出现源码态的 ## 或旧版的 textarea
    expect(w.find('textarea').exists()).toBe(false)
    expect(w.find('.md-body').text()).not.toContain('##')
    w.unmount()
  })

  it('文档场景可启用一级标题，供右侧目录形成层级', async () => {
    const w = await mountEditor({ modelValue: '# 每日计划\n\n## 今日重点', allowH1: true })

    expect(w.find('.md-body h1').text()).toBe('每日计划')
    expect(w.find('.md-body h2').text()).toBe('今日重点')
    w.unmount()
  })

  it('单元格多段正文保留结构并生成安全换行投影，重开后仍可编辑', async () => {
    const w = await mountEditor({ modelValue: '', advanced: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const paragraph = (text: string) => ({ type: 'paragraph', content: [{ type: 'text', text }] })
    e.commands.setContent({ type: 'doc', content: [{ type: 'table', content: [
      { type: 'tableRow', content: [{ type: 'tableHeader', content: [paragraph('细节')] }] },
      { type: 'tableRow', content: [{ type: 'tableCell', content: [paragraph('梳理清单'), paragraph('然后执行')] }] },
    ] }] })
    await nextTick()
    const changes = w.emitted('content-change') as [{ document: RichDocNode; markdown: string }][]
    const saved = changes[changes.length - 1]![0]
    expect(saved.markdown).toContain('梳理清单<br>然后执行')
    expect(saved.markdown).not.toMatch(/<(?!(?:br\s*\/?>))/i)
    const reopened = await mountEditor({ modelValue: saved.markdown, document: saved.document, advanced: true })
    expect(reopened.findAll('td p').map(p => p.text())).toEqual(['梳理清单', '然后执行'])
    reopened.unmount()
    w.unmount()
  })

  it('高级文档显示固定工具栏并把表格同时输出为 JSON 与 Markdown', async () => {
    const w = await mountEditor({ modelValue: '', allowH1: true, advanced: true })
    expect(w.find('.advanced-toolbar').exists()).toBe(true)

    ;(w.vm as unknown as { insertTable: () => void }).insertTable()
    await nextTick()

    const changes = w.emitted('content-change') as [{ document: RichDocNode; markdown: string }][]
    const latest = changes[changes.length - 1]?.[0]
    expect(latest?.document.content?.some((node: RichDocNode) => node.type === 'table')).toBe(true)
    expect(latest?.markdown).toContain('|')
    w.unmount()
  })

  it('知识库顶部工具栏保持可见并操作同一个编辑器，其他调用保持折叠入口', async () => {
    const host = document.createElement('div')
    document.body.append(host)
    const w = await mountEditor({ modelValue: '正文', advanced: true, toolbarTarget: host })
    const e = (w.vm as unknown as { editor: Editor }).editor
    expect(w.find('.toolbar-toggle').exists()).toBe(false)
    expect(host.querySelector<HTMLElement>('.advanced-toolbar')?.style.display).not.toBe('none')
    e.commands.selectAll()
    host.querySelector<HTMLButtonElement>('button[title="粗体"]')!.click()
    await nextTick()
    expect(e.getHTML()).toContain('<strong>正文</strong>')
    expect(host.querySelector('[aria-label="当前正文字数"]')?.textContent?.trim()).toBe('已选中 2 字 · 全文 2 字')
    await w.setProps({ readonly: true, modelValue: '正文更新' })
    expect(host.querySelector('[aria-label="当前正文字数"]')?.textContent?.trim()).toBe('已选中 2 字 · 全文 4 字')
    expect(host.querySelector('.editor-tools')).toBeNull()
    await w.setProps({ readonly: false })
    await w.setProps({ toolbarTarget: null })
    expect(host.querySelector('[aria-label="当前正文字数"]')).toBeNull()
    expect(w.get('[aria-label="当前正文字数"]').text()).toBe('已选中 2 字 · 全文 4 字')
    expect((w.vm as unknown as { editor: Editor }).editor).toBe(e)
    expect(w.find('.toolbar-toggle').exists()).toBe(true)
    expect(w.get('.advanced-toolbar').isVisible()).toBe(false)
    w.unmount()
    host.remove()
  })

  it('斜线菜单跟随光标且键盘选择会删除命令并插入二级标题', async () => {
    const w = await mountEditor({ modelValue: '', advanced: true, allowH1: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    vi.spyOn(e.view, 'coordsAtPos').mockReturnValue({ top: 120, bottom: 140, left: 200, right: 200 })
    e.commands.insertContent('/')
    await nextTick()
    const menu = document.querySelector<HTMLElement>('.slash-menu')!
    expect(menu.style.left).toBe('200px')
    expect(menu.style.top).toBe('148px')
    await w.get('.tiptap').trigger('keydown', { key: 'ArrowDown' })
    await w.get('.tiptap').trigger('keydown', { key: 'Enter' })
    expect(e.isActive('heading', { level: 2 })).toBe(true)
    expect(e.getText().trim()).toBe('')
    expect(document.querySelector('.slash-menu')).toBeNull()
    w.unmount()
  })

  it('斜线搜索可插入表格，外部点击只关闭菜单而保留输入', async () => {
    const w = await mountEditor({ modelValue: '', advanced: true, allowH1: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.insertContent('/table')
    await nextTick()
    expect(document.querySelectorAll('.slash-menu button')).toHaveLength(1)
    document.querySelector<HTMLButtonElement>('.slash-menu button')!.click()
    await nextTick()
    expect(e.getJSON().content?.some(node => node.type === 'table')).toBe(true)
    e.commands.setContent('<p>/h3</p>')
    e.commands.setTextSelection(4)
    await nextTick()
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await nextTick()
    expect(document.querySelector('.slash-menu')).toBeNull()
    expect(e.getText()).toBe('/h3')
    w.unmount()
  })

  it('高级插入菜单覆盖四类提示块、公式、图表与双三栏，并提供快捷键帮助', async () => {
    const w = await mountEditor({ modelValue: '', allowH1: true, advanced: true })
    const instance = w.vm as unknown as { slashActions: { id: string }[] }
    const ids = instance.slashActions.map((item) => item.id)
    expect(ids).toEqual(expect.arrayContaining([
      'callout-info', 'callout-success', 'callout-warning', 'callout-danger',
      'math-inline', 'math-block', 'diagram-flowchart', 'diagram-sequence', 'diagram-mindmap',
      'columns-2', 'columns-3',
    ]))

    await w.get('button[title^="快捷键帮助"]').trigger('click')
    expect(w.get('.shortcut-dialog').text()).toContain('Ctrl/⌘ + F')
    expect(w.get('.shortcut-dialog').text()).toContain('插入内容块')
    w.unmount()
  })

  it('保存的高级 JSON 可直接恢复提示块与分栏，小记菜单不暴露重型块', async () => {
    const document: RichDocNode = {
      type: 'doc',
      content: [
        { type: 'callout', attrs: { kind: 'warning', uid: 'callout-1' }, content: [{ type: 'paragraph', content: [{ type: 'text', text: '注意事项' }] }] },
        { type: 'columns', attrs: { uid: 'columns-1' }, content: [
          { type: 'column', content: [{ type: 'paragraph', content: [{ type: 'text', text: '左栏' }] }] },
          { type: 'column', content: [{ type: 'paragraph', content: [{ type: 'text', text: '右栏' }] }] },
        ] },
      ],
    }
    const w = await mountEditor({ modelValue: '', document, advanced: true, compact: true })
    expect(w.find('aside[data-callout="warning"]').text()).toContain('注意事项')
    expect(w.find('div[data-columns="2"]').text()).toContain('左栏')
    const actions = (w.vm as unknown as { slashActions: { id: string }[] }).slashActions
    expect(actions.map((item) => item.id)).not.toContain('table')
    expect(actions.map((item) => item.id)).toContain('image')
    w.unmount()
  })

  it('Tab 在编辑器内完成缩进，不再把焦点跳到图片按钮', async () => {
    const w = await mountEditor({ modelValue: '正文' })
    const editable = w.find('.md-body [contenteditable]').element as HTMLElement
    editable.focus()
    const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })

    editable.dispatchEvent(event)
    await nextTick()

    expect(event.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(editable)
    expect(w.emitted('update:modelValue')?.flat().some((value) => String(value).includes('\u00a0\u00a0'))).toBe(true)
    w.unmount()
  })

  it('对外仍以 Markdown 收发，落库格式不变', async () => {
    const w = await mountEditor({ modelValue: '- 第一项\n- 第二项' })

    expect(w.find('.md-body').html()).toContain('<li>')
    expect(w.vm.$props.modelValue).toBe('- 第一项\n- 第二项')
    w.unmount()
  })

  it('嵌套有序列表保持父级顺序，并按数字、字母、罗马数字区分层级', async () => {
    const source = [
      '1. 一级第一项',
      '   1. 二级第一项',
      '      1. 三级第一项',
      '2. 一级第二项',
    ].join('\n')
    const w = await mountEditor({ modelValue: source })

    const lists = w.findAll('.md-body ol')
    expect(lists).toHaveLength(3)
    expect(lists[0]!.element.matches('ol')).toBe(true)
    expect(lists[1]!.element.matches('ol ol')).toBe(true)
    expect(lists[2]!.element.matches('ol ol ol')).toBe(true)

    const topLevelItems = w.findAll('.md-body .tiptap > ol > li')
    expect(topLevelItems).toHaveLength(2)
    expect(topLevelItems[0]!.text()).toContain('一级第一项')
    expect(topLevelItems[1]!.text()).toBe('一级第二项')
    expect(w.vm.$props.modelValue).toBe(source)
    w.unmount()
  })

  it('粗体、行内代码等行内标记正常还原', async () => {
    const w = await mountEditor({ modelValue: '**重点**与 `代码`' })

    const html = w.find('.md-body').html()
    expect(html).toContain('<strong>重点</strong>')
    expect(html).toContain('<code>代码</code>')
    w.unmount()
  })

  it('文档链接点击后在新窗口打开，并保留安全的 target 与 rel', async () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const w = await mountEditor({
      modelValue: '[WAO](https://wao.wayuwa.cn)',
      linkControls: true,
    })

    const link = w.get('.md-body a')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
    await link.trigger('click')

    expect(open).toHaveBeenCalledWith('https://wao.wayuwa.cn', '_blank', 'noopener,noreferrer')
    open.mockRestore()
    w.unmount()
  })

  it('Codex 聊天链接可编辑并以 Markdown 与 JSON 往返，编辑态只打开一次且阅读态保留原生链接', async () => {
    const href = 'codex://threads/01a13855-11d0-7e33-90d9-23496e743d0d'
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const w = await mountEditor({ modelValue: '[聊天](https://example.com)', linkControls: true })
    const instance = w.vm as unknown as { editor: Editor; openLinkEditor: () => void }
    instance.editor.commands.setTextSelection({ from: 1, to: 3 })
    instance.openLinkEditor()
    await nextTick()
    await w.findAll('.link-dialog input')[1]!.setValue(href)
    await w.get('.link-dialog').trigger('submit')
    await nextTick()
    expect(w.find('.link-dialog').exists()).toBe(false)
    const markdown = instance.editor.getMarkdown()
    const saved = instance.editor.getJSON()
    expect(markdown).toBe(`[聊天](${href})`)
    await w.get('.md-body a').trigger('click')
    expect(open).toHaveBeenCalledTimes(1)
    expect(open).toHaveBeenCalledWith(href, '_blank', 'noopener,noreferrer')
    w.unmount()
    for (const document of [null, saved]) {
      const reader = await mountEditor({ modelValue: markdown, document, readonly: true })
      expect(reader.get('.md-body a').attributes('href')).toBe(href)
      expect(reader.get('.md-body a').attributes('target')).toBe('_blank')
      const click = new MouseEvent('click', { bubbles: true, cancelable: true })
      reader.get('.md-body a').element.dispatchEvent(click)
      expect(click.defaultPrevented).toBe(false)
      reader.unmount()
    }
    expect(open).toHaveBeenCalledTimes(1)
    open.mockRestore()
  })

  it('从富文本粘贴 Codex 链接并保存时地址完整，非法 Codex 链接不成为可点击地址', async () => {
    const href = 'codex://threads/01a13855-11d0-7e33-90d9-23496e743d0d'
    const w = await mountEditor({ modelValue: '' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.view.pasteHTML(`<p><a href="${href}">聊天</a><a href="codex://settings">非法</a></p>`, new Event('paste') as ClipboardEvent)
    await nextTick()
    expect(e.getMarkdown()).toContain(`[聊天](${href})`)
    expect(w.findAll('.md-body a')).toHaveLength(1)
    expect(w.get('.md-body a').attributes('href')).toBe(href)
    expect(e.getText()).toContain('非法')
    w.unmount()
  })

  it('历史 Markdown 含危险链接协议时，链接工具条不会执行或打开它', async () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const w = await mountEditor({
      modelValue: '[危险链接](javascript:alert(1))',
      linkControls: true,
    })
    const instance = w.vm as unknown as {
      editor: { getAttributes: (name: string) => Record<string, unknown> }
      openActiveLink: () => void
    }
    vi.spyOn(instance.editor, 'getAttributes').mockReturnValue({ href: 'javascript:alert(1)' })
    instance.openActiveLink()

    expect(open).not.toHaveBeenCalled()
    open.mockRestore()
    w.unmount()
  })

  it('可同时维护链接显示名称与 URL，结果仍以标准 Markdown 保存', async () => {
    const w = await mountEditor({
      modelValue: '[旧名称](https://old.example.com)',
      linkControls: true,
    })
    const instance = w.vm as unknown as {
      editor: { commands: { setTextSelection: (range: { from: number; to: number }) => void } }
      openLinkEditor: () => void
    }
    instance.editor.commands.setTextSelection({ from: 1, to: 4 })
    instance.openLinkEditor()
    await nextTick()

    const inputs = w.findAll('.link-dialog input')
    expect(inputs).toHaveLength(2)
    await inputs[0]!.setValue('新名称')
    await inputs[1]!.setValue('docs.example.com/guide')
    await w.get('.link-dialog').trigger('submit')
    await nextTick()

    const updates = w.emitted('update:modelValue') as [string][]
    expect(updates[updates.length - 1]?.[0]).toBe('[新名称](https://docs.example.com/guide)')
    expect(w.get('.md-body a').text()).toBe('新名称')
    expect(w.get('.md-body a').attributes('href')).toBe('https://docs.example.com/guide')
    w.unmount()
  })

  it('图片内联渲染在正文里，不是附件列表', async () => {
    const w = await mountEditor({ modelValue: '![截图](/api/images/img1/raw)' })

    const img = w.find('.md-body img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('/api/images/img1/raw')
    w.unmount()
  })

  it('文档图片可按百分比调整，尺寸随 Markdown 往返后仍保留', async () => {
    const w = await mountEditor({
      modelValue: '![截图](/api/images/img1/raw)',
      imageControls: true,
    })
    const instance = w.vm as unknown as {
      editor: { commands: { setNodeSelection: (position: number) => void } }
      setImageScale: (percent: 25 | 50 | 75 | 100) => void
    }
    instance.editor.commands.setNodeSelection(0)
    instance.setImageScale(50)
    await nextTick()

    const updates = w.emitted('update:modelValue') as [string][]
    const markdown = updates[updates.length - 1]?.[0] ?? ''
    expect(markdown.trim()).toBe('![截图](/api/images/img1/raw "wao-image-size:50%xauto")')
    w.unmount()

    const restored = await mountEditor({ modelValue: markdown, imageControls: true })
    expect(restored.get('.md-body img').attributes('style')).toContain('width: 50%')
    restored.unmount()
  })

  it('旋转上传成功后新图片地址进入 Markdown，重新打开仍使用新图', async () => {
    vi.mocked(loadRotationImage).mockResolvedValue(new Blob(['image']))
    vi.mocked(rotateImage).mockResolvedValue(new File(['png'], 'rotated.png'))
    const w = await mountEditor({ modelValue: '![截图](/api/images/original/raw)', imageControls: true })
    const instance = w.vm as unknown as { editor: Editor; rotateSelectedImage: (clockwise: boolean) => Promise<void> }
    instance.editor.commands.setNodeSelection(0)
    const rotating = instance.rotateSelectedImage(true)
    await flushPromises()
    const upload = w.emitted('upload-image')!.slice(-1)[0] as [File, (url: string, name: string) => void]
    expect(w.get('.md-body img').attributes('src')).toBe('/api/images/original/raw')
    upload[1]('/api/images/rotated/raw', 'rotated.png')
    await rotating
    await nextTick()
    const markdown = (w.emitted('update:modelValue') as [string][]).slice(-1)[0]![0]
    expect(markdown).toContain('/api/images/rotated/raw')
    w.unmount()
    const restored = await mountEditor({ modelValue: markdown, imageControls: true })
    expect(restored.get('.md-body img').attributes('src')).toBe('/api/images/rotated/raw')
    restored.unmount()
  })

  it('旋转期间原节点被删除时不覆盖其它图片', async () => {
    vi.mocked(loadRotationImage).mockResolvedValue(new Blob(['image']))
    vi.mocked(rotateImage).mockResolvedValue(new File(['png'], 'rotated.png'))
    const w = await mountEditor({ modelValue: '![一](/api/images/one/raw)\n\n![二](/api/images/two/raw)', imageControls: true })
    const instance = w.vm as unknown as { editor: Editor; rotateSelectedImage: (clockwise: boolean) => Promise<void> }
    instance.editor.commands.setNodeSelection(0)
    const rotating = instance.rotateSelectedImage(true)
    await flushPromises()
    instance.editor.commands.deleteSelection()
    const upload = w.emitted('upload-image')!.slice(-1)[0] as [File, (url: string, name: string) => void]
    upload[1]('/api/images/rotated/raw', 'rotated.png')
    await rotating
    await nextTick()
    expect(w.get('.md-body img').attributes('src')).toBe('/api/images/two/raw')
    w.unmount()
  })

  it('文档图片可设置像素长宽并限制为安全范围', async () => {
    const w = await mountEditor({
      modelValue: '![截图](/api/images/img1/raw)',
      imageControls: true,
    })
    const instance = w.vm as unknown as {
      editor: { commands: { setNodeSelection: (position: number) => void } }
      imageWidthInput: string
      imageHeightInput: string
      applyImageDimensions: () => void
    }
    instance.editor.commands.setNodeSelection(0)
    instance.imageWidthInput = '640'
    instance.imageHeightInput = '480'
    instance.applyImageDimensions()
    await nextTick()

    const updates = w.emitted('update:modelValue') as [string][]
    const markdown = updates[updates.length - 1]?.[0] ?? ''
    expect(markdown).toContain('wao-image-size:640x480')
    expect(w.get('.md-body img').attributes('style')).toContain('aspect-ratio: 640/480')
    w.unmount()
  })

  it('应用尺寸后再次点击同一张图会重新取回焦点以继续调整', async () => {
    const w = await mountEditor({
      modelValue: '![截图](/api/images/img1/raw "wao-image-size:640x480")',
      imageControls: true,
    })
    const instance = w.vm as unknown as {
      editor: {
        commands: { setNodeSelection: (position: number) => void }
        view: {
          hasFocus: () => boolean
          someProp: (
            name: 'handleClick',
            run: (handler: (view: unknown, position: number, event: MouseEvent) => boolean) => boolean,
          ) => boolean
        }
      }
    }
    instance.editor.commands.setNodeSelection(0)

    // 在尺寸输入框或“应用”按钮操作后，浏览器焦点已离开 ProseMirror。
    const outside = document.createElement('button')
    document.body.append(outside)
    outside.focus()
    expect(instance.editor.view.hasFocus()).toBe(false)

    const click = new MouseEvent('click', { bubbles: true })
    Object.defineProperty(click, 'target', { value: w.get('.md-body img').element })
    instance.editor.view.someProp('handleClick', (handler) => handler(instance.editor.view, 0, click))
    await nextTick()
    await new Promise((resolve) => requestAnimationFrame(() => resolve(undefined)))

    expect(instance.editor.view.hasFocus()).toBe(true)
    outside.remove()
    w.unmount()
  })

  it('只读模式下不可编辑', async () => {
    const w = await mountEditor({ modelValue: '内容', readonly: true })

    expect(w.find('.md-body').html()).toContain('contenteditable="false"')
    w.unmount()
  })

  it('不带格式工具条，但提供图片选择入口以支持手机图册上传', async () => {
    const w = await mountEditor({ modelValue: '## 标题' })

    expect(w.find('.md-tools').exists()).toBe(false)
    expect(w.findAll('button')).toHaveLength(1)
    expect(w.find('.image-upload').attributes('aria-label')).toBe('从图册选择图片')
    // 但格式本身照常渲染
    expect(w.find('.md-body').html()).toContain('<h2>标题</h2>')
    w.unmount()
  })

  it('发送确认立即清空有焦点的编辑器，并取消待回灌内容', async () => {
    const w = await mountEditor({ modelValue: '已发送消息' })
    const editable = w.get('[contenteditable]').element as HTMLElement
    editable.focus()
    await w.setProps({ modelValue: '待回灌旧内容' })
    const api = w.vm as unknown as { clearSentContent: (expected: string) => boolean }
    expect(api.clearSentContent('已发送消息')).toBe(true)
    await nextTick()
    expect(w.get('.md-body').text()).toBe('')
    expect(document.activeElement).toBe(editable)
    editable.blur()
    await nextTick()
    expect(w.get('.md-body').text()).toBe('')
    w.unmount()
  })

  it('发送期间新增输入不能被旧请求确认清除', async () => {
    const w = await mountEditor({ modelValue: '已发送消息和后续输入' })
    const api = w.vm as unknown as { clearSentContent: (expected: string) => boolean }
    expect(api.clearSentContent('已发送消息')).toBe(false)
    expect(w.get('.md-body').text()).toContain('后续输入')
    w.unmount()
  })

  it('外部改 modelValue 会回灌到编辑器（切换任务时不能留着上一条的内容）', async () => {
    const w = await mountEditor({ modelValue: '第一个任务的描述' })

    await w.setProps({ modelValue: '## 第二个任务' })
    await nextTick()

    expect(w.find('.md-body').html()).toContain('<h2>第二个任务</h2>')
    expect(w.find('.md-body').text()).not.toContain('第一个任务的描述')
    w.unmount()
  })

  it('正在输入时不回灌外部值——语音听写被 setContent 打断会把整段重念一遍', async () => {
    const w = await mountEditor({ modelValue: '我正在口述的第一句话' })
    const editable = w.find('.md-body [contenteditable]').element as HTMLElement
    editable.focus()
    await nextTick()

    // 后台同步把一份较旧的正文推了过来
    await w.setProps({ modelValue: '旧的服务端版本' })
    await nextTick()

    // 编辑器里还是用户自己那一句，光标不被打断
    expect(w.find('.md-body').text()).toContain('我正在口述的第一句话')
    expect(w.find('.md-body').text()).not.toContain('旧的服务端版本')
    w.unmount()
  })

  it('最新正文回传后失焦，不能恢复此前暂存的旧 Markdown', async () => {
    const w = await mountEditor({ modelValue: '第一行' })
    const editable = w.get('[contenteditable]').element as HTMLElement
    const e = (w.vm as unknown as { editor: Editor }).editor
    editable.focus()
    await w.setProps({ modelValue: '旧服务端正文' })
    e.commands.insertContentAt(e.state.doc.content.size, '<p>第二行刚写完</p>')
    await nextTick()
    await w.setProps({ modelValue: e.getMarkdown() })
    editable.blur()
    await nextTick()
    expect(e.getMarkdown()).toContain('第二行刚写完')
    expect(e.getMarkdown()).not.toContain('旧服务端正文')
    w.unmount()
  })

  it('当前正文得到确认时取消旧回灌，即使没有触发新的编辑事务', async () => {
    const w = await mountEditor({ modelValue: '当前正文' })
    const editable = w.get('[contenteditable]').element as HTMLElement
    editable.focus()
    await w.setProps({ modelValue: '过期正文', document: { type: 'doc', content: [
      { type: 'paragraph', content: [{ type: 'text', text: '过期富文本' }] },
    ] } })
    await w.setProps({ modelValue: '当前正文', document: null })
    editable.blur()
    await nextTick()
    expect(w.get('.md-body').text()).toBe('当前正文')
    w.unmount()
  })

  it('旧快照暂存后继续输入，即使父页面尚未回传也保留新一行', async () => {
    const w = await mountEditor({ modelValue: '第一行' })
    const editable = w.get('[contenteditable]').element as HTMLElement
    const e = (w.vm as unknown as { editor: Editor }).editor
    editable.focus()
    await w.setProps({ modelValue: '过期正文', document: { type: 'doc', content: [
      { type: 'paragraph', content: [{ type: 'text', text: '过期富文本' }] },
    ] } })
    e.commands.insertContentAt(e.state.doc.content.size, '<p>第二行刚写完</p>')
    await nextTick()
    editable.blur()
    await nextTick()
    expect(e.getMarkdown()).toContain('第二行刚写完')
    expect(e.getMarkdown()).not.toContain('过期')
    w.unmount()
  })

  it('最新富文本回传后失焦，不能恢复此前暂存的旧 JSON 正文', async () => {
    const w = await mountEditor({ modelValue: '第一行', document: { type: 'doc', content: [
      { type: 'paragraph', content: [{ type: 'text', text: '第一行' }] },
    ] } })
    const editable = w.get('[contenteditable]').element as HTMLElement
    const e = (w.vm as unknown as { editor: Editor }).editor
    editable.focus()
    await w.setProps({ document: { type: 'doc', content: [
      { type: 'paragraph', content: [{ type: 'text', text: '旧富文本正文' }] },
    ] } })
    e.commands.insertContentAt(e.state.doc.content.size, '<p>第二行刚写完</p>')
    await nextTick()
    await w.setProps({ modelValue: e.getMarkdown(), document: e.getJSON() as RichDocNode })
    editable.blur()
    await nextTick()
    expect(e.getMarkdown()).toContain('第二行刚写完')
    expect(e.getMarkdown()).not.toContain('旧富文本正文')
    w.unmount()
  })

  it('失焦后把输入期间错过的外部更新补灌回来', async () => {
    const w = await mountEditor({ modelValue: '本地内容' })
    const editable = w.find('.md-body [contenteditable]').element as HTMLElement
    editable.focus()
    await nextTick()
    await w.setProps({ modelValue: '别人改成了这样' })
    await nextTick()

    editable.blur()
    await nextTick()
    await new Promise((r) => setTimeout(r, 0))

    expect(w.find('.md-body').text()).toContain('别人改成了这样')
    w.unmount()
  })

  it('粘贴图片时抛 upload-image，并把返回的地址插进正文', async () => {
    const w = await mountEditor({ modelValue: '' })
    const file = new File([new Uint8Array(4)], 'a.png', { type: 'image/png' })

    pasteInto(w.find('.md-body [contenteditable]').element, [file])
    await nextTick()

    const emitted = w.emitted('upload-image')
    expect(emitted).toBeTruthy()
    expect((emitted![0]![0] as File).name).toBe('a.png')

    // 父组件回调传回地址后，图片应内联出现
    const done = emitted![0]![1] as (url: string | null, name: string) => void
    done('/api/images/img9/raw', 'a.png')
    await flushPromises()
    expect(w.find('.md-body img').attributes('src')).toBe('/api/images/img9/raw')
    w.unmount()
  })

  it('剪贴板只给 Base64 图片 HTML 时也转成 File 上传，不把长编码写入正文', async () => {
    const w = await mountEditor({ modelValue: '' })
    const source = 'data:image/png;base64,aGVsbG8='

    pasteHtmlInto(
      w.find('.md-body [contenteditable]').element,
      `<div><img src="${source}" alt="剪贴板截图"></div>`,
    )
    await nextTick()

    const emitted = w.emitted('upload-image')
    expect(emitted).toHaveLength(1)
    const file = emitted![0]![0] as File
    expect(file.type).toBe('image/png')
    expect(await file.text()).toBe('hello')
    expect(w.emitted('update:modelValue')?.flat().join('') ?? '').not.toContain('data:image')

    const done = emitted![0]![1] as (url: string | null, name: string) => void
    done('/api/images/clipboard/raw', '剪贴板截图.png')
    await flushPromises()
    expect(w.find('.md-body img').attributes('src')).toBe('/api/images/clipboard/raw')
    w.unmount()
  })

  it('一次粘贴多张图片时并行上传，并按剪贴板顺序插入', async () => {
    const w = await mountEditor({ modelValue: '' })
    const first = new File([new Uint8Array(4)], 'first.png', { type: 'image/png' })
    const second = new File([new Uint8Array(4)], 'second.png', { type: 'image/png' })

    pasteInto(w.find('.md-body [contenteditable]').element, [first, second])
    await nextTick()

    const emitted = w.emitted('upload-image')!
    expect(emitted).toHaveLength(2)
    expect((emitted[0]![0] as File).name).toBe('first.png')
    expect((emitted[1]![0] as File).name).toBe('second.png')

    // 故意让第二张先上传完；编辑器仍应等待整批并按粘贴顺序插入。
    const finishFirst = emitted[0]![1] as (url: string | null, name: string) => void
    const finishSecond = emitted[1]![1] as (url: string | null, name: string) => void
    finishSecond('/api/images/second/raw', 'second.png')
    await flushPromises()
    expect(w.findAll('.md-body img')).toHaveLength(0)

    finishFirst('/api/images/first/raw', 'first.png')
    await flushPromises()
    expect(w.findAll('.md-body img').map((img) => img.attributes('src'))).toEqual([
      '/api/images/first/raw',
      '/api/images/second/raw',
    ])
    w.unmount()
  })

  it('划词格式、链接、润色和待办只使用一份清单，添加待办不改正文', async () => {
    setActivePinia(createPinia())
    const ui = useUiStore()
    const w = await mountEditor({ modelValue: '记录明天的待办', advanced: true, polishable: true, linkControls: true })
    const instance = w.vm as unknown as { editor: Editor; addSelectionTask: () => Promise<void> }
    instance.editor.view.focus()
    instance.editor.commands.setTextSelection({ from: 1, to: 8 })
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 300))
    expect(w.findAllComponents(BubbleMenu)).toHaveLength(1)
    expect(w.findAllComponents(BubbleMenu)[0]!.props('pluginKey')).toBe('documentSelectionMenu')
    expect(document.querySelector('[aria-label="添加到待办"]')).not.toBeNull()
    expect(document.querySelector('[aria-label="润色选中文字"]')).not.toBeNull()
    const selectionHighlight = document.querySelector<HTMLButtonElement>('[aria-label="高亮背景颜色"]')
    expect(selectionHighlight).not.toBeNull()
    selectionHighlight!.click()
    expect(instance.editor.isActive('highlight')).toBe(true)
    const before = instance.editor.getJSON()
    await instance.addSelectionTask()
    expect(ui.modalTaskSeed?.desc).toBe('记录明天的待办')
    expect(ui.modal).toBe('task')
    expect(w.emitted('task-opened')).toHaveLength(1)
    expect(instance.editor.getJSON()).toEqual(before)
    await instance.addSelectionTask()
    expect(w.emitted('task-opened')).toHaveLength(1)
    await w.setProps({ readonly: true })
    instance.editor.commands.setTextSelection({ from: 1, to: 8 })
    await instance.addSelectionTask()
    expect(w.emitted('task-opened')).toHaveLength(1)
    w.unmount()
  })

  it('全屏选区工具栏显示在小记全屏层上方，并保留润色与生图入口', async () => {
    setActivePinia(createPinia())
    const w = await mountEditor({
      modelValue: '选中这一段来润色和生图',
      advanced: true,
      polishable: true,
      selectionImage: true,
      articleImageKey: 'note:me:fullscreen',
      menuZIndex: 2200,
    })
    const editor = (w.vm as unknown as { editor: Editor }).editor
    editor.view.focus()
    editor.commands.setTextSelection({ from: 1, to: 8 })
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 300))

    const menu = w.findAllComponents(BubbleMenu)[0]!
    expect(menu.attributes('style')).toContain('z-index: 2200')
    expect(document.querySelector('[aria-label="润色选中文字"]')).not.toBeNull()
    expect(document.querySelector('[aria-label="生图"]')).not.toBeNull()
    w.unmount()
  })

  it('从图片选择器选中图片时，走与粘贴相同的上传和内联插入链路', async () => {
    const w = await mountEditor({ modelValue: '' })
    const file = new File([new Uint8Array(4)], 'mobile.png', { type: 'image/png' })
    const picker = w.find('input[type="file"]').element as HTMLInputElement
    Object.defineProperty(picker, 'files', { value: [file], configurable: true })

    await w.find('input[type="file"]').trigger('change')

    const emitted = w.emitted('upload-image')
    expect(emitted).toBeTruthy()
    expect((emitted![0]![0] as File).name).toBe('mobile.png')
    const done = emitted![0]![1] as (url: string | null, name: string) => void
    done('/api/images/mobile/raw', 'mobile.png')
    await flushPromises()
    expect(w.find('.md-body img').attributes('src')).toBe('/api/images/mobile/raw')
    w.unmount()
  })

  it('粘贴纯文本时不触发上传，交回浏览器默认行为', async () => {
    const w = await mountEditor({ modelValue: '' })

    pasteInto(w.find('.md-body [contenteditable]').element, [])
    await nextTick()

    expect(w.emitted('upload-image')).toBeFalsy()
    w.unmount()
  })

  it('上传失败（回调传 null）时不插入破图', async () => {
    const w = await mountEditor({ modelValue: '' })
    const file = new File([new Uint8Array(4)], 'a.png', { type: 'image/png' })
    pasteInto(w.find('.md-body [contenteditable]').element, [file])
    await nextTick()

    const done = w.emitted('upload-image')![0]![1] as (url: string | null, name: string) => void
    done(null, '')
    await nextTick()

    expect(w.find('.md-body img').exists()).toBe(false)
    w.unmount()
  })

  it('传入协作者时，在他所在的那一行左侧挂上头像', async () => {
    const w = await mountEditor({
      modelValue: '第一行\n\n第二行\n\n第三行',
      peers: [{ id: 'm2', name: '娃弟', initial: '弟', color: '#B9821F', line: 1, editing: true }],
    })
    await nextTick()

    const av = w.find('.peer-av')
    expect(av.exists()).toBe(true)
    expect(av.text()).toBe('弟')
    expect(av.attributes('title')).toContain('正在编辑这一行')
    // 正在输入的还会给那一行压一道颜色条
    expect(w.find('.peer-line').exists()).toBe(true)
    w.unmount()
  })

  it('没有协作者时不占位、不画轨道——普通编辑场景一点不受影响', async () => {
    const w = await mountEditor({ modelValue: '正文' })

    expect(w.find('.peer-track').exists()).toBe(false)
    expect(w.find('.md').classes()).not.toContain('collab')
    w.unmount()
  })

  it('流式半篇只预览，失败清除且始终保留原文', async () => {
    let fail!: (reason: Error) => void
    vi.mocked(polishDocumentText).mockImplementationOnce((_text, options) => {
      options?.onProgress?.({ id: 'pj-stream', status: 'RUNNING', message: '正在生成', preview: '尚未完成的候选' })
      return new Promise((_resolve, reject) => { fail = reject })
    })
    const source = '需要保持不变的原文。'
    const w = await mountEditor({ modelValue: source, polishable: true })
    const instance = w.vm as unknown as {
      editor: { commands: { setTextSelection: (range: { from: number; to: number }) => void } }
      startPolish: () => Promise<void>
    }
    instance.editor.commands.setTextSelection({ from: 1, to: source.length + 1 })
    const pending = instance.startPolish()
    await flushPromises()
    expect(w.get('.polish-compare').text()).toContain('尚未完成的候选')
    expect(w.get('.polish-apply').attributes('disabled')).toBeDefined()
    expect(w.emitted('update:modelValue')).toBeFalsy()
    fail(new Error('连接断开'))
    await pending
    await flushPromises()
    expect(w.get('.polish-compare').text()).not.toContain('尚未完成的候选')
    expect(w.get('.polish-apply').attributes('disabled')).toBeDefined()
    expect(w.get('.md-body').text()).toContain(source)
    w.unmount()
  })

  it('处理中收起仍取回同一任务，完成后主动查看且不覆盖新输入', async () => {
    let finish!: (value: { polished: string }) => void
    vi.mocked(polishDocumentText).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const source = '等待润色的原文。'
    const w = await mountEditor({ modelValue: source, polishable: true })
    const instance = w.vm as unknown as {
      editor: { commands: { setTextSelection: (range: { from: number; to: number }) => void } }
      startPolish: () => Promise<void>
    }
    instance.editor.commands.setTextSelection({ from: 1, to: source.length + 1 })
    const pending = instance.startPolish()
    await flushPromises()
    expect(w.text()).toContain('已等待 0 秒')
    await w.get('.polish-keep').trigger('click')
    expect(w.find('.polish-dialog').exists()).toBe(false)
    expect(w.get('.polish-resume').text()).toContain('后台处理')
    await w.setProps({ modelValue: '等待期间继续修改的正文。' })
    finish({ polished: '改写后的候选。' })
    await pending
    await flushPromises()
    expect(w.get('.polish-resume').text()).toContain('已完成')
    await w.get('.polish-resume').trigger('click')
    await w.get('.polish-apply').trigger('click')
    expect(w.get('.md-body').text()).toContain('等待期间继续修改的正文。')
    expect(w.get('.polish-error').text()).toContain('变化')
    w.unmount()
  })

  it('润色结果先与原文对照，选择保留原文时不修改编辑器', async () => {
    vi.mocked(polishDocumentText).mockResolvedValueOnce({ polished: '这句话表达得更加自然。' })
    const source = '这句话写得不是特别顺。'
    const w = await mountEditor({ modelValue: source, polishable: true })
    const instance = w.vm as unknown as {
      editor: { commands: { setTextSelection: (range: { from: number; to: number }) => void } }
      startPolish: () => Promise<void>
    }
    instance.editor.commands.setTextSelection({ from: 1, to: source.length + 1 })

    await instance.startPolish()
    await flushPromises()

    expect(polishDocumentText).toHaveBeenCalledWith(source, expect.objectContaining({ requestId: expect.any(String) }))
    expect(w.find('.polish-dialog').exists()).toBe(true)
    expect(w.findAll('.polish-compare pre').map((node) => node.text())).toEqual([
      source,
      '这句话表达得更加自然。',
    ])
    await w.find('.polish-keep').trigger('click')
    expect(w.find('.polish-dialog').exists()).toBe(false)
    expect(w.emitted('update:modelValue')).toBeFalsy()
    expect(w.find('.md-body').text()).toContain(source)
    w.unmount()
  })

  it('只有明确选择替换时才把润色结果写入选区并触发现有保存事件', async () => {
    vi.mocked(polishDocumentText).mockResolvedValueOnce({ polished: '这句话表达得更加自然。' })
    const source = '这句话写得不是特别顺。'
    const w = await mountEditor({ modelValue: source, polishable: true })
    const instance = w.vm as unknown as {
      editor: { commands: { setTextSelection: (range: { from: number; to: number }) => void } }
      startPolish: () => Promise<void>
    }
    instance.editor.commands.setTextSelection({ from: 1, to: source.length + 1 })
    await instance.startPolish()
    await flushPromises()

    await w.find('.polish-apply').trigger('click')
    await nextTick()

    const updates = w.emitted('update:modelValue') as [string][]
    const latest = updates[updates.length - 1]?.[0]
    expect(latest).toContain('这句话表达得更加自然。')
    expect(latest).not.toContain(source)
    expect(w.find('.polish-dialog').exists()).toBe(false)
    w.unmount()
  })

  it('跨段选区以 Markdown 交给 AI，并能把多段候选完整替换回文档', async () => {
    vi.mocked(polishDocumentText).mockResolvedValueOnce({
      polished: '第一段表达更流畅。\n\n第二段表达更清楚。',
    })
    const w = await mountEditor({ modelValue: '第一段原文。\n\n第二段原文。', polishable: true })
    const instance = w.vm as unknown as {
      editor: {
        state: { doc: { content: { size: number } } }
        commands: { setTextSelection: (range: { from: number; to: number }) => void }
      }
      startPolish: () => Promise<void>
    }
    instance.editor.commands.setTextSelection({
      from: 1,
      to: instance.editor.state.doc.content.size - 1,
    })
    await instance.startPolish()
    await flushPromises()

    expect(polishDocumentText).toHaveBeenCalledWith('第一段原文。\n\n第二段原文。', expect.objectContaining({ requestId: expect.any(String) }))
    await w.find('.polish-apply').trigger('click')
    await nextTick()

    const updates = w.emitted('update:modelValue') as [string][]
    expect(updates[updates.length - 1]?.[0]).toBe('第一段表达更流畅。\n\n第二段表达更清楚。')
    w.unmount()
  })

  it('润色失败时保留原文并提供原选区重试', async () => {
    vi.mocked(polishDocumentText)
      .mockRejectedValueOnce(new Error('本地 Codex 暂时离线'))
      .mockResolvedValueOnce({ polished: '重试后的润色结果。' })
    const source = '等待润色的原文。'
    const w = await mountEditor({ modelValue: source, polishable: true })
    const instance = w.vm as unknown as {
      editor: { commands: { setTextSelection: (range: { from: number; to: number }) => void } }
      startPolish: () => Promise<void>
    }
    instance.editor.commands.setTextSelection({ from: 1, to: source.length + 1 })
    await instance.startPolish()
    await flushPromises()

    expect(w.find('.polish-error').text()).toContain('本地 Codex 暂时离线')
    expect(w.emitted('update:modelValue')).toBeFalsy()
    await w.find('.polish-error button').trigger('click')
    await flushPromises()

    expect(w.find('.polish-error').exists()).toBe(false)
    expect(w.findAll('.polish-compare pre')[1]?.text()).toBe('重试后的润色结果。')
    w.unmount()
  })

  it('小记留存失败只重试应用同一成果，跨段正文、标题和原图完整保存', async () => {
    vi.mocked(polishDocumentText).mockClear()
    vi.mocked(retainNoteAiResult).mockClear()
    vi.mocked(retainNoteAiResult).mockRejectedValueOnce(new Error('结果留存暂时不可用'))
    vi.mocked(polishDocumentText).mockResolvedValueOnce({ polished: '第一段表达更流畅。\r\n\r\n第二段保留 300 篇与【小站】。', taskId: 'pj' + 'a'.repeat(32) })
    const source = '第一段原文。\n\n第二段原文。\n\n![原图](/api/images/original/raw)'
    const w = await mountEditor({ modelValue: source, polishable: true, noteAiOrigin: { stableId: 'n', docId: 'n', sourceRev: 1, title: '手动标题', blocks: [], links: [] } })
    const instance = w.vm as unknown as { editor: Editor; startPolish: () => Promise<void> }
    const second = instance.editor.state.doc.child(1)
    instance.editor.commands.setTextSelection({ from: 1, to: instance.editor.state.doc.child(0).nodeSize + second.nodeSize - 1 })
    await instance.startPolish(); await flushPromises()
    const beforeApply = instance.editor.getMarkdown()
    await w.find('.polish-apply').trigger('click'); await flushPromises()
    expect(w.find('.polish-error').text()).toContain('结果留存暂时不可用')
    expect(instance.editor.getMarkdown()).toBe(beforeApply)
    expect(w.find('.polish-apply').attributes('disabled')).toBeUndefined()
    await w.find('.polish-error button').trigger('click'); await flushPromises()
    expect(polishDocumentText).toHaveBeenCalledTimes(1)
    expect(retainNoteAiResult).toHaveBeenCalledTimes(2)
    expect(vi.mocked(retainNoteAiResult).mock.calls[1]).toEqual(vi.mocked(retainNoteAiResult).mock.calls[0])
    const updates = w.emitted('update:modelValue') as [string][]
    const retained = vi.mocked(retainNoteAiResult).mock.calls[1]![2]
    expect(retained.markdown).toBe(updates[updates.length - 1]?.[0])
    expect(retained.title).toBe('手动标题')
    expect(retained.markdown).toContain('![原图](/api/images/original/raw)')
    expect(w.emitted('ai-applied')).toHaveLength(1)
    w.unmount()
  })

  it('小记应用等待期间正文变化，不覆盖新内容也不发出成果采用事件', async () => {
    vi.mocked(retainNoteAiResult).mockClear()
    let complete!: () => void
    vi.mocked(retainNoteAiResult).mockImplementationOnce(() => new Promise<void>(resolve => { complete = resolve }))
    vi.mocked(polishDocumentText).mockResolvedValueOnce({ polished: '保留事实的润色。', taskId: 'pj' + 'b'.repeat(32) })
    const w = await mountEditor({ modelValue: '等待润色的原文。', polishable: true, noteAiOrigin: { stableId: 'draft', title: '', blocks: [], links: [] } })
    const instance = w.vm as unknown as { editor: Editor; startPolish: () => Promise<void> }
    instance.editor.commands.setTextSelection({ from: 1, to: 10 })
    await instance.startPolish(); await flushPromises()
    await w.find('.polish-apply').trigger('click'); await flushPromises()
    expect(w.find('.polish-apply').text()).toContain('正在替换')
    await w.find('.polish-apply').trigger('click')
    expect(retainNoteAiResult).toHaveBeenCalledTimes(1)
    instance.editor.commands.insertContentAt(1, '新的输入。')
    complete(); await flushPromises()
    expect(instance.editor.getMarkdown()).toContain('新的输入。')
    expect(instance.editor.getMarkdown()).not.toContain('保留事实的润色。')
    expect(w.emitted('ai-applied')).toBeFalsy()
    w.unmount()
  })

  it('光标移动会报出自己所在的行号，供上报协同在场', async () => {
    const w = await mountEditor({ modelValue: '第一行\n\n第二行' })

    // 挂载后把光标放到文末，应当报出最后一个顶层块的序号
    const editor = (w.vm as unknown as { editor: { commands: { focus: (p: string) => void } } })
      .editor
    editor?.commands.focus('end')
    await nextTick()

    const lines = w.emitted('caret-line') as [number][] | undefined
    expect(lines?.[lines.length - 1]?.[0]).toBe(1)
    w.unmount()
  })

  it('卸载时销毁编辑器实例，不留 ProseMirror 的事件监听', async () => {
    const w = await mountEditor({ modelValue: '内容' })
    const spy = vi.spyOn(console, 'error')

    w.unmount()
    await nextTick()

    expect(spy).not.toHaveBeenCalled()
    spy.mockRestore()
  })
})


describe('跨应用复制选区', () => {
  it('小记全选复制保留正文图号，打开逐张原图步骤；不更改原文', async () => {
    const source = '## 正文\n\n![第一张](/api/images/one/raw)\n\n后文\n\n![第二张](/api/images/two/raw)'
    const w = await mountEditor({ modelValue: source, noteMediaCopy: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.selectAll()
    const before = e.getJSON()
    const values: Record<string, string> = {}
    const event = new Event('copy', { bubbles: true, cancelable: true })
    Object.defineProperty(event, 'clipboardData', { value: { setData: (type: string, value: string) => values[type] = value } })
    w.get('.tiptap').element.dispatchEvent(event)
    await nextTick()
    expect(values['text/plain']).toContain('[图 1：第一张]')
    expect(values['text/plain']).toContain('后文')
    expect(values['text/plain']).toContain('[图 2：第二张]')
    expect(document.body.querySelector('.note-media-copy-dialog')?.textContent).toContain('正文已复制')
    expect(document.body.querySelector('.copy-primary')?.textContent).toBe('复制图 1 原图')
    expect(e.getJSON()).toEqual(before)
    w.unmount()
  })
  it('只复制选中的正文，HTML 保持标题语义，纯文本不携带 Markdown 标记', async () => {
    const w = await mountEditor({ modelValue: '## 标题\n\n不要复制的后文' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.setTextSelection({ from: 1, to: 3 })
    const values: Record<string, string> = {}
    const event = new Event('copy', { bubbles: true, cancelable: true })
    Object.defineProperty(event, 'clipboardData', { value: { setData: (type: string, value: string) => values[type] = value } })
    w.get('.tiptap').element.dispatchEvent(event)
    expect(values['text/html']).toContain('<h2')
    expect(values['text/plain']).toBe('标题')
    expect(values['text/plain']).not.toContain('后文')
    expect(event.defaultPrevented).toBe(true)
    w.unmount()
  })
})

it('同一关键词再次定位时重新检查最新草稿，不把旧命中当作当前结果', async () => {
  const w = await mountEditor({ modelValue: '桂花原文', searchTerm: '桂花', searchRequest: '1', advanced: true })
  const e = (w.vm as unknown as { editor: Editor }).editor
  e.commands.setContent('<p>尚未保存的松鼠草稿</p>')
  await w.setProps({ searchRequest: '2' }); await flushPromises()
  expect(w.text()).toContain('当前内容未找到')
  expect(w.find('.content-search-hit').exists()).toBe(false)
  expect(e.getText()).toBe('尚未保存的松鼠草稿')
  w.unmount()
})


describe('拖入文档附件', () => {
  it('混合文件拖入同时上传图片和附件，失败可以重试并保存卡片', async () => {
    const w = await mountEditor({ modelValue: '正文', advanced: true })
    const e = (w.vm as unknown as { editor: Editor }).editor
    vi.spyOn(e.view, 'posAtCoords').mockReturnValue({ pos: 1, inside: 0 })
    const file = new File(['sample'], '说明.pdf', { type: 'application/pdf' })
    const picture = new File(['sample'], '图.png', { type: 'image/png' })
    const event = { dataTransfer: { files: [file, picture] }, clientX: 0, clientY: 0 } as unknown as DragEvent
    expect(e.view.someProp('handleDrop', handler => handler(e.view, event, null as never, false))).toBe(true)
    expect(w.emitted('upload-image')?.[0]?.[0]).toBe(picture)
    const upload = w.emitted('upload-file')![0] as [File, (item: unknown) => void]
    expect(upload[0]).toBe(file)
    upload[1](null)
    await nextTick()
    expect(w.get('.upload-queue').text()).toContain('重试')
    await w.get('.upload-queue button').trigger('click')
    const retry = w.emitted('upload-file')![1] as [File, (item: unknown) => void]
    retry[1]({ id: 'test-file', name: '说明.pdf', size: 6, contentType: 'application/pdf', link: '/api/files/test-file/download' })
    await nextTick()
    expect(JSON.stringify(e.getJSON())).toContain('fileCard')
    expect(JSON.stringify(e.getJSON())).toContain('test-file')
    expect(w.find('.upload-queue').exists()).toBe(false)
    const saved = e.getJSON() as RichDocNode
    w.unmount()
    const restored = await mountEditor({ modelValue: '', document: saved, advanced: true })
    expect(JSON.stringify((restored.vm as unknown as { editor: Editor }).editor.getJSON())).toContain('test-file')
    restored.unmount()
  })

  it('内部拖动不重复上传，只读编辑器不接收文件', async () => {
    for (const readonly of [false, true]) {
      const w = await mountEditor({ modelValue: '正文', readonly, advanced: true })
      const e = (w.vm as unknown as { editor: Editor }).editor
      const event = { dataTransfer: { files: [new File(['x'], 'x.pdf')] } } as unknown as DragEvent
      expect(e.view.someProp('handleDrop', handler => handler(e.view, event, null as never, !readonly))).toBeFalsy()
      expect(w.emitted('upload-file')).toBeUndefined()
      w.unmount()
    }
  })
})

it.each([false, true])('compact=%s 的斜线菜单支持代码编辑及保存回读', async (compact) => {
  setActivePinia(createPinia())
  const w = await mountEditor({ modelValue: '', slashCommands: true, compact })
  const e = (w.vm as unknown as { editor: Editor }).editor
  e.commands.insertContent(compact ? '/代码' : '/code')
  await nextTick()
  expect(document.querySelectorAll('.slash-menu button')).toHaveLength(1)
  expect(document.querySelector('.slash-menu')?.textContent).toContain('代码块')
  await w.get('.tiptap').trigger('keydown', { key: 'Enter' })
  expect(e.isActive('codeBlock')).toBe(true)
  expect(e.getText().trim()).toBe('')
  await nextTick()
  await w.get('select[aria-label="代码语言"]').setValue('javascript')
  e.commands.insertContent('/code')
  await nextTick()
  expect(document.querySelector('.slash-menu')).toBeNull()
  expect(e.getText().trim()).toBe('/code')
  e.commands.insertContent('\nconst answer = 42;')
  await nextTick()
  const markdown = w.emitted('update:modelValue')!.slice(-1)[0]![0] as string
  expect(markdown).toContain('```javascript')
  expect(markdown).toContain('const answer = 42;')
  w.unmount()
  const restored = await mountEditor({ modelValue: markdown, slashCommands: true, compact })
  const restoredEditor = (restored.vm as unknown as { editor: Editor }).editor
  expect(restoredEditor.getJSON().content?.[0]?.attrs?.language).toBe('javascript')
  expect(restoredEditor.getText()).toContain('const answer = 42;')
  restored.unmount()
})

it('简洁任务描述支持斜线搜索与键盘插入，取消保留原文', async () => {
  setActivePinia(createPinia())
  const w = await mountEditor({ modelValue: '', slashCommands: true })
  const e = (w.vm as unknown as { editor: Editor }).editor
  expect(w.find('.advanced-toolbar').exists()).toBe(false)
  e.commands.insertContent('/h2')
  await nextTick()
  expect(document.querySelectorAll('.slash-menu button')).toHaveLength(1)
  await w.get('.tiptap').trigger('keydown', { key: 'Enter' })
  expect(e.isActive('heading', { level: 2 })).toBe(true)
  expect(e.getText().trim()).toBe('')
  e.commands.setParagraph()
  e.commands.insertContent('/task')
  await nextTick()
  await w.get('.tiptap').trigger('keydown', { key: 'Escape' })
  expect(document.querySelector('.slash-menu')).toBeNull()
  expect(e.getText().trim()).toBe('/task')
  w.unmount()
})

describe('任务编辑器的路径和真实 URL', () => {
  it('文件路径加载、输入和粘贴不误识别为链接', async () => {
    const path = String.raw`dws\demo\fact_demo_sellin.py`
    const w = await mountEditor({ modelValue: `1. ${path}` })
    const e = (w.vm as unknown as { editor: Editor }).editor
    expect(w.find('.tiptap a').exists()).toBe(false)
    e.commands.setContent('<p></p>')
    e.commands.insertContent(`${path} `)
    await nextTick()
    expect(w.find('.tiptap a').exists()).toBe(false)
    e.commands.setContent('<p></p>')
    e.view.pasteText(path, new Event('paste') as ClipboardEvent)
    await nextTick()
    expect(w.find('.tiptap a').exists()).toBe(false)
    expect(e.getText()).toBe(path)
    w.unmount()
  })

  it('历史路径内的误链接恢复成原文，复制时不带 Markdown 转义', async () => {
    const w = await mountEditor({ modelValue: String.raw`1. dws\demo\fact_demo_[sellin.py](http://sellin.py)` })
    const e = (w.vm as unknown as { editor: Editor }).editor
    expect(w.find('.tiptap a').exists()).toBe(false)
    e.commands.selectAll()
    const values: Record<string, string> = {}
    const event = new Event('copy', { bubbles: true, cancelable: true })
    Object.defineProperty(event, 'clipboardData', { value: { setData: (type: string, value: string) => values[type] = value } })
    w.get('.tiptap').element.dispatchEvent(event)
    expect(values['text/plain']).toBe(String.raw`1. dws\demo\fact_demo_sellin.py`)
    expect(values['text/html']).not.toContain('<a')
    w.unmount()
  })

  it('真实 URL 无需尾部空格即可粘贴成链接，任务默认模式可单击打开', async () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const w = await mountEditor({ modelValue: '' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    const url = 'https://example.com/fact_demo_sellin.py?q=goods_type'
    e.view.pasteText(url, new Event('paste') as ClipboardEvent)
    await nextTick()
    expect(w.get('.tiptap a').attributes('href')).toBe(url)
    await w.get('.tiptap a').trigger('click')
    expect(open).toHaveBeenCalledExactlyOnceWith(url, '_blank', 'noopener,noreferrer')
    const markdown = e.getMarkdown()
    w.unmount()
    const restored = await mountEditor({ modelValue: markdown })
    expect(restored.get('.tiptap a').attributes('href')).toBe(url)
    restored.unmount()
    open.mockRestore()
  })
})
