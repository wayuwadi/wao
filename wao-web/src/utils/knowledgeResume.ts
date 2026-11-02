import type { Router, RouteLocationNormalized } from 'vue-router'

type Location = { kb: string; doc: string }
const validId = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_-]{1,120}$/.test(value)
function locationOf(route: RouteLocationNormalized): Location | null {
  if (route.path !== '/knowledge' && !route.path.startsWith('/kb/')) return null
  const kb = route.params.id || route.query.kb, doc = route.query.doc
  return validId(kb) && validId(doc) && kb !== 'kb-notes' ? { kb, doc } : null
}
/** Store only identifiers, scoped to the signed-in account/member. Explicit links always win. */
export function installKnowledgeResume(router: Router, identity: () => string) {
  const key = () => identity() ? `wao-knowledge-last-document-v1:${identity()}` : ''
  const removeBefore = router.beforeEach(to => {
    if (to.path !== '/knowledge' || Object.keys(to.query).length || to.hash || !key()) return
    try {
      const saved = JSON.parse(localStorage.getItem(key()) || 'null') as Location | null
      if (saved && validId(saved.kb) && validId(saved.doc) && saved.kb !== 'kb-notes')
        return { path:'/knowledge', query:{ kb:saved.kb, doc:saved.doc }, replace:true }
    } catch { /* Invalid or unavailable browser memory does not block navigation. */ }
  })
  const removeAfter = router.afterEach((to, _from, failure) => {
    if (failure || !key()) return
    const location = locationOf(to)
    if (location) try { localStorage.setItem(key(), JSON.stringify(location)) } catch { /* Session remains usable. */ }
  })
  return () => { removeBefore(); removeAfter() }
}
