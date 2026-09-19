<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuickLinks } from '@/composables/quickLinks'
import { useTabsStore } from '@/stores/tabs'

type State = 'loading' | 'ready' | 'missing' | 'error'

const route = useRoute()
const router = useRouter()
const quickLinks = useQuickLinks()
const tabs = useTabsStore()
const state = ref<State>('loading')
const frameKey = ref(0)
const slow = ref(false)
let frameTimer: ReturnType<typeof setTimeout> | undefined
watch([state, frameKey], () => {
  clearTimeout(frameTimer)
  slow.value = false
  if (state.value === 'loading') frameTimer = setTimeout(() => { slow.value = true }, 15_000)
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { clearTimeout(frameTimer) })


const linkId = computed(() => String(route.params.linkId ?? ''))
const link = computed(() => quickLinks.links.value.find((item) => item.id === linkId.value))

function loadFrame() {
  if (!link.value) {
    state.value = quickLinks.loading.value ? 'loading' : 'missing'
    return
  }
  state.value = 'loading'
  frameKey.value += 1
  tabs.setMeta(`quick-link:${link.value.id}`, { label: link.value.label, icon: link.value.icon })
}

function frameLoaded() {
  state.value = 'ready'
}

watch(
  () => [linkId.value, link.value?.url, quickLinks.loading.value],
  loadFrame,
  { immediate: true },
)

onMounted(async () => {
  await quickLinks.load()
  loadFrame()
})
</script>

<template>
  <section class="quick-link-host">
    <div class="quick-link-toolbar">
      <div>
        <span class="status-dot" :class="state"></span>
        <span>{{ state === 'ready' ? `「${link?.label ?? '快捷链接'}」页面已载入` : state === 'error' ? '页面未能载入' : state === 'missing' ? '快捷链接不存在' : '正在加载快捷链接' }}</span>
      </div>
      <div class="actions">
        <button v-if="link" type="button" @click="loadFrame"><i class="ri-refresh-line"></i>重新加载</button>
        <a v-if="link" :href="link.url" target="_blank" rel="noopener"><i class="ri-external-link-line"></i>新页面打开</a>
      </div>
    </div>

    <p v-if="link" class="embed-help" role="status">{{ slow ? '加载时间较长。可以继续等待、重新加载，或在新页面打开。' : '若页面空白、提示登录或无法显示，请在新页面打开；页面载入不代表应用已登录或可用。' }}</p>
    <div class="quick-link-frame-wrap">
      <iframe
        v-if="link"
        :key="frameKey"
        class="quick-link-frame"
        :src="link.url"
        :title="link.label"
        referrerpolicy="strict-origin-when-cross-origin"
        @load="frameLoaded"
        @error="state = 'error'"
      ></iframe>

      <div v-if="state === 'loading' && (!slow || !link)" class="state-card overlay" role="status">
        <span class="spinner"></span>
        <strong>正在打开{{ link?.label ? `「${link.label}」` : '快捷链接' }}</strong>
        <p>站内地址会沿用当前 WAO 登录状态。</p>
      </div>

      <div v-else-if="state === 'error'" class="state-card" role="alert">
        <strong>页面未能载入</strong><p>可以重新加载，或使用上方“新页面打开”。</p>
        <button type="button" @click="loadFrame">重新加载</button>
      </div>
      <div v-else-if="state === 'missing'" class="state-card">
        <i class="ri-link-unlink-m"></i>
        <strong>这个快捷链接不存在或已被删除</strong>
        <p>链接标识：{{ linkId }}</p>
        <button type="button" @click="router.push('/settings?tab=navigation')">管理快捷链接</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.embed-help { flex: none; margin: 0; padding: 8px 14px; font-size: 12px; line-height: 1.6; color: var(--wao-text-weak); background: var(--wao-bg-sub); border-bottom: 1px solid var(--wao-line); }
.quick-link-host { min-height: 560px; height: calc(100vh - var(--wao-header-h) - 42px); display: flex; flex-direction: column; background: var(--wao-bg-page); }
.quick-link-toolbar { height: 42px; padding: 0 14px; flex: none; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--wao-line); background: var(--wao-bg-card); color: var(--wao-text-weak); font-size: 12px; }
.quick-link-toolbar > div { display: flex; align-items: center; gap: 7px; }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: #d59a2e; box-shadow: 0 0 0 3px #fff3dc; }
.status-dot.ready { background: var(--wao-text-weak); box-shadow: none; }
.status-dot.missing { background: #d84b4b; box-shadow: 0 0 0 3px #fde8e8; }
.actions { display: flex; gap: 5px; }
.actions button, .actions a { height: 28px; padding: 0 9px; display: inline-flex; align-items: center; gap: 5px; border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-2); text-decoration: none; cursor: pointer; }
.actions button:hover, .actions a:hover { background: var(--wao-bg-hover-2); }
.quick-link-frame-wrap { position: relative; flex: 1; min-height: 0; }
.quick-link-frame { width: 100%; height: 100%; display: block; border: 0; background: var(--wao-bg-page); }
.state-card { position: absolute; inset: 0; display: grid; place-items: center; align-content: center; gap: 9px; background: var(--wao-bg-sub); text-align: center; color: var(--wao-text-weak); }
.state-card.overlay { z-index: 1; }
.state-card > i { font-size: 42px; color: #8b7fe5; }
.state-card strong { color: var(--wao-text); font-size: 15px; }
.state-card p { margin: 0; font-size: 12px; }
.state-card button { height: 34px; margin-top: 7px; padding: 0 15px; border: 0; border-radius: 8px; background: var(--wao-primary); color: #fff; cursor: pointer; }
.spinner { width: 23px; height: 23px; border: 3px solid #ddd8ff; border-top-color: #6556d8; border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) { .quick-link-host { height: calc(100dvh - 112px); min-height: 420px; } .quick-link-toolbar { padding: 0 9px; } .quick-link-toolbar > div:first-child { display: none; } .actions { width: 100%; justify-content: flex-end; } }
</style>
