import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { useDataStore } from '@/stores/data'

vi.mock('@/api/ai', () => ({
  createAiRuns: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
  fetchTaskAiRuns: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects/:id', component: { template: '<div />' } },
      { path: '/kb/:id', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
}

describe('项目内创建文档', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    const project = data.projectById('p1')!
    project.docs = []
    data.kbs = data.kbs.filter((kb) => kb.project !== project.id)
    Object.keys(data.docs).forEach((id) => delete data.docs[id])
  })

  async function mountDocs() {
    const router = makeRouter()
    await router.push('/projects/p1?tab=文档')
    await router.isReady()
    const wrapper = mount(ProjectDetailView, {
      global: { plugins: [router], stubs: { ProjectSettingsModal: true } },
    })
    return { wrapper, router }
  }

  it('首次点击会显式创建项目知识库和文档，并进入真实编辑页', async () => {
    const { wrapper, router } = await mountDocs()

    expect(wrapper.get('.doc-empty').text()).toContain('首次新建文档时会创建并关联项目知识库')
    await wrapper.get('.tab-toolbar .btn-primary').trigger('click')
    await flushPromises()

    const projectKb = data.kbs.find((kb) => kb.project === 'p1')!
    expect(projectKb).toBeDefined()
    expect(projectKb.folders).toHaveLength(1)
    expect(projectKb.folders[0]!.docs).toHaveLength(1)
    const docId = projectKb.folders[0]!.docs[0]!
    expect(data.docById(docId)?.links).toContainEqual({ type: 'project', id: 'p1' })
    expect(router.currentRoute.value.path).toBe(`/kb/${projectKb.id}`)
    expect(router.currentRoute.value.query.doc).toBe(docId)
    wrapper.unmount()
  })

  it('已有项目知识库时直接复用，不会重复创建知识库', async () => {
    const projectKb = await data.addKb({ name: '既有项目知识库', project: 'p1' })
    const before = data.kbs.length
    const { wrapper, router } = await mountDocs()

    await wrapper.get('.tab-toolbar .btn-primary').trigger('click')
    await flushPromises()

    expect(data.kbs).toHaveLength(before)
    expect(projectKb!.folders[0]!.docs).toHaveLength(1)
    expect(router.currentRoute.value.path).toBe(`/kb/${projectKb!.id}`)
    wrapper.unmount()
  })

  it('创建失败时停留项目页并给出原地重试入口', async () => {
    vi.spyOn(data, 'addKb').mockResolvedValue(null)
    const { wrapper, router } = await mountDocs()

    await wrapper.get('.tab-toolbar .btn-primary').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/projects/p1')
    expect(wrapper.get('[role="alert"]').text()).toContain('项目知识库创建失败')
    expect(wrapper.get('.tab-toolbar .btn-primary').text()).toContain('重试创建')
    expect(data.kbs.some((kb) => kb.project === 'p1')).toBe(false)
    wrapper.unmount()
  })
})
