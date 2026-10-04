<script setup lang="ts">
import { nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { companionEditing, companionQuiet as quiet, setCompanionQuiet } from '@/composables/quietCompanions'
import type { createCompanionScene } from './companionScene'
import type { CompanionActivity } from './companionActivities'
import type { SurpriseStyle } from './companionSurprises'
import type { WorldStory, WorldChoices } from './companionWorld'

const props = defineProps<{ choices?: WorldChoices }>()
const emit = defineEmits<{ random: [event: MouseEvent | KeyboardEvent, index: number]; surprise: [style: SurpriseStyle]; cancelSurprises: []; world: [story: WorldStory] }>()
const host = ref<HTMLElement>()
const canvas = ref<HTMLCanvasElement>()
const canvasKey = ref(0)
const ready = ref(false)
const failed = ref(false)
const message = ref('')
const messagePosition = ref({ left: '12px', top: '12px' })
const names = ['小画家', '小书虫', '小开心', '小青蛙']
let controller: ReturnType<typeof createCompanionScene> | undefined
let disposed = false
let active = true
let generation = 0
let timer: ReturnType<typeof setTimeout> | undefined

function say(text: string, x = 12, y = 12) {
  if (timer) clearTimeout(timer)
  message.value = text
  messagePosition.value = { left: `${x}px`, top: `${y}px` }
  timer = setTimeout(() => {
    message.value = ''
  }, 2400)
}
function fallback() {
  controller?.dispose()
  controller = undefined
  ready.value = false
  failed.value = true
}
async function load() {
  const token = ++generation
  failed.value = false
  ready.value = false
  controller?.dispose()
  controller = undefined
  canvasKey.value++
  await nextTick()
  try {
    const { createCompanionScene } = await import('./companionScene')
    if (disposed || token !== generation || !host.value || !canvas.value) return
    controller = createCompanionScene({
      host: host.value,
      canvas: canvas.value,
      targets: [...host.value.querySelectorAll<HTMLButtonElement>('.companion-target')],
      say,
      failed: fallback,
      surprise: style => emit('surprise', style),
      cancelSurprises: () => emit('cancelSurprises'),
      world: story => emit('world', story),
      choices: props.choices,
    })
    controller.setQuiet(quiet.value || companionEditing.value)
    controller.setActive(active)
    ready.value = true
  } catch {
    if (!disposed && token === generation) fallback()
  }
}
function toggleQuiet() {
  setCompanionQuiet(!quiet.value)
  message.value = ''
}
watch([quiet, companionEditing], ([silent, editing]) => { controller?.setQuiet(silent || editing); message.value = '' })
async function perform(kind: CompanionActivity) {
  if (quiet.value) { toggleQuiet(); await nextTick() }
  if (!controller?.activity(kind)) say('今天安静陪着你，休息就好。')
}
function interact(event: MouseEvent, index: number) {
  if (event.defaultPrevented) return
  if (!controller?.pop(index)) say('我在这里，安静陪着你。')
}
function onRandomKey(event: KeyboardEvent, index: number) {
  if (event.key !== 'ContextMenu' && !(event.key === 'F10' && event.shiftKey)) return
  event.preventDefault()
  event.stopPropagation()
  emit('random', event, index)
}
defineExpose({ perform, toggleQuiet, quiet, ready, settle: () => controller?.settle(), story: (event: WorldStory) => controller?.story(event), anchors: () => controller?.anchors() ?? [] })
onMounted(load)
onActivated(() => {
  active = true
  controller?.setActive(true)
})
onDeactivated(() => {
  active = false
  controller?.setActive(false)
  message.value = ''

  if (timer) clearTimeout(timer)
})
onBeforeUnmount(() => {
  disposed = true
  generation++
  if (timer) clearTimeout(timer)
  controller?.dispose()
})
</script>

<template>
  <section ref="host" class="home-companions" :class="{ ready, quiet }" aria-label="首页四个伙伴">
    <canvas :key="canvasKey" ref="canvas" aria-hidden="true" />
    <button
      v-for="(name, index) in names"
      :key="name"
      type="button"
      class="companion-target"
      :class="`companion-${index}`"
      :aria-label="`和${name}互动`"
      :title="`${name}：点击或按 Enter 升空变泡泡；右键或 Shift+F10 随机互动，拖动后归位`"
      :style="{ '--index': index }"
      @contextmenu.stop.prevent="emit('random', $event, index)"
      @keydown="onRandomKey($event, index)"
      @click="interact($event, index)"
    >
      <span class="companion-fallback" aria-hidden="true"
        ><span class="fallback-eyes">● ●</span></span
      >
    </button>
    <div class="companion-actions">
      <span v-if="failed" class="fallback-label">静态陪伴</span>
      <button v-if="failed" type="button" @click="load">重试动效</button>
      <button v-else type="button" :aria-pressed="quiet" @click="toggleQuiet">
        {{ quiet ? '唤醒伙伴' : '安静陪伴' }}
      </button>
    </div>
    <div
      class="companion-speech"
      role="status"
      :class="{ visible: message }"
      :style="messagePosition"
    >
      {{ message }}
    </div>
  </section>
</template>

<style scoped>
.home-companions {
  position: absolute;
  inset: 0;
  overflow: clip;
  isolation: isolate;
}
canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.25s;
}
.ready canvas {
  opacity: 1;
}
.companion-target {
  position: absolute;
  left: 0;
  top: 0;
  width: 100px;
  height: 130px;
  padding: 0;
  border: 0;
  border-radius: 44%;
  background: transparent;
  cursor: grab;
  touch-action: none;
  -webkit-user-select: none;
  user-select: none;
  -webkit-tap-highlight-color: transparent;
}
.companion-target:focus-visible {
  outline: 2px dashed var(--wao-primary);
  outline-offset: -5px;
}
.companion-target.dragging {
  cursor: grabbing;
}
.companion-fallback {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 48% 45% 42% 50%;
  background: radial-gradient(at 30% 20%, #6ee0ff, #08a9e3 65%, #1884b8);
  box-shadow: inset -10px -10px 20px #0001;
}
.companion-1 .companion-fallback {
  border-radius: 50% 50% 35% 35%;
  background: radial-gradient(at 30% 20%, #ffe986, #f3c224 70%, #cfa721);
}
.companion-2 .companion-fallback {
  background: radial-gradient(at 30% 20%, #fb92e8, #e950d3 70%, #c328ad);
}
.companion-3 .companion-fallback {
  background: radial-gradient(at 30% 20%, #e0ff84, #b0dc2b 70%, #81ae13);
}
.fallback-eyes {
  display: block;
  padding-top: 35%;
  text-align: center;
  color: #24272b;
  letter-spacing: 5px;
}
.ready .companion-fallback {
  visibility: hidden;
}
.home-companions:not(.ready) .companion-target {
  transform: none !important;
  width: clamp(48px, 14vw, 115px) !important;
  height: clamp(65px, 18vw, 145px) !important;
  top: auto;
  bottom: -35px;
  left: calc(50% + (var(--index) - 2) * clamp(48px, 14vw, 115px));
}
.companion-actions {
  position: absolute;
  z-index: 4;
  bottom: 12px;
  right: 16px;
  display: flex;
  gap: 8px;
  align-items: center;
  opacity: 0;
  transition: opacity 0.2s;
}
.home-companions:hover .companion-actions,
.home-companions:focus-within .companion-actions {
  opacity: 1;
}
.companion-actions button {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 20px;
  padding: 7px 11px;
  font: inherit;
  font-size: 11px;
  color: var(--wao-text-weak);
  cursor: pointer;
}
.companion-actions button:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 3px;
}
.fallback-label {
  font-size: 11px;
  color: var(--wao-text-weak);
}
.companion-speech {
  position: absolute;
  z-index: 5;
  pointer-events: none;
  max-width: calc(100% - 24px);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  border: 1px solid var(--wao-line);
  border-radius: 14px 14px 14px 3px;
  box-shadow: 0 4px 18px #3122430a;
  padding: 8px 12px;
  font-size: 12px;
  opacity: 0;
  transition: opacity 0.2s;
}
.companion-speech.visible {
  opacity: 1;
}
@media (max-width: 600px) {
  .companion-actions {
    bottom: 100px;
    right: 12px;
  }
}
@media (hover: none) {
  .companion-actions {
    opacity: 1;
  }
}
@media (prefers-reduced-motion: reduce) {
  canvas,
  .companion-actions,
  .companion-speech {
    transition: none;
  }
}
</style>
