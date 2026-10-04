<script setup lang="ts">
import { onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch, type CSSProperties } from 'vue'
import { companionEditing, companionQuiet, editingCompanionContent } from '@/composables/quietCompanions'
import { randomSurprise, type SurpriseStyle } from './companionSurprises'

type Particle = { id: number; style: SurpriseStyle; x: number; y: number; trail: boolean; marks: CSSProperties[] }
const particles = ref<Particle[]>([])
const celebration = ref<SurpriseStyle>()
const colors = ['#00b5f7', '#ffca0d', '#f42cdb', '#b3ec22']
const copy = {
  bubbles: ['全员放空成功', '这十五秒，归你了。'],
  stars: ['星星到齐了', '留一点空白，也会有光。'],
  chorus: ['快乐正在冒泡', '今天，也可以轻一点。'],
}
const celebrationMarks = Array.from({ length: 36 }, (_, i) => ({
  '--color': colors[i % 4], '--x': `${(i * 137.5) % 100}%`, '--delay': `${(i % 8) * 0.045}s`,
  '--size': `${18 + (i % 6) * 11}px`, '--turn': `${i * 27}deg`,
  '--dx': `${Math.cos(i * Math.PI / 18) * 55}vw`, '--dy': `${Math.sin(i * Math.PI / 18) * 60}vh`,
} as CSSProperties))
let active = true
let reduced = false
let media: MediaQueryList | undefined
let nextId = 0
let previous: SurpriseStyle | undefined
let lastSweep = 0
let pointer: { x: number; y: number } | undefined
let timer: ReturnType<typeof setTimeout> | undefined
const timers = new Map<number, ReturnType<typeof setTimeout>>()
const allowed = () => active && !document.hidden && !companionQuiet.value && !companionEditing.value
  && !reduced && !editingCompanionContent()
function clear() {
  if (timer) clearTimeout(timer)
  timers.forEach(clearTimeout)
  timers.clear()
  particles.value = []
  celebration.value = undefined
  pointer = undefined
}
function decorativeTarget(event: MouseEvent) {
  return event.target instanceof Element && !event.target.closest('a,button,input,textarea,select,[contenteditable],[role="dialog"],[role="menu"]')
    && !window.getSelection()?.toString()
}
function sprinkle(x: number, y: number, trail = false) {
  if (!allowed() || particles.value.length >= 8) return
  previous = randomSurprise(previous)
  const count = trail ? 3 : previous === 'chorus' ? 12 : 9
  const id = ++nextId
  const marks = Array.from({ length: count }, (_, i) => {
    const angle = i * Math.PI * 2 / count
    const distance = trail ? 10 : 22 + (i % 3) * 10
    return {
      '--color': colors[i % 4], '--dx': `${Math.cos(angle) * distance}px`,
      '--dy': `${Math.sin(angle) * distance}px`, '--delay': `${i * 0.018}s`,
      '--turn': `${i * 37}deg`, '--size': `${trail ? 4 : 5 + i % 4 * 3}px`,
    } as CSSProperties
  })
  particles.value = [...particles.value, { id, style: previous, x, y, trail, marks }]
  timers.set(id, setTimeout(() => {
    particles.value = particles.value.filter(p => p.id !== id)
    timers.delete(id)
  }, 1200))
}
function tap(event: MouseEvent) {
  if (event.button === 0 && decorativeTarget(event)) sprinkle(event.clientX, event.clientY)
}
function sweep(event: PointerEvent) {
  if (event.pointerType !== 'mouse' || event.buttons || !allowed() || !decorativeTarget(event)) { pointer = undefined; return }
  const now = performance.now(), movement = pointer ? Math.hypot(event.clientX - pointer.x, event.clientY - pointer.y) : 0
  pointer = { x: event.clientX, y: event.clientY }
  if (movement < 6 || now - lastSweep < 120) return
  lastSweep = now
  sprinkle(event.clientX, event.clientY, true)
}
function celebrate(style: SurpriseStyle) {
  if (!allowed()) return
  clear()
  celebration.value = style
  timer = setTimeout(clear, 3200)
}
function key(event: KeyboardEvent) { if (event.key === 'Escape') clear() }
function visibility() { if (document.hidden) clear() }
function motion() { reduced = media?.matches ?? false; clear() }
watch([companionQuiet, companionEditing], clear)
onMounted(() => {
  media = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : undefined
  reduced = media?.matches ?? true
  media?.addEventListener('change', motion)
  document.addEventListener('keydown', key)
  document.addEventListener('visibilitychange', visibility)
  window.addEventListener('resize', clear)
})
onActivated(() => { active = true })
onDeactivated(() => { active = false; clear() })
onBeforeUnmount(() => {
  active = false
  clear()
  media?.removeEventListener('change', motion)
  document.removeEventListener('keydown', key)
  document.removeEventListener('visibilitychange', visibility)
  window.removeEventListener('resize', clear)
})
defineExpose({ tap, sweep, celebrate, clear })
</script>

<template>
  <Teleport to="body">
    <div v-if="particles.length || celebration" class="home-surprises">
      <div v-for="particle in particles" :key="particle.id" class="tap-effect" :class="[particle.style, { trail: particle.trail }]"
        :style="{ left: `${particle.x}px`, top: `${particle.y}px` }" aria-hidden="true">
        <span v-if="!particle.trail" class="tap-ring"></span>
        <i v-for="(mark, i) in particle.marks" :key="i" :style="mark">{{ particle.style === 'stars' ? '✦' : '' }}</i>
      </div>
      <section v-if="celebration" class="surprise-celebration" :class="celebration" aria-label="首页彩蛋">
        <div class="celebration-art" aria-hidden="true">
          <div v-if="celebration === 'stars'" class="aurora"></div>
          <div v-if="celebration === 'chorus'" class="rainbow-ring"></div>
          <i v-for="(mark, i) in celebrationMarks" :key="i" :style="mark">{{ celebration === 'stars' ? '✦' : '' }}</i>
          <div v-if="celebration === 'stars'" class="constellation">
            <svg viewBox="0 0 100 100" preserveAspectRatio="none"><polyline pathLength="1" points="18,29 38,18 68,25 82,55" /></svg>
            <span v-for="(point, i) in [[18,29],[38,18],[68,25],[82,55]]" :key="i" :style="{ '--color': colors[i], left: `${point[0]}%`, top: `${point[1]}%` }">✦</span>
          </div>
        </div>
        <div class="celebration-copy" role="status" aria-live="polite"><span aria-hidden="true">✦</span><h2>{{ copy[celebration][0] }}</h2><p>{{ copy[celebration][1] }}</p></div>
        <button type="button" class="dismiss-surprise" aria-label="收起彩蛋" @click="clear">×</button>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.home-surprises { position: fixed; inset: 0; z-index: 2200; overflow: hidden; pointer-events: none; }
.tap-effect { position: absolute; width: 0; height: 0; }
.tap-effect i { position: absolute; width: var(--size); height: var(--size); margin: -5px; background: color-mix(in srgb, var(--color), transparent 82%); border: 1px solid var(--color); border-radius: 50%; animation: scatter 0.85s ease-out var(--delay) both; }
.tap-effect.stars i { background: none; border: 0; color: var(--color); font-style: normal; font-size: 15px; }
.tap-effect.chorus i { border-radius: 2px; background: var(--color); opacity: 0.6; }
.tap-ring { position: absolute; width: 18px; height: 18px; margin: -9px; border: 1px solid var(--wao-primary-line); border-radius: 50%; animation: ripple 0.75s ease-out both; }
.chorus .tap-ring { border-color: #f42cdb55; }
.stars .tap-ring { border-color: #00b5f755; }
.trail { opacity: 0.5; }
.surprise-celebration { position: absolute; inset: 0; display: grid; place-items: center; background: color-mix(in srgb, var(--wao-bg-card), transparent 8%); animation: reveal 0.3s both; }
.celebration-art { position: absolute; inset: 0; overflow: hidden; }
.celebration-art > i { position: absolute; left: var(--x); top: 105%; width: var(--size); height: var(--size); border-radius: 50%; border: 1px solid color-mix(in srgb, var(--color), transparent 25%); background: color-mix(in srgb, var(--color), transparent 92%); animation: bubble-festival 2.8s ease-out var(--delay) both; }
.celebration-copy { position: relative; text-align: center; padding: 28px; animation: copy-in 0.55s ease-out both; }
.celebration-copy > span { display: block; font-size: 42px; color: var(--wao-primary-text); }
.celebration-copy h2 { color: var(--wao-text); font-size: clamp(24px, 4vw, 34px); font-weight: 500; margin: 10px 0 8px; }
.celebration-copy p { color: var(--wao-text-3); font-size: 14px; margin: 0; }
.dismiss-surprise { position: absolute; top: 20px; right: 20px; width: 36px; height: 36px; border: 1px solid var(--wao-line); border-radius: 50%; background: var(--wao-bg-card); color: var(--wao-text-3); font: inherit; font-size: 22px; pointer-events: auto; cursor: pointer; }
.dismiss-surprise:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; }
.aurora { position: absolute; inset: -25%; background: conic-gradient(from 20deg, #00b5f718, #ffca0d10, #f42cdb18, #b3ec2218, #00b5f718); filter: blur(32px); animation: aurora-turn 3.2s ease-out both; }
.stars .celebration-art > i { top: calc(var(--x) * 0.85); background: none; border: 0; width: auto; height: auto; color: var(--color); font-style: normal; font-size: 12px; animation: twinkle 2.6s ease-out var(--delay) both; }
.constellation { position: absolute; inset: 0; }
.constellation svg { width: 100%; height: 100%; fill: none; stroke: var(--wao-primary-text); stroke-width: 1; opacity: 0.35; }
.constellation polyline { vector-effect: non-scaling-stroke; stroke-dasharray: 1; animation: line-in 0.9s ease-out both; }
.constellation span { position: absolute; margin: -20px -15px; color: var(--color); font-size: 28px; text-shadow: 0 0 18px var(--color); animation: twinkle 2.5s ease-out both; }
.rainbow-ring { position: absolute; left: 50%; top: 50%; width: 180px; height: 180px; margin: -90px; border: 12px solid transparent; border-radius: 50%; background: linear-gradient(var(--wao-bg-card), var(--wao-bg-card)) padding-box, conic-gradient(#00b5f7, #ffca0d, #f42cdb, #b3ec22, #00b5f7) border-box; animation: rainbow-expand 2.6s ease-out both; }
.chorus .celebration-art > i { left: 50%; top: 50%; width: 8px; height: 12px; border: 0; border-radius: 2px; background: var(--color); animation: confetti 2.5s ease-out var(--delay) both; }
@keyframes scatter { from { opacity: 0.75; transform: scale(0.4); } to { opacity: 0; transform: translate(var(--dx), var(--dy)) rotate(var(--turn)) scale(0.6); } }
@keyframes ripple { from { opacity: 0.55; transform: scale(0.4); } to { opacity: 0; transform: scale(5); } }
@keyframes reveal { from { opacity: 0; } to { opacity: 1; } }
@keyframes copy-in { from { opacity: 0; transform: translateY(10px) scale(0.96); } to { opacity: 1; transform: none; } }
@keyframes bubble-festival { 0% { opacity: 0; transform: scale(0.4); } 15% { opacity: 0.7; } 80% { opacity: 0.6; } 100% { opacity: 0; transform: translateY(-120vh) scale(1); } }
@keyframes twinkle { 0% { opacity: 0; scale: 0.3; } 30%,75% { opacity: 0.8; scale: 1.2; } 100% { opacity: 0; scale: 1; } }
@keyframes aurora-turn { from { opacity: 0; transform: scale(0.6) rotate(-25deg); } to { opacity: 1; transform: scale(1.1) rotate(15deg); } }
@keyframes line-in { from { stroke-dashoffset: 1; } to { stroke-dashoffset: 0; } }
@keyframes rainbow-expand { from { opacity: 0.4; transform: scale(0.1); } to { opacity: 0; transform: scale(12); } }
@keyframes confetti { 0% { opacity: 0; transform: scale(0.2); } 15% { opacity: 0.8; } 100% { opacity: 0; transform: translate(var(--dx), var(--dy)) rotate(var(--turn)); } }
@media (prefers-reduced-motion: reduce) { .home-surprises { display: none; } }
</style>
