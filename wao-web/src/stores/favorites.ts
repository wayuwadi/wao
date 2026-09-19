import { ref } from 'vue'
import { defineStore } from 'pinia'
import { apiAddFavorite, apiRemoveFavorite, fetchFavorites, type Favorite } from '@/api/favorites'
import { toast } from '@/composables/toast'

/** 收藏 store：模块 / 项目 / 任务 / 文档的星标，落库到服务端、按成员隔离。 */
export const useFavoritesStore = defineStore('favorites', () => {
  const items = ref<Favorite[]>([])
  const loaded = ref(false)

  async function load() {
    try {
      items.value = await fetchFavorites()
      loaded.value = true
    } catch {
      /* 后端不可用时侧边栏收藏组保持为空，不打断主流程 */
    }
  }

  function isFav(type: Favorite['type'], targetId: string) {
    return items.value.some((f) => f.type === type && f.targetId === targetId)
  }

  /** 星标开关：先乐观更新界面，落库失败再回滚并提示。 */
  async function toggle(type: Favorite['type'], targetId: string, label: string, parentId: string | null = null) {
    if (isFav(type, targetId)) {
      const removed = items.value.find((f) => f.type === type && f.targetId === targetId)
      items.value = items.value.filter((f) => !(f.type === type && f.targetId === targetId))
      try {
        await apiRemoveFavorite(type, targetId)
        toast('已取消收藏', 'ri-star-line')
      } catch {
        if (removed) items.value = [...items.value, removed]
        toast('取消收藏失败，请重试', 'ri-error-warning-line')
      }
      return
    }
    items.value = [...items.value, { type, targetId, label, parentId }]
    try {
      await apiAddFavorite(type, targetId)
      toast(type === 'project' ? '已收藏，可从左侧项目菜单下打开' : '已收藏，左侧菜单「收藏」里随时可打开', 'ri-star-fill')
    } catch {
      items.value = items.value.filter((f) => !(f.type === type && f.targetId === targetId))
      toast('收藏失败，请重试', 'ri-error-warning-line')
    }
  }

  return { items, loaded, load, isFav, toggle }
})
