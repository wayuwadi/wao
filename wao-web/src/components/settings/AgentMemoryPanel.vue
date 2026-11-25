<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  agentMemoryHistory, listAgentMemories, proposeAgentMemory, reviewAgentMemory,
  type AgentMemory, type MemoryInput, type MemoryReview, type MemorySource, type MemoryState,
} from '@/api/agentMemories'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoCard from '@/components/ui/WaoCard.vue'
import WaoField from '@/components/ui/WaoField.vue'

const props = defineProps<{ memberId: string }>()
const workspaceUrl = new URL('/api/mcp/workspace', window.location.origin).toString()
const states = { draft: '待确认', active: '已确认', archived: '已归档', rejected: '已驳回' } as const
const sources = { codex: 'Codex', agy: 'Agy', import: '导入', manual: '手动' } as const
const kinds = { preference: '使用偏好', experience: '工作经验' } as const
const status = ref<MemoryState>('draft')
const query = ref('')
const projectFilter = ref('')
const sourceFilter = ref<MemorySource | ''>('')
const offset = ref(0)
const rows = ref<AgentMemory[]>([])
const hasMore = ref(false)
const loading = ref(false)
const listError = ref('')
const saving = ref(false)
const saveError = ref('')
const notice = ref('')
const editor = ref<HTMLElement>()
const history = ref<AgentMemory[]>()
const historyBusy = ref(false)
const mergeQuery = ref('')
const comparisons = ref<AgentMemory[]>()
const comparing = ref(false)
const latest = ref<AgentMemory>()
const latestTarget = ref<AgentMemory>()
const conflict = ref(false)
let loadGeneration = 0

interface Draft {
  input: MemoryInput
  startup: boolean
  base?: AgentMemory
  target?: AgentMemory
}

const draftKey = `wao:agent-memory-draft:v1:${props.memberId}`
function readDraft(): Draft | undefined {
  try {
    const saved = sessionStorage.getItem(draftKey)
    if (!saved) return undefined
    const value = JSON.parse(saved) as Draft | null
    return value?.input && typeof value.startup === 'boolean' ? value : undefined
  } catch { return undefined }
}
const draft = ref<Draft | undefined>(readDraft())
const base = computed(() => draft.value?.base)
const target = computed(() => draft.value?.target)
const editable = computed(() => !base.value || ['draft', 'active'].includes(base.value.state))
const startupAvailable = computed(() =>
  !!base.value && draft.value?.input.kind === 'preference' && !draft.value.input.project.trim())
const dirty = computed(() => {
  const current = draft.value
  if (!current) return false
  const original = current.base
  return original
    ? current.input.title !== original.title || current.input.project !== original.project ||
      current.input.content !== original.content || current.startup !== original.startup || !!current.target
    : !!(current.input.title || current.input.content || current.input.project || current.input.sourceSummary ||
      current.input.kind !== 'experience' || current.input.source !== 'manual')
})

const draftStorageError = ref(false)
watch(draft, (value) => {
  try {
    if (value && dirty.value) sessionStorage.setItem(draftKey, JSON.stringify(value))
    else sessionStorage.removeItem(draftKey)
    draftStorageError.value = false
  } catch {
    draftStorageError.value = !!value && dirty.value
  }
}, { deep: true, flush: 'sync' })

const errorText = (error: unknown) => error instanceof Error ? error.message : '请求失败，请重试'
const dateLabel = (value: string) => value.slice(0, 16).replace('T', ' ')

async function load(start = offset.value) {
  const generation = ++loadGeneration
  loading.value = true
  listError.value = ''
  try {
    const page = await listAgentMemories({
      state: status.value, query: query.value.trim() || undefined,
      project: projectFilter.value.trim() || undefined, source: sourceFilter.value || undefined, offset: start,
    })
    if (generation !== loadGeneration) return
    if (!page.items.length && start > 0) { await load(Math.max(0, start - 20)); return }
    offset.value = start
    rows.value = page.items
    hasMore.value = page.hasMore
  } catch (error) {
    if (generation === loadGeneration) listError.value = errorText(error)
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}
void load()

function resetEditorFeedback() {
  saveError.value = ''
  notice.value = ''
  history.value = undefined
  comparisons.value = undefined
  mergeQuery.value = ''
  conflict.value = false
  latest.value = undefined
  latestTarget.value = undefined
}

function edit(row?: AgentMemory) {
  if (saving.value || (row && row.id === base.value?.id)) return
  if (dirty.value && !window.confirm('要放弃当前草稿并打开另一条记忆吗？')) return
  resetEditorFeedback()
  const input: MemoryInput = row ? {
    title: row.title, content: row.content, project: row.project, kind: row.kind,
    source: row.source, sourceSummary: row.sourceSummary,
  } : { kind: 'experience', source: 'manual', title: '', content: '', project: '', sourceSummary: '' }
  draft.value = { input, startup: !!row?.startup, base: row }
  void nextTick(() => editor.value?.focus())
}

function closeEditor() {
  if (saving.value || (dirty.value && !window.confirm('要丢弃当前草稿吗？'))) return
  draft.value = undefined
  resetEditorFeedback()
}

async function fetchHistory() {
  const row = base.value
  if (!row) return
  historyBusy.value = true
  try {
    const values = await agentMemoryHistory(row.id)
    if (base.value?.id === row.id) history.value = values
  } catch (error) {
    if (base.value?.id === row.id) saveError.value = errorText(error)
  } finally { historyBusy.value = false }
}

async function findComparisons() {
  const row = base.value
  if (!row) return
  comparing.value = true
  try {
    const page = await listAgentMemories({ state: 'active', project: row.project, query: mergeQuery.value.trim() })
    if (base.value?.id === row.id) comparisons.value = page.items.filter(item => item.kind === row.kind)
  } catch (error) {
    if (base.value?.id === row.id) saveError.value = errorText(error)
  } finally { comparing.value = false }
}

function chooseMerge(row: AgentMemory) {
  if (!draft.value || !base.value) return
  draft.value.target = row
  draft.value.input.project = row.project
  draft.value.startup = row.startup
  void nextTick(() => editor.value?.focus())
}

async function loadConflict() {
  const row = base.value
  const merge = target.value
  if (!row) return
  saving.value = true
  try {
    const [current, targetHistory] = await Promise.all([
      agentMemoryHistory(row.id),
      merge ? agentMemoryHistory(merge.id) : Promise.resolve([]),
    ])
    latest.value = current[0]
    latestTarget.value = targetHistory[0]
  } catch (error) { saveError.value = errorText(error) }
  finally { saving.value = false }
}

function useLatestVersion() {
  if (!draft.value || !latest.value || latest.value.state !== base.value?.state) return
  if (target.value && latestTarget.value?.state !== 'active') return
  draft.value.base = latest.value
  draft.value.target = latestTarget.value
  conflict.value = false
  saveError.value = ''
}

async function save(action?: MemoryReview['action']) {
  const current = draft.value
  if (!current || saving.value || conflict.value) return
  const row = current.base
  const chosen = action ?? (current.target ? 'merge' : row?.state === 'draft' ? 'publish' : 'revise')
  if (chosen === 'archive' && !window.confirm('归档后，Agent 将无法继续查到这条记忆。确定归档吗？')) return
  if (chosen === 'reject' && !window.confirm('确定驳回这条候选吗？')) return
  saving.value = true
  saveError.value = ''
  notice.value = ''
  try {
    if (row) {
      await reviewAgentMemory(row.id, {
        action: chosen, expectedRevision: row.revisionId, title: current.input.title, content: current.input.content,
        project: current.input.project, startup: startupAvailable.value && current.startup,
        targetId: current.target?.id, expectedTargetRevision: current.target?.revisionId,
      })
    } else {
      const result = await proposeAgentMemory(current.input)
      if (result.state !== 'draft') notice.value = `已存在这条记忆（${states[result.state]}）`
    }
    draft.value = undefined
    if (!notice.value) notice.value = row ? '操作已保存' : '候选已提交，等待确认'
    await load(0)
  } catch (error) {
    saveError.value = errorText(error)
    conflict.value = (error as { status?: number })?.status === 409
  } finally { saving.value = false }
}

defineExpose({ confirmLeave: () => !saving.value && (!draftStorageError.value || !dirty.value || window.confirm('浏览器无法保留草稿，确认离开吗？')) })
</script>

<template>
  <section class="memory-panel">
    <header>
      <h1>Agent 记忆</h1>
      <p>让 Codex 和 Agy 在新的对话和设备上沿用你确认过的偏好与经验。</p>
    </header>

    <WaoCard variant="soft">
      <details>
        <summary>连接 Agent 与恢复到新 Mac</summary>
        <ol>
          <li>在 WAO 源码的 <code>wao-cli</code> 目录运行 <code>npm link</code>，安装 <code>wao</code> 命令。</li>
          <li>在<RouterLink to="/capabilities?area=connections">能力中心</RouterLink>新建 CLI 连接，选择「仅 Agent 记忆」。</li>
          <li>执行 <code>wao memories authorize --url {{ workspaceUrl }}</code>，按提示输入设备令牌。</li>
          <li>用 <code>wao memories restore --url {{ workspaceUrl }}</code> 预览，确认后加 <code>--apply</code>。撤销用 <code>wao memories restore --undo</code>。</li>
        </ol>
        <p>导入旧手记可运行 <code>wao memories import --help</code>，预览和选择后再提交。</p>
      </details>
    </WaoCard>

    <form class="memory-filters" @submit.prevent="load(0)">
      <WaoField v-slot="{ id }" label="状态"><select :id="id" v-model="status" @change="load(0)">
        <option v-for="(label, value) in states" :key="value" :value="value">{{ label }}</option>
      </select></WaoField>
      <WaoField v-slot="{ id }" label="关键词"><input :id="id" v-model="query" type="search" maxlength="200" /></WaoField>
      <WaoField v-slot="{ id }" label="项目"><input :id="id" v-model="projectFilter" placeholder="仓库名" maxlength="120" /></WaoField>
      <WaoField v-slot="{ id }" label="来源"><select :id="id" v-model="sourceFilter">
        <option value="">全部</option>
        <option v-for="(label, value) in sources" :key="value" :value="value">{{ label }}</option>
      </select></WaoField>
      <WaoButton type="submit" :loading="loading">搜索</WaoButton>
      <WaoButton variant="ghost" :disabled="saving" @click="edit()">添加候选</WaoButton>
    </form>

    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="listError" role="alert" class="memory-error">{{ listError }} <WaoButton @click="load()">重试</WaoButton></p>
    <p v-else-if="loading" role="status">正在读取记忆…</p>
    <p v-else-if="!rows.length && !draft">当前筛选没有记忆。</p>

    <WaoCard v-if="draft" padding="roomy">
      <div ref="editor" tabindex="-1" class="memory-editor">
        <div class="memory-actions">
          <h2>{{ base ? base.title : '添加记忆候选' }}</h2>
          <WaoButton variant="ghost" :disabled="saving" @click="closeEditor">关闭</WaoButton>
        </div>
        <p v-if="base" class="memory-meta">{{ states[base.state] }} · {{ sources[base.source] }} · {{ base.project || '通用' }} · 版本 {{ base.version }} · {{ dateLabel(base.createdAt) }}</p>
        <p v-if="base?.sourceSummary">{{ base.sourceSummary }}</p>
        <p v-if="draftStorageError" role="alert" class="memory-error">浏览器无法暂存草稿，请先保存或复制内容。</p>
        <p v-if="saveError" role="alert" class="memory-error">{{ saveError }}</p>
        <div v-if="conflict" class="memory-comparison">
          <WaoButton :loading="saving" @click="loadConflict">查看账号上的最新版本</WaoButton>
          <template v-if="latest">
            <p>{{ states[latest.state] }} · 版本 {{ latest.version }} · {{ latest.title }}</p>
            <pre>{{ latest.content }}</pre>
            <template v-if="latestTarget"><p>合并目标 · 版本 {{ latestTarget.version }} · {{ latestTarget.title }}</p><pre>{{ latestTarget.content }}</pre></template>
            <WaoButton
              v-if="latest.state === base?.state && (!target || latestTarget?.state === 'active')"
              @click="useLatestVersion"
            >继续使用我的草稿</WaoButton>
            <p v-else>操作状态已变化。可复制草稿，关闭后重新选择记忆。</p>
          </template>
        </div>
        <template v-if="target">
          <div class="memory-comparison">
            <h3>合并目标 · {{ target.title }}</h3>
            <pre>{{ target.content }}</pre>
            <p>下方填写合并后的完整内容。提交后更新目标记忆，本条候选进入归档。</p>
            <WaoButton variant="ghost" :disabled="saving" @click="draft.target = undefined">取消合并</WaoButton>
          </div>
        </template>
        <form v-if="editable" class="memory-form" @submit.prevent="save()">
          <template v-if="!base">
            <WaoField v-slot="{ id }" label="类型"><select :id="id" v-model="draft.input.kind">
              <option v-for="(label, value) in kinds" :key="value" :value="value">{{ label }}</option>
            </select></WaoField>
            <WaoField v-slot="{ id }" label="来源"><select :id="id" v-model="draft.input.source">
              <option value="manual">手动整理</option><option value="import">从旧记忆导入</option>
            </select></WaoField>
            <WaoField v-slot="{ id }" label="来源摘要"><input :id="id" v-model="draft.input.sourceSummary" maxlength="480" /></WaoField>
          </template>
          <WaoField v-slot="{ id }" label="标题" required><input :id="id" v-model="draft.input.title" required maxlength="200" /></WaoField>
          <WaoField v-slot="{ id, describedBy }" label="项目标识" hint="填写仓库名或组织/仓库名。留空表示通用。">
            <input :id="id" v-model="draft.input.project" :aria-describedby="describedBy" maxlength="120" :disabled="!!target" />
          </WaoField>
          <WaoField v-slot="{ id, describedBy }" label="记忆正文" hint="只保存可复用的做法，可使用 Markdown。项目状态在使用前重新核实。" required>
            <textarea :id="id" v-model="draft.input.content" :aria-describedby="describedBy" required maxlength="4000" rows="6" />
          </WaoField>
          <label v-if="startupAvailable" class="memory-checkbox"><input v-model="draft.startup" type="checkbox" />在恢复时写入两端的全局偏好（正文最多 500 字）</label>
          <div class="memory-actions">
            <WaoButton type="submit" variant="primary" :loading="saving" :disabled="conflict">{{ !base ? '提交候选' : target ? '确认合并' : base.state === 'draft' ? '确认并生效' : '保存修订' }}</WaoButton>
            <WaoButton v-if="base?.state === 'draft'" variant="ghost" :disabled="saving || conflict" @click="save('reject')">驳回</WaoButton>
            <WaoButton v-if="base?.state === 'active'" variant="ghost" :disabled="saving || conflict" @click="save('archive')">归档</WaoButton>
          </div>
        </form>
        <pre v-else>{{ draft.input.content }}</pre>
        <details v-if="base?.state === 'draft'">
          <summary>对照或合并已有记忆</summary>
          <form class="memory-actions" @submit.prevent="findComparisons">
            <WaoField v-slot="{ id }" label="已有记忆的关键词"><input :id="id" v-model="mergeQuery" type="search" maxlength="200" /></WaoField>
            <WaoButton type="submit" :loading="comparing">查找同范围记忆</WaoButton>
          </form>
          <p v-if="comparisons && !comparisons.length">该范围没有匹配的记忆。</p>
          <details v-for="item in comparisons" :key="item.id">
            <summary>{{ item.title }}</summary><pre>{{ item.content }}</pre>
            <WaoButton :disabled="saving || conflict" @click="chooseMerge(item)">合并到这条记忆</WaoButton>
          </details>
        </details>
        <details v-if="base" @toggle="(event) => { if ((event.target as HTMLDetailsElement).open && !history) void fetchHistory() }">
          <summary>版本历史</summary>
          <p v-if="historyBusy" role="status">正在读取版本…</p>
          <details v-for="item in history" :key="item.revisionId">
            <summary>版本 {{ item.version }} · {{ dateLabel(item.createdAt) }} · {{ sources[item.source] }} · {{ item.title }}</summary>
            <p v-if="item.sourceSummary">{{ item.sourceSummary }}</p><pre>{{ item.content }}</pre>
          </details>
          <WaoButton v-if="!history && !historyBusy" @click="fetchHistory">重新读取历史</WaoButton>
        </details>
      </div>
    </WaoCard>

    <ul v-if="rows.length" class="memory-list" :aria-busy="loading">
      <li v-for="item in rows" :key="item.id">
        <button class="memory-list-item" type="button" :disabled="saving" @click="edit(item)">
          <strong>{{ item.title }}</strong>
          <span class="memory-meta">{{ kinds[item.kind] }} · {{ item.project || '通用' }} · {{ sources[item.source] }}<template v-if="item.startup"> · 全局偏好</template></span>
        </button>
      </li>
    </ul>
    <div v-if="offset || hasMore" class="memory-actions">
      <WaoButton :disabled="loading || !offset" @click="load(offset - 20)">上一页</WaoButton>
      <WaoButton :disabled="loading || !hasMore" @click="load(offset + 20)">下一页</WaoButton>
    </div>
  </section>
</template>

<style scoped>
.memory-panel, .memory-editor, .memory-form { display: grid; gap: var(--wao-space-3); min-width: 0; }
.memory-panel :is(h1, h2, h3, p) { margin: 0; overflow-wrap: anywhere; }
.memory-panel h1 { font-size: var(--wao-font-title); }
.memory-panel h2 { font-size: var(--wao-font-subtitle); }
.memory-panel :is(summary, code, pre) { overflow-wrap: anywhere; }
.memory-panel summary { cursor: pointer; padding-block: var(--wao-space-1); }
.memory-panel pre { white-space: pre-wrap; font: inherit; padding: var(--wao-space-2); background: var(--wao-bg-sub); border-radius: var(--wao-radius-card); }
.memory-panel li { margin-block: var(--wao-space-1); }
.memory-filters, .memory-actions { display: flex; flex-wrap: wrap; align-items: end; gap: var(--wao-space-2); }
.memory-filters :deep(.wao-field) { flex: 1 1 130px; }
.memory-form :is(input:not([type='checkbox']), select, textarea), .memory-actions input, .memory-filters :is(input, select) {
  background: var(--wao-bg-card); color: var(--wao-text); font: inherit; width: 100%; min-width: 0; min-height: var(--wao-control-height);
  border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); padding: var(--wao-space-1) var(--wao-space-2);
}
.memory-form textarea { resize: vertical; }
.memory-checkbox { display: flex; align-items: center; gap: var(--wao-space-2); }
.memory-list { padding: 0; margin: 0; list-style: none; }
.memory-list-item {
  display: grid; gap: var(--wao-space-1); text-align: start; width: 100%; min-height: 44px; overflow-wrap: anywhere;
  border: 1px solid var(--wao-line); border-radius: var(--wao-radius-card); background: var(--wao-bg-card);
  color: var(--wao-text); padding: var(--wao-space-2) var(--wao-space-3); cursor: pointer; font: inherit;
}
.memory-list-item:hover { background: var(--wao-bg-hover); }
.memory-meta { color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.memory-error { color: var(--wao-danger); }
</style>
