import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { apiPatchProject } from '@/api/board'

vi.mock('@/api/board', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/api/board')>(),
  apiPatchProject: vi.fn(),
}))

vi.mock('@/api/ai', () => ({
  createAiRuns: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
  fetchTaskAiRuns: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
}))

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/projects/:id', component: { template: '<div/>' } }, { path: '/:p(.*)*', component: { template: '<div/>' } }],
})

async function mountGroupView() {
  await router.push('/projects/p1?view=group')
  await router.isReady()
  const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/**
 * 分组视图：一个项目里同时压着好几个 domain 的事，看板按状态切分帮不上忙。
 * 分组按人自己的说法切，组内只关心做没做完。
 */
describe('项目分组视图', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    seedDataStore(data)
  })

  it('还没建过分组时给一个「未分组」兜底，项目里的任务全落在里面', async () => {
    const wrapper = await mountGroupView()

    const cards = wrapper.findAll('.group-card')
    expect(cards).toHaveLength(1)
    expect(cards[0]!.find('.group-name').text()).toContain('未分组')
    // 计数是「未完成 / 全部」
    const open = data.tasksByProject('p1').filter((t) => t.status !== '已完成').length
    expect(cards[0]!.find('.group-count').text()).toBe(`${open} / ${data.tasksByProject('p1').length}`)
    wrapper.unmount()
  })

  it('分组里的任务行只有完成态和标题，不再堆状态 / 优先级 / 负责人', async () => {
    const wrapper = await mountGroupView()

    const row = wrapper.find('.grow')
    expect(row.find('.check').exists()).toBe(true)
    expect(row.find('.grow-title').exists()).toBe(true)
    expect(row.find('.mini').exists()).toBe(false)
    expect(row.find('.owner-av').exists()).toBe(false)
    wrapper.unmount()
  })

  it('分组头上的「+」就地建任务，直接落进这个分组', async () => {
    const wrapper = await mountGroupView()
    const before = data.tasksByProject('p1').length

    await wrapper.find('.group-head .group-op').trigger('click')
    const input = wrapper.find('.group-add-row input')
    await input.setValue('扫描第 4 批证件')
    await input.trigger('keydown.enter')

    const created = data.tasksByProject('p1')
    expect(created).toHaveLength(before + 1)
    const task = created.find((t) => t.title === '扫描第 4 批证件')!
    expect(task.groupId).toBe('tg-default')
    wrapper.unmount()
  })

  it('分组名可以改，超过 6 个仍可继续新建', async () => {
    const wrapper = await mountGroupView()

    await wrapper.find('.group-name').trigger('click')
    const input = wrapper.find('.group-name-input')
    await input.setValue('证件')
    await input.trigger('keydown.enter')
    await flushPromises()
    expect(data.taskGroupsOf('p1')[0]!.name).toBe('证件')

    for (let i = data.taskGroupsOf('p1').length; i < 8; i++) {
      await wrapper.find('.group-add').trigger('click')
      await flushPromises()
    }
    expect(data.taskGroupsOf('p1')).toHaveLength(8)
    expect(wrapper.find('.group-add').exists()).toBe(true)
    wrapper.unmount()
  })

  it('分组独立折叠并在重新进入时恢复，快速新建会展开', async () => {
    await data.setTaskGroups('p1', [{ id: 'g1', name: '证件' }, { id: 'g2', name: '装修' }])
    let wrapper = await mountGroupView()
    await wrapper.findAll('.group-collapse')[0]!.trigger('click')
    expect(wrapper.findAll('.group-body')[0]!.isVisible()).toBe(false)
    expect(wrapper.findAll('.group-body')[1]!.isVisible()).toBe(true)
    wrapper.unmount()
    wrapper = await mountGroupView()
    expect(wrapper.findAll('.group-body')[0]!.isVisible()).toBe(false)
    await wrapper.find('.group-head .group-op').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.group-collapse')[0]!.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('.group-add-row').exists()).toBe(true)
    wrapper.unmount()
  })

  it('服务器保存失败时不把新分组冒充已保存', async () => {
    const before = data.taskGroupsOf('p1')
    data.apiLive = true
    vi.mocked(apiPatchProject).mockRejectedValueOnce(new Error('网络中断'))
    expect(await data.setTaskGroups('p1', [...before, { id: 'failed', name: '尚未保存' }])).toBe(false)
    expect(data.taskGroupsOf('p1')).toEqual(before)
  })

  it('删掉分组不会连带删任务——里面的任务落回第一个分组', async () => {
    const wrapper = await mountGroupView()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    await data.setTaskGroups('p1', [{ id: 'g1', name: '证件' }, { id: 'g2', name: '装修' }])
    await flushPromises()
    const task = data.tasksByProject('p1')[0]!
    data.moveTaskToGroup(task.id, 'g2')
    await flushPromises()

    await wrapper.findAll('.group-card')[1]!.find('.group-op.danger').trigger('click')
    await flushPromises()

    expect(data.taskGroupsOf('p1')).toHaveLength(1)
    expect(data.taskById(task.id)!.groupId).toBe('g1')
    confirm.mockRestore()
    wrapper.unmount()
  })

  it('reorders tasks when one group row is dropped onto another', async () => {
    const wrapper = await mountGroupView()
    const before = data.tasksByProject('p1').filter((task) => task.status !== '已完成')
    expect(before.length).toBeGreaterThanOrEqual(2)

    const rows = wrapper.findAll('.grow')
    await rows[1]!.trigger('dragstart')
    await rows[0]!.trigger('drop')

    const after = data.tasksByProject('p1').filter((task) => task.status !== '已完成')
    expect(after[0]!.id).toBe(before[1]!.id)
    wrapper.unmount()
  })
})
