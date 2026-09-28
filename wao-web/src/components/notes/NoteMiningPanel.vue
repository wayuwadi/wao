<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  feedbackNoteMiningArtifact,
  fetchNoteMining,
  newNoteMiningRequestId,
  startNoteMining,
  waitForNoteMining,
  type NoteMiningArtifactType,
  type NoteMiningFeedback,
  type NoteMiningResult,
} from '@/api/noteMining'

const emit = defineEmits<{ openNote: [id: string] }>()
defineProps<{ compact?: boolean }>()
const result = ref<NoteMiningResult>({ job: null, artifacts: [] })
const loading = ref(true)
const working = ref(false)
const error = ref('')
const activeType = ref<NoteMiningArtifactType>('INSIGHT')
const feedbackSaving = ref('')
let alive = true
let requestId = ''

const artifact = computed(() => result.value.artifacts.find(item => item.type === activeType.value)
  ?? result.value.artifacts[0] ?? null)
const running = computed(() => ['QUEUED', 'RUNNING'].includes(result.value.job?.status ?? ''))
const typeMeta: Record<NoteMiningArtifactType, { label: string; icon: string }> = {
  INSIGHT: { label: '洞察', icon: 'ri-sparkling-2-line' },
  REMINDER: { label: '提醒草稿', icon: 'ri-notification-3-line' },
  ARTICLE_SEED: { label: '文章种子', icon: 'ri-quill-pen-line' },
}

function update(next: NoteMiningResult) {
  result.value = next
  if (!next.artifacts.some(item => item.type === activeType.value) && next.artifacts[0]) {
    activeType.value = next.artifacts[0].type
  }
}

async function continuePolling(next: NoteMiningResult) {
  if (!next.job || !['QUEUED', 'RUNNING'].includes(next.job.status)) return
  working.value = true
  try { await waitForNoteMining(next, update, () => alive) }
  catch (cause) { if (alive) error.value = cause instanceof Error ? cause.message : '挖掘状态读取失败' }
  finally { if (alive) working.value = false }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const next = await fetchNoteMining()
    update(next)
    loading.value = false
    await continuePolling(next)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '小记挖掘加载失败' }
  finally { loading.value = false }
}

async function start() {
  if (working.value) return
  working.value = true
  error.value = ''
  requestId ||= newNoteMiningRequestId()
  try {
    const next = await startNoteMining(requestId)
    update(next)
    await continuePolling(next)
    if (result.value.job?.status === 'FAILED') error.value = result.value.job.message
    requestId = ''
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '挖掘启动失败，请重试' }
  finally { if (alive) working.value = false }
}

async function sendFeedback(status: Exclude<NoteMiningFeedback, 'CANDIDATE'>) {
  if (!artifact.value || feedbackSaving.value) return
  feedbackSaving.value = artifact.value.id
  error.value = ''
  try {
    const updated = await feedbackNoteMiningArtifact(artifact.value.id, status)
    result.value = { ...result.value, artifacts: result.value.artifacts.map(item => item.id === updated.id ? updated : item) }
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '反馈没有保存，请重试' }
  finally { feedbackSaving.value = '' }
}

onMounted(load)
onBeforeUnmount(() => { alive = false })
</script>

<template>
  <section class="mining-panel" :class="{ compact }" aria-labelledby="note-mining-title">
    <div class="mining-intro">
      <div class="eyebrow"><i class="ri-sparkling-2-line"></i> AI 挖掘小记</div>
      <h2 id="note-mining-title">让过去的小记，长出新的内容</h2>
      <p>AI 会连接近期小记里的重复主题与变化，产出洞察、提醒草稿和文章种子。原文保持不变，每条结果都能回看来源。</p>
      <button class="mine-button" type="button" :disabled="working || loading || running" @click="start">
        <i :class="working || running ? 'ri-loader-4-line spinning' : 'ri-magic-line'"></i>
        {{ working || running ? '正在挖掘…' : result.job ? '重新挖掘近期小记' : '开始挖掘近期小记' }}
      </button>
      <small v-if="result.job">基于 {{ result.job.sourceCount }} 篇近期小记 · {{ result.job.message }}</small>
    </div>

    <div class="mining-result" :aria-busy="loading || working">
      <div v-if="loading" class="state"><i class="ri-loader-4-line spinning"></i><span>正在读取上次挖掘结果</span></div>
      <div v-else-if="error && !result.artifacts.length" class="state error-state">
        <i class="ri-error-warning-line"></i><span>{{ error }}</span><button type="button" @click="load">重试</button>
      </div>
      <div v-else-if="running" class="state running-state">
        <span class="orbit" aria-hidden="true"><i class="ri-sticky-note-line"></i></span>
        <strong>AI 正在连接小记中的线索</strong>
        <span v-if="!error">可以离开这个页面，任务会在后台继续。</span>
        <template v-else><span>{{ error }}</span><button type="button" @click="load">重新查询</button></template>
      </div>
      <div v-else-if="!artifact" class="state empty-state">
        <i class="ri-links-line"></i>
        <strong>还没有内容候选</strong>
        <span>至少两篇有正文的小记就能开始。AI 不会改写你的原文。</span>
      </div>
      <template v-else>
        <div class="artifact-tabs" role="tablist" aria-label="内容候选类型">
          <button v-for="item in result.artifacts" :key="item.id" type="button" role="tab"
            :aria-selected="artifact?.id === item.id" :class="{ active: artifact?.id === item.id }" @click="activeType = item.type">
            <i :class="typeMeta[item.type].icon"></i>{{ typeMeta[item.type].label }}
          </button>
        </div>
        <article class="artifact-card">
          <p class="artifact-content">{{ artifact.content }}</p>
          <div class="why"><span>为什么生成它</span><p>{{ artifact.rationale }}</p></div>
          <div class="sources">
            <span>来自</span>
            <button v-for="source in artifact.sources" :key="`${source.docId}-${source.docRev}`" type="button"
              :title="source.excerpt" @click="emit('openNote', source.docId)">
              {{ source.title }} <small>v{{ source.docRev }}</small>
            </button>
          </div>
          <details class="source-evidence">
            <summary>查看本次引用的原文摘录</summary>
            <div v-for="source in artifact.sources" :key="`evidence-${source.docId}-${source.docRev}`">
              <strong>{{ source.title }} · v{{ source.docRev }}</strong>
              <p>{{ source.excerpt }}</p>
            </div>
          </details>
          <div class="artifact-actions">
            <button type="button" :class="{ selected: artifact.feedbackStatus === 'SAVED' }" :disabled="!!feedbackSaving" @click="sendFeedback('SAVED')">
              <i class="ri-bookmark-line"></i>{{ artifact.feedbackStatus === 'SAVED' ? '已收藏' : '留下它' }}
            </button>
            <button v-if="artifact.type === 'REMINDER'" type="button" :class="{ selected: artifact.feedbackStatus === 'REMINDER_DRAFT' }"
              :disabled="!!feedbackSaving" @click="sendFeedback('REMINDER_DRAFT')">
              <i class="ri-notification-badge-line"></i>{{ artifact.feedbackStatus === 'REMINDER_DRAFT' ? '已标记为草稿' : '作为提醒草稿' }}
            </button>
            <button type="button" :class="{ selected: artifact.feedbackStatus === 'NOT_ACCURATE' }" :disabled="!!feedbackSaving" @click="sendFeedback('NOT_ACCURATE')">
              <i class="ri-thumb-down-line"></i>不准确
            </button>
          </div>
          <p v-if="artifact.feedbackStatus === 'REMINDER_DRAFT'" class="draft-note">这只是提醒草稿，尚未启用或推送。</p>
        </article>
        <p v-if="error" class="inline-error">{{ error }}</p>
      </template>
    </div>
  </section>
</template>

<style scoped>
.mining-panel { --mine-accent: #7357d8; display: grid; grid-template-columns: minmax(250px, .72fr) minmax(360px, 1.28fr); gap: 22px; padding: 20px; margin-bottom: 22px; border: 1px solid color-mix(in srgb, var(--mine-accent) 23%, var(--wao-line)); border-radius: 22px; background: linear-gradient(135deg, color-mix(in srgb, var(--mine-accent) 8%, var(--wao-bg-card)) 0%, var(--wao-bg-card) 48%); box-shadow: 0 12px 36px rgba(66, 47, 125, .08); }
.mining-panel.compact { margin: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.mining-intro { display: flex; flex-direction: column; align-items: flex-start; padding: 4px 2px; }
.eyebrow { color: var(--mine-accent); font-size: 12px; font-weight: 750; letter-spacing: .06em; }
.mining-intro h2 { margin: 8px 0; font-size: clamp(20px, 2vw, 27px); line-height: 1.18; color: var(--wao-text); }
.mining-intro p { margin: 0; color: var(--wao-text-muted); font-size: 13px; line-height: 1.75; }
.mine-button { margin-top: 17px; border: 0; border-radius: 12px; padding: 10px 14px; background: var(--mine-accent); color: #fff; font-weight: 700; cursor: pointer; box-shadow: 0 7px 18px rgba(78, 54, 166, .2); }
.mine-button:disabled { cursor: wait; opacity: .72; }
.mining-intro small { margin-top: 10px; color: var(--wao-text-faint); line-height: 1.5; }
.mining-result { min-height: 204px; min-width: 0; border: 1px solid var(--wao-line); border-radius: 17px; background: color-mix(in srgb, var(--wao-bg-card) 92%, transparent); overflow: hidden; }
.state { min-height: 204px; padding: 24px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 9px; text-align: center; color: var(--wao-text-muted); }
.state > i { font-size: 25px; color: var(--mine-accent); }
.state strong { color: var(--wao-text); font-size: 15px; }
.state button { border: 1px solid var(--wao-line); background: var(--wao-bg-card); border-radius: 9px; padding: 6px 12px; color: var(--wao-text); cursor: pointer; }
.orbit { display: grid; place-items: center; width: 48px; height: 48px; border-radius: 50%; color: var(--mine-accent); background: color-mix(in srgb, var(--mine-accent) 12%, transparent); animation: breathe 1.8s ease-in-out infinite; }
.artifact-tabs { display: flex; gap: 5px; padding: 10px 10px 0; overflow-x: auto; }
.artifact-tabs button { flex: 0 0 auto; border: 0; border-radius: 9px 9px 0 0; padding: 8px 10px; color: var(--wao-text-muted); background: transparent; cursor: pointer; font-size: 12px; }
.artifact-tabs button.active { color: var(--mine-accent); background: color-mix(in srgb, var(--mine-accent) 10%, transparent); font-weight: 750; }
.artifact-card { padding: 16px 17px 14px; }
.artifact-content { margin: 0; color: var(--wao-text); font-size: 16px; line-height: 1.7; white-space: pre-wrap; }
.why { margin-top: 14px; padding: 10px 12px; border-radius: 11px; background: color-mix(in srgb, var(--mine-accent) 7%, transparent); }
.why span, .sources > span { color: var(--wao-text-faint); font-size: 11px; }
.why p { margin: 3px 0 0; color: var(--wao-text-muted); font-size: 12px; line-height: 1.55; }
.sources { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
.sources button { border: 1px solid var(--wao-line); border-radius: 999px; padding: 4px 8px; background: var(--wao-bg-card); color: var(--wao-text-muted); font-size: 11px; cursor: pointer; }
.sources small { color: var(--wao-text-faint); }
.source-evidence { margin-top: 9px; color: var(--wao-text-muted); font-size: 11px; }
.source-evidence summary { width: fit-content; cursor: pointer; color: var(--mine-accent); }
.source-evidence > div { margin-top: 7px; padding-left: 10px; border-left: 2px solid color-mix(in srgb, var(--mine-accent) 25%, var(--wao-line)); }
.source-evidence strong { color: var(--wao-text); }
.source-evidence p { margin: 3px 0 0; line-height: 1.55; white-space: pre-wrap; }
.artifact-actions { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 14px; }
.artifact-actions button { border: 1px solid var(--wao-line); border-radius: 9px; padding: 6px 9px; background: transparent; color: var(--wao-text-muted); font-size: 12px; cursor: pointer; }
.artifact-actions button.selected { border-color: color-mix(in srgb, var(--mine-accent) 38%, var(--wao-line)); background: color-mix(in srgb, var(--mine-accent) 10%, transparent); color: var(--mine-accent); }
.draft-note, .inline-error { margin: 9px 0 0; color: var(--wao-text-faint); font-size: 11px; }
.inline-error, .error-state { color: var(--wao-danger, #b33c48); }
.spinning { display: inline-block; animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@keyframes breathe { 50% { transform: scale(1.08); } }
@media (max-width: 820px) { .mining-panel { grid-template-columns: 1fr; padding: 16px; } .mining-result { min-height: 190px; } }
@media (prefers-reduced-motion: reduce) { .spinning, .orbit { animation: none; } }
</style>
