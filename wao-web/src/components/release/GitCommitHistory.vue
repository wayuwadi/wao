<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchGitReleaseHistory, type GitReleaseHistory } from '@/api/version'
import { CURRENT_RELEASE } from '@/data/featureCatalog'
const records = ref<GitReleaseHistory | null>(null)
const loading = ref(false)
const error = ref('')
const limit = ref(20)
const sha = CURRENT_RELEASE.id.replace(/^git-release-/, '')
const visible = computed(() => records.value?.commits.slice(0, limit.value) ?? [])
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try { records.value = await fetchGitReleaseHistory(sha) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '读取失败，请重试' }
  finally { loading.value = false }
}
onMounted(load)
</script>
<template>
  <section class="git-history" aria-label="版本提交记录">
    <h3>版本提交记录</h3>
    <p>当前页面版本 {{ sha.slice(0, 8) }} · 自动收录功能、修复、文档及测试。提交时间不等于上线时间，也不代表其他服务已同步部署。</p>
    <p v-if="loading" role="status">正在读取提交记录…</p>
    <p v-else-if="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <template v-else-if="records">
      <p>共 {{ records.commits.length }} 条提交，当前显示 {{ visible.length }} 条</p>
      <ol><li v-for="item in visible" :key="item.sha">
        <strong>{{ item.subject }}</strong>
        <span><code>{{ item.sha.slice(0, 8) }}</code> · <time :datetime="item.committedAt">{{ new Date(item.committedAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) }}</time></span>
      </li></ol>
      <button v-if="limit < records.commits.length" type="button" @click="limit += 20">查看更多提交</button>
      <p v-else>已展示该版本全部提交</p>
    </template>
  </section>
</template>
<style scoped>
.git-history { margin-top: 24px; padding: 20px; border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); overflow-wrap: anywhere; }
h3 { margin: 0 0 12px; }
p, li span { color: var(--wao-text-3); font-size: 12px; line-height: 1.8; }
ol { list-style: none; padding: 0; margin: 12px 0; }
li { display: grid; gap: 5px; padding: 12px 0; border-top: 1px solid var(--wao-line); }
strong { font-size: 14px; font-weight: 600; }
button { min-height: 36px; border: 1px solid var(--wao-line); border-radius: 8px; padding: 6px 12px; background: var(--wao-bg-card); color: var(--wao-primary-text); cursor: pointer; }
button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; }
</style>
