import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import CalendarView from '@/views/CalendarView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import type { Task } from '@/types'

vi.mock('@/api/caldav', () => ({
  fetchCaldavAccounts: vi.fn<() => Promise<never[]>>(async () => []),
  fetchCaldavEvents: vi.fn<() => Promise<never[]>>(async () => []),
}))

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
})

describe('日历成员隔离、清单与时间轴', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('默认只看当前人，待安排清单只含未排期与已逾期任务并可集中筛选', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const ui = useUiStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    const other = { id: 'wd', name: '娃弟', initial: '弟', color: '#2C8A5B', role: '成员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me, other]
    const base = data.tasks[0]!
    const task = (patch: Partial<Task>): Task => ({
      ...base,
      id: crypto.randomUUID(),
      parts: [], steps: [], subs: [], files: [], docs: [], linkTasks: [],
      comments: [], custom: [], activity: [], images: [],
      ...patch,
    })
    data.tasks = [
      task({ title: '我的已排期 P2', owner: session.currentUser.id, pr: 'P2', due: data.today }),
      task({ title: '我的未排期 P0', owner: session.currentUser.id, pr: 'P0', due: '' }),
      task({ title: '我的逾期 P1', owner: session.currentUser.id, pr: 'P1', due: '2026-07-01' }),
      task({ title: '他人的任务', owner: other.id, pr: 'P0', due: data.today }),
    ]

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()

    const backlog = wrapper.findAll('.bl-item')
    expect(backlog).toHaveLength(2)
    expect(backlog[0]!.text()).toContain('我的未排期 P0')
    expect(backlog[1]!.text()).toContain('我的逾期 P1')
    expect(wrapper.text()).toContain('我的已排期 P2')
    expect(wrapper.text()).not.toContain('他人的任务')

    await wrapper.find('.bl-filter select').setValue('overdue')
    expect(wrapper.findAll('.bl-item')).toHaveLength(1)
    expect(wrapper.find('.bl-item').text()).toContain('我的逾期 P1')

    await wrapper.find('.view-switch button:last-child').trigger('click')
    const slots = wrapper.findAll('.time-day .time-slot')
    expect(slots).toHaveLength(24)
    await slots[9]!.trigger('mousedown')
    await slots[10]!.trigger('mouseenter')
    await wrapper.find('.timeline').trigger('mouseup')

    expect(ui.modal).toBe('task')
    expect(ui.modalStartAt).toBe(`${data.today}T09:00`)
    expect(ui.modalEndAt).toBe(`${data.today}T11:00`)
  })

  it('已完成任务保留在原日期，月视图和时间轴均以删除线显示', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    const base = data.tasks[0]!
    data.tasks = [{
      ...base,
      id: 'done-calendar-task',
      title: '已完成的时间段任务',
      owner: me.id,
      status: '已完成',
      due: data.today,
      startAt: `${data.today}T09:00`,
      endAt: `${data.today}T10:00`,
      parts: [], steps: [], subs: [], files: [], docs: [], linkTasks: [],
      comments: [], custom: [], activity: [], images: [],
    }, {
      ...base,
      id: 'done-all-day-task',
      title: '已完成的截止任务',
      owner: me.id,
      status: '已完成',
      due: data.today,
      startAt: null,
      endAt: null,
      parts: [], steps: [], subs: [], files: [], docs: [], linkTasks: [],
      comments: [], custom: [], activity: [], images: [],
    }]

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()

    const monthEvents = wrapper.findAll('.event.completed')
    expect(monthEvents).toHaveLength(2)
    expect(monthEvents.map((event) => event.text())).toEqual(expect.arrayContaining([
      expect.stringContaining('已完成的时间段任务'),
      expect.stringContaining('已完成的截止任务'),
    ]))
    expect(wrapper.findAll('.bl-item')).toHaveLength(0)

    await wrapper.findAll('.view-switch button')[3]!.trigger('click')
    const timelineEvent = wrapper.find('.timeline-event.completed')
    expect(timelineEvent.exists()).toBe(true)
    expect(timelineEvent.text()).toContain('已完成的时间段任务')
    const allDayEvent = wrapper.find('.all-day-strip button.completed')
    expect(allDayEvent.exists()).toBe(true)
    expect(allDayEvent.text()).toContain('已完成的截止任务')
  })
})
