<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { getVersion } from '@tauri-apps/api/app'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { openUrl } from '@tauri-apps/plugin-opener'
import type { Update } from '@tauri-apps/plugin-updater'
import waoPuppySprites from './assets/wao-puppy-sprites-v2.png'
import {
  dragDirectionFromDelta,
  PET_ANIMATIONS,
  spritePosition,
  type DragDirection,
  type PetAnimation,
} from './petAnimation'
import { findUpdate, installUpdate, type UpdateState } from './update'

const WAO_URL = 'https://wao.wayuwa.cn'
const appWindow = getCurrentWindow()
const version = ref('')
const panelOpen = ref(false)
const updateState = ref<UpdateState>({ phase: 'idle' })
const pendingUpdate = shallowRef<Update | null>(null)
const unlisteners: UnlistenFn[] = []
let initialUpdateTimer: ReturnType<typeof setTimeout> | undefined
let animationTimer: ReturnType<typeof setInterval> | undefined
let sleepTimer: ReturnType<typeof setTimeout> | undefined
const petAnimation = ref<PetAnimation>('idle')
const petFrame = ref(0)
let frameCursor = 0
let dragging = false
let dragOrigin: { x: number, y: number } | undefined
let dragDirection: DragDirection = 'neutral'

const petSpriteStyle = computed(() => ({
  backgroundImage: `url(${waoPuppySprites})`,
  backgroundPosition: spritePosition(petFrame.value),
}))

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

function runPetAnimation(name: PetAnimation, after?: () => void) {
  if (animationTimer) clearTimeout(animationTimer)
  const sequence = PET_ANIMATIONS[name]
  petAnimation.value = name
  frameCursor = 0
  petFrame.value = sequence.frames[0]

  const advanceFrame = () => {
    frameCursor += 1
    if (frameCursor >= sequence.frames.length) {
      if (sequence.loop) {
        frameCursor = 0
      } else {
        animationTimer = undefined
        if (after) after()
        else runPetAnimation('idle')
        return
      }
    }
    petFrame.value = sequence.frames[frameCursor]
    animationTimer = setTimeout(advanceFrame, sequence.durations[frameCursor])
  }

  animationTimer = setTimeout(advanceFrame, sequence.durations[0])
}

function resetSleepTimer() {
  if (sleepTimer) clearTimeout(sleepTimer)
  sleepTimer = setTimeout(() => {
    if (!busy.value) runPetAnimation('sleepy', () => runPetAnimation('sleeping'))
  }, 45_000)
}

function wakePet() {
  resetSleepTimer()
  if (petAnimation.value === 'sleeping' || petAnimation.value === 'sleepy') {
    runPetAnimation('wake')
  }
}

function reactToPet() {
  resetSleepTimer()
  if (petAnimation.value === 'sleeping' || petAnimation.value === 'sleepy') {
    runPetAnimation('wake')
  } else if (!busy.value) {
    runPetAnimation('wave')
  }
}

async function startDragging(event: MouseEvent) {
  if ((event.target as HTMLElement).closest('button')) return
  resetSleepTimer()
  const position = await appWindow.outerPosition()
  dragOrigin = { x: position.x, y: position.y }
  dragDirection = 'neutral'
  dragging = true
  if (!busy.value) runPetAnimation('dragging')
  try {
    await appWindow.startDragging()
  } finally {
    dragging = false
    dragOrigin = undefined
    dragDirection = 'neutral'
    if (!busy.value) runPetAnimation('surprised')
  }
}

async function openWao() {
  await openUrl(WAO_URL)
}

onMounted(async () => {
  runPetAnimation('idle')
  resetSleepTimer()
  version.value = await getVersion()
  unlisteners.push(await listen('desktop://check-for-updates', () => void checkForUpdates()))
  unlisteners.push(await listen('desktop://open-wao', () => void openWao()))
  unlisteners.push(await appWindow.onMoved(({ payload: position }) => {
    if (!dragging || !dragOrigin || busy.value) return
    const nextDirection = dragDirectionFromDelta(position.x - dragOrigin.x, position.y - dragOrigin.y)
    if (nextDirection === 'neutral' || nextDirection === dragDirection) return
    dragDirection = nextDirection
    runPetAnimation(`dragging-${nextDirection}`)
  }))
  initialUpdateTimer = setTimeout(() => void checkForUpdates(true), 12_000)
})

onBeforeUnmount(() => {
  if (initialUpdateTimer) clearTimeout(initialUpdateTimer)
  if (animationTimer) clearTimeout(animationTimer)
  if (sleepTimer) clearTimeout(sleepTimer)
  unlisteners.forEach((unlisten) => unlisten())
})

watch(busy, (isBusy) => {
  resetSleepTimer()
  if (isBusy) runPetAnimation('working')
  else if (petAnimation.value === 'working') runPetAnimation('idle')
})
</script>

<template>
  <main class="pet-stage" @mousedown.left="startDragging" @mousemove="wakePet">
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

    <section
      class="pet"
      :class="[`animation-${petAnimation}`, { working: busy }]"
      role="button"
      tabindex="0"
      aria-label="WAO 小沃犬桌面宠物，单击互动，双击打开 WAO"
      @mousedown.left.stop="startDragging"
      @click.stop="reactToPet"
      @dblclick.stop="openWao"
      @keydown.enter="reactToPet"
      @keydown.space.prevent="reactToPet"
    >
      <span
        class="pet-sprite"
        :style="petSpriteStyle"
        aria-hidden="true"
      ></span>
    </section>

    <nav class="pet-actions" aria-label="桌面宠物操作">
      <button title="打开 WAO" aria-label="打开 WAO" @click="openWao"><span>WAO</span></button>
      <button title="检查更新" aria-label="检查更新" @click="checkForUpdates()"><span>↻</span></button>
      <button title="隐藏宠物，可从系统托盘再次显示" aria-label="隐藏宠物" @click="appWindow.hide()"><span>—</span></button>
    </nav>
    <small class="version">v{{ version }}</small>
  </main>
</template>
