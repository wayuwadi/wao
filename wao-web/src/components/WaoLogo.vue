<script setup lang="ts">
withDefaults(defineProps<{ wordmark?: boolean; tone?: 'brand' | 'current' }>(), { wordmark: false, tone: 'brand' })
// 使用运行时的主站公共路径，避免品牌资源被改写到仅开放 assets 的版本目录。
const logoUrl = '/brand/wao-soft-loop-v4.svg'
</script>

<template>
  <span class="wao-logo" :class="{ 'wao-logo-current': tone === 'current' }" aria-label="WAO" role="img">
    <span v-if="tone === 'current'" class="wao-logo-symbol wao-logo-mask" aria-hidden="true"></span>
    <img v-else class="wao-logo-symbol" :src="logoUrl" alt="" aria-hidden="true" width="32" height="32" />
    <span v-if="wordmark" class="wao-logo-name" aria-hidden="true"><svg viewBox="0 0 76 28" focusable="false"><path d="M3 5 L8.5 22 Q9.3 24.5 10.5 21.5 L16 8 L21.5 21.5 Q22.7 24.5 23.5 22 L29 5 M35 23 L42 6 Q43 3.5 44 6 L51 23 M38 16.5 H48 M73 14 C73 7 70 4 64 4 C58 4 55 7 55 14 C55 21 58 24 64 24 C70 24 73 21 73 14 Z" fill="none" stroke="currentColor" stroke-width="3.1" stroke-linecap="round" stroke-linejoin="round" /></svg></span>
  </span>
</template>

<style scoped>
.wao-logo { display: inline-flex; align-items: center; gap: 8px; flex-shrink: 0; vertical-align: middle; }
.wao-logo-symbol { display: block; width: 32px; height: 32px; flex-shrink: 0; }
.wao-logo-mask { background: currentColor; mask: url('/brand/wao-soft-loop-mono-v4.svg') center / contain no-repeat; }
.wao-logo-name { color: inherit; font-size: 17px; line-height: 1; }
.wao-logo-name svg { display:block; width:3.1em; height:1.15em; overflow:visible; }
</style>
