import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import WebhookConfigPanel from '@/components/settings/WebhookConfigPanel.vue'
import { readBotDraft, rememberBotDraft } from '@/utils/botConfigDraft'
import {
  fetchWebhookEndpoints,
  fetchWebhookActivity,
  updateWebhookEndpoint,
  deleteWebhookEndpoint,
  type FeishuWebhookEndpoint,
  type FeishuWebhookActivity,
} from '@/api/system'
vi.mock('@/api/system', () => ({
  fetchWebhookEndpoints: vi.fn(),
  fetchWebhookActivity: vi.fn(),
  updateWebhookEndpoint: vi.fn(),
  createWebhookEndpoint: vi.fn(),
  deleteWebhookEndpoint: vi.fn(),
  testWebhookEndpoint: vi.fn(),
  fetchChatSyncConfig: vi.fn(),
  fetchQuickCollectConfig: vi.fn(),
}))
const existing: FeishuWebhookEndpoint = {
  id: 'fw1',
  name: '任务待办',
  description: '个人收集',
  urlMask: '••••1234',
  urlSet: true,
  enabled: true,
  systemDefault: true,
  createdAt: '2026-10-07T10:00:00',
  updatedAt: '2026-10-07T10:00:00',
  captureKind: 'TASK',
  mentionId: 'bot-task',
  mentionName: '任务待办',
  appId: 'app',
  chatId: 'chat',
  revision: 3,
}
const meta: FeishuWebhookActivity = {
  usages: [{ kind: 'CAPTURE', label: '群消息创建个人待办', count: 1 }],
  recentSends: [{ kind: 'TEST', status: 'SUCCESS', occurredAt: '2026-10-07T10:00:00' }],
  recentCaptures: [],
  canDelete: false,
  receiver: {
    enabled: true,
    appConfigured: true,
    chatConfigured: true,
    callbackVerified: true,
    appId: 'app',
    chatId: 'chat',
    chatName: '真实群',
    eventUrl: '/api/feishu/events',
    pollIntervalMinutes: 10,
  },
}
let wrapper: VueWrapper | undefined
const originalConfirm = window.confirm
async function render(member = 'wg', role = '管理员') {
  const pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore().accept({
    accountId: member,
    member: { id: member, name: member, initial: 'W', color: '#fff', role },
    identities: [],
    createdAt: '',
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/capabilities', component: WebhookConfigPanel },
      { path: '/other', component: { template: '<p>其他</p>' } },
    ],
  })
  await router.push('/capabilities?area=bots')
  await router.isReady()
  wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}
function button(text: string) {
  return wrapper!.findAll('button').find((row) => row.text() === text)!
}
describe('能力中心机器人真实引用与草稿', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.confirm = vi.fn(() => true)
    vi.mocked(fetchWebhookEndpoints).mockResolvedValue([existing])
    vi.mocked(fetchWebhookActivity).mockResolvedValue(structuredClone(meta))
    vi.mocked(updateWebhookEndpoint).mockResolvedValue({ ...existing, revision: 4 })
  })
  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    window.confirm = originalConfirm
  })
  it('展示真实引用和脱敏地址，发送测试不冒充群收集成功', async () => {
    await render()
    expect(wrapper!.text()).toContain('群消息创建个人待办')
    expect(wrapper!.text()).toContain('@ 快捷记录尚未验证')
    expect(wrapper!.text()).toContain('••••1234')
    expect((wrapper!.get('input[type=password]').element as HTMLInputElement).value).toBe('')
    await button('运行记录').trigger('click')
    expect(wrapper!.text()).toContain('发送测试 · 成功')
    expect(wrapper!.text()).toContain('暂无真实 @ 收集记录')
  })
  it('成员仅看到绑定指引，不读取全局管理接口', async () => {
    await render('wd', '成员')
    expect(wrapper!.text()).toContain('机器人由管理员统一配置')
    expect(fetchWebhookEndpoints).not.toHaveBeenCalled()
    expect(fetchWebhookActivity).not.toHaveBeenCalled()
  })
  it('未改密钥时提交同一个稳定 id 和原版本', async () => {
    await render()
    await wrapper!.get('input[placeholder="例如 WAO 主群"]').setValue('待办入口')
    await wrapper!.get('form.editor').trigger('submit')
    await flushPromises()
    expect(updateWebhookEndpoint).toHaveBeenCalledWith(
      'fw1',
      expect.objectContaining({ name: '待办入口', url: undefined, revision: 3 }),
    )
    expect(wrapper!.text()).toContain('已保存')
  })
  it('将显示名称误填为稳定标识时保留输入并拒绝保存', async () => {
    await render()
    await wrapper!.get('input[placeholder="飞书 @ 元数据中的机器人标识"]').setValue('任务待办')
    await wrapper!.get('form.editor').trigger('submit')
    await flushPromises()
    expect(wrapper!.text()).toContain('稳定标识须填写飞书真实 ID')
    expect(updateWebhookEndpoint).not.toHaveBeenCalled()
  })
  it('停用前显示实际影响，取消不写服务端', async () => {
    await render()
    window.confirm = vi.fn(() => false)
    await wrapper!.findAll('input[type=checkbox]')[0]!.setValue(false)
    await wrapper!.get('form.editor').trigger('submit')
    await flushPromises()
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('群消息创建个人待办'))
    expect(updateWebhookEndpoint).not.toHaveBeenCalled()
  })
  it('影响信息读取失败时不删除，错误归到该机器人', async () => {
    await render()
    vi.mocked(fetchWebhookActivity).mockRejectedValue(new Error('引用读取失败'))
    await button('删除配置').trigger('click')
    await flushPromises()
    expect(deleteWebhookEndpoint).not.toHaveBeenCalled()
    expect(wrapper!.get('.config-row').text()).toContain('引用读取失败')
  })
  it('将已有非默认机器人设为默认时先显示原默认的引用', async () => {
    const another = {
      ...existing,
      id: 'fw2',
      name: '另一个通知',
      systemDefault: false,
      captureKind: 'NONE' as const,
    }
    vi.mocked(fetchWebhookEndpoints).mockResolvedValue([existing, another])
    vi.mocked(fetchWebhookActivity).mockImplementation(async (id) => ({
      ...structuredClone(meta),
      usages: id === 'fw1' ? [{ kind: 'SYSTEM_DEFAULT', label: '原默认通知引用', count: 1 }] : [],
    }))
    await render()
    await wrapper!.findAll('.config-select')[1]!.trigger('click')
    await flushPromises()
    await wrapper!.findAll('input[type=checkbox]')[1]!.setValue(true)
    window.confirm = vi.fn(() => false)
    await wrapper!.get('form.editor').trigger('submit')
    await flushPromises()
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('原默认通知引用'))
    expect(updateWebhookEndpoint).not.toHaveBeenCalled()
  })
  it('确认删除时提交版本和显式影响确认', async () => {
    await render()
    vi.mocked(fetchWebhookEndpoints).mockResolvedValue([])
    await button('删除配置').trigger('click')
    await flushPromises()
    expect(deleteWebhookEndpoint).toHaveBeenCalledWith('fw1', {
      revision: 3,
      impactConfirmed: true,
    })
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('群消息创建个人待办'))
  })
  it('刷新恢复非密钥草稿并保持成员隔离', async () => {
    await render()
    await wrapper!.get('input[placeholder="例如 WAO 主群"]').setValue('尚未保存')
    await wrapper!.get('input[type=password]').setValue('private-secret')
    await flushPromises()
    expect(localStorage.getItem('wao-bot-config-draft-v1:wg:fw1')).not.toContain('private-secret')
    expect(readBotDraft('wd', 'fw1')).toBeNull()
    wrapper!.unmount()
    wrapper = undefined
    await render()
    expect(
      (wrapper!.get('input[placeholder="例如 WAO 主群"]').element as HTMLInputElement).value,
    ).toBe('尚未保存')
    expect((wrapper!.get('input[type=password]').element as HTMLInputElement).value).toBe('')
  })
  it('保存失败保留草稿，后续服务器刷新暴露版本冲突', async () => {
    await render()
    await wrapper!.get('textarea').setValue('本机草稿')
    vi.mocked(updateWebhookEndpoint).mockRejectedValue(new Error('版本冲突'))
    vi.mocked(fetchWebhookEndpoints).mockResolvedValue([
      { ...existing, revision: 5, description: '远端修改' },
    ])
    await wrapper!.get('form.editor').trigger('submit')
    await flushPromises()
    expect(wrapper!.get('textarea').element.value).toBe('本机草稿')
    expect(wrapper!.text()).toContain('其他页面已更新此配置')
    expect(readBotDraft('wg', 'fw1')?.revision).toBe(3)
    await button('保留草稿，基于最新版本保存').trigger('click')
    vi.mocked(updateWebhookEndpoint).mockResolvedValue({
      ...existing,
      revision: 6,
      description: '本机草稿',
    })
    await wrapper!.get('form.editor').trigger('submit')
    await flushPromises()
    expect(updateWebhookEndpoint).toHaveBeenLastCalledWith(
      'fw1',
      expect.objectContaining({ revision: 5, description: '本机草稿' }),
    )
  })
  it('路由切换保护尚未保存的密钥输入', async () => {
    const { router } = await render()
    await wrapper!.get('input[type=password]').setValue('private-secret')
    window.confirm = vi.fn(() => false)
    await router.push('/other')
    expect(router.currentRoute.value.path).toBe('/capabilities')
  })
  it('筛选无结果保留当前编辑草稿', async () => {
    await render()
    await wrapper!.get('textarea').setValue('未保存')
    await wrapper!.get('input[type=search]').setValue('不存在')
    expect(wrapper!.text()).toContain('没有匹配的机器人')
    expect(wrapper!.get('textarea').element.value).toBe('未保存')
  })
  it('过期账号响应不显示上一成员的配置', async () => {
    let resolve!: (value: FeishuWebhookEndpoint[]) => void
    vi.mocked(fetchWebhookEndpoints).mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )
    await render()
    useSessionStore().account = null
    resolve([existing])
    await flushPromises()
    expect(wrapper!.text()).not.toContain('个人收集')
  })
  it('恢复草稿只读取允许字段，存储中的密钥也不能回填', () => {
    localStorage.setItem(
      'wao-bot-config-draft-v1:wg:new',
      JSON.stringify({
        name: '草稿',
        description: '',
        enabled: true,
        systemDefault: false,
        captureKind: 'NOTE',
        url: 'secret',
        impactConfirmed: true,
      }),
    )
    expect(readBotDraft('wg', 'new')?.url).toBe('')
    expect(readBotDraft('wg', 'new')?.impactConfirmed).toBeUndefined()
    rememberBotDraft('wg', 'new', {
      name: '草稿',
      description: '',
      enabled: true,
      systemDefault: false,
      url: 'secret',
      impactConfirmed: true,
    })
    expect(localStorage.getItem('wao-bot-config-draft-v1:wg:new')).not.toContain('secret')
  })
})
