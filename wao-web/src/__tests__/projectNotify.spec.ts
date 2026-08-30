import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProjectNotificationSettingsPanel from '@/components/settings/ProjectNotificationSettingsPanel.vue'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'

vi.mock('@/api/system', () => ({
  fetchWebhookEndpoints: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
  createWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  updateWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  deleteWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  testWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
}))

/** 独立通知设置页复用的项目通知面板。 */
async function mountNotificationTab() {
  const w = mount(ProjectNotificationSettingsPanel)
  await flushPromises()
  return w
}

describe('按项目的通知开关', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    seedDataStore(data)
  })

  it('每个项目一行开关，默认都是通知中', async () => {
    const w = await mountNotificationTab()

    const rows = w.findAll('.project-row')
    expect(rows.length).toBe(data.projects.length)
    expect(rows[0]!.text()).toContain(data.projects[0]!.name)
    expect(rows[0]!.text()).toContain('通知中')
  })

  it('关掉某个项目会写透后端并显示已静音', async () => {
    const update = vi.spyOn(data, 'updateProject').mockResolvedValue(true)
    const w = await mountNotificationTab()
    const target = data.projects[1]!

    await w.findAll('.project-row')[1]!.find('button').trigger('click')
    await flushPromises()

    expect(update).toHaveBeenCalledWith(target.id, { notifyEnabled: false })
    // spy 拦掉了真正的写入，这里补上乐观更新再断言界面
    target.notifyEnabled = false
    await w.vm.$nextTick()
    expect(w.findAll('.project-row')[1]!.text()).toContain('已静音')
  })

  it('保存失败时开关拨回原样，不显示服务端并不知道的状态', async () => {
    vi.spyOn(data, 'updateProject').mockImplementation(async (id, patch) => {
      Object.assign(data.projectById(id)!, patch)
      return false
    })
    const w = await mountNotificationTab()

    await w.findAll('.project-row')[1]!.find('button').trigger('click')
    await flushPromises()

    expect(data.projects[1]!.notifyEnabled).not.toBe(false)
    expect(w.findAll('.project-row')[1]!.text()).toContain('通知中')
  })
})
