<script setup lang="ts">
import { ref } from 'vue'
import { fetchWebVersion } from '@/api/version'
import { openReleaseHighlights, reloadToLatestVersion } from '@/utils/versionUpdate'
import { useDataStore } from '@/stores/data'
const data = useDataStore()
const current = document.querySelector('script[data-v]')?.getAttribute('data-v') || ''
const latest = ref('')
const busy = ref(false)
const error = ref('')
const refreshing = ref(false)
async function refresh() {
  refreshing.value = true
  try {
    if (!(await data.flushWorkspace())) {
      error.value = '有草稿保存失败，已保留当前页面，请重试保存后再刷新。'
      return
    }
    reloadToLatestVersion(latest.value)
  } catch {
    error.value = '保存草稿失败，未刷新页面，请重试。'
  } finally {
    refreshing.value = false
  }
}
async function check() {
  busy.value = true
  error.value = ''
  try {
    latest.value = await fetchWebVersion()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '检查失败，请重试'
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section class="version-settings">
    <h2>版本与更新</h2>
    <p>
      当前页面版本：<code>{{ current || '本地开发版本' }}</code>
    </p>
    <p v-if="latest">
      服务器版本：<code>{{ latest }}</code>
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else-if="latest" role="status">
      {{
        current === latest
          ? '当前已是最新版本'
          : current
            ? '发现新版本，请先保存正在编辑的内容再刷新。'
            : '已获取服务器版本；本地开发环境不进行自动更新。'
      }}
    </p>
    <button class="btn-primary" :disabled="busy" @click="check">
      {{ busy ? '正在检查…' : error ? '重试检查' : '检查更新' }}
    </button>
    <button
      v-if="latest && current && latest !== current"
      class="btn-ghost"
      :disabled="refreshing"
      @click="refresh"
    >
      {{ refreshing ? '正在保存草稿…' : '保存后刷新页面' }}
    </button>
    <div class="release-actions">
      <button class="btn-ghost" type="button" @click="openReleaseHighlights">查看本次更新</button>
      <RouterLink class="feature-link" to="/features">探索全部功能</RouterLink>
    </div>
    <p>这里只维护浏览器版 WAO；更新介绍可以跳过，也能随时从这里重新打开。</p>
  </section>
</template>
<style scoped>
.version-settings {
  max-width: 680px;
}
button {
  margin-right: 12px;
}
p {
  line-height: 1.8;
}
code {
  overflow-wrap: anywhere;
}
.release-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 20px;
}
.feature-link {
  color: var(--wao-primary-text);
  font-weight: 700;
  text-decoration: none;
}
.feature-link:hover {
  text-decoration: underline;
}
</style>
