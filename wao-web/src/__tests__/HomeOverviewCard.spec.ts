import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import HomeOverviewCard from '@/components/home/HomeOverviewCard.vue'
import { useSessionStore } from '@/stores/session'
import { fetchCodexMemberships } from '@/api/codexMemberships'
import { fetchPreference, savePreference } from '@/api/favorites'
import type { CodexQuotaAccount } from '@/api/ai'

vi.mock('@/api/codexMemberships', () => ({ fetchCodexMemberships: vi.fn() }))
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
vi.mock('@/api/tianren', () => ({ fetchTodayTianrenReading: vi.fn().mockResolvedValue({ primary: { name: '巽', symbol: '☴' } }) }))
const wrappers: ReturnType<typeof mount>[] = []
const account = (key = 'a', fresh = true): CodexQuotaAccount => ({ key, accountEmail: `${key}@example.test`, plan: 'pro', credits: null, runnerNames: [],
  lastSyncedAt: '2026-10-02T12:00:00+08:00', fresh, resetCredits: { availableCount: 0, credits: [] },
  limits: [{ id: 'codex', label: '通用', windows: [{ usedPercent: 32, windowDurationMinutes: 10080, resetsAt: 1791200000 }] }],
})
function respond(accounts: CodexQuotaAccount[]) {
  vi.mocked(fetchCodexMemberships).mockResolvedValue(accounts.map(account => ({ account, membership: { accountKey: account.key, purchasedAt: null, proExpiresAt: null, revision: '' } })))
}
function setup() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSessionStore()
  session.$patch({ account: { member: { id: 'home-test' } } } as never)
  const wrapper = mount(HomeOverviewCard, { attachTo: document.body, global: { plugins: [pinia], stubs: { RouterLink: RouterLinkStub } } })
  wrappers.push(wrapper)
  return { wrapper, session }
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-02T12:00:00+08:00'))
  localStorage.clear()
  vi.mocked(fetchCodexMemberships).mockReset()
  respond([account()])
  vi.mocked(fetchPreference).mockReset().mockResolvedValue(JSON.stringify({ resetCards: 3, proExpiresOn: '2026-10-20' }))
  vi.mocked(savePreference).mockReset().mockResolvedValue()
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.useRealTimers() })

describe('首页四等分信息卡片', () => {
  it('加载真实卦象、通用周剩余、个人到期日，官方零张优先于手工值', async () => {
    const { wrapper } = setup()
    await flushPromises()
    const card = wrapper.get('.overview-card')
    expect(card.text()).toContain('巽卦')
    expect(card.text()).toContain('68%')
    expect(card.text()).toContain('2026/10/20')
    expect(card.text()).toContain('剩 18 天')
    expect(card.text()).toContain('0 张')
    expect(card.text()).not.toContain('3 张')
    expect(wrapper.get('#home-codex-details').isVisible()).toBe(false)
  })
  it('历史和多账号分别显示，不合计；刷新失败保留旧快照并可重试', async () => {
    respond([account('a', false), account('b', false)])
    const { wrapper } = setup()
    await flushPromises()
    expect(wrapper.get('.overview-card').text()).toContain('2 个账号')
    expect(wrapper.get('.overview-card').text()).toContain('历史快照')
    await wrapper.get('.overview-metric').trigger('click')
    expect(wrapper.findAll('.quota-account')).toHaveLength(2)
    vi.mocked(fetchCodexMemberships).mockRejectedValueOnce(new Error('读取失败'))
    await wrapper.get('#home-codex-details > button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('已有快照仅供参考')
    expect(wrapper.findAll('.quota-account')).toHaveLength(2)
    await wrapper.get('#home-codex-details > button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
  it('收起、外部点击和 Escape 不丢权益草稿，保存后摘要更新', async () => {
    respond([])
    const { wrapper } = setup()
    await flushPromises()
    const trigger = wrapper.get('.overview-metric')
    await trigger.trigger('click')
    await wrapper.get('[aria-label="编辑 Codex 权益"]').trigger('click')
    await wrapper.get('[aria-label="重置卡数量"]').setValue('2')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper.get('#home-codex-details').isVisible()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
    await trigger.trigger('click')
    expect(wrapper.get('input[type="number"]').element).toHaveProperty('value', '2')
    expect(wrapper.get('.overview-card').text()).toContain('3 张')
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await flushPromises()
    expect(wrapper.get('#home-codex-details').isVisible()).toBe(false)
    await trigger.trigger('click')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.overview-card').text()).toContain('2 张')
    expect(wrapper.text()).toContain('已保存')
  })
  it('无通用周窗口不猜测额度；权益读取失败不伪造零张', async () => {
    const item = account()
    item.limits[0]!.id = 'codex-model-specific'
    item.resetCredits = null
    respond([item])
    vi.mocked(fetchPreference).mockRejectedValue(new Error('权益读取失败'))
    const { wrapper } = setup()
    await flushPromises()
    expect(wrapper.get('.overview-card').text()).toContain('暂无快照')
    expect(wrapper.get('.overview-card').text()).toContain('读取失败')
    expect(wrapper.get('.overview-card').text()).not.toContain('0 张')
    expect(wrapper.get('.overview-card').text()).not.toContain('68%')
  })
  it('切换成员后不接受上个成员迟到的额度响应', async () => {
    let finish!: (value: Awaited<ReturnType<typeof fetchCodexMemberships>>) => void
    vi.mocked(fetchCodexMemberships).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const { wrapper, session } = setup()
    respond([])
    session.$patch({ account: { member: { id: 'other' } } } as never)
    await flushPromises()
    finish([{ account: account(), membership: { accountKey: 'a', purchasedAt: null, proExpiresAt: null, revision: '' } }])
    await flushPromises()
    expect(wrapper.get('.overview-card').text()).toContain('暂无快照')
    expect(wrapper.get('.overview-card').text()).not.toContain('68%')
  })
  it('旧采样即使返回 fresh 也明确标为历史快照', async () => {
    respond([{ ...account(), lastSyncedAt: '2026-10-01T12:00:00' }])
    const { wrapper } = setup()
    await flushPromises()
    expect(wrapper.get('.overview-card').text()).toContain('历史快照')
  })
  it('跨过官方重置时刻的旧周窗口不再标为最近同步', async () => {
    const item = account()
    item.limits[0]!.windows[0]!.resetsAt = Date.now() / 1000 - 1
    respond([item])
    const { wrapper } = setup()
    await flushPromises()
    expect(wrapper.get('.overview-metric').text()).toContain('历史快照')
  })
})
