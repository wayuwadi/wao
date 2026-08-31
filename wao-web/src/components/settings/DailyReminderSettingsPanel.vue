<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  createNotificationReminder,
  deleteNotificationReminder,
  fetchNotificationReminders,
  testNotificationReminder,
  updateNotificationReminder,
  type NotificationReminder,
  type SaveNotificationReminder,
} from '@/api/notificationReminders'

type ReminderDraft = NotificationReminder & { isNew?: boolean }

const saved = ref<NotificationReminder[]>([])
const drafts = ref<ReminderDraft[]>([])
const canManage = ref(false)
const loading = ref(false)
const busyId = ref('')
const error = ref('')
const success = ref('')

const hasDirty = computed(
  () =>
    JSON.stringify(drafts.value.map(toPayload)) !== JSON.stringify(saved.value.map(toPayload)) ||
    drafts.value.some((item) => item.isNew),
)

function toPayload(reminder: ReminderDraft | NotificationReminder): SaveNotificationReminder {
  return {
    title: reminder.title,
    content: reminder.content,
    time: reminder.time,
    enabled: reminder.enabled,
  }
}

function original(id: string) {
  return saved.value.find((item) => item.id === id)
}

function rowDirty(item: ReminderDraft) {
  const before = original(item.id)
  return (
    item.isNew || !before || JSON.stringify(toPayload(item)) !== JSON.stringify(toPayload(before))
  )
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const value = await fetchNotificationReminders()
    canManage.value = value.canManageSystem
    saved.value = structuredClone(value.reminders)
    drafts.value = structuredClone(value.reminders)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '每日提醒读取失败，请重试'
  } finally {
    loading.value = false
  }
}

function add() {
  if (!canManage.value) return
  drafts.value.push({
    id: `new-${Date.now()}`,
    title: '',
    content: '',
    time: '09:00',
    enabled: true,
    lastResult: '尚未运行',
    nextFireAt: null,
    isNew: true,
  })
  success.value = ''
}

async function save(item: ReminderDraft) {
  if (busyId.value) return
  busyId.value = item.id
  error.value = ''
  success.value = ''
  try {
    const result = item.isNew
      ? await createNotificationReminder(toPayload(item))
      : await updateNotificationReminder(item.id, toPayload(item))
    const oldId = item.id
    const savedIndex = saved.value.findIndex((entry) => entry.id === oldId)
    if (savedIndex >= 0) saved.value[savedIndex] = structuredClone(result)
    else saved.value.push(structuredClone(result))
    const draftIndex = drafts.value.findIndex((entry) => entry.id === oldId)
    if (draftIndex >= 0) drafts.value[draftIndex] = structuredClone(result)
    success.value = `“${result.title}”已保存`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '每日提醒保存失败，请重试'
  } finally {
    busyId.value = ''
  }
}

async function remove(item: ReminderDraft) {
  if (busyId.value) return
  if (item.isNew) {
    drafts.value = drafts.value.filter((entry) => entry.id !== item.id)
    return
  }
  if (!window.confirm(`确认删除每日提醒“${item.title}”吗？`)) return
  busyId.value = item.id
  error.value = ''
  success.value = ''
  try {
    await deleteNotificationReminder(item.id)
    saved.value = saved.value.filter((entry) => entry.id !== item.id)
    drafts.value = drafts.value.filter((entry) => entry.id !== item.id)
    success.value = `“${item.title}”已删除`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '每日提醒删除失败，请重试'
  } finally {
    busyId.value = ''
  }
}

async function test(item: ReminderDraft) {
  if (busyId.value || item.isNew || rowDirty(item)) return
  busyId.value = item.id
  error.value = ''
  success.value = ''
  try {
    const result = await testNotificationReminder(item.id)
    const savedIndex = saved.value.findIndex((entry) => entry.id === item.id)
    if (savedIndex >= 0) saved.value[savedIndex] = structuredClone(result)
    const draftIndex = drafts.value.findIndex((entry) => entry.id === item.id)
    if (draftIndex >= 0) drafts.value[draftIndex] = structuredClone(result)
    success.value = result.lastResult.includes('成功')
      ? `“${result.title}”已真实发送`
      : `“${result.title}”试发完成：${result.lastResult}`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '每日提醒试发失败，请重试'
  } finally {
    busyId.value = ''
  }
}

defineExpose({ hasDirty })
onMounted(() => {
  void load()
})
</script>

<template>
  <section class="reminder-card">
    <div class="reminder-head">
      <div>
        <h2>系统 · 每日提醒</h2>
        <p>按上海时间发送到已配置的飞书群，复用自动化真实调度，并受群提醒总开关和沉默时段控制。</p>
      </div>
      <button v-if="canManage" type="button" :disabled="Boolean(busyId)" @click="add">
        <i class="ri-add-line"></i>新增提醒
      </button>
      <span v-else>仅管理员可编辑</span>
    </div>

    <div v-if="loading && !drafts.length" class="reminder-state">
      <i class="ri-loader-4-line spin"></i>正在读取真实提醒…
    </div>
    <div v-else-if="error && !drafts.length" class="reminder-state error" role="alert">
      {{ error }}<button type="button" @click="load">重新加载</button>
    </div>
    <div v-else-if="!drafts.length" class="reminder-state empty">
      还没有每日提醒。管理员可新增后按设定时间真实发送。
    </div>
    <div v-else class="reminder-list">
      <article v-for="item in drafts" :key="item.id" class="reminder-row">
        <label class="time-field">
          <span>每日</span>
          <input
            v-model="item.time"
            type="time"
            required
            :disabled="!canManage || busyId === item.id"
          />
        </label>
        <div class="reminder-fields">
          <input
            v-model="item.title"
            maxlength="128"
            placeholder="提醒标题"
            aria-label="提醒标题"
            :disabled="!canManage || busyId === item.id"
          />
          <textarea
            v-model="item.content"
            maxlength="512"
            rows="2"
            placeholder="提醒内容"
            aria-label="提醒内容"
            :disabled="!canManage || busyId === item.id"
          ></textarea>
          <small
            >{{ item.lastResult }}<template v-if="rowDirty(item)"> · 有未保存修改</template></small
          >
        </div>
        <div class="reminder-controls">
          <label class="enable-switch"
            ><input
              v-model="item.enabled"
              type="checkbox"
              :disabled="!canManage || busyId === item.id"
            /><span>{{ item.enabled ? '启用' : '暂停' }}</span></label
          >
          <div v-if="canManage" class="actions">
            <button
              type="button"
              class="ghost"
              :disabled="Boolean(busyId) || item.isNew || rowDirty(item)"
              title="立即真实发送一次"
              @click="test(item)"
            >
              试发
            </button>
            <button
              type="button"
              class="primary"
              :disabled="
                Boolean(busyId) || !rowDirty(item) || !item.title.trim() || !item.content.trim()
              "
              @click="save(item)"
            >
              {{ busyId === item.id ? '处理中…' : '保存' }}
            </button>
            <button type="button" class="danger" :disabled="Boolean(busyId)" @click="remove(item)">
              {{ item.isNew ? '取消' : '删除' }}
            </button>
          </div>
        </div>
      </article>
    </div>
    <div v-if="error && drafts.length" class="inline-message error" role="alert">{{ error }}</div>
    <div v-else-if="success" class="inline-message success" role="status">{{ success }}</div>
  </section>
</template>

<style scoped>
.reminder-card {
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  overflow: hidden;
}
.reminder-head {
  padding: 15px 16px;
  background: var(--wao-bg-sub);
  display: flex;
  align-items: center;
  gap: 12px;
}
.reminder-head > div {
  flex: 1;
}
h2,
p {
  margin: 0;
}
h2 {
  font-size: 14px;
}
p {
  margin-top: 5px;
  color: var(--wao-text-weak);
  font-size: 12px;
  line-height: 1.55;
}
.reminder-head > span {
  color: var(--wao-primary-text);
  font-size: 11px;
}
.reminder-head button,
.reminder-state button {
  border: 0;
  border-radius: 7px;
  padding: 7px 10px;
  color: #fff;
  background: var(--wao-primary);
  cursor: pointer;
  white-space: nowrap;
}
.reminder-head button:disabled {
  opacity: 0.5;
  cursor: default;
}
.reminder-list {
  display: grid;
}
.reminder-row {
  padding: 13px 16px;
  border-top: 1px solid var(--wao-divider);
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr) 146px;
  gap: 12px;
  align-items: start;
}
.time-field {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.time-field input,
.reminder-fields input,
.reminder-fields textarea {
  border: 1px solid var(--wao-line);
  border-radius: 7px;
  color: var(--wao-text);
  background: var(--wao-bg-card);
}
.time-field input {
  min-width: 0;
  width: 78px;
  padding: 7px 5px;
}
.reminder-fields {
  min-width: 0;
  display: grid;
  gap: 7px;
}
.reminder-fields input,
.reminder-fields textarea {
  box-sizing: border-box;
  width: 100%;
  padding: 7px 9px;
  font: inherit;
  resize: vertical;
}
.reminder-fields small {
  color: var(--wao-text-weak);
  font-size: 10px;
}
.reminder-controls {
  display: grid;
  gap: 10px;
  justify-items: end;
}
.enable-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.enable-switch input {
  width: 34px;
  height: 19px;
  accent-color: var(--wao-primary);
}
.actions {
  display: flex;
  gap: 5px;
}
.actions button {
  border: 0;
  border-radius: 6px;
  padding: 6px 8px;
  cursor: pointer;
}
.actions button:disabled {
  opacity: 0.45;
  cursor: default;
}
.actions .primary {
  color: #fff;
  background: var(--wao-primary);
}
.actions .ghost {
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
}
.actions .danger {
  color: var(--wao-danger-strong);
  background: var(--wao-danger-bg);
}
.reminder-state {
  min-height: 90px;
  padding: 16px;
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
.reminder-state.error,
.inline-message.error {
  color: var(--wao-danger-strong);
}
.reminder-state.empty {
  text-align: center;
}
.inline-message {
  margin: 0 16px 14px;
  padding: 8px 10px;
  border-radius: 7px;
  font-size: 12px;
}
.inline-message.error {
  background: var(--wao-danger-bg);
}
.inline-message.success {
  color: var(--wao-success);
  background: color-mix(in srgb, var(--wao-success) 9%, transparent);
}
.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 720px) {
  .reminder-row {
    grid-template-columns: 1fr;
  }
  .reminder-controls {
    justify-items: stretch;
  }
  .enable-switch {
    justify-content: flex-start;
  }
  .actions {
    justify-content: flex-end;
  }
}
</style>
