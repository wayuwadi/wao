import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import HomeDateReminders from '@/components/home/HomeDateReminders.vue'
import type { HomeDateNotice } from '@/api/homeDateReminders'
const api = vi.hoisted(() => ({
  fetchHomeDateReminders: vi.fn(),
  reserveHomeDateReminders: vi.fn(),
  recordHomeDateReminder: vi.fn(),
}))
vi.mock('@/api/homeDateReminders', () => api)
const notice: HomeDateNotice = {
  key: 'id|2026-10-06|0',
  kind: 'BIRTHDAY',
  subject: 'SELF',
  name: '我的生日',
  date: '2026-10-06',
  daysBefore: 0,
  title: '生日快乐，今天对自己好一点。',
  detail: '愿这一岁，有想做的事。',
  targetPath: '/calendar?date=2026-10-06&importantDateId=id',
  priority: 2,
}
let wrapper: VueWrapper | undefined
async function setup(overrides: Record<string, unknown> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div/>' } },
      { path: '/calendar', component: { template: '<div/>' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  wrapper = mount(HomeDateReminders, {
    attachTo: document.body,
    props: {
      member: 'wg',
      date: '2026-10-06',
      quiet: false,
      blocked: false,
      idle: () => true,
      anchors: () => [],
      ...overrides,
    },
    global: { plugins: [router] },
  })
  await flushPromises()
  return router
}
async function enter() {
  await vi.advanceTimersByTimeAsync(4500)
  await flushPromises()
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-06T09:00:00+08:00'))
  vi.spyOn(Math, 'random').mockReturnValue(0)
  api.fetchHomeDateReminders
    .mockReset()
    .mockResolvedValue({ date: '2026-10-06', notices: [notice] })
  api.reserveHomeDateReminders
    .mockReset()
    .mockResolvedValue({ date: '2026-10-06', token: 'token', notices: [notice] })
  api.recordHomeDateReminder.mockReset().mockResolvedValue(undefined)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.replaceChildren()
  vi.restoreAllMocks()
  vi.useRealTimers()
})
describe('首页日期提醒', () => {
  it('停留跨过上海午夜时清除旧日提示并读取新一天，不依赖页面旧日期', async () => {
    vi.setSystemTime(new Date('2026-10-06T23:59:55+08:00'))
    await setup()
    await enter()
    expect(wrapper!.find('.date-message').exists()).toBe(true)
    const nextNotice = { ...notice, key: 'tomorrow|2026-10-07|0', date: '2026-10-07', title: '新一天的真实提醒' }
    api.fetchHomeDateReminders.mockResolvedValue({ date: '2026-10-07', notices: [nextNotice] })
    api.reserveHomeDateReminders.mockResolvedValue({ date: '2026-10-07', token: 'new-token', notices: [nextNotice] })
    await vi.advanceTimersByTimeAsync(1000)
    expect(wrapper!.find('.date-message').exists()).toBe(false)
    await enter()
    expect(wrapper!.text()).toContain('新一天的真实提醒')
    expect(api.recordHomeDateReminder).toHaveBeenLastCalledWith(expect.objectContaining({ date: '2026-10-07', token: 'new-token' }), 'SHOWN')
  })
  it('读取不记已读，等四秒后展示且只写可见回执，确认后退场', async () => {
    await setup()
    expect(wrapper!.find('.date-message').exists()).toBe(false)
    expect(api.recordHomeDateReminder).not.toHaveBeenCalled()
    await enter()
    expect(wrapper!.text()).toContain('生日快乐')
    expect(wrapper!.find('.date-cake').exists()).toBe(true)
    expect(api.recordHomeDateReminder).toHaveBeenCalledWith(
      expect.objectContaining({ token: 'token' }),
      'SHOWN',
    )
    await wrapper!
      .findAll('button')
      .find((button) => button.text() === '收下祝福')!
      .trigger('click')
    await flushPromises()
    expect(api.recordHomeDateReminder).toHaveBeenLastCalledWith(expect.anything(), 'CONFIRMED')
    expect(wrapper!.find('.date-message').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(90000)
    expect(api.reserveHomeDateReminders).toHaveBeenCalledTimes(1)
  })
  it('安静和减少动画只留下静态文字，不丢提醒', async () => {
    await setup({ quiet: true })
    await enter()
    expect(wrapper!.text()).toContain('生日快乐')
    expect(wrapper!.find('.date-cake').exists()).toBe(false)
    expect(wrapper!.classes()).toContain('still')
  })
  it('没有事件时保持空白，过期日期不能展示', async () => {
    api.fetchHomeDateReminders.mockResolvedValue({ date: '2026-10-05', notices: [notice] })
    await setup()
    await enter()
    expect(api.reserveHomeDateReminders).not.toHaveBeenCalled()
    expect(wrapper!.find('.date-message').exists()).toBe(false)
  })
  it('系统事件被其他设备抢先展示时不补出虚构祝福', async () => {
    api.reserveHomeDateReminders.mockResolvedValue({
      date: '2026-10-06',
      token: 'token',
      notices: [],
    })
    await setup()
    await enter()
    expect(wrapper!.find('.date-message').exists()).toBe(false)
    expect(api.recordHomeDateReminder).not.toHaveBeenCalled()
  })
  it('已有弹窗、输入和伙伴动画时顺延', async () => {
    const dialog = document.createElement('div')
    dialog.setAttribute('role', 'dialog')
    dialog.getClientRects = () => ({ length: 1 }) as DOMRectList
    document.body.append(dialog)
    await setup()
    await enter()
    expect(api.reserveHomeDateReminders).not.toHaveBeenCalled()
    dialog.remove()
    const input = document.createElement('textarea')
    document.body.append(input)
    input.focus()
    await enter()
    expect(api.reserveHomeDateReminders).not.toHaveBeenCalled()
    input.remove()
    await wrapper!.setProps({ idle: () => false })
    await enter()
    expect(api.reserveHomeDateReminders).not.toHaveBeenCalled()
    await wrapper!.setProps({ idle: () => true })
    await enter()
    expect(api.reserveHomeDateReminders).toHaveBeenCalledOnce()
  })
  it('悬停保留，离开后退场但不会冒充确认', async () => {
    await setup()
    await enter()
    await wrapper!.get('.date-message').trigger('pointerenter')
    await vi.advanceTimersByTimeAsync(20000)
    expect(wrapper!.find('.date-message').exists()).toBe(true)
    await wrapper!.get('.date-message').trigger('pointerleave')
    await vi.advanceTimersByTimeAsync(11000)
    expect(wrapper!.find('.date-message').exists()).toBe(false)
    expect(api.recordHomeDateReminder.mock.calls.map((call) => call[1])).toEqual(['SHOWN'])
  })
  it('确认失败留在原处可重试，日历跳转仍指向来源', async () => {
    const router = await setup()
    await enter()
    api.recordHomeDateReminder.mockRejectedValueOnce(new Error('offline'))
    await wrapper!
      .findAll('button')
      .find((button) => button.text() === '收下祝福')!
      .trigger('click')
    await flushPromises()
    expect(wrapper!.text()).toContain('确认还没有保存')
    await vi.advanceTimersByTimeAsync(20000)
    expect(wrapper!.find('.date-message').exists()).toBe(true)
    await wrapper!
      .findAll('button')
      .find((button) => button.text() === '看看日历 ↗')!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.importantDateId).toBe('id')
  })
  it('租约响应期间离开页面释放未展示记录', async () => {
    let resolve!: (value: unknown) => void
    api.reserveHomeDateReminders.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    await setup()
    await enter()
    await wrapper!.setProps({ blocked: true })
    resolve({ date: '2026-10-06', token: 'token', notices: [notice] })
    await flushPromises()
    expect(api.recordHomeDateReminder).toHaveBeenCalledWith(expect.anything(), 'RELEASED')
    expect(wrapper!.find('.date-message').exists()).toBe(false)
  })
  it('同日合成一轮，节气旁白不生成生日仪式', async () => {
    const term = {
      ...notice,
      key: 'term|2026-10-06|0',
      kind: 'SOLAR_TERM',
      title: '今天是寒露。',
      priority: 9,
    }
    api.reserveHomeDateReminders.mockResolvedValue({
      date: '2026-10-06',
      token: 'token',
      notices: [notice, term],
    })
    await setup()
    await enter()
    expect(wrapper!.findAll('.date-message')).toHaveLength(1)
    expect(wrapper!.text()).toContain('今天是寒露')
  })
})
