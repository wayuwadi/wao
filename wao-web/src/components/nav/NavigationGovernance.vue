<script setup lang="ts">
import FunctionScoreControl from '@/components/navigation/FunctionScoreControl.vue'
import type { NavItemId } from '@/config/nav'
import { onMounted } from 'vue'
import { useFunctionHealth } from '@/composables/functionHealth'
import { useNavStore } from '@/stores/nav'
const health = useFunctionHealth()
const nav = useNavStore()
onMounted(() => void health.load(true))
</script>
<template>
  <section class="governance">
    <p>
      近 30 天真实打开：{{
        health.overview.value?.recentOpens ?? '—'
      }}。统计用于整理功能；未读取的数据不计为零。
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
          <th>近 30 天打开</th>
          <th>观察</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in health.overview.value.functions" :key="item.featureId">
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
</style>
