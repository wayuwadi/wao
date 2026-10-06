<script setup lang="ts">
import { brandMonoMask, brandWordmark } from '@/config/brand'
withDefaults(defineProps<{ wordmark?: boolean; tone?: 'brand' | 'current' }>(), { wordmark: false, tone: 'brand' })
// 使用运行时的主站公共路径，避免品牌资源被改写到仅开放 assets 的版本目录。
const logoUrl = '/brand/wao-soft-loop-v4.svg'
</script>

<template>
  <span class="wao-logo" :class="{ 'wao-logo-current': tone === 'current' }" aria-label="WAO" role="img">
    <span v-if="tone === 'current'" class="wao-logo-symbol wao-logo-mask" :style="{ maskImage: brandMonoMask }" aria-hidden="true"></span>
    <img v-else class="wao-logo-symbol" :src="logoUrl" alt="" aria-hidden="true" width="32" height="32" />
    <span v-if="wordmark" class="wao-logo-name" aria-hidden="true"><svg :viewBox="brandWordmark.viewBox" focusable="false"><path :d="brandWordmark.path" fill="none" stroke="currentColor" :stroke-width="brandWordmark.strokeWidth" stroke-linecap="round" stroke-linejoin="round" /></svg></span>
  </span>
</template>

<style scoped>
.wao-logo { display: inline-flex; align-items: center; gap: 8px; flex-shrink: 0; vertical-align: middle; }
.wao-logo-symbol { display: block; width: 32px; height: 32px; flex-shrink: 0; }
.wao-logo-mask { background: currentColor; mask-position: center; mask-size: contain; mask-repeat: no-repeat; }
.wao-logo-name { color: inherit; font-size: 17px; line-height: 1; }
.wao-logo-name svg { display:block; width:3.1em; height:1.15em; overflow:visible; }
</style>
