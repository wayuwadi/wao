import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import NotificationCenter from '@/components/notifications/NotificationCenter.vue'
import type { NotificationCenterSnapshot } from '@/api/notificationCenter'

const api = vi.hoisted(() => ({
  fetch: vi.fn(),
  markRead: vi.fn(),
}))

vi.mock('@/api/notificationCenter', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/notificationCenter')>()
  return {
    ...actual,
    fetchNotificationCenter: api.fetch,
    markNotificationCenterRead: api.markRead,
  }
})

const sample: NotificationCenterSnapshot = {
  unreadCount: 4,
  readAt: null,
  events: [
    {
      id: 'task-created:t1',
      type: 'TASK_CREATED',
      title: '建设通知中心',
      detail: '创建了任务',
      actorName: '娃哥',
      occurredAt: '2026-08-28T10:00:00+08:00',
      targetPath: '/projects/p1?task=t1',
      severity: 'INFO',
    },
    {
      id: 'login:l1',
      type: 'LOGIN',
      title: '娃弟登录了 WAO',
      detail: '通过飞书登录',
      actorName: '娃弟',
      occurredAt: '2026-08-28T09:00:00+08:00',
      targetPath: null,
      severity: 'INFO',
    },
  ],
  loginUsage: [
    {
      memberId: 'wg',
      memberName: '娃哥',
      color: '#4358D6',
      count7Days: 3,
      count30Days: 8,
      lastLoginAt: '2026-08-28T10:00:00+08:00',
    },
    {
      memberId: 'wd',
      memberName: '娃弟',
      color: '#2C8A5B',
      count7Days: 0,
      count30Days: 0,
      lastLoginAt: null,
    },
  ],
}

async function mounted() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/projects/:id', component: { template: '<div />' } },
      { path: '/dashboard', component: { template: '<div />' } },
      { path: '/system-reports', component: { template: '<div />' } },
      { path: '/request-monitoring', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  return {
    router,
    wrapper: mount(NotificationCenter, {
      global: { plugins: [router], stubs: { Teleport: true } },
    }),
  }
}

describe('右上角通知中心', () => {
  beforeEach(() => {
    api.fetch.mockReset().mockResolvedValue(sample)
    api.markRead.mockReset().mockResolvedValue('2026-08-28T10:01:00+08:00')
  })

  it('后台读取真实未读数，打开后展示动态并持久化当前成员已读', async () => {
    const { wrapper } = await mounted()
    await flushPromises()

    expect(wrapper.find('.notification-badge').text()).toBe('4')
    await wrapper.find('.notification-trigger').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('建设通知中心')
    expect(wrapper.text()).toContain('娃哥 · 创建了任务')
    expect(wrapper.text()).toContain('娃弟登录了 WAO')
    expect(api.markRead).toHaveBeenCalledOnce()
    expect(wrapper.find('.notification-badge').exists()).toBe(false)
    wrapper.unmount()
  })

  it('成员使用显示 7/30 天真实次数，并明确没有历史记录的边界', async () => {
    const { wrapper } = await mounted()
    await flushPromises()
    await wrapper.find('.notification-trigger').trigger('click')
    await flushPromises()
    await wrapper.findAll('.notification-tabs > button')[1]!.trigger('click')

    const rows = wrapper.findAll('.usage-row')
    expect(rows[0]!.text()).toContain('娃哥')
    expect(rows[0]!.text()).toContain('3近 7 天')
    expect(rows[0]!.text()).toContain('8近 30 天')
    expect(rows[1]!.text()).toContain('尚未记录')
    expect(wrapper.text()).toContain('历史次数不会推测补齐')
    wrapper.unmount()
  })

  it('读取失败时不伪造内容，并可原地重试恢复', async () => {
    api.fetch
      .mockReset()
      .mockRejectedValueOnce(new Error('网络暂时不可用'))
      .mockRejectedValueOnce(new Error('网络暂时不可用'))
      .mockResolvedValue(sample)
    const { wrapper } = await mounted()
    await flushPromises()

    await wrapper.find('.notification-trigger').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('通知中心暂时不可用')
    expect(wrapper.text()).toContain('网络暂时不可用')
    await wrapper.find('.notification-state.error button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('建设通知中心')
    wrapper.unmount()
  })

  it('报表入口沿用现有路由，事件入口回到对应任务', async () => {
    const { wrapper, router } = await mounted()
    await flushPromises()
    await wrapper.find('.notification-trigger').trigger('click')
    await flushPromises()

    await wrapper.findAll('.report-links button')[0]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/dashboard')

    await wrapper.find('.notification-trigger').trigger('click')
    await flushPromises()
    await wrapper.find('.event-row.clickable').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/projects/p1?task=t1')
    wrapper.unmount()
  })

  it('Escape 可关闭已经就位的抽屉', async () => {
    const { wrapper } = await mounted()
    await flushPromises()
    await wrapper.find('.notification-trigger').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
