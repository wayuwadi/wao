import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toast } from '@/composables/toast'

/** 等真实小记加载后消费一次更新体验入口；保留草稿、筛选及其他路由参数。 */
export function useReleaseNoteExperience(options: {
  loadState: () => string
  openSettings: () => void
  read: () => boolean | Promise<boolean>
  ai: () => boolean | Promise<boolean>
}) {
  const route = useRoute(), router = useRouter(), mounted = ref(false)
  let working = false
  onMounted(() => { mounted.value = true })
  onBeforeUnmount(() => { mounted.value = false })
  async function consume() {
    const action = route.query.releaseAction
    if (!mounted.value || working || !['settings', 'reading', 'ai'].includes(String(action))) return
    if (action !== 'settings' && options.loadState() !== 'loaded') return
    working = true
    const request = route.query.releaseRequest
    try {
      // 先消费路由意图，再打开弹层；不自动进入编辑、不写数据、不运行 AI。
      const query = { ...route.query }
      delete query.releaseAction; delete query.releaseRequest
      await router.replace({ path: route.path, query, hash: route.hash })
      await nextTick()
      if (!mounted.value || route.path !== '/notes') return
      if (action === 'settings') options.openSettings()
      else if (!(await (action === 'reading' ? options.read() : options.ai()))) {
        toast(action === 'reading' ? '还没有可阅读的小记，先写一条后再体验阅读。' : '还没有本人的小记，先写一条后再查看 AI 成果。', 'ri-information-line')
      }
    } finally {
      working = false
      // 操作期间抵达的下一次体验请求由更新的 query 触发，不能覆盖它。
      if (mounted.value && route.query.releaseRequest && route.query.releaseRequest !== request) void consume()
    }
  }
  watch([() => route.query.releaseAction, () => route.query.releaseRequest, options.loadState, mounted], consume, { immediate: true })
}
