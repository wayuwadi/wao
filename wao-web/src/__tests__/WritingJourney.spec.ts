import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import WritingEntryTransition from '@/components/home/WritingEntryTransition.vue'
import {
  completeWritingJourney,
  requestWritingJourney,
  writingFocusPending,
  writingJourneyRequested,
  writingJourneys,
} from '@/composables/writingJourney'
import { restJourneyRequested } from '@/composables/restJourney'

const push = vi.fn()
const currentRoute = ref({ path: '/', fullPath: '/' })
vi.mock('vue-router', async (original) => ({
  ...(await original<object>()),
  useRouter: () => ({ push, currentRoute, afterEach: () => vi.fn() }),
}))
let wrapper: VueWrapper | undefined
let frameCallback: FrameRequestCallback | undefined
let time = 0
async function advance(ms: number) {
  for (let n = 0; n < ms; n += 100) {
    time += 100
    frameCallback?.(time)
  }
  await flushPromises()
}
beforeEach(() => {
  time = 0
  frameCallback = undefined
  vi.spyOn(performance, 'now').mockImplementation(() => time)
  vi.stubGlobal(
    'requestAnimationFrame',
    vi.fn((callback) => {
      frameCallback = callback
      return 1
    }),
  )
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: false })),
  )
  currentRoute.value = { path: '/', fullPath: '/' }
  push.mockReset()
  push.mockImplementation(async () => {
    currentRoute.value = { path: '/notes', fullPath: '/notes' }
  })
  writingFocusPending.value = 0
  restJourneyRequested.value = false
  completeWritingJourney()
  document.body.innerHTML = '<div id="app"></div>'
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  completeWritingJourney()
  writingFocusPending.value = 0
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
  document.body.style.overflow = ''
})

describe('首页写作入场', () => {
  it('随机覆盖三种且不会连续重复，阻止双击及休息动画竞争', () => {
    const seen = new Set<string>()
    for (const random of [0, 0, 0.99, 0, 0, 0.99]) {
      vi.spyOn(Math, 'random').mockReturnValue(random)
      const previous = writingJourneyRequested.value
      completeWritingJourney()
      requestWritingJourney()
      expect(writingJourneyRequested.value).not.toEqual(previous)
      seen.add(writingJourneyRequested.value!.id)
      const selected = writingJourneyRequested.value
      requestWritingJourney()
      expect(writingJourneyRequested.value).toEqual(selected)
    }
    expect(seen.size).toBe(3)
    completeWritingJourney()
    restJourneyRequested.value = true
    requestWritingJourney()
    expect(writingJourneyRequested.value).toBeNull()
  })
  it.each(writingJourneys)('$name 自动抵达小记并请求一次焦点', async (journey) => {
    wrapper = mount(WritingEntryTransition, { props: { journey }, attachTo: document.body })
    await flushPromises()
    await advance(6600)
    expect(push).toHaveBeenCalledExactlyOnceWith('/notes')
    expect(writingFocusPending.value).toBeGreaterThan(0)
  })
  it('接住念头后提前抵达，Esc 和跳过不重复导航', async () => {
    wrapper = mount(WritingEntryTransition, {
      props: { journey: writingJourneys[2] },
      attachTo: document.body,
    })
    await flushPromises()
    await advance(3300)
    document.querySelector<HTMLButtonElement>('[aria-label="接住这个小念头"]')!.click()
    await flushPromises()
    expect(document.body.textContent).toContain('你接住了！')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    document.querySelector<HTMLButtonElement>('.writing-skip')!.click()
    await flushPromises()
    expect(push).toHaveBeenCalledTimes(1)
  })
  it('失败保留返回与重试，成功前不抢编辑焦点，卸载恢复背景', async () => {
    push.mockRejectedValueOnce(new Error('offline'))
    const app = document.getElementById('app')!
    app.inert = false
    wrapper = mount(WritingEntryTransition, {
      props: { journey: writingJourneys[0] },
      attachTo: document.body,
    })
    await flushPromises()
    expect(app.inert).toBe(true)
    document.querySelector<HTMLButtonElement>('.writing-skip')!.click()
    await flushPromises()
    expect(document.body.textContent).toContain('重试进入小记')
    expect(writingFocusPending.value).toBe(0)
    document.querySelector<HTMLButtonElement>('.writing-skip')!.click()
    await flushPromises()
    expect(writingFocusPending.value).toBeGreaterThan(0)
    wrapper.unmount()
    wrapper = undefined
    expect(app.inert).toBe(false)
    expect(document.body.style.overflow).toBe('')
  })
  it('减少动态效果时直接打开编辑器', async () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: true })),
    )
    wrapper = mount(WritingEntryTransition, {
      props: { journey: writingJourneys[1] },
      attachTo: document.body,
    })
    await flushPromises()
    expect(push).toHaveBeenCalledExactlyOnceWith('/notes')
    expect(requestAnimationFrame).not.toHaveBeenCalled()
  })
  it('隐藏时暂停，回到页面后才继续并抵达', async () => {
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    wrapper = mount(WritingEntryTransition, { props: { journey: writingJourneys[0] }, attachTo: document.body })
    await flushPromises(); await advance(2000)
    hidden.mockReturnValue(true); document.dispatchEvent(new Event('visibilitychange'))
    await advance(8000); expect(push).not.toHaveBeenCalled()
    hidden.mockReturnValue(false); document.dispatchEvent(new Event('visibilitychange'))
    await advance(4700); expect(push).toHaveBeenCalledExactlyOnceWith('/notes')
  })
})
