import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TodoAppearance from '@/components/todo/TodoAppearance.vue'
import TodoView from '@/views/TodoView.vue'
import { TODO_PALETTES, decodeTodoPalette } from '@/utils/todoAppearance'
import { useSessionStore } from '@/stores/session'
import { fetchPreference, savePreference } from '@/api/favorites'
import { seedDataStore } from './helpers/seedDataStore'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn() }) }))
vi.mock('@/api/favorites', async original => ({ ...await original<object>(), fetchPreference: vi.fn(async () => ''), savePreference: vi.fn(async () => {}) }))
vi.mock('@/api/taskLabels', () => ({ fetchTaskLabels: vi.fn(async () => []), saveTaskLabels: vi.fn() }))
vi.mock('@/api/smartListVisibility', () => ({ fetchSmartListVisibility: vi.fn(async () => ({})), saveSmartListVisibility: vi.fn() }))
enableAutoUnmount(afterEach)
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia())
  vi.mocked(fetchPreference).mockResolvedValue('')
  vi.mocked(savePreference).mockResolvedValue()
  useSessionStore().accept({ accountId: 'appearance', member: { id: 'me', name: '成员', initial: '测', role: '', color: '' }, identities: [], createdAt: '' })
  const data = seedDataStore(); data.tasks = []; data.apiLive = false; data.taskLoadState = 'loaded'
})
const options = { global: { stubs: { ProjectChecklistView: { template: '<div><slot name="list-header" /></div>' }, TodoFolderNavigation: true } } }

it('十二组颜色使用稳定键，空值及损坏值恢复松石色', () => {
  expect(TODO_PALETTES).toHaveLength(12)
  expect(new Set(TODO_PALETTES.map(item => item.id)).size).toBe(12)
  expect(decodeTodoPalette(null)).toBe('turquoise')
  expect(decodeTodoPalette('unknown')).toBe('turquoise')
  expect(decodeTodoPalette('pink')).toBe('pink')
})

it('外观选择可键盘访问，初次读取未完成时禁止覆盖', async () => {
  const wrapper = mount(TodoAppearance, { props: { modelValue: 'turquoise', ready: false }, global: { stubs: { SettingsDialogShell: { template: '<section><slot /></section>' } } } })
  await wrapper.get('[aria-label="待办背景颜色"]').trigger('click')
  expect(wrapper.findAll('.palette-grid button')).toHaveLength(12)
  expect(wrapper.findAll('.palette-grid button').every(button => button.attributes('disabled') !== undefined)).toBe(true)
  await wrapper.setProps({ ready: true })
  await wrapper.findAll('.palette-grid button').find(button => button.text() === '桃夭')!.trigger('click')
  expect(wrapper.emitted('update:modelValue')).toEqual([['pink']])
})

it('按成员保存颜色，失败保留所选颜色并允许重试', async () => {
  const wrapper = mount(TodoView, options); await flushPromises()
  expect(wrapper.get('.task-workspace').attributes('data-todo-palette')).toBe('turquoise')
  vi.mocked(savePreference).mockRejectedValueOnce(new Error('offline'))
  wrapper.getComponent(TodoAppearance).vm.$emit('update:modelValue', 'pink'); await flushPromises()
  expect(wrapper.get('.task-workspace').attributes('data-todo-palette')).toBe('pink')
  expect(wrapper.getComponent(TodoAppearance).props('error')).toContain('本机选择已保留')
  expect(savePreference).toHaveBeenLastCalledWith('todo-palette-v1', 'pink')
  wrapper.getComponent(TodoAppearance).vm.$emit('retry'); await flushPromises()
  expect(wrapper.getComponent(TodoAppearance).props('error')).toBe('')
  useSessionStore().accept({ accountId: 'other', member: { id: 'other', name: '其他成员', initial: '他', role: '', color: '' }, identities: [], createdAt: '' })
  await flushPromises()
  expect(wrapper.get('.task-workspace').attributes('data-todo-palette')).toBe('turquoise')
})
