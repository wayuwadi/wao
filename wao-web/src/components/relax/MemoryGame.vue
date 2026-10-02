<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import { buddies } from './worlds'
import { memoryResult } from './engine'
import { useGameLoop } from './useGameLoop'
import type { RelaxProgress } from './progress'
const props = defineProps<{ progress: RelaxProgress['memory']; active: boolean }>()
const emit = defineEmits<{ story: [text: string] }>()
const game = props.progress
const playing = ref(false),
  accepting = ref(false),
  cue = ref(-1),
  step = ref(0),
  won = ref(false),
  message = ref('小伙伴会做一串动作，看完后按顺序回应。')
let elapsed = 0
const length = computed(() => Math.min(game.level + 2, 9))
function play() {
  if (!game.sequence.length)
    game.sequence = Array.from({ length: length.value }, () => Math.floor(Math.random() * 5))
  elapsed = 0
  step.value = 0
  won.value = false
  accepting.value = false
  playing.value = true
  cue.value = game.sequence[0]!
  message.value = '先看一遍，不用着急。'
}
useGameLoop(toRef(props, 'active'), (dt) => {
  if (!playing.value) return
  elapsed += dt
  const at = Math.floor(elapsed / 1.15)
  cue.value = elapsed % 1.15 < 0.82 ? (game.sequence[at] ?? -1) : -1
  if (at >= game.sequence.length) {
    playing.value = false
    accepting.value = true
    cue.value = -1
    message.value = '轮到你了，谁先动了？'
  }
})
watch(
  () => props.active,
  (on) => {
    if (!on && playing.value) {
      playing.value = false
      cue.value = -1
      message.value = '动作已暂停，点“重放本轮”重新看一遍。'
    }
  },
)
function choose(index: number) {
  if (!accepting.value) return
  const result = memoryResult(game.sequence, step.value, index)
  if (result === 'wrong') {
    accepting.value = false
    message.value = '小队笑成一团，再看一遍就好。'
    return
  }
  step.value++
  if (result === 'complete') {
    accepting.value = false
    won.value = true
    game.best = Math.max(game.best, game.level)
    message.value = '接力成功！五个伙伴为你谢幕。'
    emit('story', '小剧场刚刚顺利谢幕，闪电还想再加演一次。')
  } else message.value = `记住了 ${step.value} / ${game.sequence.length} 个，再接一个。`
}
function next() {
  game.level = Math.min(game.level + 1, 20)
  game.sequence = []
  play()
}
</script>
<template>
  <div class="memory-game">
    <div class="game-meta">
      <span>第 {{ game.level }} 幕 · {{ length }} 个动作</span
      ><span>最佳：第 {{ game.best }} 幕</span>
    </div>
    <div class="curtain-scene">
      <div class="spotlight"></div>
      <div class="memory-crew">
        <button
          v-for="(b, i) in buddies"
          :key="b.name"
          :class="['memory-buddy', `action-${i}`, { cue: cue === i }]"
          :disabled="!accepting"
          :aria-label="`${b.name}，${b.action}`"
          @click="choose(i)"
        >
          <img :src="b.image" :alt="b.name" /><span>{{ b.action }}</span>
        </button>
      </div>
      <p class="game-bubble" role="status">
        {{ cue >= 0 ? `${buddies[cue]!.name}：${buddies[cue]!.action}` : message }}
      </p>
    </div>
    <div class="game-actions">
      <button v-if="won" class="game-primary" @click="next">下一幕 ↗</button
      ><button v-else class="game-primary" :disabled="playing" @click="play">
        {{ game.sequence.length ? '重放本轮' : '开演吧' }}</button
      ><span>{{
        playing
          ? '看完后再回应'
          : accepting
            ? `${step} / ${game.sequence.length}`
            : '没有倒计时，慢慢记。'
      }}</span>
    </div>
  </div>
</template>
