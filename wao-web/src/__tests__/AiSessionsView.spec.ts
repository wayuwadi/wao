import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import AiSessionsView from '@/views/AiSessionsView.vue'
import AiSessionConversation from '@/components/ai/AiSessionConversation.vue'
import * as api from '@/api/aiSessions'
import { fetchAiRunners } from '@/api/ai'
import type { AiRun } from '@/types'
vi.mock('@/api/aiSessions', () => ({
  listSessions: vi.fn(),
  getSession: vi.fn(),
  saveSessionPreference: vi.fn(),
  sessionConversation: vi.fn(),
  sendSessionInput: vi.fn(),
  pauseSession: vi.fn(),
  sessionWorkflows: vi.fn(),
  createSession: vi.fn(),
}))
vi.mock('@/api/ai', () => ({ fetchAiRunners: vi.fn() }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))
const run = {
  id: 'ar1',
  taskId: 't1',
  taskTitle: '持久会话',
  projectId: 'p1',
  projectName: 'WAO',
  engine: 'codex',
  status: 'succeeded',
  runnerId: 'n1',
  runnerName: 'Linux 主节点',
  sessionId: 'thread-1',
  progress: [
    { id: 'm1', kind: 'message', content: '**第一轮完成**', createdAt: '2026-09-08T10:00:00' },
  ],
  createdAt: '2026-09-08T09:00:00',
} as AiRun
const detail = (id = 'ar1'): api.SessionDetail => ({
  run: { ...run, id, sessionId: `thread-${id}` },
  preference: { title: null, pinned: false, archived: false },
  prompt: '初始目标',
})
const row = {
  id: 'ar1',
  taskId: 't1',
  title: '持久会话',
  projectName: 'WAO',
  engine: 'codex',
  model: 'gpt-6-astra',
  status: 'succeeded',
  sessionId: 'thread-1',
  runnerName: 'Linux 主节点',
  preview: '完成',
  updatedAt: '2026-09-08 10:00:00',
  pinned: false,
  archived: false,
}
let wrappers: VueWrapper[] = []
beforeEach(() => {
  localStorage.clear()
  vi.resetAllMocks()
  setActivePinia(createPinia())
  useSessionStore().account = { member: { id: 'u1' } } as never
  vi.mocked(api.listSessions).mockResolvedValue({
    items: [row, { ...row, id: 'ar2', title: '第二个会话' }],
    total: 2,
    page: 1,
    size: 25,
  })
  vi.mocked(api.getSession).mockImplementation(async (id) => detail(id))
  vi.mocked(api.sessionWorkflows).mockResolvedValue([
    { projectId: 'p1', projectName: 'WAO', config: { defaultEngine: 'codex' } },
  ] as never)
  vi.mocked(fetchAiRunners).mockResolvedValue([
    { id: 'n1', name: 'Linux 主节点', online: true },
  ] as never)
})
afterEach(() => {
  wrappers.forEach((w) => w.unmount())
  wrappers = []
})
async function view(path = '/ai-sessions?session=ar1') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/ai-sessions', component: { template: '<div />' } }],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AiSessionsView, { global: { plugins: [router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
function conversation() {
  const w = mount(AiSessionConversation, { props: { run, prompt: '目标' } })
  wrappers.push(w)
  return w
}
describe('云端会话工作区', () => {
  it('直接显示历史和原 session，切换会话后旧请求不覆盖当前会话', async () => {
    const { wrapper, router } = await view()
    expect(wrapper.text()).toContain('thread-ar1')
    expect(wrapper.find('.message strong').text()).toBe('第一轮完成')
    let resolve!: (value: api.SessionDetail) => void
    vi.mocked(api.getSession).mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r
        }),
    )
    await router.push('/ai-sessions?session=slow')
    await flushPromises()
    await router.push('/ai-sessions?session=ar2')
    await flushPromises()
    resolve(detail('slow'))
    await flushPromises()
    expect(wrapper.text()).toContain('thread-ar2')
    expect(wrapper.text()).not.toContain('thread-slow')
  })
  it('切换会话保留各自草稿，失败可重试且发送仍指向原运行', async () => {
    const { wrapper, router } = await view()
    await wrapper.get('textarea[aria-label="继续对话"]').setValue('请继续优化')
    await router.push('/ai-sessions?session=ar2')
    await flushPromises()
    expect(
      (wrapper.get('textarea[aria-label="继续对话"]').element as HTMLTextAreaElement).value,
    ).toBe('')
    await router.push('/ai-sessions?session=ar1')
    await flushPromises()
    expect(
      (wrapper.get('textarea[aria-label="继续对话"]').element as HTMLTextAreaElement).value,
    ).toBe('请继续优化')
    vi.mocked(api.sendSessionInput).mockRejectedValueOnce(new Error('网络失败'))
    await wrapper.get('form.composer').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('网络失败')
    expect(localStorage.getItem('wao:airun-draft:u1:ar1')).toBe('请继续优化')
    vi.mocked(api.sendSessionInput).mockResolvedValueOnce({ ...run, status: 'queued' })
    await wrapper.get('form.composer').trigger('submit')
    await flushPromises()
    expect(api.sendSessionInput).toHaveBeenLastCalledWith('ar1', '请继续优化')
    expect(localStorage.getItem('wao:airun-draft:u1:ar1')).toBeNull()
  })
  it('名称失败保留草稿，置顶归档不执行或暂停 AI', async () => {
    const { wrapper } = await view()
    await wrapper
      .findAll('.session-actions button')
      .find((b) => b.text() === '改名')!
      .trigger('click')
    await wrapper.get('input[aria-label="会话名称"]').setValue('新的会话名')
    vi.mocked(api.saveSessionPreference).mockRejectedValueOnce(new Error('保存失败'))
    await wrapper.get('form.rename').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('保存失败')
    expect(localStorage.getItem('wao:ai-session-name:u1:ar1')).toBe('新的会话名')
    vi.mocked(api.saveSessionPreference).mockResolvedValueOnce({
      title: '新的会话名',
      pinned: false,
      archived: false,
    })
    await wrapper.get('form.rename').trigger('submit')
    await flushPromises()
    expect(wrapper.get('h2').text()).toBe('新的会话名')
    vi.mocked(api.saveSessionPreference).mockResolvedValueOnce({
      title: '新的会话名',
      pinned: true,
      archived: false,
    })
    await wrapper
      .findAll('.session-actions button')
      .find((b) => b.text() === '置顶')!
      .trigger('click')
    await flushPromises()
    vi.mocked(api.saveSessionPreference).mockResolvedValueOnce({
      title: '新的会话名',
      pinned: true,
      archived: true,
    })
    await wrapper
      .findAll('.session-actions button')
      .find((b) => b.text() === '归档')!
      .trigger('click')
    await flushPromises()
    expect(api.saveSessionPreference).toHaveBeenLastCalledWith('ar1', {
      title: '新的会话名',
      pinned: true,
      archived: true,
    })
    expect(api.sendSessionInput).not.toHaveBeenCalled()
    expect(api.pauseSession).not.toHaveBeenCalled()
  })
  it('新会话在入口派发，失败保留输入，成功进入对应运行', async () => {
    const { wrapper, router } = await view('/ai-sessions?new=1')
    await wrapper.get('textarea[aria-label="新会话内容"]').setValue('仅问答验收')
    vi.mocked(api.createSession).mockRejectedValueOnce(new Error('暂时离线'))
    await wrapper.get('.new-panel form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('暂时离线')
    expect(JSON.parse(localStorage.getItem('wao:ai-session-new:u1')!).goal).toBe('仅问答验收')
    vi.mocked(api.createSession).mockResolvedValueOnce(run)
    await wrapper.get('.new-panel form').trigger('submit')
    await flushPromises()
    expect(api.createSession).toHaveBeenLastCalledWith({
      goal: '仅问答验收',
      projectId: 'p1',
      runnerId: null,
      planMode: false,
      goalMode: false,
      reasoningEffort: 'medium',
    })
    expect(router.currentRoute.value.query.session).toBe('ar1')
  })
  it('历史失败可重试，轮询窗口滑动保留已读消息', async () => {
    const w = conversation()
    const progress = Array.from({ length: 50 }, (_, i) => ({
      id: `m${String(i).padStart(3, '0')}`,
      kind: 'message' as const,
      content: `内容 ${i}`,
      createdAt: `2026-09-08T10:00:${String(i).padStart(2, '0')}`,
    }))
    await w.setProps({ run: { ...run, progress } })
    vi.mocked(api.sessionConversation).mockRejectedValueOnce(new Error('历史加载失败'))
    await w.get('.earlier').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('历史加载失败')
    vi.mocked(api.sessionConversation).mockResolvedValueOnce({
      items: [{ id: 'old', kind: 'user', content: '更早的问题', createdAt: '2026-09-07T10:00:00' }],
      nextCursor: null,
    })
    await w.get('.earlier').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('更早的问题')
    await w.setProps({
      run: {
        ...run,
        progress: [
          { id: 'latest', kind: 'message', content: '最新回复', createdAt: '2026-09-08T11:00:00' },
        ],
      },
    })
    expect(w.text()).toContain('内容 49')
    expect(w.text()).toContain('最新回复')
    expect(w.text()).toContain('更早的问题')
  })
  it('旧运行无 session 时明确不可续聊，暂停记录仍可输入', async () => {
    const w = conversation()
    await w.setProps({ run: { ...run, sessionId: null, status: 'failed' } })
    expect(w.find('textarea').exists()).toBe(false)
    expect(w.text()).toContain('尚未保存可恢复的会话')
    await w.setProps({ run: { ...run, status: 'cancelled' } })
    expect(w.find('textarea').exists()).toBe(true)
  })
})

it('配置失败不冒充无工作流，也不使用草稿项目直接派发；重试保留目标', async () => {
  localStorage.setItem('wao:ai-session-new:u1', JSON.stringify({ goal: '保留我的目标', projectId: 'p1' }))
  vi.mocked(api.sessionWorkflows).mockRejectedValueOnce(new Error('连接失败'))
  const { wrapper } = await view('/ai-sessions?new=1')
  expect(wrapper.text()).toContain('配置读取失败')
  expect(wrapper.text()).not.toContain('暂无可用项目工作流')
  expect(wrapper.get('.new-footer button').attributes('disabled')).toBeDefined()
  await wrapper.get('form').trigger('submit')
  expect(api.createSession).not.toHaveBeenCalled()
  await wrapper.findAll('button').find((b) => b.text() === '重新加载配置')!.trigger('click')
  await flushPromises()
  expect(wrapper.get<HTMLTextAreaElement>('[aria-label="新会话内容"]').element.value).toBe('保留我的目标')
  expect(wrapper.get('.new-footer button').attributes('disabled')).toBeUndefined()
})

it('失效草稿项目必须重新选择，不能派发不存在于配置中的目标', async () => {
  localStorage.setItem('wao:ai-session-new:u1', JSON.stringify({ goal: '保留目标', projectId: 'removed' }))
  const { wrapper } = await view('/ai-sessions?new=1')
  expect(wrapper.text()).toContain('原选择暂不可用')
  expect(wrapper.get('.new-footer button').attributes('disabled')).toBeDefined()
  await wrapper.get('form select').setValue('p1')
  expect(wrapper.get('.new-footer button').attributes('disabled')).toBeUndefined()
})
