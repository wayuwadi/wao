<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AiSettingsToggleRow from '@/components/ui/AiSettingsToggleRow.vue'
import {
  fetchFeishuChats,
  fetchWebhookEndpoints,
  type FeishuChat,
  type FeishuWebhookEndpoint,
} from '@/api/system'
import {
  workdayNotificationTarget,
  type WorkdayNotificationKind,
  type WorkdayNotificationTarget,
  type WorkdayRhythmEnergyNotification,
} from '@/utils/workdayRhythm'

const props = defineProps<{
  modelValue: WorkdayRhythmEnergyNotification
  disabled?: boolean
  onlyTianren?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: WorkdayRhythmEnergyNotification] }>()
const chats = ref<FeishuChat[]>([])
const webhooks = ref<FeishuWebhookEndpoint[]>([])
const loading = ref(false)
const errors = ref<string[]>([])
const routes = [
  {
    kind: 'energy',
    title: '精力补给',
    flag: 'notifyEnergy',
    description: '补给行动记录成功后发送；各项目仍可在「精力补给」里单独关闭通知。',
  },
  {
    kind: 'focus',
    title: '今日重点',
    flag: 'notifyFocus',
    description: '今日重点保存成功后发送。',
  },
  {
    kind: 'tianren',
    title: '卦象解读',
    flag: 'notifyTianren',
    description: '当天已保存的解读和公开结果链接发到所选群；每天一张，重新解读不重复发送。',
  },
] as const
const options = computed(() => [
  ...webhooks.value
    .filter((item) => item.enabled)
    .map((item) => ({
      value: `WEBHOOK:${item.id}`,
      name: item.name,
      channel: 'WEBHOOK' as const,
      id: item.id,
    })),
  ...chats.value.map((item) => ({
    value: `CHAT:${item.chatId}`,
    name: item.name,
    channel: 'CHAT' as const,
    id: item.chatId,
  })),
])
function targetValue(kind: WorkdayNotificationKind) {
  const target = workdayNotificationTarget(props.modelValue, kind)
  const id = target.channel === 'WEBHOOK' ? target.webhookId : target.chatId
  return id ? `${target.channel}:${id}` : ''
}
function targetName(kind: WorkdayNotificationKind) {
  const target = workdayNotificationTarget(props.modelValue, kind)
  return target.channel === 'WEBHOOK' ? target.webhookName : target.chatName
}
function missing(kind: WorkdayNotificationKind) {
  return !!targetValue(kind) && !options.value.some((option) => option.value === targetValue(kind))
}
function copyConfig() {
  // 在修改原精力补给目标前冻结旧版共用目标，避免另两类跟着改变。
  return {
    ...props.modelValue,
    focusTarget: { ...workdayNotificationTarget(props.modelValue, 'focus') },
    tianrenTarget: { ...workdayNotificationTarget(props.modelValue, 'tianren') },
  }
}
function updateFlag(
  flag: 'enabled' | 'notifyEnergy' | 'notifyFocus' | 'notifyTianren' | 'autoTianrenAtNine',
  event: Event | boolean,
) {
  emit('update:modelValue', {
    ...copyConfig(),
    [flag]: typeof event === 'boolean' ? event : (event.target as HTMLInputElement).checked,
  })
}
function selectTarget(kind: WorkdayNotificationKind, event: Event) {
  const selected = options.value.find(
    (option) => option.value === (event.target as HTMLSelectElement).value,
  )
  const target: WorkdayNotificationTarget = {
    channel: selected?.channel ?? 'WEBHOOK',
    webhookId: '',
    webhookName: '',
    chatId: '',
    chatName: '',
  }
  if (selected?.channel === 'WEBHOOK') {
    target.webhookId = selected.id
    target.webhookName = selected.name
  }
  if (selected?.channel === 'CHAT') {
    target.chatId = selected.id
    target.chatName = selected.name
  }
  const next = copyConfig()
  if (kind === 'energy') Object.assign(next, target)
  else if (kind === 'focus') next.focusTarget = target
  else next.tianrenTarget = target
  emit('update:modelValue', next)
}
async function refresh() {
  if (loading.value) return
  loading.value = true
  errors.value = []
  await Promise.allSettled([
    fetchWebhookEndpoints()
      .then((value) => {
        webhooks.value = value
      })
      .catch(() => {
        errors.value.push('已配置通知群读取失败，请刷新重试。')
      }),
    fetchFeishuChats()
      .then((value) => {
        chats.value = value
      })
      .catch(() => {
        errors.value.push('飞书机器人所在群读取失败，请刷新重试。')
      }),
  ])
  loading.value = false
}
onMounted(refresh)
</script>

<template>
  <section
    class="notification-routes"
    :class="{ 'tianren-only': onlyTianren }"
    aria-label="分类通知设置"
  >
    <template v-if="onlyTianren">
      <div class="section-heading">
        <div>
          <h3>飞书通知</h3>
          <p>选择通知群，安排每天的卦象解读。</p>
        </div>
      </div>
      <fieldset :disabled="disabled">
        <AiSettingsToggleRow
          :model-value="modelValue.enabled"
          title="行动通知总开关"
          description="同时控制卦象、精力补给和今日重点通知；关闭后已选群保留。"
          icon="ri-notification-3-line"
          control-label="启用行动通知"
          @update:model-value="updateFlag('enabled', $event)"
        />
        <AiSettingsToggleRow
          :model-value="!!modelValue.notifyTianren"
          title="发送卦象解读"
          description="每天发送一张已保存解读的卡片，重新解读不会重复发送。"
          icon="ri-send-plane-line"
          control-label="卦象解读发送通知"
          @update:model-value="updateFlag('notifyTianren', $event)"
        />
        <AiSettingsToggleRow
          :model-value="!!modelValue.autoTianrenAtNine"
          title="每天 9:00 自动起卦并解读"
          description="北京时间，周末照常；需启用通知并选择目标群。"
          icon="ri-time-line"
          control-label="每天 9:00 自动起卦并解读"
          @update:model-value="updateFlag('autoTianrenAtNine', $event)"
        />
      </fieldset>
      <p v-if="!modelValue.enabled" class="muted">当前暂停全部行动通知，已选的群会保留。</p>
      <div v-if="modelValue.autoTianrenAtNine" class="behavior-note">
        <i class="ri-information-line" aria-hidden="true"></i
        ><span>无需打开页面；当天已起卦会沿用。AI 暂不可用时稍后继续，失败最多自动尝试三次。</span>
      </div>
      <label class="target-field"
        ><span>目标群</span>
        <select
          aria-label="卦象解读目标群"
          :value="targetValue('tianren')"
          :disabled="disabled || (loading && !options.length)"
          @change="selectTarget('tianren', $event)"
        >
          <option value="">请选择通知群</option>
          <option v-if="missing('tianren')" :value="targetValue('tianren')" disabled>
            已保存 · {{ targetName('tianren') || '原目标' }}（{{
              loading ? '读取中' : '暂不可用'
            }}）
          </option>
          <optgroup label="已配置通知群（Webhook）">
            <option
              v-for="option in options.filter((item) => item.channel === 'WEBHOOK')"
              :key="option.value"
              :value="option.value"
            >
              {{ option.name }}
            </option>
          </optgroup>
          <optgroup label="飞书机器人所在群">
            <option
              v-for="option in options.filter((item) => item.channel === 'CHAT')"
              :key="option.value"
              :value="option.value"
            >
              {{ option.name }}
            </option>
          </optgroup>
        </select>
      </label>
    </template>
    <template v-else
      ><header>
        <div>
          <h3>通知群设置</h3>
          <p>
            {{
              onlyTianren
                ? '卦象通知保存后跨设备生效。'
                : '三类通知分别选群，互不影响；保存后跨设备生效。'
            }}
          </p>
        </div>
        <label class="toggle-field"
          ><input
            type="checkbox"
            :checked="modelValue.enabled"
            :disabled="disabled"
            @change="updateFlag('enabled', $event)"
          /><span>启用行动通知</span></label
        >
      </header>
      <p v-if="!modelValue.enabled" class="muted">当前暂停全部行动通知，已选的群会保留。</p>
      <article
        v-for="route in routes.filter((item) => !onlyTianren || item.kind === 'tianren')"
        :key="route.kind"
        :data-notification-kind="route.kind"
      >
        <div class="route-heading">
          <h4>{{ route.title }}</h4>
          <label
            ><input
              type="checkbox"
              :aria-label="`${route.title}发送通知`"
              :disabled="disabled"
              :checked="
                route.kind === 'energy'
                  ? modelValue.notifyEnergy !== false
                  : !!modelValue[route.flag]
              "
              @change="updateFlag(route.flag, $event)"
            />发送通知</label
          >
        </div>
        <p>{{ route.description }}</p>
        <label v-if="route.kind === 'tianren'" class="daily-cast-setting">
          <input
            type="checkbox"
            aria-label="每天 9:00 自动起卦并解读"
            :checked="!!modelValue.autoTianrenAtNine"
            :disabled="disabled"
            @change="updateFlag('autoTianrenAtNine', $event)"
          />
          每天 9:00 自动起卦并解读（北京时间）
        </label>
        <p v-if="route.kind === 'tianren' && modelValue.autoTianrenAtNine" class="muted">
          每天执行，周末照常；无需打开页面。需同时启用行动通知和卦象通知并选群，解读完成后发群。
          当天已起卦会沿用；AI 暂不可用时稍后继续，失败最多自动尝试三次。
        </p>
        <label class="target-field"
          ><span>目标群</span>
          <select
            :aria-label="`${route.title}目标群`"
            :value="targetValue(route.kind)"
            :disabled="disabled || (loading && !options.length)"
            @change="selectTarget(route.kind, $event)"
          >
            <option value="">请选择通知群</option>
            <option v-if="missing(route.kind)" :value="targetValue(route.kind)" disabled>
              已保存 · {{ targetName(route.kind) || '原目标' }}（{{
                loading ? '读取中' : '暂不可用'
              }}）
            </option>
            <optgroup label="已配置通知群（Webhook）">
              <option
                v-for="option in options.filter((item) => item.channel === 'WEBHOOK')"
                :key="option.value"
                :value="option.value"
              >
                {{ option.name }}
              </option>
            </optgroup>
            <optgroup label="飞书机器人所在群">
              <option
                v-for="option in options.filter((item) => item.channel === 'CHAT')"
                :key="option.value"
                :value="option.value"
              >
                {{ option.name }}
              </option>
            </optgroup>
          </select>
        </label>
      </article>
    </template>
    <div class="route-options-status" aria-live="polite">
      <span v-if="loading">正在读取可用通知群…</span>
      <span v-else-if="!options.length && !errors.length"
        >暂无可选群，请先在系统设置中添加通知群或将飞书机器人加入目标群。</span
      >
      <p v-for="error in errors" :key="error" role="alert">{{ error }}</p>
      <button type="button" :disabled="loading || disabled" @click="refresh">
        {{ loading ? '读取中' : '刷新可选群' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.tianren-only {
  gap: 16px;
}
.tianren-only h3 {
  font-size: 16px;
}
.tianren-only fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.tianren-only .target-field {
  grid-template-columns: 1fr;
  align-items: stretch;
  gap: 8px;
}
.tianren-only .target-field select {
  height: 40px;
  font-size: 14px;
}
.daily-cast-setting {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12px;
}
.notification-routes {
  display: grid;
  gap: 12px;
  min-width: 0;
}
.notification-routes header,
.route-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
h3,
h4,
p {
  margin: 0;
}
h3 {
  font-size: 17px;
}
h4 {
  font-size: 15px;
}
p,
.muted {
  color: var(--wao-text-3);
  font-size: 12px;
  line-height: 1.6;
}
header p {
  margin-top: 4px;
}
article {
  display: grid;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-sub);
  min-width: 0;
}
label {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
}
input {
  accent-color: var(--wao-primary);
}
.target-field {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr);
}
select {
  width: 100%;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
}
.route-options-status {
  display: grid;
  gap: 6px;
  font-size: 12px;
  color: var(--wao-text-3);
}
.route-options-status p {
  color: var(--wao-danger);
}
button {
  justify-self: start;
  padding: 6px 10px;
  border: 1px solid var(--wao-line);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  cursor: pointer;
}
button:disabled,
select:disabled {
  opacity: 0.6;
  cursor: wait;
}
@media (max-width: 480px) {
  article {
    padding: 10px;
  }
  .target-field {
    grid-template-columns: 1fr;
  }
}
</style>
