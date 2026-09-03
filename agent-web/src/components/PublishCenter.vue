<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  createPublishTask, getAuthorizeUrl, getPublishCenter, submitPublishTask,
  listMedia, markPublishTaskPublished, unbindAccount, updatePublishTask, uploadMedia,
} from '@/api/publish'
import { createKnowledgeArticle, listKnowledgeBases } from '@/api/knowledge'
import { articleBlocks, articleDocument, articleFragment } from '@/articleExport'
import {
  canSubmitPublish, formatBytes, isPublishEditable, isPublishInFlight, publishStatusLabel,
} from '@/publishStatus'
import type { MediaAsset, PlatformSummary, PublishCenter, PublishTask } from '@/types'
import type { KnowledgeBaseOption } from '@/api/knowledge'
import ArticleBodyEditor from './ArticleBodyEditor.vue'

type CenterMode = 'full' | 'accounts' | 'content' | 'publishing' | 'workbench'

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
const htmlDialog = ref(false)
const knowledgeDialog = ref(false)
const knowledgeBases = ref<KnowledgeBaseOption[]>([])
const knowledgeError = ref('')
const targetKbId = ref('')
const targetFolderId = ref('')
const createdDocUrl = ref('')
const createForm = reactive({ topic: '', platform: 'DOUYIN', generateCopy: true })
const editForm = reactive({ version: 0, title: '', content: '', tags: '', platformAccountId: '', mediaAssetId: '' })
let pollTimer: number | undefined

const selected = computed(() => center.value?.tasks.find((task) => task.id === selectedId.value) ?? null)
const selectedPlatform = computed(() => platform(selected.value?.platform))
const selectedMedia = computed(() => mediaAssets.value.find((item) => item.id === editForm.mediaAssetId))
const editable = computed(() => !!selected.value && isPublishEditable(selected.value.status))
const articleContent = computed(() => selected.value?.platform === 'WECHAT_MP')
const canPublish = computed(() => !!selected.value && !!selectedPlatform.value
  && canSubmitPublish(selected.value.status, selectedPlatform.value.publishSupported))
const canMarkPublished = computed(() => !!selected.value && !selectedPlatform.value?.publishSupported
  && ['WAITING_REVIEW', 'FAILED'].includes(selected.value.status) && !dirty.value)
const showAccounts = computed(() => props.mode === 'full' || props.mode === 'accounts')
const showCreate = computed(() => props.mode === 'full' || props.mode === 'content' || props.mode === 'workbench')
const showWorkspace = computed(() => props.mode !== 'accounts')
const showPublishControls = computed(() => props.mode === 'full' || props.mode === 'publishing' || props.mode === 'workbench')
const canExport = computed(() => !!selected.value && !dirty.value
  && !!(editForm.title.trim() || editForm.content.trim()))
const targetKb = computed(() => knowledgeBases.value.find((item) => item.id === targetKbId.value))
const exportInput = computed(() => ({
  title: editForm.title.trim() || selected.value?.topic || '未命名图文',
  content: editForm.content,
  tags: editForm.tags.split(/[\s,，#]+/).filter(Boolean),
  coverUrl: selectedMedia.value?.kind === 'IMAGE'
    ? new URL(`/api/agent/publish/media/${selectedMedia.value.id}/content`, window.location.origin).href
    : selected.value?.topic === '我的WAO首篇图文'
      ? new URL('/agent/article/my-wao-cover.png', window.location.origin).href
      : undefined,
}))
const exportHtml = computed(() => articleDocument(exportInput.value))
const loadingLabel = computed(() => ({
  accounts: '正在读取真实账号状态…',
  content: '正在读取真实内容…',
  publishing: '正在读取真实发布状态…',
  workbench: '正在读取内容、素材与发布状态…',
  full: '正在读取真实发布状态…',
})[props.mode])

function formatDate(value?: string) {
  if (!value) return '尚未发布'
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    .format(new Date(value))
}

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

function openHtmlExport() {
  if (!canExport.value) return
  htmlDialog.value = true
}

async function copyHtmlSource() {
  try {
    await navigator.clipboard.writeText(exportHtml.value)
    message.value = 'HTML 源码已复制，可粘贴到支持源码的编辑器'
  } catch {
    error.value = '浏览器未允许读取剪贴板，请在预览框中全选复制'
  }
}

async function copyRichArticle() {
  try {
    if (typeof ClipboardItem !== 'undefined' && navigator.clipboard.write) {
      await navigator.clipboard.write([new ClipboardItem({
        'text/html': new Blob([articleFragment(exportInput.value)], { type: 'text/html' }),
        'text/plain': new Blob([`${exportInput.value.title}\n\n${exportInput.value.content}`], { type: 'text/plain' }),
      })])
      message.value = '排版图文已复制，可直接粘贴到公众号等富文本编辑器'
    } else {
      await navigator.clipboard.writeText(exportHtml.value)
      message.value = '当前浏览器不支持富文本剪贴板，已改为复制 HTML 源码'
    }
  } catch {
    error.value = '复制失败，请允许剪贴板权限后重试'
  }
}

function downloadHtml() {
  const url = URL.createObjectURL(new Blob([exportHtml.value], { type: 'text/html;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${exportInput.value.title.replace(/[\\/:*?"<>|]/g, '-').slice(0, 80) || 'article'}.html`
  anchor.click()
  URL.revokeObjectURL(url)
  message.value = 'HTML 文件已下载'
}

async function openKnowledgeExport() {
  if (!canExport.value) return
  knowledgeDialog.value = true
  createdDocUrl.value = ''
  knowledgeError.value = ''
  busy.value = 'knowledge-load'
  error.value = ''
  try {
    knowledgeBases.value = await listKnowledgeBases()
    const first = knowledgeBases.value.find((kb) => kb.folders.length)
    targetKbId.value = first?.id ?? ''
    targetFolderId.value = first?.folders[0]?.id ?? ''
  } catch (cause) {
    knowledgeError.value = cause instanceof Error ? cause.message : '知识库读取失败，请重试'
  } finally {
    busy.value = ''
  }
}

function changeKnowledgeBase() {
  targetFolderId.value = targetKb.value?.folders[0]?.id ?? ''
}

async function saveToKnowledge() {
  if (!targetKbId.value || !targetFolderId.value || busy.value === 'knowledge-save') return
  busy.value = 'knowledge-save'
  knowledgeError.value = ''
  try {
    const doc = await createKnowledgeArticle({
      kbId: targetKbId.value,
      folderId: targetFolderId.value,
      title: exportInput.value.title,
      blocks: articleBlocks(exportInput.value.content),
    })
    createdDocUrl.value = `/kb/${doc.kbId}?doc=${encodeURIComponent(doc.id)}`
    message.value = `已存入知识库「${targetKb.value?.name ?? ''}」`
  } catch (cause) {
    knowledgeError.value = cause instanceof Error ? cause.message : '存入知识库失败，成稿仍保留，可重试'
  } finally {
    busy.value = ''
  }
}

function openCreatedDocument() {
  if (createdDocUrl.value) window.open(createdDocUrl.value, '_blank', 'noopener,noreferrer')
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

async function markPublished() {
  const task = selected.value
  if (!task || !canMarkPublished.value) return
  if (!window.confirm(`确认「${task.title || task.topic}」已经在${task.platformLabel}完成站外发布吗？`)) return
  busy.value = 'mark-published'
  error.value = ''
  try {
    await markPublishTaskPublished(task.id)
    await refresh(true)
    message.value = '已记录为已发布；WAO 没有伪造平台回执或数据'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '状态更新失败'
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
        <input v-model="createForm.topic" maxlength="200" placeholder="写下话题，或直接给我几句话" @keyup.enter="createTask" />
        <select v-model="createForm.platform"><option v-for="item in center.platforms" :key="item.platform" :value="item.platform">{{ item.label }}</option></select>
        <label><input v-model="createForm.generateCopy" type="checkbox" :disabled="!center.ai.available" /> AI 生成</label>
        <button :disabled="busy === 'create' || !createForm.topic.trim()" @click="createTask">{{ busy === 'create' ? '创建中…' : '开始创作' }}</button>
      </section>

      <div v-if="showWorkspace" class="workspace">
        <aside class="task-list">
          <div class="panel-head"><strong>{{ mode === 'workbench' ? '全部内容' : mode === 'publishing' ? '待发布与历史内容' : '最近内容' }}</strong><button aria-label="刷新内容" @click="refresh()"><i class="ri-refresh-line"></i></button></div>
          <button v-for="task in center.tasks" :key="task.id" class="task" :class="{ active: selectedId === task.id }" @click="selectTask(task)">
            <span><strong>{{ task.title || task.topic }}</strong><small>{{ task.platformLabel }} · {{ publishStatusLabel(task.status) }}</small><small>更新 {{ formatDate(task.updatedAt) }}</small></span><em>{{ task.progress }}%</em>
          </button>
          <div v-if="!center.tasks.length" class="empty">还没有内容，从上方创建第一条。</div>
        </aside>

        <div v-if="selected" class="draft-workspace">
          <main class="editor">
            <div class="editor-head">
              <div><strong>{{ selected.platformLabel }}内容稿</strong><span :class="`status ${selected.status.toLowerCase()}`">{{ publishStatusLabel(selected.status) }}</span></div>
              <small>v{{ selected.version }} · {{ dirty ? '有未保存修改' : '已与服务端同步' }}</small>
            </div>
            <div class="content-meta"><span>目标账号：{{ selected.platformAccountName || '未选择' }}</span><span>发布：{{ formatDate(selected.publishedAt) }}</span></div>
            <div v-if="selected.errorMessage" class="task-error">{{ selected.errorMessage }}</div>
            <label>标题<input v-model="editForm.title" maxlength="200" :disabled="!editable" @input="markDirty" /></label>
            <div class="body-field"><span>正文</span>
              <ArticleBodyEditor v-if="articleContent" v-model="editForm.content" :disabled="!editable" @change="markDirty" />
              <textarea v-else v-model="editForm.content" rows="18" :disabled="!editable" @input="markDirty"></textarea>
            </div>
            <label>标签<input v-model="editForm.tags" placeholder="用空格分隔" :disabled="!editable" @input="markDirty" /></label>
            <div class="editor-grid">
              <label v-if="showPublishControls">发布账号<select v-model="editForm.platformAccountId" :disabled="!editable" @change="markDirty"><option value="">暂不选择</option><option v-for="account in selectedPlatform?.accounts" :key="account.id" :value="account.id">{{ account.displayName }} · {{ account.status }}</option></select></label>
              <label>封面 / 发布素材<select v-model="editForm.mediaAssetId" :disabled="!editable" @change="markDirty"><option value="">暂不选择</option><option v-for="asset in mediaAssets" :key="asset.id" :value="asset.id">{{ asset.originalName }} · {{ formatBytes(asset.byteSize) }}</option></select></label>
              <label>上传或替换素材<span class="file"><input type="file" accept="image/*,video/*" :disabled="!editable || busy === 'upload'" @change="upload" />{{ busy === 'upload' ? '上传中…' : '选择图片或视频' }}</span></label>
            </div>
            <div class="actions">
              <button class="secondary" :disabled="!editable || !dirty || busy === 'save'" @click="saveTask">{{ busy === 'save' ? '保存中…' : '保存审核稿' }}</button>
              <button class="secondary" :disabled="!canExport" @click="openHtmlExport"><i class="ri-code-s-slash-line"></i> 导出 HTML</button>
              <button class="secondary" :disabled="!canExport" @click="openKnowledgeExport"><i class="ri-book-2-line"></i> 存入知识库</button>
              <template v-if="showPublishControls">
                <button v-if="selectedPlatform?.publishSupported" :disabled="!canPublish || dirty || busy === 'submit'" @click="submitTask">{{ busy === 'submit' ? '提交中…' : `确认发布到${selected.platformLabel}` }}</button>
                <template v-else><span>{{ selectedPlatform?.note }}</span><button :disabled="!canMarkPublished || busy === 'mark-published'" @click="markPublished">{{ busy === 'mark-published' ? '记录中…' : '我已在站外发布' }}</button></template>
              </template>
            </div>
          </main>
          <section class="live-preview" aria-label="实时发布预览">
            <header><div><strong>实时预览</strong><small>{{ selected.platformLabel }}最终排版 · 输入后立即同步</small></div><span :class="{ dirty }">{{ dirty ? '预览含未保存修改' : '已保存版本' }}</span></header>
            <iframe :srcdoc="exportHtml" title="实时图文排版预览" sandbox=""></iframe>
            <p v-if="selectedMedia && selectedMedia.kind !== 'IMAGE'" class="preview-media-note"><i class="ri-movie-line"></i>{{ selectedMedia.originalName }} 是发布视频，不嵌入图文预览。</p>
          </section>
        </div>
        <main v-else class="editor empty">选择一条任务查看成稿；创建后也会自动打开。</main>
      </div>
    </template>

    <div v-if="htmlDialog" class="dialog-mask" @click.self="htmlDialog = false">
      <section class="export-dialog" role="dialog" aria-modal="true" aria-label="HTML 图文预览">
        <header><div><strong>HTML 图文预览</strong><small>内容来自已保存审核稿；封面使用 WAO 公网静态地址</small></div><button aria-label="关闭" @click="htmlDialog = false"><i class="ri-close-line"></i></button></header>
        <iframe :srcdoc="exportHtml" title="图文排版预览" sandbox=""></iframe>
        <label>单文件 HTML 源码<textarea :value="exportHtml" rows="5" readonly @focus="($event.target as HTMLTextAreaElement).select()"></textarea></label>
        <footer><button class="secondary" @click="downloadHtml">下载 HTML</button><button class="secondary" @click="copyHtmlSource">复制 HTML 源码</button><button @click="copyRichArticle">复制排版图文</button></footer>
      </section>
    </div>

    <div v-if="knowledgeDialog" class="dialog-mask" @click.self="knowledgeDialog = false">
      <section class="knowledge-dialog" role="dialog" aria-modal="true" aria-label="存入知识库">
        <header><div><strong>存入知识库</strong><small>标题与完整正文会在一次请求中写入，不留下空白半成品</small></div><button aria-label="关闭" @click="knowledgeDialog = false"><i class="ri-close-line"></i></button></header>
        <div v-if="knowledgeError" class="dialog-error" role="alert"><span>{{ knowledgeError }}</span><button v-if="!knowledgeBases.length" @click="openKnowledgeExport">重试读取</button></div>
        <div v-if="busy === 'knowledge-load'" class="dialog-state">正在读取真实知识库…</div>
        <div v-else-if="!knowledgeBases.length" class="dialog-state">还没有可用知识库，请先在 WAO 新建知识库后重试。</div>
        <template v-else-if="!createdDocUrl">
          <label>知识库<select v-model="targetKbId" @change="changeKnowledgeBase"><option v-for="kb in knowledgeBases" :key="kb.id" :value="kb.id">{{ kb.name }}</option></select></label>
          <label>文件夹<select v-model="targetFolderId"><option v-for="folder in targetKb?.folders" :key="folder.id" :value="folder.id">{{ folder.name }}</option></select></label>
          <footer><button class="secondary" @click="knowledgeDialog = false">取消</button><button :disabled="!targetFolderId || busy === 'knowledge-save'" @click="saveToKnowledge">{{ busy === 'knowledge-save' ? '保存中…' : '保存完整图文' }}</button></footer>
        </template>
        <div v-else class="knowledge-success"><i class="ri-checkbox-circle-line"></i><strong>完整图文已进入知识库</strong><button @click="openCreatedDocument">打开文档</button></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.publish-center{display:grid;gap:14px}.notice,.error{padding:10px 12px;display:flex;align-items:center;gap:8px;border-radius:9px;font-size:12px}.notice{color:var(--wao-primary-text);background:var(--wao-primary-soft);border:1px solid var(--wao-primary-line)}.error{color:var(--wao-danger-strong);background:var(--wao-danger-bg);border:1px solid var(--wao-danger-line)}.error span{flex:1}.error button{border:0;background:transparent;color:inherit;cursor:pointer}.loading{min-height:260px;display:grid;place-items:center;align-content:center;gap:10px;color:var(--wao-text-weak)}.loading span{width:28px;height:28px;border:3px solid var(--wao-primary-line);border-top-color:var(--wao-primary);border-radius:50%;animation:spin .8s linear infinite}.capabilities{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:10px}.capabilities article{padding:13px;display:flex;gap:10px;border:1px solid var(--wao-line);border-radius:11px;background:var(--wao-bg-card)}.capabilities article>div{display:grid;gap:4px}.capabilities strong{font-size:12px}.capabilities span{color:var(--wao-text-weak);font-size:10px;line-height:1.5}.ai-card>i{font-size:22px;color:var(--wao-primary-text)}.ai-card.unavailable{opacity:.72}.platform-card{flex-direction:column}.accounts{display:flex!important;flex-wrap:wrap;gap:5px}.accounts>span{padding:4px 6px;border-radius:6px;background:var(--wao-bg-sub)}.accounts button{margin-left:4px;border:0;background:transparent;color:var(--wao-danger-strong);cursor:pointer}.accounts .link{margin:0;color:var(--wao-primary-text)}.create-card{padding:14px;display:grid;grid-template-columns:minmax(180px,1fr) minmax(220px,1.5fr) 120px auto auto;align-items:center;gap:9px;border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card)}.create-card>div{display:grid;gap:3px}.create-card strong{font-size:13px}.create-card span{color:var(--wao-text-weak);font-size:9.5px;line-height:1.4}.create-card input,.create-card select,.editor input,.editor select,.editor textarea,.knowledge-dialog select{width:100%;padding:9px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text);font:inherit}.create-card>label{display:flex;align-items:center;gap:5px;font-size:11px;white-space:nowrap}.create-card>label input{width:auto}.create-card>button,.actions>button{min-height:36px;padding:0 13px;border:0;border-radius:8px;background:var(--wao-primary);color:#fff;cursor:pointer;font-weight:650}.create-card button:disabled,.actions button:disabled{opacity:.45;cursor:not-allowed}.workspace{display:grid;grid-template-columns:280px minmax(0,1fr);gap:12px;align-items:start}.task-list,.editor{border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card)}.panel-head{padding:11px 12px;display:flex;justify-content:space-between;border-bottom:1px solid var(--wao-divider)}.panel-head button{border:0;background:transparent;color:var(--wao-text-weak);cursor:pointer}.task{width:100%;padding:10px 11px;display:flex;align-items:center;gap:8px;border:0;border-bottom:1px solid var(--wao-divider);background:transparent;color:var(--wao-text);cursor:pointer;text-align:left}.task.active{background:var(--wao-primary-soft)}.task>span{min-width:0;flex:1;display:grid;gap:3px}.task strong{overflow:hidden;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.task small{color:var(--wao-text-weak);font-size:9.5px}.task em{color:var(--wao-primary-text);font-size:10px;font-style:normal}.empty{padding:28px;color:var(--wao-text-weak);font-size:11px;text-align:center}.editor{padding:15px;display:grid;gap:12px}.editor-head{display:flex;justify-content:space-between;gap:10px}.editor-head>div{display:flex;align-items:center;gap:8px}.editor-head small{color:var(--wao-text-weak);font-size:10px}.status{padding:3px 6px;border-radius:999px;background:var(--wao-primary-soft);color:var(--wao-primary-text);font-size:9px}.status.published{background:var(--wao-success-bg);color:var(--wao-success-strong)}.status.failed{background:var(--wao-danger-bg);color:var(--wao-danger-strong)}.task-error{padding:8px 10px;color:var(--wao-danger-strong);background:var(--wao-danger-bg);border-radius:7px;font-size:11px}.editor label,.knowledge-dialog label,.export-dialog label{display:grid;gap:5px;color:var(--wao-text-3);font-size:10px}.editor textarea{resize:vertical;line-height:1.55}.editor-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}.file{min-height:36px;padding:8px;position:relative;overflow:hidden;border:1px dashed var(--wao-line-control);border-radius:8px;color:var(--wao-text-weak);cursor:pointer}.file input{position:absolute;inset:0;opacity:0;cursor:pointer}.actions{display:flex;align-items:center;justify-content:flex-end;gap:9px;flex-wrap:wrap}.actions .secondary{border:1px solid var(--wao-line-control);background:var(--wao-bg-card);color:var(--wao-text-3)}.actions span{margin-right:auto;color:var(--wao-text-weak);font-size:10px}.dialog-mask{position:fixed;inset:0;z-index:50;padding:28px;display:grid;place-items:center;background:rgba(10,30,27,.58)}.export-dialog,.knowledge-dialog{width:min(920px,100%);max-height:calc(100vh - 56px);padding:18px;display:grid;gap:14px;overflow:auto;border-radius:16px;background:var(--wao-bg-card);box-shadow:0 24px 70px rgba(0,0,0,.3)}.knowledge-dialog{width:min(520px,100%)}.export-dialog>header,.knowledge-dialog>header{display:flex;justify-content:space-between;gap:12px}.export-dialog header div,.knowledge-dialog header div{display:grid;gap:4px}.export-dialog header small,.knowledge-dialog header small{color:var(--wao-text-weak);font-size:10px}.export-dialog header button,.knowledge-dialog header button{border:0;background:transparent;color:var(--wao-text-weak);font-size:20px;cursor:pointer}.export-dialog iframe{width:100%;height:430px;border:1px solid var(--wao-line);border-radius:12px;background:#edf3f1}.export-dialog textarea{width:100%;padding:10px;border:1px solid var(--wao-line-control);border-radius:8px;box-sizing:border-box;font:11px/1.5 ui-monospace,monospace;resize:vertical}.export-dialog footer,.knowledge-dialog footer{display:flex;justify-content:flex-end;gap:8px}.export-dialog footer button,.knowledge-dialog footer button,.knowledge-success button{min-height:36px;padding:0 14px;border:0;border-radius:8px;background:var(--wao-primary);color:#fff;cursor:pointer}.export-dialog footer .secondary,.knowledge-dialog footer .secondary{border:1px solid var(--wao-line-control);background:var(--wao-bg-card);color:var(--wao-text-3)}.dialog-state{padding:30px;text-align:center;color:var(--wao-text-weak)}.knowledge-success{padding:28px;display:grid;place-items:center;gap:10px;color:var(--wao-success-strong)}.knowledge-success i{font-size:36px}@keyframes spin{to{transform:rotate(360deg)}}
.dialog-error{padding:10px 12px;display:flex;justify-content:space-between;gap:10px;border-radius:8px;background:var(--wao-danger-bg);color:var(--wao-danger-strong);font-size:11px}.dialog-error button{border:0;background:transparent;color:inherit;cursor:pointer}
@media(max-width:900px){.create-card{grid-template-columns:1fr 1fr}.create-card>div,.create-card>input{grid-column:1/-1}.workspace{grid-template-columns:1fr}.task-list{max-height:260px;overflow:auto}}@media(max-width:600px){.create-card{grid-template-columns:1fr}.create-card>*{grid-column:1!important}.editor-grid{grid-template-columns:1fr}.editor-head{display:grid}.actions{align-items:stretch;flex-direction:column}.actions span{margin:0}.actions button{width:100%}.dialog-mask{padding:10px}.export-dialog,.knowledge-dialog{max-height:calc(100vh - 20px);padding:14px}.export-dialog iframe{height:52vh}.export-dialog footer,.knowledge-dialog footer{flex-direction:column}.export-dialog footer button,.knowledge-dialog footer button{width:100%}}
.workspace { grid-template-columns: 240px minmax(0, 1fr); }
.draft-workspace {
  min-width: 0; display: grid;
  grid-template-columns: minmax(340px, 1fr) minmax(340px, 1fr);
  gap: 12px; align-items: start;
}
.task-list { max-height: calc(100vh - 230px); overflow: auto; position: sticky; top: 0; }
.live-preview { min-width: 0; overflow: hidden; position: sticky; top: 0; }
.live-preview header {
  min-height: 54px; padding: 10px 12px; display: flex; align-items: center;
  justify-content: space-between; gap: 10px; border-bottom: 1px solid var(--wao-divider);
}
.live-preview header > div { display: grid; gap: 3px; }
.live-preview header strong { font-size: 12px; }
.live-preview header small { color: var(--wao-text-weak); font-size: 9.5px; }
.live-preview header > span { color: var(--wao-success-strong); font-size: 9.5px; white-space: nowrap; }
.live-preview header > span.dirty { color: var(--wao-warning-strong); }
.live-preview iframe { width: 100%; height: calc(100vh - 310px); min-height: 560px; border: 0; background: #edf3f1; }
.preview-media-note { margin: 0; padding: 9px 12px; display: flex; gap: 6px; color: var(--wao-text-weak); font-size: 10px; border-top: 1px solid var(--wao-divider); }
.content-meta { display: flex; flex-wrap: wrap; gap: 6px 12px; color: var(--wao-text-weak); font-size: 9.5px; }
.body-field { display: grid; gap: 5px; color: var(--wao-text-3); font-size: 10px; }
.body-field :deep(.article-body-editor) { color: var(--wao-text); }
@media(max-width: 1250px) {
  .workspace { grid-template-columns: 1fr; }
  .task-list { max-height: 220px; position: static; }
}
@media(max-width: 820px) {
  .draft-workspace { grid-template-columns: 1fr; }
  .live-preview { position: static; }
  .live-preview iframe { height: 68vh; min-height: 480px; }
}
</style>
