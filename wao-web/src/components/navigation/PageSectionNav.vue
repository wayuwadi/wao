<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

interface SectionItem {
  id: string
  label: string
  element: HTMLElement
}

const props = defineProps<{
  root: HTMLElement | null
  scrollContainer: HTMLElement | null
}>()

const items = ref<SectionItem[]>([])
const activeId = ref('')
let observer: MutationObserver | undefined
let frame = 0

function sameItems(next: SectionItem[]) {
  return (
    next.length === items.value.length &&
    next.every(
      (item, index) =>
        item.id === items.value[index]?.id && item.label === items.value[index]?.label,
    )
  )
}

function updateActive() {
  frame = 0
  if (!items.value.length) {
    activeId.value = ''
    return
  }
  const containerTop = props.scrollContainer?.getBoundingClientRect().top ?? 0
  const threshold = containerTop + 32
  let current = items.value[0]!
  for (const item of items.value) {
    if (item.element.getBoundingClientRect().top <= threshold) current = item
    else break
  }
  activeId.value = current.id
}

function scheduleActiveUpdate() {
  if (frame) cancelAnimationFrame(frame)
  frame = requestAnimationFrame(updateActive)
}

function scan() {
  const next = Array.from(props.root?.querySelectorAll<HTMLElement>('[data-page-anchor][id]') ?? [])
    .filter((element) => !element.hidden && element.getAttribute('aria-hidden') !== 'true')
    .map((element) => ({
      id: element.id,
      label: element.dataset.pageAnchor?.trim() ?? '',
      element,
    }))
    .filter((item) => item.label)
  if (!sameItems(next)) items.value = next
  scheduleActiveUpdate()
}

function observeRoot() {
  observer?.disconnect()
  observer = undefined
  items.value = []
  activeId.value = ''
  if (!props.root) return
  scan()
  observer = new MutationObserver(scan)
  observer.observe(props.root, { childList: true, subtree: true })
}

function goTo(item: SectionItem) {
  const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  item.element.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
  activeId.value = item.id
}

watch(
  () => props.root,
  () => void nextTick(observeRoot),
  { immediate: true },
)
watch(
  () => props.scrollContainer,
  (current, previous) => {
    previous?.removeEventListener('scroll', scheduleActiveUpdate)
    current?.addEventListener('scroll', scheduleActiveUpdate, { passive: true })
    scheduleActiveUpdate()
  },
  { immediate: true },
)

window.addEventListener('resize', scheduleActiveUpdate, { passive: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  props.scrollContainer?.removeEventListener('scroll', scheduleActiveUpdate)
  window.removeEventListener('resize', scheduleActiveUpdate)
  if (frame) cancelAnimationFrame(frame)
})
</script>

<template>
  <nav v-if="items.length >= 3" class="page-section-nav" aria-label="本页内容">
    <div class="page-section-title"><i class="ri-list-check-3"></i>本页内容</div>
    <button
      v-for="item in items"
      :key="item.id"
      type="button"
      :class="{ active: activeId === item.id }"
      :aria-current="activeId === item.id ? 'location' : undefined"
      @click="goTo(item)"
    >
      <i aria-hidden="true"></i><span>{{ item.label }}</span>
    </button>
  </nav>
</template>

<style scoped>
.page-section-nav {
  position: sticky;
  top: 16px;
  width: 148px;
  max-height: calc(100dvh - var(--wao-header-h) - var(--wao-tabbar-h) - 32px);
  margin: 16px 0 20px 12px;
  padding: 11px 9px;
  overflow-y: auto;
  flex: 0 0 148px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: color-mix(in srgb, var(--wao-bg-card) 94%, transparent);
  box-shadow: var(--wao-shadow-card-hover-2);
  backdrop-filter: blur(12px);
}
.page-section-title {
  padding: 2px 7px 8px;
  color: var(--wao-text-weak);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
}
.page-section-title i {
  margin-right: 5px;
  font-size: 12px;
}
.page-section-nav button {
  position: relative;
  width: 100%;
  min-height: 30px;
  padding: 6px 7px 6px 16px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
  line-height: 1.35;
  text-align: left;
}
.page-section-nav button:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-text);
}
.page-section-nav button > i {
  position: absolute;
  left: 6px;
  top: 50%;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--wao-line-strong);
  transform: translateY(-50%);
}
.page-section-nav button.active {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-weight: 650;
}
.page-section-nav button.active > i {
  background: var(--wao-primary);
  box-shadow: 0 0 0 3px var(--wao-primary-soft-2);
}
@media (max-width: 1180px) {
  .page-section-nav {
    display: none;
  }
}
</style>
