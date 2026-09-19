import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import WaoWebPet from '@/components/assistant/WaoWebPet.vue'
import { useWebPetStore } from '@/stores/webPet'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { fetchPreference } from '@/api/favorites'
import type { AuthSession } from '@/api/auth'
import type { Task } from '@/types'
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
const task = (id: string, status = '待验证') => ({ id, status }) as Task
const render = () => mount(WaoWebPet, { props: { assistantStatus: { configured: true, message: '' } }, global: { stubs: { PetSprite: true, PetMessenger: true } } })
let memberSequence = 0
const storageKey = () => `wao-pet-task-states-v2:${useSessionStore().userId}`
beforeEach(async () => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  localStorage.clear()
  vi.resetAllMocks()
  useSessionStore().account = { member: { id: `notice-member-${++memberSequence}` } } as AuthSession
  useDataStore().tasks = [task('notice-task')]
  useDataStore().taskLoadState = 'loaded'
  vi.mocked(fetchPreference).mockResolvedValue('{}')
  await vi.advanceTimersByTimeAsync(1000)
})
afterEach(() => vi.useRealTimers())
describe('任务提示状态生命周期', () => {
  it('偏好尚未读取或关闭时不提前提示，也不消费任务状态', async () => {
    let finish!: (value: string) => void
    vi.mocked(fetchPreference).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = render()
    expect(wrapper.find('.pet-notice').exists()).toBe(false)
    expect(localStorage.getItem(storageKey())).toBeNull()
    finish('{"showTaskNotices":false}')
    await flushPromises()
    expect(wrapper.find('.pet-notice').exists()).toBe(false)
    const backgroundTimers = vi.getTimerCount()
    useWebPetStore().settings.showTaskNotices = true
    await flushPromises()
    expect(wrapper.find('.pet-notice').exists()).toBe(true)
    useWebPetStore().settings.showTaskNotices = false
    await flushPromises()
    expect(wrapper.find('.pet-notice').exists()).toBe(false)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(0)
    expect(vi.getTimerCount()).toBe(backgroundTimers)
  })
  it.each(['已完成', 'deleted', 'error'])('任务变为 %s 时立即清除过时气泡', async state => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.find('.pet-notice').exists()).toBe(true)
    if (state === 'error') useDataStore().taskLoadState = 'error'
    else useDataStore().tasks = state === 'deleted' ? [] : [task('notice-task', state)]
    await flushPromises()
    expect(wrapper.find('.pet-notice').exists()).toBe(false)
    wrapper.unmount()
  })
  it('聊天优先时不提前消费任务提醒，聊天关闭后才出现且八秒消失', async () => {
    const ui = useUiStore()
    ui.chatOpen = true
    const wrapper = render()
    await flushPromises()
    expect(localStorage.getItem(storageKey())).toBeNull()
    ui.chatOpen = false
    await flushPromises()
    expect(wrapper.find('.pet-notice').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(8000)
    expect(wrapper.find('.pet-notice').exists()).toBe(false)
    ui.chatOpen = true
    await flushPromises()
    ui.chatOpen = false
    await flushPromises()
    expect(wrapper.find('.pet-notice').exists()).toBe(false)
    wrapper.unmount()
  })
})
