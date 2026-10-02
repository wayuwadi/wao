<script setup lang="ts">
import { computed, ref } from 'vue'
import { buddies } from './worlds'
import { shadowError } from './engine'
import type { RelaxProgress } from './progress'
const props = defineProps<{ progress: RelaxProgress['gallery']; active: boolean }>(),
  emit = defineEmits<{ story: [text: string] }>()
const game = props.progress
const message = ref('转动角色和灯光，让实心影子盖住虚线轮廓。'),
  won = ref(false)
const targets = computed(
  () =>
    [
      [30, 110, 230],
      [170, 60, 290],
      [80, 240, 140],
      [270, 190, 40],
      [150, 320, 70],
    ][game.level]!,
)
const targetLight = computed(() => [20, 60, 100, 30, 80][game.level]!)
const error = computed(() => shadowError(game.angles, game.light, targets.value, targetLight.value))
const count = computed(() => (game.level < 2 ? 2 : 3))
function check() {
  const e = shadowError(
    game.angles.slice(0, count.value),
    game.light,
    targets.value.slice(0, count.value),
    targetLight.value,
  )
  if (e <= 5) {
    if (!won.value) {
      game.wins++
      emit('story', '美术馆的影子终于对齐了，机器人坚持说自己是抽象派。')
    }
    won.value = true
    message.value = '影子重合了！这件作品完成。'
  } else message.value = `还有一点点偏差：最大的角度差是 ${Math.round(e)}°。`
}
function next() {
  game.level = (game.level + 1) % 5
  game.angles = [0, 0, 0]
  game.light = 0
  won.value = false
  message.value = '新展品开灯，试试新的影子。'
}
function hint() {
  message.value = `灯光先转到 ${targetLight.value}°，再试试角色角度 ${targets.value.slice(0, count.value).join('°、')}°。`
}
</script>
<template>
  <div>
    <div class="game-meta">
      <span>展厅 {{ game.level + 1 }} · 光与影</span><span>完成 {{ game.wins }} 件作品</span>
    </div>
    <svg
      class="game-board shadow-board"
      viewBox="0 0 700 310"
      role="img"
      aria-label="实际影子与目标轮廓叠加预览"
    >
      <defs>
        <filter id="relax-shadow">
          <feColorMatrix type="matrix" values="0 0 0 0 .14 0 0 0 0 .16 0 0 0 0 .2 0 0 0 1 0" />
        </filter>
      </defs>
      <rect width="700" height="310" fill="#eeeff1" />
      <path d="M20 279H680" stroke="#ccced2" />
      <g
        v-for="i in count"
        :key="i"
        :transform="`translate(${350 + (i - (count + 1) / 2) * 205} 150)`"
      >
        <circle r="77" fill="#e1e3e6" />
        <g :transform="`rotate(${targets[i - 1]! + targetLight})`">
          <image
            :href="buddies[i - 1]!.image"
            x="-54"
            y="-60"
            width="108"
            height="120"
            opacity=".28"
            filter="url(#relax-shadow)"
          />
          <rect
            x="-56"
            y="-62"
            width="112"
            height="124"
            rx="12"
            fill="none"
            stroke="#c05a30"
            stroke-dasharray="4 4"
          />
        </g>
        <image
          :href="buddies[i - 1]!.image"
          x="-54"
          y="-60"
          width="108"
          height="120"
          :transform="`rotate(${game.angles[i - 1]! + game.light})`"
          filter="url(#relax-shadow)"
        />
        <text y="115" text-anchor="middle" fill="#54565d" font-size="12">
          {{ buddies[i - 1]!.name }}
        </text>
      </g>
    </svg>
    <div class="shadow-controls">
      <label
        >灯光方向 <b>{{ game.light }}°</b
        ><input
          v-model.number="game.light"
          type="range"
          min="0"
          max="350"
          step="10"
          @input="won = false" /></label
      ><label v-for="i in count" :key="i"
        >{{ buddies[i - 1]!.name }} <b>{{ game.angles[i - 1] }}°</b
        ><input
          v-model.number="game.angles[i - 1]"
          type="range"
          min="0"
          max="350"
          step="10"
          @input="won = false"
      /></label>
    </div>
    <div class="game-actions">
      <button v-if="won" class="game-primary" @click="next">下一件作品 ↗</button
      ><button v-else class="game-primary" @click="check">看看影子对齐了吗</button
      ><button @click="hint">给一点提示</button
      ><span v-if="count === 3">角度差 {{ Math.round(error) }}°</span>
    </div>
    <p class="game-feedback" role="status">{{ message }}</p>
  </div>
</template>
