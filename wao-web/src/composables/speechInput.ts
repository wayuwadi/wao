import { onBeforeUnmount, ref } from 'vue'

type Recognition = {
  lang: string
  continuous: boolean
  interimResults: boolean
  onresult: ((event: { resultIndex: number; results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }> }) => void) | null
  onerror: ((event: { error: string }) => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
  abort(): void
}

/** 使用宿主真实语音服务，只追加最终转写；从不自动发送消息。 */
export function useSpeechInput(append: (text: string) => void) {
  const host = window as Window & {
    SpeechRecognition?: new () => Recognition
    webkitSpeechRecognition?: new () => Recognition
  }
  const Constructor = host.SpeechRecognition ?? host.webkitSpeechRecognition
  const supported = !!Constructor
  const listening = ref(false)
  const error = ref('')
  let recognition: Recognition | undefined
  function stop() { recognition?.stop() }
  function toggle() {
    if (listening.value) { stop(); return }
    if (!Constructor) return
    error.value = ''
    const current = new Constructor()
    recognition = current
    current.lang = 'zh-CN'
    current.continuous = false
    current.interimResults = false
    current.onresult = (event) => {
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i]
        if (result?.isFinal) append(result[0].transcript)
      }
    }
    current.onerror = (event) => {
      error.value = event.error === 'not-allowed' ? '麦克风未获授权，可在浏览器设置中允许后重试'
        : event.error === 'no-speech' ? '没有识别到语音，请再试一次'
          : '语音识别服务暂不可用，请重试或继续键盘输入'
      listening.value = false
    }
    current.onend = () => { listening.value = false }
    try { listening.value = true; current.start() }
    catch { listening.value = false; error.value = '语音识别未能启动，请重试' }
  }
  onBeforeUnmount(() => {
    if (recognition) {
      recognition.onresult = null
      recognition.onerror = null
      recognition.onend = null
      recognition.abort()
    }
  })
  return { supported, listening, error, toggle, stop }
}
