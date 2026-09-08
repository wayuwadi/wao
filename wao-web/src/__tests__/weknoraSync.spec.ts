import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import WeKnoraSyncPanel from '@/components/weknora/WeKnoraSyncPanel.vue'
import { getSyncOverview, getSyncProgress, saveSyncSettings, runSync } from '@/api/weknoraSync'
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
vi.mock('@/api/weknoraSync', () => ({
  getSyncOverview: vi.fn(),
  getSyncProgress: vi.fn(),
  saveSyncSettings: vi.fn(),
  runSync: vi.fn(),
  getSyncSource: vi.fn(),
}))
const initial = () => ({
  ownerId: 'owner',
  configured: true,
  settings: {
    kbId: '8f7e78c1-4933-4dec-bce3-f35dd8f4e698',
    modules: ['docs'],
    batchLimit: 20,
    syncDeletes: true,
    enabled: true,
    time: '02:00',
    revision: 1,
    hasKey: true,
  },
  catalog: [{ id: 'docs', label: '知识库文档', scope: '本人', count: 41 }],
  runs: [],
  items: [],
  totals: { tracked: 0, transferred: 0, failed: 0 },
})
const wrappers: ReturnType<typeof mount>[] = []
beforeEach(() => {
  localStorage.clear()
  vi.mocked(getSyncOverview).mockResolvedValue(initial())
  vi.mocked(getSyncProgress).mockResolvedValue({ runs: [], items: [] })
})
afterEach(() => {
  wrappers.forEach((w) => w.unmount())
  wrappers.length = 0
  vi.resetAllMocks()
})
function view() {
  const w = mount(WeKnoraSyncPanel)
  wrappers.push(w)
  return w
}
describe('WeKnora 内容同步', () => {
  it('保留失败保存的草稿，且不将 API Key 写入浏览器存储', async () => {
    const w = view()
    await flushPromises()
    await w.get('input[type=time]').setValue('03:15')
    await w.get('input[type=password]').setValue('secret-not-to-store')
    vi.mocked(saveSyncSettings).mockRejectedValue(new Error('保存失败'))
    await w.get('.save-row button').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('保存失败')
    expect((w.get('input[type=time]').element as HTMLInputElement).value).toBe('03:15')
    const draft = localStorage.getItem('wao-weknora-sync-draft-v1:owner')
    expect(draft).toContain('03:15')
    expect(draft).not.toContain('secret-not-to-store')
  })
  it('展示真实来源数并触发持久化队列', async () => {
    const w = view()
    await flushPromises()
    expect(w.text()).toContain('41')
    expect(w.text()).toContain('尚未运行')
    vi.mocked(runSync).mockResolvedValue({ id: 'actual-run' })
    await w.get('.sync-header button').trigger('click')
    await flushPromises()
    expect(runSync).toHaveBeenCalledOnce()
    expect(w.text()).toContain('同步已入队')
  })
  it('读取失败提供可恢复状态而不是模拟数据', async () => {
    vi.mocked(getSyncOverview).mockRejectedValue(new Error('只有管理员可以维护知识同步'))
    const w = view()
    await flushPromises()
    expect(w.text()).toContain('只有管理员')
    expect(w.find('.stats').exists()).toBe(false)
    expect(w.text()).toContain('重新加载')
  })
})
