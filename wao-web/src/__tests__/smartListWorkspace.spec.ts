import { beforeEach, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TodoView from '@/views/TodoView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useSmartListVisibility } from '@/stores/smartListVisibility'
import { seedDataStore } from './helpers/seedDataStore'
import { shiftTaskDate } from '@/utils/taskWorkspaceFilter'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))
vi.mock('@/api/smartListVisibility', () => ({ fetchSmartListVisibility: vi.fn(async () => ({})), saveSmartListVisibility: vi.fn(async () => {}) }))
vi.mock('@/api/favorites', async original => ({ ...await original<typeof import('@/api/favorites')>(), fetchPreference: vi.fn(async () => ''), savePreference: vi.fn(async () => {}) }))
vi.mock('@/api/taskLabels', () => ({ fetchTaskLabels: vi.fn(async () => []), saveTaskLabels: vi.fn() }))

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia())
  useSessionStore().accept({ accountId: 'test', member: { id: 'wg', name: '成员', initial: '测', role: '', color: '' }, identities: [], createdAt: '' })
})

it('隐藏清单仍提示任务保存失败，并能打开清单重试', async () => {
  const data = seedDataStore(), base = data.tasks[0]!, project = data.projects[0]!
  data.tasks = [{ ...base, id: 'unsaved', project: project.id, title: '待重试任务', owner: 'wg', due: data.today, status: '待办', subs: [] }]
  const state = ref<'saving' | 'error'>('saving')
  vi.spyOn(data, 'taskSaveState').mockImplementation(id => id === 'unsaved' ? state.value : 'saved')
  const visibility = useSmartListVisibility(); await flushPromises(); await visibility.setVisible(project.id, false)
  const wrapper = mount(TodoView, { global: { stubs: { Teleport: true, MarkdownEditor: true, TaskDocuments: true, TaskDueDate: true, RouterLink: true } } })
  await flushPromises()
  expect(wrapper.text()).toContain('正在保存或保存失败')
  expect(wrapper.find('[data-task-id="unsaved"]').exists()).toBe(false)
  state.value = 'error'; await flushPromises()
  await wrapper.findAll('button').find(button => button.text().includes(`打开${project.name}`))!.trigger('click'); await flushPromises()
  expect(wrapper.find('[aria-label="重试保存 待重试任务"]').exists()).toBe(true)
  wrapper.unmount()
})

it('四个智能清单及搜索隐藏任务和子任务，单独清单仍可访问', async () => {
  const data = seedDataStore(), base = data.tasks[0]!, project = data.projects[0]!
  data.tasks = [
    { ...base, id: 'hidden-today', project: project.id, title: '隐藏今天', owner: 'wg', due: data.today, status: '待办', subs: [{ id: 'child', text: '隐藏子任务', done: false, due: data.today }] },
    { ...base, id: 'hidden-tomorrow', project: project.id, title: '隐藏明天', owner: 'wg', due: shiftTaskDate(data.today, 1), status: '待办', subs: [] },
    { ...base, id: 'visible', project: '', title: '收集箱任务', owner: 'wg', due: data.today, status: '待办', subs: [] },
  ]
  const visibility = useSmartListVisibility(); await flushPromises(); await visibility.setVisible(project.id, false)
  const wrapper = mount(TodoView, { global: { stubs: { Teleport: true, MarkdownEditor: true, TaskDocuments: true, TaskDueDate: true } } })
  await flushPromises()
  const text = () => wrapper.find('.workspace-main').text()
  expect(text()).not.toContain('隐藏今天'); expect(text()).toContain('收集箱任务')
  await wrapper.get('[aria-label="搜索任务"]').setValue('隐藏'); await flushPromises()
  expect(wrapper.find('[data-task-id="hidden-today"]').exists()).toBe(false)
  await wrapper.get('[aria-label="搜索任务"]').setValue('')
  await wrapper.get('[aria-label="更多视图选项"]').trigger('click')
  await wrapper.findAll('[role="menuitemradio"]').find(button => button.text().trim() === '四象限')!.trigger('click'); await flushPromises()
  expect(text()).not.toContain('隐藏今天'); expect(text()).not.toContain('隐藏子任务')
  await wrapper.get('[aria-label="更多视图选项"]').trigger('click')
  await wrapper.findAll('[role="menuitemradio"]').find(button => button.text().trim() === '列表视图')!.trigger('click'); await flushPromises()
  for (const name of ['今天', '明天', '最近 7 天']) {
    await wrapper.findAll('.task-navigation button').find(b => b.text().trim() === name)!.trigger('click')
    await flushPromises()
    expect(text()).not.toContain('隐藏今天'); expect(text()).not.toContain('隐藏明天'); expect(text()).not.toContain('隐藏子任务')
  }
  await wrapper.findAll('.list-entry button').find(b => b.text().trim() === project.name)!.trigger('click'); await flushPromises()
  expect(text()).toContain('隐藏今天'); expect(text()).toContain('隐藏子任务')
  await visibility.setVisible(project.id, true)
  await wrapper.findAll('.task-navigation button').find(b => b.text().trim() === '所有')!.trigger('click'); await flushPromises()
  expect(text()).toContain('隐藏今天')
  wrapper.unmount()
})
