<script setup lang="ts">
withDefaults(defineProps<{ time: string; progress?: number; label?: string }>(), { progress: 0, label: '专注' })
</script>
<template>
  <div class="focus-dial" role="timer" :aria-label="`${label} ${time}`">
    <svg viewBox="0 0 240 240" aria-hidden="true"><circle class="dial-track" cx="120" cy="120" r="112" /><circle class="dial-progress" cx="120" cy="120" r="112" pathLength="100" :stroke-dasharray="`${Math.min(100, Math.max(0, progress))} 100`" /></svg>
    <div><small>{{ label }}</small><strong>{{ time }}</strong></div>
  </div>
</template>
<style scoped>
.focus-dial { width: min(100%, 340px); aspect-ratio: 1; position: relative; margin: var(--wao-space-3) auto; }
svg { width: 100%; height: 100%; transform: rotate(-90deg); fill: none; stroke-width: 3; }
.dial-track { stroke: var(--wao-line); }
.dial-progress { stroke: var(--wao-primary); stroke-linecap: round; }
.focus-dial>div { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: var(--wao-space-2); }
small { color: var(--wao-text-3); font-size: var(--wao-font-body); }
strong { color: var(--wao-text); font-size: clamp(36px, 7vw, 56px); font-weight: 500; font-variant-numeric: tabular-nums; }
</style>
