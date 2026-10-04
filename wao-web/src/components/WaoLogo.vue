<script setup lang="ts">
withDefaults(defineProps<{ wordmark?: boolean }>(), { wordmark: false })
// 使用运行时的主站公共路径，避免品牌资源被改写到仅开放 assets 的版本目录。
const logoUrl = '/brand/wao-soft-loop-v3.svg'
</script>

<template>
  <span class="wao-logo" aria-label="WAO" role="img">
    <img class="wao-logo-symbol" :src="logoUrl" alt="" aria-hidden="true" width="32" height="32" />
    <span v-if="wordmark" class="wao-logo-name" aria-hidden="true">WAO</span>
  </span>
</template>

<style scoped>
.wao-logo { display: inline-flex; align-items: center; gap: 8px; flex-shrink: 0; vertical-align: middle; }
.wao-logo-symbol { display: block; width: 32px; height: 32px; flex-shrink: 0; }
.wao-logo-name { color: inherit; font-size: 17px; line-height: 1; font-weight: 750; letter-spacing: .04em; }
</style>
