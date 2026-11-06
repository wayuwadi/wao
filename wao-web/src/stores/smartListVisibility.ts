import { computed, onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { fetchSmartListVisibility, saveSmartListVisibility } from '@/api/smartListVisibility'
import { useSessionStore } from '@/stores/session'

type Choice = '0' | '1'
type Draft = { id: string; base: string; desired: Choice; revision: string; updatedAt: number; windowId: string; key: string; durable: boolean }
type DraftGroups = Record<string, Draft[]>

/** Account preferences use independent, persistent drafts for each list and browser window. */
export const useSmartListVisibility = defineStore('smart-list-visibility', () => {
  const session = useSessionStore()
  const identity = computed(() => `${session.account?.accountId ?? ''}:${session.userId}`)
  const values = ref<Record<string, string>>({}), pending = ref<Record<string, Draft>>({})
  const candidates = ref<DraftGroups>({}), remoteConflicts = ref<Record<string, boolean>>({})
  const loading = ref(false), saving = ref(''), ready = ref(false), loaded = ref(false), readError = ref('')
  const windowId = crypto.randomUUID()
  let generation = 0, revision = 0
  const cacheKey = () => `wao-smart-list-visibility-v1:${identity.value}`
  const draftPrefix = () => `wao-smart-list-draft-v2:${encodeURIComponent(identity.value)}:`
  const draftKey = (id: string) => `${draftPrefix()}${encodeURIComponent(id)}:${windowId}`
  const validChoice = (value: unknown): value is Choice => value === '0' || value === '1'

  function cacheValues() {
    try { localStorage.setItem(cacheKey(), JSON.stringify(values.value)) } catch { /* server remains authoritative */ }
  }
  function readValues() {
    try {
      const stored: unknown = JSON.parse(localStorage.getItem(cacheKey()) || '{}')
      if (!stored || typeof stored !== 'object' || Array.isArray(stored)) return {}
      return Object.fromEntries(Object.entries(stored).filter(([, value]) => validChoice(value)))
    } catch { return {} }
  }
  function readDrafts(): DraftGroups | null {
    try {
      const groups: DraftGroups = {}, prefix = draftPrefix()
      for (let index = 0; index < localStorage.length; index++) {
        const key = localStorage.key(index)
        if (!key?.startsWith(prefix)) continue
        try {
          const draft = JSON.parse(localStorage.getItem(key) || 'null') as Draft | null
          if (!draft || typeof draft.id !== 'string' || !draft.id || !validChoice(draft.desired)
            || !(draft.base === '' || validChoice(draft.base)) || typeof draft.revision !== 'string'
            || typeof draft.updatedAt !== 'number' || typeof draft.windowId !== 'string') continue
          ;(groups[draft.id] ??= []).push({ ...draft, key, durable: true })
        } catch { /* a damaged entry must not prevent recovery of the others */ }
      }
      return groups
    } catch { return null }
  }
  function writeDraft(id: string, base: string, desired: Choice): Draft {
    const draft: Draft = { id, base, desired, revision: crypto.randomUUID(), updatedAt: Date.now(), windowId, key: draftKey(id), durable: true }
    try { localStorage.setItem(draft.key, JSON.stringify(draft)) } catch { draft.durable = false }
    return draft
  }
  function removeDraft(draft: Draft) {
    if (!draft.durable) return
    try {
      const stored = JSON.parse(localStorage.getItem(draft.key) || 'null') as Draft | null
      // A different window or a later edit must never be cleared by an older response.
      if (stored?.revision === draft.revision) localStorage.removeItem(draft.key)
    } catch { /* a confirmed server value can be reconciled on the next read */ }
  }
  function reconcileDrafts() {
    const groups = readDrafts()
    if (!groups) return
    // Keep unsaved in-memory edits if local storage was unavailable.
    for (const draft of Object.values(pending.value)) {
      if (draft.durable) continue
      const rows = groups[draft.id] ??= []
      if (!rows.some(row => row.revision === draft.revision)) rows.push(draft)
    }
    const selected: Record<string, Draft> = {}
    for (const [id, rows] of Object.entries(groups)) {
      rows.sort((a, b) => b.updatedAt - a.updatedAt)
      selected[id] = rows.find(row => row.revision === pending.value[id]?.revision)
        ?? rows.find(row => row.windowId === windowId) ?? rows[0]!
    }
    candidates.value = groups; pending.value = selected
  }
  function localConflict(id: string) {
    const selected = pending.value[id]
    return !!selected && (candidates.value[id] ?? []).some(draft => draft.desired !== selected.desired)
  }
  function conflict(id: string) { return !!remoteConflicts.value[id] || localConflict(id) }
  const error = computed(() => {
    if (readError.value) return readError.value
    if (Object.keys(pending.value).some(conflict)) return '智能清单显示设置有不同的未同步选择，请在对应清单设置中确认。'
    const outstanding = Object.values(pending.value).filter(draft => draft.id !== saving.value)
    if (outstanding.some(draft => !draft.durable)) return '显示选择尚未同步，也无法保存本机草稿，请保持页面打开并重试。'
    return outstanding.length ? '本机显示选择尚未同步，请在清单设置中重试。' : ''
  })
  function isVisible(id: string) { return !id || (pending.value[id]?.desired ?? values.value[id]) !== '0' }

  async function load() {
    if (!session.userId || loading.value || saving.value) return false
    const owner = identity.value, epoch = generation, version = revision
    loading.value = true; readError.value = ''
    try {
      const remote = await fetchSmartListVisibility()
      if (identity.value !== owner || epoch !== generation || version !== revision) return false
      if (!remote || typeof remote !== 'object' || Array.isArray(remote) || Object.values(remote).some(value => !validChoice(value))) throw new Error('显示设置格式异常')
      values.value = { ...remote }; reconcileDrafts()
      const clashes: Record<string, boolean> = {}
      for (const [id, draft] of Object.entries(pending.value)) {
        const current = remote[id] ?? ''
        if (current === draft.desired && !localConflict(id)) {
          for (const candidate of candidates.value[id] ?? [draft]) if (candidate.desired === current) removeDraft(candidate)
          const next = { ...pending.value }; delete next[id]; pending.value = next
          const groups = { ...candidates.value }; delete groups[id]; candidates.value = groups
        } else if (current !== draft.base && current !== draft.desired) clashes[id] = true
      }
      remoteConflicts.value = clashes; loaded.value = true; cacheValues()
      return true
    } catch {
      if (identity.value === owner && epoch === generation && version === revision) readError.value = Object.values(pending.value).some(draft => !draft.durable)
        ? '无法读取账号设置，也无法保存本机草稿，请保持页面打开并重试。'
        : '无法读取智能清单显示设置，当前使用本机记录，请重试。'
      return false
    } finally {
      if (identity.value === owner && epoch === generation) { loading.value = false; ready.value = true }
    }
  }
  async function save(id: string, accepted?: Draft[]) {
    const draft = pending.value[id]
    if (!draft || !loaded.value || saving.value || (!accepted && conflict(id))) return
    const owner = identity.value, epoch = generation
    saving.value = id; readError.value = ''; revision++
    try {
      await saveSmartListVisibility(id, draft.desired === '1', draft.base)
      if (identity.value !== owner || epoch !== generation) return
      values.value = { ...values.value, [id]: draft.desired }
      const resolved = accepted ?? [draft]
      for (const item of [...resolved, draft]) removeDraft(item)
      const next = { ...pending.value }; delete next[id]; pending.value = next
      const clashes = { ...remoteConflicts.value }; delete clashes[id]; remoteConflicts.value = clashes
      cacheValues(); reconcileDrafts()
    } catch (e) {
      if (identity.value !== owner || epoch !== generation) return
      readError.value = (e as { status?: number }).status === 409 ? '显示设置已在其他设备修改，正在读取最新设置。' : '显示选择尚未同步，请重试。'
    } finally {
      if (identity.value === owner && epoch === generation) saving.value = ''
    }
    // Confirm uncertain writes and compare the server before any retry.
    if (identity.value === owner && epoch === generation && pending.value[id]) await load()
  }
  async function setVisible(id: string, visible: boolean) {
    reconcileDrafts()
    if (!loaded.value || loading.value || saving.value || conflict(id)) return
    const desired: Choice = visible ? '1' : '0'
    if (isVisible(id) === visible && !pending.value[id]) return
    const previous = candidates.value[id] ?? []
    const draft = writeDraft(id, values.value[id] ?? '', desired)
    pending.value = { ...pending.value, [id]: draft }
    candidates.value = { ...candidates.value, [id]: [...previous.filter(item => item.key !== draft.key), draft] }
    revision++
    // A new choice only replaces this window's draft. Differing drafts from
    // other windows need an explicit resolution before anything is saved.
    await save(id)
  }
  async function retry(id?: string) {
    const refreshed = await load()
    if (refreshed && id && pending.value[id] && !conflict(id) && loaded.value) await save(id)
  }
  async function resolve(id: string, keep: boolean) {
    if (!conflict(id)) return
    const previous = [...(candidates.value[id] ?? [])]
    const draft = pending.value[id]
    if (keep && draft) {
      const updated = writeDraft(id, values.value[id] ?? '', draft.desired)
      pending.value = { ...pending.value, [id]: updated }
      await save(id, previous)
    } else {
      for (const item of previous) removeDraft(item)
      const next = { ...pending.value }; delete next[id]; pending.value = next
      const groups = { ...candidates.value }; delete groups[id]; candidates.value = groups
      const clashes = { ...remoteConflicts.value }; delete clashes[id]; remoteConflicts.value = clashes
      reconcileDrafts()
    }
  }
  watch(identity, () => {
    generation++; revision++; loading.value = false; saving.value = ''; ready.value = false; loaded.value = false; readError.value = ''; remoteConflicts.value = {}
    values.value = readValues(); pending.value = {}; candidates.value = {}; reconcileDrafts()
    if (session.userId) void load()
  }, { immediate: true, flush: 'sync' })
  const refresh = () => { if (!document.hidden && session.userId && !saving.value) void load() }
  const protectVolatileDraft = (event: BeforeUnloadEvent) => {
    if (Object.values(pending.value).some(draft => !draft.durable)) { event.preventDefault(); event.returnValue = '' }
  }
  window.addEventListener('focus', refresh)
  window.addEventListener('beforeunload', protectVolatileDraft)
  onScopeDispose(() => { window.removeEventListener('focus', refresh); window.removeEventListener('beforeunload', protectVolatileDraft) })
  return { isVisible, conflict, values, pending, loading, saving, ready, loaded, error, load, setVisible, retry, resolve }
})
