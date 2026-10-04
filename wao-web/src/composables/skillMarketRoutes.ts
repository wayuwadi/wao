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
  const path = (suffix = '') => {
    if (route.path.startsWith('/capabilities')) {
      if (!suffix) return '/capabilities?type=Skill'
      if (suffix === '/manage') return '/capabilities?area=published&type=Skill'
      if (suffix === '/publish') return '/capabilities/publish/skill'
      if (suffix === '/reviews') return '/capabilities/reviews/skill'
      return `/capabilities/skills${suffix}`
    }
    return `${base.value}${suffix}`
  }
  return { base, path }
}
