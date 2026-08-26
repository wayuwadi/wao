<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  fetchHttpPerformanceInspectionConfig,
  saveHttpPerformanceInspectionConfig,
  type HttpPerformanceInspectionConfig,
} from '@/api/board'
import { fetchWebhookEndpoints, type FeishuWebhookEndpoint } from '@/api/system'
import { toast } from '@/composables/toast'

const emit = defineEmits<{
  close: []
  saved: [config: HttpPerformanceInspectionConfig]
}>()

const form = reactive<HttpPerformanceInspectionConfig>({
  enabled: true,
  frequency: 'DAILY',
  time: '06:30',
  windowHours: 24,
  topN: 5,
  maxEndpointsPerRun: 2,
  notificationWebhookId: null,
})
const webhooks = ref<FeishuWebhookEndpoint[]>([])
const loading = ref(true)
const loaded = ref(false)
const saving = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [config, targets] = await Promise.all([
      fetchHttpPerformanceInspectionConfig(),
      fetchWebhookEndpoints(),
    ])
    Object.assign(form, config)
    webhooks.value = targets
    loaded.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '巡检配置读取失败，请重试'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (form.maxEndpointsPerRun > form.topN) {
    error.value = '单次最多治理接口数不能超过 Top N 候选数'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const result = await saveHttpPerformanceInspectionConfig({ ...form })
    Object.assign(form, result)
    emit('saved', result)
    toast('HTTP 性能巡检配置已生效', 'ri-check-line')
    emit('close')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '配置保存失败，当前输入已保留'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="mask" @click.self="emit('close')">
    <form class="dialog" @submit.prevent="save">
      <header>
        <div>
          <span>REAL METRICS → AI RUN</span>
          <h2>HTTP 性能巡检设置</h2>
        </div>
        <button type="button" aria-label="关闭" @click="emit('close')">
          <i class="ri-close-line"></i>
        </button>
      </header>
      <div v-if="loading" class="state">
        <i class="ri-loader-4-line spin"></i>正在读取真实任务配置…
      </div>
      <div v-else-if="!loaded" class="load-error" role="alert">
        <i class="ri-error-warning-line"></i><span>{{ error }}</span
        ><button type="button" @click="load">重新读取</button>
      </div>
      <template v-else>
        <label class="switch-row"
          ><input v-model="form.enabled" type="checkbox" /><span
            ><b>启用自动巡检</b><small>关闭后仍可从请求监控页或任务列表手动运行。</small></span
          ></label
        >
        <div class="grid">
          <label
            ><span>频率</span
            ><select v-model="form.frequency">
              <option value="DAILY">每天</option>
              <option value="WEEKDAYS">工作日</option>
              <option value="WEEKLY">每周一</option>
            </select></label
          >
          <label><span>执行时间</span><input v-model="form.time" type="time" required /></label>
          <label
            ><span>指标窗口</span>
            <div class="unit">
              <input v-model.number="form.windowHours" type="number" min="1" max="168" required /><b
                >小时</b
              >
            </div></label
          >
          <label
            ><span>Top N 候选接口</span
            ><input v-model.number="form.topN" type="number" min="1" max="20" required /><small
              >按平均耗时从真实聚合指标选入任务上下文。</small
            ></label
          >
          <label
            ><span>单次最多治理</span>
            <div class="unit">
              <input
                v-model.number="form.maxEndpointsPerRun"
                type="number"
                min="1"
                :max="form.topN"
                required
              /><b>个接口</b>
            </div>
            <small>一个 AI Run 最多实际改动的接口数，避免一次任务范围过大。</small></label
          >
          <label
            ><span>启动通知群</span
            ><select v-model="form.notificationWebhookId">
              <option :value="null">系统默认通知群</option>
              <option
                v-for="item in webhooks.filter((entry) => entry.enabled)"
                :key="item.id"
                :value="item.id"
              >
                {{ item.name }}
              </option></select
            ><small>选定后额外发送巡检触发原因；AI 生命周期通知仍走系统默认规则。</small></label
          >
        </div>
        <div v-if="error" class="error" role="alert">{{ error }}</div>
      </template>
      <footer>
        <button type="button" class="ghost" @click="emit('close')">取消</button
        ><button class="primary" :disabled="loading || !loaded || saving">
          {{ saving ? '保存中…' : '保存并生效' }}
        </button>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(18, 20, 30, 0.38);
  display: grid;
  place-items: center;
  padding: 18px;
}
.dialog {
  width: min(620px, 100%);
  max-height: calc(100vh - 36px);
  overflow: auto;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
  padding: 20px;
}
.dialog header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.dialog header span {
  font-size: 9px;
  letter-spacing: 1.3px;
  color: var(--wao-primary-text);
  font-weight: 800;
}
.dialog h2 {
  margin: 4px 0 0;
  font-size: 19px;
}
.dialog header button {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.state {
  min-height: 240px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.load-error {
  min-height: 240px;
  display: grid;
  justify-items: center;
  align-content: center;
  gap: 8px;
  color: var(--wao-danger-strong);
  font-size: 12px;
  text-align: center;
}
.load-error button {
  border: 0;
  background: transparent;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.switch-row {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  margin-top: 20px;
  padding: 12px;
  border-radius: 10px;
  background: var(--wao-bg-sub);
}
.switch-row > input {
  margin-top: 2px;
}
.switch-row span {
  display: grid;
  gap: 3px;
}
.switch-row b,
.grid label > span {
  font-size: 12px;
  font-weight: 650;
}
.switch-row small,
.grid small {
  color: var(--wao-text-weak);
  font-size: 10px;
  line-height: 1.5;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-top: 18px;
}
.grid label {
  display: grid;
  gap: 7px;
  align-content: start;
}
.grid input,
.grid select {
  width: 100%;
  height: 36px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 0 10px;
}
.unit {
  display: flex;
  align-items: center;
  gap: 7px;
}
.unit b {
  white-space: nowrap;
  font-size: 11px;
}
.error {
  margin-top: 14px;
  padding: 9px 10px;
  border-radius: 8px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  font-size: 11px;
}
.dialog footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 22px;
}
.ghost,
.primary {
  height: 34px;
  padding: 0 14px;
  border-radius: 8px;
  cursor: pointer;
}
.ghost {
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}
.primary {
  border: 0;
  background: var(--wao-primary);
  color: #fff;
}
.primary:disabled {
  opacity: 0.55;
  cursor: wait;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 620px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
