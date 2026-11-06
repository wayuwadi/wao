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

export function useTaskScrollMemory(root: Ref<HTMLElement | null>, scope: MaybeRefOrGetter<string>, content: () => unknown, initialPosition?: (element: HTMLElement) => number) {
  const memory = useTaskViewMemory(scope, () => ({ positions: {} as Record<string, number> }))
  const restored = new Map<string, HTMLElement>()
  const elementKeys = new WeakMap<HTMLElement, string>()
  let restoring = false
  async function restore() {
    const state=memory.value
    await nextTick()
    if(state!==memory.value)return
    restoring = true
    for (const element of root.value?.querySelectorAll<HTMLElement>('[data-task-scroll]') ?? []) {
      const key = element.dataset.taskScroll!
      if (restored.get(key) === element && elementKeys.get(element) === key) continue
      const saved = memory.value.positions[key]
      const hasSaved = typeof saved==='number' && Number.isFinite(saved) && saved>=0
      const value = hasSaved ? saved : Math.max(0, initialPosition?.(element) ?? 0)
      element.scrollTop = value
      elementKeys.set(element,key)
      if (element.scrollTop >= value || (!hasSaved && initialPosition)) restored.set(key,element)
      if (!hasSaved && initialPosition) memory.value.positions[key]=element.scrollTop
    }
    restoring = false
  }
  function remember(event: Event) {
    if (restoring || !(event.target instanceof HTMLElement)) return
    rememberElement(event.target)
  }
  function rememberElement(element:HTMLElement) {
    const key=element.dataset.taskScroll
    if(!key)return
    restored.set(key,element);elementKeys.set(element,key)
    memory.value.positions[key]=element.scrollTop
  }
  watch(memory, () => { restored.clear(); void restore() }, { flush: 'post' })
  watch(content, () => { void restore() }, { flush: 'post' })
  onMounted(restore)
  return Object.assign(remember,{rememberElement})
}
