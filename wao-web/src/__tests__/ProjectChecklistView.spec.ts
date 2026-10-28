import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { savePreference } from '@/api/favorites'
import { useUiStore } from '@/stores/ui'
import { projectWorkItems } from '@/utils/projectWorkItems'
import ProjectChecklistView from '@/components/task/ProjectChecklistView.vue'
import { seedDataStore } from './helpers/seedDataStore'

vi.mock('@/api/favorites', async original => ({ ...await original<object>(), fetchPreference: vi.fn().mockResolvedValue(''), savePreference: vi.fn().mockResolvedValue(undefined) }))
enableAutoUnmount(afterEach)
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); seedDataStore() })
function setup(error = false) {
  const data = useDataStore()
  data.tasks = [{ ...data.tasks[0]!, id: 'parent', title: '父任务', desc: '父正文', status: '待办', subs: [
    { id: 'child', text: '子任务', desc: '子正文', done: false },
  ] }]
  if (error) {
    vi.spyOn(data, 'taskSaveState').mockReturnValue('error')
    vi.spyOn(data, 'taskSaveError').mockReturnValue('服务器已有新版本')
  }
  const projection = projectWorkItems(data.tasks)
  const wrapper = mount(ProjectChecklistView, {
    props: { items: projection.items, contextItems: projection.items, itemRefs: projection.refs, showDone: false, collapsed: [], query: '' },
    global: { stubs: { CellMenu: { template: '<div><slot /></div>' }, MarkdownEditor: { name: 'MarkdownEditor', props: ['modelValue', 'bare'], template: '<div>{{ modelValue }}</div>' } } },
  })
  return { data, wrapper }
}
describe('项目清单详情', () => {
  it('离开清单串行提交同源子事项，等待期间正文保存不阻止取消', async () => {
    const { data, wrapper } = setup()
    data.tasks[0]!.subs.push({ id:'second', text:'第二子任务', done:false })
    const projection = projectWorkItems(data.tasks)
    await wrapper.setProps({ items:projection.items, contextItems:projection.items, itemRefs:projection.refs })
    let saving = false
    vi.spyOn(data, 'taskSaveState').mockImplementation(() => saving ? 'saving' : 'saved')
    vi.spyOn(data, 'flushTask').mockImplementation(async () => { await Promise.resolve(); saving = false })
    const state = vi.spyOn(data, 'setSubState').mockImplementation(() => { saving = true })
    await wrapper.get('[aria-label="完成 子任务"]').setValue(true)
    saving = true
    await wrapper.setProps({ query:'' })
    expect(wrapper.get('[aria-label="完成 子任务"]').attributes('disabled')).toBeUndefined()
    saving = false
    await wrapper.get('[aria-label="完成 第二子任务"]').setValue(true)
    wrapper.unmount(); await flushPromises()
    expect(state).toHaveBeenCalledWith('parent', 'child', '已完成')
    expect(state).toHaveBeenCalledWith('parent', 'second', '已完成')
  })
  it('完成保留三秒，可再次点击取消且不改原状态；到期才保存并隐藏', async () => {
    vi.useFakeTimers()
    const { data, wrapper } = setup()
    data.tasks[0]!.subs = []
    const sync = async () => {
      const projection = projectWorkItems(data.tasks)
      await wrapper.setProps({ items:projection.items, contextItems:projection.items, itemRefs:projection.refs })
    }
    await sync()
    vi.spyOn(data, 'flushTask').mockResolvedValue()
    const patch = vi.spyOn(data, 'patchTask').mockImplementation((id, values) => { Object.assign(data.taskById(id)!, values) })
    try {
      await wrapper.get('[aria-label="完成 父任务"]').setValue(true)
      await vi.advanceTimersByTimeAsync(2000)
      expect(wrapper.find('.checklist-row').exists()).toBe(true)
      expect(patch).not.toHaveBeenCalled()
      await wrapper.get('[aria-label="完成 父任务"]').setValue(false)
      await vi.advanceTimersByTimeAsync(1500)
      expect(patch).not.toHaveBeenCalled()
      expect(data.tasks[0]!.status).toBe('待办')
      await wrapper.get('[aria-label="完成 父任务"]').setValue(true)
      await vi.advanceTimersByTimeAsync(2999)
      expect(patch).not.toHaveBeenCalled()
      await vi.advanceTimersByTimeAsync(1); await sync()
      expect(patch).toHaveBeenCalledWith('parent', { status:'已完成' })
      expect(wrapper.find('.checklist-row').exists()).toBe(false)
      await wrapper.setProps({ showDone:true })
      expect(wrapper.find('.checklist-row').exists()).toBe(true)
    } finally { wrapper.unmount(); vi.useRealTimers() }
  })
  it('宽度键盘调整保存到成员偏好，重新挂载恢复，切换成员隔离', async () => {
    const session = useSessionStore()
    session.accept({ accountId: 'test', member: { id: 'one', name: '甲', initial: '甲', role: '', color: '' }, identities: [], createdAt: '' })
    const { wrapper } = setup()
    await flushPromises()
    const divider = wrapper.get('[role=separator]')
    await divider.trigger('keydown', { key: 'ArrowRight' }); await flushPromises()
    expect(savePreference).toHaveBeenCalledWith('project-checklist-split-v1', expect.any(String))
    const saved = [...vi.mocked(savePreference).mock.calls].reverse().find(call => call[0] === 'project-checklist-split-v1')
    expect(Number(saved?.[1])).toBeCloseTo(.415)
    expect(divider.attributes('aria-valuenow')).toBe('42')
    wrapper.unmount()
    const restored = setup().wrapper
    await flushPromises()
    expect(restored.get('[role=separator]').attributes('aria-valuenow')).toBe('42')
    session.accept({ accountId: 'test', member: { id: 'two', name: '乙', initial: '乙', role: '', color: '' }, identities: [], createdAt: '' })
    await flushPromises()
    expect(restored.get('[role=separator]').attributes('aria-valuenow')).toBe('40')
    await restored.get('[role=separator]').trigger('keydown', { key: 'End' }); await flushPromises()
    expect(Number(restored.get('[role=separator]').attributes('aria-valuenow'))).toBeLessThanOrEqual(75)
  })
  it('旗标优先级保存到当前任务，详情底部显示直属子项并可切换', async () => {
    const { data, wrapper } = setup()
    const patch = vi.spyOn(data, 'patchTask').mockImplementation(() => undefined)
    const update = vi.spyOn(data, 'updateSub').mockImplementation(() => undefined)
    vi.spyOn(data, 'flushTask').mockResolvedValue(undefined)
    await wrapper.get('[aria-label="任务优先级"]').trigger('click')
    await wrapper.findAll('[role=menuitemradio]').find(b => b.text().includes('P0'))!.trigger('click')
    expect(patch).toHaveBeenCalledWith('parent', { pr: 'P0' })
    expect(wrapper.get('[aria-label="直属子任务"]').text()).toContain('子任务')
    await wrapper.setProps({ collapsed: ['parent'] })
    await wrapper.get('.child-title').trigger('click')
    expect(wrapper.get('h2').text()).toBe('子任务')
    expect(wrapper.emitted('toggle')).toContainEqual(['parent'])
    await wrapper.setProps({ collapsed:[] })
    expect(wrapper.get('.checklist-row.selected').text()).toContain('子任务')
    expect(wrapper.get('.parent-task-link').text()).toContain('父任务')
    expect(wrapper.find('[aria-label="直属子任务"]').exists()).toBe(false)
    await wrapper.get('[aria-label="任务优先级"]').trigger('click')
    await wrapper.findAll('[role=menuitemradio]').find(b => b.text().includes('P1'))!.trigger('click')
    expect(update).toHaveBeenCalledWith('parent', 'child', { pr: 'P1' })
    await wrapper.setProps({ collapsed: ['parent'], items: [...wrapper.props('items')] })
    expect(wrapper.get('h2').text()).toBe('子任务')
    await wrapper.get('.parent-task-link').trigger('click')
    expect(wrapper.get('h2').text()).toBe('父任务')
  })
  it('顶部切换分组和状态，子事项沿用父分组且只提供支持的状态', async () => {
    const { data, wrapper } = setup()
    data.projectById(data.tasks[0]!.project)!.taskGroups = [{ id: 'g1', name: '工作' }, { id: 'g2', name: '生活' }]
    const move = vi.spyOn(data, 'moveTaskToGroup').mockImplementation(() => undefined)
    const flush = vi.spyOn(data, 'flushTask').mockResolvedValue(undefined)
    const patch = vi.spyOn(data, 'patchTask').mockImplementation(() => undefined)
    await flushPromises()
    await wrapper.get('[aria-label="任务分组"]').trigger('click')
    await wrapper.findAll('[role=menuitemradio]').find(b => b.text() === '生活')!.trigger('click')
    expect(move).toHaveBeenCalledWith('parent', 'g2')
    await wrapper.get('[aria-label="任务状态"]').trigger('click')
    await wrapper.findAll('[role=menuitemradio]').find(b => b.text() === '进行中')!.trigger('click')
    expect(patch).toHaveBeenCalledWith('parent', { status: '进行中' })
    expect(flush).toHaveBeenCalledWith('parent')
    expect(wrapper.get('[aria-label="任务状态"]').text()).not.toContain('已完成')
    await wrapper.findAll('.task-select')[1]!.trigger('click')
    expect(wrapper.get('[aria-label="任务分组"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="任务状态"]').trigger('click')
    expect(wrapper.findAll('[role=menuitemradio]')).toHaveLength(3)
  })
  it('点击子任务只切换右侧卡片，Markdown 写入真实子事项而非父正文', async () => {
    const { data, wrapper } = setup()
    const update = vi.spyOn(data, 'updateSub').mockImplementation(() => undefined)
    const patch = vi.spyOn(data, 'patchTask').mockImplementation(() => undefined)
    await wrapper.findAll('.task-select')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.get('h2').text()).toBe('子任务')
    expect(useUiStore().openTaskId).toBeFalsy()
    wrapper.findComponent({ name: 'MarkdownEditor' }).vm.$emit('update:modelValue', '## 子任务记录')
    await flushPromises()
    expect(update).toHaveBeenCalledWith('parent', 'child', { desc: '## 子任务记录' })
    expect(patch).not.toHaveBeenCalled()
    await wrapper.findAll('.task-select')[0]!.trigger('click')
    expect(wrapper.find('textarea').exists()).toBe(false)
  })
  it('查找子任务保留父级，折叠和筛选移除当前选择后不会展示旧详情', async () => {
    const { wrapper } = setup()
    await wrapper.setProps({ query: '子正文', collapsed: ['parent'] })
    expect(wrapper.findAll('.task-select')).toHaveLength(2)
    await wrapper.findAll('.task-select')[1]!.trigger('click')
    await wrapper.setProps({ query: '', collapsed: ['parent'] })
    expect(wrapper.findAll('.task-select')).toHaveLength(1)
    expect(wrapper.get('h2').text()).toBe('父任务')
    await wrapper.setProps({ query: '不存在' })
    expect(wrapper.find('h2').exists()).toBe(false)
    expect(wrapper.text()).toContain('没有匹配的任务')
  })
  it('保存错误保留内容并提供原任务冲突处理入口', async () => {
    const { wrapper } = setup(true)
    await wrapper.setProps({ query: '父' })
    await flushPromises()
    expect(wrapper.text()).toContain('服务器已有新版本')
    await wrapper.findAll('button').find(b => b.text() === '查看保存问题')!.trigger('click')
    expect(useUiStore().openTaskId).toBe('parent')
  })
})
