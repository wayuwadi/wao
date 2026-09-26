import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { apiPutTask, apiMoveSubtask } from '@/api/board'
import { reloadToLatestVersion } from '@/utils/versionUpdate'
import { fetchWebVersion } from '@/api/version'
import VersionSettingsPanel from '@/components/settings/VersionSettingsPanel.vue'
import InlineSubtasks from '@/components/task/InlineSubtasks.vue'
import { copyRichContent, portableClipboardHtml } from '@/utils/richClipboard'
import { subtaskRows } from '@/utils/subtaskTree'
vi.mock('@/api/board', async original => ({ ...await original<object>(), apiPutTask: vi.fn(), apiMoveSubtask: vi.fn() }))
vi.mock('@/utils/versionUpdate', () => ({ reloadToLatestVersion: vi.fn() }))
vi.mock('@/api/version', () => ({ fetchWebVersion: vi.fn() }))
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); seedDataStore(); vi.mocked(apiPutTask).mockImplementation(async (_id, task) => task as never) })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); document.querySelectorAll('script[data-v]').forEach(el => el.remove()) })
describe('子任务树和移动', () => {
  it('展示层级，隐藏完成项仍保留未完成子项与原数据', async () => {
    const data = useDataStore(), task = data.tasks[0]!
    task.subs = [{ id: 'child', parentId: 'root', text: '子项', done: false }, { id: 'root', text: '父项', done: true }]
    expect(subtaskRows(task.id, task.subs).map(row => [row.key, row.depth])).toEqual([['root', 0], ['child', 1]])
    const wrapper = mount(InlineSubtasks, { props: { taskId: task.id } })
    await wrapper.get('.done-switch').trigger('click')
    expect(wrapper.findAll('.inline-sub')).toHaveLength(1)
    expect(wrapper.text()).toContain('子项'); expect(task.subs).toHaveLength(2)
    wrapper.unmount()
  })
  it('移动失败不删源；同一时刻只允许一次准备与请求', async () => {
    const data = useDataStore(), source = data.tasks[0]!, target = data.tasks[1]!
    source.subs = [{ id: 'a', text: '保留', done: false }]
    let reject!: (error: Error) => void
    vi.mocked(apiMoveSubtask).mockReturnValueOnce(new Promise((_resolve, r) => reject = r))
    const first = data.moveSubtask(source.id, 'a', target.id)
    await expect(data.moveSubtask(source.id, 'a', target.id)).rejects.toThrow('正在移动')
    await flushPromises()
    expect(source.subs[0]!.text).toBe('保留')
    reject(new Error('离线')); await expect(first).rejects.toThrow('离线')
    expect(source.subs[0]!.id).toBe('a'); expect(data.movingSubtasks).toBe(false)
  })
  it('成功移动只更新子任务，保留在途的主任务文字和草稿', async () => {
    const data = useDataStore(), source = data.tasks[0]!, target = data.tasks[1]!
    source.subs = [{ id: 'a', text: '移动', done: false }]
    let resolve!: (tasks: typeof data.tasks) => void
    vi.mocked(apiMoveSubtask).mockReturnValueOnce(new Promise(r => resolve = r))
    const pending = data.moveSubtask(source.id, 'a', target.id); await flushPromises()
    data.patchTask(source.id, { title: '移动期间修改主标题' })
    resolve([{ ...source, title: '旧标题', subs: [], subtaskVersion: 'new' }, { ...target, subs: source.subs }])
    await pending
    expect(source.title).toBe('移动期间修改主标题'); expect(source.subs).toEqual([])
    expect(source.subtaskVersion).toBe('new')
    await data.flushTask(source.id)
    expect(apiPutTask).toHaveBeenLastCalledWith(source.id, expect.objectContaining({ title: '移动期间修改主标题', subs: [] }))
  })
})
describe('富文本剪贴板', () => {
  it('小记预览图复制为原图地址', () => {
    const html = portableClipboardHtml('<img src="/api/images/img1/preview" data-full-src="/api/images/img1/raw" alt="总览图">')
    expect(html).toContain('/api/images/img1/raw')
    expect(html).not.toContain('/api/images/img1/preview')
    expect(html).not.toContain('data-full-src')
    const untrusted = portableClipboardHtml('<img src="/api/images/img1/preview" data-full-src="javascript:alert(1)">')
    expect(untrusted).not.toContain('javascript:')
  })
  it('保留标题嵌套列表代码块，清除脚本', () => {
    const html = portableClipboardHtml('<h2>标题</h2><ol><li>父<ul><li>子</li></ul></li></ol><pre>a  b</pre><script>bad()</script>')
    expect(html).toContain('<h2>标题</h2>'); expect(html).toContain('list-style-type:decimal')
    expect(html).toContain('list-style-type:disc'); expect(html).toContain('white-space:pre-wrap'); expect(html).not.toContain('script')
  })
  it('旧浏览器复制事件同时写入 HTML 与 Markdown 并恢复焦点', async () => {
    vi.stubGlobal('navigator', { clipboard: undefined })
    const input = document.createElement('input'); document.body.appendChild(input); input.focus()
    const values: Record<string, string> = {}
    Object.defineProperty(document, 'execCommand', { configurable: true, value: vi.fn(() => {
      const event = new Event('copy', { cancelable: true }); Object.defineProperty(event, 'clipboardData', { value: { setData: (type: string, value: string) => values[type] = value } })
      document.dispatchEvent(event); return true
    }) })
    expect(await copyRichContent('<h2>标题</h2>', '## 标题')).toBe(true)
    expect(values['text/html']).toBe('<h2>标题</h2>'); expect(values['text/plain']).toBe('## 标题')
    expect(document.activeElement).toBe(input); input.remove()
  })
})
describe('版本检查', () => {
  it('刷新前等待草稿写透，失败保留当前页面', async () => {
    const mark = document.createElement('script'); mark.setAttribute('data-v', 'old'); document.head.appendChild(mark)
    vi.mocked(fetchWebVersion).mockResolvedValue('next')
    const save = vi.spyOn(useDataStore(), 'flushWorkspace').mockResolvedValueOnce(false).mockResolvedValueOnce(true)
    const wrapper = mount(VersionSettingsPanel)
    await wrapper.get('button').trigger('click'); await flushPromises()
    await wrapper.findAll('button')[1]!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('有草稿保存失败'); expect(reloadToLatestVersion).not.toHaveBeenCalled()
    await wrapper.findAll('button')[1]!.trigger('click'); await flushPromises()
    expect(save).toHaveBeenCalledTimes(2); expect(reloadToLatestVersion).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it('失败可重试，只有版本变化才提供显式刷新', async () => {
    const mark = document.createElement('script'); mark.setAttribute('data-v', 'current'); document.head.appendChild(mark)
    vi.mocked(fetchWebVersion).mockRejectedValueOnce(new Error('网络失败')).mockResolvedValueOnce('current').mockResolvedValueOnce('next')
    const wrapper = mount(VersionSettingsPanel)
    await wrapper.get('button').trigger('click'); await flushPromises(); expect(wrapper.text()).toContain('网络失败')
    await wrapper.get('button').trigger('click'); await flushPromises(); expect(wrapper.text()).toContain('当前已是最新版本'); expect(wrapper.findAll('button')).toHaveLength(1)
    await wrapper.get('button').trigger('click'); await flushPromises(); expect(wrapper.text()).toContain('发现新版本'); expect(wrapper.findAll('button')).toHaveLength(2)
    wrapper.unmount()
  })
})
