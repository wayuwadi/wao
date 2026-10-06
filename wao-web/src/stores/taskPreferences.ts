import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'
import { useSessionStore } from './session'
import { readProjectTableColumns, type ProjectTableColumn } from '@/utils/projectTableColumns'

type SyncState = 'idle' | 'loading' | 'saving' | 'saved' | 'error'

/** 两项轻量成员偏好复用已有键值接口；草稿及手选保护由任务编辑入口负责。 */
export const useTaskPreferencesStore = defineStore('taskPreferences', () => {
  const session = useSessionStore()
  const owner = computed(() => `${session.account?.accountId ?? ''}:${session.userId}`)

  function field<T>(key: string, cachePrefix: string, decode: (raw: string | null) => T, encode: (value: T) => string) {
    const value = ref<T>(decode(null))
    const state = ref<SyncState>('idle'), error = ref('')
    let epoch = 0, version = 0, pending = false
    let queue = Promise.resolve()
    const cacheKey = (member: string) => `${cachePrefix}:${member}`
    const pendingKey = (member: string) => `wao-task-preference-pending-v1:${member}:${key}`
    const currentValue = () => value.value as T
    function readLocal(member: string) {
      try {
        const unsaved = localStorage.getItem(pendingKey(member))
        pending = unsaved !== null
        return unsaved ?? localStorage.getItem(cacheKey(member))
      } catch { return null }
    }
    function cache(member: string, raw: string, unsaved: boolean) {
      try {
        // 写入待同步标记优先，刷新时不能把未保存的选择误认成已同步。
        if (unsaved) localStorage.setItem(pendingKey(member), raw)
        localStorage.setItem(cacheKey(member), raw)
        if (!unsaved) localStorage.removeItem(pendingKey(member))
        return true
      } catch { return false }
    }
    function save(): Promise<void> {
      const member = owner.value, generation = epoch, revision = ++version, raw = encode(currentValue())
      pending = true
      const protectedLocally = cache(member, raw, true)
      state.value = 'saving'; error.value = ''
      queue = queue.then(async () => {
        if (generation !== epoch || member !== owner.value || revision !== version) return
        if (!session.userId) {
          state.value = 'error'; error.value = '请登录后重试同步，当前选择仍保留。'; return
        }
        try {
          await savePreference(key, raw)
          if (generation !== epoch || member !== owner.value || revision !== version) return
          pending = false; cache(member, raw, false); state.value = 'saved'; error.value = ''
        } catch {
          if (generation !== epoch || member !== owner.value || revision !== version) return
          state.value = 'error'
          error.value = protectedLocally ? '尚未同步到账号，本机选择已保留。' : '尚未同步到账号，也无法保护本机偏好，请保持页面并重试。'
        }
      })
      return queue
    }
    async function load() {
      if (!session.userId) return
      const member = owner.value, generation = epoch, revision = version
      const local = readLocal(member)
      state.value = 'loading'; error.value = ''
      try {
        const remote = await fetchPreference(key)
        if (generation !== epoch || member !== owner.value || revision !== version) return
        if (pending) {
          if (remote && encode(decode(remote)) === encode(currentValue())) {
            pending = false; cache(member, encode(currentValue()), false); state.value = 'saved'
          } else {
            state.value = 'error'; error.value = '本机有尚未同步的选择，重试后会保存到账号。'
          }
        } else if (remote) {
          value.value = decode(remote) as typeof value.value
          cache(member, encode(currentValue()), false); state.value = 'saved'
        } else if (local !== null) {
          // 仅成功读到账号为空时迁移旧缓存，读取失败不能覆盖其他设备偏好。
          await save()
        } else {
          value.value = decode(null) as typeof value.value; state.value = 'saved'
        }
      } catch {
        if (generation !== epoch || member !== owner.value || revision !== version) return
        state.value = 'error'; error.value = '无法读取账号偏好，当前使用本机选择，请重试读取。'
      }
    }
    function set(next: T) {
      value.value = decode(encode(next)) as typeof value.value
      return save()
    }
    function retry() { return pending ? save() : load() }
    watch(owner, member => {
      epoch++; version++; queue = Promise.resolve(); pending = false
      value.value = decode(readLocal(member)) as typeof value.value
      state.value = 'idle'; error.value = ''
      void load()
    }, { immediate: true, flush: 'sync' })
    return { value, state, error, set, retry, load }
  }

  const columns = field('project-table-columns-v1', 'wao-project-table-columns-v1', readProjectTableColumns, (value: ProjectTableColumn[]) => JSON.stringify(value))
  const recent = field('task-recent-project-v1', 'wao-day-task-recent-project-v1', raw => raw?.trim() ?? '', value => value)
  return {
    columns: columns.value, columnsState: columns.state, columnsError: columns.error,
    setColumns: columns.set, retryColumns: columns.retry,
    recentProject: recent.value, recentState: recent.state, recentError: recent.error,
    rememberProject: recent.set, retryRecent: recent.retry,
  }
})
