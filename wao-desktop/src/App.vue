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
const updatePanelOpen = ref(false)
const featurePanelOpen = ref(false)
const resting = ref(true)
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
  backgroundPosition: spritePosition(resting.value ? 0 : petFrame.value),
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

const petAriaLabel = computed(() => resting.value
  ? 'WAO 小沃犬正在窗口边缘休息，悬浮或单击唤醒'
  : 'WAO 小沃犬桌面宠物，单击查看功能，双击打开 WAO')

async function checkForUpdates(silent = false) {
  if (busy.value) return
  if (!silent) {
    wakePet()
    featurePanelOpen.value = false
    updatePanelOpen.value = true
  }
  updateState.value = { phase: 'checking', message: '正在检查更新…' }
  const result = await findUpdate()
  pendingUpdate.value = result.update
  updateState.value = result.state
  if (silent && result.state.phase !== 'available') updatePanelOpen.value = false
  if (result.state.phase === 'available') {
    wakePet()
    updatePanelOpen.value = true
  }
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
  sleepTimer = undefined
  if (resting.value || busy.value || updatePanelOpen.value || featurePanelOpen.value) return
  sleepTimer = setTimeout(() => {
    sleepTimer = undefined
    if (busy.value || updatePanelOpen.value || featurePanelOpen.value) return
    resting.value = true
    runPetAnimation('sleepy', () => {
      if (resting.value) runPetAnimation('sleeping')
    })
  }, 45_000)
}

function wakePet() {
  const wasResting = resting.value
  resting.value = false
  if (petAnimation.value === 'sleeping' || petAnimation.value === 'sleepy') {
    runPetAnimation('wake')
  }
  if (wasResting || !sleepTimer) resetSleepTimer()
}

function reactToPet() {
  wakePet()
  if (busy.value) return
  updatePanelOpen.value = false
  featurePanelOpen.value = !featurePanelOpen.value
  runPetAnimation('wave')
  resetSleepTimer()
}

function closeFeaturePanel() {
  featurePanelOpen.value = false
  resetSleepTimer()
}

function closeUpdatePanel() {
  updatePanelOpen.value = false
  resetSleepTimer()
}

async function hidePet() {
  featurePanelOpen.value = false
  updatePanelOpen.value = false
  resting.value = true
  runPetAnimation('sleeping')
  await appWindow.hide()
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
  closeFeaturePanel()
  await openUrl(WAO_URL)
}

onMounted(async () => {
  runPetAnimation('sleeping')
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
  if (isBusy && !resting.value) runPetAnimation('working')
  else {
    if (petAnimation.value === 'working') runPetAnimation('idle')
    if (!resting.value && !sleepTimer) resetSleepTimer()
  }
})
</script>

<template>
  <main class="pet-stage" :class="{ 'is-resting': resting }" @mousedown.left="startDragging">
    <section v-if="updatePanelOpen" class="bubble update-panel" aria-live="polite">
      <button class="bubble-close" aria-label="关闭更新面板" @click="closeUpdatePanel">×</button>
      <strong>{{ message }}</strong>
      <p v-if="updateState.notes && updateState.phase === 'available'">{{ updateState.notes }}</p>
      <p v-else-if="updateState.message && updateState.phase === 'error'">{{ updateState.message }}</p>
      <div v-if="updateState.phase === 'downloading'" class="progress" role="progressbar" :aria-valuenow="updateState.progress">
        <i :style="{ width: `${updateState.progress ?? 18}%` }"></i>
      </div>
      <div class="bubble-actions">
        <button v-if="updateState.phase === 'available'" class="primary" @click="applyUpdate">现在更新</button>
        <button v-else-if="updateState.phase === 'error'" @click="checkForUpdates()">再试一次</button>
        <button v-else-if="!busy" @click="closeUpdatePanel">知道了</button>
      </div>
    </section>

    <section v-if="featurePanelOpen" id="pet-feature-panel" class="bubble feature-panel" aria-label="桌面宠物功能清单">
      <div class="feature-heading">
        <span aria-hidden="true">✨</span>
        <div><strong>小沃犬的功能</strong><small>3 项真实可用</small></div>
      </div>
      <button class="bubble-close" aria-label="关闭功能清单" @click="closeFeaturePanel">×</button>
      <div class="feature-list">
        <button @click="openWao">
          <span class="feature-icon" aria-hidden="true">W</span>
          <span><b>打开 WAO</b><small>在浏览器进入完整工作台</small></span>
          <i aria-hidden="true">›</i>
        </button>
        <button @click="checkForUpdates()">
          <span class="feature-icon" aria-hidden="true">↻</span>
          <span><b>检查更新</b><small>获取经过签名验证的新版本</small></span>
          <i aria-hidden="true">›</i>
        </button>
        <button @click="hidePet">
          <span class="feature-icon" aria-hidden="true">—</span>
          <span><b>暂时隐藏</b><small>可从系统托盘再次唤回</small></span>
          <i aria-hidden="true">›</i>
        </button>
      </div>
    </section>

    <div class="pet-dock" :class="{ resting }">
      <section
        class="pet"
        :class="[`animation-${petAnimation}`, { working: busy }]"
        role="button"
        tabindex="0"
        :aria-label="petAriaLabel"
        :aria-expanded="featurePanelOpen"
        aria-controls="pet-feature-panel"
        @mouseenter="wakePet"
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
    </div>

    <nav v-if="!resting && !featurePanelOpen && !updatePanelOpen" class="pet-actions" aria-label="桌面宠物快捷操作">
      <button title="打开 WAO" aria-label="打开 WAO" @click="openWao"><span>WAO</span></button>
      <button title="检查更新" aria-label="检查更新" @click="checkForUpdates()"><span>↻</span></button>
      <button title="隐藏宠物，可从系统托盘再次显示" aria-label="隐藏宠物" @click="hidePet"><span>—</span></button>
    </nav>
    <small v-if="!resting" class="version">v{{ version }}</small>
  </main>
</template>
