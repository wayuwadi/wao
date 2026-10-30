import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useSessionStore } from './session'
import { fetchPreference, savePreference } from '@/api/favorites'
import { toast } from '@/composables/toast'

/** 常用知识库按成员持久化，列表与侧栏共享同一份状态。 */
export const useKnowledgeFavoritesStore = defineStore('knowledgeFavorites', () => {
  const ids = ref<string[]>([])
  const loaded = ref(false)
  const saving = ref(false)
  let loading: Promise<void> | null = null
  let generation = 0
  const session = useSessionStore()
  watch(() => `${session.account?.accountId ?? ''}:${session.userId}`, () => {
    generation++; ids.value = []; loaded.value = false; saving.value = false; loading = null
    if (session.userId) void load()
  }, { flush: 'sync' })
  async function load() {
    if (loading) return loading
    const epoch = generation
    loading = (async () => {
      try {
        const value = JSON.parse((await fetchPreference('knowledge-favorites')) || '[]')
        if (epoch !== generation) return
        ids.value = Array.isArray(value) ? [...new Set(value.filter((id): id is string => typeof id === 'string'))] : []
        loaded.value = true
      } catch {
        if (epoch === generation) loaded.value = false
      } finally { if (epoch === generation) loading = null }
    })()
    return loading
  }
  async function toggle(id: string) {
    if (saving.value) return
    const epoch = generation
    if (!loaded.value) await load()
    if (epoch !== generation) return
    if (!loaded.value) { toast('常用知识库读取失败，请重试', 'ri-error-warning-line'); return }
    saving.value = true
    const next = ids.value.includes(id) ? ids.value.filter((item) => item !== id) : [...ids.value, id]
    try {
      await savePreference('knowledge-favorites', JSON.stringify(next))
      if (epoch === generation) ids.value = next
    } catch { if (epoch === generation) toast('常用设置保存失败，请重试', 'ri-error-warning-line') }
    finally { if (epoch === generation) saving.value = false }
  }
  return { ids, loaded, saving, load, toggle }
})
