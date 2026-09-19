import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CodexBenefitsPanel from '@/components/ai/CodexBenefitsPanel.vue'
import { useSessionStore } from '@/stores/session'
import { fetchPreference, savePreference } from '@/api/favorites'
import { benefitDaysLeft, validBenefitDate } from '@/api/codexBenefits'

vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))

const wrappers: ReturnType<typeof mount>[] = []
function setup() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSessionStore()
  session.$patch({ account: { member: { id: 'wg', name: '娃哥' } } } as never)
  const wrapper = mount(CodexBenefitsPanel, { global: { plugins: [pinia] } })
  wrappers.push(wrapper)
  return { wrapper, session }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date(2026, 8, 8, 12))
  localStorage.clear()
  vi.mocked(fetchPreference).mockReset().mockResolvedValue('')
  vi.mocked(savePreference).mockReset().mockResolvedValue()
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.useRealTimers() })

describe('首页 Codex 权益', () => {
  it('向折叠容器传递真实到期、编辑和失败状态', async () => {
    vi.mocked(fetchPreference).mockResolvedValueOnce(JSON.stringify({ resetCards: 1, proExpiresOn: '2026-09-09' }))
    const { wrapper } = setup()
    await flushPromises()
    expect(wrapper.emitted('attention')?.slice(-1)[0]).toEqual(['Pro 剩 1 天（手工记录）'])
    await wrapper.get('[aria-label="编辑 Codex 权益"]').trigger('click')
    expect(wrapper.emitted('attention')?.slice(-1)[0]).toEqual(['权益有未完成编辑'])
    vi.mocked(savePreference).mockRejectedValueOnce(new Error('保存失败'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('attention')?.slice(-1)[0]).toEqual(['权益记录需处理'])
  })

  it('保存到成员偏好并显示数量、完整日期和倒计时，零张不当作未设置', async () => {
    const { wrapper } = setup()
    await flushPromises()
    expect(wrapper.text()).toContain('未设置')
    await wrapper.get('[aria-label="编辑 Codex 权益"]').trigger('click')
    await wrapper.get('[aria-label="重置卡数量"]').setValue('3')
    await wrapper.get('[aria-label="Pro 到期日期"]').setValue('2026-09-09')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(savePreference).toHaveBeenCalledWith('codex-benefits-v1', JSON.stringify({ resetCards: 3, proExpiresOn: '2026-09-09' }))
    expect(wrapper.text()).toContain('3 张')
    expect(wrapper.text()).toContain('2026/09/09')
    expect(wrapper.text()).toContain('剩 1 天')
    expect(wrapper.text()).toContain('已保存')
    expect(localStorage.getItem('wao:codex-benefits-draft:wg')).toBeNull()
    vi.mocked(fetchPreference).mockResolvedValue(JSON.stringify({ resetCards: 3, proExpiresOn: '2026-09-09' }))
    await vi.advanceTimersByTimeAsync(86_400_000)
    expect(wrapper.text()).toContain('今天到期')
    await wrapper.get('[aria-label="编辑 Codex 权益"]').trigger('click')
    await wrapper.get('[aria-label="重置卡数量"]').setValue('0')
    await wrapper.get('[aria-label="Pro 到期日期"]').setValue('')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('0 张')
    expect(wrapper.text()).toContain('到期日未设置')
  })

  it('官方数量变化自动展示，后台刷新不会覆盖打开的日期草稿', async () => {
    const { wrapper } = setup()
    await flushPromises()
    const account = { key: 'account-a', accountEmail: '验收账号', plan: 'pro', credits: null, limits: [], runnerNames: [], lastSyncedAt: '2026-09-11T10:00:00', fresh: true, resetCredits: { availableCount: 2, credits: [] } }
    await wrapper.setProps({ accounts: [account] })
    expect(wrapper.text()).toContain('2 张')
    await wrapper.get('[aria-label="编辑 Codex 权益"]').trigger('click')
    await wrapper.get('[aria-label="Pro 到期日期"]').setValue('2026-10-01')
    await wrapper.setProps({ accounts: [{ ...account, resetCredits: { availableCount: 1, credits: [] } }] })
    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.text()).toContain('1 张')
    expect(wrapper.get('[aria-label="Pro 到期日期"]').element).toHaveProperty('value', '2026-10-01')
  })

  it('保存失败保留旧展示与草稿，刷新后可恢复并重试', async () => {
    vi.mocked(fetchPreference).mockResolvedValue(JSON.stringify({ resetCards: 3, proExpiresOn: '2026-09-09' }))
    vi.mocked(savePreference).mockRejectedValueOnce(new Error('网络暂不可用'))
    const { wrapper } = setup()
    await flushPromises()
    await wrapper.get('[aria-label="编辑 Codex 权益"]').trigger('click')
    await wrapper.get('[aria-label="重置卡数量"]').setValue('2')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('3 张')
    expect(wrapper.text()).toContain('网络暂不可用')
    wrapper.unmount()
    const { wrapper: restored } = setup()
    await flushPromises()
    await restored.get('[aria-label="编辑 Codex 权益"]').trigger('click')
    expect(restored.text()).toContain('已恢复未保存草稿')
    expect((restored.get('input[type="number"]').element as HTMLInputElement).value).toBe('2')
    await restored.get('form').trigger('submit')
    await flushPromises()
    expect(restored.text()).toContain('2 张')
  })

  it('加载失败提供重试，非法数量不能保存，成员切换不复用权益或草稿', async () => {
    vi.mocked(fetchPreference).mockRejectedValueOnce(new Error('读取失败'))
    const { wrapper, session } = setup()
    await flushPromises()
    expect(wrapper.text()).toContain('读取失败')
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    await wrapper.get('[aria-label="编辑 Codex 权益"]').trigger('click')
    await wrapper.get('input[type="number"]').setValue('-1')
    await wrapper.get('form').trigger('submit')
    expect(savePreference).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('0 到 9999')
    session.$patch({ account: { member: { id: 'wd' } } } as never)
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    await wrapper.get('[aria-label="编辑 Codex 权益"]').trigger('click')
    expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('')
  })

  it('保存中防重复提交，旧成员的迟到响应不污染新成员', async () => {
    let finish!: () => void
    vi.mocked(savePreference).mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    const { wrapper, session } = setup()
    await flushPromises()
    await wrapper.get('[aria-label="编辑 Codex 权益"]').trigger('click')
    await wrapper.get('input[type="number"]').setValue('3')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('保存中')
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(savePreference).toHaveBeenCalledTimes(1)
    session.$patch({ account: { member: { id: 'wd' } } } as never)
    await flushPromises()
    finish()
    await flushPromises()
    expect(wrapper.text()).not.toContain('3 张')
    expect(wrapper.text()).not.toContain('已保存')
  })

  it('日期采用日历天，覆盖过期、闰年与无效日期', () => {
    expect(benefitDaysLeft('2026-09-09', new Date(2026, 8, 8, 23, 59))).toBe(1)
    expect(benefitDaysLeft('2026-09-09', new Date(2026, 8, 10))).toBe(-1)
    expect(validBenefitDate('2026-02-29')).toBe(false)
    expect(validBenefitDate('2028-02-29')).toBe(true)
    expect(validBenefitDate('2026-13-01')).toBe(false)
  })
})
