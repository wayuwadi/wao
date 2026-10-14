import { computed, ref, watch } from 'vue'
import { useSessionStore } from './session'
import { useMemberPreference } from '@/composables/memberPreference'
import { defineStore } from 'pinia'
import { apiAddFavorite, apiRemoveFavorite, fetchFavorites, type Favorite } from '@/api/favorites'
import { toast } from '@/composables/toast'

/** 收藏 store：模块 / 项目 / 任务 / 文档的星标，落库到服务端、按成员隔离。 */
export const useFavoritesStore = defineStore('favorites', () => {
  const items = ref<Favorite[]>([])
  const loaded = ref(false)
  const session = useSessionStore()
  let generation = 0
  const projectOrder = useMemberPreference<string[]>('favorite-project-order-v1', raw => {
    try { const ids: unknown = JSON.parse(raw ?? '[]'); return Array.isArray(ids) ? [...new Set(ids.filter((id): id is string => typeof id === 'string' && id.length <= 128))] : [] }
    catch { return [] }
  }, ids => JSON.stringify(ids))
  const orderedProjects = computed(() => {
    const rank = new Map(projectOrder.value.value.map((id, index) => [id, index]))
    return items.value.filter(item => item.type === 'project').sort((a, b) => (rank.get(a.targetId) ?? Infinity) - (rank.get(b.targetId) ?? Infinity))
  })
  /** 只改变当前成员的收藏项目展示顺序，不移动导航菜单或项目数据。 */
  function reorderProject(sourceId: string, targetId: string, after = false) {
    const ids = orderedProjects.value.map(item => item.targetId)
    if (sourceId === targetId || !ids.includes(sourceId) || !ids.includes(targetId)) return false
    ids.splice(ids.indexOf(sourceId), 1)
    ids.splice(ids.indexOf(targetId) + (after ? 1 : 0), 0, sourceId)
    void projectOrder.set(ids)
    return true
  }
  watch(() => `${session.account?.accountId ?? ''}:${session.userId}`, () => { generation++; items.value = []; loaded.value = false; if (session.userId) void load() })

  async function load() {
    const epoch = generation
    try {
      const result = await fetchFavorites()
      if (epoch !== generation) return
      items.value = result
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
    const epoch = generation
    if (isFav(type, targetId)) {
      const removed = items.value.find((f) => f.type === type && f.targetId === targetId)
      items.value = items.value.filter((f) => !(f.type === type && f.targetId === targetId))
      try {
        await apiRemoveFavorite(type, targetId)
        if (epoch !== generation) return
        toast('已取消收藏', 'ri-star-line')
      } catch {
        if (epoch !== generation) return
        if (removed) items.value = [...items.value, removed]
        toast('取消收藏失败，请重试', 'ri-error-warning-line')
      }
      return
    }
    items.value = [...items.value, { type, targetId, label, parentId }]
    try {
      await apiAddFavorite(type, targetId)
      if (epoch !== generation) return
      toast(type === 'project' ? '已收藏，可从左侧项目菜单下打开' : type === 'task' || type === 'doc' ? '已收藏，可从顶部快捷栏打开' : '已收藏，可从左侧收藏菜单打开', 'ri-star-fill')
    } catch {
      if (epoch !== generation) return
      items.value = items.value.filter((f) => !(f.type === type && f.targetId === targetId))
      toast('收藏失败，请重试', 'ri-error-warning-line')
    }
  }

  return { items, loaded, load, isFav, toggle, orderedProjects, reorderProject,
    projectOrderState: projectOrder.state, projectOrderError: projectOrder.error, retryProjectOrder: projectOrder.retry }
})
