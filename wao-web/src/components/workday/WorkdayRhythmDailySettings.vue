<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import {
  fetchFeishuChats,
  fetchWebhookEndpoints,
  type FeishuChat,
  type FeishuWebhookEndpoint,
} from '@/api/system'
import {
  createRhythmChecklistItem,
  deleteRhythmChecklistItem,
  fetchRhythmChecklistItems,
  fetchRhythmSettings,
  saveRhythmSettings,
  updateRhythmChecklistItem,
  type RhythmChecklistItem,
  type RhythmSettings,
  type SaveRhythmChecklistItem,
} from '@/api/workdayRhythm'
import { toast } from '@/composables/toast'

defineProps<{ externalSave?: boolean }>()
const session = useSessionStore()
const owner = session.userId
let active = true
const isCurrent = () => active && session.userId === owner
watch(
  () => session.userId,
  () => {
    active = false
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  active = false
})
const emit = defineEmits<{ saved: [] }>()
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const saved = ref(false)
const form = ref<RhythmSettings | null>(null)
const items = ref<Array<RhythmChecklistItem & { fresh?: boolean }>>([])
const chats = ref<FeishuChat[]>([])
const webhooks = ref<FeishuWebhookEndpoint[]>([])
const expandedId = ref<string | null>(null)
const mutatingId = ref('')
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
const orderedItems = computed(() => [
  ...items.value.filter((item) => item.active),
  ...items.value.filter((item) => !item.active),
])

function payload(item: RhythmChecklistItem): SaveRhythmChecklistItem {
  return {
    category: item.category,
    title: item.title,
    itemType: item.itemType ?? 'CHECK',
    durationMinutes: item.itemType === 'COUNTDOWN' ? item.durationMinutes : null,
    scheduleType: item.scheduleType,
    weekdays: item.weekdays,
    startTime: item.startTime,
    dueTime: item.dueTime,
    dueNextDay: item.dueNextDay,
    active: item.active,
    order: item.order,
    notifyDirect: item.notifyDirect,
    notifyChat: item.notifyChat,
    chatId: item.chatId,
    chatName: item.chatName,
    notifyWebhook: item.notifyWebhook,
    webhookId: item.webhookId,
    webhookName: item.webhookName,
    reminderTime: item.reminderTime,
    reminderNextDay: item.reminderNextDay,
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [settings, checklist, chatOptions, webhookOptions] = await Promise.all([
      fetchRhythmSettings(),
      fetchRhythmChecklistItems(),
      fetchFeishuChats().catch(() => []),
      fetchWebhookEndpoints().catch(() => []),
    ])
    if (!isCurrent()) return
    form.value = structuredClone(settings)
    items.value = structuredClone(checklist).map((item) => ({
      ...item,
      itemType: item.itemType ?? 'CHECK',
      durationMinutes: item.durationMinutes ?? null,
    }))
    chats.value = chatOptions
    webhooks.value = webhookOptions
  } catch (cause) {
    if (!isCurrent()) return
    error.value = cause instanceof Error ? cause.message : '每日面板设置读取失败'
  } finally {
    if (!isCurrent()) return
    loading.value = false
  }
}

function addItem() {
  const id = `new-${Date.now()}`
  items.value.push({
    id,
    kind: 'CUSTOM',
    category: 'LIFE',
    title: '',
    itemType: 'CHECK',
    durationMinutes: null,
    scheduleType: 'DAILY',
    weekdays: [],
    startTime: '07:00',
    dueTime: '23:59',
    dueNextDay: false,
    active: true,
    order: items.value.length * 10 + 50,
    notifyDirect: false,
    notifyChat: false,
    chatId: null,
    chatName: null,
    notifyWebhook: false,
    webhookId: null,
    webhookName: null,
    reminderTime: null,
    reminderNextDay: false,
    fresh: true,
  })
  expandedId.value = id
}

function scheduleLabel(item: RhythmChecklistItem) {
  if (item.scheduleType === 'WORKDAY') return '工作日'
  if (item.scheduleType === 'DAILY') return '每天'
  return item.weekdays.length
    ? item.weekdays.map((day) => `周${weekdays[day - 1]}`).join('、')
    : '未选择星期'
}

function notificationLabel(item: RhythmChecklistItem) {
  const channels = [item.notifyDirect, item.notifyChat, item.notifyWebhook].filter(Boolean).length
  return channels ? `${channels} 个提醒通道` : '不提醒'
}

function typeLabel(item: RhythmChecklistItem) {
  return item.itemType === 'COUNTDOWN' ? `${item.durationMinutes} 分钟倒计时` : '普通打卡'
}

function toggleWeekday(item: RhythmChecklistItem, day: number) {
  item.weekdays = item.weekdays.includes(day)
    ? item.weekdays.filter((value) => value !== day)
    : [...item.weekdays, day].sort()
}

function selectChat(item: RhythmChecklistItem) {
  item.chatName = chats.value.find((chat) => chat.chatId === item.chatId)?.name ?? null
}

function selectWebhook(item: RhythmChecklistItem) {
  item.webhookName = webhooks.value.find((webhook) => webhook.id === item.webhookId)?.name ?? null
}

async function removeItem(item: RhythmChecklistItem & { fresh?: boolean }) {
  if (item.fresh) {
    items.value = items.value.filter((entry) => entry.id !== item.id)
    expandedId.value = null
    return
  }
  const systemItem = item.kind !== 'CUSTOM'
  const prompt = systemItem
    ? `从每日必做中移除“${item.title}”？系统联动和历史记录会保留，之后仍可恢复。`
    : `删除“${item.title}”？历史完成记录会继续保留。`
  if (!window.confirm(prompt)) return
  mutatingId.value = item.id
  error.value = ''
  try {
    if (systemItem) {
      const savedItem = await updateRhythmChecklistItem(
        item.id,
        payload({ ...item, active: false }),
      )
      if (!isCurrent()) return
      const index = items.value.findIndex((entry) => entry.id === item.id)
      if (index >= 0) items.value[index] = savedItem
      toast(`已从每日必做中移除“${item.title}”`, 'ri-check-line')
    } else {
      await deleteRhythmChecklistItem(item.id)
      if (!isCurrent()) return
      items.value = items.value.filter((entry) => entry.id !== item.id)
      toast(`已删除“${item.title}”`, 'ri-check-line')
    }
    expandedId.value = null
  } catch (cause) {
    if (!isCurrent()) return
    error.value = cause instanceof Error ? cause.message : '项目移除失败，请重试'
  } finally {
    if (!isCurrent()) return
    mutatingId.value = ''
  }
}

async function restoreItem(item: RhythmChecklistItem & { fresh?: boolean }) {
  if (item.fresh) {
    item.active = true
    return
  }
  mutatingId.value = item.id
  error.value = ''
  try {
    const savedItem = await updateRhythmChecklistItem(item.id, payload({ ...item, active: true }))
    if (!isCurrent()) return
    const index = items.value.findIndex((entry) => entry.id === item.id)
    if (index >= 0) items.value[index] = savedItem
    toast(`已恢复“${item.title}”`, 'ri-check-line')
  } catch (cause) {
    if (!isCurrent()) return
    error.value = cause instanceof Error ? cause.message : '项目恢复失败，请重试'
  } finally {
    if (!isCurrent()) return
    mutatingId.value = ''
  }
}

async function submit() {
  if (!form.value || saving.value) return
  saving.value = true
  saved.value = false
  error.value = ''
  try {
    await saveRhythmSettings(form.value)
    if (!isCurrent()) return
    await Promise.all(
      items.value.map((item) =>
        item.fresh
          ? createRhythmChecklistItem(payload(item))
          : updateRhythmChecklistItem(item.id, payload(item)),
      ),
    )
    if (!isCurrent()) return
    saved.value = true
    emit('saved')
    await load()
  } catch (cause) {
    if (!isCurrent()) return
    error.value = cause instanceof Error ? cause.message : '每日面板设置保存失败'
  } finally {
    if (!isCurrent()) return
    saving.value = false
  }
}

defineExpose({ submit, saving, loading, mutatingId, form, saved, error })
onMounted(load)
</script>

<template>
  <section class="daily-settings">
    <header>
      <div>
        <small>DAILY DASHBOARD</small>
        <h3>每日面板与必做清单</h3>
        <p>把每天需要惦记的事情模板化；通知默认关闭，只有主动选择通道后才发送。</p>
      </div>
      <button type="button" @click="load"><i class="ri-refresh-line"></i>刷新</button>
    </header>
    <div v-if="loading" class="settings-state">
      <i class="ri-loader-4-line spin"></i>正在读取每日面板设置…
    </div>
    <div v-else-if="!form" class="settings-state error">
      <i class="ri-error-warning-line"></i>{{ error }}<button @click="load">重试</button>
    </div>
    <template v-else>
      <div class="setting-grid">
        <label class="switch-row"
          ><input v-model="form.followAlternateWeek" type="checkbox" /><span
            ><b>自动跟随大小周</b><small>周一至周五及大周周日执行；当天仍可手动覆盖</small></span
          ></label
        >
        <label class="switch-row"
          ><input v-model="form.tianrenEnabled" type="checkbox" /><span
            ><b>展示每日卦象</b><small>只展示入口，不会打开页面就自动起卦</small></span
          ></label
        >
        <label class="switch-row"
          ><input
            v-model="form.tianrenRequired"
            type="checkbox"
            :disabled="!form.tianrenEnabled"
          /><span><b>卦象纳入每日必做</b><small>起卦成功后自动完成对应清单项</small></span></label
        >
      </div>

      <section class="items-settings">
        <header>
          <div>
            <h4><i class="ri-checkbox-multiple-line"></i>每日必做项目</h4>
            <p>先浏览项目摘要，需要调整时再展开单项；系统项移除后保留联动与历史，可随时恢复。</p>
          </div>
          <button type="button" @click="addItem"><i class="ri-add-line"></i>添加项目</button>
        </header>
        <div class="project-list" role="list">
          <article
            v-for="(item, index) in orderedItems"
            :key="item.id"
            class="item-editor"
            :class="{ inactive: !item.active, expanded: expandedId === item.id }"
            role="listitem"
          >
            <header class="item-summary">
              <span class="item-index">{{ index + 1 }}</span>
              <span class="item-main"
                ><b>{{ item.title || '未命名项目' }}</b
                ><small
                  >{{ typeLabel(item) }} · {{ scheduleLabel(item) }} · {{ item.startTime }}–{{
                    item.dueNextDay ? '次日 ' : ''
                  }}{{ item.dueTime }} · {{ notificationLabel(item) }}</small
                ></span
              >
              <em>{{
                item.kind === 'CUSTOM'
                  ? '自定义'
                  : item.kind === 'PLAN'
                    ? '计划联动'
                    : item.kind === 'TIANREN'
                      ? '卦象联动'
                      : '打卡联动'
              }}</em>
              <span class="item-status" :class="{ disabled: !item.active }">{{
                item.active ? '已启用' : '已停用'
              }}</span>
              <button
                class="edit-item"
                type="button"
                :aria-expanded="expandedId === item.id"
                :aria-label="`${expandedId === item.id ? '收起' : '编辑'}${item.title}`"
                @click="expandedId = expandedId === item.id ? null : item.id"
              >
                <i :class="expandedId === item.id ? 'ri-arrow-up-s-line' : 'ri-edit-line'"></i
                >{{ expandedId === item.id ? '收起' : '编辑' }}
              </button>
              <button
                v-if="item.active"
                class="remove-item"
                type="button"
                :disabled="mutatingId === item.id"
                :aria-label="`${item.kind === 'CUSTOM' ? '删除' : '移除'}${item.title}`"
                :title="item.kind === 'CUSTOM' ? '删除项目' : '从每日必做中移除'"
                @click="removeItem(item)"
              >
                <i
                  :class="mutatingId === item.id ? 'ri-loader-4-line spin' : 'ri-delete-bin-line'"
                ></i>
              </button>
              <button
                v-else
                class="restore-item"
                type="button"
                :disabled="mutatingId === item.id"
                :aria-label="`恢复${item.title}`"
                @click="restoreItem(item)"
              >
                <i :class="mutatingId === item.id ? 'ri-loader-4-line spin' : 'ri-restart-line'"></i
                >恢复
              </button>
            </header>
            <div v-if="expandedId === item.id" class="item-detail">
              <label class="item-title-field"
                ><span>项目名称</span
                ><input v-model="item.title" maxlength="80" placeholder="必做事项名称"
              /></label>
              <div class="item-fields">
                <label
                  ><span>完成方式</span
                  ><select v-model="item.itemType" :disabled="item.kind !== 'CUSTOM'">
                    <option value="CHECK">普通打卡</option>
                    <option value="COUNTDOWN">倒计时</option>
                  </select></label
                >
                <label v-if="item.itemType === 'COUNTDOWN'"
                  ><span>固定时长（分钟）</span
                  ><input v-model.number="item.durationMinutes" type="number" min="1" max="1440"
                /></label>
                <label
                  ><span>分类</span
                  ><select v-model="item.category">
                    <option value="WORK">工作</option>
                    <option value="LIFE">生活</option>
                  </select></label
                >
                <label
                  ><span>适用日</span
                  ><select v-model="item.scheduleType">
                    <option value="WORKDAY">工作日</option>
                    <option value="DAILY">每天</option>
                    <option value="WEEKDAYS">自选星期</option>
                  </select></label
                >
                <label><span>开始</span><input v-model="item.startTime" type="time" /></label>
                <label><span>截止</span><input v-model="item.dueTime" type="time" /></label>
                <label class="inline"
                  ><input v-model="item.dueNextDay" type="checkbox" />截止在次日</label
                >
              </div>
              <div v-if="item.scheduleType === 'WEEKDAYS'" class="weekday-row">
                <button
                  v-for="(label, day) in weekdays"
                  :key="day"
                  type="button"
                  :class="{ active: item.weekdays.includes(day + 1) }"
                  @click="toggleWeekday(item, day + 1)"
                >
                  周{{ label }}
                </button>
              </div>
              <div class="notify-row">
                <b>未完成提醒</b
                ><label><input v-model="item.notifyDirect" type="checkbox" />飞书私聊</label
                ><label><input v-model="item.notifyChat" type="checkbox" />飞书群</label
                ><label><input v-model="item.notifyWebhook" type="checkbox" />Webhook</label
                ><label
                  ><span>提醒时间</span
                  ><input
                    v-model="item.reminderTime"
                    type="time"
                    :disabled="
                      !item.notifyDirect && !item.notifyChat && !item.notifyWebhook
                    " /></label
                ><label class="inline"
                  ><input v-model="item.reminderNextDay" type="checkbox" />次日提醒</label
                >
              </div>
              <div v-if="item.notifyChat || item.notifyWebhook" class="target-row">
                <label v-if="item.notifyChat"
                  ><span>通知群聊</span
                  ><select v-model="item.chatId" @change="selectChat(item)">
                    <option :value="null">请选择群聊</option>
                    <option v-for="chat in chats" :key="chat.chatId" :value="chat.chatId">
                      {{ chat.name }}
                    </option>
                  </select></label
                ><label v-if="item.notifyWebhook"
                  ><span>Webhook</span
                  ><select v-model="item.webhookId" @change="selectWebhook(item)">
                    <option :value="null">请选择配置</option>
                    <option
                      v-for="webhook in webhooks.filter((entry) => entry.enabled)"
                      :key="webhook.id"
                      :value="webhook.id"
                    >
                      {{ webhook.name }}
                    </option>
                  </select></label
                >
              </div>
            </div>
          </article>
        </div>
      </section>
      <p v-if="error" class="save-error" role="alert">
        <i class="ri-error-warning-line"></i>{{ error }}
      </p>
      <footer v-if="!externalSave">
        <span v-if="saved"><i class="ri-check-line"></i>每日面板设置已保存</span
        ><button class="primary" type="button" :disabled="saving" @click="submit">
          {{ saving ? '保存中…' : '保存每日面板' }}
        </button>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.daily-settings {
  font-size: 13px;
  line-height: 1.6;
  padding-bottom: 20px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--wao-divider);
}
.daily-settings > header,
.items-settings > header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}
.daily-settings h3,
.daily-settings h4 {
  margin: 3px 0;
}
.daily-settings p {
  margin: 4px 0;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.daily-settings > header small {
  font-size: 12px;
  letter-spacing: 0.16em;
  color: var(--wao-primary-text);
}
.daily-settings button {
  font: inherit;
  border: 0;
  background: none;
  color: var(--wao-primary-text);
  cursor: pointer;
}
.settings-state {
  min-height: 100px;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  color: var(--wao-text-weak);
}
.settings-state.error,
.save-error {
  color: var(--wao-danger-strong);
}
.setting-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 14px 0;
}
.switch-row {
  display: flex;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
}
.switch-row span {
  display: grid;
  gap: 3px;
}
.switch-row b {
  font-size: 13px;
}
.switch-row small {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.items-settings {
  margin-top: 16px;
}
.items-settings h4 {
  font-size: 14px;
}
.project-list {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}
.item-fields label,
.target-row label,
.item-title-field {
  display: grid;
  gap: 4px;
}
.item-fields span,
.target-row span,
.notify-row > label > span,
.item-title-field > span {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.item-editor input,
.item-editor select {
  font: inherit;
  min-width: 0;
  height: 34px;
  box-sizing: border-box;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 0 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
.item-editor {
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-sub);
  overflow: hidden;
}
.item-editor.inactive {
  background: var(--wao-bg-page);
}
.item-editor.expanded {
  border-color: color-mix(in srgb, var(--wao-primary) 45%, var(--wao-line));
  box-shadow: 0 8px 24px rgba(38, 124, 101, 0.07);
}
.item-summary {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto auto auto 34px;
  gap: 8px;
  align-items: center;
  padding: 10px;
}
.item-index {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 750;
}
.item-main {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.item-main b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: normal;
  overflow-wrap: anywhere;
  font-size: 14px;
}
.item-main small {
  overflow: hidden;
  color: var(--wao-text-weak);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: normal;
  overflow-wrap: anywhere;
}
.item-summary em {
  font-style: normal;
  font-size: 12px;
  color: var(--wao-primary-text);
}
.item-status {
  padding: 3px 7px;
  border-radius: 999px;
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
  font-size: 12px;
}
.item-status.disabled {
  background: var(--wao-bg-hover);
  color: var(--wao-text-weak);
}
.item-summary button {
  min-height: 36px;
  border-radius: 8px;
}
.edit-item {
  padding: 0 9px;
  border: 1px solid var(--wao-line-control) !important;
  background: var(--wao-bg-card) !important;
  color: var(--wao-text-2) !important;
}
.edit-item i {
  margin-right: 4px;
}
.remove-item {
  width: 32px;
  border: 1px solid #e2c5c1 !important;
  background: var(--wao-danger-bg) !important;
  color: #a04f45 !important;
}
.restore-item {
  padding: 0 9px;
  border: 1px solid var(--wao-success-line) !important;
  background: var(--wao-success-bg) !important;
}
.restore-item i {
  margin-right: 4px;
}
.item-detail {
  padding: 12px;
  border-top: 1px solid var(--wao-divider);
  background: var(--wao-bg-card);
}
.item-title-field input {
  font-weight: 650;
}
.item-fields {
  display: grid;
  grid-template-columns: 1fr 1fr 0.8fr 0.8fr auto;
  gap: 7px;
  margin-top: 9px;
}
.inline {
  display: flex !important;
  align-items: end !important;
  grid-auto-flow: column;
  justify-content: start;
  font-size: 12px;
}
.inline input,
.item-editor input[type='checkbox'] {
  width: auto;
  height: auto;
}
.weekday-row {
  display: flex;
  gap: 5px;
  margin-top: 8px;
}
.weekday-row button {
  padding: 5px 8px;
  border: 1px solid var(--wao-line);
  border-radius: 7px;
  color: var(--wao-text-weak);
}
.weekday-row button.active {
  border-color: var(--wao-primary);
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.notify-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px dashed var(--wao-line);
}
.notify-row b {
  font-size: 12px;
}
.notify-row label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
}
.target-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 8px;
}
.daily-settings > footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 15px;
  margin-top: 14px;
  font-size: 12px;
  color: var(--wao-success-strong);
}
.daily-settings > footer .primary {
  padding: 8px 15px;
  border-radius: 8px;
  background: var(--wao-primary);
  color: #fff;
}
.save-error {
  font-size: 12px;
}
@media (max-width: 700px) {
  .setting-grid,
  .target-row {
    grid-template-columns: 1fr;
  }
  .item-fields {
    grid-template-columns: 1fr 1fr;
  }
  .item-summary {
    grid-template-columns: minmax(0, 1fr) auto auto;
  }
  .item-index {
    display: none;
  }
  .item-main {
    grid-column: 1 / -1;
  }
  .item-summary > em {
    grid-column: 1 / -1;
  }
  .item-status {
    grid-column: 1;
    justify-self: start;
  }
  .item-summary button {
    min-width: 44px;
    min-height: 44px;
  }
  .edit-item,
  .restore-item {
    font-size: 13px;
    width: auto;
  }
  .item-editor input:not([type='checkbox']),
  .item-editor select {
    width: 100%;
    font-size: 16px;
  }
  .weekday-row {
    flex-wrap: wrap;
  }
  .weekday-row button {
    min-width: 40px;
    min-height: 40px;
  }
  .daily-settings > header,
  .items-settings > header {
    flex-wrap: wrap;
  }
}
@media (max-width: 420px) {
  .item-fields {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
