<script setup lang="ts">
withDefaults(defineProps<{ primaryLabel?: string; secondaryLabel?: string }>(), {
  primaryLabel: '当天任务',
  secondaryLabel: '日常与小记',
})
</script>
<template>
  <div class="todo-day-layout">
    <div class="day-columns">
      <section class="day-primary" :aria-label="primaryLabel"><slot /></section>
      <aside class="day-secondary" :aria-label="secondaryLabel"><slot name="secondary" /></aside>
    </div>
  </div>
</template>
<style scoped>
.todo-day-layout { container: todo-day-layout / inline-size; min-width: 0; }
.day-columns { display: grid; grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr); align-items: start; gap: var(--wao-space-3); }
.day-primary, .day-secondary { display: grid; min-width: 0; gap: var(--wao-space-2); }
@container todo-day-layout (max-width: 800px) { .day-columns { grid-template-columns: minmax(0, 1fr); } }
</style>
