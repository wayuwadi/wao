import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchDiscoveryCurrent, discoveryHttpStatus, type Discovery } from '@/api/noteDiscoveries'

/** 发现刷新与编辑器、草稿完全分离，结果只在安全位置进入列表。 */
export function useNoteDiscoveries(member: () => string, ready: () => boolean, safe: () => boolean, preservePosition: () => void = () => {}) {
  const current = ref<Discovery | null>(null)
  const pendingCurrent = ref<Discovery | null | undefined>()
  const loadError = ref('')
  const supported = ref(true)
  let generation = 0, request = 0, timer: ReturnType<typeof setInterval> | undefined
  async function refresh() {
    if (!ready() || document.hidden || !supported.value) return
    const mine = generation, sequence = ++request
    try {
      const result = await fetchDiscoveryCurrent()
      if (mine !== generation || sequence !== request) return
      loadError.value = ''
      const next = result.discovery
      // 撤销权限或正文更新必须立即去掉摘录，不能为了布局保留敏感材料。
      if (!next) { if (current.value) preservePosition(); current.value = null; pendingCurrent.value = undefined }
      else if (current.value?.id === next.id) current.value = next
      else if (safe()) { current.value = next; pendingCurrent.value = undefined }
      else pendingCurrent.value = next
    } catch (error) {
      if (mine !== generation || sequence !== request) return
      if ([404, 410].includes(discoveryHttpStatus(error) ?? 0)) { supported.value = false; current.value = null }
      else loadError.value = '顺手发现读取失败'
    }
  }
  function applyPending() {
    if (pendingCurrent.value !== undefined && safe()) { current.value = pendingCurrent.value; pendingCurrent.value = undefined }
  }
  watch(member, () => { generation++; current.value = null; pendingCurrent.value = undefined; supported.value = true; void refresh() })
  watch(ready, value => { if (value) void refresh() })
  onMounted(() => { void refresh(); timer = setInterval(() => { applyPending(); void refresh() }, 60_000) })
  onBeforeUnmount(() => { generation++; if (timer) clearInterval(timer) })
  return { current, pendingCurrent, loadError, supported, refresh, applyPending }
}
