import { describe, expect, it, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import NewTaskModal from '@/components/overlays/NewTaskModal.vue'
vi.mock('@/components/ui/MarkdownEditor.vue', () => ({ default: { name: 'MarkdownEditor', props: ['modelValue', 'readonly'], template: '<div />' } }))
import { usePreferenceStore } from '@/stores/preferences'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'

describe('任务项目选择', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); seedDataStore(); useSessionStore().accept({ accountId: 'a', member: { id: 'wg', name: '验收', initial: '验', color: '', role: '' }, identities: [], createdAt: '' }) })
  it('全局入口默认存到本人收集箱，即使旧项目偏好仍存在', async () => {
    const data = useDataStore()
    usePreferenceStore().defaultProject = data.projects[0]!.id
    localStorage.setItem('wao-day-task-recent-project-v1:a:wg', data.projects[1]!.id)
    const w = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } }); await flushPromises()
    const select = w.get<HTMLSelectElement>('[aria-label="所属清单"]')
    expect(select.element.value).toBe('')
    expect(select.get('option:checked').text()).toBe('收集箱')
    await w.get('.title-input').setValue('随手记下的待办')
    await w.get('.foot .btn-primary').trigger('click'); await flushPromises()
    expect(data.tasks.find(t => t.title === '随手记下的待办')).toMatchObject({ project:'', owner:'wg' })
    w.unmount()
  })
  it('手选的清单不受旧默认影响，清单失效时保留显式选择和标题', async () => {
    const data = useDataStore(), prefs = usePreferenceStore()
    const first = data.projects[0]!.id, second = data.projects[1]!.id
    localStorage.setItem('wao-day-task-recent-project-v1:a:wg', first)
    const w = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } }); await flushPromises()
    const select = w.get<HTMLSelectElement>('[aria-label="所属清单"]')
    await select.setValue(first)
    prefs.defaultProject = second; await flushPromises(); expect(select.element.value).toBe(first)
    await w.get('.title-input').setValue('保留标题')
    data.projects = data.projects.filter(p=>p.id!==first); await flushPromises()
    expect(select.element.value).toBe(first); expect(w.get<HTMLInputElement>('.title-input').element.value).toBe('保留标题')
    await w.get('.foot .btn-primary').trigger('click'); await flushPromises()
    expect(data.tasks.some(task => task.title === '保留标题')).toBe(false)
    w.unmount()
  })
  it('派生入口不从偏好或最近项目自动选归属', async () => {
    usePreferenceStore().defaultProject = useDataStore().projects[0]!.id
    useUiStore().modalTaskSeed = { title: '派生草稿', desc: '', sourceDocId: 'n', sourceTitle: '来源', sourceUrl: '/notes', draftKey: 'derived' }
    const w = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } }); await flushPromises()
    expect(w.get<HTMLSelectElement>('[aria-label="所属清单"]').element.value).toBe(''); w.unmount()
  })
})

vi.mock('@/api/favorites', async original => ({ ...await original<typeof import('@/api/favorites')>(), fetchPreference: vi.fn().mockResolvedValue(''), savePreference: vi.fn().mockResolvedValue(undefined) }))
