import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import CalendarDayPlan from '@/components/calendar/CalendarDayPlan.vue'
import { fetchWorkdayRhythm } from '@/api/workdayRhythm'
import { DEFAULT_WORKDAY_RHYTHM } from '@/utils/workdayRhythm'

vi.mock('@/api/workdayRhythm', () => ({ fetchWorkdayRhythm: vi.fn() }))
enableAutoUnmount(afterEach)

function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  return mount(CalendarDayPlan, { props: { date: '2026-09-22', today: '2026-09-22' }, global: { plugins: [createPinia(), router] } })
}

describe('日历计划参考', () => {
  it('展示保存的时间段与指导意见，跨日明确标注，不冒充日程', async () => {
    vi.mocked(fetchWorkdayRhythm).mockResolvedValue({ ...DEFAULT_WORKDAY_RHYTHM, blocks: [{ id: 'custom', start: '22:00', end: '06:00', title: '我的休息计划', guidance: '先收尾，再放松' }] })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('今日计划')
    expect(wrapper.text()).toContain('参考安排')
    expect(wrapper.text()).toContain('22:00–06:00 次日')
    expect(wrapper.text()).toContain('先收尾，再放松')
    expect(wrapper.findAll('article')).toHaveLength(1)
    await wrapper.setProps({ date: '2026-09-23' })
    expect(wrapper.text()).toContain('2026-09-23 计划')
  })

  it('读取失败不展示默认值冒充已保存配置，可重试恢复', async () => {
    vi.mocked(fetchWorkdayRhythm).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(DEFAULT_WORKDAY_RHYTHM)
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findAll('article')).toHaveLength(0)
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findAll('article')).toHaveLength(DEFAULT_WORKDAY_RHYTHM.blocks.length)
  })
})
