import type { RouteLocationNormalized } from 'vue-router'

/** 原独立页面只保留深链接兼容；所有业务界面由能力中心承接。 */
export function legacyCapabilityRedirect(route: Pick<RouteLocationNormalized, 'path' | 'query'>) {
  const query = { ...route.query }
  const path = route.path.replace(/^\/wao\//, '/')
  if (path === '/skill-connections')
    return { path: '/capabilities', query: { ...query, type: '应用接入' }, replace: true }
  if (path === '/hub') return { path: '/capabilities', query, replace: true }
  if (path === '/skills' || path === '/skills/manage')
    return {
      path: '/capabilities',
      query: { ...query, type: 'Skill', area: path.endsWith('/manage') ? 'published' : undefined },
      replace: true,
    }
  if (path === '/skills/publish')
    return { path: '/capabilities/publish/skill', query, replace: true }
  if (path === '/skills/reviews')
    return { path: '/capabilities/reviews/skill', query, replace: true }
  if (path.startsWith('/skills/'))
    return { path: path.replace('/skills/', '/capabilities/skills/'), query, replace: true }
  if (path === '/pets' || path === '/pets/manage')
    return {
      path: '/capabilities',
      query: { ...query, type: 'Pet', area: path.endsWith('/manage') ? 'published' : undefined },
      replace: true,
    }
  if (path === '/pets/create') return { path: '/capabilities/publish/pet', query, replace: true }
  return { path: path.replace('/pets/', '/capabilities/pets/'), query, replace: true }
}
