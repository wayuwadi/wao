import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'
import { toast } from '@/composables/toast'

/** 常用知识库按成员持久化，列表与侧栏共享同一份状态。 */
export const useKnowledgeFavoritesStore = defineStore('knowledgeFavorites', () => {
  const ids = ref<string[]>([])
  const loaded = ref(false)
  const saving = ref(false)
  let loading: Promise<void> | null = null
  async function load() {
    if (loading) return loading
    loading = (async () => {
      try {
        const value = JSON.parse((await fetchPreference('knowledge-favorites')) || '[]')
        ids.value = Array.isArray(value) ? [...new Set(value.filter((id): id is string => typeof id === 'string'))] : []
        loaded.value = true
      } catch {
        loaded.value = false
      } finally { loading = null }
    })()
    return loading
  }
  async function toggle(id: string) {
    if (saving.value) return
    if (!loaded.value) await load()
    if (!loaded.value) { toast('常用知识库读取失败，请重试', 'ri-error-warning-line'); return }
    saving.value = true
    const next = ids.value.includes(id) ? ids.value.filter((item) => item !== id) : [...ids.value, id]
    try {
      await savePreference('knowledge-favorites', JSON.stringify(next))
      ids.value = next
    } catch { toast('常用设置保存失败，请重试', 'ri-error-warning-line') }
    finally { saving.value = false }
  }
  return { ids, loaded, saving, load, toggle }
})
