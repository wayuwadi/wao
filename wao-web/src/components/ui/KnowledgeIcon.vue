<script setup lang="ts">
defineProps<{ kind: 'doc' | 'folder' | 'template' | 'table' }>()
</script>
<template>
  <svg class="knowledge-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-if="kind === 'folder'"><path d="M3 6h7l2 3h9v11H3z" /><path d="M3 6V4h7l2 2h7v3" /></template>
    <template v-else-if="kind === 'table'"><rect x="3" y="4" width="18" height="16" rx="1" /><path d="M3 9h18M3 14h18M9 9v11M15 9v11" /></template>
    <template v-else><path d="M6 3h8l5 5v13H6zM14 3v6h5M9 13h7M9 17h5" /><path v-if="kind === 'template'" d="M3 7v15h12" /></template>
  </svg>
</template>
<style scoped>
.knowledge-icon { width: 18px; height: 18px; flex: 0 0 18px; vertical-align: middle; }
</style>
