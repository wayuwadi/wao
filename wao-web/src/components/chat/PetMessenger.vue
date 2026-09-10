<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
const emit = defineEmits<{ open: []; visible: [value: boolean] }>()
const ui = useUiStore()
const session = useSessionStore()
const visible = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
const messageKey = computed(() =>
  ui.chatLatestUnread ? `${session.userId}:${ui.chatLatestUnread.id}` : '',
)
watch(
  messageKey,
  (key) => {
    clearTimeout(timer)
    visible.value = false
    if (!key || ui.chatOpen || !ui.chatUnreadCount) return
    const storageKey = `wao-pet-message-seen:${session.userId}`
    try {
      if (localStorage.getItem(storageKey) === key) return
      localStorage.setItem(storageKey, key)
    } catch {
      /* The current component still shows each message only once. */
    }
    visible.value = true
    timer = setTimeout(() => {
      visible.value = false
    }, 8000)
  },
  { immediate: true },
)
watch(
  () => ui.chatOpen,
  (open) => {
    if (open) visible.value = false
  },
)
watch(visible, (value) => emit('visible', value), { immediate: true })
function open() {
  visible.value = false
  emit('open')
  if (location.pathname !== '/desktop-pet') ui.openChat()
}
onBeforeUnmount(() => clearTimeout(timer))
</script>
<template>
  <button v-if="visible && !ui.chatOpen" class="pet-messenger" @pointerdown.stop @click="open">
    <i class="ri-chat-3-line"></i> 有新消息，点击查看
  </button>
</template>
<style scoped>
.pet-messenger {
  max-width: 220px;
  padding: 10px 14px;
  border: 1px solid var(--wao-line);
  border-radius: 14px 14px 4px 14px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  box-shadow: 0 8px 24px #14203020;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}
</style>
