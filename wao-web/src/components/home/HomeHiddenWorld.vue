<script setup lang="ts">
import { onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch, type CSSProperties } from 'vue'
import { companionEditing, companionQuiet, editingCompanionContent } from '@/composables/quietCompanions'
import { createWorldChoices, createWorldCombos, recognizeWorldGesture, type CompanionAnchor, type WorldPoint, type WorldStory, type WorldChoices } from './companionWorld'

const props = defineProps<{ anchors: () => CompanionAnchor[]; choices?: WorldChoices }>()
const emit = defineEmits<{ story: [event: WorldStory] }>()
const colors = ['#00b5f7', '#ffca0d', '#f42cdb', '#b3ec22']
const choices = props.choices ?? createWorldChoices(), combos = createWorldCombos()
const bubble = ref<{ id: number; source: number; target: number; x: number; y: number; dx: number; dy: number; tier: WorldStory['tier'] }>()
const marks = ref<{ id: number; text: string; style: CSSProperties }[]>([])
let active = true, reduced = false, id = 0, media: MediaQueryList | undefined
let gesture: { pointer: number; points: WorldPoint[]; element: HTMLElement } | undefined
let suppressTapUntil = 0, lastTap = -Infinity, recentTap = -Infinity, rewardAt = -Infinity, rewardUntil = 0
let bubbleTimer: ReturnType<typeof setTimeout> | undefined
const timers = new Set<ReturnType<typeof setTimeout>>()
const allowed = () => active && !document.hidden && !companionQuiet.value && !companionEditing.value && !reduced && !editingCompanionContent()
const blank = (event: MouseEvent) => event.target instanceof Element
  && !event.target.closest('a,button,input,textarea,select,[contenteditable],[role="dialog"],[role="menu"],.home-day') && !window.getSelection()?.toString()
function later(fn: () => void, ms: number) {
  const timer = setTimeout(() => { timers.delete(timer); if (allowed()) fn() }, ms)
  timers.add(timer); return timer
}
function clearGesture() {
  if (gesture?.element.hasPointerCapture?.(gesture.pointer)) gesture.element.releasePointerCapture(gesture.pointer)
  gesture = undefined
}
function stopBubble() {
  if (bubbleTimer) { clearTimeout(bubbleTimer); timers.delete(bubbleTimer) }
  bubbleTimer = undefined; bubble.value = undefined
}
function clear() {
  clearGesture()
  timers.forEach(clearTimeout); timers.clear()
  stopBubble(); marks.value = []
  combos.reset(); rewardAt = -Infinity; rewardUntil = 0; suppressTapUntil = 0; lastTap = recentTap = -Infinity
}
function mark(x: number, y: number, color: number, text: string, dx: number, dy: number, duration = 1600) {
  if (!allowed() || marks.value.length >= 32) return
  const key = ++id
  marks.value.push({ id: key, text, style: { left: `${x}px`, top: `${y}px`, '--world-color': colors[color % 4], '--dx': `${dx}px`, '--dy': `${dy}px`, '--duration': `${duration}ms` } as CSSProperties })
  later(() => marks.value = marks.value.filter(m => m.id !== key), duration)
}
function scatter(x: number, y: number, source: number) {
  for (let i = 0; i < 8; i++) { const a = i * Math.PI / 4; mark(x, y, source, source === 0 ? '·' : '✦', Math.cos(a) * 45, Math.sin(a) * 45, 1200) }
}
function orbit(center: WorldPoint, radius: number, source: number) {
  for (let i = 0; i < 12; i++) { const a = i * Math.PI / 6; mark(center.x + Math.cos(a) * radius, center.y + Math.sin(a) * radius, (i + source) % 4, '✦', Math.cos(a + 1) * 16, Math.sin(a + 1) * 16, 2800) }
}
function story(event: WorldStory) {
  if (!allowed()) return
  if (event.kind === 'reverse') {
    props.anchors().forEach((p, i) => mark(p.x, p.y - 30, i, '✦', 0, -170, 2400 + (3 - i) * 180))
  }
  if (event.kind === 'catch') {
    const anchor = props.anchors()[event.source]
    if (anchor) scatter(anchor.x, anchor.y, event.source)
  }
}
function finishBubble(key: number) {
  const current = bubble.value
  if (!allowed() || current?.id !== key) return
  bubble.value = undefined; bubbleTimer = undefined
  const anchors = props.anchors()
  const target = anchors[current.target]?.available ? current.target : anchors.findIndex(p => p.available)
  if (target < 0) return
  emit('story', { kind: 'catch', source: target, target: current.source, tier: current.tier })
}
function popBubble(event: MouseEvent) {
  if (!allowed() || !bubble.value) return
  const current = bubble.value
  stopBubble()
  const now = performance.now(), bounds = (event.currentTarget as HTMLElement | null)?.getBoundingClientRect()
  const x = event.detail === 0 && bounds ? bounds.left + bounds.width / 2 : event.clientX
  const y = event.detail === 0 && bounds ? bounds.top + bounds.height / 2 : event.clientY
  scatter(x, y, current.source)
  // 快速连点时第二下可能落在新泡泡上，仍计入同一个节拍。
  if (combos.beat(now)) emit('story', { kind: 'wave', source: choices.partner(), tier: 'common' })
  else if (event.detail === 2 || now - recentTap < 340) {
    orbit({ x, y }, 55, current.source)
    emit('story', { kind: 'orbit', source: current.source, tier: 'common' })
  } else emit('story', { kind: 'miss', source: current.target, tier: current.tier })
}
function pass(now: number) {
  if (!rewardUntil || now < rewardAt || now > rewardUntil) return false
  rewardUntil = 0
  const anchors = props.anchors()
  if (anchors.length !== 4 || anchors.some(p => !p.available)) return false
  const tier = choices.tier(now)
  emit('story', { kind: 'pass', source: 2, tier })
  for (let i = 0; i < 3; i++) {
    if (tier === 'rare' && i === 2) break
    later(() => {
      const fresh = props.anchors(), a = fresh[i], b = fresh[i + 1]
      if (!a?.available || !b?.available) return
      mark(a.x, a.y - 25, i, '✦', b.x - a.x, b.y - a.y, 1250)
      if (tier === 'rare' && i === 1) scatter((a.x + b.x) / 2, a.y - 20, i)
    }, i * 1350)
  }
  return true
}
function tap(event: MouseEvent) {
  if (event.defaultPrevented || event.button !== 0 || !allowed() || !blank(event)) return
  const now = performance.now()
  if (now < suppressTapUntil || (now >= rewardAt - 3300 && now < rewardAt)) return
  if (pass(now)) return
  if (combos.beat(now)) {
    stopBubble()
    emit('story', { kind: 'wave', source: choices.partner(), tier: 'common' }); return
  }
  // 触屏双点获得同一星系回应，避免画圈手势占用正常页面滚动。
  if (event.detail === 2 || now - recentTap < 340) {
    stopBubble()
    recentTap = -Infinity
    const source = choices.partner()
    orbit({ x: event.clientX, y: event.clientY }, 55, source)
    emit('story', { kind: 'orbit', source, tier: 'common' }); return
  }
  recentTap = now
  if (bubble.value || now - lastTap < 750) return
  const anchors = props.anchors(), available = anchors.flatMap((p, i) => p.available ? [i] : [])
  if (!available.length) return
  lastTap = now
  const selected = choices.partner(), source = available.includes(selected) ? selected : available[0]!
  const tier = choices.tier(now), neighbor = choices.partner(source)
  const target = tier !== 'common' && available.includes(neighbor) ? neighbor : source
  const anchor = anchors[target]!, key = ++id
  const x = Math.max(24, Math.min(innerWidth - 24, event.clientX)), y = Math.max(24, Math.min(innerHeight - 24, event.clientY))
  bubble.value = { id: key, source, target, x, y, dx: anchor.x - x, dy: anchor.y - y, tier }
  emit('story', { kind: 'watch', source, target, tier })
  bubbleTimer = later(() => finishBubble(key), source === 1 ? 2200 : 1800)
}
function down(event: PointerEvent) {
  if (!allowed() || !blank(event) || event.button !== 0 || event.pointerType === 'touch'
    || (event.target instanceof Element && event.target.closest('.home-rest-copy'))) return
  clearGesture()
  gesture = { pointer: event.pointerId, points: [{ x: event.clientX, y: event.clientY }], element: event.currentTarget as HTMLElement }
}
function move(event: PointerEvent) {
  if (!gesture || gesture.pointer !== event.pointerId || !allowed()) return
  const points = gesture.points, previous = points[points.length - 1]!
  if (Math.hypot(event.clientX - previous.x, event.clientY - previous.y) < 7) return
  if (points.length >= 128) points.splice(1, 1)
  points.push({ x: event.clientX, y: event.clientY })
  if (points.length > 3) {
    gesture.element.setPointerCapture?.(event.pointerId)
    mark(event.clientX, event.clientY, Math.floor(points.length / 8), '·', 0, 0, 700)
  }
}
function up(event: PointerEvent) {
  if (!gesture || gesture.pointer !== event.pointerId) return
  const points = gesture.points
  clearGesture()
  if (!allowed() || points.length < 4 || window.getSelection()?.toString()) return
  suppressTapUntil = performance.now() + 500
  const recognized = recognizeWorldGesture(points)
  if (!recognized) return
  // 轨迹先收起，为圈内星星留出限额；新手势也结束正在落下的泡泡。
  marks.value = []; stopBubble()
  const source = choices.partner(), tier = choices.tier(performance.now())
  if (recognized.kind === 'orbit') orbit(recognized.center, recognized.radius, source)
  else for (let i = 0; i < 6; i++) mark(recognized.center.x, recognized.center.y + i * 9, i % 4, '·', recognized.direction * 150, -15, 1400)
  emit('story', { kind: recognized.kind, source, direction: recognized.direction, tier })
}
function celebrated() { clear(); rewardAt = performance.now() + 3300; rewardUntil = rewardAt + 20000 }
function key(event: KeyboardEvent) { if (event.key === 'Escape') clear() }
function visibility() { if (document.hidden) clear() }
function motion() { reduced = media?.matches ?? false; clear() }
watch([companionQuiet, companionEditing], clear)
onMounted(() => {
  media = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : undefined; reduced = media?.matches ?? true
  media?.addEventListener('change', motion)
  document.addEventListener('keydown', key); document.addEventListener('visibilitychange', visibility); window.addEventListener('resize', clear)
})
onActivated(() => active = true)
onDeactivated(() => { active = false; clear() })
onBeforeUnmount(() => { active = false; clear(); media?.removeEventListener('change', motion); document.removeEventListener('keydown', key); document.removeEventListener('visibilitychange', visibility); window.removeEventListener('resize', clear) })
defineExpose({ tap, down, move, up, clear, celebrated, story })
</script>

<template>
  <Teleport to="body">
    <div v-if="bubble || marks.length" class="home-hidden-world">
      <button v-if="bubble" :key="bubble.id" class="world-bubble" type="button" aria-label="戳破泡泡"
        :style="{ left: `${bubble.x}px`, top: `${bubble.y}px`, '--dx': `${bubble.dx}px`, '--dy': `${bubble.dy}px`, '--world-color': colors[bubble.source], '--duration': bubble.source === 1 ? '2200ms' : '1800ms' }"
        @click.stop="popBubble" @pointerdown.stop @contextmenu.stop.prevent> </button>
      <span v-for="m in marks" :key="m.id" class="world-mark" :style="m.style" aria-hidden="true">{{ m.text }}</span>
    </div>
  </Teleport>
</template>

<style scoped>
.home-hidden-world { position:fixed; inset:0; z-index:2190; pointer-events:none; overflow:hidden; }
.world-bubble { position:absolute; width:38px; height:38px; margin:-19px; padding:0; border:1px solid var(--world-color); border-radius:50%; background:radial-gradient(circle at 30% 25%,var(--wao-bg-card),color-mix(in srgb,var(--world-color),transparent 85%)); box-shadow:inset -3px -4px 8px color-mix(in srgb,var(--world-color),transparent 85%); pointer-events:auto; cursor:pointer; animation:world-drop var(--duration) cubic-bezier(.25,.15,.4,1) both; }
.world-bubble:focus-visible { outline:2px solid var(--wao-primary); outline-offset:4px; }
.world-mark { position:absolute; color:var(--world-color); font-size:19px; animation:world-scatter var(--duration) ease-out both; }
@keyframes world-drop { from { transform:scale(.5); opacity:0; } 12% { opacity:.8; } to { transform:translate(var(--dx),var(--dy)) scale(.75); opacity:.75; } }
@keyframes world-scatter { from { opacity:0; transform:scale(.4); } 20% { opacity:.65; } to { transform:translate(var(--dx),var(--dy)) scale(.7); opacity:0; } }
@media(prefers-reduced-motion:reduce) { .home-hidden-world { display:none; } }
</style>
