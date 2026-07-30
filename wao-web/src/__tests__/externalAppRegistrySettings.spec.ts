import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import { useNavStore } from '@/stores/nav'
import { fetchExternalAppRegistry, saveExternalAppRegistry } from '@/api/system'
import { DEFAULT_EXTERNAL_APPS, externalAppItemId, type ExternalAppRegistry } from '@/config/nav'

vi.mock('@/api/system', () => ({
  fetchExternalAppRegistry: vi.fn<() => Promise<ExternalAppRegistry>>(),
  saveExternalAppRegistry: vi.fn<(apps: ExternalAppRegistry['apps']) => Promise<ExternalAppRegistry>>(),
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

async function mountRegistrySettings() {
  const wrapper = mount(SettingsView, { global: { plugins: [router] } })
  const entry = wrapper.findAll('.snav-item').find((button) => button.text() === '菜单配置')
  await entry!.trigger('click')
  await flushPromises()
  return wrapper
}

describe('外部应用菜单注册表设置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(fetchExternalAppRegistry).mockResolvedValue({
      version: 1,
      apps: DEFAULT_EXTERNAL_APPS,
    })
  })

  it('读取注册表并展示现有 Agent 记录', async () => {
    const wrapper = await mountRegistrySettings()

    expect(fetchExternalAppRegistry).toHaveBeenCalledOnce()
    expect(wrapper.find('.registry-list').text()).toContain('自媒体 Agent')
    expect(wrapper.find('.registry-list').text()).toContain('media-agent')
    expect(wrapper.find('.registry-editor').text()).toContain('/apps/media-agent')
  })

  it('注册新应用后立即更新动态菜单 store', async () => {
    const dataStudio = {
      id: 'data-studio', label: '数据工作台', icon: 'ri-dashboard-3-line',
      group: 'TOOLS' as const, order: 0, visible: true, appUrl: '/studio/', healthUrl: '',
    }
    vi.mocked(saveExternalAppRegistry).mockResolvedValue({
      version: 1,
      apps: [...DEFAULT_EXTERNAL_APPS, dataStudio],
    })
    const wrapper = await mountRegistrySettings()

    await wrapper.get('button.btn-primary.sm').trigger('click')
    await wrapper.get('input[placeholder="例如 data-studio"]').setValue(dataStudio.id)
    await wrapper.get('input[placeholder="例如 数据工作台"]').setValue(dataStudio.label)
    await wrapper.get('input[placeholder="ri-apps-2-line"]').setValue(dataStudio.icon)
    await wrapper.get('select').setValue(dataStudio.group)
    await wrapper.get('input[type="number"]').setValue(dataStudio.order)
    await wrapper.get('input[placeholder="/my-app/ 或 https://app.example.com/"]').setValue(dataStudio.appUrl)
    await wrapper.get('.registry-editor').trigger('submit')
    await flushPromises()

    expect(saveExternalAppRegistry).toHaveBeenCalledWith([...DEFAULT_EXTERNAL_APPS, dataStudio])
    const nav = useNavStore()
    expect(nav.externalAppById('data-studio')?.label).toBe('数据工作台')
    expect(nav.groups.find((group) => group.label === '工具')?.items[0])
      .toBe(externalAppItemId('data-studio'))
  })
})
