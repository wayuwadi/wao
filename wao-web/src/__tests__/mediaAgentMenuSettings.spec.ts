import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import { useNavStore } from '@/stores/nav'
import { fetchMediaAgentMenu, saveMediaAgentMenu } from '@/api/system'
import type { MediaAgentMenuConfig } from '@/config/nav'

vi.mock('@/api/system', () => ({
  fetchMediaAgentMenu: vi.fn<() => Promise<MediaAgentMenuConfig>>(),
  saveMediaAgentMenu: vi.fn<(payload: MediaAgentMenuConfig) => Promise<MediaAgentMenuConfig>>(),
  fetchWebhookConfig: vi.fn<() => Promise<unknown>>(),
  saveWebhookConfig: vi.fn<() => Promise<unknown>>(),
  testWebhook: vi.fn<() => Promise<void>>(),
  fetchSiteInfo: vi.fn<() => Promise<unknown>>(),
  saveSiteInfo: vi.fn<() => Promise<unknown>>(),
}))

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/:p(.*)*', component: { template: '<div/>' } }],
})

async function mountMenuSettings() {
  const wrapper = mount(SettingsView, { global: { plugins: [router] } })
  const entry = wrapper.findAll('.snav-item').find((button) => button.text() === '菜单配置')
  await entry!.trigger('click')
  await flushPromises()
  return wrapper
}

describe('自媒体 Agent 菜单设置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(fetchMediaAgentMenu).mockResolvedValue({
      label: '自媒体 Agent',
      group: 'PUBLIC',
      order: 20,
      visible: true,
    })
  })

  it('读取服务端配置并展示默认公共分组', async () => {
    const wrapper = await mountMenuSettings()

    expect(fetchMediaAgentMenu).toHaveBeenCalledOnce()
    expect(wrapper.find('input[placeholder="例如 自媒体 Agent"]').element).toHaveProperty('value', '自媒体 Agent')
    expect((wrapper.find('select').element as HTMLSelectElement).value).toBe('PUBLIC')
    expect(wrapper.find('.menu-preview').text()).toContain('公共')
  })

  it('保存名称和位置后立即更新导航 store', async () => {
    vi.mocked(saveMediaAgentMenu).mockResolvedValue({
      label: '内容助手',
      group: 'TOOLS',
      order: 0,
      visible: true,
    })
    const wrapper = await mountMenuSettings()

    await wrapper.find('input[placeholder="例如 自媒体 Agent"]').setValue('内容助手')
    await wrapper.find('select').setValue('TOOLS')
    await wrapper.find('input[type="number"]').setValue(0)
    await wrapper.find('.menu-form').trigger('submit')
    await flushPromises()

    expect(saveMediaAgentMenu).toHaveBeenCalledWith({
      label: '内容助手',
      group: 'TOOLS',
      order: 0,
      visible: true,
    })
    const nav = useNavStore()
    expect(nav.mediaAgentMenu.label).toBe('内容助手')
    expect(nav.groups.find((group) => group.label === '工具')?.items[0]).toBe('mediaAgent')
    expect(wrapper.find('.btn-primary').text()).toBe('已保存')
  })
})
