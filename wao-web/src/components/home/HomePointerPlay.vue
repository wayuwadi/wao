<script setup lang="ts">
import {
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
  type CSSProperties,
} from 'vue'
import {
  companionEditing,
  companionQuiet,
  editingCompanionContent,
} from '@/composables/quietCompanions'
import {
  createCompanionRandomEvents,
  randomEventTarget,
  type PointerEffect,
} from './companionRandomEvents'
import type { CompanionActivity } from './companionActivities'
import type { CompanionAnchor, WorldStory } from './companionWorld'

const props = defineProps<{ anchors: () => CompanionAnchor[]; available: () => boolean }>()
const emit = defineEmits<{
  begin: []
  activity: [kind: CompanionActivity]
  story: [event: WorldStory]
}>()
const pick = createCompanionRandomEvents()
const colors = ['#00b5f7', '#ffca0d', '#f42cdb', '#b3ec22']
const companionKinds: PointerEffect[] = ['approach', 'hop', 'audience', 'guide', 'candy', 'curious']
const bubbleKinds: PointerEffect[] = ['breath', 'approach', 'droplets']
const ringKinds: PointerEffect[] = ['ripple', 'echo', 'hop']
const windKinds: PointerEffect[] = ['breeze', 'wind']
const starKinds: PointerEffect[] = ['constellation', 'audience', 'guide', 'curious', 'secret-stars']
type Effect = {
  id: number
  kind: PointerEffect
  x: number
  y: number
  held: boolean
  releasing: boolean
  paths: string[]
  marks: CSSProperties[]
}
const effect = ref<Effect>()
let active = true,
  reduced = true,
  media: MediaQueryList | undefined,
  id = 0,
  lastTrigger = -Infinity
let press: { id: number; x: number; y: number; fired: boolean } | undefined
let pending: ReturnType<typeof setTimeout> | undefined
let finish: ReturnType<typeof setTimeout> | undefined
let remove: ReturnType<typeof setTimeout> | undefined
let suppress: { x: number; y: number; until: number } | undefined
const allowed = () =>
  active &&
  !document.hidden &&
  !reduced &&
  !companionQuiet.value &&
  !companionEditing.value &&
  !editingCompanionContent() &&
  props.available()
function clear() {
  if (pending) clearTimeout(pending)
  if (finish) clearTimeout(finish)
  if (remove) clearTimeout(remove)
  pending = finish = remove = undefined
  press = undefined
  effect.value = undefined
  suppress = undefined
}
function release() {
  if (pending) clearTimeout(pending)
  pending = undefined
  if (press?.fired) suppress = { x: press.x, y: press.y, until: performance.now() + 600 }
  press = undefined
  fade()
}
function fade() {
  if (effect.value && !effect.value.releasing) {
    effect.value.releasing = true
    if (finish) clearTimeout(finish)
    finish = undefined
    remove = setTimeout(() => {
      effect.value = undefined
      remove = undefined
    }, 800)
  }
}
function fire(x: number, y: number, held: boolean) {
  if (!allowed() || window.getSelection()?.toString() || performance.now() - lastTrigger < 350)
    return false
  const gesture = press
  clear()
  // 先清旧活动，再创建新特效，避免角色收口回调把本次效果也一起移除。
  emit('begin')
  lastTrigger = performance.now()
  if (gesture) press = { ...gesture, fired: true }
  const event = pick()
  if (event.type === 'activity') {
    emit('activity', event.kind)
    if (gesture) press = { ...gesture, fired: true }
    return true
  }
  const bounds = {
    x: Math.max(85, Math.min(innerWidth - 85, x)),
    y: Math.max(85, Math.min(innerHeight - 85, y)),
  }
  const anchors = props.anchors(),
    kind = event.kind
  const source =
    kind === 'approach' ? 0 : kind === 'hop' ? 3 : kind === 'guide' ? 1 : kind === 'candy' ? 2 : -1
  const paths = companionKinds.includes(kind)
    ? anchors.flatMap((p, i) => {
        if (!p.available || (source >= 0 && i !== source)) return []
        return [`M${p.x},${p.y - 10} Q${p.x},${bounds.y + 65} ${bounds.x},${bounds.y + 22}`]
      })
    : []
  const count =
    kind === 'candy'
      ? 16
      : kind === 'constellation'
        ? 12
        : ringKinds.includes(kind) || kind === 'guide'
          ? 3
          : kind === 'secret-stars' ||
              kind === 'clover' ||
              kind === 'audience' ||
              kind === 'curious'
            ? 4
            : 10
  const marks = Array.from({ length: count }, (_, i) => {
    const angle = kind === 'constellation' ? i * 2.4 : (i * Math.PI * 2) / count
    const radius = kind === 'constellation' ? 14 + Math.sqrt(i) * 14 : 28 + (i % 3) * 12
    return {
      '--color': colors[source >= 0 && kind !== 'candy' ? source : i % 4],
      '--x': `${Math.cos(angle) * radius}px`,
      '--y': `${Math.sin(angle) * radius * 0.7}px`,
      '--i': i,
      '--turn': `${angle}rad`,
      '--size': `${4 + (i % 4) * 2}px`,
      '--delay': `${ringKinds.includes(kind) ? i * -0.9 : i * -0.18}s`,
    } as CSSProperties
  })
  effect.value = { id: ++id, kind, x: bounds.x, y: bounds.y, held, releasing: false, paths, marks }
  emit('story', {
    kind: kind === 'hop' ? 'catch' : windKinds.includes(kind) ? 'wind' : 'attention',
    source: windKinds.includes(kind) ? 0 : source,
    direction: Math.sign(x - (anchors[Math.max(0, source)]?.x ?? innerWidth / 2)) || 1,
    tier: 'common',
  })
  // 自动收势只结束绘制；仍记住尚未松开的按压，晚松手也不能额外点击。
  finish = setTimeout(fade, held ? 7000 : 1400)
  return true
}
function down(event: PointerEvent) {
  if (
    event.button !== 0 ||
    event.pointerType === 'touch' ||
    !allowed() ||
    !randomEventTarget(event)
  )
    return
  release()
  press = { id: event.pointerId, x: event.clientX, y: event.clientY, fired: false }
  pending = setTimeout(() => {
    pending = undefined
    if (press) fire(press.x, press.y, true)
  }, 500)
}
function move(event: PointerEvent) {
  if (
    press &&
    press.id === event.pointerId &&
    Math.hypot(event.clientX - press.x, event.clientY - press.y) > 8
  )
    release()
}
function up(event: PointerEvent) {
  if (press?.id === event.pointerId) release()
}
function consumeTap(event: MouseEvent) {
  if (!suppress || performance.now() > suppress.until) {
    suppress = undefined
    return false
  }
  if (Math.hypot(event.clientX - suppress.x, event.clientY - suppress.y) > 12 || event.detail === 0)
    return false
  suppress = undefined
  return true
}
function trigger(event: MouseEvent | KeyboardEvent, partner?: number) {
  if (
    event instanceof MouseEvent &&
    (partner === undefined ? !randomEventTarget(event) : !!window.getSelection()?.toString())
  )
    return
  event.preventDefault()
  if (!allowed()) return
  release()
  const anchor = props.anchors()[partner ?? -1]
  const r = event.target instanceof Element ? event.target.getBoundingClientRect() : undefined
  const x =
    event instanceof MouseEvent
      ? event.clientX
      : (anchor?.x ?? (r ? r.left + r.width / 2 : innerWidth / 2))
  const y =
    event instanceof MouseEvent
      ? event.clientY
      : (anchor?.y ?? (r ? r.top + r.height / 2 : innerHeight / 2))
  fire(x, y, false)
}
function key(event: KeyboardEvent) {
  if (event.key === 'Escape') clear()
}
function visibility() {
  if (document.hidden) clear()
}
function motion() {
  reduced = media?.matches ?? true
  clear()
}
watch([companionQuiet, companionEditing], clear)
onMounted(() => {
  media =
    typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : undefined
  motion()
  media?.addEventListener('change', motion)
  document.addEventListener('keydown', key)
  document.addEventListener('visibilitychange', visibility)
  window.addEventListener('pointerup', up)
  window.addEventListener('pointercancel', clear)
  window.addEventListener('blur', clear)
  window.addEventListener('resize', clear)
})
onActivated(() => (active = true))
onDeactivated(() => {
  active = false
  clear()
})
onBeforeUnmount(() => {
  active = false
  clear()
  media?.removeEventListener('change', motion)
  document.removeEventListener('keydown', key)
  document.removeEventListener('visibilitychange', visibility)
  window.removeEventListener('pointerup', up)
  window.removeEventListener('pointercancel', clear)
  window.removeEventListener('blur', clear)
  window.removeEventListener('resize', clear)
})
defineExpose({ down, move, up, clear, release, trigger, consumeTap })
</script>

<template>
  <Teleport to="body">
    <div
      v-if="effect"
      :key="effect.id"
      class="home-pointer-play"
      :class="{ releasing: effect.releasing }"
      aria-hidden="true"
      :data-effect="effect.kind"
    >
      <svg v-if="effect.paths.length" class="companion-curiosity">
        <path v-for="(path, i) in effect.paths" :key="i" :d="path" :stroke="colors[i % 4]" />
      </svg>
      <div
        class="pointer-art"
        :class="effect.kind"
        :style="{ left: `${effect.x}px`, top: `${effect.y}px` }"
      >
        <span
          v-if="effect.kind === 'breath' || effect.kind === 'approach'"
          class="pointer-center"
        ></span>
        <svg
          v-if="effect.kind === 'constellation'"
          class="pointer-constellation"
          viewBox="-85 -85 170 170"
        >
          <polyline
            points="14,0 -20,13 3,-26 24,22 -42,-5 40,-18 -13,40 -24,-39 49,12 -50,22 25,-45 18,49"
          />
        </svg>
        <div v-if="effect.kind === 'portal'" class="pointer-portal"></div>
        <i
          v-for="(mark, i) in effect.marks"
          :key="i"
          :style="mark"
          :class="{
            orb: bubbleKinds.includes(effect.kind),
            ripple: ringKinds.includes(effect.kind),
            wind: windKinds.includes(effect.kind),
            sparkle: starKinds.includes(effect.kind),
            petal: effect.kind === 'clover',
            confetti: effect.kind === 'candy',
            comet: effect.kind === 'comet',
            fold: effect.kind === 'fan',
            satellite: effect.kind === 'portal',
          }"
          >{{ starKinds.includes(effect.kind) ? '✦' : '' }}</i
        >
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.home-pointer-play {
  position: fixed;
  inset: 0;
  z-index: 2180;
  overflow: hidden;
  pointer-events: none;
  animation: pointer-appear 0.22s ease-out both;
}
.home-pointer-play.releasing {
  animation: pointer-release 0.8s ease-out both;
}
.pointer-art {
  position: absolute;
  width: 0;
  height: 0;
}
.pointer-art i {
  position: absolute;
  display: block;
  color: var(--color);
  font-style: normal;
  width: var(--size);
  height: var(--size);
  margin: calc(var(--size) / -2);
  animation-delay: var(--delay);
}
.pointer-center {
  position: absolute;
  width: 28px;
  height: 28px;
  margin: -14px;
  border-radius: 50%;
  border: 1px solid #00b5f7;
  background: color-mix(in srgb, #00b5f7, transparent 93%);
  animation: pointer-breathe 2s ease-in-out infinite;
}
.orb {
  border-radius: 50%;
  border: 1px solid var(--color);
  background: color-mix(in srgb, var(--color), transparent 94%);
  box-shadow: inset 2px 2px 4px color-mix(in srgb, var(--wao-bg-card), transparent 20%);
  transform: translate(var(--x), var(--y));
  animation: pointer-orbit 3.4s ease-in-out infinite;
}
.droplets .orb {
  animation: pointer-drop 2.6s ease-in-out infinite;
  width: calc(var(--size) + 2px);
  height: calc(var(--size) + 2px);
}
i.ripple {
  width: 22px !important;
  height: 22px !important;
  margin: -11px !important;
  border: 1px solid var(--color);
  border-radius: 50%;
  animation: pointer-ripple 2.8s ease-out infinite;
}
.echo i.ripple {
  border-style: dashed;
  animation-duration: 3.6s;
}
i.wind {
  width: 15px !important;
  height: 2px !important;
  border-radius: 2px;
  background: var(--color);
  animation: pointer-wind 2.8s linear infinite;
  opacity: 0.6;
}
.breeze i.wind {
  height: 3px !important;
  width: 3px !important;
}
.sparkle {
  width: auto !important;
  height: auto !important;
  font-size: 12px;
  transform: translate(var(--x), var(--y));
  animation: pointer-twinkle 2.6s ease-in-out infinite;
}
.guide .sparkle {
  color: #ffca0d;
}
.curious .sparkle,
.audience .sparkle {
  font-size: 10px;
}
.petal {
  width: 36px !important;
  height: 23px !important;
  margin: -11px -18px !important;
  border-radius: 65% 45% 65% 45%;
  border: 1px solid var(--color);
  background: color-mix(in srgb, var(--color), transparent 82%);
  transform: rotate(var(--turn)) translateX(17px);
  animation: pointer-petal 2.4s ease-in-out infinite;
}
.confetti {
  border-radius: 2px;
  background: var(--color);
  animation: pointer-confetti 2.8s ease-in-out infinite;
}
i.comet {
  width: 5px !important;
  height: 5px !important;
  border-radius: 50%;
  background: var(--color);
  box-shadow:
    -7px 4px 0 color-mix(in srgb, var(--color), transparent 45%),
    -14px 8px 0 color-mix(in srgb, var(--color), transparent 75%);
  animation: pointer-comet 2.4s linear infinite;
}
.fold {
  width: 50px !important;
  height: 1px !important;
  margin: 0 !important;
  background: var(--color);
  transform-origin: 0 0;
  transform: rotate(calc(-150deg + var(--i) * 12deg));
  animation: pointer-fan 2.8s ease-in-out infinite;
}
.pointer-portal {
  position: absolute;
  width: 76px;
  height: 40px;
  margin: -20px -38px;
  border: 2px solid transparent;
  border-radius: 50%;
  background:
    linear-gradient(var(--wao-bg-card), var(--wao-bg-card)) padding-box,
    conic-gradient(#00b5f7, #ffca0d, #f42cdb, #b3ec22, #00b5f7) border-box;
  animation: pointer-portal 3.6s ease-in-out infinite;
  opacity: 0.7;
}
.satellite {
  border-radius: 50%;
  background: var(--color);
  transform: translate(var(--x), var(--y));
  animation: pointer-orbit 3.4s ease-in-out infinite;
}
.pointer-constellation {
  position: absolute;
  width: 170px;
  height: 170px;
  margin: -85px;
}
.pointer-constellation polyline {
  fill: none;
  stroke: #8faddf;
  stroke-width: 0.7;
  opacity: 0.35;
  stroke-dasharray: 3 5;
}
.companion-curiosity {
  position: absolute;
  width: 100%;
  height: 100%;
  overflow: visible;
}
.companion-curiosity path {
  fill: none;
  stroke-width: 1;
  stroke-dasharray: 2 8;
  opacity: 0.25;
  animation: pointer-guide 3s linear infinite;
}
@keyframes pointer-appear {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
@keyframes pointer-release {
  from {
    opacity: 1;
  }
  to {
    opacity: 0;
    transform: translateY(-20px);
  }
}
@keyframes pointer-breathe {
  0%,
  100% {
    transform: scale(0.85);
    opacity: 0.5;
  }
  50% {
    transform: scale(1.15);
    opacity: 0.85;
  }
}
@keyframes pointer-orbit {
  0%,
  100% {
    transform: translate(var(--x), var(--y)) scale(0.7);
    opacity: 0.45;
  }
  50% {
    transform: translate(calc(var(--x) * 0.8 + 6px), calc(var(--y) * 1.2 - 4px)) scale(1.2);
    opacity: 0.8;
  }
}
@keyframes pointer-drop {
  from {
    transform: translate(var(--x), -40px);
    opacity: 0;
  }
  25% {
    opacity: 0.7;
  }
  to {
    transform: translate(var(--x), 38px);
    opacity: 0;
  }
}
@keyframes pointer-ripple {
  from {
    transform: scale(0.4);
    opacity: 0.5;
  }
  to {
    transform: scale(6.5);
    opacity: 0;
  }
}
@keyframes pointer-wind {
  from {
    transform: translate(-65px, var(--y));
    opacity: 0;
  }
  30%,
  70% {
    opacity: 0.5;
  }
  to {
    transform: translate(65px, calc(var(--y) - 12px));
    opacity: 0;
  }
}
@keyframes pointer-twinkle {
  0%,
  100% {
    transform: translate(var(--x), var(--y)) scale(0.65);
    opacity: 0.3;
  }
  50% {
    transform: translate(var(--x), var(--y)) scale(1.15);
    opacity: 0.85;
  }
}
@keyframes pointer-petal {
  0%,
  100% {
    opacity: 0.5;
  }
  50% {
    opacity: 0.85;
    transform: rotate(calc(var(--turn) + 0.12rad)) translateX(19px);
  }
}
@keyframes pointer-confetti {
  from {
    transform: translate(0, 0) scale(0.4) rotate(0);
    opacity: 0;
  }
  25% {
    opacity: 0.75;
  }
  to {
    transform: translate(var(--x), calc(var(--y) + 30px)) scale(0.65)
      rotate(calc(var(--turn) + 180deg));
    opacity: 0;
  }
}
@keyframes pointer-comet {
  from {
    transform: translate(calc(var(--x) - 40px), calc(var(--y) + 24px));
    opacity: 0;
  }
  40% {
    opacity: 0.75;
  }
  to {
    transform: translate(calc(var(--x) + 40px), calc(var(--y) - 24px));
    opacity: 0;
  }
}
@keyframes pointer-fan {
  0%,
  100% {
    opacity: 0.3;
  }
  50% {
    opacity: 0.7;
    transform: rotate(calc(-155deg + var(--i) * 13deg));
  }
}
@keyframes pointer-portal {
  0%,
  100% {
    transform: rotate(-12deg) scale(0.9);
  }
  50% {
    transform: rotate(12deg) scale(1.1);
  }
}
@keyframes pointer-guide {
  to {
    stroke-dashoffset: -60;
  }
}
@media (prefers-reduced-motion: reduce) {
  .home-pointer-play {
    display: none;
  }
}
</style>
