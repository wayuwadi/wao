/**
 * 文档协同在场（见 ADR-017）。
 *
 * 每隔 {@link BEAT_MS} 向后端报一次「我还开着这篇文档、光标在第几行」，同一次请求把
 * 服务端的最新版本号带回来：别人改过就顺带取回整篇正文，没改就只回一个号，
 * 心跳因此既是在场信号也是内容同步的问询，一次往返干两件事。
 *
 * 页面不可见时不发心跳（后台标签页别空转），重新可见立刻补一次；离开文档会主动
 * 销号，让协作者那边的头像马上消失，销不掉也没关系 —— 后端的在场窗口会兜住。
 */
import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { apiDocPresence, apiLeaveDoc } from '@/api/board'
import { useDataStore } from '@/stores/data'
import { usePageVisibility } from '@/composables/pageVisibility'
import type { DocPeer } from '@/types'

/** 心跳间隔：够快到「看得见对方在动」，又不至于把请求打成机关枪。 */
const BEAT_MS = 5_000

export function useDocPresence(docId: Ref<string | null>) {
  const data = useDataStore()
  const pageVisible = usePageVisibility()
  /** 此刻也开着这篇文档的其他人。 */
  const peers = ref<DocPeer[]>([])
  /** 自己的光标落在第几个顶层块上。 */
  const caretLine = ref(-1)
  /** 上一个心跳周期内自己有没有输入 —— 让协作者看到「他正在敲这一行」。 */
  let typed = false
  let timer: ReturnType<typeof setInterval> | null = null
  let inflight = false

  function markTyping() {
    typed = true
  }
  function setCaretLine(line: number) {
    caretLine.value = line
  }

  async function beat() {
    const id = docId.value
    if (!id || !data.apiLive || inflight || document.hidden || !pageVisible.value) return
    inflight = true
    const wasTyping = typed
    typed = false
    try {
      const res = await apiDocPresence(id, {
        line: caretLine.value,
        editing: wasTyping,
        rev: data.docById(id)?.rev ?? 0,
      })
      // 心跳可能比切换文档慢一步，回来时已经不是同一篇了就整个丢掉
      if (docId.value !== id) return
      peers.value = res.peers
      if (res.doc) data.applyRemoteDoc(res.doc)
    } catch {
      /* 网络抖动时静默跳过，下个周期重试 */
    } finally {
      inflight = false
    }
  }

  function start() {
    if (timer || !pageVisible.value) return
    timer = setInterval(beat, BEAT_MS)
    document.addEventListener('visibilitychange', beat)
  }
  function stop() {
    if (timer) clearInterval(timer)
    timer = null
    document.removeEventListener('visibilitychange', beat)
  }

  function leave(id: string | null) {
    if (id && data.apiLive) apiLeaveDoc(id).catch(() => {})
  }

  watch(
    docId,
    (next, prev) => {
      if (prev && prev !== next) leave(prev)
      peers.value = []
      caretLine.value = -1
      typed = false
      if (next) {
        void beat()
        start()
      } else {
        stop()
      }
    },
    { immediate: true },
  )

  watch(pageVisible, (visible) => {
    if (visible && docId.value) {
      void beat()
      start()
    } else stop()
  })

  onBeforeUnmount(() => {
    stop()
    leave(docId.value)
  })

  return { peers, caretLine, setCaretLine, markTyping, beat }
}
