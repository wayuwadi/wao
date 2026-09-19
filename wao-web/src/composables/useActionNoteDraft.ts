import { computed, onBeforeUnmount, reactive, watch, type Ref } from 'vue'
import { saveActionNote, type ActionPlan } from '@/api/actionPlans'

type State = 'pending' | 'saving' | 'saved' | 'error'
interface Draft {
  member: string
  planId: string
  date: string
  content: string
  version: number
  dirty: boolean
  state: State
  localError: boolean
  timer?: ReturnType<typeof setTimeout>
  request?: Promise<void>
}

/** 按成员、计划和日期保护复盘草稿；同一条复盘串行保存，回包不覆盖编辑。 */
export function useActionNoteDraft(member: Ref<string>, plan: Ref<ActionPlan | null>, date: Ref<string>) {
  const drafts = reactive(new Map<string, Draft>())
  const keyOf = (owner: string, id: string, day: string) =>
    `wao-action-note-v1:${encodeURIComponent(owner)}:${encodeURIComponent(id)}:${day}`
  const currentKey = computed(() => plan.value && member.value
    ? keyOf(member.value, plan.value.id, date.value) : '')
  const current = computed(() => drafts.get(currentKey.value))

  function persist(draft: Draft) {
    try {
      const key = keyOf(draft.member, draft.planId, draft.date)
      if (draft.dirty) localStorage.setItem(key, JSON.stringify({ content: draft.content }))
      else localStorage.removeItem(key)
      draft.localError = false
    } catch { draft.localError = true }
  }

  watch([currentKey, plan], () => {
    const key = currentKey.value
    if (!key || drafts.has(key) || plan.value?.selectedDate !== date.value) return
    try {
      const raw = localStorage.getItem(key)
      if (!raw) return
      const saved: unknown = JSON.parse(raw)
      if (!saved || typeof saved !== 'object' || !('content' in saved) || typeof saved.content !== 'string') return
      drafts.set(key, { member: member.value, planId: plan.value.id, date: date.value,
        content: saved.content, version: 1, dirty: true, state: 'pending', localError: false })
    } catch { /* 损坏或不可读取的本机记录不替换服务端内容。 */ }
  }, { immediate: true })

  const text = computed({
    get: () => current.value?.content ?? (plan.value?.selectedDate === date.value ? plan.value.note : ''),
    set: (content: string) => {
      if (!currentKey.value || !plan.value || plan.value.selectedDate !== date.value) return
      let draft = current.value
      if (!draft) {
        drafts.set(currentKey.value, { member: member.value, planId: plan.value.id, date: date.value,
          content, version: 0, dirty: false, state: 'pending', localError: false })
        draft = current.value!
      }
      draft.content = content
      draft.version++
      draft.dirty = true
      draft.state = draft.request ? 'saving' : 'pending'
      persist(draft)
      if (draft.timer) clearTimeout(draft.timer)
      draft.timer = setTimeout(() => { draft.timer = undefined; void save(draft) }, 650)
    },
  })

  function save(draft: Draft): Promise<void> {
    if (draft.timer) { clearTimeout(draft.timer); draft.timer = undefined }
    if (draft.request) return draft.request
    if (!draft.dirty || draft.member !== member.value) return Promise.resolve()
    const version = draft.version
    const content = draft.content
    draft.state = 'saving'
    draft.request = (async () => {
      try {
        await saveActionNote(draft.planId, draft.date, content)
        if (draft.version === version) {
          draft.dirty = false
          draft.state = 'saved'
        } else draft.state = 'pending'
        persist(draft)
      } catch {
        if (draft.timer) { clearTimeout(draft.timer); draft.timer = undefined }
        draft.state = 'error'
        persist(draft)
      } finally {
        draft.request = undefined
      }
      // 只在上次成功后发送更新的版本；失败保留草稿，由用户明确重试。
      if (draft.dirty && draft.state === 'pending' && draft.member === member.value) await save(draft)
    })()
    return draft.request
  }

  function retry() { if (current.value) void save(current.value) }
  function protectUnload(event: BeforeUnloadEvent) {
    if ([...drafts.values()].some((draft) => draft.member === member.value && draft.dirty)) {
      event.preventDefault()
      event.returnValue = ''
    }
  }
  window.addEventListener('beforeunload', protectUnload)
  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', protectUnload)
    for (const draft of drafts.values()) {
      if (draft.timer) {
        clearTimeout(draft.timer)
        draft.timer = undefined
        if (draft.state !== 'error') void save(draft)
      }
    }
  })
  return { text, retry, state: computed(() => current.value?.state ?? 'idle'),
    localError: computed(() => current.value?.localError ?? false) }
}
