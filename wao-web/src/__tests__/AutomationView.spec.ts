import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import AutomationView from '@/views/AutomationView.vue'
import { useDataStore } from '@/stores/data'
import type { AutomationJob } from '@/types'

function mountView(autos: AutomationJob[] = []) {
  const pinia = createPinia()
  const data = useDataStore(pinia)
  data.autos = autos
  data.loadStates.automation = 'loaded'
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
    expect(wrapper.find('.run-pagination').text()).toContain('跳至')
    expect(wrapper.text()).toContain('第1次')
    expect(wrapper.text()).not.toContain('第11次')

    await wrapper.find('.run-pagination').get('[aria-label="第 2 页"]').trigger('click')
    expect(wrapper.findAll('.grid-runs.row')).toHaveLength(1)
    expect(wrapper.text()).toContain('第11次')
  })

  it('只展示真实运行圆点，缺少服务端耗时时不伪造秒数', async () => {
    const item = job(1)
    item.runs[0]!.durationMs = undefined
    const wrapper = mountView([item])

    expect(wrapper.findAll('.dot')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('更早的运行')

    await wrapper
      .findAll('.tab')
      .find((tab) => tab.text() === '运行记录')!
      .trigger('click')
    expect(wrapper.get('.grid-runs.row').text()).toContain('—')

    await wrapper.get('.grid-runs.row').trigger('click')
    expect(wrapper.get('.grid-aoruns.row').text()).toContain('—')
  })

  it('HTTP性能巡检任务显示真实配置入口', async () => {
    const wrapper = mountView([job()])
    await wrapper.find('.op[title="配置"]').trigger('click')
    expect(wrapper.find('.http-config').exists()).toBe(true)
  })
})

it('启用状态不表示运行成功，并可进入已提交任务的真实执行结果', async () => {
  const item = job(1)
  item.runs[0]!.aiRunId = 'run-123'
  item.runs[0]!.result = '成功 · 已创建 AI Run run-123'
  const wrapper = mountView([item])
  expect(wrapper.text()).toContain('已启用')
  expect(wrapper.text()).toContain('已提交执行')
  await wrapper.get('.job-name').trigger('click')
  expect(wrapper.get('.grid-aoruns .mini').text()).toBe('已提交执行')
  expect(wrapper.get('a.result-link').attributes('href')).toBe('/ai-sessions?session=run-123')
  expect(wrapper.text()).toContain('触发记录：成功 · 已创建 AI Run run-123')
  wrapper.unmount()
})

it('等待期间阻止同一任务重复操作并展示执行器真实失败', async () => {
  const wrapper = mountView([job(1)])
  const data = useDataStore()
  let finish!: () => void
  const run = vi.spyOn(data, 'runJob').mockImplementation(
    () =>
      new Promise<void>((resolve) => {
        finish = () => {
          data.autos[0]!.runs[0]!.result = '失败 · 无可用执行电脑'
          resolve()
        }
      }),
  )
  await wrapper.get('[title="手动运行"]').trigger('click')
  expect(wrapper.text()).toContain('正在运行，请稍候')
  expect(wrapper.get('[title="暂停"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[title="手动运行"]').trigger('click')
  expect(run).toHaveBeenCalledTimes(1)
  finish()
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('无可用执行电脑')
  expect(wrapper.get('[title="手动运行"]').attributes('disabled')).toBeUndefined()
  wrapper.unmount()
})

it('请求失败保留核对入口，刷新记录不会再次运行', async () => {
  const wrapper = mountView([job(1)])
  const data = useDataStore()
  const run = vi.spyOn(data, 'runJob').mockRejectedValue(new Error('连接中断'))
  const refresh = vi.spyOn(data, 'refreshAutomation').mockResolvedValue(true)
  await wrapper.get('[title="手动运行"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('避免重复执行')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '刷新并查看记录')!
    .trigger('click')
  expect(refresh).toHaveBeenCalledOnce()
  expect(run).toHaveBeenCalledOnce()
  expect(wrapper.text()).toContain('任务详情')
  wrapper.unmount()
})
