import { describe, expect, it, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { selectTaskProject } from '@/utils/taskProjectSelection'
import NewTaskModal from '@/components/overlays/NewTaskModal.vue'
import { usePreferenceStore } from '@/stores/preferences'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'

describe('任务项目选择', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); seedDataStore(); useSessionStore().accept({ accountId: 'a', member: { id: 'wg', name: '验收', initial: '验', color: '', role: '' }, identities: [], createdAt: '' }) })
  it('草稿优先，失效草稿留空，入口优先默认，默认优先最近，派生不猜归属', () => {
    const ids = ['draft','context','default','recent']
    const options = { draft: 'draft', context: 'context', preferred: 'default', recent: 'recent' }
    expect(selectTaskProject(ids, options)).toBe('draft')
    expect(selectTaskProject(ids, { ...options, draft: 'gone' })).toBe('')
    expect(selectTaskProject(ids, { ...options, draft: undefined })).toBe('context')
    expect(selectTaskProject(ids, { preferred: 'default', recent: 'recent' })).toBe('default')
    expect(selectTaskProject(ids, { preferred: 'gone', recent: 'recent' })).toBe('recent')
    expect(selectTaskProject(ids, { preferred: 'default', recent: 'recent', requireConfirmation: true })).toBe('')
    expect(selectTaskProject(ids, {})).toBe('')
  })
  it('全局入口使用共同最近项目，迟到默认不覆盖手选，失效选择保留标题', async () => {
    const data = useDataStore(), prefs = usePreferenceStore()
    const first = data.projects[0]!.id, second = data.projects[1]!.id
    localStorage.setItem('wao-day-task-recent-project-v1:a:wg', first)
    const w = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } }); await flushPromises()
    const select = w.get<HTMLSelectElement>('[aria-label="所属项目"]')
    expect(select.element.value).toBe(first)
    prefs.defaultProject = second; await flushPromises(); expect(select.element.value).toBe(second)
    await select.setValue(first); prefs.defaultProject = first; await flushPromises()
    prefs.defaultProject = second; await flushPromises(); expect(select.element.value).toBe(first)
    await w.get('.title-input').setValue('保留标题')
    data.projects = data.projects.filter(p=>p.id!==first); await flushPromises()
    expect(select.element.value).toBe(''); expect(w.get<HTMLInputElement>('.title-input').element.value).toBe('保留标题')
    w.unmount()
  })
  it('派生入口不从偏好或最近项目自动选归属', async () => {
    usePreferenceStore().defaultProject = useDataStore().projects[0]!.id
    useUiStore().modalTaskSeed = { title: '派生草稿', desc: '', sourceDocId: 'n', sourceTitle: '来源', sourceUrl: '/notes', draftKey: 'derived' }
    const w = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } }); await flushPromises()
    expect(w.get<HTMLSelectElement>('[aria-label="所属项目"]').element.value).toBe(''); w.unmount()
  })
})
