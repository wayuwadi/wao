<script setup lang="ts">
import { computed, watch } from 'vue'
import { useUiStore } from '@/stores/ui'
const emit = defineEmits<{ open: []; visible: [value: boolean] }>()
const ui = useUiStore()
const visible = computed(() => !!ui.chatLatestUnread && ui.chatUnreadCount > 0 && !ui.chatOpen)
watch(visible, value => emit('visible', value), { immediate: true })
function open() {
  emit('open')
  if (location.pathname !== '/desktop-pet') ui.openChat()
}
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
