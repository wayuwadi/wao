<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Task } from '@/types'
import { searchProjectTasks } from '@/utils/projectTaskSearch'
import { isTaskFinished } from '@/utils/projectCompletion'
import { useUiStore } from '@/stores/ui'
import { useClientPagination } from '@/composables/clientPagination'
import WaoPagination from '@/components/ui/WaoPagination.vue'

const props = defineProps<{ query: string; tasks: Task[]; filteredTasks: Task[]; loadState: string }>()
const emit = defineEmits<{ clearFilters: [] }>()
const ui = useUiStore()
const includeFinished = ref(true)
const matches = computed(() => searchProjectTasks(props.tasks, props.query))
const allowed = computed(() => new Set(props.filteredTasks.map(task => task.id)))
const filtered = computed(() => matches.value.filter(match => allowed.value.has(match.task.id)))
const results = computed(() => filtered.value.filter(match => includeFinished.value || !isTaskFinished(match.task)))
const excluded = computed(() => matches.value.length - filtered.value.length)
const hiddenFinished = computed(() => filtered.value.length - results.value.length)
const ready = computed(() => props.loadState !== 'loading' && props.loadState !== 'error')
const { page, pageCount, visible } = useClientPagination(results, [() => props.query, includeFinished, allowed], 20)
</script>

<template>
  <section class="project-search-results" aria-label="项目查找结果">
    <div class="search-summary">
      <label><input v-model="includeFinished" type="checkbox">包含已收尾任务</label>
      <span>{{ includeFinished ? '查找全部任务（含已完成）' : '仅查找未收尾任务（含待验证）' }}</span>
      <span v-if="ready" role="status">命中 {{ results.length }} 项任务</span>
      <span v-else role="status">{{ loadState === 'error' ? '加载失败，以下仅为已加载内容，请在上方重试' : '正在加载，以下结果尚不完整' }}</span>
      <button v-if="excluded" @click="emit('clearFilters')">{{ excluded }} 项被快捷条件或人员筛选排除 · 清除筛选</button>
      <button v-if="hiddenFinished" @click="includeFinished = true">另有 {{ hiddenFinished }} 项已收尾 · 包含历史</button>
    </div>
    <p v-if="ready && !matches.length" class="search-empty">当前项目没有匹配内容，试试其他关键词。</p>
    <p v-else-if="ready && !results.length" class="search-empty">当前范围没有结果，请调整上方的筛选或已收尾开关。</p>
    <article v-for="match in visible" :key="match.task.id" class="search-task">
      <div v-if="match.context.length" class="search-context">上级任务：{{ match.context.join(' › ') }}</div>
      <button class="search-task-title" @click="ui.openTask(match.task.id)">
        <span>#{{ match.task.number ?? match.task.id }} · {{ match.task.title }}</span><small>{{ match.task.status }}</small>
      </button>
      <ul>
        <li v-for="(hit, index) in match.hits" :key="index">
          <button class="search-hit" @click="ui.openTask(match.task.id, hit.subId)">
            <span class="search-field">{{ hit.field }}</span>
            <span v-if="hit.context.length" class="search-context">{{ hit.context.join(' › ') }}</span>
            <span class="search-snippet">{{ hit.snippet }}</span>
            <span class="search-open">{{ hit.subId ? '打开子任务' : '打开任务' }}</span>
          </button>
        </li>
      </ul>
    </article>
    <WaoPagination v-if="pageCount > 1" v-model:page="page" :page-count="pageCount" :total="results.length" aria-label="项目查找结果分页" />
  </section>
</template>

<style scoped>
.project-search-results { min-width:0; overflow:auto; }
.search-summary { display:flex; flex-wrap:wrap; gap:8px 14px; align-items:center; padding:10px 0; font-size:var(--wao-font-help); color:var(--wao-text-3); }
.search-summary label { display:flex; align-items:center; gap:4px; color:var(--wao-text); }
button { cursor:pointer; font:inherit; color:inherit; border:0; background:none; text-align:left; }
.search-summary button,.search-open { color:var(--wao-primary-text); }
.search-task { min-width:0; border:1px solid var(--wao-line); border-radius:10px; padding:10px 12px; margin-bottom:8px; }
.search-task-title { display:flex; flex-wrap:wrap; gap:8px; padding:0; width:100%; font-weight:600; overflow-wrap:anywhere; }
.search-task-title small { font-weight:400; color:var(--wao-text-weak); }
ul { list-style:none; margin:5px 0 0; padding:0; }
.search-hit { display:flex; flex-wrap:wrap; gap:4px 8px; padding:7px 0; width:100%; border-top:1px solid var(--wao-line); font-size:var(--wao-font-body); overflow-wrap:anywhere; }
.search-field { font-weight:600; flex-shrink:0; }
.search-context { color:var(--wao-text-3); font-size:var(--wao-font-body); overflow-wrap:anywhere; }
.search-snippet { flex-basis:100%; white-space:pre-wrap; }
.search-empty { padding:18px 0; color:var(--wao-text-weak); }
</style>
