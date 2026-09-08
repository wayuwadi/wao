<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchAiProjectConfig, fetchAiRunners, fetchAiRunOptions, saveAiRunDefaultExecutionMode } from '@/api/ai'
import { fetchAiAccounts, type AiAccount } from '@/api/aiAccount'
import { AI_ENGINES, engineColor } from '@/composables/aiEngines'
import { usePreferenceStore } from '@/stores/preferences'
import type { AiEngine, AiRunner, AiRunOptions } from '@/types'

const props = defineProps<{ projectId: string; starting?: boolean; scope?: string }>()
const preferences = usePreferenceStore()
const emit = defineEmits<{
  close: []
  start: [engine: AiEngine, runnerId: string | null, accountId: string | null,
    model: string | null, reasoningEffort: string | null, planMode: boolean, goalMode: boolean]
}>()

const loading = ref(true)
const runners = ref<AiRunner[]>([])
const accounts = ref<AiAccount[]>([])
const engine = ref<AiEngine>('codex')
const runnerId = ref('')
const accountId = ref('')
const runOptions = ref<AiRunOptions | null>(null)
const model = ref('')
const reasoningEffort = ref('')
type ExecutionMode = 'direct' | 'plan' | 'goal'
const executionMode = ref<ExecutionMode>('plan')
const defaultExecutionMode = ref<ExecutionMode>('plan')
const defaultModeState = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
const defaultModeError = ref('')
const preferredRunnerId = ref('')
let runnerRefreshTimer: ReturnType<typeof setInterval> | null = null
const executionModeLabels: Record<ExecutionMode, string> = {
  direct: '直接执行', plan: 'Plan 模式', goal: '目标模式',
}

function normalizeExecutionMode(value: unknown): ExecutionMode {
  return value === 'direct' || value === 'goal' ? value : 'plan'
}

/** 装了这种 AI 工具的电脑；离线的也列出来，选中后任务会排队等它上线。 */
const candidates = computed(() => runners.value.filter((runner) => runner.engines.includes(engine.value)))
const eligibleAccounts = computed(() => accounts.value.filter(
  (account) => account.enabled && account.provider === engine.value,
))
const linuxCandidate = computed(() => {
  const linux = candidates.value.filter((runner) =>
    runner.platform.toLowerCase().includes('linux') || runner.name.toLowerCase().includes('linux'))
  return linux.find((runner) => runner.online && runner.name.includes('主节点'))
    ?? linux.find((runner) => runner.online)
    ?? linux.find((runner) => runner.name.includes('主节点'))
    ?? linux[0]
})
const centralAccountSelected = computed(() => accountId.value !== '')
const selectedAccount = computed(() => accounts.value.find((account) => account.id === accountId.value))
const customApiSelected = computed(() => selectedAccount.value?.authMethod === 'api_key')
const codexParametersEnabled = computed(() => engine.value === 'codex' && !customApiSelected.value)
const centralRunnerMissing = computed(() =>
  centralAccountSelected.value && !linuxCandidate.value && !runnerId.value)
const availability = computed(() =>
  AI_ENGINES.map((item) => ({
    ...item,
    online: runners.value.filter((runner) => runner.online && runner.engines.includes(item.id)).length,
    total: runners.value.filter((runner) => runner.engines.includes(item.id)).length,
  })),
)
const hint = computed(() => {
  const picked = candidates.value.find((runner) => runner.id === runnerId.value)
  if (picked) return picked.online ? `将由 ${picked.name} 领取` : `${picked.name} 当前离线，任务会排队等它上线`
  if (centralAccountSelected.value && !linuxCandidate.value) {
    return `Linux 主节点尚未接入 ${AI_ENGINES.find((item) => item.id === engine.value)?.label ?? engine.value}，请先安装并重启 Runner，或明确改选其他电脑`
  }
  if (centralAccountSelected.value) return '集中账号默认由 Linux 主节点领取'
  const online = candidates.value.filter((runner) => runner.online).length
  return online ? `将由 ${online} 台在线电脑中最先空闲的一台领取` : '当前没有在线电脑，任务会先排队'
})

function pickEngine(next: AiEngine) {
  engine.value = next
  if (!candidates.value.some((runner) => runner.id === runnerId.value)) runnerId.value = ''
  if (!eligibleAccounts.value.some((account) => account.id === accountId.value)) accountId.value = ''
}

function startRun() {
  emit('start', engine.value, runnerId.value || null, accountId.value || null,
    codexParametersEnabled.value ? model.value : null,
    codexParametersEnabled.value ? reasoningEffort.value : null,
    engine.value === 'codex' && executionMode.value === 'plan',
    engine.value === 'codex' && executionMode.value === 'goal')
}

function applyPreset(kind: 'economy' | 'balanced' | 'quality') {
  const target = kind === 'economy' ? 'gpt-5.6-luna' : kind === 'balanced' ? 'gpt-5.6-terra' : 'gpt-6-astra'
  if (!runOptions.value?.codexModels.some((option) => option.id === target)) return
  model.value = target
  reasoningEffort.value = kind === 'quality' ? 'high' : 'medium'
  executionMode.value = kind === 'economy' ? 'direct' : 'plan'
}

async function saveSelectedModeAsDefault() {
  defaultModeState.value = 'saving'
  defaultModeError.value = ''
  try {
    const options = await saveAiRunDefaultExecutionMode(executionMode.value)
    defaultExecutionMode.value = normalizeExecutionMode(options.defaultExecutionMode)
    if (runOptions.value) runOptions.value = options
    defaultModeState.value = 'saved'
  } catch (error) {
    defaultModeError.value = error instanceof Error ? error.message : '默认执行模式保存失败'
    defaultModeState.value = 'error'
  }
}

watch(accountId, (next) => {
  if (next) {
    runnerId.value = linuxCandidate.value?.id ?? ''
    return
  }
  runnerId.value = candidates.value.some((runner) => runner.id === preferredRunnerId.value)
    ? preferredRunnerId.value
    : ''
})

watch(executionMode, () => {
  if (defaultModeState.value === 'saved') defaultModeState.value = 'idle'
})

async function refreshRunners() {
  try {
    runners.value = await fetchAiRunners()
  } catch {
    // 保留上一次成功状态；短暂网络失败不能把在线电脑闪成离线。
  }
}

onMounted(async () => {
  try {
    const [nodes, config, managedAccounts, options] = await Promise.all([
      fetchAiRunners(), fetchAiProjectConfig(props.projectId), fetchAiAccounts(), fetchAiRunOptions(),
    ])
    runners.value = nodes
    accounts.value = managedAccounts
    runOptions.value = options
    model.value = options.defaultCodexModel
    reasoningEffort.value = options.defaultReasoningEffort
    executionMode.value = normalizeExecutionMode(options.defaultExecutionMode)
    defaultExecutionMode.value = executionMode.value
    // 项目默认工具一台电脑都没装时，直接落到已经接入的那种，省得用户先撞一次 400。
    const supported = AI_ENGINES.map((item) => item.id)
      .filter((id) => nodes.some((runner) => runner.engines.includes(id)))
    engine.value = supported.includes(config.defaultEngine) || !supported.length
      ? config.defaultEngine
      : supported[0]!
    preferredRunnerId.value = config.preferredRunnerId ?? ''
    runnerId.value = candidates.value.some((runner) => runner.id === preferredRunnerId.value)
      ? preferredRunnerId.value
      : ''
  } catch {
    // 读取失败时保留默认选择，用户仍然可以直接开跑。
  } finally {
    loading.value = false
    runnerRefreshTimer = setInterval(refreshRunners, 5_000)
  }
})

onBeforeUnmount(() => {
  if (runnerRefreshTimer) clearInterval(runnerRefreshTimer)
})
</script>

<template>
  <div class="launcher-mask" @click.self="emit('close')"></div>
  <section class="launcher" :class="{ compact: preferences.compactControls }" role="dialog" aria-label="选择执行方式">
    <header>
      <b>交给谁来跑？</b>
      <button class="close" aria-label="关闭" @click="emit('close')"><i class="ri-close-line"></i></button>
    </header>

    <p v-if="scope" class="scope" :title="`本次范围：${scope}`"><i class="ri-focus-3-line"></i>本次范围：{{ scope }}</p>

    <div v-if="loading" class="loading"><i class="ri-loader-4-line spin"></i>正在读取可用的电脑…</div>
    <template v-else>
      <div v-if="!preferences.compactControls" class="label">AI 工具</div>
      <div class="engine-picker">
        <button
          v-for="item in availability"
          :key="item.id"
          class="engine-option"
          :class="{ on: engine === item.id }"
          :style="engine === item.id ? { borderColor: engineColor(item.id), color: engineColor(item.id) } : {}"
          @click="pickEngine(item.id)"
        >
          <i :class="item.icon"></i>
          <b>{{ item.label }}</b>
          <em>{{ item.online ? `${item.online} 台在线` : item.total ? '全部离线' : '未接入' }}</em>
        </button>
      </div>

      <template v-if="engine === 'codex'">
        <template v-if="codexParametersEnabled && runOptions">
          <div class="cost-presets" aria-label="任务配置建议">
            <button @click="applyPreset('economy')">轻量修改</button>
            <button @click="applyPreset('balanced')">日常开发</button>
            <button @click="applyPreset('quality')">复杂任务</button>
          </div>
          <p class="hint">轻量修改用 Luna · 中推理，日常开发用 Terra · 中推理，复杂任务用 GPT-6 · 高推理。续聊请回原运行，减少重复背景。</p>
          <div v-if="!preferences.compactControls" class="label">本次模型</div>
          <select v-model="model" class="runner-select" aria-label="本次模型" title="本次模型">
            <option v-for="item in runOptions.codexModels" :key="item.id" :value="item.id">
              {{ item.label }} · {{ item.description }}
            </option>
          </select>
          <div v-if="!preferences.compactControls" class="label">推理强度</div>
          <select v-model="reasoningEffort" class="runner-select" aria-label="推理强度" title="推理强度">
            <option v-for="item in runOptions.reasoningEfforts" :key="item.id" :value="item.id">
              {{ item.label }} · {{ item.description }}
            </option>
          </select>
          <p class="hint"><i class="ri-speed-line"></i>标准速度（Fast 未开启）</p>
        </template>
        <p v-else-if="customApiSelected" class="hint">
          <i class="ri-information-line"></i>自定义 API 使用账号中配置的模型
        </p>
        <div v-if="!preferences.compactControls" class="label">执行模式</div>
        <div class="execution-modes" role="radiogroup" aria-label="执行模式">
          <button class="plan-mode direct-mode" :class="{ on: executionMode === 'direct' }"
            type="button" role="radio" :aria-checked="executionMode === 'direct'" @click="executionMode = 'direct'">
            <i class="ri-flashlight-line"></i><span><b>直接执行</b><small>适合边界清晰的小改动</small></span>
          </button>
          <button class="plan-mode plan-workflow-mode" :class="{ on: executionMode === 'plan' }"
            type="button" role="radio" :aria-checked="executionMode === 'plan'"
            title="Plan 模式：先规划再连续执行" @click="executionMode = 'plan'">
            <i class="ri-route-line"></i><span><b>Plan 模式</b><small>先规划再执行；推荐项自动选择</small></span>
          </button>
          <button class="plan-mode goal-mode" :class="{ on: executionMode === 'goal' }"
            type="button" role="radio" :aria-checked="executionMode === 'goal'"
            title="目标模式：持续工作到可验证终态" @click="executionMode = 'goal'">
            <i class="ri-focus-3-line"></i><span><b>目标模式</b><small>持续推进到完成或明确阻塞</small></span>
          </button>
        </div>
        <div class="default-mode-setting" :class="{ error: defaultModeState === 'error' }">
          <span>
            <i :class="defaultModeState === 'saving' ? 'ri-loader-4-line spin' : defaultModeState === 'saved' ? 'ri-checkbox-circle-line' : 'ri-pushpin-line'"></i>
            默认：{{ executionModeLabels[defaultExecutionMode] }}
            <small v-if="defaultModeState === 'saving'">保存中…</small>
            <small v-else-if="defaultModeState === 'saved'">已保存</small>
            <small v-else-if="defaultModeState === 'error'" :title="defaultModeError">保存失败</small>
          </span>
          <button
            v-if="executionMode !== defaultExecutionMode || defaultModeState === 'error'"
            type="button"
            :disabled="defaultModeState === 'saving'"
            @click="saveSelectedModeAsDefault"
          >{{ defaultModeState === 'error' ? '重试' : `将${executionModeLabels[executionMode]}设为默认` }}</button>
        </div>
      </template>

      <div v-if="!preferences.compactControls" class="label">运行账号</div>
      <select v-model="accountId" class="runner-select" aria-label="运行账号" title="运行账号">
        <option value="">使用执行电脑本地登录态</option>
        <option v-for="account in eligibleAccounts" :key="account.id" :value="account.id">
          {{ account.label }}{{ account.authMethod === 'api_key' ? ` · ${account.model || '自定义 API'}` : account.email ? ` · ${account.email}` : '' }}
        </option>
      </select>
      <p v-if="!eligibleAccounts.length" class="hint">
        <i class="ri-information-line"></i>没有启用中的集中账号，将使用执行电脑已有登录态
      </p>

      <div v-if="!preferences.compactControls" class="label">执行电脑</div>
      <select v-model="runnerId" class="runner-select" aria-label="执行电脑" :title="`执行电脑：${hint}`">
        <option value="">{{ centralAccountSelected ? '默认使用 Linux 主节点' : '自动分配（任意在线电脑）' }}</option>
        <option v-for="runner in candidates" :key="runner.id" :value="runner.id">
          {{ runner.name }}{{ runner.online ? ` · 在线 ${runner.activeRuns}/${runner.maxConcurrency}` : ' · 离线' }}
        </option>
      </select>
      <p class="hint" :class="{ danger: centralRunnerMissing }"><i :class="centralRunnerMissing ? 'ri-error-warning-line' : 'ri-information-line'"></i>{{ hint }}</p>

      <footer>
        <button class="secondary" @click="emit('close')">取消</button>
        <button class="primary" :disabled="starting || centralRunnerMissing" @click="startRun">
          <i :class="starting ? 'ri-loader-4-line spin' : 'ri-play-circle-line'"></i>
          {{ starting ? '正在入队…' : '开始运行' }}
        </button>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.cost-presets { display: flex; gap: 7px; margin-top: 12px; flex-wrap: wrap; }
.cost-presets button { flex: 1; border: 1px solid var(--wao-line); border-radius: 8px; padding: 7px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.launcher-mask { position: fixed; inset: 0; z-index: 40; }
.launcher {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 41;
  width: 300px;
  max-height: calc(100dvh - 88px);
  overflow-y: auto;
  padding: 14px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  box-shadow: 0 16px 40px rgba(26, 31, 61, .18);
  text-align: left;
}
.launcher header { display: flex; align-items: center; }
.launcher header b { flex: 1; font-size: 13px; }
.close { width: 26px; height: 26px; border: 0; border-radius: 7px; background: var(--wao-bg-hover); cursor: pointer; }
.scope {
  margin: 9px 0 0; padding: 6px 8px; overflow: hidden; border-radius: 7px; background: var(--wao-primary-tint);
  color: var(--wao-text-2); font-size: 11px; line-height: 1.5; overflow-wrap: anywhere;
  display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2;
}
.scope i { margin-right: 4px; }
.loading { padding: 18px 0; color: var(--wao-text-weak); font-size: 12px; display: flex; align-items: center; gap: 7px; justify-content: center; }
.label { margin: 13px 0 7px; color: var(--wao-text-weak); font-size: 10.5px; font-weight: 650; letter-spacing: .5px; }
.engine-picker { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.launcher.compact .engine-picker { margin-top: 12px; }
.engine-option {
  padding: 10px 9px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
  text-align: left;
}
.engine-option:hover { border-color: #bec7ef; }
.engine-option.on { background: var(--wao-bg-sub); }
.engine-option i { font-size: 15px; }
.engine-option b { display: block; margin-top: 4px; font-size: 12px; }
.engine-option em { display: block; margin-top: 2px; color: var(--wao-text-weak); font-size: 10px; font-style: normal; }
.runner-select {
  width: 100%;
  height: 34px;
  padding: 0 9px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: var(--wao-bg-card);
  color: var(--wao-text-1);
  font-size: 12px;
}
.default-mode-setting { min-height: 28px; margin-top: 7px; padding: 5px 7px; border-radius: 7px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 10px; display: flex; align-items: center; justify-content: space-between; gap: 7px; }
.default-mode-setting span { min-width: 0; display: inline-flex; align-items: center; gap: 4px; }
.default-mode-setting small { color: var(--wao-success-strong); font-size: 9px; }
.default-mode-setting button { flex: none; min-height: 22px; padding: 0 6px; border: 1px solid var(--wao-primary-line); border-radius: 6px; background: var(--wao-primary-tint); color: var(--wao-primary-text); cursor: pointer; font-size: 9.5px; }
.default-mode-setting button:disabled { cursor: default; opacity: .55; }
.default-mode-setting.error,.default-mode-setting.error small { color: var(--wao-danger-strong); }
.launcher.compact .runner-select, .launcher.compact .execution-modes { margin-top: 10px; }
.execution-modes { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 7px; }
.plan-mode {
  width: 100%; padding: 9px; display: flex; align-items: center; gap: 9px;
  border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card);
  color: var(--wao-text-2); cursor: pointer; text-align: left;
}
.plan-mode.on { border-color: var(--wao-primary); background: var(--wao-primary-tint); }
.plan-mode > i { flex: 0 0 auto; font-size: 16px; }
.plan-mode > span:last-child { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.plan-mode b { font-size: 12px; }
.plan-mode small { color: var(--wao-text-weak); font-size: 10px; line-height: 1.4; }
.hint { margin: 9px 0 0; color: var(--wao-text-weak); font-size: 11px; line-height: 1.5; }
.hint i { margin-right: 4px; }
.hint.danger { color: #c04f47; }
.launcher footer { margin-top: 14px; display: flex; justify-content: flex-end; gap: 8px; }
.launcher footer button { padding: 7px 13px; border-radius: 8px; cursor: pointer; font-size: 12px; display: inline-flex; align-items: center; gap: 5px; }
.launcher footer .secondary { border: 1px solid var(--wao-line); background: var(--wao-bg-card); }
.launcher footer .primary { border: 1px solid #4358d6; background: #4358d6; color: #fff; }
.launcher footer .primary:disabled { opacity: .65; cursor: not-allowed; }
.spin { display: inline-block; animation: spin .9s linear infinite; }
@media (max-width: 620px) { .execution-modes { grid-template-columns: 1fr; } }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) {
  .launcher-mask { z-index: 142; background: rgba(18, 22, 39, .24); }
  .launcher {
    position: fixed; top: auto; right: 0; bottom: 0; left: 0; z-index: 143; box-sizing: border-box;
    width: 100%; max-height: 88dvh; padding: 16px 16px calc(16px + env(safe-area-inset-bottom));
    border-radius: 18px 18px 0 0;
  }
  .scope { -webkit-line-clamp: 2; }
  .launcher footer { position: sticky; bottom: calc(-16px - env(safe-area-inset-bottom)); margin: 14px -16px calc(-16px - env(safe-area-inset-bottom)); padding: 10px 16px calc(10px + env(safe-area-inset-bottom)); background: var(--wao-bg-card); border-top: 1px solid var(--wao-divider); }
  .launcher footer button { min-height: 42px; }
}
</style>
