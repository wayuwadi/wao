<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import { buddies } from './worlds'
import { direction, initialArrows, orbitPaths, traceOrbit } from './engine'
import type { RelaxProgress } from './progress'
import { useGameLoop } from './useGameLoop'
const props = defineProps<{ progress: RelaxProgress['orbit']; active: boolean }>(),
  emit = defineEmits<{ story: [text: string] }>()
const game = props.progress
const flying = ref(false),
  at = ref(0),
  visited = ref<number[]>([]),
  message = ref('点击星球旋转引力箭头，让伙伴从左上角飞到右下角。'),
  won = ref(false)
let elapsed = 0,
  trace = traceOrbit(game.arrows)
const path = computed(() => orbitPaths[game.level]!)
function launch() {
  trace = traceOrbit(game.arrows)
  at.value = 0
  visited.value = [0]
  elapsed = 0
  flying.value = true
  won.value = false
  message.value = '出发！跟着引力走。'
}
useGameLoop(toRef(props, 'active'), (dt) => {
  if (!flying.value) return
  elapsed += dt
  if (elapsed < 0.5) return
  elapsed = 0
  if (at.value < trace.path.length - 1) {
    at.value++
    visited.value.push(trace.path[at.value]!)
  } else {
    flying.value = false
    message.value = trace.reason
    if (trace.success) {
      won.value = true
      game.wins++
      emit('story', `${buddies[game.level]!.name}刚绕过几颗星球，终于赶上大家的休息时间。`)
    }
  }
})
watch(
  () => props.active,
  (on) => {
    if (!on && flying.value) {
      flying.value = false
      message.value = '飞行已暂停，点“发射伙伴”从起点再试。'
    }
  },
)
function turn(i: number) {
  if (i === 19 || flying.value) return
  game.arrows[i] = (game.arrows[i]! + 1) % 4
  won.value = false
}
function hint() {
  const i = path.value.findIndex(
    (p, n) => n < path.value.length - 1 && game.arrows[p] !== direction(p, path.value[n + 1]!),
  )
  if (i < 0) {
    message.value = '路线准备好了，发射试试。'
    return
  }
  const cell = path.value[i]!
  message.value = `试试第 ${Math.floor(cell / 5) + 1} 行第 ${(cell % 5) + 1} 列，让箭头朝${['上', '右', '下', '左'][direction(cell, path.value[i + 1]!)]}。`
}
function next() {
  game.level = (game.level + 1) % 5
  game.arrows = initialArrows(game.level)
  won.value = false
  visited.value = []
  message.value = '下一位伙伴等着出发。'
}
</script>
<template>
  <div>
    <div class="game-meta">
      <span>第 {{ game.level + 1 }} 条航线 · {{ buddies[game.level]!.name }}</span
      ><span>已接回 {{ game.wins }} 次</span>
    </div>
    <div class="orbit-puzzle" role="group" aria-label="四行五列引力星图">
      <button
        v-for="(_, i) in game.arrows"
        :key="i"
        :disabled="flying || i === 19"
        :class="{ visited: visited.includes(i), goal: i === 19 }"
        :aria-label="`第${Math.floor(i / 5) + 1}行第${(i % 5) + 1}列，${i === 19 ? '终点' : ['向上', '向右', '向下', '向左'][game.arrows[i]!]}`"
        @click="turn(i)"
      >
        <img
          v-if="(flying && trace.path[at] === i) || (!flying && i === 0)"
          :src="buddies[game.level]!.image"
          alt="等待回家的伙伴"
        /><span v-else-if="i === 19">⌂</span
        ><span v-else>{{ ['↑', '→', '↓', '←'][game.arrows[i]!] }}</span
        ><small>{{
          i === 0 ? '出发' : i === 19 ? '回家' : `${Math.floor(i / 5) + 1}·${(i % 5) + 1}`
        }}</small>
      </button>
    </div>
    <p class="game-feedback" role="status">{{ message }}</p>
    <div class="game-actions">
      <button v-if="won" class="game-primary" @click="next">
        {{ game.level === 4 ? '再环游一圈' : '接下一位伙伴' }} ↗</button
      ><button v-else class="game-primary" :disabled="flying" @click="launch">
        {{ flying ? '飞行中…' : '发射伙伴 ↗' }}</button
      ><button :disabled="flying" @click="hint">给一点提示</button>
    </div>
  </div>
</template>
