import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'

/** Tiptap 需要真实 DOM 与一帧时间来完成挂载。 */
async function mountEditor(props: {
  modelValue: string
  readonly?: boolean
  placeholder?: string
  peers?: { id: string; name: string; initial: string; color: string; line: number; editing: boolean }[]
}) {
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
    value: { files: [], items: [], types: ['text/html'], getData: (type: string) => type === 'text/html' ? html : '' },
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

  it('对外仍以 Markdown 收发，落库格式不变', async () => {
    const w = await mountEditor({ modelValue: '- 第一项\n- 第二项' })

    expect(w.find('.md-body').html()).toContain('<li>')
    expect(w.vm.$props.modelValue).toBe('- 第一项\n- 第二项')
    w.unmount()
  })

  it('粗体、行内代码等行内标记正常还原', async () => {
    const w = await mountEditor({ modelValue: '**重点**与 `代码`' })

    const html = w.find('.md-body').html()
    expect(html).toContain('<strong>重点</strong>')
    expect(html).toContain('<code>代码</code>')
    w.unmount()
  })

  it('图片内联渲染在正文里，不是附件列表', async () => {
    const w = await mountEditor({ modelValue: '![截图](/api/images/img1/raw)' })

    const img = w.find('.md-body img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('/api/images/img1/raw')
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

    pasteHtmlInto(w.find('.md-body [contenteditable]').element, `<div><img src="${source}" alt="剪贴板截图"></div>`)
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

  it('光标移动会报出自己所在的行号，供上报协同在场', async () => {
    const w = await mountEditor({ modelValue: '第一行\n\n第二行' })

    // 挂载后把光标放到文末，应当报出最后一个顶层块的序号
    const editor = (w.vm as unknown as { editor: { commands: { focus: (p: string) => void } } }).editor
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
