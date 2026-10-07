<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
    type?: 'button' | 'submit' | 'reset'
    disabled?: boolean
    loading?: boolean
  }>(),
  { variant: 'secondary', type: 'button', disabled: false, loading: false },
)
</script>
<template>
  <button
    class="wao-button"
    :class="`wao-button--${variant}`"
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
  >
    <i v-if="loading" class="ri-loader-4-line" aria-hidden="true"></i><slot />
  </button>
</template>
<style scoped>
.wao-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--wao-space-1);
  min-height: var(--wao-control-height);
  padding: 8px 16px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-size: var(--wao-font-body);
  line-height: 1.5;
  cursor: pointer;
}
.wao-button:hover:not(:disabled) {
  background: var(--wao-bg-hover);
}
.wao-button--primary {
  color: var(--wao-on-primary);
  background: var(--wao-primary);
  border-color: var(--wao-primary);
}
.wao-button--primary:hover:not(:disabled) {
  background: var(--wao-primary-hover);
}
.wao-button--ghost {
  background: transparent;
  border-color: transparent;
  color: var(--wao-primary-text);
}
.wao-button--danger {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  border-color: var(--wao-danger-line);
}
.wao-button--danger:hover:not(:disabled) {
  background: var(--wao-danger-bg);
  border-color: var(--wao-danger-strong);
}
.wao-button i {
  font-size: 16px;
}
.wao-button[aria-busy='true'] i {
  animation: wao-button-spin 1s linear infinite;
}
@keyframes wao-button-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (pointer: coarse) {
  .wao-button {
    min-height: 44px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .wao-button[aria-busy='true'] i {
    animation: none;
  }
}
</style>
