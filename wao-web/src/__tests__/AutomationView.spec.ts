import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import AutomationView from '@/views/AutomationView.vue'

const { fetchReport } = vi.hoisted(() => ({
  fetchReport: vi.fn().mockResolvedValue({
    period: 'day', doneCount: 2, missedCount: 1, totalCount: 3, rows: [],
  }),
}))

vi.mock('@/api/board', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/api/board')>(),
  fetchNotificationFeedbackReport: fetchReport,
}))

function mountView() {
  return mount(AutomationView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        DemoBanner: true,
        ChatSyncConfigModal: true,
      },
    },
  })
}

describe('AutomationView 导航', () => {
  it('查看反馈报表后仍可切回任务列表和运行记录', async () => {
    const wrapper = mountView()
    const tabs = wrapper.findAll('.tab')

    await tabs.find((item) => item.text() === '反馈报表')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.report-card').exists()).toBe(true)
    expect(wrapper.find('.tab.on').text()).toBe('反馈报表')

    await tabs.find((item) => item.text() === '任务列表')!.trigger('click')
    expect(wrapper.find('.report-card').exists()).toBe(false)
    expect(wrapper.find('.grid-jobs').exists()).toBe(true)

    await tabs.find((item) => item.text() === '运行记录')!.trigger('click')
    expect(wrapper.find('.grid-runs').exists()).toBe(true)
    wrapper.unmount()
  })
})
