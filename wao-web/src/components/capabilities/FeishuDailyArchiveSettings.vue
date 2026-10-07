<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  fetchFeishuDailyArchive,
  saveFeishuDailyArchive,
  type FeishuDailyArchive,
} from '@/api/system'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
const session = useSessionStore(),
  data = useDataStore(),
  owner = session.userId
const state = ref<FeishuDailyArchive | null>(null),
  enabled = ref(false)
const loading = ref(true),
  saving = ref(false),
  error = ref(''),
  storageError = ref(false)
const draftKey = `wao-feishu-daily-archive-draft-v1:${owner}`
const dirty = computed(() => !!state.value && enabled.value !== state.value.enabled)
let disposed = false,
  initialized = false
const alive = () => !disposed && session.userId === owner
function retainDraft() {
  try {
    if (dirty.value) localStorage.setItem(draftKey, JSON.stringify({ enabled: enabled.value }))
    else localStorage.removeItem(draftKey)
    storageError.value = false
  } catch {
    storageError.value = true
  }
}
watch(enabled, () => {
  if (initialized && alive()) retainDraft()
})
async function load() {
  loading.value = true
  error.value = ''
  try {
    const latest = await fetchFeishuDailyArchive()
    if (!alive()) return
    state.value = latest
    if (!initialized) {
      enabled.value = latest.enabled
      try {
        const draft = JSON.parse(localStorage.getItem(draftKey) ?? 'null')
        if (typeof draft?.enabled === 'boolean') enabled.value = draft.enabled
      } catch {
        storageError.value = true
      }
      initialized = true
    }
  } catch (e) {
    if (alive()) error.value = e instanceof Error ? e.message : '每日归档读取失败'
  } finally {
    if (alive()) loading.value = false
  }
}
async function save() {
  if (!state.value || loading.value || saving.value) return false
  saving.value = true
  error.value = ''
  try {
    const saved = await saveFeishuDailyArchive({
      enabled: enabled.value,
      expectedEnabled: state.value.enabled,
    })
    if (!alive()) return false
    state.value = saved
    enabled.value = saved.enabled
    retainDraft()
    return true
  } catch (e) {
    if (alive()) error.value = e instanceof Error ? e.message : '保存失败，请重试'
    return false
  } finally {
    if (alive()) saving.value = false
  }
}
function confirmLeave() {
  return (
    !saving.value &&
    (!storageError.value ||
      !dirty.value ||
      window.confirm('归档草稿未能保存在本机，确认放弃未保存修改吗？'))
  )
}
function beforeUnload(e: BeforeUnloadEvent) {
  if (saving.value || (dirty.value && storageError.value)) {
    e.preventDefault()
    e.returnValue = ''
  }
}
useSaveBeforeRefresh(async () => {
  if (dirty.value && !(await save())) throw new Error(error.value || '每日归档尚未保存')
})
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  void load()
})
onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('beforeunload', beforeUnload)
})
defineExpose({ confirmLeave, reload: load })
</script>

<template>
  <section class="daily-archive" aria-label="每日群聊归档">
    <h3>每日归档</h3>
    <p>
      每天上海时间 00:00，把前一天的群消息保存到知识库。同一天只执行一次，不补扫历史、不同步表情。
    </p>
    <p v-if="loading" role="status">正在读取每日归档…</p>
    <template v-else-if="state">
      <label class="switch-row"
        ><span>启用每日归档</span
        ><button
          type="button"
          role="switch"
          :aria-checked="enabled"
          :disabled="saving"
          @click="enabled = !enabled"
        >
          <span /></button
      ></label>
      <p>
        归档群：{{ state.chatName || state.chatId || '尚未配置' }} · 知识库：{{
          data.kbById(state.kbId)?.name || state.kbId || '尚未配置'
        }}
      </p>
      <p v-if="!state.configured">请先保存上方的应用、目标群和归档知识库。</p>
      <p v-else-if="state.enabled">
        下次运行：{{ state.nextRunAt || '等待下一次午夜' }}（上海时间）
      </p>
      <p v-if="state.latestRun">
        最近归档：{{ state.latestRun.archiveDate }} ·
        {{
          {
            RUNNING: '已领取，等待结果；当天不重试',
            SUCCESS: '已保存',
            FAILED: '失败，当天不自动重试',
          }[state.latestRun.status]
        }}<span v-if="state.latestRun.messageCount != null">
          · {{ state.latestRun.messageCount }} 条消息</span
        >
      </p>
      <p v-if="state.latestRun?.errorCode" class="error">{{ state.latestRun.errorCode }}</p>
      <p class="muted">
        一天一次指归档任务执行频率；消息分页和图片读取仍可能产生多次飞书 API 请求。
      </p>
      <footer>
        <span>{{ saving ? '保存中…' : dirty ? '有待保存修改' : '已保存' }}</span
        ><button
          class="save"
          :disabled="saving || !dirty || (enabled && !state.configured)"
          @click="save"
        >
          保存每日归档
        </button>
      </footer>
    </template>
    <div v-if="error" class="error" role="alert">
      {{ error }}<button :disabled="saving || loading" @click="load">重新读取并保留草稿</button>
    </div>
    <p v-if="storageError" class="error" role="alert">本机草稿保存失败，请保存后再离开。</p>
  </section>
</template>

<style scoped>
.daily-archive {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid var(--wao-line);
}
h3 {
  font-size: 16px;
  margin: 0 0 10px;
}
p {
  color: var(--wao-text-2);
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.muted {
  color: var(--wao-text-3);
}
.error {
  color: var(--wao-danger-strong);
}
.switch-row,
footer {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}
.switch-row {
  justify-content: space-between;
  max-width: 260px;
}
button {
  cursor: pointer;
  font: inherit;
  border-radius: 8px;
  border: 1px solid var(--wao-line);
  padding: 7px 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}
button[role='switch'] {
  width: 36px;
  height: 21px;
  padding: 2px;
  border: none;
  border-radius: 12px;
  background: var(--wao-line);
}
button[role='switch'] span {
  display: block;
  width: 17px;
  height: 17px;
  border-radius: 50%;
  background: white;
  transition: transform 0.15s;
}
button[aria-checked='true'] {
  background: var(--wao-primary);
}
button[aria-checked='true'] span {
  transform: translateX(15px);
}
button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
footer {
  margin-top: 14px;
  flex-wrap: wrap;
}
footer span {
  margin-right: auto;
  color: var(--wao-text-3);
}
.save {
  background: var(--wao-primary);
  color: white;
  border-color: transparent;
}
</style>
