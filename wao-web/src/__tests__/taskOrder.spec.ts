import TaskOrderStatus from '@/components/task/TaskOrderStatus.vue'
import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { fetchPreference, savePreference } from '@/api/favorites'
import { seedDataStore } from './helpers/seedDataStore'
import InlineSubtasks from '@/components/task/InlineSubtasks.vue'
import TaskQuadrantView from '@/components/task/TaskQuadrantView.vue'
import { SUBTASK_DRAG_TYPE } from '@/utils/subtaskTree'
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
function member(id: string) {
  useSessionStore().account = { accountId: id, email: '', member: { id, name: id, role: '成员', initial: id, color: '#000' } } as never
}
beforeEach(() => {
  localStorage.clear(); setActivePinia(createPinia()); member('one')
  vi.mocked(fetchPreference).mockReset().mockResolvedValue('')
  vi.mocked(savePreference).mockReset().mockResolvedValue(undefined)
})
it('上下插入持久保存，筛选隐藏项保留，切换设备读取恢复', async () => {
  const order = useTaskOrderStore()
  vi.mocked(fetchPreference).mockResolvedValue('["a","hidden","b","c"]')
  await order.move('project:p', ['a','b','c'], 'c', 'a')
  expect(order.state('project:p').ids).toEqual(['c','a','hidden','b'])
  await order.move('project:p', ['a','b','c'], 'c', 'b', true)
  expect(savePreference).toHaveBeenLastCalledWith('task-order-v1:project:p', '["a","hidden","b","c"]')
  setActivePinia(createPinia()); member('one')
  await useTaskOrderStore().load('project:p')
  expect(useTaskOrderStore().sort('project:p', ['c','b','a'], id => id)).toEqual(['a','b','c'])
})
it('保存失败保留草稿，刷新后可重试且不被服务端旧顺序覆盖', async () => {
  vi.mocked(savePreference).mockRejectedValueOnce(new Error('离线'))
  await useTaskOrderStore().move('project:p', ['a','b'], 'b','a')
  expect(useTaskOrderStore().state('project:p').error).toBe('离线')
  setActivePinia(createPinia()); member('one')
  const restored = useTaskOrderStore(); await restored.load('project:p')
  expect(restored.state('project:p').ids).toEqual(['b','a'])
  await restored.retry('project:p')
  expect(restored.state('project:p').dirty).toBe(false)
  expect(localStorage.length).toBe(0)
})
it('切换成员后迟到读取不混入新账号', async () => {
  let finish!: (value: string) => void
  vi.mocked(fetchPreference).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const order = useTaskOrderStore(), pending = order.load('project:p')
  member('two'); finish('["private"]'); await pending
  expect(order.state('project:p').ids).toEqual([])
})
it('保存期间继续拖动串行保存最新顺序', async () => {
  let finish!: () => void
  vi.mocked(savePreference).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const order = useTaskOrderStore()
  const first = order.move('project:p',['a','b','c'],'c','a')
  await flushPromises()
  await order.move('project:p',['a','b','c'],'b','c')
  finish(); await first
  expect(savePreference).toHaveBeenLastCalledWith('task-order-v1:project:p','["b","c","a"]')
  expect(order.state('project:p').dirty).toBe(false)
})
it.each([true,false])('子任务行拖拽只改个人顺序，不改变父子关系（表格=%s）', async table => {
  const data = seedDataStore(); const task=data.tasks[0]!
  task.subs=[{id:'a',text:'甲',done:false},{id:'b',text:'乙',done:false}]
  const move=vi.spyOn(data,'moveSubtask'), update=vi.spyOn(data,'reorderSub')
  const wrapper=mount(InlineSubtasks,{props:{taskId:task.id,table}})
  await flushPromises()
  const rows=wrapper.findAll(table?'.inline-sub-row':'.inline-sub')
  await rows[0]!.trigger('drop',{dataTransfer:{getData:(key:string)=>key===SUBTASK_DRAG_TYPE?JSON.stringify({taskId:task.id,subId:'b'}):''}})
  await flushPromises()
  expect(wrapper.findAll(table?'.inline-sub-row':'.inline-sub')[0]!.text()).toContain('乙')
  expect(wrapper.findAll(table?'.inline-sub-row':'.inline-sub')[1]!.text()).toContain('甲')
  expect(useTaskOrderStore().state(`subs:${task.id}`).ids).toEqual(['b','a'])
  expect(task.subs.map(s=>s.id)).toEqual(['a','b'])
  expect(move).not.toHaveBeenCalled();expect(update).not.toHaveBeenCalled()
  wrapper.unmount()
})
it('四象限同区排序保持优先级与父子关系', async () => {
  const data=seedDataStore(), sample=data.tasks[0]!
  data.tasks=[{...sample,id:'a',pr:'P0',title:'甲'},{...sample,id:'b',pr:'P0',title:'乙'}]
  const flush=vi.spyOn(data,'flushTask').mockResolvedValue(undefined), move=vi.spyOn(data,'moveTask')
  const wrapper=mount(TaskQuadrantView,{props:{tasks:data.tasks,selectedIds:[],showDone:false,projectId:'p'}})
  await flushPromises()
  await wrapper.findAll('article')[1]!.trigger('dragstart')
  await wrapper.findAll('article')[0]!.trigger('drop')
  await flushPromises()
  expect(wrapper.findAll('.quadrant-title').map(n=>n.text())).toEqual(['乙','甲'])
  expect(data.tasks.map(t=>t.pr)).toEqual(['P0','P0'])
  expect(move).not.toHaveBeenCalled();flush.mockRestore();wrapper.unmount()
})

it('排序保存后不占提示行，失败时仍显示重试入口', async () => {
  const w = mount(TaskOrderStatus, { props: { scope: 'project:quiet' } })
  await flushPromises()
  await vi.waitFor(() => expect(w.find('[role="status"]').exists()).toBe(false))
  const state = useTaskOrderStore().state('project:quiet')
  state.error = '保存失败'
  await flushPromises()
  expect(w.text()).toContain('保存失败')
  expect(w.get('button').text()).toBe('重试')
  w.unmount()
})
