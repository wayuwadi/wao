<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  feedbackNoteMiningArtifact,
  feedbackNoteMiningVisual,
  fetchNoteMining,
  newNoteMiningRequestId,
  startNoteMining,
  retryNoteMiningVisual,
  waitForNoteMining,
  type NoteMiningArtifactType,
  type NoteMiningFeedback,
  type NoteMiningResult,
  type NoteMiningVisual,
  type NoteMiningVisualLens,
} from '@/api/noteMining'

const emit = defineEmits<{ openNote: [id: string] }>()
defineProps<{ compact?: boolean }>()
const result = ref<NoteMiningResult>({ job: null, artifacts: [], visuals: [] })
const loading = ref(true)
const working = ref(false)
const error = ref('')
const activeType = ref<NoteMiningArtifactType>('INSIGHT')
const feedbackSaving = ref('')
const visualSaving = ref('')
let alive = true
let requestId = ''
let visualTimer: ReturnType<typeof setInterval> | undefined

const artifact = computed(() => result.value.artifacts.find(item => item.type === activeType.value)
  ?? result.value.artifacts[0] ?? null)
const visualItems = computed(() => result.value.visuals ?? [])
const running = computed(() => ['QUEUED', 'RUNNING'].includes(result.value.job?.status ?? ''))
const typeMeta: Record<NoteMiningArtifactType, { label: string; icon: string }> = {
  INSIGHT: { label: '洞察', icon: 'ri-sparkling-2-line' },
  REMINDER: { label: '提醒草稿', icon: 'ri-notification-3-line' },
  ARTICLE_SEED: { label: '文章种子', icon: 'ri-quill-pen-line' },
}
const lensMeta: Record<NoteMiningVisualLens, string> = {
  PROGRESS: '看见积累', REFRAME: '换个角度', NEXT_STEP: '下一步',
}

function update(next: NoteMiningResult) {
  result.value = { ...next, visuals: next.visuals ?? [] }
  if (!next.artifacts.some(item => item.type === activeType.value) && next.artifacts[0]) {
    activeType.value = next.artifacts[0].type
  }
}

async function refreshVisuals() {
  if (!alive || !result.value.job || !visualItems.value.some(item => ['PENDING', 'RUNNING'].includes(item.status))) return
  try { update(await fetchNoteMining()) }
  catch { /* 图片状态可在下次打开时继续读取，避免覆盖已有结果。 */ }
}

function replaceVisual(updated: NoteMiningVisual) {
  result.value = { ...result.value, visuals: visualItems.value.map(item => item.lens === updated.lens ? updated : item) }
}

async function retryVisual(lens: NoteMiningVisualLens) {
  if (!result.value.job || visualSaving.value) return
  visualSaving.value = lens
  error.value = ''
  try { replaceVisual(await retryNoteMiningVisual(result.value.job.id, lens)) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '图片重试失败' }
  finally { visualSaving.value = '' }
}

async function sendVisualFeedback(lens: NoteMiningVisualLens, status: 'SAVED' | 'NOT_ACCURATE') {
  if (!result.value.job || visualSaving.value) return
  visualSaving.value = lens
  error.value = ''
  try { replaceVisual(await feedbackNoteMiningVisual(result.value.job.id, lens, status)) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '图片反馈没有保存' }
  finally { visualSaving.value = '' }
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

onMounted(() => { void load(); visualTimer = setInterval(() => { void refreshVisuals() }, 8_000) })
onBeforeUnmount(() => { alive = false; if (visualTimer) clearInterval(visualTimer) })
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
    <div v-if="visualItems.length" class="visual-group">
      <div class="visual-heading"><strong>来自这些小记的三种画面</strong><span>每张图可单独判断；不会写回原文</span></div>
      <div class="visual-grid">
        <article v-for="visual in visualItems" :key="visual.lens" class="visual-card">
          <div class="visual-title"><span>{{ lensMeta[visual.lens] }}</span><strong>{{ visual.caption }}</strong></div>
          <div v-if="visual.status === 'DONE' && visual.imageUrl" class="visual-image"><img :src="visual.imageUrl" :alt="visual.caption" loading="lazy"></div>
          <div v-else class="visual-placeholder" :class="{ failed: visual.status === 'FAILED' }">
            <i :class="visual.status === 'FAILED' ? 'ri-error-warning-line' : 'ri-loader-4-line spinning'"></i>
            <span>{{ visual.status === 'FAILED' ? visual.errorMessage || '图片生成失败' : visual.status === 'PENDING' ? '等待生成图片' : '正在生成图片' }}</span>
            <button v-if="visual.status === 'FAILED'" type="button" :disabled="!!visualSaving" @click="retryVisual(visual.lens)">重试这张</button>
          </div>
          <p class="visual-rationale">{{ visual.rationale }}</p>
          <div class="visual-sources"><span>依据</span><button v-for="source in visual.sources" :key="`${visual.lens}-${source.docId}`" type="button" :title="source.excerpt" @click="emit('openNote', source.docId)">{{ source.title }} · v{{ source.docRev }}</button></div>
          <div v-if="visual.status === 'DONE'" class="visual-actions">
            <button type="button" :class="{ selected: visual.feedbackStatus === 'SAVED' }" :disabled="!!visualSaving" @click="sendVisualFeedback(visual.lens, 'SAVED')">{{ visual.feedbackStatus === 'SAVED' ? '已留下' : '留下这张' }}</button>
            <button type="button" :class="{ selected: visual.feedbackStatus === 'NOT_ACCURATE' }" :disabled="!!visualSaving" @click="sendVisualFeedback(visual.lens, 'NOT_ACCURATE')">{{ visual.feedbackStatus === 'NOT_ACCURATE' ? '已标记不准确' : '不准确' }}</button>
          </div>
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
.mining-panel { --mine-accent: #7357d8; display: grid; grid-template-columns: minmax(250px, .72fr) minmax(360px, 1.28fr); gap: 22px; padding: 20px; margin-bottom: 22px; border: 1px solid color-mix(in srgb, var(--mine-accent) 23%, var(--wao-line)); border-radius: 22px; background: linear-gradient(135deg, color-mix(in srgb, var(--mine-accent) 8%, var(--wao-bg-card)) 0%, var(--wao-bg-card) 48%); box-shadow: 0 12px 36px rgba(66, 47, 125, .08); }
.mining-panel.compact { margin: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.visual-group { grid-column: 1 / -1; min-width: 0; border-top: 1px solid var(--wao-line); padding-top: 16px; }
.visual-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.visual-heading strong { color: var(--wao-text); font-size: 15px; }
.visual-heading span { color: var(--wao-text-faint); font-size: 11px; }
.visual-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.visual-card { min-width: 0; border: 1px solid var(--wao-line); border-radius: 13px; padding: 10px; background: var(--wao-bg-card); }
.visual-title { min-height: 57px; display: flex; flex-direction: column; gap: 4px; }
.visual-title span { font-size: 11px; color: var(--mine-accent); font-weight: 700; }
.visual-title strong { color: var(--wao-text); font-size: 13px; line-height: 1.4; }
.visual-image, .visual-placeholder { width: 100%; aspect-ratio: 4 / 3; border-radius: 9px; overflow: hidden; }
.visual-image img { width: 100%; height: 100%; object-fit: cover; }
.visual-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 7px; padding: 10px; text-align: center; background: color-mix(in srgb, var(--mine-accent) 7%, var(--wao-bg-card)); color: var(--wao-text-muted); font-size: 11px; }
.visual-placeholder.failed { color: var(--wao-danger, #b33c48); }
.visual-placeholder button { border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text); cursor: pointer; padding: 4px 8px; }
.visual-rationale { margin: 9px 0; color: var(--wao-text-muted); font-size: 11px; line-height: 1.5; }
.visual-sources { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; font-size: 10px; color: var(--wao-text-faint); }
.visual-sources button { border: 1px solid var(--wao-line); border-radius: 99px; padding: 3px 6px; color: var(--wao-text-muted); background: transparent; cursor: pointer; font-size: 10px; }
.visual-actions { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 9px; }
.visual-actions button { border: 1px solid var(--wao-line); border-radius: 7px; padding: 4px 7px; color: var(--wao-text-muted); background: transparent; cursor: pointer; font-size: 10px; }
.visual-actions button.selected { color: var(--mine-accent); border-color: var(--mine-accent); }
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
@media (max-width: 820px) { .mining-panel { grid-template-columns: 1fr; padding: 16px; } .mining-result { min-height: 190px; } .visual-grid { grid-template-columns: 1fr; } }
@media (prefers-reduced-motion: reduce) { .spinning, .orbit { animation: none; } }
</style>
