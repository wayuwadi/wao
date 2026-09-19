<script setup lang="ts">
import {
  computed,
  markRaw,
  onBeforeUnmount,
  onMounted,
  onUpdated,
  shallowReactive,
  watch,
} from 'vue'
import {
  loadRouteLocation,
  useRoute,
  useRouter,
  type RouteLocationNormalizedLoaded,
} from 'vue-router'
import { tabFromRoute } from '@/config/nav'
import { useTabsStore } from '@/stores/tabs'
import { useFloatingTabsStore } from '@/stores/floatingTabs'
import TabPage from './TabPage.vue'
import TabWindow from './TabWindow.vue'

const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const windows = useFloatingTabsStore()
const emit = defineEmits<{ assistantTarget: [element: HTMLElement | null] }>()
function updateAssistantTarget() {
  emit(
    'assistantTarget',
    active.value === 'chatgpt' || windows.keys.includes('chatgpt')
      ? document.getElementById('chatgpt-workspace')
      : null,
  )
}
onMounted(updateAssistantTarget)
onUpdated(updateAssistantTarget)
onBeforeUnmount(() => {
  windows.keys = []
  windows.front = ''
})
const pages = shallowReactive(new Map<string, RouteLocationNormalizedLoaded>())
const retained = new Set<string>()
const errors = shallowReactive(new Map<string, string>())
const active = computed(() => tabFromRoute(route)?.key || route.path)
function snapshot(location: RouteLocationNormalizedLoaded) {
  return markRaw({ ...location })
}
watch(
  () => route.fullPath,
  () => {
    if (!windows.keys.includes(active.value) || !pages.has(active.value))
      pages.set(active.value, snapshot(route))
    if (active.value.startsWith('kb:')) retained.add(active.value)
    for (const key of pages.keys()) {
      if (key !== active.value && !retained.has(key) && !windows.keys.includes(key))
        pages.delete(key)
    }
  },
  { immediate: true, flush: 'sync' },
)

async function loadPage(key: string) {
  const tab = tabs.tabs.find((item) => item.key === key)
  if (!tab) return
  errors.delete(key)
  try {
    const location = await loadRouteLocation(router.resolve(tab.path))
    if (windows.keys.includes(key)) pages.set(key, snapshot(location))
  } catch {
    errors.set(key, '页面加载失败，请重试')
  }
}
watch(
  () => [...windows.keys],
  async (keys) => {
    for (const key of keys) {
      retained.add(key)
      if (!pages.has(key)) void loadPage(key)
    }
    if (keys.includes(active.value)) {
      const next = tabs.tabs.find((tab) => !keys.includes(tab.key))
      if (next) await router.push(next.path)
    }
  },
)
watch(
  () => tabs.tabs.map((tab) => tab.key),
  (keys) => {
    for (const key of new Set([...pages.keys(), ...windows.keys])) {
      if (!keys.includes(key)) {
        pages.delete(key)
        retained.delete(key)
        errors.delete(key)
        windows.dock(key)
      }
    }
  },
)
async function dock(key: string) {
  const location = pages.get(key)
  const tab = tabs.tabs.find((item) => item.key === key)
  if (!tab) return
  const failure = await router.push(location?.fullPath || tab.path)
  if (!failure || router.currentRoute.value.fullPath === location?.fullPath) windows.dock(key)
}
const entries = computed(() => [...new Set([...pages.keys(), ...windows.keys])])
function rememberLocation(key: string, location: RouteLocationNormalizedLoaded) {
  pages.set(key, snapshot(location))
  const tab = tabs.tabs.find((item) => item.key === key)
  if (tab) tab.path = location.fullPath
}
</script>

<template>
  <TabWindow
    v-for="key in entries"
    :key="key"
    :page-key="key"
    :label="tabs.tabs.find((tab) => tab.key === key)?.label || '页面'"
    :floating="windows.keys.includes(key)"
    :visible="key === active || windows.keys.includes(key)"
    @dock="dock(key)"
  >
    <TabPage
      v-if="pages.has(key)"
      :page-key="key"
      :location="pages.get(key)!"
      :floating="windows.keys.includes(key)"
      @navigate="rememberLocation(key, $event)"
    />
    <div v-else-if="errors.has(key)" role="alert">
      {{ errors.get(key) }} <button @click="loadPage(key)">重试</button>
    </div>
    <div v-else role="status">正在加载页面…</div>
  </TabWindow>
  <p v-if="windows.keys.includes(active)">
    此页面已在悬浮窗口打开。<button @click="dock(active)">收回 Tab</button>
  </p>
</template>
