import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import { useDataStore } from '@/stores/data'

vi.mock('@/api/system', () => ({
  fetchWebhookEndpoints: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
  createWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  updateWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  deleteWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  testWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
}))

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/:p(.*)*', component: { template: '<div/>' } }],
})

/** 打开「Webhook配置」页签，那里放着按项目的通知开关。 */
async function mountWebhookTab() {
  const w = mount(SettingsView, { global: { plugins: [router] } })
  await flushPromises()
  const entry = w.findAll('.snav-item').find((button) => button.text() === 'Webhook配置')
  await entry!.trigger('click')
  await flushPromises()
  return w
}

describe('按项目的通知开关', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
  })

  it('每个项目一行开关，默认都是通知中', async () => {
    const w = await mountWebhookTab()

    const rows = w.findAll('.cal-row')
    expect(rows.length).toBe(data.projects.length)
    expect(rows[0]!.text()).toContain(data.projects[0]!.name)
    expect(rows[0]!.text()).toContain('通知中')
  })

  it('关掉某个项目会写透后端并显示已静音', async () => {
    const update = vi.spyOn(data, 'updateProject').mockResolvedValue(true)
    const w = await mountWebhookTab()
    const target = data.projects[1]!

    await w.findAll('.cal-row')[1]!.find('.toggle').trigger('click')
    await flushPromises()

    expect(update).toHaveBeenCalledWith(target.id, { notifyEnabled: false })
    // spy 拦掉了真正的写入，这里补上乐观更新再断言界面
    target.notifyEnabled = false
    await w.vm.$nextTick()
    expect(w.findAll('.cal-row')[1]!.text()).toContain('已静音')
  })

  it('保存失败时开关拨回原样，不显示服务端并不知道的状态', async () => {
    vi.spyOn(data, 'updateProject').mockImplementation(async (id, patch) => {
      Object.assign(data.projectById(id)!, patch)
      return false
    })
    const w = await mountWebhookTab()

    await w.findAll('.cal-row')[1]!.find('.toggle').trigger('click')
    await flushPromises()

    expect(data.projects[1]!.notifyEnabled).toBe(true)
    expect(w.findAll('.cal-row')[1]!.text()).toContain('通知中')
  })
})
