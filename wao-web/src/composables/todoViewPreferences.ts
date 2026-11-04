import { computed, onBeforeUnmount, onMounted, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { useMemberPreference } from '@/composables/memberPreference'
import { useSessionStore } from '@/stores/session'
import { decodeTodoDisplay, decodeTodoView, todoViewPreferenceKey, type TodoDisplaySettings, type TodoViewSettings } from '@/utils/todoViewSettings'

export function useTodoViewPreferences(scope: MaybeRefOrGetter<string>) {
  const account = useMemberPreference('todo-display-v1', decodeTodoDisplay, JSON.stringify)
  const shared = useMemberPreference(() => todoViewPreferenceKey(toValue(scope)), decodeTodoView, JSON.stringify)
  const session = useSessionStore()
  const key = computed(() => `wao-todo-local-view-v1:${session.account?.accountId ?? ''}:${session.userId}:${toValue(scope)}`)
  const local = ref(decodeTodoView(null)), localError = ref<'read' | 'write' | ''>('')
  function readLocal() {
    try { local.value = decodeTodoView(localStorage.getItem(key.value)); localError.value = '' }
    catch { localError.value = 'read' }
  }
  function writeLocal(next: TodoViewSettings) {
    local.value = next
    try { localStorage.setItem(key.value, JSON.stringify(next)); localError.value = '' }
    catch { localError.value = 'write' }
  }
  watch(key, () => { local.value = decodeTodoView(null); readLocal() }, { immediate:true, flush:'sync' })
  const view = computed(() => account.value.value.sync ? shared.value.value : local.value)
  // 初次读取失败或未完成时，默认值不能成为整份账号偏好的写入基线。
  const ready = computed(() => account.loaded.value && (!account.value.value.sync || shared.loaded.value))
  function changeView(patch: Partial<TodoViewSettings>) {
    if (!ready.value) return
    const next = { ...view.value, ...patch }
    writeLocal(next)
    if (account.value.value.sync) void shared.set(next)
  }
  function changeDisplay(patch: Partial<TodoDisplaySettings>) {
    if (!ready.value) return
    if (patch.sync === false && account.value.value.sync) writeLocal(view.value)
    if (patch.sync === true && !account.value.value.sync) void shared.set(view.value)
    void account.set({ ...account.value.value, ...patch })
  }
  const error = computed(() => account.error.value || (account.value.value.sync ? shared.error.value :
    localError.value === 'write' ? '本机视图未能保存，当前选择仍保留。' : localError.value === 'read' ? '无法读取本机视图，请重试。' : ''))
  function retry() {
    if (account.error.value) return account.retry()
    if (account.value.value.sync) return shared.retry()
    if (localError.value === 'write') writeLocal(local.value)
    else readLocal()
  }
  function refresh() {
    if (account.state.value === 'saved') void account.load()
    if (account.value.value.sync && shared.state.value === 'saved') void shared.load()
  }
  onMounted(() => window.addEventListener('focus', refresh))
  onBeforeUnmount(() => window.removeEventListener('focus', refresh))
  return { view, display: account.value, ready, changeView, changeDisplay, error, retry }
}
