import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectsView from '@/views/ProjectsView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useProjectListLayoutStore } from '@/stores/projectListLayout'
import { seedDataStore } from './helpers/seedDataStore'

enableAutoUnmount(afterEach)
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(async () => ''), savePreference: vi.fn(async () => {}) }))
beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  const data = seedDataStore()
  data.projects = [{ ...data.projects[0]!, area: 'career' }]
  useSessionStore().account = { accountId: 'wg', member: { id: 'wg', name: '甲' } } as never
})
async function view() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects', component: ProjectsView }] })
  await router.push('/projects')
  const wrapper = mount(ProjectsView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}
it('隐藏空分组但保留移动目标，搜索和分页不留下空标题', async () => {
  const wrapper = await view()
  const layout = useProjectListLayoutStore()
  expect(wrapper.findAll('.group-head')).toHaveLength(1)
  expect(layout.state.groups.length).toBeGreaterThan(1)
  await wrapper.get('.search input').setValue('不存在')
  expect(wrapper.findAll('.group-head')).toHaveLength(0)
  await wrapper.get('.search input').setValue('')
  const data = useDataStore()
  data.projects = Array.from({ length: 11 }, (_, i) => ({ ...data.projects[0]!, id: `page-${i}`, area: i === 10 ? 'family' : 'career' }))
  await flushPromises()
  expect(wrapper.findAll('.group-head')).toHaveLength(1)
  expect(layout.state.groups.some(g => g.projectIds.includes('page-10'))).toBe(true)
  layout.moveProject('page-0', 'family')
  await flushPromises()
  expect(wrapper.findAll('.group-head')).toHaveLength(2)
})
