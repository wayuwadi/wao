<script setup lang="ts">
withDefaults(defineProps<{ wordmark?: boolean }>(), { wordmark: false })
</script>

<template>
  <span class="wao-logo" aria-label="WAO" role="img">
    <svg class="wao-logo-symbol" viewBox="40 300 1200 660" aria-hidden="true" focusable="false">
      <image href="/brand/wao-ribbon-512-v1.png" width="1280" height="1280" />
    </svg>
    <span v-if="wordmark" class="wao-logo-name" aria-hidden="true">WAO</span>
  </span>
</template>

<style scoped>
.wao-logo { display: inline-flex; align-items: center; gap: 8px; flex-shrink: 0; vertical-align: middle; }
.wao-logo-symbol { display: block; width: 42px; height: 26px; flex-shrink: 0; overflow: hidden; }
.wao-logo-name { color: inherit; font-size: 17px; line-height: 1; font-weight: 750; letter-spacing: .04em; }
</style>
