<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  archiveMemory,
  createMemory,
  fetchMemories,
  reviseMemory,
  type MemoryDocument,
  type SaveMemoryPayload,
} from '@/api/unifiedMemories'
import { toast } from '@/composables/toast'
import { useSessionStore } from '@/stores/session'
import { formatDecisionContent, parseDecisionContent } from '@/utils/decisionContent'

const session = useSessionStore()
const decisions = ref<MemoryDocument[]>([])
const loading = ref(true)
const loadError = ref('')
const query = ref('')
const editorOpen = ref(false)
const editing = ref<MemoryDocument | null>(null)
const saving = ref(false)
const saveError = ref('')
const restored = ref(false)
const archivingId = ref('')
const form = reactive({ title: '', situation: '', choice: '', reason: '' })
let queryTimer: number | undefined

const draftKey = computed(() => `wao:decision-draft:${session.userId || 'unknown'}:${editing.value?.id || 'new'}`)

function details(item: MemoryDocument) {
  return parseDecisionContent(item.revision.content)
}

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  if (!session.userId) {
    decisions.value = []
    loading.value = false
    loadError.value = '无法识别当前账号，请重新登录'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    decisions.value = await fetchMemories({
      scopeType: 'personal',
      scopeId: session.userId,
      category: 'decision',
      query: query.value.trim() || undefined,
    })
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '决策读取失败，请重试'
  } finally {
    loading.value = false
  }
}

watch(query, () => {
  window.clearTimeout(queryTimer)
  queryTimer = window.setTimeout(() => void load(), 250)
})

function resetForm() {
  Object.assign(form, { title: '', situation: '', choice: '', reason: '' })
  saveError.value = ''
  restored.value = false
}

function restoreDraft() {
  const raw = localStorage.getItem(draftKey.value)
  if (!raw) return
  try {
    Object.assign(form, JSON.parse(raw))
    restored.value = true
  } catch {
    localStorage.removeItem(draftKey.value)
  }
}

function openCreate() {
  editing.value = null
  resetForm()
  restoreDraft()
  editorOpen.value = true
}

function openEdit(item: MemoryDocument) {
  editing.value = item
  resetForm()
  Object.assign(form, { title: item.revision.title, ...details(item) })
  restoreDraft()
  editorOpen.value = true
}

function closeEditor() {
  if (saving.value) return
  editorOpen.value = false
}

watch(form, () => {
  if (editorOpen.value && !saving.value) localStorage.setItem(draftKey.value, JSON.stringify(form))
}, { deep: true })

async function save() {
  if (!form.title.trim() || !form.choice.trim()) {
    saveError.value = '请填写决策标题和具体决定'
    return
  }
  saving.value = true
  saveError.value = ''
  const payload: SaveMemoryPayload = {
    scopeType: 'personal',
    scopeId: session.userId,
    category: 'decision',
    title: form.title.trim(),
    content: formatDecisionContent(form),
    changeSummary: editing.value ? '更新决策内容' : '新增个人决策',
    evidenceLinks: [],
    baseRevisionId: editing.value?.currentPublishedRevisionId,
  }
  try {
    if (editing.value) await reviseMemory(editing.value.id, payload)
    else await createMemory(payload)
    localStorage.removeItem(draftKey.value)
    editorOpen.value = false
    toast(editing.value ? '决策已更新并保留历史版本' : '决策已保存，可在相似情境中检索', 'ri-checkbox-circle-line')
    await load()
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

async function archive(item: MemoryDocument) {
  if (!window.confirm(`确认废止“${item.revision.title}”？历史版本仍会保留。`)) return
  archivingId.value = item.id
  try {
    await archiveMemory(item.id)
    toast('决策已废止，不再进入默认检索', 'ri-archive-line')
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '废止失败，请重试', 'ri-error-warning-line')
  } finally {
    archivingId.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="decisions-page">
    <header class="hero">
      <div>
        <div class="eyebrow"><i class="ri-route-line"></i> 个人选择原则</div>
        <h1>决策管理</h1>
        <p>把一次经历沉淀成下次可直接复用的选择，减少重复权衡和能量消耗。</p>
      </div>
      <button class="primary" type="button" @click="openCreate"><i class="ri-add-line"></i>新增决策</button>
    </header>

    <section class="toolbar">
      <label class="search"><i class="ri-search-line"></i><input v-model="query" placeholder="搜索情境、决定或原因" /></label>
      <RouterLink to="/memories?scope=personal" class="memory-link"><i class="ri-history-line"></i>在记忆中心查看完整版本</RouterLink>
    </section>

    <section v-if="loading" class="state-card"><i class="ri-loader-4-line spin"></i><span>正在读取你的决策…</span></section>
    <section v-else-if="loadError" class="state-card error" role="alert">
      <i class="ri-error-warning-line"></i><span>{{ loadError }}</span><button type="button" @click="load">重新加载</button>
    </section>
    <section v-else-if="!decisions.length" class="state-card empty">
      <i class="ri-compass-discover-line"></i>
      <h2>{{ query ? '没有匹配的决策' : '还没有沉淀决策' }}</h2>
      <p>{{ query ? '换个关键词，或清空搜索后查看全部。' : '从一次消耗精力的经历开始，写下下次要怎么选。' }}</p>
      <button v-if="!query" class="primary" type="button" @click="openCreate">记录第一条决策</button>
    </section>

    <section v-else class="decision-list" aria-live="polite">
      <article v-for="item in decisions" :key="item.id" class="decision-card">
        <header><div><span>确认决策 · v{{ item.revision.versionNo }}</span><h2>{{ item.revision.title }}</h2></div><i class="ri-route-line"></i></header>
        <dl>
          <template v-if="details(item).situation"><dt>何时适用</dt><dd>{{ details(item).situation }}</dd></template>
          <dt>具体决定</dt><dd class="choice">{{ details(item).choice }}</dd>
          <template v-if="details(item).reason"><dt>为什么</dt><dd>{{ details(item).reason }}</dd></template>
        </dl>
        <footer>
          <span>更新于 {{ formatTime(item.updatedAt) }}</span>
          <div><button type="button" @click="openEdit(item)"><i class="ri-edit-line"></i>编辑</button><button class="danger" type="button" :disabled="archivingId === item.id" @click="archive(item)"><i class="ri-archive-line"></i>{{ archivingId === item.id ? '废止中…' : '废止' }}</button></div>
        </footer>
      </article>
    </section>

    <div v-if="editorOpen" class="mask" @click.self="closeEditor">
      <section class="editor" role="dialog" aria-modal="true" :aria-label="editing ? '编辑决策' : '新增决策'">
        <header><div><h2>{{ editing ? '更新这条决策' : '新增决策' }}</h2><p>写清适用情境，未来检索时才知道何时采用。</p></div><button type="button" aria-label="关闭" :disabled="saving" @click="closeEditor"><i class="ri-close-line"></i></button></header>
        <div v-if="restored" class="draft"><i class="ri-history-line"></i>已恢复本机未保存草稿</div>
        <label>决策标题 <span>必填</span><input v-model="form.title" maxlength="120" placeholder="例如：去武汉的出行方式" /></label>
        <label>何时适用<textarea v-model="form.situation" maxlength="5000" rows="3" placeholder="例如：从当前城市去武汉，行程可安排在傍晚时"></textarea></label>
        <label>具体决定 <span>必填</span><textarea v-model="form.choice" maxlength="10000" rows="4" placeholder="例如：优先坐傍晚的高铁，到站后再坐地铁"></textarea></label>
        <label>为什么<textarea v-model="form.reason" maxlength="5000" rows="4" placeholder="记录事实依据和代价，方便以后判断是否仍然适用"></textarea></label>
        <div v-if="saveError" class="save-error" role="alert">{{ saveError }}；当前输入仍保存在本机，可直接重试。</div>
        <footer><span>{{ saving ? '保存中，请勿关闭…' : '输入会自动保存为本机草稿' }}</span><div><button type="button" :disabled="saving" @click="closeEditor">取消</button><button class="primary" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : editing ? '保存新版本' : '保存决策' }}</button></div></footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.decisions-page{max-width:none;margin:0 auto;padding:32px 28px 56px;color:var(--wao-text-1)}
.hero{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;padding:28px;border:1px solid var(--wao-line);border-radius:20px;background:linear-gradient(135deg,color-mix(in srgb,var(--wao-accent) 12%,var(--wao-bg-card)),var(--wao-bg-card))}
.eyebrow{color:var(--wao-accent);font-size:12px;font-weight:700;letter-spacing:.08em}.hero h1{margin:8px 0 6px;font-size:28px}.hero p{max-width:620px;margin:0;color:var(--wao-text-3);line-height:1.7}
button,.memory-link{border:1px solid var(--wao-line);border-radius:9px;background:var(--wao-bg-card);color:var(--wao-text-2);font:inherit;cursor:pointer}.primary{padding:10px 16px;border-color:var(--wao-accent);background:var(--wao-accent);color:#fff;font-weight:700}.primary i{margin-right:5px}button:disabled{cursor:not-allowed;opacity:.55}
.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:20px 0}.search{display:flex;align-items:center;flex:1;max-width:520px;padding:0 12px;border:1px solid var(--wao-line);border-radius:10px;background:var(--wao-bg-card);color:var(--wao-text-3)}.search input{width:100%;padding:10px;border:0;outline:0;background:transparent;color:var(--wao-text-1)}.memory-link{padding:9px 12px;text-decoration:none}.memory-link i{margin-right:5px}
.state-card{display:flex;align-items:center;justify-content:center;gap:10px;min-height:220px;padding:28px;border:1px dashed var(--wao-line);border-radius:16px;color:var(--wao-text-3);text-align:center}.state-card.error{color:var(--wao-danger-strong)}.state-card.error button{padding:7px 11px}.state-card.empty{flex-direction:column}.state-card.empty>i{font-size:34px;color:var(--wao-accent)}.state-card h2,.state-card p{margin:0}.state-card .primary{margin-top:8px}
.decision-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.decision-card{display:flex;min-width:0;flex-direction:column;padding:20px;border:1px solid var(--wao-line);border-radius:16px;background:var(--wao-bg-card);box-shadow:0 6px 24px rgba(31,41,55,.04)}.decision-card>header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.decision-card>header span{color:var(--wao-accent);font-size:11px;font-weight:700}.decision-card h2{margin:5px 0 0;font-size:18px;overflow-wrap:anywhere}.decision-card>header>i{font-size:24px;color:var(--wao-accent)}
dl{display:grid;gap:6px;margin:20px 0;white-space:pre-wrap;overflow-wrap:anywhere}dt{margin-top:8px;color:var(--wao-text-3);font-size:11px;font-weight:700}dd{margin:0;line-height:1.65}.choice{padding:10px 12px;border-left:3px solid var(--wao-accent);border-radius:0 8px 8px 0;background:var(--wao-bg-soft);font-weight:600}.decision-card>footer{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:auto;padding-top:14px;border-top:1px solid var(--wao-line);color:var(--wao-text-3);font-size:11px}.decision-card>footer div{display:flex;gap:7px}.decision-card>footer button{padding:6px 9px}.decision-card>footer .danger{color:var(--wao-danger-strong)}
.mask{position:fixed;z-index:1000;inset:0;display:flex;align-items:center;justify-content:center;padding:20px;background:rgba(15,23,42,.52);backdrop-filter:blur(4px)}.editor{display:flex;width:min(620px,100%);max-height:calc(100dvh - 40px);flex-direction:column;overflow:auto;border:1px solid var(--wao-line);border-radius:18px;background:var(--wao-bg-card);box-shadow:0 24px 70px rgba(0,0,0,.28)}.editor>header{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;padding:20px 22px 12px}.editor h2{margin:0;font-size:21px}.editor header p{margin:4px 0 0;color:var(--wao-text-3);font-size:12px}.editor header button{padding:6px;border:0;font-size:18px}.editor label{display:grid;gap:6px;padding:8px 22px;color:var(--wao-text-2);font-size:12px;font-weight:700}.editor label span{color:var(--wao-accent);font-size:10px}.editor input,.editor textarea{box-sizing:border-box;width:100%;padding:10px 11px;border:1px solid var(--wao-line);border-radius:9px;outline:0;resize:vertical;background:var(--wao-bg-soft);color:var(--wao-text-1);font:inherit;font-weight:400}.editor input:focus,.editor textarea:focus{border-color:var(--wao-accent)}.draft,.save-error{margin:4px 22px;padding:9px 11px;border-radius:8px;font-size:12px}.draft{background:var(--wao-warning-bg);color:var(--wao-warning-strong)}.save-error{background:var(--wao-danger-bg);color:var(--wao-danger-strong)}.editor>footer{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:10px;padding:15px 22px;border-top:1px solid var(--wao-line);color:var(--wao-text-3);font-size:11px}.editor>footer div{display:flex;gap:8px}.editor>footer button{padding:8px 12px}
.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:760px){.decisions-page{padding:18px 12px 36px}.hero{align-items:stretch;flex-direction:column;padding:20px}.hero .primary{width:100%}.toolbar{align-items:stretch;flex-direction:column}.search{box-sizing:border-box;max-width:none}.memory-link{text-align:center}.decision-list{grid-template-columns:1fr}.decision-card>footer{align-items:flex-start;flex-direction:column}.mask{align-items:flex-end;padding:7px}.editor{max-height:calc(100dvh - 14px)}.editor>footer{align-items:stretch;flex-direction:column}.editor>footer div{display:grid;grid-template-columns:1fr 1fr}}
</style>
