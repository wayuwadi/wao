<script setup lang="ts">
import { useToastHost } from '@/composables/toast'

const host = useToastHost()
</script>

<template>
  <div class="toast-host">
    <div v-for="t in host.items" :key="t.id" class="toast">
      <i :class="t.icon"></i><span>{{ t.text }}</span>
    </div>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  top: 18px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 200;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}
.toast {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #23262f;
  color: #fff;
  border-radius: 999px;
  padding: 9px 16px;
  font-size: 13px;
  box-shadow: 0 8px 24px rgba(16, 18, 28, 0.28);
  animation: waoToast 0.2s ease;
  white-space: nowrap;
}
.toast i {
  font-size: 15px;
  opacity: 0.9;
}
</style>
