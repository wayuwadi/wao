import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import DevelopmentGlance from '@/components/development/DevelopmentGlance.vue'
import { fetchDevelopmentOverview, type DevelopmentStatus } from '@/api/development'
import { developmentOverview, developmentRun } from './fixtures/development'

vi.mock('@/api/development', () => ({ fetchDevelopmentOverview: vi.fn() }))
const wrappers: VueWrapper[] = []
async function glance(props: { variant?: 'dashboard' | 'companion'; blocked?: boolean } = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/automation', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  const wrapper = mount(DevelopmentGlance, { props, global: { plugins: [createPinia(), router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(fetchDevelopmentOverview).mockResolvedValue(developmentOverview())
})
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
describe('看板与伙伴的真实建设摘要', () => {
  it('没有启用且无实际记录时保持quiet，不展示假零统计或伙伴提示', async () => {
    const { wrapper } = await glance()
    expect(wrapper.text()).toBe('')
    const compact = await glance({ variant: 'companion' })
    expect(compact.wrapper.find('.companion-glance').exists()).toBe(false)
  })
  it('看板显示真实分组数，点击带上真实运行id', async () => {
    vi.mocked(fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({
        runs: [
          developmentRun({ id: 'decision-real' }),
          developmentRun({ id: 'failed-real', status: 'FAILED' }),
          developmentRun({ id: 'release-1', status: 'RELEASED' }),
          developmentRun({ id: 'release-2', status: 'RELEASED' }),
        ],
      }),
    )
    const { wrapper, router } = await glance()
    expect(wrapper.findAll('.glance-counts button').map((item) => item.text())).toEqual([
      '0执行中',
      '1待确认',
      '1失败',
      '2已上线待验收',
    ])
    await wrapper.findAll('.glance-counts button')[1]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/automation?tab=development&run=decision-real')
    expect(wrapper.text()).not.toMatch(/Runner|AI Run|已完成/)
  })
  it('首页提示向日期和互动让位，解除blocked后仅显示最需要行动的条目', async () => {
    vi.mocked(fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({
        runs: [
          developmentRun({ id: 'decision-real' }),
          developmentRun({ status: 'FAILED' }),
          developmentRun({ id: 'active-real', status: 'IMPLEMENTING' }),
        ],
      }),
    )
    const { wrapper, router } = await glance({ variant: 'companion', blocked: true })
    expect(wrapper.find('.companion-glance').exists()).toBe(false)
    await wrapper.setProps({ blocked: false })
    expect(wrapper.text()).toContain('1 项建设待确认')
    expect(wrapper.findAll('button')).toHaveLength(1)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.run).toBe('decision-real')
  })
  it('执行中计数仅来自实际推进的六种状态，入队不冒充执行', async () => {
    const statuses: DevelopmentStatus[] = [
      'ANALYZING',
      'IMPLEMENTING',
      'VERIFYING',
      'PR_READY',
      'MERGED',
      'DEPLOYING',
    ]
    vi.mocked(fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({
        runs: [
          ...statuses.map((status, index) => developmentRun({ id: `executing-${index}`, status })),
          developmentRun({ id: 'queued-only', status: 'QUEUED' }),
        ],
      }),
    )
    const { wrapper, router } = await glance()
    expect(wrapper.findAll('.glance-counts button')[0]!.text()).toBe('6执行中')
    await wrapper.findAll('.glance-counts button')[0]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.run).toBe('executing-0')
  })
  it('没有更优先决定时伙伴显示正在执行的真实功能，仍尊重阻塞', async () => {
    vi.mocked(fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({
        runs: [developmentRun({ id: 'active-home', title: '首页功能审视', status: 'ANALYZING' })],
      }),
    )
    const { wrapper, router } = await glance({ variant: 'companion', blocked: true })
    expect(wrapper.find('.companion-glance').exists()).toBe(false)
    await wrapper.setProps({ blocked: false })
    expect(wrapper.text()).toContain('正在推进：首页功能审视')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/automation?tab=development&run=active-home')
  })
  it('仅有入队记录时首页伙伴保持安静', async () => {
    vi.mocked(fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({ runs: [developmentRun({ status: 'QUEUED' })] }),
    )
    const { wrapper } = await glance({ variant: 'companion' })
    expect(wrapper.find('.companion-glance').exists()).toBe(false)
  })
  it('看板读取失败有明确重试，首页不伪报无任务', async () => {
    vi.mocked(fetchDevelopmentOverview).mockRejectedValue(new Error('摘要读取受阻'))
    const { wrapper } = await glance()
    expect(wrapper.get('[role="alert"]').text()).toContain('摘要读取受阻')
    vi.mocked(fetchDevelopmentOverview).mockResolvedValue(
      developmentOverview({
        policy: { enabled: true, version: 1, intervalHours: 4, maxParallel: 3 },
      }),
    )
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('打开工作台')
  })
})
