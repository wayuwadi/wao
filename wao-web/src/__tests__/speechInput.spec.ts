import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { useSpeechInput } from '@/composables/speechInput'
afterEach(() => vi.unstubAllGlobals())
it('仅最终转写追加到最新草稿，权限拒绝可重试且卸载停止麦克风', () => {
  let instance: FakeRecognition
  class FakeRecognition {
    lang = ''
    continuous = false
    interimResults = false
    onresult?: (event: unknown) => void
    onerror?: (event: unknown) => void
    onend?: () => void
    start = vi.fn()
    stop = vi.fn()
    abort = vi.fn()
    constructor() {
      instance = this
    }
  }
  vi.stubGlobal('SpeechRecognition', FakeRecognition)
  const append = vi.fn()
  let speech!: ReturnType<typeof useSpeechInput>
  const wrapper = mount(
    defineComponent({
      setup() {
        speech = useSpeechInput(append)
        return () => null
      },
    }),
  )
  speech.toggle()
  expect(instance!.lang).toBe('zh-CN')
  instance!.onresult!({
    resultIndex: 0,
    results: [
      { isFinal: false, 0: { transcript: '中间结果' } },
      { isFinal: true, 0: { transcript: '真实识别结果' } },
    ],
  })
  expect(append).toHaveBeenCalledExactlyOnceWith('真实识别结果')
  instance!.onerror!({ error: 'not-allowed' })
  expect(speech.error.value).toContain('未获授权')
  speech.toggle()
  expect(speech.error.value).toBe('')
  wrapper.unmount()
  expect(instance!.abort).toHaveBeenCalledOnce()
})
describe('能力探测', () => {
  it('无真实识别接口时不提供语音入口', () => {
    vi.stubGlobal('SpeechRecognition', undefined)
    vi.stubGlobal('webkitSpeechRecognition', undefined)
    let speech!: ReturnType<typeof useSpeechInput>
    const wrapper = mount(
      defineComponent({
        setup() {
          speech = useSpeechInput(vi.fn())
          return () => null
        },
      }),
    )
    expect(speech.supported).toBe(false)
    wrapper.unmount()
  })
})

it('取消后丢弃已排队的旧语音回调，允许新会话继续输入', () => {
  let instance: FakeRecognition
  class FakeRecognition {
    lang = ''
    continuous = false
    interimResults = false
    onresult?: (event: unknown) => void
    onerror?: (event: unknown) => void
    onend?: () => void
    start = vi.fn()
    stop = vi.fn()
    abort = vi.fn()
    constructor() {
      instance = this
    }
  }
  vi.stubGlobal('SpeechRecognition', FakeRecognition)
  const append = vi.fn()
  let speech!: ReturnType<typeof useSpeechInput>
  const wrapper = mount(
    defineComponent({
      setup() {
        speech = useSpeechInput(append)
        return () => null
      },
    }),
  )
  speech.toggle()
  const oldResult = instance!.onresult!
  const oldError = instance!.onerror!
  speech.cancel()
  expect(instance!.abort).toHaveBeenCalledOnce()
  speech.toggle()
  oldResult({ resultIndex: 0, results: [{ isFinal: true, 0: { transcript: '旧账号内容' } }] })
  oldError({ error: 'not-allowed' })
  expect(append).not.toHaveBeenCalled()
  expect(speech.listening.value).toBe(true)
  expect(speech.error.value).toBe('')
  wrapper.unmount()
})
