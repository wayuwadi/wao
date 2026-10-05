import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NavigationGovernance from '@/components/nav/NavigationGovernance.vue'
const health = vi.hoisted(() => ({ overview: { value: null as unknown }, loading: { value: false }, error: { value: '' }, load: vi.fn() }))
vi.mock('@/composables/functionHealth', () => ({ useFunctionHealth: () => health }))
vi.mock('@/stores/nav', () => ({ useNavStore: () => ({ itemMeta: (id: string) => ({ label: id }) }) }))
beforeEach(() => {
  health.error.value = ''; health.loading.value = false; health.load.mockClear()
  health.overview.value = { recentOpens: 42, functions: [
    { featureId: 'home', recentOpens: 10 }, { featureId: 'notes', recentOpens: 20 },
    { featureId: 'ideas', recentOpens: 5 }, { featureId: 'skills', recentOpens: 7 },
  ] }
})
describe('治理数据范围', () => {
  it('在用与已退场模块分别汇总，切换不会丢历史，排序仍可用', async () => {
    const w = mount(NavigationGovernance)
    expect(w.text()).toContain('在用功能打开：30')
    expect(w.find('[data-feature-id="ideas"]').exists()).toBe(false)
    expect(w.find('tbody tr').attributes('data-feature-id')).toBe('notes')
    await w.get('.sort-trigger').trigger('click')
    expect(w.find('tbody tr').attributes('data-feature-id')).toBe('home')
    await w.get('.archive-switch button').trigger('click')
    expect(w.text()).toContain('历史模块打开：12')
    expect(w.findAll('tbody tr')).toHaveLength(2)
    expect(w.find('[data-feature-id="notes"]').exists()).toBe(false)
    await w.get('.archive-switch button').trigger('click')
    expect(w.text()).toContain('在用功能打开：30'); w.unmount()
  })
  it('读取失败不把缓存记录当作当前数据，仍可重试', async () => {
    health.error.value = '暂时不可用'
    const w = mount(NavigationGovernance)
    expect(w.find('table').exists()).toBe(false)
    expect(w.text()).toContain('在用功能打开：—')
    await w.get('[role="alert"] button').trigger('click'); await flushPromises()
    expect(health.load).toHaveBeenCalledWith(true); w.unmount()
  })
})
