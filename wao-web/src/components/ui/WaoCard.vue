<script setup lang="ts">
withDefaults(defineProps<{ variant?: 'outline' | 'soft'; padding?: 'normal' | 'roomy' }>(), {
  variant: 'outline',
  padding: 'normal',
})
</script>
<template>
  <div class="wao-card" :class="[`wao-card--${variant}`, `wao-card--${padding}`]"><slot /></div>
</template>
<style scoped>
.wao-card {
  min-width: 0;
  padding: var(--wao-space-2);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  box-shadow: none;
}
.wao-card--soft {
  background: var(--wao-bg-sub);
  border-color: transparent;
}
.wao-card--roomy {
  padding: var(--wao-space-3);
}
</style>
