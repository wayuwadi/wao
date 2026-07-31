<script setup lang="ts">
import { ref } from 'vue'

/**
 * 功能演示提示横幅。用于本迭代尚未落地为完整功能的模块，
 * 说明「当前为演示 / 后续迭代交付」，并可按 storageKey 记住关闭状态。
 */
const props = defineProps<{
  icon?: string
  title: string
  text: string
  /** 关闭状态的持久化键（不传则不记忆，刷新后重现）。 */
  storageKey?: string
  tone?: 'info' | 'demo'
}>()

const KEY = props.storageKey ? `wao-banner-${props.storageKey}` : ''
const dismissed = ref(KEY ? localStorage.getItem(KEY) === '1' : false)

function dismiss() {
  dismissed.value = true
  if (KEY) localStorage.setItem(KEY, '1')
}
</script>

<template>
  <div v-if="!dismissed" class="banner" :class="tone ?? 'demo'">
    <i class="b-icon" :class="icon ?? 'ri-sparkling-2-line'"></i>
    <div class="b-main">
      <div class="b-title">{{ title }}</div>
      <div class="b-text">{{ text }}</div>
    </div>
    <button class="b-close" title="不再提示" @click="dismiss"><i class="ri-close-line"></i></button>
  </div>
</template>

<style scoped>
.banner {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  border-radius: 12px;
  padding: 12px 14px;
  margin-bottom: 16px;
}
.banner.demo {
  background: linear-gradient(180deg, var(--wao-primary-tint), var(--wao-bg-sub));
  border: 1px solid var(--wao-primary-line);
}
.banner.info {
  background: var(--wao-primary-tint);
  border: 1px solid var(--wao-line);
}
.b-icon {
  font-size: 18px;
  color: var(--wao-primary);
  margin-top: 1px;
  flex-shrink: 0;
}
.b-main {
  flex: 1;
  min-width: 0;
}
.b-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-text);
}
.b-text {
  margin-top: 2px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--wao-text-weak);
}
.b-close {
  width: 24px;
  height: 24px;
  border: none;
  background: none;
  border-radius: 6px;
  cursor: pointer;
  color: var(--wao-text-faint);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.b-close:hover {
  background: rgba(23, 25, 35, 0.06);
  color: var(--wao-text-2);
}
</style>
