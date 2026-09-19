<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'

const props = defineProps<{ active: boolean }>()
const canvas = ref<HTMLCanvasElement | null>(null)
const ready = ref(false)
const paused = ref(false)
const reducedMotion = ref(false)
const pauseKey = 'wao-home-motion-paused'
const motionLabel = computed(() => reducedMotion.value ? '已减少动效' : paused.value ? '播放动效' : '暂停动效')
let context: CanvasRenderingContext2D | null = null
let resizeObserver: ResizeObserver | undefined
let intersectionObserver: IntersectionObserver | undefined
let motionQuery: MediaQueryList | undefined
let frame = 0
let width = 0
let height = 0
let visible = true
let activated = true
let lastTime = 0
let phase = 0
let particles: Array<{ latitude: number; longitude: number; sin: number; cos: number }> = []

function stop() {
  cancelAnimationFrame(frame)
  frame = 0
  lastTime = 0
}

function draw() {
  if (!context || !width || !height) return
  context.clearRect(0, 0, width, height)
  const radius = Math.min(width * .37, Math.max(height * .76, 180))
  const centerX = width * (width < 600 ? .72 : .76)
  const centerY = height * .44
  const rotation = phase * .12
  const cosRotation = Math.cos(rotation)
  const sinRotation = Math.sin(rotation)
  for (const point of particles) {
    const wave = 1 + .14 * Math.sin(point.latitude * 5 + phase * .7)
      + .09 * Math.cos(point.longitude * 3 + point.latitude * 2 - phase * .5)
    const x = point.sin * Math.cos(point.longitude) * wave
    const y = point.cos * wave
    const z = point.sin * Math.sin(point.longitude) * wave
    const rotatedX = x * cosRotation + z * sinRotation
    const rotatedZ = z * cosRotation - x * sinRotation
    const tiltedY = y * .94 - rotatedZ * .34
    const depth = y * .34 + rotatedZ * .94
    const perspective = 3 / (3 - depth * .36)
    const px = centerX + rotatedX * radius * perspective
    const py = centerY + tiltedY * radius * perspective
    const alpha = (.14 + (depth + 1.3) * .18) * Math.min(1, Math.max(0, px / width * 1.6))
    context.fillStyle = `rgba(155, 222, 255, ${alpha})`
    context.beginPath()
    context.arc(px, py, Math.max(.55, (depth + 1.6) * .68), 0, Math.PI * 2)
    context.fill()
  }
}

function tick(time: number) {
  frame = 0
  if (!lastTime || time - lastTime >= 1000 / 30) {
    if (lastTime) phase += Math.min(time - lastTime, 80) / 1000
    lastTime = time
    draw()
  }
  frame = requestAnimationFrame(tick)
}

function syncMotion() {
  stop()
  if (ready.value && props.active && activated && visible && !document.hidden
    && !paused.value && !reducedMotion.value) frame = requestAnimationFrame(tick)
}

function resize() {
  const element = canvas.value
  if (!element || !context) return
  const bounds = element.getBoundingClientRect()
  width = bounds.width
  height = bounds.height
  const ratio = Math.min(window.devicePixelRatio || 1, 1.5)
  element.width = Math.round(width * ratio)
  element.height = Math.round(height * ratio)
  context.setTransform(ratio, 0, 0, ratio, 0, 0)
  const rows = width < 600 ? 26 : 44
  const columns = width < 600 ? 48 : 80
  particles = []
  for (let row = 1; row < rows; row++) {
    const latitude = row / rows * Math.PI
    for (let column = 0; column < columns; column++) {
      particles.push({ latitude, longitude: column / columns * Math.PI * 2, sin: Math.sin(latitude), cos: Math.cos(latitude) })
    }
  }
  draw()
}

function updateMotionPreference() {
  reducedMotion.value = motionQuery?.matches ?? false
  syncMotion()
}

function toggleMotion() {
  paused.value = !paused.value
  try { localStorage.setItem(pauseKey, paused.value ? '1' : '0') }
  catch { /* 存储不可用仍允许本次暂停，不影响业务入口。 */ }
  syncMotion()
}

watch(() => props.active, syncMotion)
onActivated(() => { activated = true; syncMotion() })
onDeactivated(() => { activated = false; stop() })
onMounted(() => {
  try { paused.value = localStorage.getItem(pauseKey) === '1' }
  catch { /* 无法读取本机偏好时遵循系统设置。 */ }
  motionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  reducedMotion.value = motionQuery?.matches ?? false
  motionQuery?.addEventListener('change', updateMotionPreference)
  if (typeof CanvasRenderingContext2D === 'undefined') return
  try { context = canvas.value?.getContext('2d') ?? null }
  catch { return }
  if (!context) return
  ready.value = true
  resize()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(canvas.value!)
  } else window.addEventListener('resize', resize)
  if (typeof IntersectionObserver !== 'undefined') {
    intersectionObserver = new IntersectionObserver(([entry]) => {
      visible = entry?.isIntersecting ?? false
      syncMotion()
    })
    intersectionObserver.observe(canvas.value!)
  }
  document.addEventListener('visibilitychange', syncMotion)
  syncMotion()
})
onBeforeUnmount(() => {
  stop()
  resizeObserver?.disconnect()
  intersectionObserver?.disconnect()
  motionQuery?.removeEventListener('change', updateMotionPreference)
  document.removeEventListener('visibilitychange', syncMotion)
  window.removeEventListener('resize', resize)
})
</script>

<template>
  <div class="particle-backdrop" aria-hidden="true"><canvas ref="canvas"></canvas></div>
  <button v-if="active && ready" class="motion-control" type="button" :disabled="reducedMotion"
    :aria-pressed="paused || reducedMotion" :title="reducedMotion ? '已遵循系统的减少动态效果设置' : motionLabel" @click="toggleMotion">
    <i :class="paused || reducedMotion ? 'ri-play-line' : 'ri-pause-line'" aria-hidden="true"></i>{{ motionLabel }}
  </button>
</template>

<style scoped>
.particle-backdrop { position: absolute; inset: 0; overflow: hidden; pointer-events: none; }
canvas { display: block; width: 100%; height: 100%; opacity: .85; }
.motion-control { position: absolute; z-index: 2; right: 18px; bottom: 12px; display: inline-flex; align-items: center; gap: 5px; padding: 5px 10px; border: 1px solid #ffffff38; border-radius: 20px; background: #062b5980; color: #e2f2ff; font: inherit; font-size: 11px; cursor: pointer; }
.motion-control:hover { background: #062b59c0; }
.motion-control:focus-visible { outline: 2px solid #fff; outline-offset: 3px; }
.motion-control:disabled { cursor: default; }
@media (max-width: 760px) { .motion-control { left: 16px; right: auto; } }
</style>
