import { reactive, watch } from 'vue'
import { defineStore } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'
import { useSessionStore } from './session'

interface Order { ids: string[]; loaded: boolean; loading: boolean; saving: boolean; dirty: boolean; error: string; revision: number }
/** 成员级顺序独立于任务正文；后台刷新不会覆盖未保存的排序。 */
export const useTaskOrderStore = defineStore('task-order', () => {
  const session = useSessionStore()
  const states = reactive<Record<string, Order>>({})
  let generation = 0
  watch(() => session.userId, () => { generation++; Object.keys(states).forEach(key => delete states[key]) }, { flush: 'sync' })
  const key = (scope: string) => `task-order-v1:${scope}`
  const draftKey = (scope: string) => `${key(scope)}:${session.userId}`
  function state(scope: string): Order {
    states[scope] ??= { ids: [], loaded: false, loading: false, saving: false, dirty: false, error: '', revision: 0 }
    return states[scope]!
  }
  function parse(value: string) {
    const ids: unknown = JSON.parse(value || '[]')
    if (!Array.isArray(ids) || !ids.every(id => typeof id === 'string')) throw new Error('任务顺序格式异常')
    return [...new Set(ids)] as string[]
  }
  async function load(scope: string) {
    const s = state(scope), version = generation
    if (s.loaded || s.loading || !session.userId) return
    s.loading = true; s.error = ''
    try {
      const ids = parse(await fetchPreference(key(scope)))
      if (version !== generation) return
      const draft = localStorage.getItem(draftKey(scope))
      s.ids = draft ? parse(draft) : ids
      s.dirty = !!draft; s.loaded = true
    } catch (e) { if (version === generation) s.error = e instanceof Error ? e.message : '顺序读取失败' }
    finally { if (version === generation) s.loading = false }
  }
  function sort<T>(scope: string, items: T[], id: (item: T) => string): T[] {
    const ranks = new Map(state(scope).ids.map((value, index) => [value, index]))
    return [...items].sort((a, b) => (ranks.get(id(a)) ?? Infinity) - (ranks.get(id(b)) ?? Infinity))
  }
  async function save(scope: string) {
    const s = state(scope), version = generation, storageKey = draftKey(scope)
    if (s.saving || !s.loaded) return
    s.saving = true; s.error = ''
    try {
      while (s.dirty && version === generation) {
        const revision = s.revision
        await savePreference(key(scope), JSON.stringify(s.ids))
        if (version !== generation) return
        if (revision === s.revision) { s.dirty = false; localStorage.removeItem(storageKey) }
      }
    } catch (e) { if (version === generation) s.error = e instanceof Error ? e.message : '顺序保存失败' }
    finally { if (version === generation) s.saving = false }
  }
  async function move(scope: string, ids: string[], source: string, target: string, after = false) {
    const version = generation
    await load(scope)
    if (version !== generation) return
    const s = state(scope)
    if (!s.loaded || !ids.includes(source) || !ids.includes(target) || source === target) return
    const ordered = sort(scope, [...new Set([...ids, ...s.ids])], id => id).filter(id => id !== source)
    ordered.splice(ordered.indexOf(target) + (after ? 1 : 0), 0, source)
    s.ids = ordered; s.revision++; s.dirty = true
    try { localStorage.setItem(draftKey(scope), JSON.stringify(ordered)) }
    catch { s.error = '本机草稿保存失败，请保持页面打开并重试保存'; return }
    await save(scope)
  }
  async function retry(scope: string) { if (!state(scope).loaded) await load(scope); if (state(scope).dirty) await save(scope) }
  return { state, load, sort, move, retry }
})
