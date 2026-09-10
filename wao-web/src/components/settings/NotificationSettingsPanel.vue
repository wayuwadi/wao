<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  fetchNotificationSettings,
  saveNotificationSettings,
  type NotificationSettings,
  type PersonalNotificationPolicy,
  type SystemNotificationPolicy,
} from '@/api/notificationSettings'
import DailyReminderSettingsPanel from './DailyReminderSettingsPanel.vue'

const snapshot = ref<NotificationSettings | null>(null)
const system = ref<SystemNotificationPolicy | null>(null)
const personal = ref<PersonalNotificationPolicy | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const savedAt = ref('')
const clock = ref(new Date())
let clockTimer: number | undefined
const reminderPanel = ref<{ hasDirty: boolean } | null>(null)
type BooleanSystemPolicyKey = Exclude<keyof SystemNotificationPolicy, 'quietStart' | 'quietEnd'>

const dirty = computed(() => Boolean(snapshot.value && system.value && personal.value)
  && (JSON.stringify(system.value) !== JSON.stringify(snapshot.value!.system)
    || JSON.stringify(personal.value) !== JSON.stringify(snapshot.value!.personal)))

const systemRows: Array<{ key: BooleanSystemPolicyKey; title: string; detail: string }> = [
  { key: 'taskActivity', title: '任务普通动态', detail: '新建、状态变化、子任务与普通评论；默认关闭以减少噪音' },
  { key: 'taskSuccess', title: '任务成功通知', detail: '任务进入已完成时推送；默认关闭' },
  { key: 'aiRunSuccess', title: 'AI Run 成功通知', detail: '成功后生成待验收通知；默认关闭' },
  { key: 'aiRunFailure', title: 'AI Run 失败通知', detail: '失败或未报告终态时推送真实原因与处理入口' },
  { key: 'manualTask', title: '人工通知任务', detail: '允许成员在任务详情明确点击后发送一张任务卡片' },
  { key: 'reminders', title: '站内提醒', detail: '日历、今日节奏和自动化定时提醒' },
  { key: 'deliveryFailure', title: 'Jenkins 失败通知', detail: '构建或部署失败时发送真实失败阶段；成功部署继续保持静默' },
  { key: 'automation', title: '自动触发说明', detail: '闲置派工、HTTP 巡检等无人操作场景说明为什么启动' },
]

const quietNow = computed(() => {
  if (!system.value?.enabled || !system.value.quietHoursEnabled) return false
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(clock.value)
  const current = Number(parts.find((part) => part.type === 'hour')?.value ?? 0) * 60
    + Number(parts.find((part) => part.type === 'minute')?.value ?? 0)
  const minutes = (value: string) => {
    const [hour = '0', minute = '0'] = value.split(':')
    return Number(hour) * 60 + Number(minute)
  }
  const start = minutes(system.value.quietStart)
  const end = minutes(system.value.quietEnd)
  return start < end ? current >= start && current < end : current >= start || current < end
})

const personalRows: Array<{ key: keyof PersonalNotificationPolicy; title: string; detail: string }> = [
  { key: 'enabled', title: '我的站内通知', detail: '只影响当前账号的通知中心动态与未读数' },
  { key: 'taskCreated', title: '任务创建', detail: '在通知中心展示真实新建任务记录' },
  { key: 'taskCompleted', title: '任务完成', detail: '在通知中心展示真实完成记录' },
  { key: 'aiRunSuccess', title: 'AI Run 成功', detail: '在通知中心保留成功运行，不产生群消息' },
  { key: 'aiRunFailure', title: 'AI Run 失败', detail: '在通知中心保留失败运行与处理入口' },
  { key: 'loginActivity', title: '成员登录', detail: '展示团队真实成功登录动态，不含设备或身份标识' },
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    const value = await fetchNotificationSettings()
    snapshot.value = structuredClone(value)
    system.value = structuredClone(value.system)
    personal.value = structuredClone(value.personal)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '通知设置读取失败，请重试'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!system.value || !personal.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const value = await saveNotificationSettings({
      ...(snapshot.value?.canManageSystem ? { system: { ...system.value } } : {}),
      personal: { ...personal.value },
    })
    snapshot.value = structuredClone(value)
    system.value = structuredClone(value.system)
    personal.value = structuredClone(value.personal)
    savedAt.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date())
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '通知设置保存失败，请重试'
  } finally {
    saving.value = false
  }
}

function confirmLeave() {
  return (!dirty.value && !reminderPanel.value?.hasDirty)
    || window.confirm('通知设置还有未保存的修改，确认离开吗？')
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value && !reminderPanel.value?.hasDirty) return
  event.preventDefault()
}

defineExpose({ confirmLeave })
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  clockTimer = window.setInterval(() => { clock.value = new Date() }, 60_000)
  void load()
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnload)
  if (clockTimer !== undefined) window.clearInterval(clockTimer)
})
</script>

<template>
  <div class="notification-settings">
    <header>
      <div><h1>通知设置</h1><p>系统群推送由管理员共享管理；站内偏好只属于当前成员。</p></div>
      <span v-if="saving" class="save-state"><i class="ri-loader-4-line spin"></i>保存中</span>
      <span v-else-if="savedAt" class="save-state saved"><i class="ri-check-line"></i>{{ savedAt }} 已保存</span>
    </header>

    <div v-if="loading && !snapshot" class="panel-state"><i class="ri-loader-4-line spin"></i>正在读取真实配置…</div>
    <div v-else-if="error && !snapshot" class="panel-state error" role="alert">
      <i class="ri-wifi-off-line"></i>{{ error }}<button type="button" @click="load">重新加载</button>
    </div>
    <template v-else-if="snapshot && system && personal">
      <div v-if="error" class="inline-error" role="alert">{{ error }}<button type="button" @click="save">重试保存</button></div>

      <section class="policy-card">
        <div class="policy-head"><div><h2>系统 · 群聊通知</h2><p>全组织共享，覆盖 Jenkins、任务、提醒和其他 WAO Webhook 消息。</p></div><span>{{ snapshot.canManageSystem ? '管理员可编辑' : '仅管理员可编辑' }}</span></div>
        <div class="effective-state" :class="{ quiet: quietNow, off: !system.enabled }">
          <i :class="!system.enabled ? 'ri-notification-off-line' : quietNow ? 'ri-moon-clear-line' : 'ri-notification-3-line'"></i>
          <span><b>{{ !system.enabled ? '全部群通知已关闭' : quietNow ? '当前处于沉默时段' : '当前允许群通知' }}</b><small>上海时间（UTC+8）· 私聊消息不受影响</small></span>
        </div>
        <label class="policy-row">
          <span><b>系统通知总开关</b><small>控制 WAO 业务通知；已保存的记录仍保留</small></span>
          <input v-model="system.enabled" type="checkbox" :disabled="!snapshot.canManageSystem || saving" />
        </label>
        <div class="quiet-row">
          <label><span><b>每日沉默时段</b><small>时段内所有群通知直接跳过，不积压到次日补发</small></span><input v-model="system.quietHoursEnabled" type="checkbox" :disabled="!snapshot.canManageSystem || saving" /></label>
          <div class="quiet-times" :class="{ disabled: !system.quietHoursEnabled }">
            <input v-model="system.quietStart" type="time" aria-label="沉默开始时间" :disabled="!snapshot.canManageSystem || saving || !system.quietHoursEnabled" />
            <span>至</span>
            <input v-model="system.quietEnd" type="time" aria-label="沉默结束时间" :disabled="!snapshot.canManageSystem || saving || !system.quietHoursEnabled" />
            <small>上海时间，支持跨午夜</small>
          </div>
        </div>
        <label v-for="row in systemRows" :key="row.key" class="policy-row">
          <span><b>{{ row.title }}</b><small>{{ row.detail }}</small></span>
          <input v-model="system[row.key]" type="checkbox" :disabled="!snapshot.canManageSystem || saving" />
        </label>
      </section>

      <section class="policy-card">
        <div class="policy-head"><div><h2>个人 · 站内通知中心</h2><p>按当前登录成员隔离，不改变其他成员看到的动态。</p></div><span>仅自己</span></div>
        <label v-for="row in personalRows" :key="row.key" class="policy-row">
          <span><b>{{ row.title }}</b><small>{{ row.detail }}</small></span>
          <input v-model="personal[row.key]" type="checkbox" :disabled="saving" />
        </label>
      </section>

      <DailyReminderSettingsPanel ref="reminderPanel" />

      <footer><span>{{ dirty ? '有未保存修改' : '当前设置已同步' }}</span><button type="button" :disabled="!dirty || saving" @click="save">{{ saving ? '保存中…' : '保存通知设置' }}</button></footer>
    </template>
  </div>
</template>

<style scoped>
.notification-settings { max-width: 820px; display: grid; gap: 14px; }
header { display: flex; align-items: center; gap: 16px; }
header > div { flex: 1; }
h1, h2, p { margin: 0; }
h1 { font-size: 20px; }
header p, .policy-head p { margin-top: 5px; color: var(--wao-text-weak); font-size: 12px; }
.save-state { color: var(--wao-text-weak); font-size: 12px; display: inline-flex; gap: 5px; }
.save-state.saved { color: var(--wao-success); }
.policy-card { border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); overflow: hidden; }
.policy-head { padding: 15px 16px; background: var(--wao-bg-sub); display: flex; align-items: center; gap: 12px; }
.policy-head > div { flex: 1; }
.policy-head h2 { font-size: 14px; }
.policy-head > span { color: var(--wao-primary-text); font-size: 11px; }
.policy-row { min-height: 58px; padding: 8px 16px; border-top: 1px solid var(--wao-divider); display: flex; align-items: center; gap: 12px; }
.policy-row > span { flex: 1; min-width: 0; display: grid; gap: 3px; }
.policy-row b { font-size: 13px; }
.policy-row small { color: var(--wao-text-weak); font-size: 11px; line-height: 1.5; }
.policy-row input { width: 36px; height: 20px; accent-color: var(--wao-primary); cursor: pointer; }
.policy-row input:disabled { cursor: default; opacity: .55; }
.effective-state { margin: 12px 16px 4px; padding: 10px 12px; border-radius: 9px; display: flex; align-items: center; gap: 9px; color: var(--wao-success); background: color-mix(in srgb, var(--wao-success) 9%, transparent); }
.effective-state.quiet { color: var(--wao-warning-strong); background: var(--wao-warning-bg); }
.effective-state.off { color: var(--wao-text-weak); background: var(--wao-bg-sub); }
.effective-state > span { display: grid; gap: 2px; }
.effective-state small { font-size: 10px; color: var(--wao-text-weak); }
.quiet-row { padding: 10px 16px 14px; border-top: 1px solid var(--wao-divider); display: grid; gap: 10px; }
.quiet-row > label { display: flex; align-items: center; gap: 12px; }
.quiet-row > label > span { flex: 1; display: grid; gap: 3px; }
.quiet-row b { font-size: 13px; }
.quiet-row small { color: var(--wao-text-weak); font-size: 11px; }
.quiet-row > label > input { width: 36px; height: 20px; accent-color: var(--wao-primary); }
.quiet-times { display: flex; align-items: center; gap: 8px; padding-left: 12px; }
.quiet-times.disabled { opacity: .55; }
.quiet-times input { padding: 6px 8px; border: 1px solid var(--wao-line); border-radius: 7px; color: var(--wao-text); background: var(--wao-bg-card); }
footer { position: sticky; bottom: 12px; padding: 10px 12px; border: 1px solid var(--wao-line); border-radius: 10px; background: color-mix(in srgb, var(--wao-bg-card) 94%, transparent); box-shadow: var(--wao-shadow-pop); display: flex; align-items: center; gap: 12px; }
footer span { flex: 1; color: var(--wao-text-weak); font-size: 12px; }
footer button, .panel-state button, .inline-error button { padding: 7px 12px; border: 0; border-radius: 7px; background: var(--wao-primary); color: #fff; cursor: pointer; }
footer button:disabled { opacity: .5; cursor: default; }
.panel-state { min-height: 180px; border: 1px solid var(--wao-line); border-radius: 12px; color: var(--wao-text-weak); display: flex; align-items: center; justify-content: center; gap: 8px; }
.panel-state.error, .inline-error { color: var(--wao-danger-strong); }
.inline-error { padding: 9px 12px; border: 1px solid var(--wao-danger-line); border-radius: 8px; background: var(--wao-danger-bg); display: flex; align-items: center; gap: 8px; }
.inline-error button { margin-left: auto; }
.spin { animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 640px) { .policy-head { align-items: flex-start; }.policy-row { padding: 10px 12px; } }
</style>
