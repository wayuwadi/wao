<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { buddies } from './worlds'
import { clamp } from './engine'
import type { RelaxProgress, Sticker } from './progress'
import starRaw from '@/assets/relax/star.svg?raw'
import bookRaw from '@/assets/relax/book.svg?raw'
import robotRaw from '@/assets/relax/robot.svg?raw'
import boltRaw from '@/assets/relax/bolt.svg?raw'
import folderRaw from '@/assets/relax/folder.svg?raw'
const props = defineProps<{ progress: RelaxProgress['journal']; active: boolean }>(),
  emit = defineEmits<{ story: [text: string] }>()
const game = props.progress
const svg = ref<SVGSVGElement>(),
  selected = ref<number | null>(null),
  tool = ref<'move' | 'pen'>('move'),
  drawing = ref(false),
  drag = ref(false),
  color = ref('#3659cf'),
  notice = ref(''),
  deleted = ref<Sticker | null>(null)
let offset = { x: 0, y: 0 }
const selectedSticker = computed(() => game.stickers.find((s) => s.id === selected.value))
const exportedUrl = ref('')
onBeforeUnmount(() => {
  if (exportedUrl.value) URL.revokeObjectURL(exportedUrl.value)
})
function point(e: PointerEvent) {
  const r = svg.value!.getBoundingClientRect()
  return {
    x: clamp(((e.clientX - r.left) * 700) / r.width, 0, 700),
    y: clamp(((e.clientY - r.top) * 420) / r.height, 0, 420),
  }
}
function add(i: number) {
  if (game.stickers.length >= 30) {
    notice.value = '这一页最多放 30 张贴纸，可以先移除一张。'
    return
  }
  const id = Math.max(-1, ...game.stickers.map((s) => s.id)) + 1
  game.stickers.push({ id, buddy: i, x: 260 + (id % 4) * 30, y: 100 + (id % 3) * 40, angle: 0 })
  selected.value = id
  tool.value = 'move'
  notice.value = '贴好了，拖一拖或转个角度。'
  emit('story', `${buddies[i]!.name}刚被贴进手账，开心得想再摆一个姿势。`)
}
function down(e: PointerEvent, id?: number) {
  if (!props.active) return
  svg.value?.setPointerCapture(e.pointerId)
  const p = point(e)
  if (tool.value === 'pen') {
    if (game.strokes.length >= 80) {
      notice.value = '这一页已画满 80 笔，可以撤销一笔再画。'
      return
    }
    drawing.value = true
    game.strokes.push({ points: [[p.x, p.y]], color: color.value })
  } else if (id !== undefined) {
    selected.value = id
    drag.value = true
    const s = selectedSticker.value!
    offset = { x: p.x - s.x, y: p.y - s.y }
  }
}
function move(e: PointerEvent) {
  const p = point(e)
  if (drawing.value) {
    const points = game.strokes[game.strokes.length - 1]!.points,
      last = points[points.length - 1]!
    if (points.length < 160 && Math.hypot(p.x - last[0]!, p.y - last[1]!) > 3)
      points.push([p.x, p.y])
  }
  if (drag.value && selectedSticker.value) {
    selectedSticker.value.x = clamp(p.x - offset.x, 0, 620)
    selectedSticker.value.y = clamp(p.y - offset.y, 0, 340)
  }
}
function up() {
  drawing.value = false
  drag.value = false
}
function remove() {
  const s = selectedSticker.value
  if (s) {
    deleted.value = { ...s }
    game.stickers = game.stickers.filter((a) => a.id !== s.id)
    selected.value = null
  }
}
function restoreSticker() {
  if (deleted.value) game.stickers.push(deleted.value)
  deleted.value = null
}
function key(e: KeyboardEvent) {
  const s = selectedSticker.value
  if (!s) return
  if (e.key.startsWith('Arrow')) {
    e.preventDefault()
    s.x = clamp(s.x + (e.key === 'ArrowLeft' ? -10 : e.key === 'ArrowRight' ? 10 : 0), 0, 620)
    s.y = clamp(s.y + (e.key === 'ArrowUp' ? -10 : e.key === 'ArrowDown' ? 10 : 0), 0, 340)
  }
}
function exportPage() {
  try {
    const ns = 'http://www.w3.org/2000/svg',
      doc = document.implementation.createDocument(ns, 'svg'),
      out = doc.documentElement
    out.setAttribute('viewBox', '0 0 700 500')
    out.setAttribute('width', '700')
    out.setAttribute('height', '500')
    function element(name: string, attrs: Record<string, string>) {
      const n = doc.createElementNS(ns, name)
      Object.entries(attrs).forEach(([k, v]) => n.setAttribute(k, v))
      out.append(n)
      return n
    }
    element('rect', { width: '700', height: '500', fill: '#f8faff' })
    for (const stroke of game.strokes)
      element('polyline', {
        points: stroke.points.map((p) => p.join(',')).join(' '),
        fill: 'none',
        stroke: stroke.color,
        'stroke-width': '4',
        'stroke-linecap': 'round',
        'stroke-linejoin': 'round',
      })
    const raws = [starRaw, bookRaw, robotRaw, boltRaw, folderRaw]
    for (const s of game.stickers)
      element('image', {
        href: `data:image/svg+xml;charset=utf-8,${encodeURIComponent(raws[s.buddy]!)}`,
        x: String(s.x),
        y: String(s.y),
        width: '80',
        height: '80',
        transform: `rotate(${s.angle} ${s.x + 40} ${s.y + 40})`,
      })
    const lines = game.text.match(/.{1,36}/gu) || []
    lines.slice(0, 7).forEach((line, i) => {
      element('text', {
        x: '28',
        y: String(432 + i * 9),
        'font-size': '9',
        fill: '#253a62',
      }).textContent = line
    })
    if (exportedUrl.value) URL.revokeObjectURL(exportedUrl.value)
    const url = URL.createObjectURL(
        new Blob([new XMLSerializer().serializeToString(out)], { type: 'image/svg+xml' }),
      ),
      a = document.createElement('a')
    a.href = url
    exportedUrl.value = url
    a.download = 'wao-今天的手账.svg'
    a.click()
    notice.value = '手账已生成。若浏览器未自动下载，可点下方链接保存。'
  } catch {
    notice.value = '暂时无法导出，请重试。当前草稿仍保留。'
  }
}
</script>
<template>
  <div>
    <div class="game-meta">
      <span>贴一点、画一点，留一点空白</span><span>{{ game.stickers.length }}/30 张贴纸</span>
    </div>
    <div class="game-actions">
      <button v-for="(b, i) in buddies" :key="i" @click="add(i)">＋{{ b.name }}</button>
    </div>
    <div
      tabindex="0"
      class="journal-canvas"
      role="group"
      aria-label="手账画布，选中贴纸后可用方向键移动"
      @keydown="key"
    >
      <svg
        ref="svg"
        class="game-board"
        viewBox="0 0 700 420"
        @pointerdown.prevent="down($event)"
        @pointermove="move"
        @pointerup="up"
        @pointercancel="up"
        @lostpointercapture="up"
      >
        <defs>
          <pattern id="relax-paper" width="28" height="28" patternUnits="userSpaceOnUse">
            <path d="M28 0H0V28" fill="none" stroke="#e0e6ef" />
          </pattern>
        </defs>
        <rect width="700" height="420" fill="#f8faff" />
        <rect width="700" height="420" fill="url(#relax-paper)" />
        <text
          v-if="!game.stickers.length && !game.strokes.length"
          x="350"
          y="208"
          text-anchor="middle"
          fill="#73809c"
          font-size="16"
        >
          点上方伙伴，贴下今天的第一张贴纸。
        </text>
        <polyline
          v-for="(s, i) in game.strokes"
          :key="i"
          :points="s.points.map((p) => p.join(',')).join(' ')"
          fill="none"
          :stroke="s.color"
          stroke-width="4"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
        <g
          v-for="s in game.stickers"
          :key="s.id"
          :transform="`translate(${s.x} ${s.y}) rotate(${s.angle} 40 40)`"
          @pointerdown.stop.prevent="down($event, s.id)"
        >
          <rect
            v-if="selected === s.id"
            width="80"
            height="80"
            fill="none"
            stroke="#3659cf"
            stroke-dasharray="4 4"
          />
          <image :href="buddies[s.buddy]!.image" width="80" height="80" />
        </g>
      </svg>
    </div>
    <div class="game-actions">
      <button :aria-pressed="tool === 'move'" @click="tool = 'move'">移动贴纸</button
      ><button :aria-pressed="tool === 'pen'" @click="tool = 'pen'">画一笔</button
      ><button
        v-for="c in ['#3659cf', '#ef745e', '#253a62']"
        :key="c"
        :style="{ color: c }"
        :aria-pressed="color === c"
        :aria-label="`画笔颜色 ${c}`"
        @click="color = c"
      >
        ●</button
      ><button :disabled="!game.strokes.length" @click="game.strokes.pop()">撤销画笔</button
      ><button
        :disabled="!selectedSticker"
        @click="selectedSticker && (selectedSticker.angle = (selectedSticker.angle + 30) % 180)"
      >
        转贴纸 ↻</button
      ><button :disabled="!selectedSticker" @click="remove">移除贴纸</button
      ><button v-if="deleted" @click="restoreSticker">撤销移除</button>
    </div>
    <label class="journal-note"
      >给今天写一句话<textarea
        v-model="game.text"
        maxlength="240"
        rows="2"
        placeholder="也可以什么都不写。"
      />
    </label>
    <div class="game-actions">
      <button class="game-primary" @click="exportPage">导出这一页 ↗</button
      ><span>{{ game.text.length }}/240 字</span>
    </div>
    <p class="game-feedback" role="status">{{ notice }}</p>
    <a v-if="exportedUrl" :href="exportedUrl" download="wao-今天的手账.svg">保存手账 SVG</a>
  </div>
</template>
