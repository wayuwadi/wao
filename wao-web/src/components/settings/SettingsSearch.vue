<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { searchSettings } from '@/utils/settingsNavigation'

const props = defineProps<{ currentTab: string }>()
const emit = defineEmits<{ navigate: [id: string] }>()
const query = ref('')
const results = computed(() => searchSettings(query.value))
watch(() => props.currentTab, () => { query.value = '' })
</script>

<template>
  <div class="settings-search">
    <label><span class="search-label">查找设置</span><input v-model="query" type="search" aria-label="查找设置" placeholder="主题、提醒、账号…" @keydown.esc.stop="query = ''" /></label>
    <div v-if="query.trim()" class="settings-search-results" aria-label="设置搜索结果">
      <p role="status">{{ results.length ? `找到 ${results.length} 项设置` : '没有找到匹配设置，试试其他关键词。' }}</p>
      <button v-for="result in results" :key="result.id" type="button" @click="emit('navigate', result.id)">
        <i :class="result.icon" aria-hidden="true"></i>
        <span><strong>{{ result.label }}</strong><small>{{ result.category }}<template v-if="result.id === 'jenkins'"> · 新窗口打开</template></small></span>
        <i class="ri-arrow-right-s-line" aria-hidden="true"></i>
      </button>
      <button class="clear-search" type="button" @click="query = ''">清除搜索</button>
    </div>
  </div>
</template>

<style scoped>
.settings-search { padding: var(--wao-space-1) var(--wao-space-2); min-width: 0; }
.search-label { display: block; font-size: var(--wao-font-help); color: var(--wao-text-3); margin-bottom: var(--wao-space-1); }
input { width: 100%; min-width: 0; box-sizing: border-box; min-height: var(--wao-control-height); padding: var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text); font-size: var(--wao-font-body); }
.settings-search-results { margin-top: var(--wao-space-1); max-height: 45dvh; overflow: auto; }
.settings-search-results p { color: var(--wao-text-3); font-size: var(--wao-font-help); }
button { display: flex; align-items: center; gap: var(--wao-space-1); width: 100%; min-height: 44px; padding: var(--wao-space-1); border: 0; border-radius: var(--wao-radius-control); background: transparent; color: var(--wao-text); text-align: left; cursor: pointer; }
button:hover { background: var(--wao-bg-hover); }
button > span { flex: 1; min-width: 0; overflow-wrap: anywhere; }
strong { display: block; font-size: var(--wao-font-body); font-weight: 600; }
small { display: block; color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.clear-search { color: var(--wao-primary-text); font-size: var(--wao-font-help); }
@media (max-width: 650px) { input { font-size: var(--wao-font-reading); } }
</style>
