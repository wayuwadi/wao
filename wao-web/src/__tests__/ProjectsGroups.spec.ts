import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectsView from '@/views/ProjectsView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useProjectListLayoutStore } from '@/stores/projectListLayout'
import { seedDataStore } from './helpers/seedDataStore'
import { fetchPreference, savePreference } from '@/api/favorites'

enableAutoUnmount(afterEach)
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(async () => ''), savePreference: vi.fn(async () => {}) }))
beforeEach(() => {
  localStorage.clear()
  vi.mocked(fetchPreference).mockResolvedValue('')
  vi.mocked(savePreference).mockReset().mockResolvedValue(undefined)
  setActivePinia(createPinia())
  const data = seedDataStore()
  data.projects = [{ ...data.projects[0]!, area: 'career' }]
  useSessionStore().account = { accountId: 'wg', member: { id: 'wg', name: '甲' } } as never
})
it('点击分组名可改名，保存失败保留草稿，重试后刷新恢复', async () => {
  const wrapper = await view()
  const layout = useProjectListLayoutStore()
  vi.mocked(savePreference).mockRejectedValueOnce(new Error('网络不可用'))
  await wrapper.get('[aria-label="修改分组名称：事业"]').trigger('click')
  await wrapper.get('#project-group-name').setValue('近期重点')
  await wrapper.get('.group-dialog').trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('网络不可用')
  expect(localStorage.getItem('wao-project-layout-v1:wg')).toContain('近期重点')
  await layout.retry(useDataStore().areas, useDataStore().projects)
  const saved = vi.mocked(savePreference).mock.calls[vi.mocked(savePreference).mock.calls.length - 1]![1]
  vi.mocked(fetchPreference).mockResolvedValue(saved)
  await layout.load(useDataStore().areas, useDataStore().projects, true)
  expect(wrapper.find('[aria-label="修改分组名称：近期重点"]').exists()).toBe(true)
  expect(useDataStore().areas.find(a => a.id === 'career')!.name).toBe('事业')
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
