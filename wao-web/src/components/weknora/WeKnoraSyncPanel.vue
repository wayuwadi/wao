<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  getSyncOverview,
  getSyncProgress,
  saveSyncSettings,
  runSync,
  getSyncSource,
  type SyncSettings,
  type SyncModule,
  type SyncRun,
  type SyncItem,
  type SyncSource,
} from '@/api/weknoraSync'

const route = useRoute()
const form = reactive<SyncSettings>({
  kbId: '',
  modules: [],
  batchLimit: 20,
  syncDeletes: true,
  enabled: true,
  time: '02:00',
  revision: 0,
})
const apiKey = ref('')
const catalog = ref<SyncModule[]>([]),
  runs = ref<SyncRun[]>([]),
  items = ref<SyncItem[]>([])
const configured = ref(false),
  hasKey = ref(false),
  loading = ref(true),
  saving = ref(false),
  triggering = ref(false),
  ready = ref(false)
const error = ref(''),
  notice = ref(''),
  savedSignature = ref(''),
  pollError = ref(''),
  source = ref<SyncSource | null>(null),
  sourceError = ref('')
const signature = () => JSON.stringify(form)
const dirty = computed(
  () => ready.value && (signature() !== savedSignature.value || apiKey.value.length > 0),
)
const active = computed(() => runs.value.some((r) => ['queued', 'running'].includes(r.status)))
const selectedCount = computed(() =>
  catalog.value.filter((m) => form.modules.includes(m.id)).reduce((n, m) => n + m.count, 0),
)
const latest = computed(() => runs.value[0])
let draftKey = 'wao-weknora-sync-draft-v1'
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
const stateLabel = (s: string) =>
  ({
    queued: '等待执行',
    running: '同步中',
    completed: '本轮完成',
    partial: '部分失败',
    failed: '失败',
    synced: '已传输',
    pending: '待处理',
    creating: '正在创建',
    updating: '正在更新',
    uncertain: '待核对',
  })[s] || s
const timeLabel = (s: string | null) =>
  s ? new Date(s).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'
function persistDraft() {
  if (!ready.value) return
  try {
    if (dirty.value) localStorage.setItem(draftKey, JSON.stringify(form))
    else localStorage.removeItem(draftKey)
  } catch {
    notice.value = '浏览器无法保存草稿，请在离开前保存设置'
  }
}
watch(form, persistDraft, { deep: true })
async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await getSyncOverview()
    draftKey = 'wao-weknora-sync-draft-v1:' + data.ownerId
    catalog.value = data.catalog
    runs.value = data.runs
    items.value = data.items
    configured.value = data.configured
    hasKey.value = !!data.settings.hasKey
    ready.value = false
    Object.assign(form, {
      ...form,
      ...data.settings,
      modules: data.settings.modules ?? data.catalog.map((m) => m.id),
    })
    delete form.hasKey
    delete form.nextFireAt
    savedSignature.value = signature()
    try {
      const raw = localStorage.getItem(draftKey)
      if (raw) {
        const draft = JSON.parse(raw)
        if (draft.revision === form.revision) {
          for (const key of [
            'kbId',
            'modules',
            'batchLimit',
            'syncDeletes',
            'enabled',
            'time',
            'revision',
          ] as const) {
            if (key in draft) Object.assign(form, { [key]: draft[key] })
          }
          notice.value = '已恢复未保存的配置草稿；API Key 不保存在浏览器中'
        } else notice.value = '服务端配置已更新，旧草稿未自动覆盖，请核对设置'
      }
    } catch {
      notice.value = '草稿无法读取，已加载服务端设置'
    }
    ready.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : '读取失败，请重试'
  } finally {
    loading.value = false
  }
}
async function save() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    await saveSyncSettings({ ...form, apiKey: apiKey.value })
    apiKey.value = ''
    localStorage.removeItem(draftKey)
    ready.value = false
    await load()
    notice.value = '设置已保存，连接已验证'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败，草稿已保留'
  } finally {
    saving.value = false
  }
}
async function trigger() {
  triggering.value = true
  error.value = ''
  try {
    await runSync()
    notice.value = '同步已入队，可以离开此页；进度会自动刷新'
    await poll(false)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '触发失败，请重试'
  } finally {
    triggering.value = false
  }
}
async function poll(schedule = true) {
  if (disposed) return
  if (configured.value) {
    try {
      const data = await getSyncProgress()
      runs.value = data.runs
      items.value = data.items
      pollError.value = ''
    } catch (e) {
      if (
        (e as { response?: { status?: number } })?.response?.status === 401 ||
        (e as { response?: { status?: number } })?.response?.status === 403
      ) {
        ready.value = false
        configured.value = false
        catalog.value = []
        runs.value = []
        items.value = []
        source.value = null
        apiKey.value = ''
      }
      pollError.value = '进度刷新失败，正在重试；配置草稿不受影响'
    }
  }
  if (schedule && !disposed) timer = setTimeout(() => void poll(), 5000)
}
async function showSource(key: string) {
  source.value = null
  sourceError.value = ''
  try {
    source.value = await getSyncSource(key)
  } catch (e) {
    sourceError.value = e instanceof Error ? e.message : '来源读取失败'
  }
}
function closeSource() {
  source.value = null
  sourceError.value = ''
}
function toggleAll() {
  form.modules = form.modules.length === catalog.value.length ? [] : catalog.value.map((m) => m.id)
}
function beforeUnload(e: BeforeUnloadEvent) {
  if (dirty.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}
watch(
  () => route.query.source,
  (key) => {
    if (typeof key === 'string') void showSource(key)
  },
  { immediate: true },
)
onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload)
  await load()
  void poll()
})
onUnmounted(() => {
  disposed = true
  if (timer) clearTimeout(timer)
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>
<template>
  <main class="sync-panel">
    <header class="sync-header">
      <div>
        <span class="eyebrow">WAO → WEKNORA</span>
        <h1>内容同步</h1>
        <p>在 WAO 维护原始内容，让知识库按计划接收更新。</p>
      </div>
      <button
        class="primary"
        :disabled="!configured || dirty || active || triggering || loading"
        @click="trigger"
      >
        <i class="ri-refresh-line"></i
        >{{ active ? '同步执行中' : triggering ? '正在入队' : '立即同步' }}
      </button>
    </header>
    <p v-if="error" role="alert" class="alert error">
      {{ error }} <button v-if="!ready" @click="load">重新加载</button>
    </p>
    <p v-if="notice" role="status" class="alert">{{ notice }}</p>
    <p v-if="pollError" role="status" class="alert error">{{ pollError }}</p>
    <div v-if="loading && !ready" class="empty">正在读取同步配置与内容数量…</div>
    <template v-if="ready">
      <div class="stats">
        <article>
          <span>已选来源记录</span><strong>{{ selectedCount.toLocaleString() }}</strong
          ><small>长正文会拆分成多篇</small>
        </article>
        <article>
          <span>最近一轮传输</span><strong>{{ latest?.uploaded ?? 0 }}</strong
          ><small>{{ latest ? stateLabel(latest.status) : '尚未运行' }}</small>
        </article>
        <article>
          <span>待下轮处理</span><strong>{{ latest?.remaining ?? '—' }}</strong
          ><small>未运行时数量待核对</small>
        </article>
        <article>
          <span>夜间计划</span><strong>{{ form.enabled ? form.time : '已暂停' }}</strong
          ><small>北京时间 · {{ dirty ? '未保存修改' : '已保存的配置' }}</small>
        </article>
      </div>
      <div class="columns">
        <section class="card">
          <h2>连接与计划</h2>
          <p class="muted">
            目标使用独立的「WAO 同步知识库」。WeKnora API Key 与模型 API Key 是两种不同凭据。
          </p>
          <fieldset :disabled="saving || active">
            <label class="field"
              >目标知识库 ID<input
                v-model.trim="form.kbId"
                placeholder="WeKnora 知识库的 UUID"
                autocomplete="off"
            /></label>
            <label class="field"
              >WeKnora API Key<input
                v-model="apiKey"
                type="password"
                :placeholder="
                  hasKey
                    ? '已加密保存；留空保持现有 Key'
                    : '填写专用 Key，仅授予目标库读取和写入权限'
                "
                autocomplete="new-password"
            /></label>
            <div class="form-row">
              <label class="field"
                >夜间时间（北京时间）<input v-model="form.time" type="time" /></label
              ><label class="field"
                >每轮最多变化篇数<input
                  v-model.number="form.batchLimit"
                  type="number"
                  min="1"
                  max="100"
              /></label>
            </div>
            <label class="check"
              ><input v-model="form.enabled" type="checkbox" />启用每天夜间自动同步</label
            >
            <label class="check"
              ><input
                v-model="form.syncDeletes"
                type="checkbox"
              />同步删除及取消范围（只移除本集成创建的副本）</label
            >
          </fieldset>
          <div class="save-row">
            <span>{{ saving ? '正在验证并保存…' : dirty ? '有未保存的修改' : '设置已保存' }}</span
            ><button
              class="primary"
              :disabled="saving || active || !form.modules.length"
              @click="save"
            >
              {{ saving ? '保存中…' : '保存并验证连接' }}
            </button>
          </div>
          <p v-if="active" class="muted">本轮使用已保存的范围。完成后可修改设置。</p>
        </section>
        <section class="card scope">
          <div class="section-title">
            <h2>同步范围</h2>
            <button :disabled="saving || active" @click="toggleAll">
              {{ form.modules.length === catalog.length ? '取消全选' : '全选' }}
            </button>
          </div>
          <p class="muted">私有记录仅属于当前配置者。凭据表、其他成员私有内容不会导出。</p>
          <div class="module-list">
            <label v-for="module in catalog" :key="module.id" class="module"
              ><input
                v-model="form.modules"
                type="checkbox"
                :value="module.id"
                :disabled="saving || active"
              /><span
                ><b>{{ module.label }}</b
                ><small>{{ module.scope }}</small></span
              ><em>{{ module.count.toLocaleString() }}</em></label
            >
          </div>
        </section>
      </div>
      <aside class="note">
        <b>当前为无需 Embedding 的 Wiki 模式</b>
        <p>
          已传输表示文档已保存到 WeKnora；Wiki 由模型异步生成，仍可能消耗模型 API
          额度。问答效果与模型兼容性需要单独验证。附件保留正文引用与名称，图片、音视频和文件二进制尚不自动解析；密码、API
          Key、原始运行日志及内部状态不纳入同步。
        </p>
      </aside>
      <section class="card">
        <div class="section-title">
          <h2>运行记录</h2>
          <a href="/automation">查看定时任务 ↗</a>
        </div>
        <p v-if="!runs.length" class="empty">
          还没有运行记录。保存配置后，可手动同步或等待夜间计划。
        </p>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>开始时间（北京时间）</th>
                <th>方式</th>
                <th>状态</th>
                <th>传输 / 未变化 / 移除</th>
                <th>待处理 / 传输失败</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="run in runs" :key="run.id">
                <td>{{ timeLabel(run.created_at) }}</td>
                <td>{{ run.trigger_type === 'manual' ? '手动' : '夜间' }}</td>
                <td>
                  <span class="badge" :class="run.status">{{ stateLabel(run.status) }}</span>
                </td>
                <td>{{ run.uploaded }} / {{ run.skipped }} / {{ run.removed }}</td>
                <td>{{ run.remaining }} / {{ run.failed }}</td>
                <td class="message">{{ run.message || '等待执行器接手' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <section class="card">
        <div class="section-title">
          <h2>最近处理的内容</h2>
          <a
            v-if="configured"
            :href="`/weknora/platform/knowledge-bases/${form.kbId}`"
            target="_blank"
            rel="noopener"
            >查看 Wiki 处理状态 ↗</a
          >
        </div>
        <p class="muted">
          最多显示最近 100
          条。失败记录会在后续同步中重试；创建结果不确定时先核对远端，避免重复生成。
        </p>
        <p v-if="!items.length" class="empty">首次同步后，这里会显示真实来源与结果。</p>
        <div v-else class="item-list">
          <article v-for="item in items" :key="item.source_key">
            <button class="source-link" @click="showSource(item.source_key)">
              {{ item.source_title.replace(/ \[wao-sync:.*\]$/, '') }}</button
            ><span class="badge" :class="item.status">{{ stateLabel(item.status) }}</span
            ><small v-if="item.parse_status" class="muted"
              >上次核对：解析 {{ stateLabel(item.parse_status) }} / 摘要
              {{ stateLabel(item.summary_status) }}</small
            >
            <p v-if="item.error_message" class="item-error">{{ item.error_message }}</p>
          </article>
        </div>
      </section>
    </template>
    <div
      v-if="source || sourceError"
      class="source-overlay"
      @click.self="closeSource"
    >
      <section class="source-dialog" role="dialog" aria-modal="true" aria-label="WAO 来源内容">
        <button
          class="close"
          @click="closeSource"
        >
          关闭
        </button>
        <h2>WAO 当前来源</h2>
        <p v-if="sourceError" role="alert">{{ sourceError }}</p>
        <template v-if="source"
          ><p class="muted">
            来源标识：{{ source.key }} · 此处展示当前内容，可能新于上次同步副本。
          </p>
          <pre>{{ source.content }}</pre>
        </template>
      </section>
    </div>
  </main>
</template>
<style scoped>
.sync-panel {
  padding: 28px;
  max-width: 1500px;
  margin: auto;
  color: var(--wao-text);
  flex: 1;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
  overflow: auto;
  background: var(--wao-bg-page);
}
.sync-header,
.section-title,
.save-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.sync-header {
  margin-bottom: 24px;
}
.eyebrow {
  font-size: 11px;
  letter-spacing: 2px;
  color: var(--wao-primary);
  font-weight: 700;
}
h1 {
  font-size: 26px;
  margin: 6px 0;
}
h2 {
  font-size: 16px;
  margin: 0 0 14px;
}
p {
  line-height: 1.7;
}
.sync-header p,
.muted {
  font-size: 12px;
  color: var(--wao-text-weak);
  margin: 6px 0 16px;
}
button,
a {
  font: inherit;
}
button {
  cursor: pointer;
  border: 1px solid var(--wao-line);
  border-radius: 7px;
  padding: 8px 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.primary {
  background: var(--wao-primary);
  color: white;
  border-color: transparent;
  font-size: 13px;
  white-space: nowrap;
}
.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}
.stats article,
.card {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 20px;
}
.stats span,
.stats small {
  display: block;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.stats strong {
  display: block;
  font-size: 28px;
  margin: 9px 0;
}
.columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.card {
  margin-bottom: 20px;
  min-width: 0;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
  font-size: 12px;
  margin-bottom: 16px;
}
input:not([type='checkbox']) {
  width: 100%;
  box-sizing: border-box;
  padding: 9px 10px;
  border: 1px solid var(--wao-line);
  border-radius: 6px;
  background: var(--wao-bg-page);
  color: var(--wao-text);
}
input[type='checkbox'] {
  accent-color: var(--wao-primary);
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.check {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  margin: 14px 0;
  line-height: 1.5;
}
fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.save-row {
  border-top: 1px solid var(--wao-line);
  padding-top: 16px;
  margin-top: 20px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.module-list {
  height: 305px;
  overflow: auto;
  padding-right: 6px;
}
.module {
  display: flex;
  gap: 10px;
  align-items: center;
  border-bottom: 1px solid var(--wao-line);
  padding: 10px 2px;
}
.module span {
  flex: 1;
}
.module b {
  font-size: 12px;
  font-weight: 500;
}
.module small {
  display: block;
  font-size: 10px;
  color: var(--wao-text-weak);
  margin-top: 3px;
}
.module em {
  font-size: 12px;
  font-style: normal;
}
.note {
  background: var(--wao-bg-sub);
  border-left: 3px solid var(--wao-primary);
  padding: 14px 18px;
  border-radius: 6px;
  margin: 0 0 20px;
  font-size: 12px;
}
.note p {
  margin: 7px 0 0;
  color: var(--wao-text-weak);
}
.alert {
  border: 1px solid var(--wao-line);
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  font-size: 13px;
}
.error,
.item-error {
  color: #c64c4c;
}
.empty {
  padding: 30px;
  text-align: center;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.table-wrap {
  overflow: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  text-align: left;
}
th {
  font-weight: 500;
  color: var(--wao-text-weak);
}
td,
th {
  padding: 12px 10px;
  border-bottom: 1px solid var(--wao-line);
  white-space: nowrap;
}
td.message {
  white-space: normal;
  min-width: 200px;
  max-width: 450px;
}
.badge {
  display: inline-block;
  font-size: 11px;
  padding: 3px 7px;
  border-radius: 5px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  white-space: nowrap;
}
.badge.synced,
.badge.completed {
  background: #e5f4ec;
  color: #278158;
}
.badge.failed,
.badge.partial,
.badge.uncertain {
  background: #fff0e5;
  color: #ae651a;
}
.badge.running,
.badge.queued {
  background: #eeebff;
  color: #6754c7;
}
.section-title a {
  font-size: 12px;
  color: var(--wao-primary);
  text-decoration: none;
}
.item-list {
  max-height: 400px;
  overflow: auto;
}
.item-list article {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid var(--wao-line);
}
.source-link {
  flex: 1;
  text-align: left;
  border: 0;
  background: none;
  padding: 0;
  font-size: 12px;
  min-width: 180px;
  overflow-wrap: anywhere;
  color: var(--wao-primary);
}
.item-error {
  font-size: 12px;
  flex-basis: 100%;
  margin: 0;
}
.source-overlay {
  position: fixed;
  inset: 0;
  z-index: 1200;
  background: #0006;
  display: grid;
  place-items: center;
  padding: 24px;
}
.source-dialog {
  position: relative;
  background: var(--wao-bg-card);
  border-radius: 14px;
  padding: 26px;
  width: min(850px, 90vw);
  max-height: 85vh;
  overflow: auto;
}
.source-dialog pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.8;
  font-family: inherit;
  font-size: 13px;
}
.close {
  float: right;
}
@media (max-width: 850px) {
  .sync-panel {
    padding: 16px;
  }
  .columns {
    grid-template-columns: 1fr;
  }
  .stats {
    grid-template-columns: repeat(2, 1fr);
  }
  .sync-header {
    align-items: flex-start;
  }
  .stats article,
  .card {
    padding: 16px;
  }
}
</style>
