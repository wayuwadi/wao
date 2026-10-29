import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiShareSub, apiShareTask } from '@/api/board'
import type { SharedTask } from '@/types'
import ShareTaskView from '@/views/ShareTaskView.vue'

const route = reactive({
  params: { id: 't1', subId: undefined as string | undefined },
  query: { openOnly: 'true' as string | undefined },
})

vi.mock('vue-router', () => ({ useRoute: () => route }))
vi.mock('@/api/board', () => ({
  apiShareTask: vi.fn<(typeof import('@/api/board'))['apiShareTask']>(),
  apiShareSub: vi.fn<(typeof import('@/api/board'))['apiShareSub']>(),
}))
vi.mock('@/components/SiteFooter.vue', () => ({ default: { template: '<footer />' } }))
enableAutoUnmount(afterEach)

const shared: SharedTask = {
  kind: 'task',
  id: 't1',
  subId: null,
  title: '整理这周的高频任务',
  status: '进行中',
  pr: 'P2',
  due: '2026-08-13',
  projectId: 'p1',
  project: 'Wa All in One',
  desc: '从真实数据开始整理。',
  images: [],
  subs: [{ id: 's1', text: '确认分享页在手机上可读', done: false }],
  pdcaCycles: [],
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('ShareTaskView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    route.params.id = 't1'
    route.params.subId = undefined
    route.query.openOnly = 'true'
  })

  it('读取期间只展示明确的加载状态，成功后再展示真实任务', async () => {
    const request = deferred<SharedTask>()
    vi.mocked(apiShareTask).mockReturnValue(request.promise)
    const wrapper = mount(ShareTaskView)

    expect(wrapper.get('[role="status"]').text()).toContain('正在读取任务')
    expect(wrapper.find('.title').exists()).toBe(false)
    expect(apiShareTask).toHaveBeenCalledWith('t1', true)

    request.resolve(shared)
    await flushPromises()

    expect(wrapper.get('.title').text()).toBe('整理这周的高频任务')
    expect(wrapper.text()).toContain('确认分享页在手机上可读')
  })

  it('失败时保留原因和原位重试入口，重试成功后恢复内容', async () => {
    const retryRequest = deferred<SharedTask>()
    vi.mocked(apiShareTask)
      .mockRejectedValueOnce(new Error('网络暂不可用'))
      .mockReturnValueOnce(retryRequest.promise)
    const wrapper = mount(ShareTaskView)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('任务暂时没读出来')
    expect(wrapper.get('[role="alert"]').text()).toContain('可能是网络暂时不可用')
    expect(wrapper.get('.retry-btn').text()).toContain('重新加载')

    await wrapper.get('.retry-btn').trigger('click')
    expect(wrapper.get('[role="status"]').text()).toContain('正在读取任务')
    retryRequest.resolve(shared)
    await flushPromises()

    expect(apiShareTask).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.title').text()).toBe(shared.title)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('快速切换分享地址时忽略旧请求的迟到结果', async () => {
    const oldRequest = deferred<SharedTask>()
    const newRequest = deferred<SharedTask>()
    vi.mocked(apiShareTask).mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)
    const wrapper = mount(ShareTaskView)

    route.params.id = 't2'
    await flushPromises()
    const newer = { ...shared, id: 't2', title: '新地址对应的任务' }
    newRequest.resolve(newer)
    await flushPromises()
    oldRequest.resolve(shared)
    await flushPromises()

    expect(apiShareTask).toHaveBeenNthCalledWith(2, 't2', true)
    expect(wrapper.get('.title').text()).toBe('新地址对应的任务')
  })

  it('服务端确认不存在的分享不提供无效重试入口', async () => {
    vi.mocked(apiShareTask).mockRejectedValueOnce(Object.assign(new Error('not found'), { status: 404 }))
    const wrapper = mount(ShareTaskView)
    await flushPromises()
    expect(wrapper.text()).toContain('获取新链接')
    expect(wrapper.find('.retry-btn').exists()).toBe(false)
  })

  it('子任务地址走子任务接口并保持成功状态', async () => {
    route.params.subId = 's1'
    vi.mocked(apiShareSub).mockResolvedValue({ ...shared, kind: 'sub', subId: 's1' })
    const wrapper = mount(ShareTaskView)
    await flushPromises()

    expect(apiShareSub).toHaveBeenCalledWith('t1', 's1')
    expect(wrapper.text()).toContain('子任务分享')
  })

  it('历史 PDCA 数据继续由接口兼容返回，但分享页不再展示该模块', async () => {
    vi.mocked(apiShareTask).mockResolvedValue({
      ...shared,
      pdcaCycles: [{
        id: 'pc-history', plan: '旧计划', check: '旧复盘', act: '旧行动', outcome: '达成',
        startedAt: '2026-08-01T09:00:00+08:00', reviewedAt: '2026-08-02T09:00:00+08:00',
      }],
    })

    const wrapper = mount(ShareTaskView)
    await flushPromises()

    expect(wrapper.find('.shared-pdca').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('PDCA 记录')
  })
})
