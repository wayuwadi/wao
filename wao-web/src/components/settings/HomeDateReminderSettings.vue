<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import {
  fetchHomeDateSettings,
  saveHomeDateSettings,
  type HomeDateSettings,
} from '@/api/homeDateReminders'
const session = useSessionStore(),
  server = ref<HomeDateSettings>(),
  draft = reactive<HomeDateSettings>({ enabled: true, solarTerms: true, revision: 0 })
const loading = ref(false),
  saving = ref(false),
  error = ref(''),
  saved = ref(false),
  conflict = ref(false)
let generation = 0
const key = computed(() => `wao-home-date-settings-draft:${session.currentUser.id}`)
const dirty = computed(
  () =>
    !!server.value &&
    (draft.enabled !== server.value.enabled || draft.solarTerms !== server.value.solarTerms),
)
function remember() {
  try {
    localStorage.setItem(key.value, JSON.stringify(draft))
  } catch {
    /* 草稿留在表单。 */
  }
}
function forget() {
  try {
    localStorage.removeItem(key.value)
  } catch {
    /* 不覆盖其他数据。 */
  }
}
async function load(preserve = false) {
  const mine = generation
  loading.value = true
  error.value = ''
  try {
    const value = await fetchHomeDateSettings()
    if (mine !== generation) return
    server.value = value
    if (!preserve) {
      Object.assign(draft, value)
      try {
        const local = JSON.parse(
          localStorage.getItem(key.value) || 'null',
        ) as HomeDateSettings | null
        if (
          local &&
          typeof local.enabled === 'boolean' &&
          typeof local.solarTerms === 'boolean' &&
          typeof local.revision === 'number'
        )
          Object.assign(draft, local)
      } catch {
        /* 使用账号设置。 */
      }
    }
    conflict.value = draft.revision !== value.revision
  } catch {
    if (mine === generation) error.value = '设置暂未读取成功，请重试；本机草稿保留。'
  } finally {
    if (mine === generation) loading.value = false
  }
}
async function save() {
  if (!server.value || loading.value || saving.value || conflict.value) return
  const mine = generation
  saving.value = true
  error.value = ''
  saved.value = false
  remember()
  try {
    const value = await saveHomeDateSettings({ ...draft })
    if (mine !== generation) return
    server.value = value
    Object.assign(draft, value)
    forget()
    saved.value = true
  } catch (cause) {
    if (mine !== generation) return
    const status = (cause as { response?: { status?: number } }).response?.status
    if (status === 409) {
      await load(true)
      conflict.value = true
      error.value = '账号设置已更新，请选择如何处理本机草稿。'
    } else error.value = '保存失败，选择已保留，可重试保存。'
  } finally {
    if (mine === generation) saving.value = false
  }
}
function useAccount() {
  if (!server.value) return
  Object.assign(draft, server.value)
  conflict.value = false
  error.value = ''
  forget()
}
function keepDraft() {
  if (!server.value) return
  draft.revision = server.value.revision
  conflict.value = false
  error.value = ''
  remember()
}
function confirmLeave() {
  return (
    (!saving.value && !dirty.value) ||
    window.confirm('首页提醒设置尚未保存，草稿会保留，确认离开吗？')
  )
}
defineExpose({ confirmLeave })
watch(
  () => [draft.enabled, draft.solarTerms],
  () => {
    if (!loading.value && !saving.value && server.value) {
      saved.value = false
      if (dirty.value) remember()
      else forget()
    }
  },
)
watch(
  () => session.currentUser.id,
  () => {
    generation++
    server.value = undefined
    saving.value = false
    conflict.value = false
    saved.value = false
    void load()
  },
  { immediate: true },
)
</script>
<template>
  <section class="home-reminder-settings card thin" aria-label="首页日期提醒设置">
    <div class="home-reminder-title">
      <i class="ri-calendar-heart-line" aria-hidden="true"></i><strong>首页记得你的重要日子</strong>
    </div>
    <p>提醒短暂出现，随后回到留白。安静陪伴时保留文字，不播放小仪式。</p>
    <p v-if="loading" role="status">正在读取账号设置…</p>
    <template v-if="server">
      <label class="home-reminder-row"
        ><span><b>首页轻提醒</b><small>生日、纪念日和日历重要事项</small></span
        ><input
          v-model="draft.enabled"
          type="checkbox"
          role="switch"
          :disabled="loading || saving"
          aria-label="首页轻提醒"
      /></label>
      <label class="home-reminder-row"
        ><span><b>二十四节气</b><small>当天捎来一句季节提示</small></span
        ><input
          v-model="draft.solarTerms"
          type="checkbox"
          role="switch"
          :disabled="loading || saving"
          aria-label="二十四节气首页提醒"
      /></label>
      <p v-if="conflict">
        账号当前：{{ server.enabled ? '开启首页提醒' : '关闭首页提醒' }}，{{
          server.solarTerms ? '提醒节气' : '不提醒节气'
        }}。本机选择仍保留。
      </p>
      <div v-if="conflict" class="home-reminder-footer">
        <button type="button" :disabled="loading || saving" @click="useAccount">使用账号设置</button
        ><button type="button" :disabled="loading || saving" @click="keepDraft">
          保留草稿，采用最新版本
        </button>
      </div>
      <div class="home-reminder-footer">
        <span role="status">{{
          saving ? '保存中…' : saved ? '已保存' : dirty ? '有未保存的修改' : '已读取账号设置'
        }}</span
        ><button type="button" :disabled="loading || saving || conflict || !dirty" @click="save">
          {{ saving ? '保存中…' : error ? '重试保存' : '保存首页提醒设置' }}
        </button>
      </div>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
    <button
      v-if="!server || error"
      type="button"
      :disabled="loading || saving"
      @click="load(!!server)"
    >
      重新读取
    </button>
    <p><RouterLink to="/calendar">到日历设置我的生日和重要日子 ↗</RouterLink></p>
  </section>
</template>
<style scoped>
.home-reminder-settings {
  padding: 18px;
  margin: 16px 0;
  font-size: var(--wao-font-body);
  line-height: 1.7;
}
.home-reminder-title {
  display: flex;
  gap: 9px;
  align-items: center;
  color: var(--wao-text-1);
}
.home-reminder-title i {
  color: var(--wao-primary-text);
  font-size: 20px;
}
.home-reminder-settings p {
  color: var(--wao-text-2);
  font-size: var(--wao-font-caption);
}
.home-reminder-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--wao-line);
}
.home-reminder-row b,
.home-reminder-row small {
  display: block;
  font-weight: 400;
}
.home-reminder-row small {
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
}
.home-reminder-row input {
  appearance: none;
  width: 36px;
  height: 21px;
  flex-shrink: 0;
  background: var(--wao-line);
  border-radius: 16px;
  position: relative;
  cursor: pointer;
}
.home-reminder-row input:checked {
  background: var(--wao-primary-text);
}
.home-reminder-row input:before {
  content: '';
  position: absolute;
  top: 3px;
  left: 3px;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: var(--wao-bg-card);
}
.home-reminder-row input:checked:before {
  left: 18px;
}
.home-reminder-row input:disabled {
  opacity: 0.5;
  cursor: default;
}
.home-reminder-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
}
.home-reminder-footer span {
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
}
.home-reminder-settings button {
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  padding: 8px 12px;
  font: inherit;
  cursor: pointer;
}
.home-reminder-settings button:disabled {
  opacity: 0.5;
  cursor: default;
}
.home-reminder-settings [role='alert'] {
  color: var(--wao-danger);
}
</style>
