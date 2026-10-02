<script setup lang="ts">
import { ref, toRef, watch } from 'vue'
import { buddies } from './worlds'
import { clamp, stepBody } from './engine'
import { freshProgress, type RelaxProgress, type Piece } from './progress'
import { useGameLoop } from './useGameLoop'
const props = defineProps<{ progress: RelaxProgress['lounge']; active: boolean }>()
const emit = defineEmits<{ story: [text: string] }>()
const game = props.progress
const svg = ref<SVGSVGElement>(),
  selected = ref(2),
  drag = ref<number | null>(null),
  undo = ref<Piece[] | null>(null)
const velocities = Array.from({ length: 8 }, () => ({ vx: 0, vy: 0 }))
let offset = { x: 0, y: 0 }
const size = (id: number) => (id < 5 ? { w: 65, h: 77 } : { w: 81, h: 35 })
function point(e: PointerEvent) {
  const r = svg.value!.getBoundingClientRect()
  return { x: ((e.clientX - r.left) * 700) / r.width, y: ((e.clientY - r.top) * 380) / r.height }
}
function down(e: PointerEvent, id: number) {
  if (!props.active) return
  selected.value = id
  drag.value = id
  const p = point(e),
    piece = game.pieces[id]!
  offset = { x: p.x - piece.x, y: p.y - piece.y }
  svg.value?.setPointerCapture(e.pointerId)
}
function move(e: PointerEvent) {
  if (drag.value === null) return
  const p = point(e),
    item = game.pieces[drag.value]!
  item.x = clamp(p.x - offset.x, 0, 700 - size(item.id).w)
  item.y = clamp(p.y - offset.y, 0, 330 - size(item.id).h)
  velocities[item.id] = { vx: 0, vy: 0 }
}
function up() {
  if (drag.value !== null && game.pieces[drag.value]!.y > 245 && drag.value < 5)
    emit('story', `${buddies[drag.value]!.name}刚从沙发滑了下来：“下个展台给我留个位置！”`)
  drag.value = null
}
watch(
  () => props.active,
  (on) => {
    if (!on) up()
  },
)
useGameLoop(toRef(props, 'active'), (dt) => {
  game.pieces.forEach((p) => {
    if (drag.value === p.id) return
    const sz = size(p.id),
      body = { ...p, ...sz, ...velocities[p.id]! }
    const others = game.pieces
      .filter((o) => o.id !== p.id && o.id !== drag.value && o.y >= p.y + sz.h - 10)
      .map((o) => ({ ...o, ...size(o.id) }))
    stepBody(
      body,
      [{ x: 0, y: 340, w: 700, h: 40 }, { x: 140, y: 264, w: 410, h: 30 }, ...others],
      dt,
      650,
    )
    p.x = clamp(body.x, 0, 700 - sz.w)
    p.y = body.y
    velocities[p.id] = { vx: body.vx, vy: body.vy }
  })
})
function key(e: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(e.key)) return
  e.preventDefault()
  const p = game.pieces[selected.value]!
  p.x = clamp(p.x + (e.key === 'ArrowLeft' ? -15 : e.key === 'ArrowRight' ? 15 : 0), 0, 620)
  p.y = clamp(p.y + (e.key === 'ArrowUp' ? -25 : e.key === 'ArrowDown' ? 15 : 0), 0, 260)
  velocities[p.id] = { vx: 0, vy: 0 }
}
function reset() {
  undo.value = game.pieces.map((p) => ({ ...p }))
  game.pieces = freshProgress().lounge.pieces
}
function restoreLayout() {
  if (undo.value) game.pieces = undo.value
  undo.value = null
}
</script>
<template>
  <div>
    <div class="game-meta">
      <span>把伙伴和抱枕，摆成舒服的样子</span><span>拖动 · 旋转 · 自由落下</span>
    </div>
    <svg
      ref="svg"
      class="game-board lounge-board"
      viewBox="0 0 700 380"
      role="group"
      aria-label="可拖动的伙伴休息角"
      @pointermove="move"
      @pointerup="up"
      @pointercancel="up"
      @lostpointercapture="up"
    >
      <path d="M48 334H659" stroke="currentColor" opacity=".2" />
      <path d="M144 260V175Q144 156 165 156H519Q541 156 541 178V260" fill="#a792db" />
      <rect x="124" y="233" width="441" height="51" rx="16" fill="#bda7f1" />
      <path d="M154 283v47m381-47v47" stroke="#776397" stroke-width="12" />
      <text x="55" y="57" fill="currentColor" font-size="16">OFF DUTY.</text>
      <g
        v-for="p in game.pieces"
        :key="p.id"
        :transform="`translate(${p.x} ${p.y})`"
        @pointerdown.prevent="down($event, p.id)"
      >
        <rect
          v-if="p.id === selected"
          x="-4"
          y="-4"
          :width="size(p.id).w + 8"
          :height="size(p.id).h + 8"
          rx="10"
          fill="none"
          stroke="var(--world-accent)"
          stroke-dasharray="4 4"
        />
        <image
          v-if="p.id < 5"
          :href="buddies[p.id]!.image"
          width="65"
          height="77"
          :transform="`rotate(${p.angle} 32 38)`"
        />
        <rect
          v-else
          width="81"
          height="35"
          rx="12"
          :fill="['#ff91bf', '#d3f586', '#65dfcf'][p.id - 5]"
          :transform="`rotate(${p.angle} 40 18)`"
        />
      </g>
    </svg>
    <div class="game-actions" @keydown="key">
      <button
        v-for="(p, i) in game.pieces"
        :key="i"
        :aria-pressed="selected === i"
        @click="selected = i"
      >
        {{ i < 5 ? buddies[i]!.name : `抱枕 ${i - 4}` }}</button
      ><button @click="game.pieces[selected]!.angle = (game.pieces[selected]!.angle + 30) % 180">
        转一转 ↻</button
      ><button @click="reset">重新摆放</button
      ><button v-if="undo" @click="restoreLayout">撤销重摆</button>
    </div>
    <p class="game-help">选中伙伴后可用方向键挪动；向上多按几次，它会蹦起来。</p>
  </div>
</template>
