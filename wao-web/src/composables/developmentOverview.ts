import { onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { fetchDevelopmentOverview, type DevelopmentOverview } from '@/api/development'
import { useSessionStore } from '@/stores/session'
import { usePageVisibility } from './pageVisibility'
import { createVisibilityPoller } from './visibilityPolling'

/** 工作台、看板和伙伴提示共用真实概览读取与可见页签串行轮询。 */
export function useDevelopmentOverview(
  options: {
    canRefresh?: () => boolean
    beforeApply?: (next: DevelopmentOverview, previous: DevelopmentOverview | null) => void
  } = {},
) {
  const session = useSessionStore()
  const visible = usePageVisibility()
  const overview = ref<DevelopmentOverview | null>(null)
  const loading = ref(false)
  const loadError = ref('')
  let revision = 0
  let disposed = false
  let active = true
  function invalidate() {
    revision++
    loading.value = false
  }
  async function refresh() {
    if (disposed || !active || loading.value || !visible.value || options.canRefresh?.() === false)
      return
    const request = ++revision
    const member = session.currentUser.id
    loading.value = true
    try {
      const next = await fetchDevelopmentOverview()
      if (disposed || request !== revision || member !== session.currentUser.id) return
      options.beforeApply?.(next, overview.value)
      overview.value = next
      loadError.value = ''
    } catch (error) {
      if (!disposed && request === revision && member === session.currentUser.id)
        loadError.value = error instanceof Error ? error.message : '读取自主建设记录失败，请重试。'
    } finally {
      if (request === revision) loading.value = false
    }
  }
  const poller = createVisibilityPoller(refresh, () => 10_000)
  watch(visible, (value) => {
    if (value) void poller.trigger()
  })
  watch(
    () => session.currentUser.id,
    () => {
      invalidate()
      overview.value = null
      loadError.value = ''
      void refresh()
    },
  )
  onMounted(() => poller.start())
  onActivated(() => {
    active = true
    poller.start()
  })
  onDeactivated(() => {
    active = false
    poller.stop()
  })
  onBeforeUnmount(() => {
    disposed = true
    invalidate()
    poller.stop()
  })
  return { overview, loading, loadError, refresh, invalidate }
}
