import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import AutomationView from '@/views/AutomationView.vue'

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
  it('在自动化页面提供受保护的 Jenkins 部署记录入口', () => {
    const wrapper = mountView()
    const link = wrapper.get('a.cicd-open')

    expect(wrapper.get('.cicd-title').text()).toBe('Jenkins 部署中心')
    expect(link.attributes('href')).toBe('/jenkins/job/wao-main-cd/')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
    expect(wrapper.get('.cicd-desc').text()).toContain('Jenkins 管理员账号')
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
})
