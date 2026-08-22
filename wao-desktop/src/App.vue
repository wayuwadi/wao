<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { getVersion } from '@tauri-apps/api/app'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { openUrl } from '@tauri-apps/plugin-opener'
import type { Update } from '@tauri-apps/plugin-updater'
import { findUpdate, installUpdate, type UpdateState } from './update'

const WAO_URL = 'https://wao.wayuwa.cn'
const appWindow = getCurrentWindow()
const version = ref('')
const panelOpen = ref(false)
const updateState = ref<UpdateState>({ phase: 'idle' })
const pendingUpdate = shallowRef<Update | null>(null)
const unlisteners: UnlistenFn[] = []
let initialUpdateTimer: ReturnType<typeof setTimeout> | undefined

const message = computed(() => {
  if (updateState.value.phase === 'available') return `新版本 ${updateState.value.version} 已经准备好。`
  if (updateState.value.phase === 'downloading') return updateState.value.progress === undefined
    ? '正在把新版本带回来…'
    : `正在更新，已经完成 ${updateState.value.progress}%`
  if (updateState.value.phase === 'ready') return '马上以新版本回来。'
  if (updateState.value.phase === 'error') return '刚才没更新成功，旧版本还好好的。'
  if (updateState.value.phase === 'current') return '我已经是最新版本。'
  return '今天也一起，把重要的事情慢慢做好。'
})

const busy = computed(() => ['checking', 'downloading', 'ready'].includes(updateState.value.phase))

async function checkForUpdates(silent = false) {
  if (busy.value) return
  if (!silent) panelOpen.value = true
  updateState.value = { phase: 'checking', message: '正在检查更新…' }
  const result = await findUpdate()
  pendingUpdate.value = result.update
  updateState.value = result.state
  if (silent && result.state.phase !== 'available') panelOpen.value = false
  if (result.state.phase === 'available') panelOpen.value = true
}

async function applyUpdate() {
  if (!pendingUpdate.value || busy.value) return
  await installUpdate(pendingUpdate.value, (state) => { updateState.value = state })
}

function startDragging(event: MouseEvent) {
  if ((event.target as HTMLElement).closest('button')) return
  void appWindow.startDragging()
}

async function openWao() {
  await openUrl(WAO_URL)
}

onMounted(async () => {
  version.value = await getVersion()
  unlisteners.push(await listen('desktop://check-for-updates', () => void checkForUpdates()))
  unlisteners.push(await listen('desktop://open-wao', () => void openWao()))
  initialUpdateTimer = setTimeout(() => void checkForUpdates(true), 12_000)
})

onBeforeUnmount(() => {
  if (initialUpdateTimer) clearTimeout(initialUpdateTimer)
  unlisteners.forEach((unlisten) => unlisten())
})
</script>

<template>
  <main class="pet-stage" @mousedown.left="startDragging">
    <section v-if="panelOpen" class="bubble" aria-live="polite">
      <button class="bubble-close" aria-label="关闭更新面板" @click="panelOpen = false">×</button>
      <strong>{{ message }}</strong>
      <p v-if="updateState.notes && updateState.phase === 'available'">{{ updateState.notes }}</p>
      <p v-else-if="updateState.message && updateState.phase === 'error'">{{ updateState.message }}</p>
      <div v-if="updateState.phase === 'downloading'" class="progress" role="progressbar" :aria-valuenow="updateState.progress">
        <i :style="{ width: `${updateState.progress ?? 18}%` }"></i>
      </div>
      <div class="bubble-actions">
        <button v-if="updateState.phase === 'available'" class="primary" @click="applyUpdate">现在更新</button>
        <button v-else-if="updateState.phase === 'error'" @click="checkForUpdates()">再试一次</button>
        <button v-else-if="!busy" @click="panelOpen = false">知道了</button>
      </div>
    </section>

    <section class="pet" :class="{ working: busy }" aria-label="WAO 桌面宠物">
      <div class="ears" aria-hidden="true"><i></i><i></i></div>
      <div class="body">
        <div class="eyes"><i></i><i></i></div>
        <div class="mouth"></div>
        <div class="cheeks"><i></i><i></i></div>
        <span class="mark">W</span>
      </div>
      <div class="shadow"></div>
    </section>

    <nav class="pet-actions" aria-label="桌面宠物操作">
      <button title="打开 WAO" aria-label="打开 WAO" @click="openWao"><span>WAO</span></button>
      <button title="检查更新" aria-label="检查更新" @click="checkForUpdates()"><span>↻</span></button>
      <button title="隐藏宠物，可从系统托盘再次显示" aria-label="隐藏宠物" @click="appWindow.hide()"><span>—</span></button>
    </nav>
    <small class="version">v{{ version }}</small>
  </main>
</template>
