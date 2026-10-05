<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { isNavigationFailure, NavigationFailureType, useRouter } from 'vue-router'
import {
  completeWritingJourney,
  requestWritingFocus,
  type WritingJourney,
} from '@/composables/writingJourney'
import WritingJourneyScene from './WritingJourneyScene.vue'

const props = defineProps<{ journey: WritingJourney }>()
const router = useRouter()
const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
const elapsed = ref(0)
const paused = ref(document.hidden)
const phase = ref<'playing' | 'caught' | 'leaving' | 'error'>('playing')
const error = ref('')
const navigating = ref(false)
const caughtIdea = ref(false)
const skip = ref<HTMLButtonElement>()
const overlay = ref<HTMLElement>()
const duration = 5800
const speech = computed(() =>
    caughtIdea.value
    ? '你接住了！快把它写下来。'
    : props.journey.lines[
        elapsed.value < duration * 0.33 ? 0 : elapsed.value < duration * 0.76 ? 1 : 2
      ],
)
const canCatch = computed(
  () =>
    props.journey.id === 'chase' &&
    phase.value === 'playing' &&
    elapsed.value >= duration * 0.54 &&
    elapsed.value < duration * 0.8 &&
    !paused.value,
)
const app = document.getElementById('app')
const priorInert = app?.inert ?? false
const priorOverflow = document.body.style.overflow
const previousFocus = document.activeElement
const startingRoute = router.currentRoute.value.fullPath
let frame = 0,
  stamp = 0,
  phaseStart = 0,
  disposed = false
const removeRouteListener = router.afterEach((to) => {
  if (!navigating.value && to.fullPath !== startingRoute) completeWritingJourney()
})

async function enter() {
  if (navigating.value || disposed) return
  navigating.value = true
  cancelAnimationFrame(frame)
  try {
    const failure = await router.push('/notes')
    if (
      (isNavigationFailure(failure) &&
        !isNavigationFailure(failure, NavigationFailureType.duplicated)) ||
      router.currentRoute.value.path !== '/notes'
    )
      throw new Error('小记页面暂时未能打开')
    if (disposed) return
    completeWritingJourney()
    await nextTick() // 浮层卸载、恢复 app 可交互后再交出一次焦点请求。
    requestWritingFocus()
  } catch {
    if (!disposed) {
      phase.value = 'error'
      error.value = '小记暂时未能打开，重试一下。已有草稿仍保留。'
      await nextTick()
      skip.value?.focus()
    }
  } finally {
    navigating.value = false
  }
}

function catchIdea() {
  if (!canCatch.value) return
  caughtIdea.value = true
  phase.value = 'caught'
  phaseStart = elapsed.value
  skip.value?.focus({ preventScroll: true })
}
function tick(now: number) {
  if (disposed || paused.value || phase.value === 'error') return
  elapsed.value += Math.max(0, now - stamp)
  stamp = now
  if (
    (phase.value === 'playing' && elapsed.value >= duration) ||
    (phase.value === 'caught' && elapsed.value - phaseStart >= 700)
  ) {
    phase.value = 'leaving'
    phaseStart = elapsed.value
  }
  if (phase.value === 'leaving' && elapsed.value - phaseStart >= 550) {
    void enter()
    return
  }
  frame = requestAnimationFrame(tick)
}
function visibility() {
  paused.value = document.hidden
  cancelAnimationFrame(frame)
  if (!paused.value && !navigating.value) {
    stamp = performance.now()
    frame = requestAnimationFrame(tick)
  }
}
function cancel() {
  completeWritingJourney()
  if (previousFocus instanceof HTMLElement)
    void nextTick(() => previousFocus.focus({ preventScroll: true }))
}
function keys(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    void enter()
  }
  if (event.key === 'Tab') {
    const controls = [
      ...(overlay.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? []),
    ].filter((button) => button.getClientRects().length)
    const i = controls.indexOf(document.activeElement as HTMLButtonElement)
    event.preventDefault()
    controls[(i + (event.shiftKey ? -1 : 1) + controls.length) % controls.length]?.focus()
  }
}
onMounted(async () => {
  if (app) app.inert = true
  document.body.style.overflow = 'hidden'
  document.addEventListener('visibilitychange', visibility)
  document.addEventListener('keydown', keys, true)
  await nextTick()
  skip.value?.focus({ preventScroll: true })
  if (reduced) {
    void enter()
    return
  }
  stamp = performance.now()
  if (!paused.value) frame = requestAnimationFrame(tick)
})
onBeforeUnmount(() => {
  disposed = true
  cancelAnimationFrame(frame)
  removeRouteListener()
  document.removeEventListener('visibilitychange', visibility)
  document.removeEventListener('keydown', keys, true)
  if (app) app.inert = priorInert
  document.body.style.overflow = priorOverflow
})
</script>

<template>
  <Teleport to="body">
    <section
      ref="overlay"
      class="writing-entry"
      :class="[journey.scene, phase, { paused, reduced }]"
      role="dialog"
      aria-modal="true"
      :aria-label="journey.name"
    >
      <WritingJourneyScene
        :kind="journey.id"
        :paused="paused"
        :caught="caughtIdea"
        :leaving="phase === 'leaving'"
        :can-catch="canCatch"
        @catch="catchIdea"
      />
      <div v-if="phase !== 'error'" class="writing-story">
        <span>给一个念头，留个位置</span>
        <h2 role="status">{{ speech }}</h2>
      </div>
      <div v-else class="writing-error" role="alert">
        <p>{{ error }}</p>
        <button type="button" @click="cancel">返回首页</button>
      </div>
      <button ref="skip" class="writing-skip" type="button" :disabled="navigating" @click="enter">
        {{ phase === 'error' ? '重试进入小记' : '直接去写' }} <span aria-hidden="true">↗</span>
      </button>
      <span v-if="paused" class="writing-paused" role="status">旅程已暂停，回来后继续。</span>
    </section>
  </Teleport>
</template>

<style scoped>
.writing-entry {
  position: fixed;
  inset: 0;
  z-index: 2600;
  overflow: hidden;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  animation: writing-in 0.55s ease-out;
}
.writing-story {
  position: absolute;
  top: 15%;
  left: 6%;
  right: 6%;
  text-align: center;
  pointer-events: none;
}
.writing-story > span {
  font-size: 12px;
  color: var(--wao-text-weak);
  letter-spacing: 0.08em;
}
.writing-story h2 {
  margin: 22px 0 0;
  font-size: clamp(20px, 3vw, 30px);
  font-weight: 500;
  line-height: 1.5;
}
button {
  font: inherit;
  cursor: pointer;
  min-height: 44px;
  padding: 11px 18px;
  border: 1px solid var(--wao-line);
  border-radius: 24px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}
button:focus-visible {
  outline: 2px solid var(--wao-primary-text);
  outline-offset: 4px;
}
.writing-skip {
  position: absolute;
  top: 24px;
  right: 26px;
  font-size: 12px;
  z-index: 5;
}
.writing-error {
  position: absolute;
  top: 30%;
  left: 50%;
  transform: translateX(-50%);
  width: min(420px, calc(100% - 36px));
  text-align: center;
}
.writing-paused {
  position: absolute;
  bottom: 6%;
  left: 12px;
  right: 12px;
  text-align: center;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.leaving .writing-story {
  opacity: 0;
  transition: opacity 0.5s;
}
@keyframes writing-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
@media (max-width: 430px) {
  .writing-story {
    top: 18%;
  }
  .writing-skip {
    top: 18px;
    right: 18px;
  }
}
@media (max-height: 500px) {
  .writing-story {
    top: 8%;
  }
  .writing-story h2 {
    margin-top: 10px;
  }
  .writing-skip {
    top: auto;
    bottom: 12px;
    right: 12px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .writing-entry {
    animation: none;
  }
}
</style>
