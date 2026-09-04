import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'
import { polishDocumentText } from '@/api/assistant'
import type { RichDocNode } from '@/types'

vi.mock('@/api/assistant', () => ({
  polishDocumentText: vi.fn<(text: string) => Promise<{ polished: string }>>(),
}))

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
  linkControls?: boolean
  imageControls?: boolean
  advanced?: boolean
  compact?: boolean
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

    expect(polishDocumentText).toHaveBeenCalledWith(source)
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

    expect(polishDocumentText).toHaveBeenCalledWith('第一段原文。\n\n第二段原文。')
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
