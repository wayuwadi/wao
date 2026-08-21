import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import SkillMarketView from '@/views/SkillMarketView.vue'

const { fetchSkillCategories, fetchSkills } = vi.hoisted(() => ({
  fetchSkillCategories: vi.fn<() => Promise<unknown[]>>(),
  fetchSkills: vi.fn<() => Promise<Record<string, unknown>>>(),
}))
vi.mock('@/api/skills', () => ({ fetchSkillCategories, fetchSkills }))

async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/skills', component: SkillMarketView }, { path: '/skills/:handle/:slug', component: { template: '<div />' } },
    { path: '/skills/publish', component: { template: '<div />' } },
  ] })
  await router.push('/skills'); await router.isReady()
  const wrapper = mount(SkillMarketView, { global: { plugins: [router] } }); await flushPromises()
  return wrapper
}

describe('Skill 市场公开列表', () => {
  beforeEach(() => { fetchSkillCategories.mockReset(); fetchSkills.mockReset(); fetchSkillCategories.mockResolvedValue([{ key: 'office-efficiency', name: '办公效率' }]) })

  it('游客能看到版本、作者、兼容性和公开统计', async () => {
    fetchSkills.mockResolvedValue({ total: 1, page: 1, pageSize: 48, items: [{ id: 'skill-1', slug: 'meeting-helper', displayName: '会议助手', summary: '自动沉淀会议结论', category: 'office-efficiency', tags: [], status: 'PUBLISHED', downloads: 12, favorites: 3, updatedAt: '2026-08-21', publisher: { handle: 'wao-author', displayName: 'WAO 作者' }, version: '1.2.0', compatiblePlatforms: ['codex', 'claude-code'] }] })
    const wrapper = await render()
    expect(wrapper.text()).toContain('会议助手')
    expect(wrapper.text()).toContain('v1.2.0')
    expect(wrapper.text()).toContain('@wao-author')
    expect(wrapper.text()).toContain('Codex')
    expect(wrapper.get('.skill-card').attributes('href')).toBe('/skills/@wao-author/meeting-helper')
  })

  it('空列表有明确的恢复引导', async () => {
    fetchSkills.mockResolvedValue({ total: 0, page: 1, pageSize: 48, items: [] })
    const wrapper = await render()
    expect(wrapper.text()).toContain('还没有匹配的 Skill')
    expect(wrapper.text()).toContain('清空筛选')
  })
})
