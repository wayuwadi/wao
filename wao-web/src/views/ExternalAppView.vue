<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiExternalAppHealth } from '@/api/agent'
import { useNavStore } from '@/stores/nav'

type State = 'checking' | 'loading' | 'ready' | 'error' | 'missing'

const route = useRoute()
const router = useRouter()
const nav = useNavStore()
const state = ref<State>('checking')
const frameKey = ref(0)
const slow = ref(false)
let frameTimer: ReturnType<typeof setTimeout> | undefined
watch([state, frameKey], () => {
  clearTimeout(frameTimer)
  slow.value = false
  if (state.value === 'loading') frameTimer = setTimeout(() => { slow.value = true }, 15_000)
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { clearTimeout(frameTimer); requestSequence++ })

const errorMessage = ref('')
let requestSequence = 0

const appId = computed(() => String(route.params.appId ?? ''))
const app = computed(() => nav.externalAppById(appId.value))

async function loadApp() {
  const sequence = ++requestSequence
  errorMessage.value = ''
  if (!app.value) {
    if (nav.externalAppsState === 'idle' || nav.externalAppsState === 'loading') {
      state.value = 'checking'
      return
    }
    if (nav.externalAppsState === 'error') {
      state.value = 'error'
      errorMessage.value = nav.externalAppsError || '外部应用注册表读取失败'
      return
    }
    state.value = 'missing'
    return
  }

  state.value = 'checking'
  try {
    if (app.value.healthUrl) await apiExternalAppHealth(app.value.healthUrl)
    if (sequence !== requestSequence) return
    frameKey.value += 1
    state.value = 'loading'
  } catch (error) {
    if (sequence !== requestSequence) return
    state.value = 'error'
    errorMessage.value = error instanceof Error ? error.message : '应用服务暂时不可用'
  }
}

function frameLoaded() {
  state.value = 'ready'
}

function frameFailed() {
  state.value = 'error'
  errorMessage.value = '应用页面加载失败，请稍后重试'
}

watch(
  () => [appId.value, app.value?.appUrl, app.value?.healthUrl, nav.externalAppsState],
  loadApp,
  { immediate: true },
)

onMounted(() => nav.loadExternalApps())
</script>

<template>
  <section class="external-host">
    <div class="external-toolbar">
      <div>
        <span class="status-dot" :class="state"></span>
        <span>{{ state === 'ready' ? `「${app?.label ?? '应用'}」页面已载入` : state === 'error' ? '应用不可用' : state === 'missing' ? '应用未注册' : '正在连接应用' }}</span>
      </div>
      <div class="actions">
        <button v-if="app" type="button" @click="loadApp"><i class="ri-refresh-line"></i>重新加载</button>
        <a v-if="app" :href="app.appUrl" target="_blank" rel="noopener"><i class="ri-external-link-line"></i>新窗口打开</a>
      </div>
    </div>

    <p v-if="app" class="embed-help" role="status">{{ slow ? '加载时间较长。可以继续等待、重新加载，或在新窗口打开。' : '若页面空白、提示登录或无法显示，请在新窗口打开；页面载入不代表应用已登录或可用。' }}</p>
    <div class="external-frame-wrap">
      <iframe
        v-if="app && (state === 'loading' || state === 'ready')"
        :key="frameKey"
        class="external-frame"
        :src="app.appUrl"
        :title="app.label"
        @load="frameLoaded"
        @error="frameFailed"
      ></iframe>

      <div v-if="state === 'checking' || (state === 'loading' && !slow)" class="state-card overlay" role="status">
        <span class="spinner"></span>
        <strong>正在打开{{ app?.label ? `「${app.label}」` : '外部应用' }}</strong>
        <p>正在加载应用，请稍候。</p>
      </div>

      <div v-if="state === 'missing'" class="state-card">
        <i class="ri-apps-2-line"></i>
        <strong>这个外部应用尚未注册</strong>
        <p>应用标识：{{ appId }}</p>
        <button type="button" @click="router.push('/settings?tab=navigation')">打开应用注册表</button>
      </div>

      <div v-else-if="state === 'error'" class="state-card">
        <i class="ri-cloud-off-line"></i>
        <strong>{{ app?.label ?? '外部应用' }}暂时无法连接</strong>
        <p>{{ errorMessage }}</p>
        <button type="button" @click="loadApp">重试</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.embed-help { flex: none; margin: 0; padding: 8px 14px; font-size: 12px; line-height: 1.6; color: var(--wao-text-weak); background: var(--wao-bg-sub); border-bottom: 1px solid var(--wao-line); }
.external-host { min-height: 560px; height: calc(100vh - var(--wao-header-h) - 42px); display: flex; flex-direction: column; background: var(--wao-bg-page); }
.external-toolbar { height: 42px; padding: 0 14px; flex: none; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--wao-line); background: var(--wao-bg-card); color: var(--wao-text-weak); font-size: 12px; }
.external-toolbar > div { display: flex; align-items: center; gap: 7px; }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: #d59a2e; box-shadow: 0 0 0 3px #fff3dc; }
.status-dot.ready { background: var(--wao-text-weak); box-shadow: none; }
.status-dot.error, .status-dot.missing { background: #d84b4b; box-shadow: 0 0 0 3px #fde8e8; }
.actions { display: flex; gap: 5px; }
.actions button, .actions a { height: 28px; padding: 0 9px; display: inline-flex; align-items: center; gap: 5px; border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-2); text-decoration: none; cursor: pointer; }
.actions button:hover, .actions a:hover { background: var(--wao-bg-hover-2); }
.external-frame-wrap { position: relative; flex: 1; min-height: 0; }
.external-frame { width: 100%; height: 100%; display: block; border: 0; background: var(--wao-bg-page); }
.state-card { position: absolute; inset: 0; display: grid; place-items: center; align-content: center; gap: 9px; background: var(--wao-bg-sub); text-align: center; color: var(--wao-text-weak); }
.state-card.overlay { z-index: 1; }
.state-card > i { font-size: 42px; color: #8b7fe5; }
.state-card strong { color: var(--wao-text); font-size: 15px; }
.state-card p { margin: 0; font-size: 12px; }
.state-card button { height: 34px; margin-top: 7px; padding: 0 15px; border: 0; border-radius: 8px; background: var(--wao-primary); color: #fff; cursor: pointer; }
.spinner { width: 23px; height: 23px; border: 3px solid #ddd8ff; border-top-color: #6556d8; border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) { .external-host { height: calc(100dvh - 112px); min-height: 420px; } .external-toolbar { padding: 0 9px; } .external-toolbar > div:first-child { display: none; } .actions { width: 100%; justify-content: flex-end; } }
</style>
