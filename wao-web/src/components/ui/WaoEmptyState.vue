<script setup lang="ts">
withDefaults(defineProps<{ title: string; description?: string; icon?: string }>(), {
  description: '', icon: 'ri-inbox-line',
})
</script>

<template>
  <section class="wao-empty-state" :aria-label="title">
    <i :class="icon" aria-hidden="true"></i>
    <strong>{{ title }}</strong>
    <p v-if="description">{{ description }}</p>
    <slot />
    <div v-if="$slots.actions" class="empty-actions"><slot name="actions" /></div>
  </section>
</template>

<style scoped>
.wao-empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--wao-space-1);
  padding: var(--wao-space-4) var(--wao-space-2);
  text-align: center;
  color: var(--wao-text-3);
  overflow-wrap: anywhere;
  font-size: var(--wao-font-body);
}
.wao-empty-state > i { font-size: var(--wao-font-title); }
.wao-empty-state strong { color: var(--wao-text); font-size: var(--wao-font-card-title); }
.wao-empty-state p { margin: 0; max-width: 48ch; }
.empty-actions { display: flex; flex-wrap: wrap; justify-content: center; gap: var(--wao-space-1); margin-top: var(--wao-space-1); }
</style>
