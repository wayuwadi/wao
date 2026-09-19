import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DesktopPetView from '@/views/DesktopPetView.vue'
import { useSessionStore } from '@/stores/session'
import type { AuthSession } from '@/api/auth'
const api = vi.hoisted(() => ({ account: vi.fn(), messages: vi.fn(), tasks: vi.fn(), poll: async () => {} }))
vi.mock('@/api/auth', () => ({ getSession: api.account }))
vi.mock('@/api/chat', () => ({ fetchSharedChatMessages: api.messages }))
vi.mock('@/api/board', () => ({ fetchTasks: api.tasks }))
vi.mock('@/composables/visibilityPolling', () => ({ createVisibilityPoller: (poll: () => Promise<void>) => { api.poll = poll; return { start: vi.fn(), stop: vi.fn() } } }))
let member = 0
beforeEach(() => {
 setActivePinia(createPinia())
 vi.useFakeTimers()
 vi.setSystemTime(new Date('2026-09-19T08:00:00Z'))
 vi.clearAllMocks()
 localStorage.clear()
 const account = { member: { id: `desktop-notice-${++member}` } } as AuthSession
 useSessionStore().account = account
 api.account.mockResolvedValue(account)
 api.messages.mockResolvedValue([])
 api.tasks.mockResolvedValue([{ id: 'task1', status: '待验证' }])
 vi.stubGlobal('location', { search: '?native=3', href: '' })
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })
const render = () => mount(DesktopPetView, { global: { stubs: { PetSprite: true, PetMessenger: true } } })
describe('桌面宠物任务提示失效处理', () => {
 it('会话失效立即收掉任务提示，显示登录入口', async () => {
  const wrapper = render()
  await api.poll(); await flushPromises()
  expect(wrapper.text()).toContain('有任务等你验收')
  api.account.mockRejectedValueOnce({ response: { status: 401 } })
  await api.poll(); await flushPromises()
  expect(wrapper.text()).not.toContain('有任务等你验收')
  expect(wrapper.text()).toContain('打开 WAO 登录')
  expect(location.href).toBe('/__desktop_pet__/bubble')
  wrapper.unmount()
 })
 it('点击任务后清除气泡并只请求固定任务入口', async () => {
  const wrapper = render()
  await api.poll(); await flushPromises()
  await wrapper.get('.pet-login').trigger('click')
  expect(wrapper.find('.pet-login').exists()).toBe(false)
  expect(location.href).toBe('/__desktop_pet__/task/task1')
  wrapper.unmount()
 })
 it('聊天未读时不消费任务提示，消息已读后才显示', async () => {
  api.messages.mockResolvedValueOnce([{ id: 'message1', read: false }])
  const wrapper = render()
  await api.poll(); await flushPromises()
  expect(wrapper.text()).not.toContain('有任务等你验收')
  await api.poll(); await flushPromises()
  expect(wrapper.text()).toContain('有任务等你验收')
  wrapper.unmount()
 })
 it('请求暂时失败清除旧任务气泡，下次轮询重新读取任务', async () => {
  const wrapper = render()
  await api.poll(); await flushPromises()
  expect(wrapper.text()).toContain('有任务等你验收')
  api.messages.mockRejectedValueOnce(new Error('网络暂时不可用'))
  await api.poll(); await flushPromises()
  expect(wrapper.find('.pet-login').exists()).toBe(false)
  expect(useSessionStore().authed).toBe(true)
  await api.poll(); await flushPromises()
  expect(api.tasks).toHaveBeenCalledTimes(2)
  expect(wrapper.find('.pet-login').exists()).toBe(false)
  wrapper.unmount()
 })
 it('成员切换清除旧气泡并立即读取新成员任务', async () => {
  const wrapper = render()
  await api.poll(); await flushPromises()
  expect(wrapper.text()).toContain('有任务等你验收')
  api.account.mockResolvedValueOnce({ member: { id: `desktop-other-${member}` } })
  api.tasks.mockResolvedValueOnce([])
  await api.poll(); await flushPromises()
  expect(api.tasks).toHaveBeenCalledTimes(2)
  expect(wrapper.find('.pet-login').exists()).toBe(false)
  expect(useSessionStore().userId).toBe(`desktop-other-${member}`)
  wrapper.unmount()
 })
})
