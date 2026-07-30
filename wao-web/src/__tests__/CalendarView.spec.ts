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

  it('默认只看当前人，清单含全部未完成任务并可框选时间段', async () => {
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
      task({ title: '他人的任务', owner: other.id, pr: 'P0', due: data.today }),
    ]

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()

    const backlog = wrapper.findAll('.bl-item')
    expect(backlog).toHaveLength(2)
    expect(backlog[0]!.text()).toContain('我的未排期 P0')
    expect(wrapper.text()).toContain('我的已排期 P2')
    expect(wrapper.text()).not.toContain('他人的任务')

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
})
