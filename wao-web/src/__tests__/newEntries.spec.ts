import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import NewTaskModal from '@/components/overlays/NewTaskModal.vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { seedDataStore } from './helpers/seedDataStore'

// 本文件只验证入口与表单外壳，不验证编辑器。模块级 mock 可阻止 AppLayout 静态导入
// NewTaskModal 时在用例结束后继续解析 Tiptap，避免 CI 并行运行时发生环境销毁竞态。
vi.mock('@/components/ui/MarkdownEditor.vue', () => ({
  __esModule: true,
  default: { name: 'MarkdownEditor', template: '<div class="markdown-editor-stub" />' },
}))

/**
 * 新建入口与任务视图：右上角只留两个按钮、看板「待办」列自带加号、视图被记住。
 *
 * 这些都是「少点一次」的交互，断言必须落在真实渲染出来的 DOM 上，
 * 光验证 store 的状态说明不了按钮到底在不在。
 */

function router() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/projects/:id', name: 'project', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
}

async function mountLayout() {
  const r = router()
  r.push('/')
  await r.isReady()
  return mount(AppLayout, {
    global: {
      plugins: [r],
      stubs: {
        SideNav: true,
        TabBar: true,
        TaskDrawer: true,
        NewTaskModal: true,
        NewProjectModal: true,
        QuickNoteModal: true,
        GlobalSearch: true,
        ShortcutsHelp: true,
        Celebration: true,
        NotificationCenter: true,
        FloatingChat: true,
        RouterView: true,
      },
    },
  })
}

describe('右上角新建入口', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore()
    vi.spyOn(useDataStore(), 'hydrate').mockResolvedValue(undefined as never)
  })

  it('只有「新建任务」和「新建小记」两个按钮，不用再点开下拉', async () => {
    const wrapper = await mountLayout()

    const labels = wrapper.findAll('.header .plus').map((b) => b.text())
    expect(labels).toEqual(['新建任务', '新建小记'])
    // 二级菜单整体下线
    expect(wrapper.find('.plus-menu').exists()).toBe(false)
    wrapper.unmount()
  })

  it('新建任务打开弹窗，小记一步进入固定记录页面', async () => {
    const ui = useUiStore()
    const wrapper = await mountLayout()

    await wrapper.findAll('.header .plus')[0]!.trigger('click')
    expect(ui.modal).toBe('task')

    ui.closeModal()
    ui.openTask('existing-task')
    await wrapper.findAll('.header .plus')[1]!.trigger('click')
    await flushPromises()
    expect(ui.modal).toBeNull()
    expect(ui.openTaskId).toBeNull()
    expect(wrapper.vm.$router.currentRoute.value.path).toBe('/notes')
    wrapper.unmount()
  })

  it('手机悬浮新建入口仍可直接打开任务表单', async () => {
    const ui = useUiStore()
    const wrapper = await mountLayout()

    const fab = wrapper.find('.fab')
    expect(fab.attributes('aria-label')).toBe('新建任务')
    await fab.trigger('click')

    expect(ui.modal).toBe('task')
    wrapper.unmount()
  })
})

describe('新建任务的所属项目', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore()
  })

  it('项目页入口不被旧全局草稿覆盖，重开恢复该项目草稿', async () => {
    const data = useDataStore(), project = data.projects[1]!
    localStorage.setItem('wao-new-task-draft-v1', JSON.stringify({ title: '全局草稿', project: data.projects[0]!.id }))
    const r = router(); await r.push(`/projects/${project.id}`)
    const first = mount(NewTaskModal, { global: { plugins: [r] } })
    expect(first.get('[aria-label="所属项目"]').element).toHaveProperty('value', project.id)
    expect(first.get('.title-input').element).toHaveProperty('value', '')
    await first.get('.title-input').setValue('项目草稿'); first.unmount()
    await r.push(`/projects/${project.id}`)
    const second = mount(NewTaskModal, { global: { plugins: [r] } })
    expect(second.get('.title-input').element).toHaveProperty('value', '项目草稿')
    expect(JSON.parse(localStorage.getItem('wao-new-task-draft-v1')!).title).toBe('全局草稿'); second.unmount()
  })

  it('真实项目列表替换启动 fixtures 后，首页入口会改选真实项目', async () => {
    const data = useDataStore()
    const fixtureProject = data.projects[0]!
    const realProject = { ...fixtureProject, id: 'real-project', name: '真实项目' }
    const wrapper = mount(NewTaskModal, {
      global: { stubs: { MarkdownEditor: true } },
    })

    expect(wrapper.get('[aria-label="所属项目"]').element).toHaveProperty('value', fixtureProject.id)

    data.projects = [realProject]
    await flushPromises()

    expect(wrapper.get('[aria-label="所属项目"]').element).toHaveProperty('value', 'real-project')
    expect(wrapper.findAll('[aria-label="所属项目"] option').map((pill) => pill.text())).not.toContain(fixtureProject.name)
    wrapper.unmount()
  })

  it('新建任务不再提供 PDCA 入口，普通任务仍可正常创建', async () => {
    const data = useDataStore()
    const before = data.tasks.length
    const wrapper = mount(NewTaskModal, {
      global: { stubs: { MarkdownEditor: true } },
    })

    await wrapper.find('.title-input').setValue('连续一周早睡')
    expect(wrapper.find('.pdca-toggle').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('使用 PDCA 跟踪')
    await wrapper.find('.foot .btn-primary').trigger('click')

    expect(data.tasks).toHaveLength(before + 1)
    expect(data.tasks[0]!.pdcaCycles).toEqual([])
    expect(data.tasks[0]!.createdAt).toMatch(/^\d{4}-\d{2}-\d{2}T/)
    wrapper.unmount()
  })
})

describe('项目任务视图', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    seedDataStore(data)
  })

  async function mountProject() {
    const r = router()
    r.push(`/projects/${data.projects[0]!.id}`)
    await r.isReady()
    return mount(ProjectDetailView, {
      global: { plugins: [r], stubs: { ProjectSettingsModal: true } },
    })
  }

  it('没有记忆时默认进看板，不是列表', async () => {
    const wrapper = await mountProject()

    expect(wrapper.find('.board').exists()).toBe(true)
    expect(wrapper.find('.task-list-card').exists()).toBe(false)
    wrapper.unmount()
  })

  it('切到列表后记住，下次打开还是列表', async () => {
    const first = await mountProject()
    await first.findAll('.seg-btn')[0]!.trigger('click')
    expect(first.find('.task-list-card').exists()).toBe(true)
    first.unmount()

    // 换一个 pinia 实例模拟重开页面，localStorage 里的偏好仍在
    setActivePinia(createPinia())
    data = useDataStore()
    seedDataStore(data)
    const second = await mountProject()
    expect(second.find('.task-list-card').exists()).toBe(true)
    second.unmount()
  })

  it('看板「待办」列头有加号，点它直接新建这个项目的任务', async () => {
    const ui = useUiStore()
    const wrapper = await mountProject()

    const adds = wrapper.findAll('.board-col-add')
    // 只有「待办」一列有加号
    expect(adds).toHaveLength(1)
    expect(wrapper.findAll('.board-col')[0]!.text()).toContain('待办')

    await adds[0]!.trigger('click')
    expect(ui.modal).toBe('task')
    expect(ui.modalProject).toBe(data.projects[0]!.id)
    wrapper.unmount()
  })
})
