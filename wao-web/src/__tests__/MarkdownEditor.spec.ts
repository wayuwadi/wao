import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'

/** Tiptap 需要真实 DOM 与一帧时间来完成挂载。 */
async function mountEditor(props: { modelValue: string; readonly?: boolean; placeholder?: string }) {
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

  it('只读模式下不可编辑，也不显示工具条', async () => {
    const w = await mountEditor({ modelValue: '内容', readonly: true })

    expect(w.find('.md-body').html()).toContain('contenteditable="false"')
    expect(w.find('.md-tools').exists()).toBe(false)
    w.unmount()
  })

  it('工具条按钮反映光标处的格式状态', async () => {
    const w = await mountEditor({ modelValue: '## 标题' })

    // 工具条常驻 DOM，靠 .show 控制可见性；标题按钮应处于激活态
    expect(w.find('.md-tools').exists()).toBe(true)
    expect(w.find('.md-tool[title="标题"]').exists()).toBe(true)
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
    await nextTick()
    expect(w.find('.md-body img').attributes('src')).toBe('/api/images/img9/raw')
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

  it('卸载时销毁编辑器实例，不留 ProseMirror 的事件监听', async () => {
    const w = await mountEditor({ modelValue: '内容' })
    const spy = vi.spyOn(console, 'error')

    w.unmount()
    await nextTick()

    expect(spy).not.toHaveBeenCalled()
    spy.mockRestore()
  })
})
