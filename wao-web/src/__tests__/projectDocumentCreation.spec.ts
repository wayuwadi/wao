import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'

vi.mock('@/api/ai', () => ({
  createAiRuns: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
  fetchTaskAiRuns: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
}))

describe('项目只汇总任务关联的文档', () => {
  let data: ReturnType<typeof useDataStore>
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    seedDataStore(data)
    data.tasks.forEach(task => { task.docs = []; task.subs = [] })
    Object.keys(data.docs).forEach(id => delete data.docs[id])
  })

  async function mountDocs() {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/projects/:id', component: { template: '<div />' } },
      { path: '/kb/:id', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ] })
    await router.push('/projects/p1?tab=文档')
    await router.isReady()
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router], stubs: { ProjectSettingsModal: true } } })
    return { wrapper, router }
  }

  async function makeDoc(title: string) {
    const kb = await data.addKb({ name: '独立知识库' })
    const id = await data.addDoc(kb!.id, kb!.folders[0]!.id)
    data.docs[id!]!.title = title
    return { id: id!, kb: kb! }
  }

  it('不自动创建项目知识库，空状态引导关联具体任务', async () => {
    const before = data.kbs.length
    const { wrapper, router } = await mountDocs()
    expect(wrapper.get('.doc-empty').text()).toContain('在任务或子任务中关联文档')
    expect(wrapper.text()).not.toContain('新建文档')
    await wrapper.get('.tab-toolbar .btn-primary').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/knowledge')
    expect(data.kbs).toHaveLength(before)
    wrapper.unmount()
  })

  it('跨知识库汇总主任务和子任务的文档，去重并排除旧项目绑定和归档文档', async () => {
    const first = await makeDoc('任务文档')
    const second = await makeDoc('子任务文档')
    const legacy = await makeDoc('旧项目文档')
    legacy.kb.project = 'p1'
    data.docs[legacy.id]!.links = [{ type: 'project', id: 'p1' }]
    data.projectById('p1')!.docs = [legacy.id]
    const archived = await makeDoc('归档文档')
    await data.archiveDoc(archived.id)
    const task = data.tasksByProject('p1')[0]!
    task.docs = [first.id, archived.id]
    task.subs = [{ id: 'sub1', text: '子任务', done: false, docs: [first.id, second.id] }]
    const { wrapper, router } = await mountDocs()
    expect(wrapper.findAll('.doc-title').map(node => node.text())).toEqual(['任务文档', '子任务文档'])
    await wrapper.findAll('.doc-row')[1]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(`/kb/${second.kb.id}`)
    expect(router.currentRoute.value.query.doc).toBe(second.id)
    await router.push('/projects/p1?tab=文档')
    task.subs[0]!.docs = []
    await flushPromises()
    expect(wrapper.findAll('.doc-title').map(node => node.text())).toEqual(['任务文档'])
    wrapper.unmount()
  })
})
