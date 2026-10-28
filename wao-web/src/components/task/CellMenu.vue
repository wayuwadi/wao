<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 表格单元格的悬浮下拉菜单。
 *
 * <p>菜单挂到 body 上、按触发单元格的位置固定定位：以前菜单直接放在单元格里，
 * 会撑开表格的滚动区域，看起来像是表格多出几行选项。挂出去后表格不再跟着变形。
 * 第一次按 Esc、点菜单外任意处或滚动页面都会收起，Esc 不会再冒泡去关掉外层弹框。
 */
defineOptions({ inheritAttrs: false })
const props = defineProps<{ anchor: HTMLElement | null; minWidth?: number; customContent?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const menu = ref<HTMLElement | null>(null)
const theme = ref<string | undefined>()
const style = ref<Record<string, string>>({ visibility: 'hidden' })

function place() {
  const anchor = props.anchor
  const el = menu.value
  if (!anchor || !el) return
  theme.value = anchor.closest('[data-wao-theme]')?.getAttribute('data-wao-theme') ?? undefined
  const rect = anchor.getBoundingClientRect()
  const height = el.offsetHeight
  const width = Math.max(el.offsetWidth, props.minWidth ?? 0)
  const below = rect.bottom + 4
  const preferredTop = below + height > window.innerHeight - 8 && rect.top - height - 4 > 8 ? rect.top - height - 4 : below
  const top = Math.max(8, Math.min(preferredTop, window.innerHeight - height - 8))
  const left = Math.min(Math.max(8, rect.left), window.innerWidth - width - 8)
  style.value = { top: `${top}px`, left: `${left}px`, minWidth: `${Math.max(rect.width, props.minWidth ?? 0)}px` }
}
function onKey(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  event.preventDefault()
  event.stopPropagation()
  emit('close')
}
function onPointer(event: MouseEvent) {
  const target = event.target as Node | null
  if (target && (menu.value?.contains(target) || props.anchor?.contains(target))) return
  emit('close')
}
function onScroll(event: Event) {
  if (menu.value && event.target instanceof Node && menu.value.contains(event.target)) return
  emit('close')
}
onMounted(() => {
  nextTick(place)
  window.addEventListener('keydown', onKey, true)
  window.addEventListener('mousedown', onPointer, true)
  window.addEventListener('scroll', onScroll, true)
  window.addEventListener('resize', place)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey, true)
  window.removeEventListener('mousedown', onPointer, true)
  window.removeEventListener('scroll', onScroll, true)
  window.removeEventListener('resize', place)
})
</script>

<template>
  <Teleport to="body">
    <div ref="menu" v-bind="$attrs" class="cell-menu" :data-wao-theme="theme" :class="{ 'custom-content': customContent }" :role="customContent ? undefined : 'menu'" :style="style" @click.stop>
      <slot />
    </div>
  </Teleport>
</template>

<style scoped>
.cell-menu {
  position: fixed;
  z-index: 1200;
  max-height: 280px;
  overflow-y: auto;
  padding: 4px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(16, 18, 28, 0.14);
}
.cell-menu:not(.custom-content) :slotted(button) {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border: none;
  background: none;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  color: var(--wao-text-2);
  text-align: left;
  white-space: nowrap;
}
.cell-menu:not(.custom-content) :slotted(button > i) {
  color: var(--wao-primary-text);
}
.cell-menu:not(.custom-content) :slotted(button:hover),
.cell-menu:not(.custom-content) :slotted(button.on) {
  background: var(--wao-bg-hover);
}
</style>
