<script setup lang="ts">
import { computed, provide } from 'vue'
import {
  RouterView,
  routeLocationKey,
  routerKey,
  useRouter,
  type NavigationGuard,
  type NavigationGuardReturn,
  type RouteLocationNormalizedLoaded,
  type RouteLocationRaw,
} from 'vue-router'
import { tabFromRoute } from '@/config/nav'

const props = defineProps<{
  pageKey: string
  location: RouteLocationNormalizedLoaded
  floating: boolean
}>()
const emit = defineEmits<{ navigate: [location: RouteLocationNormalizedLoaded] }>()
const router = useRouter()
const guards = new Set<NavigationGuard>()
function runGuard(
  guard: NavigationGuard,
  to: RouteLocationNormalizedLoaded,
  from: RouteLocationNormalizedLoaded,
) {
  if (guard.length < 3) return guard.call(undefined, to, from, () => {})
  return new Promise<NavigationGuardReturn>((resolve, reject) => {
    void Promise.resolve(
      guard.call(undefined, to, from, (result?: unknown) => {
        if (result instanceof Error) reject(result)
        else resolve(result as NavigationGuardReturn)
      }),
    ).catch(reject)
  })
}
// 每个页面读取自己的快照；主工作区导航不会改写参考页的参数和筛选。
// 使用 getter 保持 useRoute 的标准对象形状。
const routeProxy = {} as RouteLocationNormalizedLoaded
for (const key of Object.keys(router.currentRoute.value)) {
  Object.defineProperty(routeProxy, key, {
    enumerable: true,
    get: () => props.location[key as keyof RouteLocationNormalizedLoaded],
  })
}
provide(routeLocationKey, routeProxy)
async function navigate(to: RouteLocationRaw, replace = false) {
  const resolved = router.resolve(to, props.location)
  const normalized = { ...resolved, name: resolved.name ?? undefined }
  if (
    props.floating &&
    resolved.name === props.location.name &&
    tabFromRoute(normalized)?.key === props.pageKey
  ) {
    const destination = { ...props.location, ...normalized, matched: props.location.matched }
    for (const guard of guards) {
      const result = await runGuard(guard, destination, props.location)
      if (result === false) return
      if (result instanceof Error) throw result
      if (result && typeof result !== 'boolean')
        return navigate(result as RouteLocationRaw, replace)
    }
    emit('navigate', destination)
    return
  }
  return replace ? router.replace(resolved.fullPath) : router.push(resolved.fullPath)
}
provide(
  routerKey,
  new Proxy(router, {
    get(target, key) {
      if (key === 'currentRoute') return computed(() => props.location)
      if (key === 'push') return (to: RouteLocationRaw) => navigate(to)
      if (key === 'replace') return (to: RouteLocationRaw) => navigate(to, true)
      if (key === 'resolve') return (to: RouteLocationRaw) => router.resolve(to, props.location)
      // 悬浮页仍留在原处，主工作区切换不应触发它的离开确认。
      if (key === 'beforeEach')
        return (guard: NavigationGuard) => {
          guards.add(guard)
          const remove = router.beforeEach((to, from) => {
            if (props.floating || tabFromRoute(from)?.key !== props.pageKey) return true
            return runGuard(guard, { ...to, matched: props.location.matched }, from)
          })
          return () => {
            guards.delete(guard)
            remove()
          }
        }
      return Reflect.get(target, key)
    },
  }),
)
</script>

<template><RouterView :route="location" /></template>
