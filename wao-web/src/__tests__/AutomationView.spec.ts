import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import AutomationView from '@/views/AutomationView.vue'
import { useDataStore } from '@/stores/data'
import type { AutomationJob } from '@/types'

function mountView(autos: AutomationJob[] = []) {
  const pinia = createPinia()
  useDataStore(pinia).autos = autos
  return mount(AutomationView, {
    global: {
      plugins: [pinia],
      stubs: {
        DemoBanner: true,
        ChatSyncConfigModal: true,
        HttpPerformanceConfigModal: { template: '<div class="http-config"></div>' },
      },
    },
  })
}

function job(runs = 0): AutomationJob {
  return {
    id: 'job-http-performance-ai-run',
    name: 'HTTP 请求性能夜间 AI 巡检',
    icon: 'ri-speed-up-line',
    schedule: '每日 06:30',
    status: '正常',
    last: '今天 06:30',
    desc: '',
    source: '',
    target: '',
    steps: [],
    handler: 'http-performance-ai-run',
    runs: Array.from({ length: runs }, (_, index) => ({
      time: `8月${26 - index}日 06:30`,
      executor: '定时任务',
      result: `成功 · 第${index + 1}次`,
      runAt: `2026-08-${String(26 - index).padStart(2, '0')}T06:30:00`,
      durationMs: 4200,
    })),
  }
}

describe('AutomationView 导航', () => {
  it('不再把 Jenkins 入口混入自动化业务页面', () => {
    const wrapper = mountView()
    expect(wrapper.find('.cicd-tool').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Jenkins 部署中心')
    wrapper.unmount()
  })

  it('只保留任务列表和运行记录，不再暴露反馈报表', async () => {
    const wrapper = mountView()
    const tabs = wrapper.findAll('.tab')
    expect(tabs).toHaveLength(2)
    expect(tabs.some((item) => item.text() === '反馈报表')).toBe(false)

    await tabs.find((item) => item.text() === '任务列表')!.trigger('click')
    expect(wrapper.find('.grid-jobs').exists()).toBe(true)

    await tabs.find((item) => item.text() === '运行记录')!.trigger('click')
    expect(wrapper.find('.grid-runs').exists()).toBe(true)
    wrapper.unmount()
  })

  it('运行记录默认只展示最新10条并支持翻页', async () => {
    const wrapper = mountView([job(11)])
    await wrapper
      .findAll('.tab')
      .find((item) => item.text() === '运行记录')!
      .trigger('click')

    expect(wrapper.findAll('.grid-runs.row')).toHaveLength(10)
    expect(wrapper.text()).toContain('第 1 / 2 页')
    expect(wrapper.text()).toContain('第1次')
    expect(wrapper.text()).not.toContain('第11次')

    await wrapper.find('.run-pagination').findAll('button')[1]!.trigger('click')
    expect(wrapper.findAll('.grid-runs.row')).toHaveLength(1)
    expect(wrapper.text()).toContain('第11次')
  })

  it('HTTP性能巡检任务显示真实配置入口', async () => {
    const wrapper = mountView([job()])
    await wrapper.find('.op[title="配置"]').trigger('click')
    expect(wrapper.find('.http-config').exists()).toBe(true)
  })
})
