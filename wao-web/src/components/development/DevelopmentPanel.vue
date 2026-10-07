<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  createDevelopmentRun,
  fetchDevelopmentRun,
  replyDevelopmentDecision,
  retryDevelopmentRun,
  saveDevelopmentPolicy,
  type DevelopmentPolicy,
  type DevelopmentRun,
} from '@/api/development'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoCard from '@/components/ui/WaoCard.vue'
import WaoField from '@/components/ui/WaoField.vue'
import DigitalAssetImage from '@/components/ui/DigitalAssetImage.vue'
import CompanionAvatar from '@/components/companions/CompanionAvatar.vue'
import { useSessionStore } from '@/stores/session'
import { useDevelopmentOverview } from '@/composables/developmentOverview'
import { COMPANION_IDS } from '@/utils/companionIdentity'
import {
  developmentFilters,
  developmentStatusLabel,
  matchesDevelopmentFilter,
  safeDevelopmentUrl,
  type DevelopmentFilter,
} from '@/utils/developmentStatus'

const session = useSessionStore()
const props = defineProps<{ runId?: string }>()
const operationError = ref('')
const pending = ref('')
const feedback = ref('')
const filter = ref<DevelopmentFilter>('all')
const selectedId = ref('')
const extraRun = ref<DevelopmentRun | null>(null)
const detailError = ref('')
const detailLoading = ref(false)
let detailRevision = 0
watch(
  () => props.runId,
  (id) => {
    if (id) {
      detailRevision++
      extraRun.value = null
      detailError.value = ''
      selectedId.value = id
      filter.value = 'all'
    }
  },
  { immediate: true },
)
const featureId = ref('')
type PolicyDraft = Omit<DevelopmentPolicy, 'intervalHours' | 'maxParallel'> & {
  intervalHours: number | string
  maxParallel: number | string
}
const policyDraft = ref<PolicyDraft | null>(null)
const replies = ref<Record<string, { revision: number; reply: string }>>({})
let disposed = false
let restoringDraft = false
const { overview, loading, loadError, refresh, invalidate } = useDevelopmentOverview({
  canRefresh: () => !pending.value,
  beforeApply(value, previous) {
    const clean =
      !policyDraft.value || (!!previous && samePolicy(policyDraft.value, previous.policy))
    if (clean) policyDraft.value = { ...value.policy }
    if (!featureId.value && value.features[0]) featureId.value = value.features[0].id
  },
})
const storageKey = computed(
  () => `wao-development-draft:${encodeURIComponent(session.currentUser.id)}`,
)
const canManage = computed(() => overview.value?.canManage === true)
const allRuns = computed(() => {
  const runs = overview.value?.runs ?? []
  return extraRun.value && !runs.some((run) => run.id === extraRun.value?.id)
    ? [extraRun.value, ...runs]
    : runs
})
const filteredRuns = computed(() =>
  allRuns.value.filter((run) => matchesDevelopmentFilter(run.status, filter.value)),
)
const selected = computed(() =>
  props.runId &&
  selectedId.value === props.runId &&
  !allRuns.value.some((run) => run.id === props.runId)
    ? null
    : (filteredRuns.value.find((run) => run.id === selectedId.value) ??
      filteredRuns.value[0] ??
      null),
)
const policyChanged = computed(
  () =>
    policyDraft.value && overview.value && !samePolicy(policyDraft.value, overview.value.policy),
)
const policyConflict = computed(
  () => !!policyChanged.value && policyDraft.value?.version !== overview.value?.policy.version,
)
const decisionDraft = computed(() =>
  selected.value?.decision ? replies.value[selected.value.decision.id] : undefined,
)
const decisionConflict = computed(
  () =>
    !!decisionDraft.value && decisionDraft.value.revision !== selected.value?.decision?.revision,
)
const decisionReply = computed(() => decisionDraft.value?.reply ?? '')
const decisionReady = computed(
  () =>
    canManage.value &&
    !pending.value &&
    !decisionConflict.value &&
    selected.value?.decision?.status === 'PENDING' &&
    !!decisionReply.value.trim(),
)
async function loadRequestedRun() {
  const id = props.runId
  if (
    !id ||
    !overview.value ||
    overview.value.runs.some((run) => run.id === id) ||
    selectedId.value !== id ||
    pending.value
  )
    return
  const revision = ++detailRevision
  const member = session.currentUser.id
  detailLoading.value = true
  try {
    const run = await fetchDevelopmentRun(id)
    if (revision === detailRevision && !disposed && member === session.currentUser.id) {
      extraRun.value = run
      detailError.value = ''
    }
  } catch (error) {
    if (revision === detailRevision && !disposed && member === session.currentUser.id)
      detailError.value = errorMessage(error)
  } finally {
    if (revision === detailRevision) detailLoading.value = false
  }
}
watch([() => props.runId, overview], () => {
  void loadRequestedRun()
})

function samePolicy(left: PolicyDraft, right: DevelopmentPolicy) {
  return (
    left.enabled === right.enabled &&
    left.intervalHours === right.intervalHours &&
    left.maxParallel === right.maxParallel
  )
}
function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请重试。'
}
function readDraft() {
  restoringDraft = true
  policyDraft.value = null
  replies.value = {}
  try {
    if (!session.currentUser.id) return
    const stored = JSON.parse(localStorage.getItem(storageKey.value) ?? 'null')
    if (
      stored?.policy &&
      typeof stored.policy.enabled === 'boolean' &&
      Number.isInteger(stored.policy.version) &&
      (Number.isFinite(stored.policy.intervalHours) ||
        typeof stored.policy.intervalHours === 'string') &&
      (Number.isFinite(stored.policy.maxParallel) || typeof stored.policy.maxParallel === 'string')
    )
      policyDraft.value = stored.policy
    if (stored?.replies && typeof stored.replies === 'object') {
      for (const [id, draft] of Object.entries(stored.replies)) {
        const value = draft as { revision?: unknown; reply?: unknown }
        if (Number.isInteger(value?.revision) && typeof value?.reply === 'string')
          replies.value[id] = { revision: value.revision as number, reply: value.reply }
      }
    }
  } catch {
    /* 本机存储受限或旧草稿损坏时，不影响服务端读取。 */
  } finally {
    restoringDraft = false
  }
}
function persistDraft() {
  if (!session.currentUser.id || restoringDraft) return
  try {
    localStorage.setItem(
      storageKey.value,
      JSON.stringify({ policy: policyDraft.value, replies: replies.value }),
    )
  } catch {
    /* 页面内草稿仍保留。 */
  }
}
watch(
  () => session.currentUser.id,
  () => {
    selectedId.value = props.runId ?? ''
    detailRevision++
    extraRun.value = null
    detailError.value = ''
    detailLoading.value = false
    featureId.value = ''
    operationError.value = ''
    feedback.value = ''
    readDraft()
  },
  { flush: 'sync' },
)
watch([policyDraft, replies], persistDraft, { deep: true, flush: 'sync' })

async function mutate(key: string, action: () => Promise<void>) {
  if (!canManage.value || pending.value) return
  invalidate()
  detailRevision++
  detailLoading.value = false
  pending.value = key
  operationError.value = ''
  feedback.value = ''
  const member = session.currentUser.id
  try {
    await action()
  } catch (error) {
    if (member === session.currentUser.id)
      operationError.value = `${errorMessage(error)} 草稿已保留，请重新读取核对后重试。`
  } finally {
    pending.value = ''
    if (member === session.currentUser.id && !disposed) await refresh()
  }
}
function updateRun(run: DevelopmentRun) {
  if (!overview.value) return
  const index = overview.value.runs.findIndex((item) => item.id === run.id)
  if (index < 0) overview.value.runs.unshift(run)
  else overview.value.runs[index] = run
  if (extraRun.value?.id === run.id) extraRun.value = run
  selectedId.value = run.id
}
async function savePolicy() {
  if (!policyDraft.value || policyConflict.value || !policyChanged.value) return
  const submitted = {
    ...policyDraft.value,
    intervalHours: Number(policyDraft.value.intervalHours),
    maxParallel: Number(policyDraft.value.maxParallel),
  }
  if (
    !Number.isInteger(submitted.intervalHours) ||
    submitted.intervalHours < 1 ||
    submitted.intervalHours > 168 ||
    !Number.isInteger(submitted.maxParallel) ||
    submitted.maxParallel < 1 ||
    submitted.maxParallel > 3
  ) {
    operationError.value = '检查间隔须为 1–168 小时，同时运行须为 1–3 项。'
    return
  }
  const member = session.currentUser.id
  await mutate('policy', async () => {
    const saved = await saveDevelopmentPolicy(submitted)
    if (member !== session.currentUser.id || disposed) return
    policyDraft.value = { ...saved }
    if (overview.value) overview.value.policy = saved
    feedback.value = '自主建设设置已保存。'
  })
}
function useAccountPolicy() {
  if (overview.value) policyDraft.value = { ...overview.value.policy }
  operationError.value = ''
}
function rebasePolicy() {
  if (policyDraft.value && overview.value) policyDraft.value.version = overview.value.policy.version
}
function setReply(event: Event) {
  const decision = selected.value?.decision
  if (!decision) return
  replies.value[decision.id] = {
    revision: replies.value[decision.id]?.revision ?? decision.revision,
    reply: (event.target as HTMLTextAreaElement).value,
  }
}
function rebaseDecision() {
  const decision = selected.value?.decision
  if (decision && replies.value[decision.id])
    replies.value[decision.id]!.revision = decision.revision
}
async function submitDecision(action: 'approve' | 'reject') {
  const decision = selected.value?.decision
  if (!decision || !decisionReady.value) return
  const draft = { ...replies.value[decision.id]! }
  const member = session.currentUser.id
  await mutate('decision', async () => {
    const run = await replyDevelopmentDecision(
      decision.id,
      draft.revision,
      action,
      draft.reply.trim(),
    )
    if (member !== session.currentUser.id || disposed) return
    updateRun(run)
    delete replies.value[decision.id]
    feedback.value = '决定已保存，后续进展以实际运行记录为准。'
  })
}
async function startRun() {
  if (!featureId.value) return
  const member = session.currentUser.id
  await mutate('create', async () => {
    const run = await createDevelopmentRun(featureId.value)
    if (member !== session.currentUser.id || disposed) return
    filter.value = 'all'
    updateRun(run)
    feedback.value = '建设请求已入队，等待执行。'
  })
}
async function retryRun() {
  const run = selected.value
  if (!run) return
  const member = session.currentUser.id
  await mutate('retry', async () => {
    const result = await retryDevelopmentRun(run.id, run.version)
    if (member !== session.currentUser.id || disposed) return
    updateRun(result)
    feedback.value = '重试请求已返回，请查看当前状态。'
  })
}
function dependencyName(id: string) {
  return selected.value?.agents.find((agent) => agent.id === id)?.title ?? id
}
function phaseLabel(phase: string) {
  return (
    (
      {
        ANALYSIS: '分析',
        ANALYZING: '分析',
        PLANNING: '比较方案',
        PLAN: '比较方案',
        IMPLEMENTATION: '建设',
        IMPLEMENTING: '建设',
        VALIDATION: '验证',
        VERIFYING: '验证',
        PR: '提交 PR',
        MERGE: '合并',
        DEPLOY: '发布',
        RELEASE: '发布',
        DECISION: '等待决定',
      } as Record<string, string>
    )[phase] ?? phase
  )
}
function decisionStatus(status: string) {
  return (
    (
      {
        PENDING: '等待回复',
        WAITING: '等待回复',
        APPROVED: '已批准',
        REJECTED: '已拒绝',
        ANSWERED: '已回复',
        CANCELLED: '已取消',
      } as Record<string, string>
    )[status] ?? status
  )
}
function deliveryStatus(status: string) {
  return (
    (
      {
        PENDING: '等待发送',
        SENDING: '正在发送',
        SENT: '已发送',
        FAILED: '发送失败，可在此直接回复',
      } as Record<string, string>
    )[status] ??
    (status || '尚未记录发送结果')
  )
}
onMounted(readDraft)
onBeforeUnmount(() => {
  persistDraft()
  disposed = true
  detailRevision++
})
</script>

<template>
  <section class="development-panel" aria-label="自主建设工作台">
    <header class="panel-heading">
      <div>
        <h2>自主建设</h2>
        <p>从真实功能中寻找下一步，在关键节点等待你的决定。</p>
      </div>
      <WaoButton :loading="loading" :disabled="!!pending" @click="refresh">重新读取</WaoButton>
    </header>
    <p v-if="loading && !overview" role="status">正在读取建设计划与真实运行记录…</p>
    <WaoCard v-if="loadError" variant="soft" class="error" role="alert"
      ><span v-if="overview">刷新失败，保留上一次读取结果。</span>{{ loadError
      }}<WaoButton :loading="loading" @click="refresh">重试读取</WaoButton></WaoCard
    >
    <p v-if="operationError" class="error" role="alert">{{ operationError }}</p>
    <p v-if="feedback" class="success" role="status">{{ feedback }}</p>
    <template v-if="overview">
      <div class="worker-status">
        <i
          :class="overview.worker.online ? 'ri-checkbox-circle-line' : 'ri-time-line'"
          aria-hidden="true"
        ></i>
        <strong>{{ overview.worker.online ? '执行电脑在线' : '执行电脑离线' }}</strong>
        <span>{{ overview.worker.message }}</span
        ><span v-if="overview.worker.lastSeenAt">最近连接 {{ overview.worker.lastSeenAt }}</span>
      </div>
      <p v-if="!canManage" class="muted">当前会话仅可查看建设记录，设置与执行由已授权成员维护。</p>
      <details class="policy-section">
        <summary>
          建设设置 · {{ overview.policy.enabled ? '已启用' : '已暂停'
          }}<span v-if="policyChanged"> · 有未保存草稿</span>
        </summary>
        <div v-if="policyDraft" class="policy-fields">
          <WaoField label="自动寻找改进"
            ><template #default="field"
              ><select
                :id="field.id"
                v-model="policyDraft.enabled"
                :disabled="!canManage || !!pending"
              >
                <option :value="true">已启用</option>
                <option :value="false">已暂停</option>
              </select></template
            ></WaoField
          >
          <WaoField label="检查间隔（小时）"
            ><template #default="field"
              ><input
                :id="field.id"
                v-model.number="policyDraft.intervalHours"
                type="number"
                min="1"
                max="168"
                :disabled="!canManage || !!pending" /></template
          ></WaoField>
          <WaoField label="同时运行（项）"
            ><template #default="field"
              ><input
                :id="field.id"
                v-model.number="policyDraft.maxParallel"
                type="number"
                min="1"
                max="3"
                :disabled="!canManage || !!pending" /></template
          ></WaoField>
        </div>
        <div v-if="policyConflict" class="conflict" role="alert">
          <p>账号设置已有新版本。你的草稿已保留，先核对再选择。</p>
          <p>
            账号当前：{{ overview.policy.enabled ? '已启用' : '已暂停' }}，每
            {{ overview.policy.intervalHours }} 小时，同时 {{ overview.policy.maxParallel }} 项。
          </p>
          <WaoButton :disabled="!!pending" @click="rebasePolicy"
            >保留草稿，基于最新版本保存</WaoButton
          ><WaoButton :disabled="!!pending" @click="useAccountPolicy">使用账号设置</WaoButton>
        </div>
        <div class="save-row">
          <span role="status">{{
            pending === 'policy' ? '正在保存…' : policyChanged ? '草稿未保存' : '已读取账号设置'
          }}</span
          ><WaoButton
            :disabled="!canManage || !policyChanged || policyConflict || !!pending"
            :loading="pending === 'policy'"
            variant="primary"
            @click="savePolicy"
            >保存建设设置</WaoButton
          >
        </div>
      </details>
      <div v-if="canManage" class="start-row">
        <WaoField label="选择真实功能"
          ><template #default="field"
            ><select
              :id="field.id"
              v-model="featureId"
              :disabled="!!pending || !overview.features.length"
            >
              <option v-for="feature in overview.features" :key="feature.id" :value="feature.id">
                {{ feature.name }}
              </option>
              <option v-if="!overview.features.length" value="">暂无可建设功能</option>
            </select></template
          ></WaoField
        >
        <WaoButton
          :disabled="!featureId || !!pending"
          :loading="pending === 'create'"
          @click="startRun"
          >发起建设</WaoButton
        >
      </div>
      <p v-if="detailError && selected" class="error" role="alert">
        指定记录刷新失败：{{ detailError }}，保留上一次读取结果。
      </p>
      <div class="workspace">
        <aside class="run-sidebar" aria-label="建设记录">
          <div class="filters" role="group" aria-label="筛选建设记录">
            <button
              v-for="item in developmentFilters"
              :key="item.id"
              type="button"
              :aria-pressed="filter === item.id"
              @click="filter = item.id"
            >
              {{ item.label
              }}<span>{{
                allRuns.filter((run) => matchesDevelopmentFilter(run.status, item.id)).length
              }}</span>
            </button>
          </div>
          <p v-if="!allRuns.length" class="empty-state">
            暂无建设记录。启用后等待执行，或选择真实功能发起建设。
          </p>
          <p v-else-if="!filteredRuns.length" class="empty-state">当前筛选下暂无记录。</p>
          <div class="run-list">
            <button
              v-for="run in filteredRuns"
              :key="run.id"
              type="button"
              class="run-item"
              :aria-current="selected?.id === run.id ? 'true' : undefined"
              @click="selectedId = run.id"
            >
              <strong>{{ run.title }}</strong
              ><span>{{ run.featureId }} · {{ developmentStatusLabel(run.status) }}</span
              ><small>{{ run.updatedAt }}</small>
            </button>
          </div>
        </aside>
        <div
          v-if="!selected && (detailLoading || detailError)"
          class="detail-feedback"
          :role="detailError ? 'alert' : 'status'"
        >
          <p v-if="detailLoading">正在读取链接指定的建设记录…</p>
          <template v-if="detailError"
            ><p class="error">指定记录读取失败：{{ detailError }}</p>
            <WaoButton :disabled="detailLoading || !!pending" @click="loadRequestedRun"
              >重新读取此记录</WaoButton
            ></template
          >
        </div>
        <article v-if="selected" class="run-detail" aria-label="建设详情">
          <header>
            <p class="eyebrow">{{ selected.featureId }}</p>
            <h3>{{ selected.title }}</h3>
            <div class="status-row">
              <span>当前阶段：{{ phaseLabel(selected.phase) || '尚未开始' }}</span
              ><strong>实际结论：{{ developmentStatusLabel(selected.status) }}</strong>
            </div>
            <p class="goal">{{ selected.goal || '目标尚未记录。' }}</p>
          </header>
          <WaoCard v-if="selected.error" variant="soft" class="error"
            ><p>{{ selected.error }}</p>
            <WaoButton
              v-if="canManage && selected.status === 'FAILED'"
              :loading="pending === 'retry'"
              :disabled="!!pending"
              @click="retryRun"
              >重试此项建设</WaoButton
            ></WaoCard
          >
          <section class="detail-section">
            <h4>方案与推荐</h4>
            <template v-if="selected.plan"
              ><p v-if="selected.plan.goal">{{ selected.plan.goal }}</p>
              <div class="plans">
                <div
                  v-for="(option, index) in selected.plan.options"
                  :key="index"
                  class="plan"
                  :class="{ recommended: index === selected.plan.recommendedIndex }"
                >
                  <strong>{{ option.title }}</strong
                  ><span v-if="index === selected.plan.recommendedIndex" class="recommendation"
                    >推荐</span
                  >
                  <p>{{ option.description }}</p>
                </div>
              </div>
              <p v-if="selected.plan.reason" class="muted">
                推荐理由：{{ selected.plan.reason }}
              </p></template
            >
            <p v-else class="muted">尚未形成方案。</p>
          </section>
          <section class="detail-section">
            <h4>协作与依赖</h4>
            <p v-if="!selected.agents.length" class="muted">尚未分配协作任务。</p>
            <div v-for="(agent, index) in selected.agents" :key="agent.id" class="agent-row">
              <div class="agent-avatar">
                <DigitalAssetImage
                  v-if="agent.identity?.assetId"
                  :asset-id="agent.identity.assetId"
                  :alt="agent.identity.name || '此协作任务绑定的数字形象'"
                /><CompanionAvatar
                  v-else
                  :identity="COMPANION_IDS[index % COMPANION_IDS.length]!"
                  :size="44"
                  class="companion-image"
                />
              </div>
              <div>
                <strong>{{ agent.title }}</strong
                ><span class="agent-status">{{ developmentStatusLabel(agent.status) }}</span>
                <p>{{ agent.goal }}</p>
                <p v-if="agent.identity?.name" class="muted">形象：{{ agent.identity.name }}</p>
                <p v-if="agent.dependsOn.length" class="muted">
                  依赖：{{ agent.dependsOn.map(dependencyName).join('、') }}
                </p>
                <p v-if="agent.paths.length" class="muted">
                  负责范围：{{ agent.paths.join('、') }}
                </p>
              </div>
            </div>
          </section>
          <section v-if="selected.decision" class="detail-section decision-section">
            <h4>关键决定 · {{ decisionStatus(selected.decision.status) }}</h4>
            <p>{{ selected.decision.question }}</p>
            <p class="muted">{{ selected.decision.reason }}</p>
            <p v-if="selected.decision.reply">已回复：{{ selected.decision.reply }}</p>
            <p class="muted">通知：{{ deliveryStatus(selected.decision.deliveryStatus) }}</p>
            <template
              v-if="
                selected.status === 'WAITING_DECISION' &&
                selected.decision.status === 'PENDING' &&
                canManage
              "
              ><WaoField
                label="你的明确回复"
                hint="同意或拒绝前请写明决定；保存后继续推进或停止本次方案。"
                required
                ><template #default="field">
                  <textarea
                    :id="field.id"
                    :value="decisionReply"
                    :aria-describedby="field.describedBy"
                    rows="3"
                    maxlength="2000"
                    :disabled="!!pending"
                    @input="setReply"
                  /></template
              ></WaoField>
              <div v-if="decisionConflict" class="conflict" role="alert">
                <p>问题已更新，回复草稿仍对应旧版本。请核对当前问题。</p>
                <WaoButton :disabled="!!pending" @click="rebaseDecision"
                  >我已核对新问题，保留回复</WaoButton
                >
              </div>
              <div class="decision-actions">
                <WaoButton
                  variant="primary"
                  :disabled="!decisionReady"
                  :loading="pending === 'decision'"
                  @click="submitDecision('approve')"
                  >同意并继续</WaoButton
                ><WaoButton
                  variant="danger"
                  :disabled="!decisionReady"
                  @click="submitDecision('reject')"
                  >拒绝此方案</WaoButton
                ><span v-if="decisionReply" class="muted">{{
                  pending === 'decision' ? '正在保存决定…' : '回复草稿未提交'
                }}</span>
              </div></template
            >
          </section>
          <section class="detail-section evidence-section">
            <h4>验收与交付证据</h4>
            <template v-if="selected.evidence"
              ><ul v-if="selected.evidence.validation.length">
                <li v-for="(item, index) in selected.evidence.validation" :key="index">
                  {{ item }}
                </li>
              </ul>
              <p v-else class="muted">尚未记录验证结果。</p>
              <dl>
                <dt>PR</dt>
                <dd>
                  <a
                    v-if="safeDevelopmentUrl(selected.evidence.prUrl)"
                    :href="safeDevelopmentUrl(selected.evidence.prUrl)"
                    target="_blank"
                    rel="noopener noreferrer"
                    >查看 PR <i class="ri-external-link-line" aria-hidden="true"></i></a
                  ><span v-else>{{ selected.evidence.prUrl || '尚未提交' }}</span>
                </dd>
                <dt>合并版本</dt>
                <dd>{{ selected.evidence.mergeSha || '尚未合并' }}</dd>
                <dt>公网实际版本</dt>
                <dd>{{ selected.evidence.publicVersion || '尚未取得' }}</dd>
                <dt>发布验证</dt>
                <dd>
                  {{
                    selected.evidence.releaseVerified
                      ? '已核对公网版本与页面，等待用户验收'
                      : '尚未确认公网发布结果'
                  }}
                </dd>
              </dl>
              <div
                v-for="(artifact, index) in selected.evidence.artifacts"
                :key="index"
                class="artifact"
              >
                <a
                  v-if="safeDevelopmentUrl(artifact.url)"
                  :href="safeDevelopmentUrl(artifact.url)"
                  target="_blank"
                  rel="noopener noreferrer"
                  >{{ artifact.label }}</a
                ><span v-else>{{ artifact.label }}：{{ artifact.url }}</span>
              </div></template
            >
            <p v-else class="muted">尚无验收、PR 或发布证据。</p>
          </section>
        </article>
      </div>
    </template>
  </section>
</template>

<style scoped>
.development-panel {
  color: var(--wao-text);
  min-width: 0;
}
.panel-heading,
.worker-status,
.save-row,
.start-row,
.status-row,
.decision-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--wao-space-2);
}
.panel-heading {
  justify-content: space-between;
  margin-bottom: var(--wao-space-2);
}
h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}
h3 {
  margin: 4px 0 var(--wao-space-2);
  font-size: 20px;
  font-weight: 600;
}
h4 {
  margin: 0 0 var(--wao-space-2);
  font-size: var(--wao-font-body);
}
p {
  margin: 8px 0;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.panel-heading p,
.muted,
.worker-status span {
  color: var(--wao-text-3);
}
.error {
  color: var(--wao-danger-strong);
}
.success {
  color: var(--wao-success-strong);
}
.worker-status {
  padding: var(--wao-space-2) 0;
  border-bottom: 1px solid var(--wao-line);
  font-size: var(--wao-font-caption);
}
.worker-status strong {
  color: var(--wao-text);
}
.policy-section {
  padding: var(--wao-space-2) 0;
  border-bottom: 1px solid var(--wao-line);
}
summary {
  cursor: pointer;
  line-height: 1.8;
}
.policy-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--wao-space-2);
  margin: var(--wao-space-2) 0;
}
.save-row {
  justify-content: space-between;
  font-size: var(--wao-font-caption);
}
.conflict {
  margin: var(--wao-space-2) 0;
  padding: var(--wao-space-2);
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  border-radius: var(--wao-radius-control);
}
.conflict button {
  margin: 4px 8px 4px 0;
}
.start-row {
  padding: var(--wao-space-2) 0;
  align-items: end;
}
.start-row :deep(.wao-field) {
  flex: 1;
  max-width: 420px;
}
.workspace {
  display: grid;
  grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  gap: var(--wao-space-3);
  margin-top: var(--wao-space-2);
}
.run-sidebar {
  min-width: 0;
  position: sticky;
  top: var(--wao-space-2);
  align-self: start;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: var(--wao-space-2);
}
.filters button {
  border: 1px solid transparent;
  border-radius: var(--wao-radius-control);
  padding: 8px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
  min-height: 44px;
}
.filters button[aria-pressed='true'] {
  border-color: var(--wao-primary-line);
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.filters span {
  margin-left: 4px;
}
.run-list {
  display: grid;
  gap: 4px;
  max-height: min(72dvh, 1040px);
  overflow-y: auto;
}
.run-item {
  display: grid;
  gap: 8px;
  width: 100%;
  padding: var(--wao-space-2);
  border: 1px solid transparent;
  border-radius: var(--wao-radius-control);
  background: transparent;
  color: var(--wao-text);
  text-align: left;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.run-item:hover {
  background: var(--wao-bg-hover);
}
.run-item[aria-current='true'] {
  border-color: var(--wao-primary-line);
  background: var(--wao-primary-soft);
}
.run-item strong {
  font-size: var(--wao-font-body);
  line-height: 1.6;
}
.run-item span,
.run-item small {
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
.empty-state {
  color: var(--wao-text-3);
  padding: var(--wao-space-2) 0;
}
.run-detail {
  min-width: 0;
  padding-left: var(--wao-space-3);
  border-left: 1px solid var(--wao-line);
  overflow-wrap: anywhere;
}
.eyebrow {
  color: var(--wao-primary-text);
  font-size: var(--wao-font-caption);
}
.status-row {
  font-size: var(--wao-font-caption);
}
.status-row strong {
  color: var(--wao-primary-text);
  font-weight: 500;
}
.goal {
  margin: var(--wao-space-2) 0;
}
.detail-section {
  padding: var(--wao-space-3) 0;
  border-top: 1px solid var(--wao-line);
}
.plans {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--wao-space-2);
}
.plan {
  padding: var(--wao-space-2);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-control);
}
.plan.recommended {
  border-color: var(--wao-primary-line);
  background: var(--wao-primary-tint);
}
.plan strong {
  font-size: var(--wao-font-body);
}
.plan p,
.recommendation {
  font-size: var(--wao-font-caption);
}
.recommendation {
  display: block;
  color: var(--wao-primary-text);
  margin-top: 8px;
}
.agent-row {
  display: flex;
  gap: var(--wao-space-2);
  padding: var(--wao-space-2) 0;
}
.agent-row > div {
  flex: 1;
  min-width: 0;
}
.agent-avatar {
  width: 44px;
  height: 44px;
  object-fit: contain;
  flex-shrink: 0;
}
.agent-status {
  margin-left: var(--wao-space-1);
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
.agent-row p {
  font-size: var(--wao-font-caption);
}
.decision-actions {
  margin-top: var(--wao-space-2);
}
dl {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 12px;
  line-height: 1.7;
  font-size: var(--wao-font-body);
}
dt {
  color: var(--wao-text-3);
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
a {
  color: var(--wao-primary-text);
}
ul {
  padding-left: 20px;
  line-height: 1.8;
}
.artifact {
  margin: 8px 0;
}
@media (max-width: 1050px) {
  .plans {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 760px) {
  .save-row,
  .decision-actions {
    padding-inline-end: 52px;
  }
  .run-sidebar {
    position: static;
  }
  .workspace {
    grid-template-columns: minmax(0, 1fr);
  }
  .run-detail {
    border-left: 0;
    border-top: 1px solid var(--wao-line);
    padding: var(--wao-space-3) 0 0;
  }
  .run-list {
    max-height: 360px;
    overflow-y: auto;
  }
  .policy-fields {
    grid-template-columns: 1fr;
  }
  .start-row {
    align-items: stretch;
    flex-direction: column;
  }
  .start-row :deep(.wao-field) {
    max-width: none;
  }
  dl {
    grid-template-columns: 1fr;
    gap: 4px;
  }
  dd {
    margin-bottom: 12px;
  }
}
</style>
