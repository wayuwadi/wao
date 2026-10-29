import { computed, nextTick, onMounted, ref, toValue, watch, type MaybeRefOrGetter, type Ref } from 'vue'
import { useSessionStore } from '@/stores/session'

/** Navigation state belongs to this tab and account, never to a shared global preference. */
export function useTaskViewMemory<T extends object>(scope: MaybeRefOrGetter<string>, defaults: () => T) {
  const session = useSessionStore()
  const key = computed(() => `wao-task-view-v1:${session.account?.accountId ?? ''}:${session.userId}:${toValue(scope)}`)
  const state = ref(defaults()) as Ref<T>
  watch(key, () => {
    let saved: Partial<T> = {}
    try { saved = JSON.parse(sessionStorage.getItem(key.value) || '{}') } catch { /* disposable navigation memory */ }
    const clean = defaults()
    for (const field of Object.keys(clean) as (keyof T)[]) {
      const value=saved?.[field], fallback=clean[field]
      if (value != null && typeof value === typeof fallback && Array.isArray(value) === Array.isArray(fallback)) clean[field]=value as T[keyof T]
    }
    state.value = clean
  }, { immediate: true, flush: 'sync' })
  watch(state, value => { try { sessionStorage.setItem(key.value, JSON.stringify(value)) } catch { /* navigation stays usable */ } }, { deep: true, flush: 'sync' })
  return state
}

export function useTaskScrollMemory(root: Ref<HTMLElement | null>, scope: MaybeRefOrGetter<string>, content: () => unknown) {
  const memory = useTaskViewMemory(scope, () => ({ positions: {} as Record<string, number> }))
  const restored = new Map<string, HTMLElement>()
  const elementKeys = new WeakMap<HTMLElement, string>()
  let restoring = false
  async function restore() {
    await nextTick()
    restoring = true
    for (const element of root.value?.querySelectorAll<HTMLElement>('[data-task-scroll]') ?? []) {
      const key = element.dataset.taskScroll!
      if (restored.get(key) === element && elementKeys.get(element) === key) continue
      const value = memory.value.positions[key] ?? 0
      element.scrollTop = value
      elementKeys.set(element,key)
      if (element.scrollTop >= value) restored.set(key,element)
    }
    restoring = false
  }
  function remember(event: Event) {
    if (restoring || !(event.target instanceof HTMLElement)) return
    const key = event.target.dataset.taskScroll
    if (!key) return
    restored.set(key,event.target)
    elementKeys.set(event.target,key)
    memory.value.positions[key] = event.target.scrollTop
  }
  watch(() => toValue(scope), () => { restored.clear(); void restore() }, { flush: 'post' })
  watch(content, () => { void restore() }, { flush: 'post' })
  onMounted(restore)
  return remember
}
