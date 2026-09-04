import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import CodexMembershipPanel from '@/components/dashboard/CodexMembershipPanel.vue'
const api = vi.hoisted(() => ({ fetchCodexMemberships: vi.fn(), saveCodexMembership: vi.fn() }))
vi.mock('@/api/codexMemberships', () => api)
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ currentUser: { id: 'wg', role: '超级管理员' } }) }))
const entry = () => ({ account: { key: 'account', accountEmail: 'member@example.com', plan: 'pro', fresh: true, lastSyncedAt: '2026-09-04', resetCredits: { availableCount: 0, credits: [] } }, membership: { accountKey: 'account', purchasedAt: null, proExpiresAt: null, revision: '' } })
describe('Codex 会员信息', () => {
  beforeEach(() => { localStorage.clear(); vi.clearAllMocks(); api.fetchCodexMemberships.mockResolvedValue([entry()]) })
  it('明确展示真实零张，旧 Runner 缺字段显示尚未同步', async () => {
    const wrapper = mount(CodexMembershipPanel); await flushPromises()
    expect(wrapper.text()).toContain('可用重置卡：0 张')
    api.fetchCodexMemberships.mockResolvedValue([{ ...entry(), account: { ...entry().account, resetCredits: null } }])
    await wrapper.get('header button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('尚未同步')
  })
  it('刷新保留草稿，失败可重试，保存后才清理本机草稿', async () => {
    const wrapper = mount(CodexMembershipPanel); await flushPromises()
    await wrapper.findAll('input')[1]!.setValue('2026-12-01')
    await wrapper.get('header button').trigger('click'); await flushPromises()
    expect((wrapper.findAll('input')[1]!.element as HTMLInputElement).value).toBe('2026-12-01')
    api.saveCodexMembership.mockRejectedValueOnce(new Error('网络超时')).mockResolvedValueOnce({ accountKey: 'account', purchasedAt: null, proExpiresAt: '2026-12-01', revision: 'v1' })
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('网络超时')
    expect(localStorage.getItem('wao-codex-membership-draft-v1:wg:account')).toContain('2026-12-01')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('已保存到 WAO')
    expect(localStorage.getItem('wao-codex-membership-draft-v1:wg:account')).toBeNull()
  })
})
