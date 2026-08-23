<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { toast } from '@/composables/toast'
import { useDataStore } from '@/stores/data'
import { apiPatchDoc, fetchDoc, fetchDocTemplates } from '@/api/board'
import {
  createRhythmDocument,
  fetchRhythmDashboard,
  setRhythmOccurrenceCompleted,
  setRhythmWorkdayStatus,
  type RhythmDashboard,
  type RhythmOccurrence,
} from '@/api/workdayRhythm'
import { createTodayTianrenReading, fetchTodayTianrenReading, type TianrenReading } from '@/api/tianren'
import { blocksToText, textToBlocks } from '@/utils/doc'
import type { DocTemplate } from '@/types'

const props = defineProps<{ date: string; reloadKey?: number }>()
const emit = defineEmits<{ loaded: [dashboard: RhythmDashboard] }>()
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const router = useRouter()
const data = useDataStore()
const dashboard = ref<RhythmDashboard | null>(null)
const loading = ref(true)
const error = ref('')
const toggling = ref('')
const reading = ref<TianrenReading | null>(null)
const casting = ref(false)
const templates = ref<DocTemplate[]>([])
const documentBody = ref('')
const documentId = ref<string | null>(null)
const documentState = ref<'idle' | 'dirty' | 'saving' | 'saved' | 'error'>('idle')
const documentError = ref('')
const hydratingDocument = ref(true)
const forceDocument = ref(false)
let saveTimer: ReturnType<typeof setTimeout> | null = null

const draftKey = computed(() => `wao-workday-rhythm-document-draft-v1:${props.date}`)
const completionRate = computed(() => dashboard.value?.totalCount
  ? Math.round((dashboard.value.completedCount / dashboard.value.totalCount) * 100) : 0)
const workItems = computed(() => dashboard.value?.checklist.filter((item) => item.category === 'WORK') ?? [])
const lifeItems = computed(() => dashboard.value?.checklist.filter((item) => item.category === 'LIFE') ?? [])
const selectedTemplate = computed(() => templates.value.find(
  (item) => item.id === dashboard.value?.settings.documentTemplateId,
) ?? null)
const showDocument = computed(() => forceDocument.value || dashboard.value?.workday || dashboard.value?.document.state === 'CREATED')

function statusText(item: RhythmOccurrence) {
  if (item.status === 'ON_TIME') return '准时完成'
  if (item.status === 'COMPLETED_LATE') return '逾期完成'
  if (item.status === 'LATE') return '已逾期'
  if (item.status === 'UPCOMING') return `${item.startTime} 后开始`
  return `${item.dueNextDay ? '次日 ' : ''}${item.dueTime} 前完成`
}

async function load() {
  loading.value = true
  error.value = ''
  hydratingDocument.value = true
  try {
    const [result, todayReading, docTemplates] = await Promise.all([
      fetchRhythmDashboard(props.date),
      fetchTodayTianrenReading(),
      fetchDocTemplates().catch(() => []),
    ])
    dashboard.value = result
    reading.value = todayReading
    templates.value = docTemplates
    documentId.value = result.document.id
    const localDraft = localStorage.getItem(draftKey.value)
    if (localDraft != null) {
      documentBody.value = localDraft
      documentState.value = 'error'
    } else if (result.document.state === 'CREATED' && result.document.id) {
      const doc = await fetchDoc(result.document.id)
      documentBody.value = blocksToText(doc.blocks)
      documentState.value = 'saved'
    } else {
      documentBody.value = blocksToText(selectedTemplate.value?.blocks ?? [])
      documentState.value = 'idle'
    }
    emit('loaded', result)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '每日面板暂时无法读取'
  } finally {
    hydratingDocument.value = false
    loading.value = false
  }
}

async function toggle(item: RhythmOccurrence) {
  if (toggling.value) return
  toggling.value = item.id
  try {
    const saved = await setRhythmOccurrenceCompleted(item.id, !item.completedAt)
    const index = dashboard.value?.checklist.findIndex((entry) => entry.id === item.id) ?? -1
    if (dashboard.value && index >= 0) {
      dashboard.value.checklist[index] = saved
      dashboard.value.completedCount = dashboard.value.checklist.filter((entry) => entry.completedAt).length
    }
    toast(saved.completedAt ? `${saved.title}已完成` : `已撤销${saved.title}`, 'ri-check-line')
  } catch (cause) {
    toast(cause instanceof Error ? cause.message : '清单状态保存失败', 'ri-error-warning-line')
  } finally {
    toggling.value = ''
  }
}

async function restoreAutomaticWorkday() {
  if (!dashboard.value) return
  try {
    dashboard.value = await setRhythmWorkdayStatus(props.date, null)
    emit('loaded', dashboard.value)
  } catch (cause) {
    toast(cause instanceof Error ? cause.message : '自动工作日状态恢复失败', 'ri-error-warning-line')
  }
}

async function cast() {
  if (casting.value) return
  casting.value = true
  try {
    reading.value = await createTodayTianrenReading()
    await load()
    toast('今日卦象已保存', 'ri-sparkling-line')
  } catch (cause) {
    toast(cause instanceof Error ? cause.message : '今日卦象生成失败', 'ri-error-warning-line')
  } finally {
    casting.value = false
  }
}

function documentInput(value: string) {
  if (hydratingDocument.value) return
  documentBody.value = value
  documentState.value = 'dirty'
  localStorage.setItem(draftKey.value, value)
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => void saveDocument(), 900)
}

async function saveDocument() {
  if (documentState.value === 'saving' || !documentBody.value.trim()) return
  documentState.value = 'saving'
  documentError.value = ''
  try {
    if (!documentId.value) {
      const created = await createRhythmDocument(
        props.date,
        dashboard.value?.document.title ?? `${props.date} 每日工作记录`,
        textToBlocks(documentBody.value),
      )
      documentId.value = created.id
      if (dashboard.value) dashboard.value.document = {
        id: created.id,
        state: 'CREATED',
        kbId: created.kbId ?? dashboard.value.document.kbId,
        folderId: created.folderId ?? dashboard.value.document.folderId,
        title: created.title,
      }
    } else {
      await apiPatchDoc(documentId.value, { blocks: textToBlocks(documentBody.value) })
    }
    localStorage.removeItem(draftKey.value)
    documentState.value = 'saved'
  } catch (cause) {
    documentState.value = 'error'
    documentError.value = cause instanceof Error ? cause.message : '每日文档保存失败'
  }
}

async function onUploadImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) {
    toast('图片超过 10MB，已跳过', 'ri-error-warning-line')
    done(null, '')
    return
  }
  const image = await data.uploadImage(file, `每日工作记录 · ${props.date}`, { requirePersisted: true })
  done(image?.url ?? null, image?.name ?? file.name)
}

function openDocument() {
  const doc = dashboard.value?.document
  if (doc?.id && doc.kbId) router.push({ path: `/kb/${doc.kbId}`, query: { doc: doc.id } })
}

watch(() => [props.date, props.reloadKey], () => void load())
onMounted(load)
onBeforeUnmount(() => { if (saveTimer) clearTimeout(saveTimer) })
</script>

<template>
  <section class="daily-panel">
    <div v-if="loading" class="panel-state"><i class="ri-loader-4-line spin"></i>正在整理今天的必做事项…</div>
    <div v-else-if="error" class="panel-state error" role="alert">
      <i class="ri-error-warning-line"></i><span>{{ error }}</span><button @click="load">重试</button>
    </div>
    <template v-else-if="dashboard">
      <header class="panel-head">
        <div><small>DAILY MUST-DO</small><h2>每日必做清单</h2><p>{{ dashboard.workWeekLabel }} · {{ dashboard.workday ? '今天按工作日执行' : '今天是休息日' }} <button v-if="dashboard.workdayOverride !== null" class="restore-auto" @click="restoreAutomaticWorkday">恢复自动判断</button></p></div>
        <div class="completion"><b>{{ completionRate }}%</b><span>{{ dashboard.completedCount }} / {{ dashboard.totalCount }} 已完成</span></div>
      </header>
      <div v-if="!dashboard.checklist.length" class="empty-list"><i class="ri-checkbox-circle-line"></i>今天没有适用的必做项</div>
      <div v-else class="checklist-columns">
        <section v-if="workItems.length"><h3><i class="ri-briefcase-4-line"></i>工作</h3>
          <button v-for="item in workItems" :key="item.id" class="check-row" :class="[item.status.toLowerCase(), { done: item.completedAt }]" :disabled="toggling === item.id" @click="toggle(item)">
            <i :class="item.completedAt ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'"></i><span><b>{{ item.title }}</b><small>{{ statusText(item) }}<em v-if="item.occurrenceDate !== date"> · {{ item.occurrenceDate }} 的跨日事项</em></small></span><strong>{{ item.kind === 'PLAN' ? '自动联动' : item.completedAt ? '撤销' : '完成' }}</strong>
          </button>
        </section>
        <section v-if="lifeItems.length"><h3><i class="ri-heart-3-line"></i>生活</h3>
          <button v-for="item in lifeItems" :key="item.id" class="check-row" :class="[item.status.toLowerCase(), { done: item.completedAt }]" :disabled="toggling === item.id" @click="toggle(item)">
            <i :class="item.completedAt ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'"></i><span><b>{{ item.title }}</b><small>{{ statusText(item) }}</small></span><strong>{{ item.completedAt ? '撤销' : '完成' }}</strong>
          </button>
        </section>
      </div>

      <div class="module-grid">
        <article v-if="dashboard.settings.tianrenEnabled" class="module-card hexagram">
          <header><div><small>DAILY HEXAGRAM</small><h3>每日卦象</h3></div><span v-if="dashboard.settings.tianrenRequired">已纳入必做</span></header>
          <template v-if="reading"><div class="hex-result"><strong>{{ reading.primary.symbol }}</strong><span><b>{{ reading.primary.name }}卦</b><small v-if="reading.relating">之 {{ reading.relating.name }}卦 · {{ reading.changingLines.length }} 个变爻</small><small v-else>今日无变爻</small></span></div><RouterLink to="/tianren">查看完整卦象<i class="ri-arrow-right-line"></i></RouterLink></template>
          <template v-else><p>今天还没有起卦；由你主动开始，不会因为打开页面自动生成。</p><button :disabled="casting" @click="cast"><i :class="casting ? 'ri-loader-4-line spin' : 'ri-sparkling-line'"></i>{{ casting ? '正在起卦…' : '起出今日一卦' }}</button></template>
        </article>

        <article class="module-card document" :class="{ collapsed: !showDocument }">
          <header><div><small>DAILY DOCUMENT</small><h3>每日工作记录</h3></div><button v-if="documentId" class="link-button" @click="openDocument">打开正式文档<i class="ri-arrow-right-up-line"></i></button></header>
          <div v-if="dashboard.document.state === 'UNCONFIGURED'" class="doc-warning"><i class="ri-settings-4-line"></i><span>请先在右上角“调整节奏”中选择知识库和文件夹。</span></div>
          <div v-else-if="dashboard.document.state === 'TRASHED'" class="doc-warning"><i class="ri-delete-bin-line"></i><span>当天文档在回收站中，请先恢复；系统不会创建重复文档。</span><RouterLink to="/knowledge">打开知识库</RouterLink></div>
          <div v-else-if="!showDocument" class="rest-document"><p>休息日默认不展开工作记录，需要时仍可手动开始。</p><button @click="forceDocument = true">记录今天</button></div>
          <template v-else>
            <MarkdownEditor :model-value="documentBody" :min-height="180" allow-h1 polishable link-controls image-controls placeholder="记录今天的工作事项…停手后自动保存" @update:model-value="documentInput" @upload-image="onUploadImage" />
            <footer class="doc-save"><span v-if="documentState === 'saving'"><i class="ri-loader-4-line spin"></i>保存中…</span><span v-else-if="documentState === 'saved'" class="saved"><i class="ri-cloud-line"></i>已保存</span><span v-else-if="documentState === 'dirty'">草稿已留在本机</span><span v-else-if="documentState === 'error'" class="error"><i class="ri-cloud-off-line"></i>{{ documentError || '上次保存失败，草稿仍在本机' }}</span><button v-if="documentState === 'error'" @click="saveDocument">重试保存</button></footer>
          </template>
        </article>
      </div>
    </template>
  </section>
</template>

<style scoped>
.daily-panel{margin:18px 0;padding:20px;border:1px solid color-mix(in srgb,var(--wao-primary) 18%,var(--wao-line));border-radius:18px;background:linear-gradient(145deg,color-mix(in srgb,var(--wao-primary) 5%,var(--wao-bg-card)),var(--wao-bg-card));box-shadow:var(--wao-shadow-card)}
.panel-state{min-height:110px;display:flex;align-items:center;justify-content:center;gap:9px;color:var(--wao-text-weak)}.panel-state.error{color:var(--wao-danger-strong)}.panel-state button,.doc-save button{border:0;background:none;color:var(--wao-primary);cursor:pointer}.panel-head{display:flex;justify-content:space-between;gap:18px;align-items:flex-start}.panel-head small,.module-card header small{font-size:9px;letter-spacing:.18em;color:var(--wao-primary)}.panel-head h2,.module-card h3{margin:4px 0}.panel-head p{margin:0;color:var(--wao-text-weak);font-size:11px}.completion{text-align:right}.completion b{display:block;font-size:25px;color:var(--wao-primary)}.completion span{font-size:10px;color:var(--wao-text-weak)}
.checklist-columns{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px;margin-top:18px}.checklist-columns h3{margin:0 0 8px;font-size:12px;color:var(--wao-text-2)}.checklist-columns h3 i{margin-right:6px}.check-row{width:100%;display:grid;grid-template-columns:23px minmax(0,1fr) auto;gap:8px;align-items:center;margin-top:7px;padding:10px 11px;border:1px solid var(--wao-line);border-radius:11px;background:var(--wao-bg-card);color:inherit;text-align:left;cursor:pointer}.check-row>i{font-size:19px;color:var(--wao-text-faint)}.check-row span{display:grid;gap:2px;min-width:0}.check-row b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}.check-row small{color:var(--wao-text-weak);font-size:9.5px}.check-row em{font-style:normal}.check-row strong{font-size:9.5px;color:var(--wao-primary)}.check-row.late{border-color:color-mix(in srgb,var(--wao-danger) 42%,var(--wao-line));background:color-mix(in srgb,var(--wao-danger) 5%,var(--wao-bg-card))}.check-row.late small{color:var(--wao-danger-strong)}.check-row.done{opacity:.72}.check-row.done>i{color:#2f9674}.empty-list{margin-top:16px;padding:20px;border:1px dashed var(--wao-line);border-radius:12px;text-align:center;color:var(--wao-text-weak)}
.module-grid{display:grid;grid-template-columns:minmax(240px,.68fr) minmax(0,1.7fr);gap:14px;margin-top:18px}.module-card{min-width:0;padding:15px;border:1px solid var(--wao-line);border-radius:14px;background:var(--wao-bg-card)}.module-card>header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.module-card header span{font-size:9px;color:#97651f}.module-card p{color:var(--wao-text-weak);font-size:11px;line-height:1.6}.module-card button,.module-card a{display:inline-flex;align-items:center;gap:5px;border:0;background:none;color:var(--wao-primary);font-size:10.5px;cursor:pointer;text-decoration:none}.hex-result{display:flex;align-items:center;gap:12px;margin:13px 0}.hex-result>strong{font-size:48px;color:#8e6534}.hex-result span{display:grid;gap:4px}.hex-result b{font-family:'STKaiti','KaiTi',serif;font-size:18px}.hex-result small{font-size:9.5px;color:var(--wao-text-weak)}.doc-warning,.rest-document{min-height:110px;display:flex;align-items:center;justify-content:center;gap:8px;color:var(--wao-text-weak);font-size:11px}.doc-save{min-height:26px;display:flex;justify-content:flex-end;align-items:center;gap:8px;color:var(--wao-text-weak);font-size:9.5px}.doc-save .saved{color:#267c65}.doc-save .error{color:var(--wao-danger-strong)}
@media(max-width:760px){.daily-panel{padding:15px}.checklist-columns,.module-grid{grid-template-columns:1fr}.panel-head{align-items:center}.completion b{font-size:20px}.check-row{grid-template-columns:22px minmax(0,1fr)}.check-row>strong{display:none}}
</style>
