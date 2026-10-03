import { computed, reactive, watch } from 'vue'
import { useSessionStore } from '@/stores/session'

type Fields = Record<string, string | number | boolean>
type Validators<T> = { [K in keyof T]: (value: unknown) => boolean }
export const validPage = (value: unknown) => Number.isSafeInteger(value) && Number(value) >= 1 && Number(value) <= 100000
export const validSearch = (value: unknown) => typeof value === 'string' && value.length <= 200
export const oneOf = (...values: (string | number)[]) => (value: unknown) => values.includes(value as string | number)

/** 只保存浏览条件，不保存业务记录；账号隔离，会话存储失败时继续内存浏览。 */
export function useBrowsingState<T extends Fields>(name: string, defaults: T, validators: Validators<T>) {
  const session = useSessionStore()
  const identity = computed(() => session.account ? `${session.account.accountId}:${session.userId}` : '')
  const key = computed(() => identity.value ? `wao-browse-v1:${identity.value}:${name}` : '')
  const state = reactive({ ...defaults }) as T
  let restoring = false
  watch(key, value => {
    restoring = true
    let saved: Record<string, unknown> = {}
    try {
      const raw: unknown = value ? JSON.parse(sessionStorage.getItem(value) || '{}') : {}
      if (raw && typeof raw === 'object' && !Array.isArray(raw)) saved = raw as Record<string, unknown>
    } catch { /* 损坏或不可用的存储不能阻断页面。 */ }
    for (const field of Object.keys(defaults) as (keyof T)[]) {
      const candidate = saved[field as string]
      state[field] = (validators[field](candidate) ? candidate : defaults[field]) as T[keyof T]
    }
    restoring = false
  }, { immediate: true, flush: 'sync' })
  watch(state, value => {
    if (restoring || !key.value) return
    try { sessionStorage.setItem(key.value, JSON.stringify(value)) } catch { /* 仍保留当前页面状态。 */ }
  }, { deep: true, flush: 'sync' })
  return state
}
