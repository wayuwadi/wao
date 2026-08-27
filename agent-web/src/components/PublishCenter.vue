<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  createPublishTask, getAuthorizeUrl, getPublishCenter, submitPublishTask,
  listMedia, unbindAccount, updatePublishTask, uploadMedia,
} from '@/api/publish'
import {
  canSubmitPublish, formatBytes, isPublishEditable, isPublishInFlight, publishStatusLabel,
} from '@/publishStatus'
import type { MediaAsset, PlatformSummary, PublishCenter, PublishTask } from '@/types'

type CenterMode = 'full' | 'accounts' | 'content' | 'publishing'

const props = withDefaults(defineProps<{ initialTopic?: string; mode?: CenterMode }>(), { mode: 'full' })
const emit = defineEmits<{ 'topic-consumed': []; 'dirty-change': [dirty: boolean] }>()
const center = ref<PublishCenter | null>(null)
const mediaAssets = ref<MediaAsset[]>([])
const selectedId = ref('')
const loading = ref(true)
const busy = ref('')
const message = ref('')
const error = ref('')
const dirty = ref(false)
const createForm = reactive({ topic: '', platform: 'DOUYIN', generateCopy: true })
const editForm = reactive({ version: 0, title: '', content: '', tags: '', platformAccountId: '', mediaAssetId: '' })
let pollTimer: number | undefined

const selected = computed(() => center.value?.tasks.find((task) => task.id === selectedId.value) ?? null)
const selectedPlatform = computed(() => platform(selected.value?.platform))
const editable = computed(() => !!selected.value && isPublishEditable(selected.value.status))
const canPublish = computed(() => !!selected.value && !!selectedPlatform.value
  && canSubmitPublish(selected.value.status, selectedPlatform.value.publishSupported))
const showAccounts = computed(() => props.mode === 'full' || props.mode === 'accounts')
const showCreate = computed(() => props.mode === 'full' || props.mode === 'content')
const showWorkspace = computed(() => props.mode === 'full' || props.mode === 'content' || props.mode === 'publishing')
const showPublishControls = computed(() => props.mode === 'full' || props.mode === 'publishing')
const loadingLabel = computed(() => ({
  accounts: '正在读取真实账号状态…',
  content: '正在读取真实内容…',
  publishing: '正在读取真实发布状态…',
  full: '正在读取真实发布状态…',
})[props.mode])

watch(() => props.initialTopic, (topic) => {
  if (topic) createForm.topic = topic
}, { immediate: true })
watch(dirty, (value) => emit('dirty-change', value))

function platform(code?: string): PlatformSummary | undefined {
  return center.value?.platforms.find((item) => item.platform === code)
}

function selectTask(task: PublishTask, force = false) {
  if (dirty.value && !force && !window.confirm('当前编辑内容还没保存，仍要切换任务吗？')) return
  selectedId.value = task.id
  editForm.version = task.version
  editForm.title = task.title ?? ''
  editForm.content = task.content ?? ''
  editForm.tags = task.tags.join(' ')
  editForm.platformAccountId = task.platformAccountId ?? ''
  editForm.mediaAssetId = task.mediaAssetId ?? ''
  dirty.value = false
}

async function refresh(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  try {
    center.value = await getPublishCenter()
    if (showWorkspace.value) mediaAssets.value = await listMedia()
    const current = center.value.tasks.find((task) => task.id === selectedId.value)
    if (!dirty.value && current) selectTask(current, true)
    else if (!selectedId.value && center.value.tasks[0]) selectTask(center.value.tasks[0], true)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '发布中心读取失败'
  } finally {
    loading.value = false
  }
}

async function createTask() {
  if (!createForm.topic.trim()) return
  busy.value = 'create'
  message.value = ''
  error.value = ''
  try {
    const created = await createPublishTask(createForm.topic, createForm.platform,
      createForm.generateCopy && !!center.value?.ai.available)
    await refresh(true)
    const current = center.value?.tasks.find((task) => task.id === created.id) ?? created
    selectTask(current, true)
    createForm.topic = ''
    emit('topic-consumed')
    message.value = created.status === 'GENERATING' ? '已开始生成文案' : '已创建草稿，请人工完善后保存'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '创建发布任务失败'
  } finally {
    busy.value = ''
  }
}

function markDirty() {
  dirty.value = true
  message.value = '有未保存修改'
}

async function saveTask() {
  const task = selected.value
  if (!task || !editable.value) return
  busy.value = 'save'
  error.value = ''
  try {
    const saved = await updatePublishTask(task.id, {
      version: editForm.version,
      title: editForm.title,
      content: editForm.content,
      tags: editForm.tags.split(/[\s,，#]+/).filter(Boolean),
      platformAccountId: editForm.platformAccountId,
      mediaAssetId: editForm.mediaAssetId,
    })
    await refresh(true)
    selectTask(center.value?.tasks.find((item) => item.id === saved.id) ?? saved, true)
    message.value = `已保存 · v${saved.version}`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
  } finally {
    busy.value = ''
  }
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  busy.value = 'upload'
  error.value = ''
  try {
    const asset = await uploadMedia(file)
    mediaAssets.value = [asset, ...mediaAssets.value.filter((item) => item.id !== asset.id)]
    editForm.mediaAssetId = asset.id
    dirty.value = true
    message.value = `素材已上传：${asset.originalName}（${formatBytes(asset.byteSize)}），请保存任务`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '素材上传失败'
  } finally {
    busy.value = ''
    input.value = ''
  }
}

async function submitTask() {
  const task = selected.value
  if (!task || !canPublish.value || dirty.value) return
  if (!window.confirm(`确认把「${task.title || task.topic}」提交到${task.platformLabel}吗？`)) return
  busy.value = 'submit'
  error.value = ''
  try {
    await submitPublishTask(task.id)
    await refresh(true)
    message.value = '已提交官方平台发布，状态会自动刷新'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '提交发布失败'
  } finally {
    busy.value = ''
  }
}

async function authorize(item: PlatformSummary) {
  busy.value = `auth-${item.platform}`
  error.value = ''
  try {
    const { authorizeUrl } = await getAuthorizeUrl(item.platform)
    window.open(authorizeUrl, '_blank', 'noopener,noreferrer')
    message.value = `请在新窗口完成${item.label}授权，完成后点刷新`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '授权入口暂不可用'
  } finally {
    busy.value = ''
  }
}

async function unbind(id: string) {
  if (!window.confirm('确认解绑这个平台账号吗？已有发布记录不会删除。')) return
  busy.value = `unbind-${id}`
  try {
    await unbindAccount(id)
    await refresh(true)
    message.value = '平台账号已解绑'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '解绑失败'
  } finally {
    busy.value = ''
  }
}

onMounted(async () => {
  await refresh()
  if (showWorkspace.value) {
    pollTimer = window.setInterval(() => {
      if (center.value?.tasks.some((task) => isPublishInFlight(task.status))) void refresh(true)
    }, 3000)
  }
})
function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
}

onMounted(() => window.addEventListener('beforeunload', warnBeforeUnload))
onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  window.removeEventListener('beforeunload', warnBeforeUnload)
})
</script>

<template>
  <div class="publish-center">
    <div v-if="message" class="notice"><i class="ri-information-line"></i>{{ message }}</div>
    <div v-if="error" class="error" role="alert"><i class="ri-error-warning-line"></i><span>{{ error }}</span><button @click="refresh()">重试</button></div>
    <div v-if="loading" class="loading"><span></span>{{ loadingLabel }}</div>

    <template v-else-if="center">
      <section v-if="showAccounts" class="capabilities">
        <article v-if="mode === 'full'" class="ai-card" :class="{ unavailable: !center.ai.available }">
          <i class="ri-sparkling-2-line"></i><div><strong>AI 文案</strong><span>{{ center.ai.available ? `${center.ai.provider} · ${center.ai.model}` : '尚未配置模型，只能手工写稿' }}</span></div>
        </article>
        <article v-for="item in center.platforms" :key="item.platform" class="platform-card">
          <div><strong>{{ item.label }}</strong><span>{{ item.note }}</span></div>
          <div class="accounts">
            <span v-for="account in item.accounts" :key="account.id">{{ account.displayName }} · {{ account.status }}<button @click="unbind(account.id)">解绑</button></span>
            <button v-if="item.oauthSupported" class="link" :disabled="busy === `auth-${item.platform}`" @click="authorize(item)">连接账号</button>
          </div>
        </article>
      </section>

      <section v-if="showCreate" class="create-card">
        <div><strong>新建内容</strong><span>{{ center.ai.available ? `${center.ai.provider} · ${center.ai.model} 可生成初稿，仍需人工审核。` : '尚未配置模型，创建后可手工写稿。' }}</span></div>
        <input v-model="createForm.topic" maxlength="200" placeholder="这次想创作什么？" @keyup.enter="createTask" />
        <select v-model="createForm.platform"><option v-for="item in center.platforms" :key="item.platform" :value="item.platform">{{ item.label }}</option></select>
        <label><input v-model="createForm.generateCopy" type="checkbox" :disabled="!center.ai.available" /> AI 生成</label>
        <button :disabled="busy === 'create' || !createForm.topic.trim()" @click="createTask">{{ busy === 'create' ? '创建中…' : '开始创作' }}</button>
      </section>

      <div v-if="showWorkspace" class="workspace">
        <aside class="task-list">
          <div class="panel-head"><strong>{{ mode === 'publishing' ? '待发布与历史内容' : '最近内容' }}</strong><button @click="refresh()"><i class="ri-refresh-line"></i></button></div>
          <button v-for="task in center.tasks" :key="task.id" class="task" :class="{ active: selectedId === task.id }" @click="selectTask(task)">
            <span><strong>{{ task.title || task.topic }}</strong><small>{{ task.platformLabel }} · {{ publishStatusLabel(task.status) }}</small></span><em>{{ task.progress }}%</em>
          </button>
          <div v-if="!center.tasks.length" class="empty">{{ mode === 'publishing' ? '还没有可发布的内容，请先到内容管理创建。' : '还没有内容，从上方创建第一条。' }}</div>
        </aside>

        <main v-if="selected" class="editor">
          <div class="editor-head">
            <div><strong>{{ selected.platformLabel }}{{ mode === 'publishing' ? '发布稿' : '内容稿' }}</strong><span :class="`status ${selected.status.toLowerCase()}`">{{ publishStatusLabel(selected.status) }}</span></div>
            <small>v{{ selected.version }} · {{ dirty ? '有未保存修改' : '已与服务端同步' }}</small>
          </div>
          <div v-if="selected.errorMessage" class="task-error">{{ selected.errorMessage }}</div>
          <label>标题<input v-model="editForm.title" maxlength="200" :disabled="!editable" @input="markDirty" /></label>
          <label>正文<textarea v-model="editForm.content" rows="10" :disabled="!editable" @input="markDirty"></textarea></label>
          <label>标签<input v-model="editForm.tags" placeholder="用空格分隔" :disabled="!editable" @input="markDirty" /></label>
          <div class="editor-grid">
            <label v-if="showPublishControls">发布账号<select v-model="editForm.platformAccountId" :disabled="!editable" @change="markDirty"><option value="">暂不选择</option><option v-for="account in selectedPlatform?.accounts" :key="account.id" :value="account.id">{{ account.displayName }} · {{ account.status }}</option></select></label>
            <label>素材库<select v-model="editForm.mediaAssetId" :disabled="!editable" @change="markDirty"><option value="">暂不选择</option><option v-for="asset in mediaAssets" :key="asset.id" :value="asset.id">{{ asset.originalName }} · {{ formatBytes(asset.byteSize) }}</option></select></label>
            <label>上传新素材<span class="file"><input type="file" :disabled="!editable || busy === 'upload'" @change="upload" />{{ busy === 'upload' ? '上传中…' : '选择图片或视频' }}</span></label>
          </div>
          <div class="actions">
            <button class="secondary" :disabled="!editable || !dirty || busy === 'save'" @click="saveTask">{{ busy === 'save' ? '保存中…' : '保存审核稿' }}</button>
            <template v-if="showPublishControls">
              <button v-if="selectedPlatform?.publishSupported" :disabled="!canPublish || dirty || busy === 'submit'" @click="submitTask">{{ busy === 'submit' ? '提交中…' : `确认发布到${selected.platformLabel}` }}</button>
              <span v-else>{{ selectedPlatform?.note }}</span>
            </template>
          </div>
        </main>
        <main v-else class="editor empty">选择一条任务查看成稿；创建后也会自动打开。</main>
      </div>
    </template>
  </div>
</template>

<style scoped>
.publish-center{display:grid;gap:14px}.notice,.error{padding:10px 12px;display:flex;align-items:center;gap:8px;border-radius:9px;font-size:12px}.notice{color:var(--wao-primary-text);background:var(--wao-primary-soft);border:1px solid var(--wao-primary-line)}.error{color:var(--wao-danger-strong);background:var(--wao-danger-bg);border:1px solid var(--wao-danger-line)}.error span{flex:1}.error button{border:0;background:transparent;color:inherit;cursor:pointer}.loading{min-height:260px;display:grid;place-items:center;align-content:center;gap:10px;color:var(--wao-text-weak)}.loading span{width:28px;height:28px;border:3px solid var(--wao-primary-line);border-top-color:var(--wao-primary);border-radius:50%;animation:spin .8s linear infinite}.capabilities{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:10px}.capabilities article{padding:13px;display:flex;gap:10px;border:1px solid var(--wao-line);border-radius:11px;background:var(--wao-bg-card)}.capabilities article>div{display:grid;gap:4px}.capabilities strong{font-size:12px}.capabilities span{color:var(--wao-text-weak);font-size:10px;line-height:1.5}.ai-card>i{font-size:22px;color:var(--wao-primary-text)}.ai-card.unavailable{opacity:.72}.platform-card{flex-direction:column}.accounts{display:flex!important;flex-wrap:wrap;gap:5px}.accounts>span{padding:4px 6px;border-radius:6px;background:var(--wao-bg-sub)}.accounts button{margin-left:4px;border:0;background:transparent;color:var(--wao-danger-strong);cursor:pointer}.accounts .link{margin:0;color:var(--wao-primary-text)}.create-card{padding:14px;display:grid;grid-template-columns:minmax(180px,1fr) minmax(220px,1.5fr) 120px auto auto;align-items:center;gap:9px;border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card)}.create-card>div{display:grid;gap:3px}.create-card strong{font-size:13px}.create-card span{color:var(--wao-text-weak);font-size:9.5px;line-height:1.4}.create-card input,.create-card select,.editor input,.editor select,.editor textarea{width:100%;padding:9px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text);font:inherit}.create-card>label{display:flex;align-items:center;gap:5px;font-size:11px;white-space:nowrap}.create-card>label input{width:auto}.create-card>button,.actions>button{min-height:36px;padding:0 13px;border:0;border-radius:8px;background:var(--wao-primary);color:#fff;cursor:pointer;font-weight:650}.create-card button:disabled,.actions button:disabled{opacity:.45;cursor:not-allowed}.workspace{display:grid;grid-template-columns:280px minmax(0,1fr);gap:12px;align-items:start}.task-list,.editor{border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card)}.panel-head{padding:11px 12px;display:flex;justify-content:space-between;border-bottom:1px solid var(--wao-divider)}.panel-head button{border:0;background:transparent;color:var(--wao-text-weak);cursor:pointer}.task{width:100%;padding:10px 11px;display:flex;align-items:center;gap:8px;border:0;border-bottom:1px solid var(--wao-divider);background:transparent;color:var(--wao-text);cursor:pointer;text-align:left}.task.active{background:var(--wao-primary-soft)}.task>span{min-width:0;flex:1;display:grid;gap:3px}.task strong{overflow:hidden;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.task small{color:var(--wao-text-weak);font-size:9.5px}.task em{color:var(--wao-primary-text);font-size:10px;font-style:normal}.empty{padding:28px;color:var(--wao-text-weak);font-size:11px;text-align:center}.editor{padding:15px;display:grid;gap:12px}.editor-head{display:flex;justify-content:space-between;gap:10px}.editor-head>div{display:flex;align-items:center;gap:8px}.editor-head small{color:var(--wao-text-weak);font-size:10px}.status{padding:3px 6px;border-radius:999px;background:var(--wao-primary-soft);color:var(--wao-primary-text);font-size:9px}.status.published{background:var(--wao-success-bg);color:var(--wao-success-strong)}.status.failed{background:var(--wao-danger-bg);color:var(--wao-danger-strong)}.task-error{padding:8px 10px;color:var(--wao-danger-strong);background:var(--wao-danger-bg);border-radius:7px;font-size:11px}.editor label{display:grid;gap:5px;color:var(--wao-text-3);font-size:10px}.editor textarea{resize:vertical;line-height:1.55}.editor-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}.file{min-height:36px;padding:8px;position:relative;overflow:hidden;border:1px dashed var(--wao-line-control);border-radius:8px;color:var(--wao-text-weak);cursor:pointer}.file input{position:absolute;inset:0;opacity:0;cursor:pointer}.actions{display:flex;align-items:center;justify-content:flex-end;gap:9px}.actions .secondary{border:1px solid var(--wao-line-control);background:var(--wao-bg-card);color:var(--wao-text-3)}.actions span{margin-right:auto;color:var(--wao-text-weak);font-size:10px}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:900px){.create-card{grid-template-columns:1fr 1fr}.create-card>div,.create-card>input{grid-column:1/-1}.workspace{grid-template-columns:1fr}.task-list{max-height:260px;overflow:auto}}@media(max-width:600px){.create-card{grid-template-columns:1fr}.create-card>*{grid-column:1!important}.editor-grid{grid-template-columns:1fr}.editor-head{display:grid}.actions{align-items:stretch;flex-direction:column}.actions span{margin:0}.actions button{width:100%}}
</style>
