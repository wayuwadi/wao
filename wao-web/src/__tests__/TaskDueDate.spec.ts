import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TaskDueDate from '@/components/task/TaskDueDate.vue'
import { deadlineInfo, shiftDay } from '@/utils/taskDeadline'
import { useTaskDeadlineDefaults } from '@/composables/taskDeadlineDefaults'
import { useDataStore } from '@/stores/data'
import { todayIso } from '@/utils/time'
import { seedDataStore } from './helpers/seedDataStore'

enableAutoUnmount(afterEach)
beforeEach(() => { setActivePinia(createPinia()); localStorage.clear(); seedDataStore() })
describe('截止日期', () => {
  it('按日计算延期，今天与完成项不标延期，跨月和闰年正确', () => {
    expect(deadlineInfo('2026-10-20', '2026-10-28')).toEqual({ label:'10月20日', overdue:8 })
    expect(deadlineInfo('2026-10-28', '2026-10-28')).toEqual({ label:'10月28日，今天', overdue:0 })
    expect(deadlineInfo('2026-10-20', '2026-10-28', true).overdue).toBe(0)
    expect(shiftDay('2028-02-28', 1)).toBe('2028-02-29')
    expect(shiftDay('2026-12-31', 1)).toBe('2027-01-01')
  })
  it('点击或键盘选日期，确认才写入；取消不改日期', async () => {
    const wrapper = mount(TaskDueDate, { props:{ due:'2026-10-20', today:'2026-10-28' }, global:{ stubs:{ CellMenu:{ template:'<div><slot /></div>' } } } })
    expect(wrapper.get('.due-trigger').text()).toContain('已延期 8 天')
    await wrapper.get('.due-trigger').trigger('click')
    await wrapper.get('[aria-label="2026-10-20"]').trigger('keydown', { key:'ArrowRight' })
    expect(wrapper.emitted('change')).toBeUndefined()
    await wrapper.findAll('button').find(b => b.text() === '确定')!.trigger('click')
    expect(wrapper.emitted('change')).toEqual([['2026-10-21']])
    await wrapper.get('.due-trigger').trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '明天')!.trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '取消')!.trigger('click')
    expect(wrapper.emitted('change')).toHaveLength(1)
  })
  it('批量补齐多个项目及子事项，仅修改缺失日期，冲突任务保留', async () => {
    const data = useDataStore(), base = data.tasks[0]!
    data.tasks = [
      { ...base, id:'one', project:'p1', due:'', subs:[{ id:'c1', text:'无日期', done:false }, { id:'c2', text:'有日期', done:false, due:'2027-01-01' }] },
      { ...base, id:'two', project:'p2', due:'2026-11-01', subs:[] },
      { ...base, id:'conflict', project:'p2', due:'', subs:[] },
    ]
    vi.spyOn(data, 'flushTask').mockResolvedValue()
    vi.spyOn(data, 'taskSaveState').mockImplementation(id => id === 'conflict' ? 'error' : 'saved')
    const patch = vi.spyOn(data, 'patchTask').mockImplementation((id, fields) => Object.assign(data.taskById(id)!, fields))
    const batch = useTaskDeadlineDefaults()
    expect(batch.missingCount.value).toBe(3)
    await batch.fill()
    expect(data.taskById('one')?.due).toBe(todayIso())
    expect(data.taskById('one')?.subs.map(sub => sub.due)).toEqual([todayIso(), '2027-01-01'])
    expect(data.taskById('two')?.due).toBe('2026-11-01')
    expect(data.taskById('conflict')?.due).toBe('')
    expect(patch).toHaveBeenCalledTimes(1)
    expect(batch.result.value).toContain('已补齐 2 项')
    expect(batch.result.value).toContain('1 个任务未保存')
  })
})
