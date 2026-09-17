<script setup lang="ts">
import { computed, watch } from 'vue'
import { useFloatingDrag } from '@/composables/floatingDrag'
import { useFloatingTabsStore } from '@/stores/floatingTabs'

const props = defineProps<{ pageKey: string; label: string; floating: boolean; visible: boolean }>()
defineEmits<{ dock: [] }>()
const windows = useFloatingTabsStore()
const { elementRef, style, dragging, onPointerDown } = useFloatingDrag(
  `wao-tab-window-${props.pageKey}`,
)
const windowStyle = computed(() =>
  props.floating
    ? {
        ...style.value,
        zIndex: windows.front === props.pageKey ? 112 : 111,
        maxWidth: `calc(100vw - ${style.value?.left || '24px'} - 8px)`,
        maxHeight: `calc(100dvh - ${style.value?.top || '90px'} - 8px)`,
      }
    : undefined,
)
watch(
  () => props.floating,
  (floating) => {
    if (!floating && elementRef.value) {
      elementRef.value.style.removeProperty('width')
      elementRef.value.style.removeProperty('height')
    }
  },
  { flush: 'post' },
)
function startDrag(event: PointerEvent) {
  if (!props.floating || !(event.target instanceof Element)) return
  windows.front = props.pageKey
  if (event.target.closest('button') || !event.target.closest('.window-title')) return
  onPointerDown(event)
}
</script>

<template>
  <Teleport to="body" :disabled="!floating">
    <section
      ref="elementRef"
      v-show="visible"
      class="tab-page-window"
      :class="{ floating, dragging }"
      :style="windowStyle"
      :aria-label="floating ? `${label}悬浮窗口` : undefined"
      :role="floating ? 'dialog' : undefined"
      @pointerdown="startDrag"
    >
      <header v-if="floating" class="window-title">
        <i class="ri-drag-move-2-line" aria-hidden="true"></i>
        <span :title="label">{{ label }}</span>
        <button type="button" title="收回 Tab，保留页面内容" @click="$emit('dock')">
          <i class="ri-login-box-line"></i>收回 Tab
        </button>
      </header>
      <div class="window-content"><slot /></div>
    </section>
  </Teleport>
</template>

<style scoped>
.tab-page-window {
  min-width: 0;
  min-height: 0;
  height: 100%;
}
.window-content {
  min-width: 0;
  min-height: 0;
  height: 100%;
}
.floating {
  position: fixed;
  top: 90px;
  left: 24px;
  width: min(640px, calc(100vw - 32px));
  height: min(640px, calc(100dvh - 110px));
  min-width: min(320px, calc(100vw - 16px));
  min-height: 160px;
  max-width: calc(100vw - 16px);
  max-height: calc(100dvh - 16px);
  display: flex;
  flex-direction: column;
  resize: both;
  overflow: hidden;
  border: 1px solid var(--wao-line-control);
  border-radius: 12px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
}
.floating .window-content {
  flex: 1;
  overflow: auto;
  padding: 14px;
}
.window-title {
  flex: 0 0 42px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border-bottom: 1px solid var(--wao-line);
  cursor: move;
  touch-action: none;
  user-select: none;
}
.window-title span {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.window-title button {
  flex-shrink: 0;
  border: 0;
  background: var(--wao-bg);
  color: var(--wao-text);
  border-radius: 6px;
  padding: 5px 8px;
  cursor: pointer;
}
.dragging {
  user-select: none;
}
@media (max-width: 760px) {
  .floating {
    top: 64px;
    left: 8px;
    width: calc(100vw - 16px);
    height: 55dvh;
  }
}
</style>
