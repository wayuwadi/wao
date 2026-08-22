import { computed } from 'vue'
import { useRoute } from 'vue-router'

/**
 * Skill 市场同时服务公开站和 WAO 站内页签；业务视图只关心当前路由树的根地址。
 */
export function useSkillMarketRoutes() {
  const route = useRoute()
  const base = computed(() =>
    typeof route.meta.skillBase === 'string' ? route.meta.skillBase : '/skills',
  )
  const path = (suffix = '') => `${base.value}${suffix}`
  return { base, path }
}
