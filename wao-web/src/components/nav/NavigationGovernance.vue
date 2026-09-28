<script setup lang="ts">
import FunctionScoreControl from '@/components/navigation/FunctionScoreControl.vue'
import type { NavItemId } from '@/config/nav'
import { computed, onMounted, ref } from 'vue'
import { useFunctionHealth } from '@/composables/functionHealth'
import { useNavStore } from '@/stores/nav'
const health = useFunctionHealth()
const nav = useNavStore()
const sortDirection = ref<'asc' | 'desc'>('desc')
const sortedFunctions = computed(() => [...(health.overview.value?.functions ?? [])].sort((a, b) =>
  sortDirection.value === 'desc' ? b.recentOpens - a.recentOpens : a.recentOpens - b.recentOpens,
))
function toggleOpenSort() {
  sortDirection.value = sortDirection.value === 'desc' ? 'asc' : 'desc'
}
onMounted(() => void health.load(true))
</script>
<template>
  <section class="governance">
    <p>
      当前账号近 30 天真实打开：{{
        health.overview.value?.recentOpens ?? '—'
      }}。统计用于整理功能；未读取的数据不计为零。
    </p>
    <p class="governance-guidance">
      打开次数按当前账号统计，功能分数为全局评分；可用打开次数整理个人入口。全站下线前，还需核对其他成员使用情况和业务依赖。
    </p>
    <div v-if="health.error.value" role="alert">
      {{ health.error.value }} <button @click="health.load(true)">重试</button>
    </div>
    <p v-if="health.loading.value" role="status">正在读取统计…</p>
    <table v-else-if="health.overview.value">
      <thead>
        <tr>
          <th>功能</th>
          <th>评分</th>
          <th :aria-sort="sortDirection === 'desc' ? 'descending' : 'ascending'">
            <button class="sort-trigger" type="button" :aria-label="`按近 30 天打开次数${sortDirection === 'desc' ? '倒序' : '正序'}排列，点击切换`" @click="toggleOpenSort">
              近 30 天打开
              <i :class="sortDirection === 'desc' ? 'ri-sort-desc' : 'ri-sort-asc'" aria-hidden="true"></i>
            </button>
          </th>
          <th>建议操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in sortedFunctions" :key="item.featureId" :data-feature-id="item.featureId">
          <td>{{ nav.itemMeta(item.featureId as NavItemId)?.label ?? item.featureId }}</td>
          <td>
            <FunctionScoreControl
              :feature-id="item.featureId"
              :label="nav.itemMeta(item.featureId as NavItemId)?.label ?? item.featureId"
            />
          </td>
          <td>{{ item.recentOpens }}</td>
          <td>{{ item.insight }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
<style scoped>
.governance {
  overflow: auto;
  font-size: 13px;
}
p {
  color: var(--wao-text-weak);
  line-height: 1.7;
}
table {
  width: 100%;
  border-collapse: collapse;
  min-width: 540px;
}
th,
td {
  text-align: left;
  padding: 13px;
  border-bottom: 1px solid var(--wao-line);
}
th {
  color: var(--wao-text-weak);
  font-weight: 500;
}
.governance-guidance {
  margin-top: -8px;
  font-size: 12px;
}
.sort-trigger {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.sort-trigger:hover,
.sort-trigger:focus-visible {
  color: var(--wao-primary-text);
}
</style>
