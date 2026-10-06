import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SystemAiCenter from '@/components/capabilities/SystemAiCenter.vue'
import { useSessionStore } from '@/stores/session'
import { filterSystemAiFeatures } from '@/utils/systemAiCatalog'
import { fetchSystemAiActivity, fetchSystemAiResources } from '@/api/systemAi'
vi.mock('@/api/systemAi', () => ({
  fetchSystemAiActivity: vi.fn(),
  fetchSystemAiResources: vi.fn(),
}))
const note = {
  props: ['section'],
  template: '<div class="shared-note">小记配置 {{ section }}</div>',
}
async function render(path = '/capabilities?area=system-ai') {
  setActivePinia(createPinia())
  useSessionStore().accept({
    accountId: 'synthetic',
    member: { id: 'synthetic', name: '合成成员', initial: 'M', color: '', role: '成员' },
    identities: [],
    createdAt: '',
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/capabilities', component: SystemAiCenter }],
  })
  await router.push(path)
  const wrapper = mount(SystemAiCenter, {
    global: {
      plugins: [router],
      stubs: {
        NoteAiSettingsForm: note,
        TianrenAiSettingsDialog: {
          props: { embedded: Boolean, section: String },
          template: '<div class="shared-tianren">{{ embedded }} {{ section }}</div>',
        },
        NoteDiscoveryPreference: true,
        CompanionPreferenceForm: true,
        AiAccountPanel: true,
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}
function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((item) => item.text() === text)!
}
describe('系统 AI 功能与依赖管理', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchSystemAiResources).mockResolvedValue({
      capabilities: {
        value: [
          {
            id: 'local-codex',
            label: '本机 Codex',
            provider: 'codex',
            executionMode: 'LOCAL_CODEX',
            available: true,
            message: '已就绪',
          },
        ],
        error: '',
      },
      models: {
        value: {
          models: [{ id: 'synthetic-model', label: '合成模型', reasoningEfforts: ['medium'] }],
          stale: true,
          updatedAt: null,
          message: '刷新中',
        },
        error: '',
      },
    })
    vi.mocked(fetchSystemAiActivity).mockResolvedValue({
      summary: '合成记录',
      items: [],
      note: '没有聚合日志',
    })
  })
  it('搜索按真实入口登记，Python 与混合类型不冒充已有能力', async () => {
    expect(filterSystemAiFeatures('天人一卦', 'AI').map((item) => item.id)).toEqual([
      'tianren',
      'tianren-image',
    ])
    expect(filterSystemAiFeatures('', 'Python')).toEqual([])
    expect(filterSystemAiFeatures('', 'AI + 工具')).toEqual([])
    const { wrapper } = await render()
    expect(wrapper.findAll('.ai-card')).toHaveLength(9)
    await button(wrapper, 'Python').trigger('click')
    expect(wrapper.text()).toContain('尚未登记在用能力')
    expect(wrapper.find('.shared-note').exists()).toBe(false)
    wrapper.unmount()
  })
  it('深链接复用卦象内嵌表单，配图切到同组提示词；原入口保留', async () => {
    const { wrapper, router } = await render('/capabilities?area=system-ai&feature=tianren-image')
    await button(wrapper, '功能设置').trigger('click')
    expect(wrapper.get('.shared-tianren').text()).toContain('true imagePrompt')
    expect(wrapper.get('a').attributes('href')).toBe('/tianren')
    expect(wrapper.find('[role=dialog]').exists()).toBe(false)
    await router.replace('/capabilities?area=system-ai&feature=note-image')
    await flushPromises()
    expect(wrapper.get('.shared-note').text()).toContain('imagePrompt')
    wrapper.unmount()
  })
  it('资源目录失败与过期分别展示，依赖跳转回实际配置且不触发 AI', async () => {
    const { wrapper } = await render()
    await button(wrapper, '模型与执行').trigger('click')
    expect(wrapper.text()).toContain('配置检查通过')
    expect(wrapper.text()).toContain('不验证上游登录')
    expect(wrapper.text()).toContain('目录已过期')
    await button(wrapper, '小记配图').trigger('click')
    expect(wrapper.get('.shared-note').text()).toContain('imagePrompt')
    expect(fetchSystemAiActivity).not.toHaveBeenCalled()
    vi.mocked(fetchSystemAiResources).mockResolvedValue({
      capabilities: { value: null, error: '读取失败' },
      models: { value: null, error: '读取失败' },
    })
    await button(wrapper, '模型与执行').trigger('click')
    await button(wrapper, '刷新资源').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('状态尚未确认')
    expect(wrapper.text()).not.toContain('配置检查通过')
    wrapper.unmount()
  })
  it('切换功能丢弃迟到记录，失败可重试，不把失败当成空数据', async () => {
    let resolve!: (value: Awaited<ReturnType<typeof fetchSystemAiActivity>>) => void
    vi.mocked(fetchSystemAiActivity).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const { wrapper, router } = await render()
    await button(wrapper, '运行记录').trigger('click')
    vi.mocked(fetchSystemAiActivity).mockRejectedValueOnce(new Error('服务不可用'))
    await router.replace('/capabilities?area=system-ai&feature=discovery')
    await flushPromises()
    resolve({ summary: '旧功能记录', items: [], note: '' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧功能记录')
    expect(wrapper.text()).toContain('记录读取失败')
    await button(wrapper, '重试').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('合成记录')
    wrapper.unmount()
  })
})
