import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'
import { polishDocumentText } from '@/api/assistant'
import type { RichDocNode } from '@/types'
import type { Editor } from '@tiptap/core'
import { BubbleMenu } from '@tiptap/vue-3/menus'
import { CellSelection } from '@tiptap/pm/tables'
import { createPinia, setActivePinia } from 'pinia'
import { useUiStore } from '@/stores/ui'
import { loadRotationImage } from '@/api/imageRotation'
import { rotateImage } from '@/utils/rotateImage'
import { fetchPreference, savePreference } from '@/api/favorites'
import { startArticleImage, fetchArticleImage } from '@/api/articleImages'
vi.mock('@/api/imageRotation', () => ({ loadRotationImage: vi.fn() }))
vi.mock('@/utils/rotateImage', () => ({ rotateImage: vi.fn() }))

vi.mock('@/api/assistant', () => ({
  polishDocumentText: vi.fn<(text: string) => Promise<{ polished: string }>>(),
}))
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
vi.mock('@/api/articleImages', () => ({ startArticleImage: vi.fn(), fetchArticleImage: vi.fn() }))

/** Tiptap 需要真实 DOM 与一帧时间来完成挂载。 */
async function mountEditor(props: {
  modelValue: string
  readonly?: boolean
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
  advanced?: boolean
  articleImageKey?: string
  compact?: boolean
  toolbarTarget?: HTMLElement | null
  document?: RichDocNode | null
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

describe('文章总览配图', () => {
  it('根据正文生成图片并插入开头，保留原文', async () => {
    vi.mocked(fetchPreference).mockResolvedValue('')
    vi.mocked(startArticleImage).mockResolvedValue({ id: 'ac-image', status: 'QUEUED', message: '等待生成', imageUrl: null })
    vi.mocked(fetchArticleImage).mockResolvedValue({ id: 'ac-image', status: 'SUCCEEDED', message: '已生成', imageUrl: '/api/images/img1/raw' })
    const w = await mountEditor({ modelValue: '第一段。\n\n第二段。', articleImageKey: 'note:me:n1' })
    await flushPromises()

    await w.get('.generate-image').trigger('click')
    await flushPromises()

    expect(startArticleImage).toHaveBeenCalledWith('第一段。第二段。', expect.stringContaining('总览图'))
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
    expect(w.emitted('update:modelValue')?.flat().some((value) => String(value).includes('\t'))).toBe(true)
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
    const before = instance.editor.getJSON()
    expect(document.querySelector('[aria-label="添加到待办"]')).not.toBeNull()
    expect(document.querySelector('[aria-label="润色选中文字"]')).not.toBeNull()
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
  it('只复制选中的正文，HTML 保持标题语义，纯文本保留 Markdown', async () => {
    const w = await mountEditor({ modelValue: '## 标题\n\n不要复制的后文' })
    const e = (w.vm as unknown as { editor: Editor }).editor
    e.commands.setTextSelection({ from: 1, to: 3 })
    const values: Record<string, string> = {}
    const event = new Event('copy', { bubbles: true, cancelable: true })
    Object.defineProperty(event, 'clipboardData', { value: { setData: (type: string, value: string) => values[type] = value } })
    w.get('.tiptap').element.dispatchEvent(event)
    expect(values['text/html']).toContain('<h2')
    expect(values['text/plain']).toContain('## 标题')
    expect(values['text/plain']).not.toContain('后文')
    expect(event.defaultPrevented).toBe(true)
    w.unmount()
  })
})
