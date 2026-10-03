<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useTheme } from '@/composables/theme'
import WorldScene from '@/components/relax/WorldScene.vue'
import { worldById, type WorldId } from '@/components/relax/worlds'
import {
  BreathingSession,
  breathingDefaults,
  readBreathingPreferences,
  breathingPreferenceKey,
  readPracticeSnapshot,
  type PracticeMode,
  type PracticeSnapshot,
} from '@/composables/breathingSession'

const props = defineProps<{ world?: WorldId; story?: string; minimal?: boolean }>()
const emit = defineEmits<{ workspace: [] }>()
const mode = ref<PracticeMode>('breathing')
const pendingMode = ref<PracticeMode | null>(null)
let retainForAuth = false
const modeName = computed(() => (mode.value === 'sitting' ? '静坐' : '呼吸'))
const account = useSessionStore()
const theme = useTheme()
const root = ref<HTMLElement>()
const content = ref<HTMLElement>()
const canvas = ref<HTMLCanvasElement>()
const startButton = ref<HTMLButtonElement>()
const settingsButton = ref<HTMLButtonElement>()
const closeButton = ref<HTMLButtonElement>()
const doneButton = ref<HTMLButtonElement>()
const prefs = reactive({ ...breathingDefaults })
const timer = reactive(new BreathingSession())
const now = ref(Date.now())
const panel = ref(false)
const quiet = ref(false)
const complete = ref(false)
const custom = ref(false)
const minutesInput = ref('15')
const demo = ref(false)
const starting = ref(false)
const reduced = ref(false)
const fullscreen = ref(false)
const notice = ref('')
const storageError = ref(false)
const breath = reactive({ inhale: 4, exhale: 6 })
const colors = { gold: '206,163,106', sage: '135,165,149', blue: '135,160,187' }
const scenes = [
  {
    id: 'halo',
    name: '余晖光晕',
    description: '一团安静的暖光，停在画面中央。没有漂移与闪烁。',
    thumb: 'a',
  },
  {
    id: 'horizon',
    name: '静水地平线',
    description: '一道很淡的远方，像平静水面。没有波浪与流动。',
    thumb: 'b',
  },
  {
    id: 'blank',
    name: '纯净留白',
    description: '只留纯净底色，几乎没有视觉负担。适合放空或长时间停留。',
    thumb: 'c',
  },
] as const
const swatches = [
  { id: 'gold', name: '暖金' },
  { id: 'sage', name: '雾绿' },
  { id: 'blue', name: '月蓝' },
] as const
const rhythms = [
  { inhale: 3, exhale: 4 },
  { inhale: 4, exhale: 4 },
  { inhale: 4, exhale: 6 },
]
const idle = computed(() => timer.state === 'idle')
const validMinutes = computed(
  () =>
    Number.isInteger(Number(minutesInput.value)) &&
    Number(minutesInput.value) >= 1 &&
    Number(minutesInput.value) <= 180,
)
const left = computed(() => timer.left(now.value))
const remaining = computed(() => {
  const s = Math.ceil(left.value / 1000)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
})
const phase = computed(() => (timer.elapsed(now.value) / 1000) % (breath.inhale + breath.exhale))
const inhaling = computed(() => phase.value < breath.inhale)
const expansion = computed(() => {
  const p = inhaling.value
    ? phase.value / breath.inhale
    : 1 - (phase.value - breath.inhale) / breath.exhale
  return (1 - Math.cos(Math.PI * p)) / 2
})
const label = computed(() =>
  idle.value
    ? props.world
      ? worldById(props.world).title
      : '留一点空白，再开始。'
    : timer.state === 'paused'
      ? '安心停一会儿'
      : mode.value === 'sitting'
        ? '只是安静坐着'
        : inhaling.value
          ? '吸气'
          : '呼气',
)
let interval: ReturnType<typeof setInterval> | undefined
let hideTimeout: ReturnType<typeof setTimeout> | undefined
let noticeTimeout: ReturnType<typeof setTimeout> | undefined
let frame = 0
let lastFrame = 0
let observer: ResizeObserver | undefined
let media: MediaQueryList | undefined
let audio: AudioContext | undefined
let disposed = false
let loadingPrefs = false
let audioAttempt = 0

function wake() {
  quiet.value = false
  clearTimeout(hideTimeout)
  hideTimeout = setTimeout(() => {
    if (!panel.value && !root.value?.querySelector(':focus-visible')) quiet.value = true
  }, 5000)
}
function tell(text: string) {
  notice.value = text
  clearTimeout(noticeTimeout)
  noticeTimeout = setTimeout(() => {
    notice.value = ''
  }, 4500)
}
async function setPanel(value: boolean) {
  panel.value = value
  wake()
  await nextTick()
  ;(value ? closeButton.value : settingsButton.value)?.focus()
}
function toggleCustom() {
  custom.value = !custom.value
  minutesInput.value = String(prefs.minutes)
}
function chooseRhythm(inhale: number, exhale: number) {
  prefs.inhale = inhale
  prefs.exhale = exhale
}
function goWorkspace() {
  void closeComplete()
  emit('workspace')
}
function chooseMinutes(value: number) {
  prefs.minutes = value
  minutesInput.value = String(value)
  custom.value = false
}
function editMinutes() {
  if (validMinutes.value) prefs.minutes = Number(minutesInput.value)
}
function reset() {
  Object.assign(prefs, breathingDefaults)
  minutesInput.value = '15'
  custom.value = false
  tell('已恢复余晖光晕与 15 分钟')
}
function chime() {
  try {
    if (!audio || audio.state !== 'running') return false
    const t = audio.currentTime
    for (const [frequency, volume] of [
      [528, 0.11],
      [792, 0.025],
      [1056, 0.012],
    ] as const) {
      const oscillator = audio.createOscillator(),
        gain = audio.createGain()
      oscillator.frequency.value = frequency
      gain.gain.setValueAtTime(0, t)
      gain.gain.linearRampToValueAtTime(volume, t + 0.08)
      gain.gain.exponentialRampToValueAtTime(0.0001, t + 3.2)
      oscillator.connect(gain)
      gain.connect(audio.destination)
      oscillator.start(t)
      oscillator.stop(t + 3.3)
    }
    return true
  } catch {
    return false
  }
}
async function unlockAudio() {
  try {
    audio ??= new AudioContext()
    if (audio.state === 'suspended') {
      let timeout: ReturnType<typeof setTimeout> | undefined
      try {
        await Promise.race([
          audio.resume(),
          new Promise<void>((resolve) => {
            timeout = setTimeout(resolve, 1200)
          }),
        ])
      } finally {
        clearTimeout(timeout)
      }
    }
    return audio.state === 'running'
  } catch {
    return false
  }
}
async function previewSound() {
  const ready = await unlockAudio()
  if (disposed) return
  if (ready) chime()
  else tell('浏览器未能播放声音，请检查音量或静音设置')
}
async function start(isDemo = false) {
  if (!idle.value || starting.value || (custom.value && !validMinutes.value)) return
  starting.value = true
  const attempt = ++audioAttempt
  const member = account.userId
  const soundReady = !prefs.sound || (await unlockAudio())
  if (disposed || attempt !== audioAttempt || account.userId !== member) return
  if (!soundReady) tell('声音暂不可用，本次保留页面结束提醒')
  starting.value = false
  demo.value = isDemo
  Object.assign(breath, { inhale: prefs.inhale, exhale: prefs.exhale })
  now.value = Date.now()
  timer.start(isDemo ? 10000 : prefs.minutes * 60000, now.value)
  panel.value = custom.value = false
  wake()
  syncAnimation()
}
function pauseOrResume() {
  now.value = Date.now()
  if (idle.value) {
    void start()
    return
  }
  if (timer.state === 'running') {
    if (timer.left(now.value) <= 0) {
      finish()
      return
    }
    timer.pause(now.value)
  } else {
    timer.resume(now.value)
    if (prefs.sound)
      void unlockAudio().then((ready) => {
        if (!disposed && !ready) tell('声音暂不可用，本次保留页面结束提醒')
      })
  }
  syncAnimation()
  wake()
}
function stop() {
  if (idle.value) return
  void finish(false)
}
async function finish(natural = true) {
  if (idle.value) return
  timer.stop()
  syncAnimation()
  panel.value = false
  complete.value = true
  if (natural && prefs.sound && !chime()) tell('本次已结束；浏览器未能播放提示音')
  await nextTick()
  doneButton.value?.focus()
  wake()
}
async function closeComplete() {
  complete.value = false
  await nextTick()
  startButton.value?.focus()
  wake()
}
function tick() {
  now.value = Date.now()
  if (timer.state === 'running' && timer.left(now.value) <= 0) void finish()
}
function animate(stamp: number) {
  if (stamp - lastFrame > 33) {
    tick()
    lastFrame = stamp
  }
  if (timer.state === 'running' && !document.hidden) frame = requestAnimationFrame(animate)
}
function syncAnimation() {
  cancelAnimationFrame(frame)
  if (timer.state === 'running' && !document.hidden) frame = requestAnimationFrame(animate)
}
function visibility() {
  tick()
  syncAnimation()
}
function drawGalaxy() {
  if (
    !prefs.galaxy ||
    !canvas.value ||
    !root.value ||
    typeof CanvasRenderingContext2D === 'undefined'
  )
    return
  const c = canvas.value,
    bounds = root.value.getBoundingClientRect()
  const w = Math.max(1, bounds.width),
    h = Math.max(1, bounds.height),
    d = Math.min(devicePixelRatio || 1, 1.5)
  c.width = Math.round(w * d)
  c.height = Math.round(h * d)
  const ctx = c.getContext('2d')
  if (!ctx) return
  ctx.scale(d, d)
  let seed = 85437
  const rand = () => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0
    return seed / 4294967296
  }
  for (let i = 0; i < 3500; i++) {
    const a = rand() * Math.PI * 2,
      r = 0.7 + rand() * 0.5,
      x = Math.cos(a) * r * w * 0.34,
      y = Math.sin(a) * r * h * 0.13 + (rand() - 0.5) * h * 0.065
    ctx.fillStyle = `rgba(${colors[prefs.color]},${0.12 + rand() * 0.45})`
    ctx.fillRect(w / 2 + x, h * 0.4 + y - x * 0.11, 0.6 + rand() * 0.9, 0.6 + rand() * 0.9)
  }
}
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement === root.value) await document.exitFullscreen()
    else if (document.fullscreenEnabled && root.value?.requestFullscreen)
      await root.value.requestFullscreen()
    else tell('此浏览器不支持页面全屏，电脑可按 F11')
  } catch {
    tell('暂时无法进入全屏，电脑可按 F11')
  }
}
function updateFullscreen() {
  fullscreen.value = document.fullscreenElement === root.value
}
function updateMotion() {
  reduced.value = media?.matches ?? false
}
function keydown(event: KeyboardEvent) {
  wake()
  if (complete.value || pendingMode.value) {
    if (event.key === 'Escape') {
      if (pendingMode.value) void cancelMode()
      else void closeComplete()
    }
    if (event.key === 'Tab') {
      const buttons = Array.from(
        root.value?.querySelectorAll<HTMLButtonElement>('.overlay button') ?? [],
      )
      const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
      event.preventDefault()
      buttons[(index + (event.shiftKey ? -1 : 1) + buttons.length) % buttons.length]?.focus()
    }
    return
  }
  if (event.key === 'Escape') {
    if (panel.value) void setPanel(false)
  }
  if ((event.target as HTMLElement)?.closest('input,button,textarea,[contenteditable=true]')) return
  if (event.code === 'Space') {
    event.preventDefault()
    pauseOrResume()
  }
  if (event.key.toLowerCase() === 'f') void toggleFullscreen()
}
watch(
  () => account.userId,
  (member, previous) => {
    audioAttempt++
    starting.value = false
    if (!(retainForAuth && !previous && member)) {
      timer.stop()
      pendingMode.value = null
    }
    retainForAuth = false
    complete.value = false
    loadingPrefs = true
    try {
      Object.assign(
        prefs,
        readBreathingPreferences(localStorage.getItem(breathingPreferenceKey(member))),
      )
      storageError.value = false
    } catch {
      Object.assign(prefs, breathingDefaults)
      storageError.value = true
    }
    minutesInput.value = String(prefs.minutes)
    loadingPrefs = false
    syncAnimation()
  },
  { immediate: true },
)
watch(
  prefs,
  () => {
    if (loadingPrefs) return
    try {
      localStorage.setItem(breathingPreferenceKey(account.userId), JSON.stringify(prefs))
      storageError.value = false
    } catch {
      storageError.value = true
    }
  },
  { deep: true, flush: 'sync' },
)
watch(
  () => [prefs.galaxy, prefs.color],
  () => nextTick(drawGalaxy),
)
function releaseAudio() {
  const previous = audio
  audio = undefined
  void previous?.close().catch(() => {})
}
watch(
  () => prefs.sound,
  (enabled) => {
    if (!enabled) releaseAudio()
    else if (!loadingPrefs)
      void unlockAudio().then((ready) => {
        if (!disposed && !ready) tell('声音暂不可用，本次保留页面结束提醒')
      })
  },
  { flush: 'sync' },
)
function snapshot(): PracticeSnapshot | null {
  if (idle.value || timer.left(Date.now()) <= 0) return null
  return {
    mode: mode.value,
    total: timer.total,
    remaining: timer.left(Date.now()),
    inhale: breath.inhale,
    exhale: breath.exhale,
  }
}
function pauseForAuth() {
  retainForAuth = true
  audioAttempt++
  starting.value = false
  now.value = Date.now()
  if (timer.state === 'running') {
    if (timer.left(now.value) <= 0) {
      void finish()
      return
    }
    timer.pause(now.value)
  }
  panel.value = false
  complete.value = false
  syncAnimation()
  releaseAudio()
}
function restore(value: PracticeSnapshot) {
  const saved = readPracticeSnapshot(value)
  if (!saved) return
  mode.value = saved.mode
  Object.assign(breath, { inhale: saved.inhale, exhale: saved.exhale })
  timer.restore(saved.total, saved.remaining)
  now.value = Date.now()
  tell('已保留本次练习，请准备好后主动继续')
}
async function chooseMode(next: PracticeMode) {
  if (next === mode.value) return
  if (idle.value) {
    mode.value = next
    return
  }
  now.value = Date.now()
  timer.pause(now.value)
  syncAnimation()
  pendingMode.value = next
  await nextTick()
  root.value?.querySelector<HTMLButtonElement>('.overlay button')?.focus()
}
async function cancelMode() {
  pendingMode.value = null
  await nextTick()
  startButton.value?.focus()
}
function confirmMode() {
  if (!pendingMode.value) return
  mode.value = pendingMode.value
  timer.stop()
  void cancelMode()
}
function cancelAuthRetention() {
  retainForAuth = false
}
function pauseForPlay() {
  pauseForAuth()
  cancelAuthRetention()
}
defineExpose({
  pauseForAuth,
  pauseForPlay,
  cancelAuthRetention,
  snapshot,
  restore,
  tell,
  chooseMode,
})
onMounted(() => {
  media = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  updateMotion()
  media?.addEventListener('change', updateMotion)
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(drawGalaxy)
    if (root.value) observer.observe(root.value)
  }
  document.addEventListener('visibilitychange', visibility)
  document.addEventListener('fullscreenchange', updateFullscreen)
  interval = setInterval(() => {
    if (timer.state === 'running') tick()
  }, 250)
  drawGalaxy()
  wake()
})
onBeforeUnmount(() => {
  disposed = true
  audioAttempt++
  timer.stop()
  cancelAnimationFrame(frame)
  clearInterval(interval)
  clearTimeout(hideTimeout)
  clearTimeout(noticeTimeout)
  observer?.disconnect()
  media?.removeEventListener('change', updateMotion)
  document.removeEventListener('visibilitychange', visibility)
  document.removeEventListener('fullscreenchange', updateFullscreen)
  if (document.fullscreenElement === root.value) void document.exitFullscreen().catch(() => {})
  void audio?.close().catch(() => {})
})
</script>

<template>
  <section
    ref="root"
    class="breathing-stage"
    :class="[
      timer.state,
      props.world ? 'world-stage' : '',
      {
        day: theme.effective.value === 'light',
        sitting: mode === 'sitting',
        'no-guide': !prefs.guide,
        minimal: props.minimal,
      },
    ]"
    :style="{ '--accent': colors[prefs.color], '--strength': prefs.glow / 100 }"
    tabindex="-1"
    aria-label="一呼一吸"
    @keydown="keydown"
  >
    <div class="scene" aria-hidden="true">
      <div class="ambient" :class="prefs.scene"></div>
      <canvas ref="canvas" id="galaxy" v-show="prefs.galaxy"></canvas>
    </div>
    <div ref="content" class="stage-content" :inert="complete || !!pendingMode">
      <header class="entry-header">
        <a class="entry-brand" href="/breathe" aria-label="WAO 一呼一吸首页"
          >WAO <span>· 一呼一吸</span></a
        >
        <div class="entry-tools">
          <button
            class="icon"
            :aria-label="`主题：${theme.mode.value === 'system' ? '默认夜间' : theme.mode.value === 'dark' ? '夜间' : '日间'}，点击切换`"
            @click="theme.toggleTheme"
          >
            <i
              :class="theme.mode.value === 'light' ? 'ri-sun-line' : 'ri-moon-line'"
              aria-hidden="true"
            ></i>
          </button>
          <button
            class="icon sound-toggle"
            :aria-pressed="prefs.sound"
            :aria-label="prefs.sound ? '关闭结束提示音' : '开启结束提示音'"
            @click="prefs.sound = !prefs.sound"
          >
            <i
              :class="prefs.sound ? 'ri-volume-up-line' : 'ri-volume-mute-line'"
              aria-hidden="true"
            ></i>
          </button>
          <slot name="account" />
        </div>
      </header>
      <div class="practice-main">
        <div class="focus-scene">
          <div
            class="orb"
            :style="{
              '--scale': idle || reduced || mode === 'sitting' ? 1 : 0.94 + expansion * 0.1,
              '--light': prefs.glow / 100,
            }"
            aria-hidden="true"
          >
            <div class="orb-light"></div>
            <div class="orb-ring"></div>
          </div>
          <div class="center-copy">
            <p class="eyebrow">
              {{
                idle
                  ? '停 下 来 · 回 到 当 下'
                  : demo
                    ? '十 秒 提 醒 体 验'
                    : mode === 'sitting'
                      ? '自 然 呼 吸 · 无 需 跟 随'
                      : '一 呼 · 一 吸'
              }}
            </p>
            <h1 class="phase">{{ label }}</h1>
            <p class="phase-sub">
              {{
                idle
                  ? '不必急着开始'
                  : timer.state === 'paused'
                    ? '进度已保留，准备好后再继续'
                    : mode === 'sitting'
                      ? '让念头来去，不必做些什么'
                      : inhaling
                        ? '慢慢舒展'
                        : '轻轻回落'
              }}
            </p>
          </div>
        </div>
        <WorldScene
          v-if="idle && props.world && !props.minimal"
          :world="props.world"
          :story="props.story"
        />
        <p v-if="idle && prefs.words" class="entry-copy">
          {{
            props.world ? worldById(props.world).subtitle : '跟随一呼一吸，或者只是安静待一会儿。'
          }}
        </p>
        <section class="dock" aria-label="练习计时">
          <div class="mode-picker" role="group" aria-label="练习模式">
            <button :aria-pressed="mode === 'breathing'" @click="chooseMode('breathing')">
              呼吸</button
            ><button :aria-pressed="mode === 'sitting'" @click="chooseMode('sitting')">静坐</button>
          </div>
          <div v-if="idle" class="duration" role="group" aria-label="本次时长">
            <button
              v-for="m in [1, 5, 15, 30]"
              :key="m"
              class="chip"
              :aria-pressed="!custom && prefs.minutes === m"
              @click="chooseMinutes(m)"
            >
              {{ m }} 分钟</button
            ><button class="chip" :aria-expanded="custom" @click="toggleCustom">自定义</button>
          </div>
          <label v-if="idle && custom" class="custom-time"
            >本次时长<input
              v-model="minutesInput"
              type="number"
              min="1"
              max="180"
              step="1"
              :aria-invalid="!validMinutes"
              @input="editMinutes"
            />分钟</label
          >
          <p v-if="idle && custom && !validMinutes" class="error" role="alert">
            请输入 1–180 的整数分钟
          </p>
          <div v-if="!idle" class="session-bar">
            <div class="timer" role="timer" aria-label="剩余练习时间">
              <b>{{ remaining }}</b
              >{{ timer.state === 'paused' ? '已暂停' : '剩余' }}
            </div>
            <div class="progress">
              <i :style="{ '--progress': `${100 * (1 - left / timer.total)}%` }"></i>
            </div>
          </div>
          <div class="actions">
            <button
              ref="startButton"
              class="primary"
              :disabled="starting || (idle && custom && !validMinutes)"
              @click="pauseOrResume"
            >
              {{
                starting
                  ? '准备中…'
                  : idle
                    ? `开始${modeName} · ${prefs.minutes} 分钟`
                    : timer.state === 'paused'
                      ? `继续${modeName}`
                      : '暂停'
              }}</button
            ><button v-if="!idle" class="text-button" @click="stop">结束本次</button>
          </div>
          <p class="bottom-note">
            <template v-if="mode === 'breathing'"
              >吸 {{ idle ? prefs.inhale : breath.inhale }} 秒 · 呼
              {{ idle ? prefs.exhale : breath.exhale }} 秒 · </template
            >{{ prefs.sound ? '结束时一声轻响' : '结束时页面提醒' }}
          </p>
        </section>
      </div>
      <div class="entry-bottom">
        <div class="companions"><slot v-if="idle" name="companions" /></div>
        <div class="scene-actions">
          <button class="text-button" @click="toggleFullscreen">
            {{ fullscreen ? '退出全屏' : '全屏停留' }}</button
          ><button
            ref="settingsButton"
            class="text-button"
            aria-label="调整画面与呼吸"
            :aria-expanded="panel"
            aria-controls="breathing-settings"
            @click="setPanel(!panel)"
          >
            画面与节奏 <i class="ri-equalizer-line" aria-hidden="true"></i>
          </button>
        </div>
      </div>
      <p v-if="storageError" class="storage-notice" role="alert">
        本机暂时无法保存偏好，本次设置仍可使用。
      </p>
      <slot name="footer" />
      <section v-if="panel" id="breathing-settings" class="panel" aria-label="让这里更像你">
        <div class="panel-head">
          <h2>让这里更像你</h2>
          <button ref="closeButton" class="close" aria-label="收起设置" @click="setPanel(false)">
            ×
          </button>
        </div>
        <p class="section-title">没有呼吸时 · 三种静默</p>
        <div class="variants" role="group" aria-label="静默方案">
          <button
            v-for="s in scenes"
            :key="s.id"
            class="variant"
            :aria-pressed="prefs.scene === s.id"
            @click="prefs.scene = s.id"
          >
            <span class="thumb" :class="s.thumb"></span>{{ s.name }}
          </button>
        </div>
        <p class="description">{{ scenes.find((s) => s.id === prefs.scene)?.description }}</p>
        <div class="row">
          <span>光的色温</span>
          <div class="swatches" role="group" aria-label="微光颜色">
            <button
              v-for="s in swatches"
              :key="s.id"
              class="swatch"
              :style="{ '--sw': colors[s.id] }"
              :aria-label="s.name"
              :aria-pressed="prefs.color === s.id"
              @click="prefs.color = s.id"
            ></button>
          </div>
        </div>
        <label class="slider"
          >亮度<input v-model.number="prefs.glow" type="range" min="20" max="100" /><output
            >{{ prefs.glow }}%</output
          ></label
        >
        <label class="row"
          ><span>显示星河<small>默认关闭 · 开启后也保持静止</small></span
          ><input v-model="prefs.galaxy" class="switch" type="checkbox" /></label
        ><label class="row"
          >显示背景文字<input v-model="prefs.words" class="switch" type="checkbox"
        /></label>
        <div class="line"></div>
        <p class="section-title">呼吸时 · 只跟随光圈</p>
        <div class="rhythms">
          <button
            v-for="r in rhythms"
            :key="`${r.inhale}-${r.exhale}`"
            class="chip"
            :disabled="!idle"
            :aria-pressed="prefs.inhale === r.inhale && prefs.exhale === r.exhale"
            @click="chooseRhythm(r.inhale, r.exhale)"
          >
            吸 {{ r.inhale }} · 呼 {{ r.exhale }}
          </button>
        </div>
        <label class="slider"
          >吸气<input
            v-model.number="prefs.inhale"
            type="range"
            min="2"
            max="6"
            step=".5"
            :disabled="!idle"
          /><output>{{ prefs.inhale }} 秒</output></label
        ><label class="slider"
          >呼气<input
            v-model.number="prefs.exhale"
            type="range"
            min="2"
            max="8"
            step=".5"
            :disabled="!idle"
          /><output>{{ prefs.exhale }} 秒</output></label
        >
        <label class="row"
          >显示吸气 / 呼气文字<input v-model="prefs.guide" class="switch" type="checkbox" /></label
        ><label class="row"
          ><span>结束提示音<small>请保持页面打开、设备唤醒。</small></span
          ><input v-model="prefs.sound" class="switch" type="checkbox" /></label
        ><button class="text-button" @click="previewSound">试听结束轻响</button>
        <div class="line"></div>
        <p class="description" :role="storageError ? 'alert' : undefined">
          {{
            storageError
              ? '本机暂时无法保存偏好，当前设置仍可使用。'
              : '访客与成员偏好分别保存在此浏览器。离开空间或刷新后结束计时，返回静默。'
          }}
        </p>
        <p v-if="reduced" class="description">
          已遵循系统减少动态效果设置，光圈保持固定，文字继续引导。
        </p>
        <div class="panel-foot">
          <button class="text-button" :disabled="!idle || starting" @click="start(true)">
            体验 10 秒结束提醒</button
          ><button class="text-button" :disabled="!idle" @click="reset">恢复默认</button>
        </div>
      </section>
    </div>
    <div v-if="complete || pendingMode" class="overlay">
      <section
        class="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="breathing-done-title"
      >
        <template v-if="pendingMode"
          ><p class="eyebrow">进 度 已 暂 停</p>
          <h2 id="breathing-done-title">结束本次，切换模式？</h2>
          <p>还剩 {{ remaining }}。切换会结束当前{{ modeName }}，你也可以保留进度。</p>
          <button ref="doneButton" class="primary" @click="cancelMode">保留本次练习</button
          ><button class="text-button" @click="confirmMode">结束并切换</button></template
        >
        <template v-else
          ><div class="seal">✓</div>
          <p class="eyebrow">把 这 份 从 容 · 带 回 日 常</p>
          <h2 id="breathing-done-title">本次{{ modeName }}已结束</h2>
          <p>
            {{
              demo ? '10 秒体验已结束。' : '这一段留白，属于你。'
            }}让呼吸回到自然，想停留多久都可以。
          </p>
          <button ref="doneButton" class="primary" @click="closeComplete">再待一会儿</button
          ><button class="text-button" @click="goWorkspace">进入工作空间 ↗</button></template
        >
      </section>
    </div>
    <div v-if="notice" class="toast" role="status">{{ notice }}</div>
    <span class="sr" role="status">{{ complete ? `本次${modeName}已结束` : '' }}</span>
  </section>
</template>

<style scoped>
.breathing-stage {
  color-scheme: dark;
  --bg: #090a0c;
  --text: #e5e5e8;
  --muted: #a1a1aa;
  --line: #ffffff24;
  --panel: #17171b;
  --selected: #ffffff0b;
  --accent: 206, 163, 106;
  --strength: 0.65;
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
}
* {
  box-sizing: border-box;
}
.breathing-stage {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  transition: none;
}
.breathing-stage.day {
  color-scheme: light;
  --bg: #ededf0;
  --text: #34343b;
  --muted: #666670;
  --line: #25253024;
  --panel: #f7f7f9;
  --selected: #25253009;
}
button,
input {
  font: inherit;
}
button {
  color: inherit;
  cursor: pointer;
}
button,
input {
  outline-offset: 5px;
}
button:focus-visible,
input:focus-visible {
  outline: 2px solid rgba(var(--accent), 0.8);
}
button {
  border: 0;
}
button:disabled {
  opacity: 0.35;
  cursor: default;
}
[hidden] {
  display: none !important;
}
.scene {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}
.ambient {
  position: absolute;
  inset: 0;
  opacity: calc(var(--strength) * 0.3);
  transition: opacity 1s;
}
.ambient:before,
.ambient:after {
  content: '';
  position: absolute;
  transition: opacity 1s;
}
.ambient.halo:before {
  width: min(72cqw, 900px);
  height: min(72cqw, 900px);
  left: 50%;
  top: 46%;
  transform: translate(-50%, -50%);
  background: radial-gradient(
    circle,
    rgba(var(--accent), 0.11),
    rgba(var(--accent), 0.065) 24%,
    rgba(var(--accent), 0.022) 42%,
    transparent 67%
  );
}
.ambient.halo:after {
  inset: 0;
  background: radial-gradient(ellipse at 50% 110%, rgba(var(--accent), 0.065), transparent 60%);
}
.ambient.horizon:before {
  left: 0;
  right: 0;
  top: 54%;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(var(--accent), 0.26), transparent);
}
.ambient.horizon:after {
  inset: 0;
  background:
    radial-gradient(ellipse 48% 12% at 50% 54%, rgba(var(--accent), 0.12), transparent),
    linear-gradient(transparent 54%, rgba(var(--accent), 0.025) 55%, transparent 83%);
}
.ambient.blank:before {
  inset: 0;
  background: radial-gradient(ellipse at 50% 45%, rgba(var(--accent), 0.025), transparent 75%);
}
#galaxy {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0.38;
}
.brand {
  position: absolute;
  left: 42px;
  top: 34px;
  display: flex;
  align-items: center;
  gap: 19px;
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--muted);
  z-index: 2;
}
.brand strong {
  font-size: 14px;
  letter-spacing: 5px;
  font-weight: 400;
  color: var(--text);
}
.brand i {
  height: 12px;
  width: 1px;
  background: var(--line);
}
.top-right {
  position: absolute;
  right: 34px;
  top: 26px;
  display: flex;
  gap: 10px;
  align-items: center;
  z-index: 4;
}
.tag {
  font-size: 9px;
  color: var(--muted);
  letter-spacing: 2px;
  margin-right: 10px;
}
.icon {
  width: 38px;
  height: 38px;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: transparent;
  display: grid;
  place-items: center;
  color: var(--muted);
}
.icon:hover {
  color: var(--text);
  background: rgba(var(--accent), 0.08);
}
.icon svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.center {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.orb {
  width: min(39cqw, 300px);
  height: min(39cqw, 300px);
  min-width: 210px;
  min-height: 210px;
  position: absolute;
  top: 46%;
  left: 50%;
  transform: translate(-50%, -50%);
  opacity: 0;
  transition: opacity 1.2s;
}
.running .orb,
.paused .orb {
  opacity: 1;
}
.orb-light {
  position: absolute;
  inset: -35%;
  border-radius: 50%;
  background: radial-gradient(
    circle,
    rgba(var(--accent), 0.095),
    rgba(var(--accent), 0.07) 30%,
    transparent 65%
  );
  transform: scale(var(--scale, 0.78));
  opacity: var(--light, 0.4);
}
.orb-ring {
  position: absolute;
  inset: 0;
  border: 1px solid rgba(var(--accent), 0.31);
  border-radius: 50%;
  transform: scale(var(--scale, 0.78));
  box-shadow:
    inset 0 0 48px rgba(var(--accent), 0.025),
    0 0 28px rgba(var(--accent), 0.035);
}
.orb-ring:after {
  content: '';
  position: absolute;
  inset: 10px;
  border: 1px solid rgba(var(--accent), 0.05);
  border-radius: 50%;
}
.center-copy {
  position: absolute;
  top: 46%;
  left: 50%;
  transform: translate(-50%, -50%);
  text-align: center;
  white-space: nowrap;
}
.eyebrow {
  font-size: 9px;
  letter-spacing: 4px;
  color: var(--muted);
  margin: 0 0 23px;
  text-indent: 4px;
}
.phase {
  font-size: 22px;
  font-weight: 300;
  letter-spacing: 10px;
  text-indent: 10px;
  margin: 0;
  color: var(--text);
}
.phase-sub {
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--muted);
  margin: 17px 0 0;
}
.idle .phase {
  font-size: 19px;
  letter-spacing: 8px;
}
.slogan {
  position: absolute;
  left: 50%;
  top: 66%;
  transform: translateX(-50%);
  text-align: center;
  color: rgba(var(--accent), 0.34);
  font-size: 11px;
  line-height: 2.2;
  letter-spacing: 2px;
  white-space: nowrap;
  pointer-events: none;
}
.slogan span {
  font-size: 9px;
  letter-spacing: 3px;
}
.running .slogan,
.paused .slogan {
  opacity: 0.3;
}
.dock {
  position: absolute;
  bottom: max(35px, env(safe-area-inset-bottom));
  left: 50%;
  transform: translateX(-50%);
  width: min(460px, calc(100% - 40px));
  display: flex;
  align-items: center;
  flex-direction: column;
  z-index: 3;
  gap: 18px;
}
.duration {
  display: flex;
  gap: 9px;
  align-items: center;
}
.chip {
  background: transparent;
  border: 1px solid var(--line);
  border-radius: 20px;
  padding: 8px 17px;
  color: var(--muted);
  font-size: 11px;
  white-space: nowrap;
}
.chip[aria-pressed='true'] {
  color: var(--text);
  border-color: rgba(var(--accent), 0.35);
  background: rgba(var(--accent), 0.07);
}
.chip:hover {
  color: var(--text);
}
.actions {
  display: flex;
  gap: 10px;
  justify-content: center;
  align-items: center;
}
.primary {
  min-width: 164px;
  padding: 13px 26px;
  border: 1px solid rgba(var(--accent), 0.28);
  border-radius: 28px;
  background: rgba(var(--accent), 0.11);
  color: var(--text);
  font-size: 12px;
  letter-spacing: 3px;
  transition: background 0.3s;
}
.primary:hover {
  background: rgba(var(--accent), 0.2);
}
.text-button {
  background: transparent;
  color: var(--muted);
  padding: 10px 13px;
  font-size: 11px;
}
.text-button:hover {
  color: var(--text);
}
.bottom-note {
  font-size: 9px;
  letter-spacing: 1px;
  color: var(--muted);
  margin: 0;
}
.timer {
  font-size: 12px;
  letter-spacing: 3px;
  font-variant-numeric: tabular-nums;
  color: var(--muted);
}
.timer b {
  color: var(--text);
  font-weight: 400;
  font-size: 17px;
  margin-right: 8px;
}
.session-bar {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 15px;
}
.progress {
  width: 150px;
  height: 1px;
  background: var(--line);
}
.progress i {
  display: block;
  height: 1px;
  background: rgba(var(--accent), 0.65);
  width: var(--progress, 0%);
}
.settings-trigger {
  position: absolute;
  right: 35px;
  bottom: 34px;
  z-index: 6;
}
.panel {
  position: absolute;
  right: 30px;
  top: 82px;
  bottom: 88px;
  width: 338px;
  max-height: 740px;
  z-index: 10;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 19px;
  padding: 25px;
  overflow: auto;
  box-shadow: 0 20px 90px #0002;
  backdrop-filter: blur(25px);
}
.panel h2 {
  font-size: 15px;
  font-weight: 400;
  letter-spacing: 3px;
  margin: 0;
}
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 25px;
}
.close {
  font-size: 23px;
  color: var(--muted);
  background: none;
  line-height: 1;
}
.section-title {
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--muted);
  margin: 23px 0 13px;
}
.variants {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.variant {
  border-radius: 10px;
  border: 1px solid var(--line);
  padding: 7px;
  background: transparent;
  text-align: center;
  color: var(--muted);
  font-size: 10px;
}
.variant[aria-pressed='true'] {
  border-color: rgba(var(--accent), 0.55);
  color: var(--text);
}
.thumb {
  height: 46px;
  border-radius: 5px;
  display: block;
  background: var(--bg);
  position: relative;
  overflow: hidden;
  margin-bottom: 8px;
}
.thumb.a {
  background: radial-gradient(circle, rgba(var(--accent), 0.2), transparent 66%), var(--bg);
}
.thumb.b {
  background:
    radial-gradient(ellipse 70% 18% at 50% 55%, rgba(var(--accent), 0.2), transparent), var(--bg);
}
.thumb.b:after {
  content: '';
  position: absolute;
  top: 55%;
  left: 10%;
  right: 10%;
  height: 1px;
  background: rgba(var(--accent), 0.25);
}
.thumb.c {
  background: var(--bg);
}
.description {
  font-size: 10px;
  line-height: 1.8;
  color: var(--muted);
  margin: 12px 0;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 11px;
  color: var(--text);
  margin: 20px 0;
}
.row small {
  display: block;
  margin-top: 6px;
  color: var(--muted);
  font-size: 9px;
  line-height: 1.7;
}
.switch {
  appearance: none;
  width: 30px;
  height: 17px;
  flex: none;
  border-radius: 20px;
  background: var(--line);
  position: relative;
  cursor: pointer;
  margin: 0;
}
.switch:after {
  content: '';
  position: absolute;
  left: 3px;
  top: 3px;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: var(--muted);
  transition: transform 0.2s;
}
.switch:checked {
  background: rgba(var(--accent), 0.3);
}
.switch:checked:after {
  transform: translateX(13px);
  background: rgb(var(--accent));
}
.slider {
  display: grid;
  grid-template-columns: 35px 1fr 35px;
  align-items: center;
  gap: 8px;
  margin: 17px 0;
  font-size: 10px;
  color: var(--muted);
}
.slider output {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
input[type='range'] {
  width: 100%;
  accent-color: rgb(var(--accent));
  height: 3px;
  cursor: pointer;
}
.swatches {
  display: flex;
  gap: 10px;
}
.swatch {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: rgb(var(--sw));
  border: 4px solid var(--panel);
  outline: 1px solid transparent;
}
.swatch[aria-pressed='true'] {
  outline-color: var(--text);
}
.line {
  height: 1px;
  background: var(--line);
  margin: 22px 0;
}
.rhythms {
  display: flex;
  gap: 6px;
}
.rhythms .chip {
  padding: 7px 10px;
  font-size: 10px;
  flex: 1;
}
.panel-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--muted);
  font-size: 9px;
}
.panel-foot button {
  font-size: 10px;
  padding: 8px 0;
}
.custom-time {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  color: var(--muted);
}
.custom-time input {
  width: 80px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: rgba(var(--accent), 0.035);
  color: var(--text);
  padding: 8px;
}
.overlay {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  z-index: 20;
  background: #05090a77;
  backdrop-filter: blur(12px);
}
.dialog {
  padding: 38px 35px;
  border: 1px solid var(--line);
  background: var(--panel);
  border-radius: 22px;
  width: min(380px, calc(100% - 40px));
  text-align: center;
  box-shadow: 0 25px 100px #0003;
}
.dialog .seal {
  width: 48px;
  height: 48px;
  border: 1px solid rgba(var(--accent), 0.4);
  border-radius: 50%;
  display: grid;
  place-items: center;
  margin: 0 auto 24px;
  color: rgb(var(--accent));
  font-size: 18px;
}
.dialog h2 {
  font-size: 20px;
  font-weight: 400;
  letter-spacing: 4px;
}
.dialog p {
  font-size: 11px;
  color: var(--muted);
  line-height: 2;
  margin: 18px 0 27px;
}
.toast {
  position: absolute;
  top: 88px;
  left: 50%;
  transform: translateX(-50%);
  padding: 11px 20px;
  max-width: calc(100% - 40px);
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 20px;
  font-size: 11px;
  line-height: 1.7;
  text-align: center;
  z-index: 30;
}
.quiet .brand,
.quiet .tag,
.quiet .bottom-note {
  opacity: 0;
}
.brand,
.tag,
.bottom-note {
  transition: opacity 1.3s;
}
.quiet .top-right,
.quiet .settings-trigger {
  opacity: 0.22;
}
.top-right:hover,
.settings-trigger:hover,
.top-right:has(:focus-visible),
.settings-trigger:focus-visible {
  opacity: 1;
}
.running .eyebrow,
.paused .eyebrow {
  opacity: 0.65;
}
.no-guide .center-copy {
  visibility: hidden;
}
.no-guide.idle .center-copy {
  visibility: visible;
}
.error {
  font-size: 10px;
  color: #c98a69;
  margin: 0;
}
.sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
}
@media (max-width: 600px) {
  .brand {
    left: 24px;
    top: 28px;
    gap: 12px;
  }
  .brand strong {
    font-size: 12px;
  }
  .brand span {
    font-size: 9px;
  }
  .top-right {
    right: 17px;
    top: 20px;
    gap: 6px;
  }
  .tag {
    display: none;
  }
  .settings-trigger {
    right: 20px;
    bottom: 22px;
  }
  .dock {
    bottom: 85px;
    gap: 15px;
  }
  .orb {
    top: 41%;
    min-width: 220px;
    min-height: 220px;
  }
  .center-copy {
    top: 41%;
  }
  .slogan {
    top: 59%;
    font-size: 9px;
    letter-spacing: 1px;
  }
  .slogan span {
    font-size: 8px;
  }
  .panel {
    right: 15px;
    left: 15px;
    top: 76px;
    bottom: 76px;
    width: auto;
    padding: 23px;
  }
  .chip {
    padding: 8px 13px;
  }
  .ambient.halo:before {
    width: 120cqw;
    height: 120cqw;
    top: 41%;
  }
  .phase {
    font-size: 20px;
  }
  .duration {
    gap: 7px;
  }
  .bottom-note {
    font-size: 8px;
  }
  .dialog {
    padding: 32px 25px;
  }
}
@media (prefers-reduced-motion: reduce) {
  *,
  *:before,
  *:after {
    transition: none !important;
  }
}

.breathing-stage {
  position: relative;
  isolation: isolate;
  container-type: inline-size;
  width: 100%;
  min-height: 650px;
  height: calc(100dvh - var(--wao-header-h, 56px) - var(--wao-tabbar-h, 40px) - 64px);
  overflow: hidden;
  border-radius: 16px;
}
.breathing-stage:fullscreen {
  width: 100vw;
  height: 100dvh;
  border-radius: 0;
  min-height: 0;
}
.stage-content {
  position: absolute;
  inset: 0;
}
.home-info {
  position: absolute;
  top: 78px;
  right: 30px;
  z-index: 8;
  max-width: calc(100% - 40px);
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 16px;
  border-radius: 15px;
  box-shadow: 0 20px 60px #0002;
}
.home-info :deep(.home-day-tools) {
  flex-wrap: wrap;
  justify-content: flex-end;
}
@media (max-width: 600px) {
  .breathing-stage {
    min-height: 650px;
    height: calc(100dvh - 145px);
  }
  .home-info {
    left: 15px;
    right: 15px;
    max-width: none;
    top: 75px;
  }
  .top-right .text-button {
    font-size: 9px;
    padding: 6px;
  }
  .brand {
    gap: 8px;
    left: 20px;
  }
  .brand span {
    display: none;
  }
}
@media (max-height: 650px) {
  .breathing-stage:fullscreen .dock {
    bottom: 20px;
    gap: 10px;
  }
  .breathing-stage:fullscreen .slogan {
    display: none;
  }
  .breathing-stage:fullscreen .orb,
  .breathing-stage:fullscreen .center-copy {
    top: 36%;
  }
  .breathing-stage:fullscreen .orb {
    min-width: 150px;
    min-height: 150px;
    width: 170px;
    height: 170px;
  }
}
@container (max-width: 340px) {
  .duration {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 6px;
  }
  .dock {
    width: calc(100% - 32px);
  }
  .settings-trigger {
    left: 20px;
    right: auto;
  }
  .phase {
    font-size: 17px;
    letter-spacing: 6px;
  }
  .slogan {
    font-size: 8px;
    letter-spacing: 0;
  }
  .brand {
    display: none;
  }
}
</style>

<style scoped>
.breathing-stage {
  min-height: 100dvh;
  height: auto;
  border-radius: 0;
  overflow: clip;
  --muted: #a1a1aa;
}
.breathing-stage.day {
  --muted: #666670;
}
.stage-content {
  position: relative;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  padding: 26px 42px 10px;
}
.entry-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  z-index: 2;
}
.entry-brand {
  color: var(--text);
  text-decoration: none;
  font-size: 18px;
  letter-spacing: 3px;
  white-space: nowrap;
}
.entry-brand span {
  font-size: 13px;
  letter-spacing: 2px;
}
.entry-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}
.practice-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 20px 0 22px;
}
.focus-scene {
  position: relative;
  width: 100%;
  height: clamp(220px, 35dvh, 360px);
  display: grid;
  place-items: center;
}
.orb {
  top: 50%;
  width: 260px;
  height: 260px;
  min-width: 0;
  min-height: 0;
  opacity: 0.55;
}
.running .orb,
.paused .orb {
  opacity: 1;
}
.idle .orb-ring {
  border-color: rgba(var(--accent), 0.1);
}
.idle .orb-ring:after {
  inset: 18px;
}
.orb-light {
  background: radial-gradient(circle, rgba(var(--accent), 0.035), transparent 60%);
}
.orb-ring {
  box-shadow: none;
}
.duration button[aria-pressed='true'],
.primary {
  border-color: var(--line);
  background: var(--selected);
}
.primary:hover {
  background: #ffffff14;
}
.center-copy {
  top: 50%;
  white-space: normal;
  width: 100%;
}
.phase,
.idle .phase {
  font-size: clamp(22px, 3vw, 34px);
  letter-spacing: 3px;
  text-indent: 0;
  font-weight: 300;
  line-height: 1.5;
}
.phase-sub {
  font-size: 12px;
  letter-spacing: 1px;
}
.entry-copy {
  color: var(--muted);
  font-size: 12px;
  margin: -8px 0 28px;
  text-align: center;
  line-height: 1.8;
}
.dock {
  position: relative;
  left: auto;
  bottom: auto;
  transform: none;
  width: 100%;
  gap: 18px;
}
.mode-picker {
  display: flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--line);
  border-radius: 30px;
}
.mode-picker button {
  background: transparent;
  padding: 9px 30px;
  border-radius: 24px;
  font-size: 12px;
  color: var(--muted);
}
.mode-picker button[aria-pressed='true'] {
  color: var(--text);
  background: var(--selected);
}
.duration {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}
.timer b {
  font-size: 28px;
}
.no-guide.sitting .center-copy {
  visibility: visible;
}
.entry-bottom {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  min-height: 84px;
}
.companions {
  width: 190px;
}
.scene-actions {
  display: flex;
  white-space: nowrap;
  align-self: flex-end;
}
.storage-notice {
  color: var(--muted);
  font-size: 12px;
  text-align: center;
}
.panel {
  position: fixed;
  top: 82px;
  bottom: 26px;
  z-index: 10;
}
.overlay {
  position: fixed;
  overflow: auto;
  padding: 18px 0;
}
.dialog {
  max-height: calc(100dvh - 36px);
  overflow: auto;
}
.dialog button {
  display: block;
  margin: 12px auto 0;
}
.toast {
  position: fixed;
}
@media (max-width: 600px) {
  .stage-content {
    padding: 20px 18px 8px;
  }
  .entry-header {
    flex-wrap: wrap;
    gap: 14px;
  }
  .entry-brand {
    font-size: 15px;
    letter-spacing: 2px;
  }
  .entry-brand span {
    font-size: 12px;
    letter-spacing: 1px;
  }
  .entry-tools {
    gap: 6px;
    margin-left: auto;
  }
  .entry-tools .icon {
    width: 32px;
    height: 32px;
  }
  .focus-scene {
    height: 240px;
  }
  .orb {
    width: 210px;
    height: 210px;
    top: 50%;
  }
  .center-copy {
    top: 50%;
  }
  .entry-copy {
    font-size: 11px;
    margin: 0 0 24px;
  }
  .dock {
    bottom: auto;
    gap: 16px;
  }
  .chip {
    padding: 8px 11px;
  }
  .entry-bottom {
    min-height: 72px;
    gap: 0;
  }
  .companions {
    width: 130px;
  }
  .scene-actions .text-button {
    padding: 10px 7px;
    font-size: 10px;
  }
  .panel {
    top: 72px;
    bottom: 20px;
  }
  .bottom-note {
    font-size: 10px;
  }
}
@media (max-width: 380px) {
  .entry-header {
    justify-content: center;
  }
  .entry-tools {
    margin: 0;
  }
  .entry-bottom {
    flex-direction: column;
    align-items: center;
  }
  .scene-actions {
    align-self: center;
  }
}
@media (max-height: 600px) {
  .focus-scene {
    height: 180px;
  }
  .orb {
    width: 170px;
    height: 170px;
  }
  .entry-copy {
    margin: 0 0 16px;
  }
  .dock {
    gap: 12px;
  }
  .companions {
    display: none;
  }
  .entry-bottom {
    min-height: 36px;
    justify-content: flex-end;
  }
  .practice-main {
    padding: 10px 0;
  }
}
.breathing-stage:fullscreen {
  overflow: auto;
}
@container (max-width:340px) {
  .duration {
    display: flex;
  }
  .dock {
    width: 100%;
  }
}
</style>
<style scoped>
@media (max-height: 500px) and (min-width: 601px) {
  .stage-content {
    padding: 14px 24px 6px;
  }
  .practice-main {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
    padding: 10px 0;
  }
  .focus-scene {
    height: 175px;
  }
  .entry-copy {
    display: none;
  }
  .dock {
    grid-column: 2;
    grid-row: 1;
    gap: 10px;
  }
  .phase,
  .idle .phase {
    font-size: 23px;
  }
  .eyebrow {
    margin-bottom: 12px;
  }
  .duration {
    gap: 5px;
  }
  .chip {
    padding: 8px 10px;
  }
  .entry-bottom {
    min-height: 24px;
  }
}
</style>

<style scoped>
.breathing-stage.minimal {
  background: transparent;
  min-height: calc(100dvh - 82px);
  --text: #424740;
  --muted: #898d85;
  --line: #dcded6;
  --selected: #e8e9df;
}
.minimal:not(.day) {
  --text: #e8e8df;
  --muted: #979b95;
  --line: #454942;
  --selected: #34382f;
}
.minimal .stage-content {
  min-height: calc(100dvh - 82px);
  padding: 10px 24px 16px;
}
.minimal .scene,
.minimal .entry-header,
.minimal .mode-picker,
.minimal .entry-copy,
.minimal .eyebrow,
.minimal .phase-sub,
.minimal .companions {
  display: none;
}
.minimal .orb {
  width: 240px;
  height: 240px;
  opacity: 0.8;
}
.minimal .orb-light {
  display: none;
}
.minimal .orb-ring {
  border: 1px solid #abb59b77;
  box-shadow: none;
}
.minimal .orb-ring:after {
  display: none;
}
.minimal .phase {
  font-size: 24px;
  letter-spacing: 2px;
}
.minimal .entry-bottom {
  min-height: 42px;
  justify-content: center;
}
.minimal .scene-actions {
  align-self: center;
}
.minimal .dock {
  gap: 18px;
}
.minimal .focus-scene {
  height: 320px;
}
.minimal .practice-main {
  padding: 10px 0;
}
.minimal .primary {
  border-radius: 24px;
  min-width: 170px;
}
@media (max-width: 600px) {
  .minimal .stage-content {
    min-height: calc(100dvh - 66px);
    padding: 8px 14px;
  }
  .minimal .focus-scene {
    height: 300px;
  }
  .minimal .phase {
    font-size: 21px;
  }
  .minimal .orb {
    width: 220px;
    height: 220px;
  }
}
</style>
