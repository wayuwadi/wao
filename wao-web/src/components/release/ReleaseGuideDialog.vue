<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import CompanionAvatar from '@/components/companions/CompanionAvatar.vue'
import { selectCompanion } from '@/utils/companionIdentity'
import { companionMember } from '@/composables/quietCompanions'
import type { ReleaseHighlight } from '@/types/featureExplorer'
import ReleaseFeatureDemo from './ReleaseFeatureDemo.vue'
const props = defineProps<{ item: ReleaseHighlight; editionId: string }>()
const identity = computed(() =>
  selectCompanion(`release:${props.editionId}`, companionMember.value),
)
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLElement>()
const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
const previousOverflow = document.body.style.overflow
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopImmediatePropagation()
    emit('close')
  }
  if (event.key !== 'Tab') return
  const buttons = Array.from(
    dialog.value?.querySelectorAll<HTMLElement>('button, a[href], input:not([disabled])') ?? [],
  ).filter((el) => el.getClientRects().length)
  const first = buttons[0],
    last = buttons[buttons.length - 1]
  if (
    event.shiftKey &&
    (document.activeElement === first || !dialog.value?.contains(document.activeElement))
  ) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}
onMounted(() => {
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', keydown, true)
  void nextTick(() => dialog.value?.querySelector<HTMLButtonElement>('.guide-close')?.focus())
})
onBeforeUnmount(() => {
  document.body.style.overflow = previousOverflow
  window.removeEventListener('keydown', keydown, true)
  previousFocus?.focus()
})
</script>
<template>
  <Teleport to="body"
    ><div class="guide-mask" @click.self="emit('close')">
      <section
        ref="dialog"
        class="guide-window"
        role="dialog"
        aria-modal="true"
        aria-labelledby="release-guide-title"
      >
        <header>
          <CompanionAvatar :identity="identity" :size="40" /><span>看看怎么用</span
          ><button
            type="button"
            class="guide-close"
            aria-label="关闭功能演示"
            @click="emit('close')"
          >
            <i class="ri-close-line" aria-hidden="true"></i>
          </button>
        </header>
        <div class="guide-body">
          <div class="guide-copy">
            <span>{{ item.eyebrow }}</span>
            <h2 id="release-guide-title">{{ item.title }}</h2>
            <p>{{ item.summary }}</p>
            <ol v-if="item.steps?.length">
              <li v-for="step in item.steps" :key="step">{{ step }}</li>
            </ol>
          </div>
          <ReleaseFeatureDemo v-if="item.demo" :demo="item.demo" :edition-id="editionId" />
        </div>
        <footer><button type="button" @click="emit('close')">返回更新卡片</button></footer>
      </section>
    </div></Teleport
  >
</template>
<style scoped>
.guide-mask {
  position: fixed;
  inset: 0;
  z-index: 1300;
  padding: 24px;
  display: grid;
  place-items: center;
  background: #11151b99;
  backdrop-filter: blur(12px);
}
.guide-window {
  display: flex;
  flex-direction: column;
  width: min(920px, 100%);
  max-height: calc(100dvh - 48px);
  border: 1px solid #ffffff20;
  border-radius: 26px;
  overflow: hidden;
  background:
    radial-gradient(ellipse at top left, #27243c, transparent 70%),
    radial-gradient(ellipse at bottom right, #193429, transparent 70%), #101411;
  color: #fff;
  box-shadow: 0 30px 90px #0005;
}
header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 22px;
  border-bottom: 1px solid #ffffff14;
  font-size: 13px;
  flex: none;
}
button {
  border: 1px solid #ffffff20;
  background: #ffffff0e;
  color: #fff;
  border-radius: 10px;
  padding: 10px 14px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.guide-close {
  margin-left: auto;
  padding: 8px;
  width: 36px;
  height: 36px;
}
.guide-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
  gap: 30px;
  align-items: center;
  padding: 36px;
  overflow: auto;
  min-height: 0;
}
.guide-copy > span {
  color: #a3e3c6;
  font-size: 12px;
}
h2 {
  font-size: 28px;
  line-height: 1.4;
  margin: 14px 0;
  overflow-wrap: anywhere;
}
p,
li {
  color: #c1c7c3;
  font-size: 13px;
  line-height: 1.85;
}
ol {
  padding-left: 20px;
}
footer {
  display: flex;
  justify-content: flex-end;
  padding: 16px 22px;
  border-top: 1px solid #ffffff14;
  flex: none;
}
button:focus-visible {
  outline: 2px solid #b4a3ff;
  outline-offset: 3px;
}
@media (max-width: 600px) {
  .guide-mask {
    padding: 12px;
  }
  .guide-window {
    max-height: calc(100dvh - 24px);
    border-radius: 18px;
  }
  .guide-body {
    grid-template-columns: 1fr;
    padding: 20px;
    gap: 20px;
  }
  h2 {
    font-size: 21px;
  }
}
@media (pointer: coarse) {
  button {
    min-height: 44px;
  }
  .guide-close {
    min-width: 44px;
  }
}
</style>
