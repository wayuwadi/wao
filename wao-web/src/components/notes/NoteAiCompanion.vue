<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import type { Doc } from '@/types'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import {
  fetchCompanion,
  fetchAiResults,
  actOnArtifact,
  cancelNoteAi,
  saveCompanionMode,
  fetchNoteChat,
  askNoteChat,
  companionError,
  type CompanionOverview,
  type AiArtifact,
  type AiResults,
  type NoteChat,
  type ChatSource,
  type CompanionStage,
} from '@/api/noteAiCompanion'
import { fetchDiscoveryCurrent, type DiscoveryCurrent } from '@/api/noteDiscoveries'
import NoteDiscoveryInline from './NoteDiscoveryInline.vue'
import NoteDiscoveryPreference from './NoteDiscoveryPreference.vue'
import { copyText } from '@/utils/clipboard'
const props = defineProps<{
  notes: Doc[]
  activeId: string
  blocked: boolean
  explicitlySelected?: boolean
  overlayOpen?: boolean
  collapsed?: boolean
}>()
const emit = defineEmits<{
  select: [id: string]
  updated: [doc: Doc]
  seen: []
  editNote: [id: string]
  run: [stage: CompanionStage | 'four']
  source: [itemId: string, docId: string, event: Event]
  openNote: [id: string, quote?: string, rev?: number]
  append: [text: string, messageId: string]
  appendExisting: [id: string, text: string, messageId: string]
  settings: []
  closeOverlay: []
  collapse: [value: boolean]
}>()
const session = useSessionStore()
const panel = ref<HTMLElement>()
watch(
  () => props.overlayOpen,
  async (open) => {
    if (open) {
      await nextTick()
      panel.value?.querySelector<HTMLButtonElement>('button')?.focus()
    }
  },
)
const ui = useUiStore()
const tabs = [
  { id: 'results', text: '整理结果' },
  { id: 'discovery', text: '顺手发现' },
  { id: 'chat', text: '继续聊' },
  { id: 'runs', text: '执行记录' },
] as const
const tab = ref<(typeof tabs)[number]['id']>('results')
const stage = ref<CompanionStage>('polish')
const labels: Record<CompanionStage, string> = {
  polish: '正文',
  title: '标题',
  tags: '标签',
  image: '配图',
}
const overview = ref<CompanionOverview>(),
  results = ref<AiResults>(),
  discovery = ref<DiscoveryCurrent>(),
  chat = ref<NoteChat>()
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  discoveryError = ref(''),
  chatError = ref('')
const chosen = ref(''),
  question = ref(''),
  selectedSources = ref<string[]>([]),
  settingsOpen = ref(false)
const transfer = ref<{ text: string; mode: 'note' | 'existing' | 'task'; messageId: string }>(),
  transferTitle = ref('')
const explicitlyViewed = ref(false)
const mobile = ref(
    typeof window.matchMedia === 'function' && window.matchMedia('(max-width:760px)').matches,
  ),
  mobileOpen = ref(false)
const desktopCollapsed = computed(() => !!props.collapsed && !mobile.value && !props.overlayOpen)
const dockStatus = computed(() => {
  if (overview.value?.running) return `${overview.value.running} 项执行中`
  if (overview.value?.queued) return `${overview.value.queued} 项等待中`
  if (overview.value?.unread) return `${overview.value.unread} 项新成果`
  return '随时继续'
})
let contentScroll = 0
watch(desktopCollapsed, async (closed) => {
  const content = panel.value?.querySelector<HTMLElement>('.companion-content')
  if (closed) contentScroll = content?.scrollTop || 0
  await nextTick()
  if (!closed && content) content.scrollTop = contentScroll
  panel.value?.querySelector<HTMLButtonElement>(closed ? '.companion-dock' : '.companion-collapse')?.focus()
  if (!closed) void seen(artifact.value)
})
function resizePanel() {
  mobile.value =
    typeof window.matchMedia === 'function' && window.matchMedia('(max-width:760px)').matches
}
function closePanel() {
  mobileOpen.value = false
  emit('closeOverlay')
}
useOverlayDismiss(() => !!props.overlayOpen || mobileOpen.value, closePanel)
window.addEventListener('resize', resizePanel)
let generation = 0,
  request = 0
let pendingChat: { id: string; message: string; rev: number; sources: string[] } | undefined
const note = computed(() => props.notes.find((n) => n.id === props.activeId))
const ownNotes = computed(() => props.notes.filter((n) => n.author === session.currentUser.id))
const visibleArtifacts = computed(
  () => results.value?.items.filter((a) => a.stage === stage.value) ?? [],
)
const artifact = computed(
  () => visibleArtifacts.value.find((a) => a.id === chosen.value) ?? visibleArtifacts.value[0],
)
const paragraphDiff = computed(() => {
  if (stage.value !== 'polish' || !artifact.value) return []
  const a = artifact.value.sourceText.split(/\n\s*\n/).filter(Boolean),
    b = artifact.value.resultText.split(/\n\s*\n/).filter(Boolean)
  const out: { kind: 'same' | 'removed' | 'added'; text: string }[] = []
  if (a.length * b.length > 40_000) return []
  const table = Array.from({ length: a.length + 1 }, () => new Uint16Array(b.length + 1))
  for (let i = a.length - 1; i >= 0; i--)
    for (let j = b.length - 1; j >= 0; j--)
      table[i]![j] =
        a[i] === b[j] ? table[i + 1]![j + 1]! + 1 : Math.max(table[i + 1]![j]!, table[i]![j + 1]!)
  let i = 0,
    j = 0
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) {
      out.push({ kind: 'same', text: a[i++]! })
      j++
    } else if (i < a.length && (j === b.length || table[i + 1]![j]! >= table[i]![j + 1]!))
      out.push({ kind: 'removed', text: a[i++]! })
    else out.push({ kind: 'added', text: b[j++]! })
  }
  return out
})
function applicationLabel(state: string) {
  return (
    (
      {
        HISTORICAL_APPLIED: '历史已采用 · 当前内容已更新',
        NOT_NEEDED: '本次无需修改',
        APPLIED: '已采用',
        PENDING: '待你采用',
        RESTORED: '已恢复原稿',
        DECLINED: '已忽略',
        CANCELLED: '已取消',
        STALE: '旧来源成果',
      } as Record<string, string>
    )[state] || state
  )
}
function executionLabel(state: string, runStatus?: string) {
  if (state === 'RUNNING' && runStatus?.includes('queued')) return '已排队'
  if (state === 'RUNNING' && runStatus?.includes('running')) return '执行中'
  return (
    (
      {
        DONE: '调用已完成',
        FAILED: '失败',
        RUNNING: '排队或执行中',
        PENDING: '待入队',
        SKIPPED: '已跳过或停止',
      } as Record<string, string>
    )[state] || state
  )
}
const activeRuns = computed(
  () => results.value?.attempts.filter((a) => ['PENDING', 'RUNNING'].includes(a.state)) ?? [],
)
const chatRunning = computed(() =>
  chat.value?.runs.some((r) => ['secure_queued', 'provider_running'].includes(r.status)),
)
const messageSources = computed(() => {
  const out: Record<string, ChatSource[]> = {}
  let sources: ChatSource[] = []
  for (const message of chat.value?.messages ?? []) {
    if (message.role === 'user') {
      const run = chat.value?.runs.find((r) => r.userMessageId === message.id)
      try {
        sources = run?.sourcesJson ? JSON.parse(run.sourcesJson) : []
      } catch {
        sources = []
      }
    } else out[message.id] = sources
  }
  return out
})
const discoveryText = computed(() => {
  const d = discovery.value
  if (!d) return '正在读取分析状态…'
  if (d.discovery) return '发现了一条有原文依据的联系'
  return (
    (
      {
        OFF: '尚未开启 · 由你决定是否分析历史小记',
        PAUSED: '已暂停 · 已有收藏仍可查看',
        UNAVAILABLE: '自动发现暂不可用，请检查执行器与设置',
        PROCESSING: '正在分析 · 有合格结果后会出现在这里',
        FAILED: '最近一次分析失败，原文与失败记录保留',
        DEFERRED: '等待执行条件或预算，稍后继续',
      } as Record<string, string>
    )[d.automationState] ??
    (d.lastOutcome === 'NO_QUALIFIED_RESULT' || d.lastOutcome?.includes('NO_')
      ? '这次没有足够依据的发现，保留空结果'
      : '等待稳定的小记来源 · 不为凑数制造结论')
  )
})
function draftKey() {
  return `wao-note-companion-question:${session.currentUser.id}:${props.activeId}`
}
function keepQuestion() {
  try {
    if (question.value) localStorage.setItem(draftKey(), question.value)
    else localStorage.removeItem(draftKey())
  } catch {
    chatError.value = '问题暂时无法保护到本机，请复制留存后重试。'
  }
}
watch(question, keepQuestion)
async function refresh(append = false) {
  const mine = generation,
    sequence = ++request,
    id = props.activeId
  if (!append) loading.value = !overview.value
  try {
    const state = await fetchCompanion()
    if (mine !== generation || sequence !== request) return
    overview.value = state
    error.value = ''
    if (id) {
      const data = await fetchAiResults(id, append ? results.value?.nextCursor : undefined)
      if (mine !== generation || sequence !== request) return
      results.value =
        append && results.value ? { ...data, items: [...results.value.items, ...data.items] } : data
    }
  } catch (cause) {
    if (mine === generation && sequence === request) {
      error.value = companionError(cause)
      const status = (cause as { response?: { status?: number } }).response?.status
      if ([401, 403, 404].includes(status ?? 0)) results.value = undefined
    }
  } finally {
    if (mine === generation && sequence === request) loading.value = false
  }
  if (tab.value === 'discovery') await loadDiscovery()
  if (tab.value === 'chat') await loadChat()
}
async function loadDiscovery() {
  const mine = generation
  try {
    const value = await fetchDiscoveryCurrent()
    if (mine === generation) {
      discovery.value = value
      discoveryError.value = ''
    }
  } catch (e) {
    if (mine === generation) {
      discovery.value = undefined
      discoveryError.value = companionError(e)
    }
  }
}
async function loadChat() {
  if (!props.activeId) return
  const mine = generation
  try {
    const value = await fetchNoteChat(props.activeId)
    if (mine === generation) {
      chat.value = value
      chatError.value = ''
    }
  } catch (e) {
    if (mine === generation) {
      chat.value = undefined
      chatError.value = companionError(e)
    }
  }
}
async function seen(value?: AiArtifact) {
  if (
    !explicitlyViewed.value ||
    desktopCollapsed.value ||
    (mobile.value && !mobileOpen.value && !props.overlayOpen) ||
    !value ||
    value.seen ||
    tab.value !== 'results'
  )
    return
  const mine = generation
  try {
    await actOnArtifact(value.id, 'seen')
    if (mine === generation) {
      value.seen = true
      emit('seen')
    }
  } catch {
    /* 下次打开仍保留未读，不伪报读取。 */
  }
}
watch(mobileOpen, (value) => {
  if (value) {
    explicitlyViewed.value = true
    void seen(artifact.value)
  }
})
watch(
  () => props.explicitlySelected,
  (value) => {
    if (value) {
      explicitlyViewed.value = true
      void seen(artifact.value)
    }
  },
)
function chooseStage(value: CompanionStage) {
  explicitlyViewed.value = true
  stage.value = value
  chosen.value = ''
  void seen(artifact.value)
}
watch(artifact, (value) => {
  if (tab.value === 'results') void seen(value)
})
watch(tab, (value) => {
  if (value === 'discovery') void loadDiscovery()
  if (value === 'chat') void loadChat()
  if (value === 'results') void seen(artifact.value)
})
watch(
  [() => props.activeId, () => session.currentUser.id],
  () => {
    generation++
    request++
    busy.value = false
    explicitlyViewed.value = props.explicitlySelected ?? !!props.activeId
    results.value = undefined
    chat.value = undefined
    chosen.value = ''
    selectedSources.value = []
    error.value = ''
    pendingChat = undefined
    try {
      question.value = localStorage.getItem(draftKey()) || ''
    } catch {
      question.value = ''
    }
    try {
      const saved = JSON.parse(localStorage.getItem(`${draftKey()}:pending`) || 'null')
      if (
        saved &&
        /^[a-f0-9]{32}$/.test(saved.id) &&
        typeof saved.message === 'string' &&
        Number.isInteger(saved.rev) &&
        Array.isArray(saved.sources) &&
        saved.sources.every((id: unknown) => typeof id === 'string')
      ) {
        pendingChat = saved
        if (saved.message === question.value.trim()) selectedSources.value = [...saved.sources]
      }
    } catch {
      /* 损坏的本机请求记录不作为可信请求。 */
    }
    void refresh()
  },
  { immediate: true },
)
async function action(value: 'apply' | 'restore' | 'decline') {
  if (!artifact.value || !note.value || busy.value || (props.blocked && value !== 'decline')) return
  const mine = generation
  busy.value = true
  error.value = ''
  try {
    const doc = await actOnArtifact(artifact.value.id, value, note.value.rev ?? 1)
    if (mine === generation) {
      emit('updated', doc)
      await refresh()
    }
  } catch (e) {
    if (mine === generation) error.value = companionError(e)
  } finally {
    if (mine === generation) busy.value = false
  }
}
function editSelectedNote() {
  closePanel()
  emit('editNote', props.activeId)
}
async function cancel() {
  if (!note.value || busy.value) return
  busy.value = true
  try {
    await cancelNoteAi(note.value.id, note.value.rev ?? 1)
    await refresh()
  } catch (e) {
    error.value = companionError(e)
  } finally {
    busy.value = false
  }
}
async function changeMode(mode: CompanionOverview['mode']) {
  if (!overview.value || busy.value) return
  busy.value = true
  try {
    const p = await saveCompanionMode(mode, overview.value.version)
    Object.assign(overview.value, p)
  } catch (e) {
    error.value = companionError(e)
  } finally {
    busy.value = false
  }
}
async function ask() {
  if (!note.value || !question.value.trim() || busy.value || chatRunning.value || props.blocked)
    return
  const mine = generation,
    input = question.value.trim()
  busy.value = true
  chatError.value = ''
  if (
    !pendingChat ||
    pendingChat.message !== input ||
    pendingChat.rev !== note.value.rev ||
    JSON.stringify(pendingChat.sources) !== JSON.stringify(selectedSources.value)
  )
    pendingChat = {
      id: crypto.randomUUID().replace(/-/g, ''),
      message: input,
      rev: note.value.rev ?? 1,
      sources: [...selectedSources.value],
    }
  try {
    localStorage.setItem(`${draftKey()}:pending`, JSON.stringify(pendingChat))
  } catch {
    chatError.value = '请求号无法在本机保护，请保持页面打开直到提交完成。'
  }
  try {
    const value = await askNoteChat(
      note.value.id,
      input,
      pendingChat.rev,
      pendingChat.id,
      pendingChat.sources,
    )
    if (mine === generation) {
      chat.value = value
      question.value = ''
      pendingChat = undefined
      try {
        localStorage.removeItem(`${draftKey()}:pending`)
      } catch {
        /* 成功会话已经服务端持久化。 */
      }
    }
  } catch (e) {
    if (mine === generation) chatError.value = companionError(e)
  } finally {
    if (mine === generation) busy.value = false
  }
}
function prepareTransfer(text: string, mode: 'note' | 'existing' | 'task', messageId: string) {
  const sources = messageSources.value[messageId] ?? []
  const references = sources
    .map(
      (source, index) =>
        `[来源${index + 1} · ${source.title.replace(/[[\]\\]/g, '\\$&')} · v${source.rev}](/notes?note=${encodeURIComponent(source.id)})`,
    )
    .join('\n')
  transfer.value = {
    text: references ? `${text}\n\n来源小记：\n${references}` : text,
    mode,
    messageId,
  }
  transferTitle.value = mode === 'task' ? '' : '围绕小记的继续思考'
}
function openChatSource(source: ChatSource, index: number, answer: string) {
  const quote = answer.match(new RegExp(`\\[来源${index + 1}\\] “([^”]+)”`))?.[1]
  closePanel()
  emit('openNote', source.id, quote, source.rev)
}
function confirmTransfer() {
  if (!transfer.value || !note.value) return
  if (transfer.value.mode === 'task') {
    if (ui.modal === 'task') {
      chatError.value = '已有任务正在编辑，请先保存或关闭，再带入这份草稿。'
      return
    }
    ui.openModal('task')
    ui.modalTaskSeed = {
      draftKey: `wao-note-answer-task-${note.value.id}`,
      title: transferTitle.value,
      desc: transfer.value.text,
      sourceTitle: note.value.title,
      sourceUrl: `/notes?note=${note.value.id}`,
      sourceDocId: note.value.id,
    }
  } else if (transfer.value.mode === 'existing')
    emit(
      'appendExisting',
      note.value.id,
      `\n\n${transferTitle.value}\n\n${transfer.value.text}`,
      transfer.value.messageId,
    )
  else
    emit('append', `# ${transferTitle.value}\n\n${transfer.value.text}`, transfer.value.messageId)
  transfer.value = undefined
  closePanel()
}
const timer = setInterval(() => {
  if (!document.hidden && !busy.value) void refresh()
}, 10_000)
onBeforeUnmount(() => {
  generation++
  if (timer) clearInterval(timer)
  window.removeEventListener('resize', resizePanel)
})
defineExpose({ refresh })
</script>

<template>
  <Teleport to="body" :disabled="!overlayOpen">
    <section
      ref="panel"
      class="ai-companion"
      :class="{
        'reader-overlay': overlayOpen,
        'mobile-open': mobile && mobileOpen,
        'mobile-closed': mobile && !mobileOpen && !overlayOpen,
        'desktop-collapsed': desktopCollapsed,
      }"
      aria-label="AI 伴读"
    >
      <button v-if="desktopCollapsed" class="companion-dock" type="button" aria-label="展开 AI 伴读" :aria-expanded="false" @click="emit('collapse', false)">
        <i class="ri-sparkling-2-line" aria-hidden="true" /><strong>AI 伴读</strong><span class="dock-status" :class="{ unread: overview?.unread }">{{ dockStatus }}</span><span class="dock-expand">展开 <i class="ri-arrow-up-s-line" aria-hidden="true" /></span>
      </button>
      <header v-show="!desktopCollapsed" class="companion-heading">
        <div>
          <i class="ri-sparkling-2-line" aria-hidden="true"></i><strong>AI 伴读</strong
          ><span>{{
            mobile && !mobileOpen && overview?.unread
              ? `${overview.unread} 项新成果`
              : '让整理看得见'
          }}</span>
        </div>
        <button
          v-if="mobile || overlayOpen"
          type="button"
          :aria-expanded="mobileOpen"
          :aria-label="overlayOpen ? '返回阅读' : mobileOpen ? '返回小记空间' : '打开 AI 伴读'"
          @click="overlayOpen || mobileOpen ? closePanel() : (mobileOpen = true)"
        >
          {{ overlayOpen ? '返回阅读' : mobileOpen ? '返回小记' : '打开伴读' }}</button
        ><button
          v-if="!mobile || mobileOpen || overlayOpen"
          type="button"
          aria-label="伴读设置"
          :aria-expanded="settingsOpen"
          @click="settingsOpen = !settingsOpen"
        >
          <i class="ri-settings-3-line" />
        </button>
        <button v-if="!mobile && !overlayOpen" class="companion-collapse" type="button" aria-label="收起 AI 伴读" :aria-expanded="true" title="收进底部，为记录腾出空间" @click="emit('collapse', true)"><i class="ri-arrow-down-s-line" aria-hidden="true" /></button>
      </header>
      <div v-show="!desktopCollapsed && (!mobile || mobileOpen || overlayOpen)" class="companion-body">
        <div v-if="settingsOpen" class="companion-settings">
          <strong>成果采用方式</strong>
          <div class="mode-choice">
            <button
              :aria-pressed="overview?.mode === 'AUTO'"
              :disabled="busy || !overview"
              @click="changeMode('AUTO')"
            >
              自动采用</button
            ><button
              :aria-pressed="overview?.mode === 'REVIEW'"
              :disabled="busy || !overview"
              @click="changeMode('REVIEW')"
            >
              先看建议
            </button>
          </div>
          <p>仅影响之后发起的整理，已排队的任务保留原策略。</p>
          <button @click="emit('settings')">
            模型、提示词与自动开关 <i class="ri-arrow-right-up-line" /></button
          ><NoteDiscoveryPreference />
        </div>
        <div class="companion-health" role="status">
          <span class="health-dot" :class="{ ready: overview?.available }" />{{
            loading ? '正在读取执行状态…' : overview?.message || '等待服务状态'
          }}<small v-if="overview && (overview.queued || overview.running)"
            >{{ overview.running }} 执行 · {{ overview.queued }} 等待</small
          >
        </div>
        <nav aria-label="伴读栏目">
          <button
            v-for="item in tabs"
            :key="item.id"
            :aria-pressed="tab === item.id"
            @click="tab = item.id"
          >
            {{ item.text }}
          </button>
        </nav>
        <label v-if="tab !== 'discovery'" class="note-picker"
          >围绕这篇<select
            :value="activeId"
            aria-label="选择伴读小记"
            @change="emit('select', ($event.target as HTMLSelectElement).value)"
          >
            <option value="">选择一篇已保存的小记</option>
            <option v-for="n in ownNotes" :key="n.id" :value="n.id">{{ n.title }}</option>
          </select></label
        >
        <p v-if="error" class="companion-error" role="alert">
          {{ error }} <button :disabled="busy" @click="refresh()">重试读取</button>
        </p>
        <p v-if="blocked && tab !== 'discovery'" class="draft-warning">
          这篇有修改待保存，成果可以看；保存后再采用或提问。
        </p>
        <div class="companion-content">
          <template v-if="tab === 'results'">
            <div v-if="note" class="stage-tabs">
              <button
                v-for="(text, id) in labels"
                :key="id"
                :aria-pressed="stage === id"
                @click="chooseStage(id)"
              >
                {{ text
                }}<span
                  v-if="results?.items.some((a) => a.stage === id && !a.seen)"
                  class="unread-dot"
                />
              </button>
            </div>
            <template v-if="artifact">
              <div class="artifact-status">
                <b>{{ applicationLabel(artifact.application) }}</b
                ><span v-if="artifact.validity === 'STALE'">来源已更新 · 仅供对照</span
                ><small>来源 v{{ artifact.sourceRev }}</small>
              </div>
              <select
                v-if="visibleArtifacts.length > 1"
                v-model="chosen"
                aria-label="选择历史成果"
                @change="seen(artifact)"
              >
                <option v-for="a in visibleArtifacts" :key="a.id" :value="a.id">
                  {{ a.createdAt }} · {{ a.application }}
                </option>
              </select>
              <template v-if="stage === 'image'"
                ><img class="result-image" :src="artifact.resultText" alt="本次 AI 配图成果" />
                <details>
                  <summary>查看配图依据</summary>
                  <pre>{{ artifact.sourceText }}</pre>
                </details></template
              >
              <template v-else
                ><div class="diff-summary">
                  变更范围：移除 {{ artifact.removedChars }} · 新增 {{ artifact.addedChars }} 个字符
                </div>
                <div class="artifact-compare">
                  <details open>
                    <summary>{{ stage === 'tags' ? '原有标签' : '加工前' }}</summary>
                    <pre>{{ artifact.sourceText || '（空）' }}</pre>
                  </details>
                  <details open>
                    <summary>{{ stage === 'tags' ? '推荐的已有标签' : 'AI 成果' }}</summary>
                    <pre>{{ artifact.resultText || '本次没有推荐标签' }}</pre>
                  </details>
                </div></template
              >
              <div class="artifact-actions">
                <button
                  v-if="artifact.application === 'PENDING'"
                  class="primary"
                  :disabled="busy || blocked || artifact.validity !== 'CURRENT'"
                  @click="action('apply')"
                >
                  采用{{ labels[stage] }}</button
                ><button
                  v-if="artifact.application === 'APPLIED'"
                  :disabled="busy || blocked || artifact.validity !== 'CURRENT'"
                  @click="action('restore')"
                >
                  恢复加工前</button
                ><button
                  v-if="artifact.application === 'PENDING'"
                  :disabled="busy"
                  @click="action('decline')"
                >
                  忽略</button
                ><button :disabled="busy || blocked" @click="emit('run', stage)">重新整理</button
                ><button @click="copyText(artifact.resultText, '成果已复制')">复制成果</button>
              </div>
              <ul
                v-if="stage === 'tags' && artifact.tagRecommendations?.length"
                class="tag-reasons"
              >
                <li v-for="t in artifact.tagRecommendations" :key="t.name">
                  <b># {{ t.name }}</b> · {{ t.reason || '这次历史结果未记录匹配说明' }}
                </li>
              </ul>
              <details v-if="paragraphDiff.length">
                <summary>逐段对照</summary>
                <p v-for="(p, i) in paragraphDiff" :key="i" class="diff-paragraph" :class="p.kind">
                  <span>{{ p.kind === 'added' ? '＋ ' : p.kind === 'removed' ? '− ' : '＝ ' }}</span
                  >{{ p.text }}
                </p>
              </details>
              <details v-if="artifact.currentText !== undefined">
                <summary>当前已保存 · v{{ artifact.currentRev }}</summary>
                <pre>{{ artifact.currentText || '（空）' }}</pre>
              </details>
              <p v-if="artifact.reasonCode === 'REVIEW_REQUIRED'" class="draft-warning">
                {{ artifact.reason }}
                <button @click="editSelectedNote">打开编辑器对照采用</button>
              </p>
              <small class="model-info"
                >{{ artifact.model || '历史模型未记录'
                }}{{ artifact.effort ? ` · ${artifact.effort}` : '' }} ·
                {{ artifact.finishedAt || artifact.createdAt }}</small
              >
            </template>
            <div v-else class="empty-state">
              <i class="ri-quill-pen-line" /><strong>{{
                note ? '这项还没有可对照的成果' : '从右侧选一篇，开始伴读'
              }}</strong>
              <p>
                {{
                  activeRuns.length
                    ? '任务正在队列中推进，完成后会出现在这里。'
                    : '原稿先留好，AI 的每一次整理都能回来看。'
                }}
              </p>
              <button
                v-if="note"
                class="primary"
                :disabled="busy || blocked || !overview?.available"
                @click="emit('run', stage)"
              >
                整理{{ labels[stage] }}
              </button>
            </div>
            <div v-if="activeRuns.length" class="active-work">
              <span>{{ activeRuns[0]?.message }}</span
              ><button :disabled="busy" @click="cancel">停止本篇整理</button>
            </div>
            <button
              v-if="results?.nextCursor"
              class="more"
              :disabled="loading"
              @click="refresh(true)"
            >
              更早的成果
            </button>
          </template>
          <template v-else-if="tab === 'discovery'"
            ><p class="discovery-state" role="status">{{ discoveryText }}</p>
            <p v-if="discovery?.coverage" class="model-info">
              近 {{ discovery.coverage.days }} 天 · {{ discovery.coverage.indexed }} 篇已索引 /
              {{ discovery.coverage.eligible }} 篇可用 · 本次
              {{ discovery.coverage.analyzed }} 篇分析材料
            </p>
            <NoteDiscoveryInline
              v-if="discovery?.discovery"
              :item="discovery.discovery"
              @source="(item, id, event) => emit('source', item, id, event)"
              @changed="loadDiscovery" />
            <p v-if="discoveryError" class="companion-error" role="alert">
              {{ discoveryError }} <button @click="loadDiscovery">重试</button>
            </p>
            <NoteDiscoveryPreference
              v-if="
                !discovery?.discovery &&
                ['OFF', 'PAUSED', 'UNAVAILABLE'].includes(discovery?.automationState || '')
              "
          /></template>
          <template v-else-if="tab === 'runs'"
            ><div v-if="!results?.attempts.length" class="empty-state">
              <strong>还没有执行记录</strong>
              <p>从四项整理开始，排队、执行和采用会分别留痕。</p>
            </div>
            <ol class="execution-list">
              <li v-for="a in results?.attempts" :key="a.id">
                <i
                  :class="
                    a.state === 'DONE'
                      ? 'ri-check-line'
                      : a.state === 'FAILED'
                        ? 'ri-error-warning-line'
                        : 'ri-time-line'
                  "
                />
                <div>
                  <strong
                    >{{ labels[a.stage] }} · {{ executionLabel(a.state, a.runStatus) }}</strong
                  >
                  <p>{{ a.message }}</p>
                  <small>{{ a.updatedAt }}</small
                  ><button
                    v-if="a.state === 'FAILED'"
                    :disabled="blocked || busy"
                    @click="emit('run', a.stage)"
                  >
                    仅重试这一项
                  </button>
                </div>
              </li>
            </ol>
            <button v-if="activeRuns.length" :disabled="busy" @click="cancel">
              停止本篇整理
            </button></template
          >
          <template v-else>
            <p class="scope-hint">默认只读当前已保存小记。问题已在本机保护，关闭后可以继续。</p>
            <details v-if="note" class="source-choice">
              <summary>增加明确选择的来源（最多 5 篇）</summary>
              <label v-for="n in ownNotes.filter((n) => n.id !== activeId)" :key="n.id"
                ><input
                  v-model="selectedSources"
                  type="checkbox"
                  :value="n.id"
                  :disabled="selectedSources.length >= 5 && !selectedSources.includes(n.id)"
                />{{ n.title }}</label
              >
            </details>
            <div
              v-for="message in chat?.messages"
              :key="message.id"
              class="chat-message"
              :class="message.role"
            >
              <small>{{ message.role === 'user' ? '我' : 'AI 伴读' }}</small>
              <p>{{ message.content }}</p>
              <div v-if="messageSources[message.id]?.length" class="chat-sources">
                <span>这条回答的原文依据：</span
                ><button
                  v-for="(s, i) in messageSources[message.id]"
                  :key="s.id"
                  @click="openChatSource(s, i, message.content)"
                >
                  [来源{{ i + 1 }}] {{ s.title }} · v{{ s.rev }}
                </button>
              </div>
              <div v-if="message.role === 'assistant'" class="chat-actions">
                <button @click="prepareTransfer(message.content, 'note', message.id)">
                  带到新小记</button
                ><button @click="prepareTransfer(message.content, 'existing', message.id)">
                  附加到原小记</button
                ><button @click="prepareTransfer(message.content, 'task', message.id)">
                  整理成待办草稿</button
                ><button @click="copyText(message.content, '回答已复制')">复制</button>
              </div>
            </div>
            <p v-if="chatRunning" role="status">
              {{
                chat?.runs[0]?.status === 'secure_queued'
                  ? '已入队，等待本机执行'
                  : '正在回答，关闭页面后仍可回来查看'
              }}
            </p>
            <p
              v-for="r in chat?.runs.filter((r) => r.status === 'failed').slice(0, 1)"
              :key="r.id"
              class="companion-error"
            >
              {{ r.error }}
            </p>
            <p v-if="chatError" class="companion-error" role="alert">{{ chatError }}</p>
            <p v-if="note" class="scope-hint">
              本轮范围：{{ note.title
              }}{{
                selectedSources.length
                  ? '；' +
                    ownNotes
                      .filter((n) => selectedSources.includes(n.id))
                      .map((n) => n.title)
                      .join('；')
                  : '（仅当前小记）'
              }}
            </p>
            <form v-if="note" class="chat-form" @submit.prevent="ask">
              <textarea
                v-model="question"
                maxlength="4000"
                rows="3"
                aria-label="向小记 AI 提问"
                placeholder="从这篇小记，继续想一想…"
              /><button
                class="primary"
                :disabled="
                  busy || chatRunning || blocked || !question.trim() || !overview?.available
                "
              >
                {{ busy ? '提交中…' : '发送' }}
              </button>
            </form>
            <p v-else class="empty-state">先选择一篇小记，再继续聊。</p>
            <div v-if="transfer" class="transfer-confirm" role="group" aria-label="确认带到记录区">
              <strong>{{
                transfer.mode === 'task'
                  ? '待办草稿 · 不会直接创建任务'
                  : transfer.mode === 'existing'
                    ? '附加到原小记 · 先进入编辑草稿'
                    : '带到新小记 · 保存前可继续修改'
              }}</strong
              ><input
                v-model="transferTitle"
                :aria-label="transfer.mode === 'task' ? '待办草稿标题' : '新小记标题'"
                placeholder="输入标题"
              />
              <pre>{{ transfer.text }}</pre>
              <button :disabled="!transferTitle.trim()" class="primary" @click="confirmTransfer">
                {{
                  transfer.mode === 'task'
                    ? '打开待办草稿'
                    : transfer.mode === 'existing'
                      ? '打开原小记草稿'
                      : '带到左上记录区'
                }}</button
              ><button @click="transfer = undefined">取消</button>
            </div>
          </template>
        </div>
      </div>
    </section>
  </Teleport>
</template>

<style scoped>
.ai-companion.desktop-collapsed { min-height:44px; height:44px; box-sizing:border-box; }
.companion-dock { display:flex; align-items:center; gap:8px; flex:1; width:100%; min-width:0; border:0; padding:0 14px; background:var(--wao-bg-card); color:var(--wao-text-2); text-align:left; cursor:pointer; }
.companion-dock:hover { background:var(--wao-bg-hover); }
.companion-dock > i, .companion-collapse { color:var(--wao-primary-text); }
.companion-dock strong { color:var(--wao-text); font-size:13px; white-space:nowrap; }
.dock-status { font-size:11px; color:var(--wao-text-faint); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.dock-status.unread { color:var(--wao-primary-text); }
.dock-expand { margin-left:auto; white-space:nowrap; font-size:11px; color:var(--wao-text-3); }
.companion-collapse { margin-left:6px; }
.companion-heading > div { margin-right:auto; min-width:0; }
.companion-dock:focus-visible, .companion-collapse:focus-visible { outline:2px solid var(--wao-primary); outline-offset:-3px; }
.ai-companion {
  display: flex;
  flex-direction: column;
  min-height: 280px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font-size: 12px;
  overflow: hidden;
}
.companion-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px 8px;
}
.companion-heading > div {
  display: flex;
  align-items: center;
  gap: 7px;
}
.companion-heading i {
  color: var(--wao-primary-text);
}
.companion-heading strong {
  font-size: 14px;
  color: var(--wao-text);
}
.companion-heading span,
.model-info {
  color: var(--wao-text-faint);
  font-size: 11px;
}
.companion-health {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 0 16px 10px;
  color: var(--wao-text-3);
  font-size: 11px;
}
.health-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #aaa;
}
.health-dot.ready {
  background: #40aa86;
}
.companion-health small {
  margin-left: auto;
}
button {
  border: 0;
  border-radius: 7px;
  background: var(--wao-bg-hover);
  color: var(--wao-text-2);
  cursor: pointer;
  padding: 7px 9px;
  font: inherit;
}
button:hover,
button[aria-pressed='true'] {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
button:focus-visible,
select:focus-visible,
textarea:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 2px;
}
nav {
  display: flex;
  gap: 3px;
  padding: 0 10px 10px;
  border-bottom: 1px solid var(--wao-line);
}
nav button {
  flex: 1;
  white-space: nowrap;
  background: transparent;
  font-size: 11px;
}
.note-picker {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  color: var(--wao-text-faint);
}
select {
  min-width: 0;
  flex: 1;
  max-width: 100%;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
  padding: 6px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font: inherit;
}
.companion-content {
  min-height: 0;
  overflow: auto;
  padding: 0 14px 14px;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
.stage-tabs {
  display: flex;
  gap: 5px;
  margin-bottom: 10px;
}
.stage-tabs button {
  position: relative;
  flex: 1;
  background: transparent;
  border: 1px solid var(--wao-line);
}
.unread-dot {
  display: inline-block;
  width: 5px;
  height: 5px;
  background: var(--wao-primary);
  border-radius: 50%;
  margin-left: 4px;
}
.artifact-status {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin: 9px 0;
}
.artifact-status b {
  color: var(--wao-primary-text);
  font-weight: 500;
}
.artifact-status span {
  color: #aa743a;
}
.artifact-status small {
  margin-left: auto;
  color: var(--wao-text-faint);
}
.diff-summary {
  color: var(--wao-text-faint);
  font-size: 10px;
  margin: 8px 0;
}
.artifact-compare {
  display: grid;
  gap: 8px;
}
.artifact-compare details {
  border-radius: 9px;
  background: var(--wao-bg-sub);
  padding: 9px 11px;
}
.artifact-compare details:last-child {
  background: color-mix(in srgb, var(--wao-primary) 5%, var(--wao-bg-card));
}
summary {
  cursor: pointer;
  color: var(--wao-text-3);
  font-size: 11px;
}
pre,
.chat-message p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  line-height: 1.8;
  margin: 8px 0;
}
.artifact-actions,
.chat-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 10px 0;
}
.primary {
  background: var(--wao-primary) !important;
  color: white !important;
}
.result-image {
  width: 100%;
  max-height: 260px;
  object-fit: contain;
  border-radius: 10px;
}
.empty-state {
  display: grid;
  justify-items: center;
  text-align: center;
  padding: 20px 8px;
  color: var(--wao-text-3);
}
.empty-state i {
  font-size: 23px;
  color: var(--wao-primary-text);
  margin-bottom: 10px;
}
.empty-state p {
  line-height: 1.8;
  color: var(--wao-text-faint);
}
.active-work {
  display: flex;
  gap: 10px;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--wao-line);
}
.active-work span {
  min-width: 0;
  line-height: 1.7;
}
.companion-error,
.draft-warning {
  margin: 8px 14px;
  padding: 10px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-danger-text, #b42318);
  line-height: 1.7;
}
.draft-warning {
  color: #9b743c;
}
.companion-settings {
  padding: 12px 16px;
  border-bottom: 1px solid var(--wao-line);
  background: var(--wao-bg-sub);
}
.mode-choice {
  display: flex;
  gap: 5px;
  margin-top: 8px;
}
.companion-settings p {
  color: var(--wao-text-faint);
  line-height: 1.7;
}
.execution-list {
  list-style: none;
  padding: 0;
  margin: 0;
}
.execution-list li {
  display: flex;
  gap: 9px;
  padding: 12px 0;
  border-bottom: 1px solid var(--wao-line);
}
.execution-list i {
  color: var(--wao-primary-text);
  font-size: 17px;
}
.execution-list strong {
  font-weight: 500;
}
.execution-list p {
  margin: 5px 0;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.execution-list small {
  display: block;
  color: var(--wao-text-faint);
}
.discovery-state,
.scope-hint {
  line-height: 1.8;
  color: var(--wao-text-3);
}
.source-choice {
  padding: 8px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
}
.source-choice label {
  display: flex;
  align-items: start;
  gap: 6px;
  margin-top: 8px;
  line-height: 1.6;
}
.source-choice input {
  flex: none;
  width: 14px;
}
.chat-message {
  border-radius: 10px;
  padding: 10px;
  margin-top: 10px;
  background: var(--wao-bg-sub);
}
.chat-message.assistant {
  background: color-mix(in srgb, var(--wao-primary) 5%, var(--wao-bg-card));
}
.chat-message small {
  color: var(--wao-text-faint);
}
.chat-actions button {
  font-size: 11px;
}
.chat-sources {
  display: grid;
  gap: 5px;
  margin: 10px 0;
  color: var(--wao-text-faint);
}
.chat-sources button {
  text-align: left;
  overflow-wrap: anywhere;
}
.chat-form {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}
.chat-form textarea {
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 10px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
  font-size: 14px;
  line-height: 1.7;
}
.chat-form > button {
  justify-self: end;
}
.transfer-confirm {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
}
.transfer-confirm input {
  box-sizing: border-box;
  width: 100%;
  border: 1px solid var(--wao-line-control);
  padding: 9px;
  margin: 10px 0;
  border-radius: 6px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
}
.transfer-confirm pre {
  max-height: 200px;
  overflow: auto;
}
.transfer-confirm button {
  margin: 4px;
}
.more {
  display: block;
  margin: 10px auto;
}
@media (max-width: 760px) {
  button,
  select,
  summary {
    min-height: 44px;
    box-sizing: border-box;
  }
  .ai-companion {
    max-height: none;
  }
  .companion-content {
    max-height: 540px;
  }
  nav {
    gap: 0;
  }
  nav button {
    padding: 5px;
    font-size: 12px;
  }
  .chat-form textarea,
  .transfer-confirm input {
    font-size: 16px;
  }
  .companion-heading span {
    font-size: 10px;
  }
  .source-choice input {
    margin-top: 4px;
  }
}
</style>

<style scoped>
.diff-paragraph {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  padding: 8px;
  border-radius: 6px;
  line-height: 1.8;
}
.diff-paragraph.added {
  background: #eaf7ee;
  color: #276944;
}
.diff-paragraph.removed {
  background: #fff0ee;
  color: #963e32;
}
.diff-paragraph.same {
  color: var(--wao-text-faint);
}
.companion-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.ai-companion.reader-overlay {
  position: fixed;
  inset: 12px 12px 12px auto;
  width: min(520px, calc(100vw - 24px));
  height: calc(100dvh - 24px);
  max-height: none;
  z-index: 1100;
  background: var(--wao-bg-card);
  box-shadow: 0 10px 50px #211b4330;
}
.reader-overlay .companion-content {
  flex: 1;
  max-height: none;
}
@media (max-width: 760px) {
  .ai-companion.mobile-closed {
    min-height: 64px;
  }
  .mobile-closed .companion-heading {
    padding: 10px 14px;
  }
  .ai-companion.mobile-open {
    position: fixed;
    inset: 0;
    z-index: 1100;
    height: 100dvh;
    box-sizing: border-box;
    border-radius: 0;
    padding: env(safe-area-inset-top) 0 env(safe-area-inset-bottom);
    background: var(--wao-bg-card);
  }
  .mobile-open .companion-content {
    max-height: none;
    flex: 1;
  }
  .mobile-open .companion-heading {
    flex: none;
    gap: 6px;
  }
  .mobile-open .companion-heading span {
    display: none;
  }
}
</style>
