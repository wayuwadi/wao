import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { recapDocument } from '@/utils/noteVisualRecap'
import NoteVisualRecap from '@/components/notes/NoteVisualRecap.vue'
import { fetchVisualRecap, startVisualRecap } from '@/api/noteVisualRecap'
vi.mock('@/api/noteVisualRecap', () => ({ fetchVisualRecap: vi.fn(), startVisualRecap: vi.fn() }))
const shell = { template: '<section><slot/><slot name="footer"/></section>' }
afterEach(() => {
  vi.clearAllMocks()
  vi.useRealTimers()
})
describe('图文整理隔离与生命周期', () => {
  it('保留版式和内联图示，同时剔除执行、导航和嵌入入口', () => {
    const output = recapDocument(
      '<style>.card{display:grid}</style><main><svg viewBox="0 0 20 20"><path d="M0 0L20 20"/></svg><a href="https://evil.test">内容</a><iframe src="https://evil.test"></iframe><img src=x onerror=alert(1)><script>alert(1)</script><form action=x><input></form></main>',
    )
    const doc = new DOMParser().parseFromString(output, 'text/html')
    expect(doc.querySelector('style')?.textContent).toContain('display:grid')
    expect(doc.querySelector('svg path')).not.toBeNull()
    expect(doc.querySelector('script, a, iframe, img, form, input')).toBeNull()
    expect(doc.querySelector('[http-equiv]')?.getAttribute('content')).toContain(
      "default-src 'none'",
    )
  })
  it('读取已有整理并允许原文对照，iframe 没有脚本和同源权限', async () => {
    vi.mocked(fetchVisualRecap).mockResolvedValue({
      status: 'SUCCEEDED',
      html: '<main>整理</main>',
      sourceText: '原文',
      sourceRev: 3,
      stale: true,
    })
    const wrapper = mount(NoteVisualRecap, {
      props: { noteId: 'n', rev: 4, blocked: false },
      global: { stubs: { SettingsDialogShell: shell } },
    })
    await flushPromises()
    expect(wrapper.find('iframe').attributes('sandbox')).toBe('')
    expect(wrapper.text()).toContain('原文已有更新')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '对照原文')!
      .trigger('click')
    expect(wrapper.find('pre').text()).toBe('原文')
    wrapper.unmount()
  })
  it('关闭后不继续轮询，不自动调用生成', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchVisualRecap).mockResolvedValue({ status: 'RUNNING' })
    const wrapper = mount(NoteVisualRecap, {
      props: { noteId: 'n', rev: 1, blocked: false },
      global: { stubs: { SettingsDialogShell: shell } },
    })
    await flushPromises()
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(10000)
    expect(fetchVisualRecap).toHaveBeenCalledTimes(1)
    expect(startVisualRecap).not.toHaveBeenCalled()
  })
})
