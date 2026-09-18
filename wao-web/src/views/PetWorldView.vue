<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useSessionStore } from '@/stores/session'
import {
  initialWorldState,
  jump,
  LANDMARKS,
  serializeWorld,
  stepWorld,
} from '@/features/pet-world/world'

const session = useSessionStore()
const host = ref<HTMLElement>()
const loadState = ref<'loading' | 'ready' | 'error'>('loading')
const saveState = ref<'saved' | 'saving' | 'error' | 'session'>('session')
const focused = ref(false)
const state = reactive(initialWorldState())
const yaw = ref(0.25),
  distance = ref(17)
const keys = new Set<string>()
const touchKeys = new Map<number, string>()
let controller: AbortController | undefined
let scene:
  Awaited<ReturnType<typeof import('@/features/pet-world/scene').createWorldScene>> | undefined
let frame = 0,
  lastTime = 0,
  waveUntil = 0,
  drag: { id: number; x: number } | undefined
let disposed = false
const storageKey = computed(() => (session.userId ? `wao-pet-world-v1:${session.userId}` : ''))
const landmark = computed(() =>
  LANDMARKS.find((l) => Math.hypot(state.x - l.x, state.z - l.z) < 2.5),
)
const status = computed(() => (state.y > 0 ? '轻身一跃' : state.moving ? '自在行走' : '驻足观景'))
const saveLabel = computed(
  () =>
    ({ saved: '位置已保存', saving: '正在保存', error: '位置未保存', session: '本次游玩' })[
      saveState.value
    ],
)
function save() {
  if (!storageKey.value) {
    saveState.value = 'session'
    return
  }
  saveState.value = 'saving'
  try {
    localStorage.setItem(storageKey.value, serializeWorld(state))
    saveState.value = 'saved'
  } catch {
    saveState.value = 'error'
  }
}
function stop() {
  keys.clear()
  touchKeys.clear()
  drag = undefined
  state.moving = false
  save()
}
function wave() {
  waveUntil = performance.now() + 700
}
function sceneBlur() {
  focused.value = false
  stop()
}
function jumpAction() {
  jump(state)
  host.value?.focus()
}
function waveAction() {
  wave()
  host.value?.focus()
}
function reset() {
  stop()
  Object.assign(state, initialWorldState())
  yaw.value = 0.25
  distance.value = 17
  save()
  host.value?.focus()
}
const controlCodes = [
  'KeyW',
  'KeyA',
  'KeyS',
  'KeyD',
  'ArrowUp',
  'ArrowLeft',
  'ArrowDown',
  'ArrowRight',
  'Space',
  'ShiftLeft',
  'ShiftRight',
  'KeyE',
]
function keyDown(event: KeyboardEvent) {
  if (event.target !== host.value || !controlCodes.includes(event.code)) return
  event.preventDefault()
  if (loadState.value !== 'ready') return
  keys.add(event.code)
  if (!event.repeat && event.code === 'Space') jump(state)
  if (!event.repeat && event.code === 'KeyE') wave()
}
function keyUp(event: KeyboardEvent) {
  keys.delete(event.code)
}
function pointerDown(event: PointerEvent) {
  if (event.button !== 0 || loadState.value !== 'ready') return
  host.value?.focus()
  host.value?.setPointerCapture(event.pointerId)
  drag = { id: event.pointerId, x: event.clientX }
}
function pointerMove(event: PointerEvent) {
  if (!drag || drag.id !== event.pointerId) return
  yaw.value += (event.clientX - drag.x) * 0.006
  drag.x = event.clientX
}
function pointerEnd(event: PointerEvent) {
  if (drag?.id === event.pointerId) drag = undefined
  if (host.value?.hasPointerCapture(event.pointerId))
    host.value.releasePointerCapture(event.pointerId)
  touchKeys.delete(event.pointerId)
}
function touchStart(event: PointerEvent, code: string) {
  if (loadState.value !== 'ready') return
  event.preventDefault()
  host.value?.focus()
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  touchKeys.set(event.pointerId, code)
}
function zoom(event: WheelEvent) {
  if (focused.value) {
    event.preventDefault()
    distance.value = Math.max(8, Math.min(23, distance.value + event.deltaY * 0.012))
  }
}
function loop(time: number) {
  if (disposed || loadState.value !== 'ready' || document.hidden) return
  const active = new Set([...keys, ...touchKeys.values()])
  const before = state.moving
  stepWorld(
    state,
    {
      right:
        Number(active.has('KeyD') || active.has('ArrowRight')) -
        Number(active.has('KeyA') || active.has('ArrowLeft')),
      forward:
        Number(active.has('KeyW') || active.has('ArrowUp')) -
        Number(active.has('KeyS') || active.has('ArrowDown')),
      sprint: active.has('ShiftLeft') || active.has('ShiftRight'),
    },
    yaw.value,
    lastTime ? (time - lastTime) / 1000 : 0,
  )
  lastTime = time
  if (state.moving && saveState.value !== 'error') saveState.value = 'session'
  if (before && !state.moving) save()
  scene?.render(state, yaw.value, distance.value, waveUntil > time, time)
  frame = requestAnimationFrame(loop)
}
function visibility() {
  stop()
  cancelAnimationFrame(frame)
  lastTime = 0
  if (!document.hidden && loadState.value === 'ready') frame = requestAnimationFrame(loop)
}
async function enter() {
  loadState.value = 'loading'
  cancelAnimationFrame(frame)
  stop()
  controller?.abort()
  controller = new AbortController()
  const current = controller
  try {
    const { createWorldScene } = await import('@/features/pet-world/scene')
    if (current.signal.aborted || !host.value) return
    const created = await createWorldScene(host.value, current.signal, () => {
      loadState.value = 'error'
      stop()
      cancelAnimationFrame(frame)
    })
    if (current.signal.aborted) {
      created.dispose()
      return
    }
    scene = created
    loadState.value = 'ready'
    lastTime = 0
    frame = requestAnimationFrame(loop)
  } catch {
    if (!current.signal.aborted) loadState.value = 'error'
  }
}
onMounted(() => {
  try {
    if (storageKey.value)
      Object.assign(state, initialWorldState(localStorage.getItem(storageKey.value)))
  } catch {
    saveState.value = 'error'
  }
  void enter()
  window.addEventListener('keyup', keyUp)
  window.addEventListener('blur', stop)
  document.addEventListener('visibilitychange', visibility)
})
onBeforeUnmount(() => {
  disposed = true
  stop()
  cancelAnimationFrame(frame)
  controller?.abort()
  scene?.dispose()
  window.removeEventListener('keyup', keyUp)
  window.removeEventListener('blur', stop)
  document.removeEventListener('visibilitychange', visibility)
})
</script>

<template>
  <main class="pet-world-page">
    <header class="world-heading">
      <div>
        <span class="eyebrow">张三丰的小世界 · 单人原型</span>
        <h1>松风庭院 <span>山间有清风，心中有天地。</span></h1>
      </div>
      <button class="return-button" @click="reset">
        <i class="ri-compass-3-line" /> 回到太极台
      </button>
    </header>
    <section class="world-shell" :aria-busy="loadState === 'loading'">
      <div
        ref="host"
        class="world-canvas"
        tabindex="0"
        role="application"
        aria-label="松风庭院，点击后用方向键行走，空格跳跃，拖动旋转镜头"
        :data-world-state="loadState"
        :data-player-x="state.x.toFixed(3)"
        :data-player-z="state.z.toFixed(3)"
        :data-player-height="state.y.toFixed(3)"
        :data-moving="state.moving"
        @keydown="keyDown"
        @focus="focused = true"
        @blur="sceneBlur"
        @pointerdown="pointerDown"
        @pointermove="pointerMove"
        @pointerup="pointerEnd"
        @pointercancel="pointerEnd"
        @lostpointercapture="pointerEnd"
        @wheel="zoom"
      />
      <div v-if="loadState !== 'ready'" class="world-overlay" role="status">
        <i
          :class="loadState === 'loading' ? 'ri-loader-4-line loading-icon' : 'ri-landscape-line'"
        />
        <h2>{{ loadState === 'loading' ? '正在推开山门…' : '暂时没能进入庭院' }}</h2>
        <p>
          {{
            loadState === 'loading'
              ? '松风、石径，还有一位老朋友。'
              : '请检查网络，或使用支持 3D 加速的浏览器后重试。'
          }}
        </p>
        <button v-if="loadState === 'error'" class="return-button" @click="enter">重新进入</button>
      </div>
      <template v-else>
        <div class="world-location">
          <span class="live-dot" /> {{ landmark?.name ?? '庭院漫步' }} <small>{{ status }}</small>
        </div>
        <div class="mini-map" aria-label="庭院小地图">
          <span class="map-title">松风庭院 <span>北 ↑</span></span>
          <div class="map-ground">
            <span class="map-path" /><span class="map-temple">山门</span
            ><span class="map-pavilion">亭</span><span class="map-pond">荷池</span
            ><span class="map-platform">☯</span>
            <span
              class="map-player"
              :style="{
                left: `${((state.x + 12) / 24) * 100}%`,
                top: `${((state.z + 12) / 24) * 100}%`,
              }"
              aria-label="张三丰当前位置"
            />
          </div>
        </div>
        <div v-if="!focused" class="focus-hint">
          点击庭院，开始走走 <span>或使用下方方向按钮</span>
        </div>
        <div class="world-bottom">
          <div class="direction-pad" aria-label="移动方向">
            <button
              v-for="control in [
                { key: 'KeyW', name: '向前', icon: '↑' },
                { key: 'KeyA', name: '向左', icon: '←' },
                { key: 'KeyS', name: '向后', icon: '↓' },
                { key: 'KeyD', name: '向右', icon: '→' },
              ]"
              :key="control.key"
              :class="control.key"
              :aria-label="control.name"
              @pointerdown="touchStart($event, control.key)"
              @pointerup="pointerEnd"
              @pointercancel="pointerEnd"
              @lostpointercapture="pointerEnd"
            >
              {{ control.icon }}
            </button>
          </div>
          <p class="landmark-copy">{{ landmark?.description ?? '不赶路，随心走。' }}</p>
          <div class="action-pad">
            <button
              @click="jumpAction"
            >
              <i class="ri-arrow-up-double-line" /> 跳跃</button
            ><button
              @click="waveAction"
            >
              <i class="ri-hand-heart-line" /> 招手
            </button>
          </div>
        </div>
      </template>
    </section>
    <footer class="world-footer">
      <p>
        <kbd>W A S D</kbd> / 方向键行走 <span>·</span> <kbd>Shift</kbd> 加速 <span>·</span>
        <kbd>空格</kbd> 跳跃 <span>·</span> <kbd>E</kbd> 招手 <span>·</span> 拖动旋转 · 滚轮缩放
      </p>
      <span :class="{ 'save-error': saveState === 'error' }" role="status"
        >{{ saveLabel }}<button v-if="saveState === 'error'" @click="save">重试保存</button></span
      >
    </footer>
    <p class="world-note">
      第一处落脚点。未来可以从这里延伸出自己的山川、村落与故事；当前位置仅保存在本浏览器。
    </p>
  </main>
</template>

<style scoped>
.pet-world-page {
  padding: 24px 28px 18px;
  max-width: none;
  margin: 0 auto;
  color: var(--wao-text, #253a32);
}
.world-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.eyebrow {
  font-size: 11px;
  letter-spacing: 2px;
  color: var(--wao-text-secondary, #758175);
}
h1 {
  font-size: 27px;
  letter-spacing: 3px;
  margin: 6px 0 0;
  font-family: serif;
}
h1 span {
  font-family: inherit;
  font-size: 13px;
  font-weight: 400;
  letter-spacing: 1px;
  margin-left: 18px;
  color: var(--wao-text-secondary, #758175);
}
button {
  cursor: pointer;
  font: inherit;
}
.return-button {
  border: 1px solid #c6d0bf;
  background: #f7f8ef;
  color: #415547;
  border-radius: 9px;
  padding: 9px 14px;
  font-size: 12px;
  white-space: nowrap;
}
.return-button:hover {
  background: #e9eddf;
}
.world-shell {
  position: relative;
  height: clamp(440px, 67vh, 850px);
  overflow: hidden;
  border-radius: 18px;
  background: #dce5dc;
  border: 1px solid #c9d0bf;
  box-shadow: 0 10px 35px #263b3110;
}
.world-canvas {
  width: 100%;
  height: 100%;
  outline: none;
  touch-action: none;
  cursor: grab;
}
.world-canvas:active {
  cursor: grabbing;
}
.world-canvas:focus-visible {
  outline: 3px solid #658575;
  outline-offset: -3px;
}
.world-canvas :deep(canvas) {
  display: block;
  width: 100%;
  height: 100%;
}
.world-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: #e7ebdf;
  color: #4d6254;
  text-align: center;
  padding: 24px;
}
.world-overlay > i {
  font-size: 34px;
}
.world-overlay h2 {
  font-size: 21px;
  font-family: serif;
  letter-spacing: 2px;
}
.world-overlay p {
  font-size: 13px;
}
.loading-icon {
  animation: spin 1.2s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.world-location {
  position: absolute;
  top: 20px;
  left: 20px;
  border: 1px solid #fff9;
  border-radius: 8px;
  background: #faf9eddc;
  color: #354b3b;
  padding: 8px 12px;
  font-size: 13px;
  pointer-events: none;
  box-shadow: 0 3px 12px #263b310d;
}
.world-location small {
  margin-left: 12px;
  color: #788374;
  font-size: 10px;
}
.live-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  background: #769875;
  border-radius: 50%;
  margin-right: 6px;
}
.mini-map {
  position: absolute;
  top: 20px;
  right: 20px;
  width: 142px;
  border: 1px solid #fff9;
  border-radius: 12px;
  background: #faf9eddd;
  padding: 9px;
  pointer-events: none;
  box-shadow: 0 5px 15px #263b3115;
}
.map-title {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: #536249;
  margin-bottom: 6px;
}
.map-title > span {
  font-size: 9px;
  color: #7f886d;
}
.map-ground {
  position: relative;
  height: 120px;
  border: 1px solid #a9b39b;
  background: #aebc98;
  border-radius: 4px;
  overflow: hidden;
}
.map-path {
  position: absolute;
  left: 38%;
  width: 24%;
  height: 100%;
  background: #d9d4b9;
}
.map-temple,
.map-pavilion,
.map-pond,
.map-platform {
  position: absolute;
  display: grid;
  place-items: center;
  font-size: 9px;
  color: #fcf7dd;
}
.map-temple {
  top: 10%;
  left: 35%;
  width: 30%;
  height: 15%;
  background: #527268;
}
.map-pavilion {
  top: 30%;
  left: 10%;
  width: 17%;
  height: 17%;
  background: #627967;
}
.map-pond {
  top: 44%;
  left: 71%;
  width: 18%;
  height: 27%;
  background: #659c94;
  border-radius: 3px;
}
.map-platform {
  top: 45%;
  left: 40%;
  width: 20%;
  height: 20%;
  color: #526452;
  font-size: 22px;
}
.map-player {
  position: absolute;
  width: 8px;
  height: 8px;
  transform: translate(-50%, -50%);
  border: 2px solid #fff7d7;
  background: #b17935;
  border-radius: 50%;
  box-shadow: 0 1px 4px #263b3155;
}
.focus-hint {
  position: absolute;
  left: 50%;
  bottom: 120px;
  transform: translateX(-50%);
  background: #f9f8eadf;
  border: 1px solid #fff8;
  border-radius: 12px;
  padding: 10px 18px;
  color: #475c4b;
  font-size: 13px;
  text-align: center;
  pointer-events: none;
  white-space: nowrap;
}
.focus-hint span {
  display: block;
  font-size: 10px;
  color: #839079;
  margin-top: 3px;
}
.world-bottom {
  position: absolute;
  left: 20px;
  right: 20px;
  bottom: 18px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  pointer-events: none;
}
.direction-pad {
  display: grid;
  grid-template-columns: repeat(3, 36px);
  grid-template-rows: repeat(2, 32px);
  gap: 4px;
  pointer-events: auto;
}
.direction-pad .KeyW {
  grid-column: 2;
}
.direction-pad .KeyA {
  grid-column: 1;
  grid-row: 2;
}
.direction-pad .KeyS {
  grid-column: 2;
  grid-row: 2;
}
.direction-pad .KeyD {
  grid-column: 3;
  grid-row: 2;
}
.direction-pad button,
.action-pad button {
  border: 1px solid #fff9;
  background: #f9f8eae8;
  color: #425a48;
  border-radius: 8px;
  touch-action: none;
  user-select: none;
}
.direction-pad button:active,
.action-pad button:active {
  background: #c6d7be;
}
.action-pad {
  display: flex;
  gap: 7px;
  pointer-events: auto;
}
.action-pad button {
  padding: 9px 12px;
  font-size: 12px;
}
.landmark-copy {
  color: #3f5648;
  background: #f9f8eabf;
  padding: 6px 13px;
  border-radius: 20px;
  font-family: serif;
  font-size: 13px;
  letter-spacing: 1px;
  margin: 0 0 5px;
  text-align: center;
}
.world-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 11px;
  color: var(--wao-text-secondary, #788576);
  align-items: center;
  margin-top: 10px;
}
.world-footer p {
  line-height: 1.9;
  margin: 0;
}
.world-footer p span {
  margin: 0 6px;
}
.world-footer > span {
  white-space: nowrap;
}
.world-footer kbd {
  font-family: inherit;
  font-size: 10px;
}
.world-footer button {
  background: none;
  border: 0;
  color: #956332;
  text-decoration: underline;
  margin-left: 5px;
}
.save-error {
  color: #a56e35;
}
.world-note {
  font-size: 11px;
  color: var(--wao-text-secondary, #788576);
  margin-top: 10px;
}
@media (max-width: 800px) {
  .pet-world-page {
    padding: 14px 10px;
  }
  .world-heading {
    gap: 8px;
    margin-bottom: 12px;
  }
  h1 {
    font-size: 23px;
  }
  h1 span {
    display: none;
  }
  .eyebrow {
    font-size: 9px;
    letter-spacing: 1px;
  }
  .return-button {
    font-size: 10px;
    padding: 8px 10px;
  }
  .world-shell {
    height: 65dvh;
    min-height: 410px;
    border-radius: 12px;
  }
  .mini-map {
    top: 12px;
    right: 12px;
    width: 98px;
    padding: 6px;
  }
  .map-ground {
    height: 83px;
  }
  .map-title {
    font-size: 8px;
  }
  .world-location {
    top: 12px;
    left: 12px;
    font-size: 11px;
    padding: 7px 9px;
  }
  .world-location small {
    display: none;
  }
  .world-bottom {
    left: 12px;
    right: 12px;
    bottom: 15px;
  }
  .landmark-copy {
    display: none;
  }
  .world-footer {
    align-items: flex-start;
  }
  .world-footer p {
    max-width: 75%;
    font-size: 10px;
  }
  .world-footer p span {
    margin: 0 2px;
  }
  .world-note {
    font-size: 10px;
    line-height: 1.6;
  }
}
@media (prefers-reduced-motion: reduce) {
  .loading-icon {
    animation: none;
  }
}
</style>
