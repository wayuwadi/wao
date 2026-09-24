<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import ChatView from '@/views/ChatView.vue'
import { useUiStore } from '@/stores/ui'
import { useFloatingDrag } from '@/composables/floatingDrag'

withDefaults(defineProps<{ showTrigger?: boolean }>(), { showTrigger: true })

const route = useRoute()
const ui = useUiStore()
const trigger = ref<HTMLElement | null>(null)
const panel = ref<HTMLElement | null>(null)
const unreadCount = computed(() => ui.chatUnreadCount)
const {
  elementRef: draggablePanel,
  style: panelStyle,
  dragging: panelDragging,
  onPointerDown: startPanelDrag,
} = useFloatingDrag('wao-floating-chat-position-v1')

function setPanelRef(element: unknown) {
  panel.value = element instanceof HTMLElement ? element : null
  draggablePanel.value = panel.value
}

function onPanelPointerDown(event: PointerEvent) {
  if (window.innerWidth <= 760 || !(event.target instanceof Element)) return
  if (!event.target.closest('.chat-header')) return
  if (event.target.closest('button, input, select, textarea, a, [contenteditable="true"]')) return
  startPanelDrag(event)
}

function onPointerDown(event: Event) {
  if (!ui.chatOpen || !(event.target instanceof Node)) return
  if (panel.value?.contains(event.target) || trigger.value?.contains(event.target)) return
  // 复盘弹层由聊天悬浮框发起但 Teleport 到 body；操作复盘时不能误把底层聊天最小化。
  if (event.target instanceof Element && event.target.closest('.review-mask, .chat-image-menu-mask, [data-chat-trigger]')) return
  ui.closeChat()
}

// Native toolbar and keyboard activation produce click without pointerdown.
function onToolbarClick(event: MouseEvent) {
  if (event.target instanceof Element && event.target.closest('[data-desktop-action]')) {
    onPointerDown(event)
  }
}

watch(
  () => [route.query.chat, route.query.review] as const,
  ([chat, review]) => {
    if (chat === '1' || typeof review === 'string') {
      ui.openChat(typeof review === 'string' ? review : undefined)
    }
  },
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown, true)
  document.addEventListener('click', onToolbarClick, true)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onPointerDown, true)
  document.removeEventListener('click', onToolbarClick, true)
})
</script>

<template>

    <button
      v-if="showTrigger"
      ref="trigger"
      class="chat-entry"
      :class="{ active: ui.chatOpen }"
      type="button"
      data-desktop-action="chat"
      data-chat-trigger
      :aria-expanded="ui.chatOpen"
      aria-controls="wao-floating-chat"
      :aria-label="unreadCount ? `聊天（${unreadCount} 条未读）` : '聊天'"
      :title="unreadCount ? `聊天（${unreadCount} 条未读）` : '聊天'"
      @click="ui.toggleChat()"
    >
      <i class="ri-chat-3-line" aria-hidden="true"></i>
      <span v-if="unreadCount" class="chat-badge">{{
        unreadCount > 99 ? '99+' : unreadCount
      }}</span>
    </button>

  <Teleport to="body">
    <aside
      id="wao-floating-chat"
      :ref="setPanelRef"
      v-show="ui.chatOpen"
      class="floating-chat"
      :class="{ dragging: panelDragging }"
      :style="panelStyle"
      role="dialog"
      aria-label="共享聊天"
      @pointerdown="onPanelPointerDown"
    >
      <ChatView
        floating
        :visible="ui.chatOpen"
        :review-id="ui.chatReviewId"
        :review-request-version="ui.chatReviewRequestVersion"
        @minimize="ui.closeChat()"
        @unread-change="ui.chatUnreadCount = $event"
      />
    </aside>
  </Teleport>
</template>

<style scoped>
.chat-slot {
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
}
.chat-entry {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: none;
  color: var(--wao-text-3);
  cursor: pointer;
  display: grid;
  place-items: center;
  position: relative;
  flex-shrink: 0;
  background: var(--wao-bg-card);
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.12);
}
.chat-entry:hover,
.chat-entry.active {
  background: var(--wao-bg-hover-2);
  color: var(--wao-primary-text);
}
.chat-entry i {
  font-size: 17px;
}
.chat-badge {
  position: absolute;
  top: -3px;
  right: -5px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border: 2px solid var(--wao-bg-card);
  border-radius: 999px;
  background: var(--wao-danger);
  color: #fff;
  font-size: 8px;
  font-weight: 700;
  line-height: 12px;
  box-sizing: border-box;
}
.floating-chat {
  position: fixed;
  z-index: 1101;
  top: calc(var(--wao-header-h) + 8px);
  right: 16px;
  width: min(720px, calc(100vw - 32px));
  height: min(760px, calc(100dvh - var(--wao-header-h) - 24px));
  min-height: min(520px, calc(100dvh - var(--wao-header-h) - 24px));
  overflow: hidden;
  border: 1px solid var(--wao-line);
  border-radius: 18px;
  background: var(--wao-bg-card);
  box-shadow: 0 24px 64px rgba(15, 23, 42, 0.24);
}
.floating-chat :deep(.chat-header) {
  cursor: grab;
  user-select: none;
}
.floating-chat.dragging,
.floating-chat.dragging :deep(.chat-header) {
  cursor: grabbing;
}
@media (max-width: 760px) {
  .chat-slot {
    width: 36px;
    flex-basis: 36px;
  }
  .chat-entry {
    width: 36px;
    height: 36px;
  }
  .floating-chat {
    inset: 0 !important;
    width: 100vw;
    height: 100dvh;
    min-height: 0;
    border: 0;
    border-radius: 0;
  }
  .floating-chat :deep(.chat-header) {
    cursor: default;
  }
}
</style>
