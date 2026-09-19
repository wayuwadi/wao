import { onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { getDocumentSyncStates, type DocumentSyncState } from '@/api/weknoraSync'

/** One bounded request for the visible list; never writes document content or local drafts. */
export function useWeKnoraDocuments(ids: () => string[], revision: () => unknown) {
  const states = ref<Record<string, DocumentSyncState>>({})
  const error = ref('')
  let timer: ReturnType<typeof setTimeout> | undefined
  let active = true, disposed = false, generation = 0, inFlight = false
  function schedule(delay: number) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => void refresh(), delay)
  }
  async function refresh() {
    if (timer) clearTimeout(timer)
    if (disposed || !active || document.hidden) return
    if (inFlight) { schedule(1000); return }
    const requested = [...new Set(ids())]
    if (!requested.length) { states.value = {}; return }
    const current = generation
    inFlight = true
    try {
      const result: DocumentSyncState[] = []
      for (let offset = 0; offset < requested.length; offset += 100) {
        result.push(...await getDocumentSyncStates(requested.slice(offset, offset + 100)))
        if (disposed || current !== generation) return
      }
      if (disposed || current !== generation) return
      states.value = Object.fromEntries(result.map(item => [item.id, item]))
      for (const id of requested) {
        states.value[id] ??= { id, state: 'unavailable', message: '文档已删除或当前账号无权访问', sourceUrl: '', knowledgeUrl: '' }
      }
      error.value = ''
    } catch {
      if (!disposed && current === generation) { error.value = '同步状态读取失败'; states.value = {} }
    } finally {
      inFlight = false
      if (!disposed && active && !document.hidden) schedule(current === generation ? 15000 : 0)
    }
  }
  watch(() => JSON.stringify([ids(), revision()]), () => {
    generation++
    states.value = {}
    error.value = ''
    void refresh()
  })
  function visibility() { if (!document.hidden) void refresh(); else { generation++; if (timer) clearTimeout(timer) } }
  onMounted(() => { document.addEventListener('visibilitychange', visibility); void refresh() })
  onDeactivated(() => { active = false; generation++; if (timer) clearTimeout(timer) })
  onActivated(() => { active = true; void refresh() })
  onUnmounted(() => { disposed = true; generation++; if (timer) clearTimeout(timer); document.removeEventListener('visibilitychange', visibility) })
  return { states, error, refresh }
}
