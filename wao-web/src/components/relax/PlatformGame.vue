<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import { buddies } from './worlds'
import { clamp, platformCollectibles, platformSolids, stepBody, type Body } from './engine'
import { freshProgress, type RelaxProgress } from './progress'
import { useGameLoop } from './useGameLoop'
const props = defineProps<{ progress: RelaxProgress['platform']; active: boolean }>(),
  emit = defineEmits<{ story: [text: string] }>()
const game = props.progress
const board = ref<HTMLDivElement>(),
  message = ref('先用星星照亮路标，再让五个伙伴各取一枚贴纸。'),
  keys = new Set<string>()
const body: Body = { x: game.x, y: game.y, w: 34, h: 44, vx: 0, vy: 0 }
let grounded = false,
  dash = 0,
  checkpoint = 30
const camera = computed(() => clamp(game.x - 130, 0, 720))
const solids = computed(() => [
  ...platformSolids,
  ...(game.bridge ? [{ x: 460, y: 330, w: 120, h: 24 }] : []),
  { x: game.crate, y: 290, w: 42, h: 40 },
  ...(game.crate < 330 ? [{ x: 400, y: 212, w: 18, h: 118 }] : []),
  ...(!game.gate ? [{ x: 1110, y: 200, w: 15, h: 130 }] : []),
])
function skill() {
  if (!props.active || game.won) return
  if (game.role === 0) {
    game.lit = true
    message.value = '星星把路标照亮了，黄色贴纸就在前面。'
  }
  if (game.role === 1) {
    if (game.x > 340 && game.x < 625) {
      game.bridge = true
      message.value = '小本铺好了纸桥。'
    } else message.value = '带小本走到断桥附近再搭桥。'
  }
  if (game.role === 2) {
    if (Math.abs(game.x - game.crate) < 105) {
      game.crate = clamp(game.crate + 35, 180, 365)
      message.value =
        game.crate >= 330 ? '箱子压住开关，前面的门开了。' : '机器人把箱子推近了开关。'
    } else message.value = '靠近木箱，再使用推箱技能。'
  }
  if (game.role === 3) {
    dash = 0.5
    message.value = '闪电冲刺！跳起时冲刺，拿到粉色贴纸。'
  }
  if (game.role === 4) {
    if (game.x > 970) {
      game.gate = true
      message.value = '方块压住机关，终点打开了。'
    } else message.value = '把方块带到右侧绿色机关。'
  }
}
function jump() {
  if (grounded && props.active) {
    body.vy = -435
    grounded = false
  }
}
function hold(e: PointerEvent, code: string) {
  keys.add(code)
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}
function key(e: KeyboardEvent, on: boolean) {
  if (
    ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'Space', 'KeyA', 'KeyD', 'KeyW', 'KeyE'].includes(e.code)
  ) {
    e.preventDefault()
    if (on) {
      keys.add(e.code)
      if (!e.repeat && ['ArrowUp', 'Space', 'KeyW'].includes(e.code)) jump()
      if (!e.repeat && e.code === 'KeyE') skill()
    } else keys.delete(e.code)
  }
  if (on && /^Digit[1-5]$/.test(e.code)) game.role = Number(e.code.slice(-1)) - 1
}
function reset() {
  Object.assign(game, freshProgress().platform)
  body.x = 30
  body.y = 280
  body.vx = 0
  body.vy = 0
  checkpoint = 30
  message.value = '伙伴都回到起点，重新出发吧。'
}
watch(
  () => props.active,
  (on) => {
    if (!on) {
      keys.clear()
      dash = 0
    }
  },
)
useGameLoop(toRef(props, 'active'), (dt) => {
  if (game.won) return
  dash = Math.max(0, dash - dt)
  const left = keys.has('ArrowLeft') || keys.has('KeyA'),
    right = keys.has('ArrowRight') || keys.has('KeyD')
  body.vx = (right ? 1 : left ? -1 : dash > 0 ? 1 : 0) * (dash > 0 ? 400 : 175)
  if (
    game.role === 2 &&
    body.vx > 0 &&
    body.x + body.w > game.crate - 3 &&
    body.x < game.crate &&
    body.y > 245
  )
    game.crate = clamp(game.crate + 95 * dt, 180, 365)
  grounded = stepBody(body, solids.value, dt)
  if (body.y > 430) {
    body.x = checkpoint
    body.y = 265
    body.vy = 0
    message.value = '掉下来也没关系，从安全点再出发。'
    emit('story', '像素小队刚掉进一个小坑，拍拍灰又站起来了。')
  }
  if (grounded && body.x > 595 && body.x < 690) checkpoint = 610
  for (const c of platformCollectibles)
    if (
      c.role === game.role &&
      !game.collected.includes(c.role) &&
      Math.hypot(body.x - c.x, body.y - c.y) < 48
    ) {
      const ok =
        c.role === 0
          ? game.lit
          : c.role === 1
            ? game.bridge
            : c.role === 2
              ? game.crate >= 330
              : c.role === 3
                ? dash > 0
                : game.gate
      if (ok) {
        game.collected.push(c.role)
        message.value = `${buddies[c.role]!.name}拿到贴纸！已收集 ${game.collected.length}/5。`
      }
    }
  game.x = body.x
  game.y = body.y
  if (body.x > 1140 && game.collected.length === 5) {
    game.won = true
    keys.clear()
    message.value = '五位伙伴一起到家了！摸鱼冒险成功。'
    emit('story', '五人摸鱼小队集齐了贴纸，正在争论谁先挑沙发。')
  }
})
</script>
<template>
  <div>
    <div class="game-meta">
      <span>休息贴纸 {{ game.collected.length }} / 5</span
      ><span>{{ buddies[game.role]!.name }} · {{ buddies[game.role]!.skill }}</span>
    </div>
    <div
      ref="board"
      class="platform-focus"
      tabindex="0"
      role="group"
      aria-label="平台闯关，方向键移动，空格跳跃，E 技能，数字一到五切换角色"
      @keydown="key($event, true)"
      @keyup="key($event, false)"
      @blur="keys.clear()"
    >
      <svg
        class="game-board platform-board"
        :viewBox="`${camera} 75 480 310`"
        role="img"
        aria-label="像素伙伴冒险场景"
      >
        <defs>
          <pattern id="relax-blocks" width="28" height="24" patternUnits="userSpaceOnUse">
            <rect width="27" height="23" fill="#46603b" />
          </pattern>
        </defs>
        <rect :x="camera" y="75" width="480" height="310" fill="#102419" />
        <path
          d="M0 140h120m100 45h80m160-65h100m180 42h90m90-25h130"
          stroke="#294431"
          stroke-width="11"
        />
        <rect
          v-for="(r, i) in platformSolids"
          :key="i"
          v-bind="{ x: r.x, y: r.y, width: r.w, height: r.h }"
          fill="url(#relax-blocks)"
        />
        <rect v-if="game.bridge" x="460" y="330" width="120" height="18" fill="#b7a2f2" />
        <text x="452" y="372" fill="#d6c5ff" font-size="12">小本搭桥</text>
        <rect :x="game.crate" y="290" width="42" height="40" fill="#c79757" stroke="#e4be82" />
        <path :d="`M${game.crate + 5} 295l32 29m-32 0 32-29`" stroke="#97703d" stroke-width="3" />
        <rect x="329" y="326" width="55" height="5" fill="#65dfcf" />
        <rect v-if="game.crate < 330" x="400" y="212" width="18" height="118" fill="#598b7b" />
        <rect v-if="!game.gate" x="1110" y="200" width="15" height="130" fill="#b6d87c" />
        <text v-if="game.lit" x="212" y="261" fill="#d5e9ae" font-size="12">机器人推箱 →</text>
        <text x="782" y="192" fill="#ffb8d8" font-size="12">跳跃 + 闪电冲刺</text>
        <text x="990" y="280" fill="#d5e9ae" font-size="12">方块压机关</text>
        <g
          v-for="c in platformCollectibles.filter((c) => !game.collected.includes(c.role))"
          :key="c.role"
        >
          <circle
            :cx="c.x + 17"
            :cy="c.y + 15"
            r="12"
            :fill="buddies[c.role]!.color"
            :opacity="game.lit ? 1 : 0.35"
          />
          <text :x="c.x + 17" :y="c.y + 19" text-anchor="middle" fill="#17311e" font-size="11">
            {{ c.role + 1 }}
          </text>
        </g>
        <image
          :href="buddies[game.role]!.image"
          :x="game.x - 9"
          :y="game.y - 11"
          width="53"
          height="61"
          style="image-rendering: pixelated"
        />
        <text x="1144" y="240" fill="#efffdf" font-size="18">⌂</text>
      </svg>
    </div>
    <div class="role-picker">
      <button
        v-for="(b, i) in buddies"
        :key="i"
        :aria-pressed="game.role === i"
        @click="game.role = i"
      >
        <img :src="b.image" alt="" /><span>{{ i + 1 }} · {{ b.skill }}</span>
      </button>
    </div>
    <div class="game-actions touch-controls">
      <button
        aria-label="向左移动"
        @pointerdown.prevent="hold($event, 'ArrowLeft')"
        @pointerup="keys.clear()"
        @pointercancel="keys.clear()"
        @lostpointercapture="keys.clear()"
      >
        ←</button
      ><button
        aria-label="向右移动"
        @pointerdown.prevent="hold($event, 'ArrowRight')"
        @pointerup="keys.clear()"
        @pointercancel="keys.clear()"
        @lostpointercapture="keys.clear()"
      >
        →</button
      ><button @click="jump">跳跃 ↑</button
      ><button class="game-primary" @click="skill">{{ buddies[game.role]!.skill }} · E</button
      ><button v-if="game.won" @click="reset">再冒险一次</button>
    </div>
    <p class="game-feedback" role="status">{{ message }}</p>
    <p class="game-help">
      点一下画面：方向键 / A D 移动，空格跳跃，E 技能，1–5 换角色。触屏使用上方按钮。
    </p>
  </div>
</template>
