<script setup lang="ts">
import {
  computed,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
} from 'vue'
import { useRouter } from 'vue-router'
import { companionBrand } from '@/config/companionBrand'
import { notificationSnapshot } from '@/composables/notificationSnapshot'
import { livingNotification, livingNotificationText, safeLivingPath } from './livingNotifications'
import { livingConversation, type LivingLine, type LivingWeather } from './livingWeather'
import type { NotificationEvent } from '@/api/notificationCenter'

const props = defineProps<{
  weather: LivingWeather
  member: string
  date: string
  quiet: boolean
  blocked: boolean
  idle: () => boolean
  anchors: () => { x: number; y: number; size: number }[]
}>()
const router = useRouter()
const host = ref<HTMLElement>()
const bubble = ref<HTMLElement>()
const active = ref(true),
  visible = ref(true),
  hidden = ref(false),
  reduced = ref(false)
const line = ref<LivingLine | null>(null),
  notice = ref<NotificationEvent | null>(null)
const position = ref({ left: '12px', top: '12px' })
const navigationError = ref(''),
  navigating = ref(false)
const placed = ref(false)
const names = companionBrand.map((item) => item.name)
const paused = computed(
  () =>
    props.quiet ||
    props.blocked ||
    !active.value ||
    !visible.value ||
    hidden.value ||
    reduced.value,
)
const namesOfClothes = {
  plain: '原来的样子',
  light: '轻薄上衣',
  jacket: '薄外套',
  winter: '厚外套',
  raincoat: '雨衣',
}
const climateLabel = computed(() =>
  props.weather.sky === 'unknown'
    ? '普通陪伴，天气暂不可用'
    : `今日天气预报场景，伙伴穿着${namesOfClothes[props.weather.clothes]}${props.weather.windy ? '，有风' : ''}`,
)
const slots = Array.from({ length: 18 }, (_, i) => ({
  left: `${(i * 47) % 100}%`,
  animationDelay: `${-i * 0.23}s`,
}))
let seen = new Set<string>(),
  seenKey = '',
  sequence: LivingLine[] = [],
  sequenceIndex = 0,
  variant = 0
let nextAt = 0,
  lineUntil = 0,
  lastInput = Date.now(),
  mounted = false
let timer: ReturnType<typeof setInterval> | undefined
let media: MediaQueryList | undefined, observer: IntersectionObserver | undefined
let generation = 0
const cooldown = () => 75_000 + Math.random() * 30_000
function clear() {
  line.value = null
  notice.value = null
  sequence = []
  navigationError.value = ''
  lineUntil = 0
}
function resetSchedule() {
  clear()
  nextAt = Date.now() + 25_000 + Math.random() * 15_000
  lastInput = Date.now()
}
function loadSeen() {
  seenKey = `wao-home-living-seen:${props.member}:${props.date}`
  seen = new Set()
  if (!props.member) return
  try {
    const value = JSON.parse(sessionStorage.getItem(seenKey) || '[]')
    if (Array.isArray(value))
      seen = new Set(value.filter((id): id is string => typeof id === 'string').slice(-100))
  } catch {
    /* 当前页面继续去重。 */
  }
}
function remember(id: string) {
  seen.add(id)
  seen = new Set([...seen].slice(-100))
  try {
    sessionStorage.setItem(seenKey, JSON.stringify([...seen]))
  } catch {
    /* 当前页面继续去重。 */
  }
}
async function place() {
  const token = generation
  await nextTick()
  if (token !== generation || !line.value || !host.value || !bubble.value) return
  const bounds = host.value.getBoundingClientRect(),
    anchor = props.anchors()[line.value.person]
  const width = bubble.value.offsetWidth,
    height = bubble.value.offsetHeight
  const x = anchor ? anchor.x - bounds.left - width / 2 : bounds.width / 2 - width / 2
  const y = anchor
    ? anchor.y - bounds.top - anchor.size * 0.65 - height - 10
    : bounds.height - height - 135
  position.value = {
    left: `${Math.max(12, Math.min(bounds.width - width - 12, x))}px`,
    top: `${Math.max(12, Math.min(bounds.height - height - 12, y))}px`,
  }
  placed.value = true
}
function show(value: LivingLine, event: NotificationEvent | null = null) {
  placed.value = false
  line.value = value
  notice.value = event
  lineUntil = Date.now() + (event ? 9000 : 3600)
  void place()
}
function interacting(event: Event) {
  if (event instanceof KeyboardEvent && event.key === 'Escape') {
    clear()
    lastInput = Date.now()
    return
  }
  if (bubble.value?.contains(event.target as Node)) return
  if (line.value) clear()
  lastInput = Date.now()
}
function tick() {
  if (paused.value || !props.member) return
  const overlay = [
    ...document.querySelectorAll<HTMLElement>('[role="dialog"], [role="menu"], #app[inert]'),
  ].some(
    (element) =>
      element.getClientRects().length > 0 && element.getAttribute('aria-hidden') !== 'true',
  )
  if (
    !props.idle() ||
    window.getSelection()?.toString() ||
    overlay ||
    document.activeElement?.matches('input,textarea,[contenteditable="true"]')
  ) {
    if (line.value) clear()
    lastInput = Date.now()
    return
  }
  const now = Date.now()
  if (line.value) {
    if (bubble.value?.contains(document.activeElement) || now < lineUntil) return
    if (sequence.length && sequenceIndex + 1 < sequence.length) {
      show(sequence[++sequenceIndex]!)
      return
    }
    clear()
    return
  }
  if (now - lastInput < 18_000 || now < nextAt) return
  const snapshot = notificationSnapshot.value
  const event = livingNotification(
    snapshot?.member === props.member ? snapshot.value : undefined,
    seen,
    now,
  )
  nextAt = now + cooldown()
  if (event) {
    show({ person: 1, text: livingNotificationText(event) }, event)
    remember(event.id)
    return
  }
  sequence = livingConversation(props.weather, variant++)
  sequenceIndex = 0
  show(sequence[0]!)
}
async function navigate() {
  const event = notice.value
  if (!event || !safeLivingPath(event.targetPath) || navigating.value) return
  const token = generation
  navigating.value = true
  navigationError.value = ''
  try {
    const failure = await router.push(event.targetPath)
    if (token !== generation) return
    if (failure) navigationError.value = '暂时没有打开，可再试一次。'
    else clear()
  } catch {
    if (token === generation) navigationError.value = '暂时没有打开，可再试一次。'
  } finally {
    if (token === generation) navigating.value = false
  }
}
watch(
  () => [props.member, props.date],
  () => {
    generation++
    navigating.value = false
    loadSeen()
    resetSchedule()
  },
  { immediate: true },
)
watch(() => props.weather, resetSchedule)
watch(paused, () => {
  if (mounted) resetSchedule()
})
watch(notificationSnapshot, (snapshot) => {
  if (
    notice.value &&
    !livingNotification(
      snapshot?.member === props.member
        ? {
            ...snapshot.value,
            events: snapshot.value.events.filter((event) => event.id === notice.value?.id),
          }
        : undefined,
      new Set(),
      Date.now(),
    )
  )
    clear()
})
function visibility() {
  hidden.value = document.hidden
  resetSchedule()
}
function motion() {
  reduced.value = !!media?.matches
}
onMounted(() => {
  mounted = true
  hidden.value = document.hidden
  media = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  motion()
  media?.addEventListener('change', motion)
  document.addEventListener('visibilitychange', visibility)
  document.addEventListener('pointerdown', interacting, true)
  document.addEventListener('keydown', interacting, true)
  document.addEventListener('pointermove', interacting, true)
  if (typeof IntersectionObserver !== 'undefined' && host.value) {
    observer = new IntersectionObserver((entries) => {
      visible.value = !!entries[0]?.isIntersecting
    })
    observer.observe(host.value)
  }
  timer = setInterval(tick, 1000)
})
onActivated(() => {
  active.value = true
  resetSchedule()
})
onDeactivated(() => {
  generation++
  active.value = false
  clear()
})
onBeforeUnmount(() => {
  generation++
  if (timer) clearInterval(timer)
  observer?.disconnect()
  media?.removeEventListener('change', motion)
  document.removeEventListener('visibilitychange', visibility)
  document.removeEventListener('pointerdown', interacting, true)
  document.removeEventListener('keydown', interacting, true)
  document.removeEventListener('pointermove', interacting, true)
})
</script>

<template>
  <div
    ref="host"
    class="living-corner"
    :class="{ paused }"
    :data-sky="weather.sky"
    :data-windy="weather.windy"
    :data-clothes="weather.clothes"
    :aria-label="climateLabel"
  >
    <div class="living-weather" aria-hidden="true">
      <div class="weather-wash"></div>
      <div class="weather-sun"></div>
      <div class="weather-cloud cloud-one"></div>
      <div class="weather-cloud cloud-two"></div>
      <div class="weather-cloud cloud-three"></div>
      <div v-if="['rain', 'storm', 'snow'].includes(weather.sky)" class="weather-drops">
        <i v-for="(slot, i) in slots" :key="i" :style="slot"></i>
      </div>
      <div v-if="weather.sky === 'fog'" class="weather-fog"></div>
      <div v-if="weather.windy" class="weather-wind"><i></i><i></i><i></i></div>
      <div v-if="weather.umbrella" class="weather-umbrella"><span></span></div>
    </div>
    <div
      v-if="line"
      ref="bubble"
      class="living-speech"
      :style="{ ...position, visibility: placed ? 'visible' : 'hidden' }"
      role="status"
      @pointerdown.stop
      @click.stop
      @contextmenu.stop
      @pointermove.stop
    >
      <span class="living-speaker"
        >{{ names[line.person] }}<template v-if="notice"> · 系统动态</template></span
      >
      <span>{{ line.text }}</span>
      <button v-if="notice" type="button" :disabled="navigating" @click="navigate">
        {{ navigating ? '打开中…' : navigationError ? '重试打开 ↗' : '查看原文 ↗' }}
      </button>
      <span v-if="navigationError" class="living-error" role="alert">{{ navigationError }}</span>
    </div>
  </div>
</template>

<style scoped>
.living-corner {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 4;
  overflow: clip;
}
.living-weather {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.weather-wash {
  position: absolute;
  inset: 35% 0 0;
  background: linear-gradient(
    transparent,
    color-mix(in srgb, var(--wao-primary-soft) 45%, transparent)
  );
  opacity: 0.6;
}
.weather-sun {
  position: absolute;
  right: 16%;
  top: 22%;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #ffd56d;
  box-shadow:
    0 0 0 10px #ffd56d12,
    0 0 0 25px #ffd56d08;
  display: none;
}
[data-sky='sun'] .weather-sun {
  display: block;
}
.weather-cloud {
  position: absolute;
  width: 95px;
  height: 18px;
  border-radius: 40px;
  right: 18%;
  top: 45%;
  background: color-mix(in srgb, var(--wao-text-3) 17%, var(--wao-bg-card));
  opacity: 0.55;
  animation: living-cloud 24s ease-in-out infinite alternate;
}
.weather-cloud:before,
.weather-cloud:after {
  content: '';
  position: absolute;
  border-radius: 50%;
  background: inherit;
  width: 42px;
  height: 35px;
  bottom: 0;
  left: 20px;
}
.weather-cloud:after {
  width: 30px;
  height: 27px;
  left: 51px;
}
.cloud-two {
  right: 3%;
  top: 25%;
  scale: 0.7;
  animation-delay: -8s;
}
.cloud-three {
  right: 47%;
  top: 34%;
  scale: 0.5;
  animation-delay: -15s;
}
[data-sky='sun'] .weather-cloud {
  opacity: 0.17;
}
[data-sky='storm'] .weather-cloud {
  opacity: 0.8;
  background: color-mix(in srgb, var(--wao-text-3) 32%, var(--wao-bg-card));
}
[data-sky='unknown'] .living-weather {
  display: none;
}
.weather-drops {
  position: absolute;
  inset: 30% 0 0;
  overflow: clip;
}
.weather-drops i {
  position: absolute;
  top: -12px;
  height: 10px;
  width: 1px;
  background: color-mix(in srgb, #6c9ac5 75%, var(--wao-text));
  opacity: 0.32;
  animation: living-rain 2.7s linear infinite;
}
[data-sky='snow'] .weather-drops i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: color-mix(in srgb, #bfd6ed 65%, var(--wao-text-3));
  opacity: 0.65;
  animation-duration: 8s;
}
.weather-fog {
  position: absolute;
  inset: 55% 0 0;
  background: linear-gradient(
    transparent,
    color-mix(in srgb, var(--wao-text-3) 8%, var(--wao-bg-card))
  );
}
.weather-wind {
  position: absolute;
  inset: 30% 5% 15%;
}
.weather-wind i {
  position: absolute;
  top: 20%;
  height: 1px;
  width: 45%;
  background: linear-gradient(
    90deg,
    transparent,
    color-mix(in srgb, var(--wao-text-3) 40%, transparent),
    transparent
  );
  animation: living-wind 4s ease-out infinite;
}
.weather-wind i:nth-child(2) {
  top: 50%;
  width: 32%;
  animation-delay: -1.5s;
}
.weather-wind i:nth-child(3) {
  top: 70%;
  animation-delay: -2.5s;
}
.weather-umbrella {
  position: absolute;
  bottom: 155px;
  left: 48%;
  width: min(78%, 580px);
  height: 145px;
  transform: translateX(-50%);
}
.weather-umbrella:before {
  content: '';
  position: absolute;
  inset: 0 0 35%;
  border-radius: 100% 100% 10% 10%;
  background: repeating-linear-gradient(80deg, #a7b8e3 0 24%, #c8d3ef 25% 49%);
  opacity: 0.9;
}
.weather-umbrella:after {
  content: '';
  position: absolute;
  top: 62%;
  bottom: 0;
  left: 50%;
  width: 2px;
  background: #8790ae;
}
.living-speech {
  position: absolute;
  pointer-events: auto;
  max-width: min(260px, calc(100% - 24px));
  padding: 10px 13px;
  border: 1px solid var(--wao-line);
  border-radius: 14px 14px 14px 3px;
  box-shadow: 0 5px 24px #3122430a;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-size: 12px;
  line-height: 1.65;
  overflow-wrap: anywhere;
  z-index: 7;
}
.living-speaker {
  display: block;
  color: var(--wao-text-3);
  font-size: 11px;
  margin-bottom: 3px;
}
.living-speech button {
  display: block;
  border: 0;
  padding: 4px 0;
  background: transparent;
  color: var(--wao-primary-text);
  font: inherit;
  cursor: pointer;
}
.living-speech button:focus-visible {
  outline: 2px solid var(--wao-primary-text);
  outline-offset: 3px;
}
.living-error {
  display: block;
  color: var(--wao-text-2);
}
.paused * {
  animation-play-state: paused !important;
}
@keyframes living-cloud {
  to {
    translate: -30px 0;
  }
}
@keyframes living-rain {
  to {
    translate: -35px 420px;
  }
}
@keyframes living-wind {
  0% {
    translate: 70px 0;
    opacity: 0;
  }
  50% {
    opacity: 0.5;
  }
  100% {
    translate: -100px 0;
    opacity: 0;
  }
}
@media (max-width: 600px) {
  .weather-umbrella {
    bottom: 96px;
    width: min(78%, 340px);
    height: 95px;
  }
  .weather-cloud {
    scale: 0.7;
  }
  .living-speech {
    font-size: 12px;
  }
}
@media (prefers-reduced-motion: reduce) {
  * {
    animation: none !important;
  }
  .weather-drops i {
    top: 40%;
  }
}
</style>
