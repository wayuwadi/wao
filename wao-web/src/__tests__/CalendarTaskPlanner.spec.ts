import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import CalendarTaskPlanner from '@/components/calendar/CalendarTaskPlanner.vue'
import type { Task } from '@/types'
const task = (patch: Partial<Task> = {}): Task => ({ id: 't1', title: '虚构排期任务', owner: 'me', project: '', pr: 'P2', start: '', due: '2026-11-10', startAt: null, endAt: null, editVersion: 'v1', ...patch } as Task)
enableAutoUnmount(afterEach)
beforeEach(() => localStorage.clear())
function setup(tasks = [task()]) {
  const save = vi.fn(async () => true)
  return { save, wrapper: mount(CalendarTaskPlanner, { props: { tasks, projects: [], identity: 'account:me', date: '2026-11-06', loading: false, loadError: false, save } }) }
}
describe('日历并列任务安排', () => {
  it('精确编辑提交执行安排、版本与旧快照，不修改截止日期', async () => {
    const { wrapper, save } = setup()
    await wrapper.findAll('button').find(b => b.text() === '安排')!.trigger('click')
    await wrapper.get('.planner-editor input[type=date]').setValue('2026-11-08')
    await wrapper.findAll('.planner-actions button').find(b => b.text() === '保存安排')!.trigger('click')
    await flushPromises()
    expect(save).toHaveBeenCalledWith('t1', { start: '2026-11-08', startAt: null, endAt: null, schedulePeriod: null }, 'v1', { start: '', startAt: null, endAt: null, schedulePeriod: null })
    expect(wrapper.find('.planner-editor').exists()).toBe(false)
  })
  it('时间区间验证与保存失败保留输入，再打开恢复草稿', async () => {
    const { wrapper, save } = setup()
    await wrapper.findAll('button').find(b => b.text() === '安排')!.trigger('click')
    await wrapper.get('.planner-editor select').setValue('time')
    const inputs = wrapper.findAll('.planner-editor input[type=datetime-local]')
    await inputs[0]!.setValue('2026-11-06T23:00'); await inputs[1]!.setValue('2026-11-06T22:00')
    const apply = wrapper.findAll('.planner-actions button').find(b => b.text() === '保存安排')!
    await apply.trigger('click'); expect(save).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('结束时间必须晚于开始时间')
    await inputs[1]!.setValue('2026-11-07T02:00'); save.mockResolvedValue(false)
    await apply.trigger('click'); await flushPromises()
    expect(wrapper.get('.planner-editor').text()).toContain('执行安排尚未保存')
    expect(wrapper.findAll('.planner-editor input')[1]!.element).toHaveProperty('value', '2026-11-07T02:00')
    await wrapper.findAll('.planner-actions button').find(b => b.text() === '收起')!.trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '安排')!.trigger('click')
    expect(wrapper.text()).toContain('已恢复本机未提交安排')
  })
  it('并发更新阻止覆盖，重新读取后才能应用；账号切换不带走旧草稿', async () => {
    const { wrapper, save } = setup()
    await wrapper.findAll('button').find(b => b.text() === '安排')!.trigger('click')
    await wrapper.get('.planner-editor input[type=date]').setValue('2026-11-08')
    await wrapper.setProps({ tasks: [task({ start: '2026-11-09', editVersion: 'v2' })] })
    expect(wrapper.get('.planner-editor').text()).toContain('任务已被其他操作修改')
    expect(wrapper.findAll('.planner-actions button')[0]!.attributes('disabled')).toBeDefined()
    await wrapper.findAll('.planner-editor button').find(b => b.text() === '重新读取')!.trigger('click')
    expect(wrapper.get('.planner-editor input[type=date]').element).toHaveProperty('value', '2026-11-09')
    await wrapper.setProps({ identity: 'account:someone-else' })
    expect(wrapper.find('.planner-editor').exists()).toBe(false)
    expect(save).not.toHaveBeenCalled()
  })
  it('账号切走再切回后，旧保存结果不能关闭新编辑或解除新保存状态', async () => {
    const { wrapper, save } = setup()
    let first!: (value: boolean) => void, second!: (value: boolean) => void
    save.mockImplementationOnce(() => new Promise<boolean>(resolve => { first = resolve }))
      .mockImplementationOnce(() => new Promise<boolean>(resolve => { second = resolve }))
    const arrange = () => wrapper.findAll('button').find(b => b.text() === '安排')!
    await arrange().trigger('click')
    await wrapper.get('.planner-editor input[type=date]').setValue('2026-11-08')
    await wrapper.findAll('.planner-actions button')[0]!.trigger('click')
    await wrapper.setProps({ identity:'account:other', date:'2026-12-01' })
    await wrapper.setProps({ identity:'account:me' })
    await arrange().trigger('click')
    await wrapper.get('.planner-editor input[type=date]').setValue('2026-12-03')
    await wrapper.findAll('.planner-actions button')[0]!.trigger('click')
    first(true); await flushPromises()
    expect(wrapper.find('.planner-editor').exists()).toBe(true)
    expect(wrapper.findAll('.planner-actions button')[0]!.text()).toBe('保存中…')
    expect(wrapper.get('.planner-editor input[type=date]').element).toHaveProperty('value','2026-12-03')
    second(true); await flushPromises()
    expect(wrapper.find('.planner-editor').exists()).toBe(false)
    expect((save.mock.calls as unknown[][])[0]?.[1]).toEqual({start:'2026-11-08',startAt:null,endAt:null,schedulePeriod:null})
  })
  it('未排期不以截止日期判断，搜索及分批显示不丢任务', async () => {
    const { wrapper } = setup(Array.from({ length: 65 }, (_, i) => task({ id: `t${i}`, title: `虚构任务${i}` })).concat(task({ id: 'scheduled', start: '2026-11-06' })))
    expect(wrapper.findAll('.planner-task')).toHaveLength(50)
    await wrapper.findAll('button').find(b => b.text() === '继续显示 15 项')!.trigger('click')
    expect(wrapper.findAll('.planner-task')).toHaveLength(65)
    await wrapper.get('input[type=search]').setValue('虚构任务64')
    expect(wrapper.findAll('.planner-task')).toHaveLength(1)
    expect(wrapper.get('.planner-task').text()).toContain('截止 2026-11-10')
  })
})
