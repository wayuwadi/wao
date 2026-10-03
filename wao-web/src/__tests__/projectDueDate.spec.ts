import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectsView from '@/views/ProjectsView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useProjectListLayoutStore } from '@/stores/projectListLayout'
import { seedDataStore } from './helpers/seedDataStore'
vi.mock('@/api/favorites', async original => ({ ...await original<typeof import('@/api/favorites')>(), fetchPreference: vi.fn().mockResolvedValue('[]'), savePreference: vi.fn().mockResolvedValue(undefined) }))
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); seedDataStore(); useSessionStore().account = { member: { id: 'wg' } } as never })
it('空串与历史 null 均未排期，按日期排序置底且不改历史日期', async () => {
  const data = useDataStore(), source = data.projects[0]!
  data.projects = [
    { ...source, id: 'empty', name: '空串', due: '' },
    { ...source, id: 'null', name: '历史空值', due: null },
    { ...source, id: 'late', name: '晚日期', due: '2027-01-15' },
    { ...source, id: 'early', name: '早日期', due: '2026-12-03' },
  ]
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects', component: ProjectsView }] })
  await router.push('/projects')
  const wrapper = mount(ProjectsView, { global: { plugins: [router] } })
  await flushPromises(); useProjectListLayoutStore().setGrouping('不分组'); await flushPromises()
  expect(wrapper.findAll('.pc-name').map(item => item.text())).toEqual(['早日期', '晚日期', '空串', '历史空值'])
  expect(wrapper.findAll('.proj-card').filter(card => card.text().includes('未排期'))).toHaveLength(2)
  expect(data.projectById('early')!.due).toBe('2026-12-03')
  wrapper.unmount()
})
