import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'

vi.mock('@/api/ai', () => ({ createAiRuns: vi.fn().mockResolvedValue([]) }))

describe('项目动态分页', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore(useDataStore())
  })

  it('只展示当前 10 条，翻页后可看到末尾记录', async () => {
    const data = useDataStore()
    data.projects[0]!.activity = Array.from({ length: 23 }, (_, index) => ({
      icon: 'ri-add-line', color: '#333', text: `动态 ${index + 1}`, time: '2026-09-24T10:00:00', by: 'wg',
    }))
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/projects/:id', component: { template: '<div />' } },
    ] })
    await router.push('/projects/p1?tab=动态')
    await router.isReady()
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.findAll('.feed-row')).toHaveLength(10)
    expect(wrapper.get('.feed-row').text()).toContain('动态 1')
    await wrapper.get('button[aria-label="第 3 页"]').trigger('click')
    expect(wrapper.findAll('.feed-row')).toHaveLength(3)
    expect(wrapper.get('.feed-row').text()).toContain('动态 21')
    wrapper.unmount()
  })
})
