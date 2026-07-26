import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'

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
      { path: '/projects/:id', component: { template: '<div />' } },
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
        SideNav: true, TabBar: true, TaskDrawer: true, NewTaskModal: true,
        NewProjectModal: true, QuickNoteModal: true, GlobalSearch: true,
        ShortcutsHelp: true, Celebration: true, RouterView: true,
      },
    },
  })
}

describe('右上角新建入口', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
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

  it('点一次就打开对应弹窗，中间没有第二次点击', async () => {
    const ui = useUiStore()
    const wrapper = await mountLayout()

    await wrapper.findAll('.header .plus')[0]!.trigger('click')
    expect(ui.modal).toBe('task')

    ui.closeModal()
    await wrapper.findAll('.header .plus')[1]!.trigger('click')
    expect(ui.modal).toBe('note')
    wrapper.unmount()
  })
})

describe('项目任务视图', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
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
