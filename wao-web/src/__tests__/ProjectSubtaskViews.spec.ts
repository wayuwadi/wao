import ProjectChecklistView from '@/components/task/ProjectChecklistView.vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { computed, ref } from 'vue'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import TaskQuadrantView from '@/components/task/TaskQuadrantView.vue'
import { seedDataStore } from './helpers/seedDataStore'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { projectWorkItems, subItemKey, withProjectAncestors } from '@/utils/projectWorkItems'
import { fetchPreference, savePreference } from '@/api/favorites'

enableAutoUnmount(afterEach)
vi.mock('@/api/favorites', async original => ({ ...await original<object>(), fetchPreference: vi.fn().mockResolvedValue(''), savePreference: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@/api/board', async original => ({ ...await original<object>(), apiPutTask: vi.fn(async (_id, task) => JSON.parse(JSON.stringify(task))) }))
beforeEach(() => {
  localStorage.clear(); setActivePinia(createPinia()); vi.clearAllMocks()
  vi.mocked(fetchPreference).mockResolvedValue(''); vi.mocked(savePreference).mockResolvedValue()
  useSessionStore().accept({ accountId: 'a', member: { id: 'm', name: '验收', initial: '验', color: '', role: '' }, identities: [], createdAt: '' })
  const data = seedDataStore(), original = data.tasks[0]!
  data.tasks = [
    { ...original, id: 'a', title: '主任务甲', project: 'p1', status: '待办', pr: 'P2', owner: 'm', parentTaskId: null,
      subs: [{ id: 's1', text: '普通子事项', done: false, status: '待验证', pr: 'P0' },
        { id: 's2', text: '嵌套子事项', parentId: 's1', done: false, status: '进行中', pr: 'P1' },
        { id: 's3', text: '已完成子事项', done: true, pr: 'P3' }] },
    { ...original, id: 'b', title: '完整下级任务', project: 'p1', status: '进行中', pr: 'P1', parentTaskId: 'a', owner: 'm', subs: [] },
    { ...original, id: 'c', title: '独立主任务', project: 'p1', status: '待办', pr: 'P3', parentTaskId: null, owner: 'm', subs: [] },
  ]
})
async function open(view = 'list') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:id', component: { template: '<div />' } }] })
  await router.push(`/projects/p1?view=${view}`)
  const wrapper = mount(ProjectDetailView, { global: { plugins: [router] }, attachTo: document.body }); await flushPromises()
  return { wrapper, router, data: useDataStore() }
}

describe('子任务四视图', () => {
  it('清单分组加号展开就地输入，失败保留草稿并重试同一任务', async () => {
    const data = useDataStore()
    data.projectById('p1')!.taskGroups = [{ id:'g1', name:'工作' }, { id:'g2', name:'生活' }]
    const created = { ...data.tasks[0]!, id:'new-task', title:'新任务', groupId:'g2' }
    const add = vi.spyOn(data, 'addTask').mockResolvedValue({ task:created, persisted:false })
    const patch = vi.spyOn(data, 'patchTask').mockImplementation(() => {})
    const flush = vi.spyOn(data, 'flushTask').mockResolvedValue()
    vi.spyOn(data, 'taskSaveState').mockReturnValue('saved')
    const { wrapper } = await open('checklist')
    const section = wrapper.getComponent(ProjectChecklistView).findAll('section')[1]!
    await section.get('.group-heading').trigger('click')
    expect(section.get('.group-heading').attributes('aria-expanded')).toBe('false')
    await section.get('[aria-label="在 生活 新建任务"]').trigger('click'); await flushPromises()
    expect(section.get('.group-heading').attributes('aria-expanded')).toBe('true')
    const input = section.get<HTMLInputElement>('.group-add-row input')
    expect(document.activeElement).toBe(input.element)
    await input.setValue('新任务'); await input.trigger('keydown', { key:'Enter', isComposing:true })
    expect(add).not.toHaveBeenCalled()
    await input.trigger('keydown', { key:'Enter' }); await flushPromises()
    expect(add).toHaveBeenCalledWith(expect.objectContaining({ title:'新任务', project:'p1', groupId:'g2' }))
    expect(input.element.value).toBe('新任务')
    expect(section.text()).toContain('未保存到服务器')
    await section.get('.group-add-done').trigger('click'); await flushPromises()
    expect(add).toHaveBeenCalledTimes(1)
    expect(patch).toHaveBeenCalledWith('new-task', expect.objectContaining({ groupId:'g2' }))
    expect(flush).toHaveBeenCalledWith('new-task')
    expect(input.element.value).toBe('')
    await input.trigger('keydown', { key:'Escape' })
    expect(section.find('.group-add-row').exists()).toBe(false)
  })
  it('重新打开项目恢复最近视图，显式链接更新记忆', async () => {
    const { wrapper, router } = await open('checklist')
    await router.replace('/projects/p1'); await flushPromises()
    expect(wrapper.findComponent(ProjectChecklistView).exists()).toBe(true)
    await router.replace('/projects/p1?view=list'); await flushPromises()
    await router.replace('/projects/p1'); await flushPromises()
    expect(wrapper.findComponent(ProjectChecklistView).exists()).toBe(false)
    expect(useUiStore().taskViewOf('p1')).toBe('list')
  })
  it.each(['list', 'group', 'board', 'quadrant'])('%s 展示两类子任务，始终展示层级，完成独立过滤', async view => {
    const { wrapper } = await open(view)
    const area = () => wrapper.get('.project-normal-tasks')
    expect(area().text()).toContain('普通子事项'); expect(area().text()).toContain('完整下级任务')
    expect(area().text()).not.toContain('已完成子事项')
    await wrapper.get('.subtask-controls [role=switch]').trigger('click'); await flushPromises()
    expect(area().text()).toContain('已完成子事项')
    expect(wrapper.find('[role=switch][aria-label=显示子任务]').exists()).toBe(false)
    expect(area().text()).toContain('主任务甲'); expect(area().text()).toContain('独立主任务')
  })
  it('忽略旧的隐藏子任务偏好，保持层级可见', async () => {
    vi.mocked(fetchPreference).mockResolvedValue('{"show":false,"collapsed":[]}')
    const { wrapper } = await open('list')
    expect(wrapper.text()).toContain('普通子事项')
    expect(wrapper.get('.subtask-controls').findAll('[role=switch]')).toHaveLength(1)
  })
  it('清单拖动复用真实任务移动接口并拒绝循环归属', async () => {
    const { wrapper, data } = await open('checklist')
    const move = vi.spyOn(data, 'moveTask').mockResolvedValue(undefined)
    const checklist = wrapper.getComponent(ProjectChecklistView)
    checklist.vm.$emit('move', 'c', 'a', 'inside'); await flushPromises()
    expect(move).toHaveBeenCalledWith('c', 'a')
    move.mockClear()
    checklist.vm.$emit('move', 'a', 'b', 'inside'); await flushPromises()
    expect(move).not.toHaveBeenCalled()
    const sub = vi.spyOn(data, 'moveSubtask').mockResolvedValue(undefined)
    checklist.vm.$emit('move', 'sub:a:s2', 'sub:a:s1', 'inside'); await flushPromises()
    expect(sub).toHaveBeenCalledWith('a', 's2', 'a', 's1')
  })
  it('清单直接显示项目分组，无需浏览器先开启表格分组', async () => {
    useDataStore().projectById('p1')!.taskGroups = [{ id:'g1', name:'工作' }, { id:'g2', name:'生活' }]
    useDataStore().tasks[0]!.groupId = 'g1'; useDataStore().tasks[2]!.groupId = 'g2'
    const { wrapper } = await open('checklist')
    const sections = wrapper.getComponent(ProjectChecklistView).findAll('section')
    expect(sections[0]!.text()).toContain('工作'); expect(sections[0]!.text()).toContain('普通子事项')
    expect(sections[1]!.text()).toContain('生活'); expect(sections[1]!.text()).toContain('独立主任务')
  })
  it('子事项拖到其他分组的顶层任务旁时跟随目标分组', async () => {
    const data = useDataStore()
    data.projectById('p1')!.taskGroups = [{ id:'g1', name:'工作' }, { id:'g2', name:'生活' }]
    data.taskById('a')!.groupId = 'g1'; data.taskById('c')!.groupId = 'g2'
    const promoted = { ...data.taskById('a')!, id:'promoted', parentTaskId:null, subs:[] }
    vi.spyOn(data, 'promoteSubtask').mockImplementation(async () => { data.tasks.push(promoted); return promoted })
    vi.spyOn(data, 'moveTask').mockResolvedValue()
    const flush = vi.spyOn(data, 'flushTask').mockResolvedValue()
    const { wrapper } = await open('checklist')
    wrapper.getComponent(ProjectChecklistView).vm.$emit('move', 'sub:a:s1', 'c', 'before'); await flushPromises()
    expect(data.taskById('promoted')!.groupId).toBe('g2')
    expect(flush).toHaveBeenCalledWith('promoted')
    expect(useTaskOrderStore().state('project:p1').ids.indexOf('promoted')).toBeLessThan(useTaskOrderStore().state('project:p1').ids.indexOf('c'))
  })
  it('完整任务拖入子事项保留原父级，嵌套子事项拒绝无损无法表达的移动', async () => {
    const { wrapper, data } = await open('checklist')
    const promote = vi.spyOn(data, 'promoteSubtask').mockImplementation(async () => {
      const promoted = { ...data.taskById('a')!, id:'promoted', parentTaskId:null, subs:[] }
      data.tasks.push(promoted); return promoted
    })
    const move = vi.spyOn(data, 'moveTask').mockImplementation(async (id, parentId) => { data.taskById(id)!.parentTaskId = parentId })
    const checklist = wrapper.getComponent(ProjectChecklistView)
    checklist.vm.$emit('move', 'c', 'sub:a:s2', 'inside'); await flushPromises()
    expect(promote).not.toHaveBeenCalled(); expect(move).not.toHaveBeenCalled()
    checklist.vm.$emit('move', 'c', 'sub:a:s1', 'inside'); await flushPromises()
    expect(move).toHaveBeenNthCalledWith(1, 'promoted', 'a')
    expect(move).toHaveBeenNthCalledWith(2, 'c', 'promoted')
    expect(data.taskById('promoted')!.parentTaskId).toBe('a')
    expect(data.taskById('c')!.parentTaskId).toBe('promoted')
  })
  it.each(['list','group'])('%s 支持全部收起和展开，并保留层级', async view => {
    const { wrapper } = await open(view)
    await wrapper.findAll('button').find(b => b.text() === '收起全部')!.trigger('click'); await flushPromises()
    const rows = () => wrapper.findAll('.task-row,.inline-sub-row,.grow').filter(r => r.isVisible()).map(r=>r.text()).join(' ')
    expect(rows()).not.toContain('嵌套子事项'); expect(rows()).not.toContain('完整下级任务')
    await wrapper.findAll('button').find(b => b.text() === '展开全部')!.trigger('click'); await flushPromises()
    expect(rows()).toContain('嵌套子事项'); expect(rows()).toContain('完整下级任务')
  })
  it('分组全选只包含当前展开可见的完整任务', async () => {
    const { wrapper } = await open('group')
    await wrapper.findAll('button').find(b => b.text() === '收起全部')!.trigger('click'); await flushPromises()
    await wrapper.findAll('button').find(b => b.text().includes('批量选择'))!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('全选当前可见 · 2')
  })
  it.each(['list','group'])('%s 保留已完成父事项下未完成后代', async view => {
    useDataStore().tasks[0]!.subs[0]!.done = true
    useDataStore().tasks[0]!.subs[0]!.status = null
    const { wrapper } = await open(view)
    const visible = wrapper.findAll('.inline-sub-row,.grow').filter(row => row.isVisible()).map(row => row.text()).join(' ')
    expect(visible).toContain('普通子事项'); expect(visible).toContain('嵌套子事项')
  })
  it('看板拖动普通子事项只改变其状态，保持父级和兄弟，详情映射真实 id', async () => {
    const { wrapper, data } = await open('board')
    const card = wrapper.findAll('.board-card').find(c => c.attributes('aria-label') === '任务：普通子事项')!
    await card.trigger('click'); expect(useUiStore().openTaskId).toBe('a'); expect(useUiStore().openTaskSubId).toBe('s1')
    await card.trigger('dragstart', { dataTransfer: { setData: vi.fn() } })
    await wrapper.findAll('.board-col').find(c => c.find('.board-col-label').text() === '进行中')!.trigger('drop'); await flushPromises()
    expect(data.subById('a','s1')!.status).toBe('进行中'); expect(data.taskById('a')!.status).toBe('待办')
    expect(data.subById('a','s2')!.parentId).toBe('s1'); expect(data.taskById('b')!.parentTaskId).toBe('a')
  })
  it('象限普通子事项保存、分享和详情使用源身份，失败项可重试', async () => {
    const data = useDataStore(), state = ref<'idle'|'error'|'saved'>('idle')
    vi.spyOn(data,'taskSaveState').mockImplementation(() => state.value)
    const flush = vi.spyOn(data,'flushTask').mockImplementation(async () => { state.value = 'error' })
    const projection = computed(()=>projectWorkItems(data.tasks))
    const wrapper = mount({ components: { TaskQuadrantView }, setup:()=>({ projection }), template:'<TaskQuadrantView :tasks="projection.items" :item-refs="projection.refs" :selected-ids="[]" :show-done="false" project-id="p1" />' })
    await wrapper.get('button[aria-label="完成任务 普通子事项"]').trigger('click'); await flushPromises()
    expect(data.subById('a','s1')!.done).toBe(true); expect(data.taskById('a')!.pr).toBe('P2'); expect(flush).toHaveBeenCalledWith('a')
    expect(wrapper.text()).toContain('重试保存')
    flush.mockImplementation(async()=>{state.value='saved'})
    await wrapper.findAll('button').find(b=>b.text()==='重试保存')!.trigger('click'); await flushPromises()
    expect(state.value).toBe('saved')
    await wrapper.findAll('.quadrant-title').find(b=>b.text().includes('普通子事项'))!.trigger('click')
    expect(useUiStore().openTaskId).toBe('a'); expect(useUiStore().openTaskSubId).toBe('s1')
  })
  it('四象限按根任务优先级连续展示整个层级，移动主任务不改子项属性', async () => {
    const { wrapper, data } = await open('quadrant')
    const section = () => wrapper.findAll('.quadrant').find(n => n.attributes('aria-label') === '不重要紧急')!
    expect(section().findAll('.quadrant-title').map(n => n.text())).toEqual(['主任务甲','↳ 普通子事项','↳ 嵌套子事项','↳ 完整下级任务'])
    expect(section().get('.quadrant-count').text()).toBe('1')
    expect(wrapper.find('select[aria-label="调整象限 普通子事项"]').exists()).toBe(false)
    expect(wrapper.find('select[aria-label="调整象限 完整下级任务"]').exists()).toBe(false)
    await wrapper.get('select[aria-label="调整象限 主任务甲"]').setValue('P3'); await flushPromises()
    expect(section().findAll('.quadrant-task')).toHaveLength(0)
    const target = wrapper.findAll('.quadrant').find(n => n.attributes('aria-label') === '不重要不紧急')!
    expect(target.findAll('.quadrant-task')).toHaveLength(5)
    expect(data.subById('a','s1')!.pr).toBe('P0'); expect(data.taskById('b')!.pr).toBe('P1')
  })
  it('四象限筛选命中子项仍保留祖先及根优先级', async () => {
    const data = useDataStore(), projection = projectWorkItems(data.tasks)
    data.tasks[0]!.status = '已完成'
    const sub = projection.items.find(t => t.id === subItemKey('a','s2'))!
    const wrapper = mount(TaskQuadrantView, { props: { tasks: [sub], contextTasks: projection.items, itemRefs: projection.refs, selectedIds: [], showDone: false } })
    const section = wrapper.findAll('.quadrant').find(n => n.attributes('aria-label') === '不重要紧急')!
    expect(section.findAll('.quadrant-title').map(n=>n.text())).toEqual(['主任务甲','↳ 普通子事项','↳ 嵌套子事项'])
  })
  it('投影不继承父级属性，历史子项保留稳定键和筛选祖先', () => {
    const data=useDataStore();data.tasks[0]!.subs=[{text:'历史子项',done:false}]
    const {items,refs}=projectWorkItems(data.tasks),sub=items.find(t=>t.id===subItemKey('a','a#0'))!
    expect(sub.owner).toBe('');expect(sub.due).toBe('');expect(sub.pr).toBe('P2');expect(refs[sub.id]!.priorityUnset).toBe(true)
    expect(withProjectAncestors([sub],items).map(t=>t.id)).toEqual(['a',sub.id])
  })
  it('普通子事项不写入主任务排序', async () => {
    const {wrapper}=await open('board');const order=vi.spyOn(useTaskOrderStore(),'move').mockResolvedValue()
    const source=wrapper.findAll('.board-card').find(c=>c.attributes('aria-label') === '任务：普通子事项')!,target=wrapper.findAll('.board-card').find(c=>c.text().includes('独立主任务'))!
    await source.trigger('dragstart',{dataTransfer:{setData:vi.fn()}});await target.trigger('drop');await flushPromises()
    expect(order).not.toHaveBeenCalled()
  })
})
