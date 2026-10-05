import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { Group, Mesh, SphereGeometry, MeshStandardMaterial } from 'three'
import HomeLivingCorner from '@/components/home/HomeLivingCorner.vue'
import { livingWeather, plainWeather } from '@/components/home/livingWeather'
import { livingNotification, safeLivingPath } from '@/components/home/livingNotifications'
import { createCompanionWardrobe, disposeWardrobe } from '@/components/home/companionWardrobe'
import {
  notificationSnapshot,
  publishNotificationSnapshot,
} from '@/composables/notificationSnapshot'
import type { WeatherDay } from '@/api/weather'
import type { NotificationCenterSnapshot, NotificationEvent } from '@/api/notificationCenter'

const now = Date.parse('2026-10-06T09:00:00+08:00')
const day: WeatherDay = { date: '2026-10-06', code: 3, low: 16, high: 22, rain: 0, wind: 5 }
const event: NotificationEvent = {
  id: 'task-completed:one',
  type: 'TASK_COMPLETED',
  title: '真实私有标题',
  detail: '详情',
  actorName: null,
  occurredAt: new Date(now - 60_000).toISOString(),
  targetPath: '/projects/p1?task=t1',
  severity: 'SUCCESS',
}
const snapshot: NotificationCenterSnapshot = {
  events: [event],
  unreadCount: 1,
  readAt: new Date(now - 120_000).toISOString(),
  loginUsage: [],
}

describe('每日预报与衣库', () => {
  it.each([undefined, { ...day, code: null }, { ...day, code: 999 }])(
    '缺失或未知天气不伪装晴天，不猜穿搭',
    (value) => {
      expect(livingWeather(value, true)).toEqual(plainWeather)
    },
  )
  it('过期失败预报回到普通陪伴，未知温度不猜冷热', () => {
    expect(livingWeather(day, false)).toEqual(plainWeather)
    expect(livingWeather({ ...day, code: 0, high: null }, true).clothes).toBe('plain')
  })
  it('雨和风可叠加，强风不撑伞，冷天加衣且穿搭稳定', () => {
    const rainy = livingWeather({ ...day, code: 61, high: 18, low: 12 }, true)
    expect(rainy).toMatchObject({ sky: 'rain', clothes: 'raincoat', umbrella: true })
    expect(livingWeather({ ...day, code: 61, wind: 39 }, true)).toMatchObject({
      sky: 'rain',
      windy: true,
      umbrella: false,
    })
    const cold = { ...day, code: 71, low: -2, high: 4 }
    expect(livingWeather(cold, true)).toMatchObject({ sky: 'snow', clothes: 'winter', scarf: true })
    expect(livingWeather(cold, true)).toEqual(livingWeather(cold, true))
    expect(livingWeather({ ...day, code: 0, low: 25, high: 31 }, true).clothes).toBe('light')
  })
  it('衣服沿身体裁出且不替换原模型，销毁只释放衣服', () => {
    const body = new Group(),
      shape = new Group()
    shape.name = 'companion-fur-body'
    const original = new Mesh(new SphereGeometry(1, 12, 10), new MeshStandardMaterial())
    shape.add(original)
    body.add(shape)
    const originalDispose = vi.spyOn(original.geometry, 'dispose')
    const wardrobe = createCompanionWardrobe(
      body,
      1,
      livingWeather({ ...day, low: 0, high: 4 }, true),
    )
    expect(body.getObjectByName('companion-clothes-winter')).toBeDefined()
    expect(body.getObjectByName('companion-scarf')).toBeDefined()
    const coat = wardrobe.getObjectByName('companion-clothes-winter') as Mesh
    const dispose = vi.spyOn(coat.geometry, 'dispose')
    disposeWardrobe(wardrobe)
    expect(body.children).toEqual([shape])
    expect(dispose).toHaveBeenCalledOnce()
    expect(originalDispose).not.toHaveBeenCalled()
    original.geometry.dispose()
    ;(original.material as MeshStandardMaterial).dispose()
  })
})

describe('通知选择边界', () => {
  it('只选近期未读、可内部跳转、非警告事件，不显示私有标题', () => {
    expect(livingNotification(snapshot, new Set(), now)?.id).toBe(event.id)
    for (const invalid of [
      { ...event, severity: 'WARNING' as const },
      { ...event, type: 'LOGIN' as const },
      { ...event, type: 'TASK_CREATED' as const },
      { ...event, targetPath: '//evil.example' },
      { ...event, occurredAt: 'bad' },
      { ...event, occurredAt: new Date(now + 1).toISOString() },
      { ...event, occurredAt: new Date(now - 25 * 60 * 60_000).toISOString() },
    ])
      expect(livingNotification({ ...snapshot, events: [invalid] }, new Set(), now)).toBeUndefined()
    expect(livingNotification(snapshot, new Set([event.id]), now)).toBeUndefined()
    expect(livingNotification({ ...snapshot, unreadCount: 0 }, new Set(), now)).toBeUndefined()
    expect(
      livingNotification({ ...snapshot, readAt: new Date(now).toISOString() }, new Set(), now),
    ).toBeUndefined()
    expect(safeLivingPath('/\\evil')).toBe(false)
  })
})

describe('街角日常排期与生命周期', () => {
  const wrappers: ReturnType<typeof mount>[] = []
  let idle = true
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(now)
    vi.spyOn(Math, 'random').mockReturnValue(0)
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    notificationSnapshot.value = null
    sessionStorage.clear()
    idle = true
  })
  afterEach(() => {
    wrappers.splice(0).forEach((w) => w.unmount())
    vi.useRealTimers()
    vi.restoreAllMocks()
    document.body.replaceChildren()
  })
  async function setup() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div/>' } },
        { path: '/projects/:id', component: { template: '<div/>' } },
      ],
    })
    await router.push('/')
    const wrapper = mount(HomeLivingCorner, {
      attachTo: document.body,
      props: {
        weather: livingWeather(day, true),
        member: 'me',
        date: day.date,
        quiet: false,
        blocked: false,
        idle: () => idle,
        anchors: () => [],
      },
      global: { plugins: [router] },
    })
    wrappers.push(wrapper)
    await flushPromises()
    return { wrapper, router }
  }
  const advance = async (ms: number) => {
    await vi.advanceTimersByTimeAsync(ms)
    await flushPromises()
  }
  it('首次静置后轮流聊天，输入立即收起，并等待新的安静窗口', async () => {
    const { wrapper } = await setup()
    await advance(24_000)
    expect(wrapper.find('.living-speech').exists()).toBe(false)
    await advance(1000)
    expect(wrapper.text()).toContain('小书虫')
    await advance(4000)
    expect(wrapper.text()).toContain('小青蛙')
    document.dispatchEvent(new Event('pointerdown'))
    await flushPromises()
    expect(wrapper.find('.living-speech').exists()).toBe(false)
    await advance(17_000)
    expect(wrapper.find('.living-speech').exists()).toBe(false)
  })
  it('安静、入场、后台和场景活动都不自动冒泡，卸载清理计时器', async () => {
    const { wrapper } = await setup()
    await wrapper.setProps({ quiet: true })
    await advance(90_000)
    expect(wrapper.find('.living-speech').exists()).toBe(false)
    await wrapper.setProps({ quiet: false, blocked: true })
    await advance(40_000)
    expect(wrapper.find('.living-speech').exists()).toBe(false)
    await wrapper.setProps({ blocked: false })
    idle = false
    await advance(40_000)
    expect(wrapper.find('.living-speech').exists()).toBe(false)
    idle = true
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange'))
    await advance(40_000)
    expect(wrapper.find('.living-speech').exists()).toBe(false)
    wrapper.unmount()
    wrappers.pop()
    expect(vi.getTimerCount()).toBe(0)
  })
  it('显示真实类别气泡，点击跳转而不修改已读；同成员同日不重播', async () => {
    publishNotificationSnapshot('me', snapshot)
    const { wrapper, router } = await setup()
    await advance(25_000)
    expect(wrapper.text()).toContain('有一项任务完成了')
    expect(wrapper.text()).not.toContain('真实私有标题')
    expect(JSON.parse(sessionStorage.getItem(`wao-home-living-seen:me:${day.date}`)!)).toEqual([
      event.id,
    ])
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe(event.targetPath)
    expect(snapshot.unreadCount).toBe(1)
    await advance(100_000)
    expect(wrapper.text()).not.toContain('有一项任务完成了')
  })
  it('已读水位推进收起当前通知，其他成员快照不会漏出', async () => {
    publishNotificationSnapshot('other', snapshot)
    const { wrapper } = await setup()
    await advance(25_000)
    expect(wrapper.text()).not.toContain('有一项任务完成了')
    await wrapper.setProps({ member: 'other' })
    await advance(25_000)
    expect(wrapper.text()).toContain('有一项任务完成了')
    publishNotificationSnapshot('other', {
      ...snapshot,
      readAt: new Date(now + 60_000).toISOString(),
      unreadCount: 0,
    })
    await flushPromises()
    expect(wrapper.find('.living-speech').exists()).toBe(false)
    await wrapper.setProps({ member: 'me' })
    await advance(25_000)
    expect(wrapper.text()).not.toContain('有一项任务完成了')
  })
  it('聚焦查看原文时保留气泡，路由失败可重试', async () => {
    publishNotificationSnapshot('me', snapshot)
    const { wrapper, router } = await setup()
    router.beforeEach(() => false)
    await advance(25_000)
    ;(wrapper.get('button').element as HTMLButtonElement).focus()
    await advance(10_000)
    expect(wrapper.find('button').exists()).toBe(true)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('重试打开')
    expect(router.currentRoute.value.path).toBe('/')
  })
  it('隐藏浮层不妨碍闲聊，实际打开的菜单会让位', async () => {
    const overlay = document.createElement('div')
    overlay.setAttribute('role', 'dialog')
    document.body.append(overlay)
    vi.spyOn(overlay, 'getClientRects').mockReturnValue([] as unknown as DOMRectList)
    const { wrapper } = await setup()
    await advance(25_000)
    expect(wrapper.find('.living-speech').exists()).toBe(true)
    vi.spyOn(overlay, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
    await advance(1000)
    expect(wrapper.find('.living-speech').exists()).toBe(false)
  })
  it('减少动态效果时不自动聊天；聚焦提醒也能用 Esc 收起', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    const { wrapper } = await setup()
    await advance(40_000)
    expect(wrapper.find('.living-speech').exists()).toBe(false)
    wrapper.unmount()
    wrappers.pop()
    vi.unstubAllGlobals()
    publishNotificationSnapshot('me', snapshot)
    const next = await setup()
    await advance(25_000)
    const button = next.wrapper.get('button')
    ;(button.element as HTMLButtonElement).focus()
    await button.trigger('keydown', { key: 'Escape' })
    expect(next.wrapper.find('.living-speech').exists()).toBe(false)
  })
})
