import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { fetchPreference, savePreference, type Favorite } from '@/api/favorites'
import { useSessionStore } from '@/stores/session'
import { useNavStore } from '@/stores/nav'

export interface TopShortcut {
  key: string
  label: string
  icon: string
  path: string
  quickLinkId?: string
}
const PREF_KEY = 'nav-top-shortcuts-v1'

/** 只接受站内资源路由或已保存快捷链接的引用，不接受任意拖入地址。 */
export function normalizeTopShortcuts(value: unknown): TopShortcut[] {
  if (!Array.isArray(value) || value.length > 50) throw new Error('顶部快捷入口格式错误或超过 50 个')
  const keys = new Set<string>()
  return value.map(raw => {
    const item = raw as TopShortcut
    if (!item || typeof item.key !== 'string' || !item.key || item.key.length > 200
      || typeof item.label !== 'string' || !item.label.trim() || item.label.length > 300
      || typeof item.icon !== 'string' || !/^ri-[a-z0-9-]+$/.test(item.icon)
      || typeof item.path !== 'string' || !/^\/(?:projects|kb|quick-links)\/[a-zA-Z0-9_%.-]+(?:\?(?:task|doc)=[a-zA-Z0-9_%.-]+)?$/.test(item.path)
      || (item.quickLinkId !== undefined && (typeof item.quickLinkId !== 'string' || item.path !== `/quick-links/${encodeURIComponent(item.quickLinkId)}`))
      || keys.has(item.key)) throw new Error('顶部快捷入口数据无效，请重新加载')
    keys.add(item.key)
    return { key: item.key, label: item.label, icon: item.icon, path: item.path, ...(item.quickLinkId ? { quickLinkId: item.quickLinkId } : {}) }
  })
}

export function resourceShortcut(kind: 'project' | 'kb', id: string, label: string): TopShortcut {
  return { key: `${kind}:${id}`, label, icon: kind === 'project' ? 'ri-stack-line' : 'ri-book-2-line', path: `/${kind === 'project' ? 'projects' : 'kb'}/${encodeURIComponent(id)}` }
}

export function favoriteShortcut(f: Favorite): TopShortcut | undefined {
  if (f.type === 'project') return resourceShortcut('project', f.targetId, f.label)
  if ((f.type === 'doc' || f.type === 'task') && f.parentId) return {
    key: `${f.type}:${f.targetId}`, label: f.label, icon: f.type === 'doc' ? 'ri-file-text-line' : 'ri-checkbox-circle-line',
    path: `/${f.type === 'doc' ? 'kb' : 'projects'}/${encodeURIComponent(f.parentId)}?${f.type}=${encodeURIComponent(f.targetId)}`,
  }
  return undefined
}

export const useTopShortcutsStore = defineStore('top-shortcuts', () => {
  const session = useSessionStore()
  const items = ref<TopShortcut[]>([])
  const state = ref<'idle' | 'loading' | 'loaded' | 'saving' | 'saved' | 'error'>('idle')
  const error = ref('')
  const loaded = ref(false)
  const dragged = ref<TopShortcut | null>(null)
  const pending = ref<TopShortcut[] | null>(null)
  const canEdit = computed(() => loaded.value && !['loading', 'saving'].includes(state.value))
  let generation = 0
  watch(() => session.userId, () => {
    generation++
    items.value = []; state.value = 'idle'; error.value = ''; loaded.value = false; dragged.value = null; pending.value = null
  }, { flush: 'sync' })

  async function load(force = false) {
    if (!session.userId || ['loading', 'saving'].includes(state.value) || (loaded.value && !force)) return
    const token = generation
    state.value = 'loading'; error.value = ''
    try {
      const raw = await fetchPreference(PREF_KEY)
      const saved = normalizeTopShortcuts(raw ? JSON.parse(raw) : [])
      if (token !== generation) return
      items.value = saved; loaded.value = true; state.value = 'loaded'
    } catch (cause) {
      if (token !== generation) return
      state.value = 'error'; error.value = cause instanceof Error ? cause.message : '快捷入口读取失败'
    }
  }
  async function save(next: TopShortcut[]) {
    if (!canEdit.value) throw new Error('快捷入口尚未加载或正在保存，请稍后重试')
    const canonical = normalizeTopShortcuts(next)
    const token = generation
    pending.value = canonical; state.value = 'saving'; error.value = ''
    try {
      await savePreference(PREF_KEY, JSON.stringify(canonical))
      if (token !== generation) return
      items.value = canonical; pending.value = null; state.value = 'saved'
    } catch (cause) {
      if (token !== generation) return
      state.value = 'error'; error.value = cause instanceof Error ? cause.message : '快捷入口保存失败'
      throw cause
    }
  }
  async function pin(item: TopShortcut, beforeKey?: string) {
    if (item.key === beforeKey) return
    const next = items.value.filter(saved => saved.key !== item.key)
    const index = beforeKey ? next.findIndex(saved => saved.key === beforeKey) : -1
    next.splice(index < 0 ? next.length : index, 0, item)
    await save(next)
  }
  function remove(key: string) { return save(items.value.filter(item => item.key !== key)) }
  function retry() { return pending.value ? save(pending.value) : load(true) }
  function start(item: TopShortcut | undefined, event: DragEvent) {
    if (!item) { event.preventDefault(); return }
    useNavStore().draggedMenuId = null
    dragged.value = item
    event.dataTransfer?.setData('application/x-wao-top-shortcut', item.key)
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copyMove'
  }
  function end() { dragged.value = null }
  return { items, state, error, loaded, canEdit, dragged, pending, load, pin, remove, retry, start, end }
})
