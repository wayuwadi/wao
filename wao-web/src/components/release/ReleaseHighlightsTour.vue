<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { MODULES } from '@/config/nav'
import type { ReleaseEdition } from '@/types/featureExplorer'

const props = defineProps<{ open: boolean; edition: ReleaseEdition }>()
const emit = defineEmits<{
  dismiss: [mode: 'seen' | 'later']
  navigate: [path: string]
  features: []
}>()

const dialog = ref<HTMLElement | null>(null)
const step = ref(0)
const total = computed(() => props.edition.highlights.length + 2)
const highlight = computed(() => props.edition.highlights[step.value - 1])
const isFinish = computed(() => step.value === total.value - 1)
const stepLabel = computed(() => {
  if (step.value === 0) return '欢迎'
  if (isFinish.value) return '完成'
  return highlight.value?.eyebrow ?? '更新亮点'
})
let previousFocus: HTMLElement | null = null
let previousOverflow = ''

function show(next: number) {
  if (next < 0 || next >= total.value) return
  step.value = next
  void nextTick(() => dialog.value?.querySelector<HTMLElement>('h2')?.focus())
}

function keydown(event: KeyboardEvent) {
  if (!props.open) return
  if (event.key === 'ArrowRight') show(step.value + 1)
  else if (event.key === 'ArrowLeft') show(step.value - 1)
  else if (event.key === 'Escape') emit('dismiss', 'seen')
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
      previousOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
      step.value = 0
      window.addEventListener('keydown', keydown)
      void nextTick(() => dialog.value?.querySelector<HTMLElement>('h2')?.focus())
    } else {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', keydown)
      previousFocus?.focus()
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  document.body.style.overflow = previousOverflow
  window.removeEventListener('keydown', keydown)
})

function next() {
  if (isFinish.value) emit('dismiss', 'seen')
  else show(step.value + 1)
}

function openCurrentFeature() {
  const item = highlight.value
  if (!item) return
  emit('navigate', MODULES[item.featureId].path)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="release-mask">
      <section
        ref="dialog"
        class="release-tour"
        role="dialog"
        aria-modal="true"
        aria-labelledby="release-tour-title"
      >
        <header class="tour-header">
          <div class="tour-brand">
            <span class="tour-mark">W</span><b>WAO 的新变化</b><em>{{ edition.publishedOn }}</em>
          </div>
          <div class="header-actions">
            <button type="button" class="later" @click="emit('dismiss', 'later')">稍后再看</button>
            <button
              type="button"
              class="close"
              aria-label="关闭更新介绍"
              @click="emit('dismiss', 'seen')"
            >
              ×
            </button>
          </div>
        </header>

        <div class="tour-stage">
          <article v-if="step === 0" class="slide intro">
            <div class="intro-orbit" aria-hidden="true"><span>W</span></div>
            <div class="copy intro-copy">
              <span class="kicker">更新已经完成</span>
              <h2 id="release-tour-title" tabindex="-1">{{ edition.title }}</h2>
              <p>{{ edition.intro }}</p>
              <div class="intro-tags">
                <span v-for="item in edition.highlights" :key="item.featureId">{{
                  item.eyebrow
                }}</span>
              </div>
            </div>
          </article>

          <article v-else-if="highlight" class="slide feature-slide">
            <div class="copy feature-copy">
              <span class="number"
                >{{ String(step).padStart(2, '0') }} /
                {{ String(edition.highlights.length).padStart(2, '0') }} ·
                {{ highlight.eyebrow }}</span
              >
              <span class="kicker">{{ MODULES[highlight.featureId].label }}</span>
              <h2 tabindex="-1">{{ highlight.title }}</h2>
              <p>{{ highlight.summary }}</p>
              <ul>
                <li v-for="point in highlight.points" :key="point">
                  <i class="ri-check-line" aria-hidden="true"></i>{{ point }}
                </li>
              </ul>
              <button type="button" class="try-feature" @click="openCurrentFeature">
                进入{{ MODULES[highlight.featureId].label }}看看
                <i class="ri-arrow-right-up-line"></i>
              </button>
            </div>
            <div class="feature-visual" :data-feature="highlight.featureId" aria-hidden="true">
              <div class="visual-window">
                <div class="window-bar"><span></span><span></span><span></span></div>
                <div class="visual-content">
                  <div class="visual-sidebar">
                    <i :class="MODULES[highlight.featureId].icon"></i><span></span><span></span
                    ><span></span>
                  </div>
                  <div class="visual-main">
                    <div class="visual-chip">{{ highlight.eyebrow }}</div>
                    <i class="visual-icon" :class="MODULES[highlight.featureId].icon"></i>
                    <b>{{ highlight.title }}</b>
                    <span class="visual-line long"></span><span class="visual-line"></span
                    ><span class="visual-line short"></span>
                  </div>
                </div>
              </div>
            </div>
          </article>

          <article v-else class="slide finish">
            <div class="finish-card copy">
              <span class="kicker">已经看完</span>
              <h2 tabindex="-1">继续你的 WAO</h2>
              <p>
                这份介绍不会挡住工作。以后可以从“全部功能 → 功能探索”查看功能介绍。
              </p>
              <div class="finish-grid">
                <div v-for="item in edition.highlights" :key="item.featureId">
                  <i :class="MODULES[item.featureId].icon" aria-hidden="true"></i
                  ><b>{{ item.eyebrow }}</b
                  ><small>{{ item.title }}</small>
                </div>
              </div>
              <div class="finish-actions">
                <button type="button" class="features-button" @click="emit('features')">
                  探索全部功能 <i class="ri-arrow-right-up-line"></i>
                </button>
                <button type="button" class="back-button" @click="emit('dismiss', 'seen')">
                  回到 WAO
                </button>
              </div>
            </div>
          </article>
        </div>

        <footer class="tour-footer">
          <div class="step-copy">
            <b>{{ stepLabel }}</b
            ><span>{{ step + 1 }} / {{ total }}</span>
          </div>
          <div class="dots" aria-label="更新介绍进度">
            <button
              v-for="index in total"
              :key="index"
              type="button"
              :class="{ active: step === index - 1 }"
              :aria-label="`查看第 ${index} 页`"
              @click="show(index - 1)"
            ></button>
          </div>
          <div class="tour-actions">
            <button
              type="button"
              class="previous"
              :disabled="step === 0"
              aria-label="上一页"
              @click="show(step - 1)"
            >
              <i class="ri-arrow-left-line"></i>
            </button>
            <button type="button" class="next" @click="next">
              {{ isFinish ? '回到 WAO' : step === 0 ? '开始看看' : '下一条' }}
              <i v-if="!isFinish" class="ri-arrow-right-line"></i>
            </button>
          </div>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.release-mask {
  position: fixed;
  inset: 0;
  z-index: 1300;
  display: grid;
  place-items: center;
  padding: 28px;
  background: rgb(12 16 14 / 62%);
  backdrop-filter: blur(18px) saturate(0.82);
}
.release-tour {
  position: relative;
  width: min(1160px, 100%);
  height: min(760px, calc(100vh - 56px));
  min-height: 600px;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 30px;
  background: #101411;
  color: #fff;
  box-shadow: 0 40px 120px rgb(0 0 0 / 45%);
}
.release-tour::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 15% 18%, rgb(91 86 232 / 24%), transparent 31%),
    radial-gradient(circle at 86% 72%, rgb(74 190 133 / 17%), transparent 30%);
  pointer-events: none;
}
.tour-header {
  position: relative;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 74px;
  padding: 0 26px 0 30px;
  border-bottom: 1px solid rgb(255 255 255 / 8%);
}
.tour-brand,
.header-actions {
  display: flex;
  align-items: center;
  gap: 11px;
}
.tour-mark {
  display: grid;
  place-items: center;
  width: 31px;
  height: 31px;
  border-radius: 10px;
  background: linear-gradient(145deg, #665df1, #8e75ff);
  font-weight: 900;
}
.tour-brand b {
  font-size: 15px;
}
.tour-brand em {
  padding: 5px 9px;
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 999px;
  color: #aeb7b2;
  font-size: 10px;
  font-style: normal;
}
.header-actions button {
  border: 0;
  color: #c7ceca;
  cursor: pointer;
}
.later {
  padding: 8px 10px;
  background: transparent;
  font-size: 12px;
  font-weight: 700;
}
.close {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  background: rgb(255 255 255 / 7%);
  font-size: 20px;
}
.tour-stage {
  position: absolute;
  inset: 74px 0 98px;
}
.slide {
  height: 100%;
  display: grid;
  grid-template-columns: 0.86fr 1.14fr;
  align-items: center;
  gap: 38px;
  padding: 42px 68px 34px;
}
.intro,
.finish {
  grid-template-columns: 1fr;
  justify-items: center;
  text-align: center;
}
.copy h2 {
  margin: 0;
  font-size: clamp(34px, 4.6vw, 58px);
  line-height: 1.08;
  letter-spacing: -0.055em;
}

.copy h2:focus {
  outline: none;
}
.copy p {
  max-width: 620px;
  margin: 18px 0 0;
  color: #adb6b1;
  font-size: 15px;
  line-height: 1.8;
}
.kicker {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 16px;
  color: #a8e8c6;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
}
.kicker::before {
  content: '';
  width: 20px;
  height: 1px;
  background: currentColor;
}
.intro-orbit {
  position: relative;
  display: grid;
  place-items: center;
  width: 150px;
  height: 150px;
  margin-bottom: 18px;
}
.intro-orbit::before,
.intro-orbit::after {
  content: '';
  position: absolute;
  inset: 12px;
  border: 1px solid rgb(153 139 255 / 34%);
  border-radius: 42% 58% 57% 43%;
  transform: rotate(28deg);
}
.intro-orbit::after {
  inset: 29px;
  border-color: rgb(119 222 168 / 40%);
  transform: rotate(-30deg);
}
.intro-orbit span {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 74px;
  height: 74px;
  border-radius: 24px;
  background: linear-gradient(145deg, #665df1, #8e75ff);
  box-shadow: 0 18px 50px rgb(91 86 232 / 40%);
  font-size: 27px;
  font-weight: 900;
}
.intro-copy {
  max-width: 820px;
}
.intro-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 25px;
}
.intro-tags span {
  padding: 7px 10px;
  border: 1px solid rgb(255 255 255 / 10%);
  border-radius: 999px;
  background: rgb(255 255 255 / 4%);
  color: #c4cbc7;
  font-size: 11px;
}
.number {
  display: block;
  margin-bottom: 24px;
  color: #7d8580;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.15em;
}
.feature-copy ul {
  display: grid;
  gap: 10px;
  margin: 20px 0 0;
  padding: 0;
  list-style: none;
}
.feature-copy li {
  display: flex;
  align-items: center;
  gap: 9px;
  color: #d7ded9;
  font-size: 12px;
}
.feature-copy li i {
  display: grid;
  place-items: center;
  width: 21px;
  height: 21px;
  border-radius: 50%;
  background: rgb(112 217 165 / 14%);
  color: #8ce0b5;
}
.try-feature,
.features-button,
.back-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 27px;
  padding: 11px 16px;
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 12px;
  background: rgb(255 255 255 / 8%);
  color: #fff;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
}
.feature-visual {
  position: relative;
  display: grid;
  place-items: center;
  min-height: 390px;
  perspective: 1000px;
}
.feature-visual::before {
  content: '';
  position: absolute;
  width: 76%;
  height: 76%;
  border-radius: 50%;
  background: radial-gradient(circle, rgb(91 86 232 / 22%), transparent 66%);
  filter: blur(8px);
}
.visual-window {
  position: relative;
  z-index: 1;
  width: min(590px, 100%);
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 15%);
  border-radius: 22px;
  background: #f4f3ee;
  color: #202924;
  box-shadow: 0 32px 70px rgb(0 0 0 / 36%);
  transform: rotateY(-5deg) rotateX(2deg);
}
.window-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 37px;
  padding: 0 14px;
  border-bottom: 1px solid #dfe2dd;
  background: #e9ebe7;
}
.window-bar span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c9cdc8;
}
.window-bar span:first-child {
  background: #f0796f;
}
.window-bar span:nth-child(2) {
  background: #ecc05c;
}
.window-bar span:nth-child(3) {
  background: #6bc483;
}
.visual-content {
  display: grid;
  grid-template-columns: 106px 1fr;
  min-height: 310px;
}
.visual-sidebar {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 24px 17px;
  background: #e8e7ff;
  color: #4b46c8;
}
.visual-sidebar i {
  font-size: 23px;
}
.visual-sidebar span {
  height: 8px;
  border-radius: 5px;
  background: rgb(75 70 200 / 14%);
}
.visual-sidebar span:last-child {
  width: 62%;
}
.visual-main {
  position: relative;
  padding: 35px 38px;
  background: #fffefa;
}
.visual-main .visual-icon {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  margin-bottom: 21px;
  border-radius: 17px;
  background: #eeedff;
  color: #554ed4;
  font-size: 25px;
}
.visual-main b {
  display: block;
  max-width: 360px;
  margin-bottom: 22px;
  font-size: 23px;
  line-height: 1.35;
}
.visual-chip {
  position: absolute;
  right: 20px;
  top: 18px;
  padding: 7px 10px;
  border-radius: 9px;
  background: #e5f7ec;
  color: #26754f;
  font-size: 9px;
  font-weight: 800;
}
.visual-line {
  display: block;
  width: 80%;
  height: 9px;
  margin: 10px 0;
  border-radius: 5px;
  background: #e7e9e5;
}
.visual-line.long {
  width: 96%;
}
.visual-line.short {
  width: 62%;
}
.finish-card {
  width: min(720px, 100%);
  padding: 32px;
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 26px;
  background: rgb(255 255 255 / 5%);
}
.finish-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin: 27px 0;
  text-align: left;
}
.finish-grid > div {
  padding: 14px;
  border: 1px solid rgb(255 255 255 / 9%);
  border-radius: 14px;
  background: rgb(255 255 255 / 3%);
}
.finish-grid i {
  display: block;
  margin-bottom: 10px;
  color: #8f87ff;
  font-size: 17px;
}
.finish-grid b {
  display: block;
  font-size: 11px;
}
.finish-grid small {
  display: block;
  margin-top: 5px;
  color: #929b96;
  font-size: 9px;
  line-height: 1.5;
}
.finish-actions {
  display: flex;
  justify-content: center;
  gap: 10px;
}
.finish-actions button {
  margin-top: 0;
}
.features-button {
  background: #665fe9;
}
.back-button {
  background: transparent;
}
.tour-footer {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 3;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  height: 98px;
  padding: 0 30px;
  border-top: 1px solid rgb(255 255 255 / 8%);
  background: rgb(13 17 14 / 78%);
  backdrop-filter: blur(18px);
}
.step-copy {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #818a85;
  font-size: 11px;
}
.step-copy b {
  color: #d7ded9;
}
.dots {
  display: flex;
  gap: 7px;
}
.dots button {
  width: 22px;
  height: 4px;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 15%);
  cursor: pointer;
  transition:
    width 0.2s ease,
    background 0.2s ease;
}
.dots button.active {
  width: 40px;
  background: linear-gradient(90deg, #7a73ff, #87ddb2);
}
.tour-actions {
  justify-self: end;
  display: flex;
  gap: 9px;
}
.tour-actions button {
  height: 44px;
  border-radius: 13px;
  color: #fff;
  font-weight: 800;
  cursor: pointer;
}
.previous {
  width: 46px;
  border: 1px solid rgb(255 255 255 / 12%);
  background: rgb(255 255 255 / 6%);
}
.next {
  min-width: 118px;
  padding: 0 16px;
  border: 0;
  background: linear-gradient(135deg, #665fe9, #7770f5);
  box-shadow: 0 12px 28px rgb(91 86 232 / 25%);
}
.previous:disabled {
  opacity: 0.32;
  cursor: default;
}
button:focus-visible {
  outline: 3px solid rgb(150 145 255 / 65%);
  outline-offset: 3px;
}
@media (max-width: 820px) {
  .release-mask {
    padding: 0;
  }
  .release-tour {
    width: 100%;
    height: 100dvh;
    min-height: 560px;
    border: 0;
    border-radius: 0;
  }
  .tour-header {
    height: 64px;
    padding: 0 16px;
  }
  .tour-brand em,
  .later {
    display: none;
  }
  .tour-stage {
    inset: 64px 0 88px;
    overflow-x: hidden;
    overflow-y: auto;
  }
  .slide {
    height: auto;
    min-height: 100%;
    grid-template-columns: 1fr;
    align-content: start;
    gap: 20px;
    padding: 28px 22px 22px;
  }
  .intro,
  .finish {
    align-content: center;
  }
  .intro-orbit {
    width: 118px;
    height: 118px;
    margin-bottom: 14px;
  }
  .copy h2 {
    font-size: 36px;
  }
  .copy p {
    font-size: 13px;
  }
  .feature-visual {
    order: -1;
    min-height: 260px;
  }
  .visual-window {
    transform: none;
  }
  .visual-content {
    min-height: 220px;
    grid-template-columns: 78px 1fr;
  }
  .visual-sidebar {
    padding: 17px 11px;
  }
  .visual-main {
    padding: 26px 20px;
  }
  .visual-main b {
    font-size: 18px;
  }
  .number {
    margin-bottom: 14px;
  }
  .tour-footer {
    height: 88px;
    grid-template-columns: 1fr auto;
    padding: 0 16px;
  }
  .step-copy {
    display: none;
  }
  .dots {
    justify-self: start;
  }
  .next {
    min-width: 98px;
  }
  .finish-grid {
    grid-template-columns: 1fr;
  }
  .finish-actions {
    flex-wrap: wrap;
  }
}
@media (max-width: 430px) {
  .slide {
    padding-inline: 18px;
  }
  .copy h2 {
    font-size: 30px;
  }
  .feature-copy ul {
    display: none;
  }
  .feature-visual {
    min-height: 236px;
  }
  .visual-sidebar {
    display: none;
  }
  .visual-content {
    grid-template-columns: 1fr;
  }
  .dots button {
    width: 14px;
  }
  .dots button.active {
    width: 25px;
  }
  .finish-card {
    padding: 23px 18px;
  }
}
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    scroll-behavior: auto !important;
    animation: none !important;
    transition-duration: 0.01ms !important;
  }
  .visual-window {
    transform: none;
  }
}
</style>
