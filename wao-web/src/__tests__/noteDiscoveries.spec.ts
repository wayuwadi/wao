import { defineComponent, nextTick, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NoteDiscoveryInline from '@/components/notes/NoteDiscoveryInline.vue'
import NoteDiscoveryPreference from '@/components/notes/NoteDiscoveryPreference.vue'
import NoteDiscoverySavedList from '@/components/notes/NoteDiscoverySavedList.vue'
import { useNoteDiscoveries } from '@/composables/useNoteDiscoveries'
import { useSessionStore } from '@/stores/session'
import * as api from '@/api/noteDiscoveries'
vi.mock('@/api/noteDiscoveries', () => ({ feedbackDiscovery: vi.fn(), fetchDiscovery: vi.fn(), fetchDiscoveryCurrent: vi.fn(), fetchDiscoveryPreference: vi.fn(), saveDiscoveryPreference: vi.fn(), fetchDiscoveryPage: vi.fn(), discoveryHttpStatus: (error: { status?: number; response?: { status?: number } }) => error?.status ?? error?.response?.status }))
// 明确合成来源，仅验证交互状态，不代表实际 AI 质量。
const item = (): api.Discovery => ({ id: 'synthetic-a', origin: 'AUTO', headline: '同一批客户名称又核对了一遍', observation: '两篇涉及同一批名称', valueReason: '可以复用已确认的对照材料', restricted: false,
  evidence: [{ docId: 'synthetic-doc', docRev: 1, title: '合成原文', quote: '同一批客户名称' }], feedback: { version: 0, saved: false, dismissed: false, correction: null } })
beforeEach(() => {
  setActivePinia(createPinia()); vi.clearAllMocks()
  vi.mocked(api.fetchDiscoveryCurrent).mockResolvedValue({ discovery: null, automationState: 'OFF', lastOutcome: null, revision: '' })
  vi.mocked(api.fetchDiscoveryPreference).mockResolvedValue({ enabled: false, available: true, version: 0, message: '读取本人小记，可随时暂停' })
  vi.mocked(api.fetchDiscoveryPage).mockResolvedValue({ items: [], nextCursor: null })
})
describe('顺手发现原地阅读与独立反馈', () => {
  it('默认收起时直接显示具体结论，展开才展示原文和操作', async () => {
    const w = mount(NoteDiscoveryInline, { props: { item: item() } })
    expect(w.text()).toBe('同一批客户名称又核对了一遍'); expect(w.find('blockquote').exists()).toBe(false)
    await w.get('.discovery-heading').trigger('click'); expect(w.get('blockquote').text()).toBe('同一批客户名称')
    expect(w.find('form').exists()).toBe(false); expect(w.text()).not.toContain('可以试试：')
    await w.get('.discovery-evidence button').trigger('click'); expect(w.emitted('source')?.[0]?.slice(0, 2)).toEqual(['synthetic-a', 'synthetic-doc']); w.unmount()
  })
  it('收藏与不贴切发出独立赋值，不要求反馈原因', async () => {
    const value = item(); vi.mocked(api.feedbackDiscovery).mockImplementation(async (_item, patch) => ({ ...value, feedback: { ...value.feedback, ...patch, version: 1 } }))
    const w = mount(NoteDiscoveryInline, { props: { item: value } }); await w.get('.discovery-heading').trigger('click')
    await w.get('.discovery-actions button').trigger('click'); await flushPromises()
    expect(api.feedbackDiscovery).toHaveBeenCalledWith(value, { saved: true })
    await w.findAll('.discovery-actions button')[1]!.trigger('click'); await flushPromises()
    expect(api.feedbackDiscovery).toHaveBeenLastCalledWith(value, { dismissed: true }); expect(w.find('textarea').exists()).toBe(false); w.unmount()
  })
  it('反馈失败或409保留补充输入，允许按最新版本重试', async () => {
    vi.mocked(api.feedbackDiscovery).mockRejectedValue({ status: 409 })
    vi.mocked(api.fetchDiscovery).mockResolvedValue({ ...item(), feedback: { ...item().feedback, version: 3 } })
    const w = mount(NoteDiscoveryInline, { props: { item: item() } }); await w.get('.discovery-heading').trigger('click')
    await w.findAll('.discovery-actions button')[2]!.trigger('click'); await w.get('textarea').setValue('真实对象需要补充')
    await w.get('form').trigger('submit'); await flushPromises()
    expect((w.get('textarea').element as HTMLTextAreaElement).value).toBe('真实对象需要补充'); expect(w.get('[role="alert"]').text()).toContain('最新状态'); expect(w.emitted('changed')?.[0]?.[0]).toMatchObject({ feedback: { version: 3 } }); w.unmount()
  })
  it('来源受限时收藏也不能暴露结论、摘录或旧图片', async () => {
    const w = mount(NoteDiscoveryInline, { props: { item: { ...item(), restricted: true, headline: '来源已不可访问', imageUrl: '/private-image' } } })
    await w.get('.discovery-heading').trigger('click'); expect(w.find('blockquote').exists()).toBe(false); expect(w.find('img').exists()).toBe(false); w.unmount()
  })
  it('历史只读列表有空态，取消收藏后从收藏列表移除', async () => {
    vi.mocked(api.fetchDiscoveryPage).mockResolvedValue({ items: [{ ...item(), feedback: { ...item().feedback, saved: true } }], nextCursor: null })
    const w = mount(NoteDiscoverySavedList, { props: { mode: 'saved', memberId: 'synthetic-member' } }); await flushPromises()
    w.getComponent(NoteDiscoveryInline).vm.$emit('changed', item()); await nextTick(); expect(w.findComponent(NoteDiscoveryInline).exists()).toBe(false); expect(w.text()).toContain('还没有收藏'); w.unmount()
  })
})
describe('发现设置不丢草稿', () => {
  it('未开启的成员明确勾选并保存，刷新读回服务器值', async () => {
    vi.mocked(api.saveDiscoveryPreference).mockResolvedValue({ enabled: true, available: true, version: 1, message: '' })
    const w = mount(NoteDiscoveryPreference); await flushPromises(); expect((w.get('input').element as HTMLInputElement).checked).toBe(false)
    await w.get('input').setValue(true); await w.find('button').trigger('click'); await flushPromises()
    expect(api.saveDiscoveryPreference).toHaveBeenCalledWith(true, 0); expect(w.text()).toContain('设置已保存'); w.unmount()
  })
  it('保存失败保留选择和重试入口', async () => {
    vi.mocked(api.saveDiscoveryPreference).mockRejectedValue(new Error('暂不可用'))
    const w = mount(NoteDiscoveryPreference); await flushPromises(); await w.get('input').setValue(true); await w.find('button').trigger('click'); await flushPromises()
    expect((w.get('input').element as HTMLInputElement).checked).toBe(true); expect(w.get('[role="alert"]').text()).toContain('选择已保留'); w.unmount()
  })
  it('跨窗口冲突读新版本，保留本人的开启草稿', async () => {
    vi.mocked(api.saveDiscoveryPreference).mockRejectedValue({ response: { status: 409 } })
    const w = mount(NoteDiscoveryPreference); await flushPromises(); await w.get('input').setValue(true)
    vi.mocked(api.fetchDiscoveryPreference).mockResolvedValue({ enabled: false, available: true, version: 2, message: '' })
    await w.find('button').trigger('click'); await flushPromises(); expect((w.get('input').element as HTMLInputElement).checked).toBe(true); expect(w.text()).toContain('其他窗口更新'); w.unmount()
  })
  it('切换成员不会展示上一成员的迟到设置', async () => {
    let resolve!: (value: api.DiscoveryPreference) => void
    vi.mocked(api.fetchDiscoveryPreference).mockReturnValueOnce(new Promise(done => { resolve = done }))
    const w = mount(NoteDiscoveryPreference); useSessionStore().accept({ accountId: 'synthetic-account', member: { id: 'synthetic-second-member', name: '合成成员', initial: '合', color: '#999', role: '' }, identities: [], createdAt: '' }); await flushPromises()
    resolve({ enabled: true, available: true, version: 5, message: '' }); await flushPromises()
    expect((w.get('input').element as HTMLInputElement).checked).toBe(false); w.unmount()
  })
})
describe('后台刷新与写作分离', () => {
  it('忙于写作时暂存发现，回到安全位置才插入；旧权限失效立即移除', async () => {
    let state!: ReturnType<typeof useNoteDiscoveries>; const safe = ref(false)
    const Host = defineComponent({ setup() { state = useNoteDiscoveries(() => 'm', () => true, () => safe.value); return () => null } })
    const w = mount(Host); await flushPromises(); vi.mocked(api.fetchDiscoveryCurrent).mockResolvedValue({ discovery: item(), automationState: 'READY', lastOutcome: 'PUBLISHED', revision: '1' })
    await state.refresh(); expect(state.current.value).toBeNull(); expect(state.pendingCurrent.value?.id).toBe('synthetic-a')
    safe.value = true; state.applyPending(); expect(state.current.value?.id).toBe('synthetic-a')
    safe.value = false; vi.mocked(api.fetchDiscoveryCurrent).mockResolvedValue({ discovery: null, automationState: 'READY', lastOutcome: 'STALE_INPUT', revision: '2' })
    await state.refresh(); expect(state.current.value).toBeNull(); w.unmount()
  })
})
