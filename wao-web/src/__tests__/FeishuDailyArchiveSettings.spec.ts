import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import {
  fetchFeishuDailyArchive,
  saveFeishuDailyArchive,
  type FeishuDailyArchive,
} from '@/api/system'
import FeishuDailyArchiveSettings from '@/components/capabilities/FeishuDailyArchiveSettings.vue'
vi.mock('@/api/system', () => ({
  fetchFeishuDailyArchive: vi.fn(),
  saveFeishuDailyArchive: vi.fn(),
}))
const state: FeishuDailyArchive = {
  enabled: false,
  configured: true,
  timezone: 'Asia/Shanghai',
  schedule: '0 0 0 * * *',
  nextRunAt: null,
  appId: 'app',
  chatId: 'chat',
  chatName: '真实群',
  kbId: 'kb',
  latestRun: null,
}
let wrapper: VueWrapper
beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  setActivePinia(createPinia())
  useSessionStore().accept({
    accountId: 'wg',
    member: { id: 'wg', name: '娃哥', role: '管理员', initial: 'W', color: '#fff' },
    identities: [],
    createdAt: '',
  })
  vi.mocked(fetchFeishuDailyArchive).mockResolvedValue(structuredClone(state))
  vi.mocked(saveFeishuDailyArchive).mockResolvedValue({
    ...state,
    enabled: true,
    nextRunAt: '2026-10-08T00:00:00',
  })
})
afterEach(() => wrapper?.unmount())
async function render() {
  wrapper = mount(FeishuDailyArchiveSettings)
  await flushPromises()
}
describe('每日飞书归档', () => {
  it('读取失败不变成空配置，重试后使用真实群', async () => {
    vi.mocked(fetchFeishuDailyArchive).mockRejectedValueOnce(new Error('读取失败'))
    await render()
    expect(wrapper.find('[role=switch]').exists()).toBe(false)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('真实群')
    expect(saveFeishuDailyArchive).not.toHaveBeenCalled()
  })
  it('保存仅切换归档，携带旧状态，下一次午夜生效', async () => {
    await render()
    await wrapper.get('[role=switch]').trigger('click')
    await wrapper.get('.save').trigger('click')
    await flushPromises()
    expect(saveFeishuDailyArchive).toHaveBeenCalledWith({ enabled: true, expectedEnabled: false })
    expect(wrapper.text()).toContain('2026-10-08T00:00:00')
    expect(wrapper.text()).toContain('不补扫历史、不同步表情')
  })
  it('冲突保留草稿，用户重新读取后才能基于最新状态保存', async () => {
    await render()
    await wrapper.get('[role=switch]').trigger('click')
    vi.mocked(saveFeishuDailyArchive).mockRejectedValue(new Error('配置已有更新'))
    await wrapper.get('.save').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role=switch]').attributes('aria-checked')).toBe('true')
    expect(localStorage.getItem('wao-feishu-daily-archive-draft-v1:wg')).toContain('true')
    expect(wrapper.text()).toContain('配置已有更新')
  })
  it('缺少归档知识库时禁止启用，失败显示当天不重试', async () => {
    vi.mocked(fetchFeishuDailyArchive).mockResolvedValue({
      ...state,
      configured: false,
      kbId: '',
      latestRun: {
        archiveDate: '2026-10-06',
        status: 'FAILED',
        runAt: '2026-10-07T00:00:00',
        durationMs: 100,
        errorCode: 'ARCHIVE_FAILED',
        docId: null,
        messageCount: null,
      },
    })
    await render()
    await wrapper.get('[role=switch]').trigger('click')
    expect(wrapper.get('.save').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('失败，当天不自动重试')
  })
})
