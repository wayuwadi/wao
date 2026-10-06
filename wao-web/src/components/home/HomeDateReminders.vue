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
import {
  fetchHomeDateReminders,
  reserveHomeDateReminders,
  recordHomeDateReminder,
  type HomeDateQueue,
  type HomeDateReservation,
} from '@/api/homeDateReminders'
import { safeLivingPath } from './livingNotifications'

const props = defineProps<{
  member: string
  date: string
  quiet: boolean
  blocked: boolean
  idle: () => boolean
  anchors: () => { x: number; y: number; size: number }[]
}>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const router = useRouter(),
  host = ref<HTMLElement>(),
  bubble = ref<HTMLElement>()
const queue = ref<HomeDateQueue>(),
  shown = ref<HomeDateReservation>(),
  placed = ref(false)
const active = ref(true),
  hidden = ref(false),
  intersecting = ref(true),
  reduced = ref(false)
const held = ref(false),
  error = ref(''),
  saving = ref(false),
  opening = ref(false)
const position = ref({ left: '12px', top: '12px' }),
  ceremony = ref('stars')
const primary = computed(() => shown.value?.notices[0])
const secondary = computed(() => shown.value?.notices[1])
const birthday = computed(
  () => primary.value?.daysBefore === 0 && ['BIRTHDAY', 'ANNIVERSARY'].includes(primary.value.kind),
)
const style = computed(() =>
  birthday.value ? 'ceremony' : primary.value?.kind === 'SOLAR_TERM' ? 'whisper' : 'speech',
)
const title = computed(() => primary.value?.title ?? '')
const detail = computed(() =>
  secondary.value
    ? `${secondary.value.title}${(shown.value?.notices.length ?? 0) > 2 ? ' 其他日子也留在日历里。' : ''}`
    : (primary.value?.detail ?? ''),
)
const ackLabel = computed(() =>
  birthday.value ? '收下祝福' : primary.value?.kind === 'SOLAR_TERM' ? '知道啦' : '记住了',
)
const paused = computed(() => props.blocked || !active.value || hidden.value || !intersecting.value)
const dayFormatter = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})
const currentDay = ref(dayFormatter.format(Date.now()))
let generation = 0,
  attempted = false,
  loading = false,
  lastInput = Date.now(),
  readyAt = 0,
  until = 0,
  retryAt = 0
let timer: ReturnType<typeof setInterval> | undefined,
  media: MediaQueryList | undefined,
  observer: IntersectionObserver | undefined,
  resize: ResizeObserver | undefined

function clear() {
  shown.value = undefined
  placed.value = false
  held.value = false
  error.value = ''
  emit('busy', false)
}
async function load() {
  if (!props.member || !props.date || loading || !active.value) return
  const mine = generation
  loading = true
  try {
    const value = await fetchHomeDateReminders()
    if (mine === generation) {
      queue.value = value
      readyAt = Date.now() + 4000 + Math.random() * 4000
    }
  } catch {
    if (mine === generation) queue.value = undefined
  } finally {
    if (mine === generation) {
      loading = false
      retryAt = Date.now() + 60000
    }
  }
}
function allowed() {
  return (
    !paused.value &&
    props.idle() &&
    !window.getSelection()?.toString() &&
    !document.activeElement?.matches('input,textarea,[contenteditable="true"]') &&
    ![...document.querySelectorAll<HTMLElement>('[role="dialog"],[role="menu"],#app[inert]')].some(
      (el) => el.getClientRects().length > 0 && el.getAttribute('aria-hidden') !== 'true',
    )
  )
}
async function place() {
  await nextTick()
  if (!host.value || !bubble.value || !shown.value) return
  const bounds = host.value.getBoundingClientRect(),
    anchors = props.anchors(),
    anchor = anchors[1]
  const width = bubble.value.offsetWidth,
    height = bubble.value.offsetHeight
  const x = anchor ? anchor.x - bounds.left - width / 2 : (bounds.width - width) / 2
  const y = anchor
    ? anchor.y - bounds.top - anchor.size * 0.65 - height - 12
    : bounds.height - height - 135
  position.value = {
    left: `${Math.max(12, Math.min(bounds.width - width - 12, x))}px`,
    top: `${Math.max(12, Math.min(bounds.height - height - 12, y))}px`,
  }
}
async function present() {
  const value = queue.value
  if (!value?.notices.length || value.date !== currentDay.value) return
  const mine = generation
  attempted = true
  emit('busy', true)
  try {
    const reservation = await reserveHomeDateReminders(value)
    if (!reservation.notices.length) {
      if (mine === generation) emit('busy', false)
      return
    }
    if (mine !== generation || !allowed()) {
      void recordHomeDateReminder(reservation, 'RELEASED').catch(() => {})
      if (mine === generation) {
        emit('busy', false)
        attempted = false
      }
      return
    }
    shown.value = reservation
    ceremony.value = ['stars', 'ribbons', 'candles'][Math.floor(Math.random() * 3)]!
    await place()
    if (mine !== generation || !allowed()) {
      void recordHomeDateReminder(reservation, 'RELEASED').catch(() => {})
      if (mine === generation) clear()
      return
    }
    placed.value = true
    until = Date.now() + 10000
    await nextTick()
    // 可见后再写展示回执；读取和租约不会被记成已读。
    if (mine === generation) await receipt('SHOWN')
  } catch {
    if (mine === generation) {
      clear()
      retryAt = Date.now() + 60000
      attempted = false
      queue.value = undefined
    }
  }
}
async function receipt(state: 'SHOWN' | 'CONFIRMED') {
  const value = shown.value
  if (!value || saving.value) return
  const mine = generation
  saving.value = true
  error.value = ''
  try {
    await recordHomeDateReminder(value, state)
    if (mine === generation && state === 'CONFIRMED') clear()
  } catch (cause) {
    if (mine === generation) {
      if ((cause as { response?: { status?: number } }).response?.status === 409) clear()
      else
        error.value =
          state === 'CONFIRMED' ? '确认还没有保存，点击重试。' : '提醒状态还没有保存，点击重试。'
    }
  } finally {
    if (mine === generation) saving.value = false
  }
}
async function navigate() {
  const path = primary.value?.targetPath
  if (!path || !safeLivingPath(path) || opening.value) return
  opening.value = true
  error.value = ''
  try {
    const failure = await router.push(path)
    if (failure) error.value = '日历暂未打开，点击重试。'
    else clear()
  } catch {
    error.value = '日历暂未打开，点击重试。'
  } finally {
    opening.value = false
  }
}
function input(event: Event) {
  if (event instanceof KeyboardEvent && event.key === 'Escape') {
    clear()
    return
  }
  if (bubble.value?.contains(event.target as Node)) return
  lastInput = Date.now()
  if (shown.value) clear()
}
function tick() {
  if (paused.value) return
  const day = dayFormatter.format(Date.now())
  if (day !== currentDay.value) {
    currentDay.value = day
    generation++
    clear()
    loading = false
    attempted = false
    queue.value = undefined
    void load()
    return
  }
  if (shown.value) {
    if (!allowed()) {
      clear()
      return
    }
    if (
      placed.value &&
      !held.value &&
      !saving.value &&
      !error.value &&
      !bubble.value?.contains(document.activeElement) &&
      Date.now() > until
    )
      clear()
    return
  }
  if (!allowed() || Date.now() - lastInput < 4000) return
  if (!attempted && queue.value?.notices.length && Date.now() >= readyAt) void present()
  else if (!attempted && Date.now() > retryAt && !loading) void load()
}
function visibility() {
  hidden.value = document.hidden
  if (hidden.value) clear()
  else lastInput = Date.now()
}
function motion() {
  reduced.value = !!media?.matches
}
function changed() {
  queue.value = undefined
  retryAt = 0
  if (!attempted) void load()
}
watch(
  () => [props.member, props.date],
  () => {
    generation++
    loading = false
    saving.value = false
    attempted = false
    queue.value = undefined
    clear()
    lastInput = Date.now()
    void load()
  },
  { immediate: true },
)
watch(paused, (value) => {
  if (value) clear()
  else lastInput = Date.now()
})
onMounted(() => {
  hidden.value = document.hidden
  media = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  motion()
  media?.addEventListener('change', motion)
  document.addEventListener('visibilitychange', visibility)
  document.addEventListener('pointerdown', input, true)
  document.addEventListener('keydown', input, true)
  window.addEventListener('wao-home-date-changed', changed)
  if (typeof IntersectionObserver !== 'undefined' && host.value) {
    observer = new IntersectionObserver((entries) => {
      intersecting.value = !!entries[0]?.isIntersecting
    })
    observer.observe(host.value)
  }
  if (typeof ResizeObserver !== 'undefined' && host.value) {
    resize = new ResizeObserver(() => {
      void place()
    })
    resize.observe(host.value)
  }
  timer = setInterval(tick, 500)
})
onActivated(() => {
  active.value = true
  lastInput = Date.now()
  if (!queue.value) void load()
})
onDeactivated(() => {
  generation++
  active.value = false
  loading = false
  saving.value = false
  clear()
  queue.value = undefined
  attempted = false
})
onBeforeUnmount(() => {
  generation++
  clear()
  if (timer) clearInterval(timer)
  observer?.disconnect()
  resize?.disconnect()
  media?.removeEventListener('change', motion)
  document.removeEventListener('visibilitychange', visibility)
  document.removeEventListener('pointerdown', input, true)
  document.removeEventListener('keydown', input, true)
  window.removeEventListener('wao-home-date-changed', changed)
})
function releaseHold() {
  held.value = false
  until = Date.now() + 10000
}
</script>

<template>
  <div ref="host" class="home-date-reminders" :class="{ still: quiet || reduced }">
    <div
      v-if="shown"
      ref="bubble"
      class="date-message"
      :class="[style, ceremony]"
      :style="{ ...position, visibility: placed ? 'visible' : 'hidden' }"
      role="status"
      @pointerenter="held = true"
      @pointerleave="releaseHold"
      @pointerdown.stop
      @pointermove.stop
      @click.stop
      @contextmenu.stop
    >
      <div v-if="birthday && !quiet && !reduced" class="date-sparks" aria-hidden="true">
        <i v-for="i in 7" :key="i" :style="{ '--i': i }">✦</i>
      </div>
      <span class="date-kicker">{{
        primary?.kind === 'SOLAR_TERM'
          ? '今日节气'
          : primary?.daysBefore
            ? '你提前留给自己的提醒'
            : birthday
              ? '今天值得纪念'
              : '伙伴捎来一句提醒'
      }}</span>
      <strong>{{ title }}</strong
      ><span class="date-detail">{{ detail }}</span>
      <div class="date-actions">
        <button type="button" :disabled="saving" @click="receipt('CONFIRMED')">
          {{ saving ? '保存中…' : error ? '重试确认' : ackLabel }}</button
        ><button type="button" :disabled="opening" @click="navigate">
          {{ opening ? '打开中…' : '看看日历 ↗' }}</button
        ><button type="button" class="date-close" aria-label="收起日期提醒" @click="clear">
          <i class="ri-close-line" aria-hidden="true"></i>
        </button>
      </div>
      <span v-if="error" class="date-error" role="alert">{{ error }}</span>
      <div v-if="birthday && !quiet && !reduced" class="date-cake" aria-hidden="true">
        <i></i><span></span>
      </div>
    </div>
  </div>
</template>
<style scoped>
.home-date-reminders {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 6;
  overflow: clip;
}
.date-message {
  position: absolute;
  pointer-events: auto;
  width: 300px;
  max-width: calc(100% - 24px);
  padding: 16px 18px;
  border: 1px solid var(--wao-line);
  border-radius: 18px 18px 18px 4px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  box-shadow: 0 5px 24px color-mix(in srgb, var(--wao-primary-text) 5%, transparent);
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
  animation: date-arrive 0.7s ease-out both;
}
.date-kicker {
  display: block;
  color: var(--wao-text-3);
  font-size: 11px;
  margin-bottom: 5px;
}
.date-message strong {
  display: block;
  font-size: 16px;
  line-height: 1.6;
  font-weight: 500;
}
.date-detail {
  display: block;
  margin-top: 6px;
  color: var(--wao-text-2);
}
.date-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 9px;
}
.date-actions button {
  border: 0;
  background: transparent;
  padding: 5px 0;
  color: var(--wao-primary-text);
  font: inherit;
  cursor: pointer;
  min-height: 32px;
}
.date-actions button:focus-visible {
  outline: 2px solid var(--wao-primary-text);
  outline-offset: 3px;
}
.date-close {
  margin-left: auto;
}
.date-error {
  display: block;
  color: var(--wao-danger);
  font-size: 12px;
}
.whisper,
.ceremony {
  border: 0;
  box-shadow: none;
  background: transparent;
}
.whisper {
  text-align: right;
}
.whisper strong {
  font-size: 20px;
  font-weight: 400;
}
.whisper .date-actions {
  justify-content: flex-end;
}
.ceremony {
  text-align: center;
  padding-bottom: 48px;
}
.ceremony .date-actions {
  justify-content: center;
}
.date-sparks {
  display: flex;
  justify-content: space-around;
  color: var(--wao-primary-text);
  margin-bottom: 8px;
}
.date-sparks i {
  font-style: normal;
  font-size: calc(9px + var(--i) * 1px);
  animation: date-star 1.5s ease-out both;
  animation-delay: calc(var(--i) * 0.08s);
}
.ribbons .date-sparks i {
  font-size: 0;
  width: 3px;
  height: 12px;
  border-radius: 2px;
  transform: rotate(calc(var(--i) * 28deg));
  background: var(--wao-primary-text);
  opacity: 0.65;
}
.candles .date-sparks i:nth-child(even) {
  visibility: hidden;
}
.date-cake {
  position: absolute;
  bottom: 0;
  left: calc(50% - 22px);
  width: 44px;
  height: 36px;
}
.date-cake span {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 20px;
  border-radius: 5px;
  border-top: 6px solid color-mix(in srgb, var(--wao-primary-soft) 80%, var(--wao-bg-card));
  background: color-mix(in srgb, var(--wao-primary-text) 24%, var(--wao-bg-card));
}
.date-cake i {
  position: absolute;
  bottom: 20px;
  left: 21px;
  width: 3px;
  height: 10px;
  background: var(--wao-primary-text);
}
.date-cake i:before {
  content: '';
  position: absolute;
  top: -7px;
  left: -2px;
  width: 6px;
  height: 7px;
  border-radius: 50%;
  background: #e7b752;
}
.still * {
  animation: none !important;
}
.still .ceremony {
  padding-bottom: 16px;
}
@keyframes date-arrive {
  from {
    opacity: 0;
    translate: 0 8px;
  }
  to {
    opacity: 1;
    translate: 0 0;
  }
}
@keyframes date-star {
  from {
    opacity: 0;
    translate: 0 10px;
  }
  to {
    opacity: 0.8;
    translate: 0 0;
  }
}
@media (pointer: coarse) {
  .date-actions button {
    min-height: 44px;
  }
}
@media (prefers-reduced-motion: reduce) {
  * {
    animation: none !important;
  }
}
</style>
