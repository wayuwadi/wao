import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useSessionStore } from './session'
import { fetchFocus, startFocus, changeFocus, focusElapsed, focusTime, type FocusSession, type FocusState, type FocusStart } from '@/api/focus'
import { toast } from '@/composables/toast'

export const useFocusStore = defineStore('focus', () => {
  const session = useSessionStore()
  const identity = computed(() => session.account ? `${session.account.accountId}:${session.userId}` : '')
  const active = ref<FocusSession | null>(null), history = ref<FocusSession[]>([])
  const stats = ref<FocusState['stats']>({ todayCount: 0, todayMs: 0, totalCount: 0, totalMs: 0 })
  const note = ref(''), error = ref(''), draftError = ref(''), busy = ref(false), loading = ref(false), ready = ref(false)
  const conflict = ref(false)
  const now = ref(Date.now()), offset = ref(0), pendingStart = ref<{ id: string; body: FocusStart } | null>(null)
  let epoch = 0, request = 0, attemptedFinish = '', draftSession = '', restoredDraft = false, loadFailed = false
  const key = () => `wao-focus-draft:${identity.value}`
  const elapsed = computed(() => active.value ? focusElapsed(active.value, now.value) : 0)
  const display = computed(() => active.value ? focusTime(active.value.plannedMs ? active.value.plannedMs - elapsed.value : elapsed.value) : '25:00')
  const dirty = computed(() => !!active.value && note.value !== active.value.note)
  function protect() {
    if (!identity.value) return false
    try { localStorage.setItem(key(), JSON.stringify({ note: note.value, session: active.value?.id ?? draftSession, pending: pendingStart.value })); draftError.value = ''; return true }
    catch { draftError.value = '本机草稿保存失败，请保持页面并复制笔记备份'; return false }
  }
  function editNote(value: string) { note.value = value; protect() }
  function apply(row: FocusSession) {
    if (row.status === 'finished') { active.value = null; history.value = [row, ...history.value.filter(s => s.id !== row.id)] }
    else active.value = row
  }
  async function load() {
    if (!identity.value || busy.value) return
    const generation = epoch, sequence = ++request
    loading.value = true
    try {
      const result = await fetchFocus()
      if (generation !== epoch || sequence !== request || busy.value) return
      if (loadFailed) { error.value = ''; loadFailed = false }
      const keepDraft = dirty.value || restoredDraft || !!pendingStart.value || (!!note.value && !active.value)
      if (restoredDraft && draftSession === result.active?.id && result.active.note !== note.value) {
        conflict.value = true; error.value = '已恢复本机笔记草稿，与云端内容不同。请确认后同步本机笔记。'
      }
      if (dirty.value && result.active?.id === active.value?.id && result.active?.note !== active.value?.note && result.active?.note !== note.value) {
        conflict.value = true; error.value = '其他页面更新了笔记。本机草稿已保留，请选择同步本机笔记后继续。'
      }
      active.value = result.active; history.value = result.history; stats.value = result.stats
      offset.value = result.serverNow - Date.now(); now.value = result.serverNow; ready.value = true
      if (!keepDraft) note.value = result.active?.note ?? ''
      restoredDraft = false
      if (pendingStart.value && result.active && result.active.id !== pendingStart.value.id) {
        conflict.value = true; error.value = '其他页面已开始另一段专注，请确认保留的本机笔记后继续。'; pendingStart.value = null
      }
      if (pendingStart.value && result.active?.id === pendingStart.value.id) pendingStart.value = null
      if (draftSession && draftSession !== result.active?.id && note.value) error.value = '原专注已结束，未同步的笔记仍保留在输入框中'
      draftSession = result.active?.id ?? ''; protect()
    } catch (e: any) { if (generation === epoch) { loadFailed = true; error.value = e?.message || '专注记录读取失败，请重试' } }
    finally { if (generation === epoch && sequence === request) loading.value = false }
  }
  async function start(body: Omit<FocusStart, 'note'>) {
    if (busy.value || active.value || !ready.value) return
    pendingStart.value ??= { id: crypto.randomUUID().replace(/-/g, ''), body: { ...body, note: note.value } }
    if (!protect()) return
    const generation = epoch, pending = pendingStart.value
    busy.value = true; error.value = ''; request++
    try {
      const result = await startFocus(pending.id, pending.body)
      if (generation !== epoch) return
      apply(result); pendingStart.value = null; draftSession = result.id; protect()
    } catch (e: any) { if (generation === epoch) error.value = e?.message || '开始失败，重试将恢复同一条专注' }
    finally { if (generation === epoch) { busy.value = false; await load() } }
  }
  async function act(action: 'pause' | 'resume' | 'save' | 'finish') {
    const current = active.value
    if (!current || busy.value || (conflict.value && action !== 'save')) return
    protect()
    const generation = epoch, submitted = note.value
    busy.value = true; error.value = ''; request++
    try {
      const result = await changeFocus(current.id, { revision: current.revision, action, note: submitted })
      if (generation !== epoch) return
      apply(result)
      conflict.value = false
      if (result.status === 'finished') {
        if (note.value === submitted && result.mode !== 'break') note.value = ''
        draftSession = ''; attemptedFinish = ''
        toast(result.mode === 'break' ? '休息结束，可以开始下一次专注' : result.noteId ? '专注笔记已保存 · 番茄钟' : '专注已结束，计时记录已保留', 'ri-timer-line')
      }
      protect()
    } catch (e: any) { if (generation === epoch) error.value = e?.message || '未能保存，笔记已保留，请重试' }
    finally { if (generation === epoch) { busy.value = false; await load() } }
  }
  function tick() {
    now.value = Date.now() + offset.value
    const current = active.value
    if (current?.status === 'running' && current.plannedMs && elapsed.value >= current.plannedMs && !busy.value && !conflict.value && !error.value && !draftError.value && attemptedFinish !== current.id) {
      attemptedFinish = current.id; void act('finish')
    }
  }
  watch(identity, () => {
    epoch++; request++; active.value = null; history.value = []; note.value = ''; error.value = ''; draftError.value = ''; ready.value = false; busy.value = false; loading.value = false; pendingStart.value = null; attemptedFinish = ''; draftSession = ''; restoredDraft = false; conflict.value = false; loadFailed = false
    stats.value = { todayCount: 0, todayMs: 0, totalCount: 0, totalMs: 0 }
    if (!identity.value) return
    try { const stored = JSON.parse(localStorage.getItem(key()) || 'null'); note.value = typeof stored?.note === 'string' ? stored.note : ''; draftSession = stored?.session || ''; pendingStart.value = stored?.pending || null; restoredDraft = !!draftSession }
    catch { draftError.value = '本机草稿无法读取，请勿清除浏览器数据' }
    void load()
  }, { immediate: true, flush: 'sync' })
  return { active, history, stats, note, error, draftError, busy, loading, ready, pendingStart, elapsed, display, dirty, conflict, identity, load, start, act, tick, editNote }
})
