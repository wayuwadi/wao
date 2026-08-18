<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  createPersonalMemory,
  deletePersonalMemory,
  fetchPersonalMemories,
  updatePersonalMemory,
  uploadPersonalMemory,
  type MemoryCategory,
  type PersonalMemory,
} from '@/api/memory'
import { toast } from '@/composables/toast'

const DRAFT_KEY = 'wao-personal-memory-draft-v1'
const MAX_FILE_BYTES = 50 * 1024 * 1024
const categories: { value: MemoryCategory; label: string; icon: string }[] = [
  { value: 'experience', label: '生活经历', icon: 'ri-footprint-line' },
  { value: 'preference', label: '偏好习惯', icon: 'ri-heart-3-line' },
  { value: 'responsibility', label: '长期职责', icon: 'ri-briefcase-4-line' },
  { value: 'plan', label: '长期规划', icon: 'ri-route-line' },
  { value: 'decision', label: '确认决策', icon: 'ri-scales-3-line' },
  { value: 'context', label: '背景资料', icon: 'ri-archive-line' },
]

const memories = ref<PersonalMemory[]>([])
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const deletingId = ref('')
const search = ref('')
const filter = ref<MemoryCategory | ''>('')
const queryTimer = ref<number | null>(null)
const composerOpen = ref(false)
const editing = ref<PersonalMemory | null>(null)
const title = ref('')
const content = ref('')
const category = ref<MemoryCategory>('experience')
const selectedFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const titleInput = ref<HTMLInputElement | null>(null)
const draftRestored = ref(false)
const highlightedId = ref(new URLSearchParams(window.location.search).get('entry') ?? '')

const stats = computed(() => ({
  total: memories.value.length,
  manual: memories.value.filter((item) => item.captureMode === 'manual').length,
  ai: memories.value.filter((item) => item.captureMode === 'ai').length,
  media: memories.value.filter((item) => item.mediaType !== 'text').length,
}))

function categoryMeta(value: MemoryCategory) {
  return categories.find((item) => item.value === value) ?? categories[5]!
}

function mediaLabel(item: PersonalMemory) {
  return { text: '文字', image: '图片', audio: '音频', video: '视频', file: '文件' }[item.mediaType]
}

function mediaIcon(item: PersonalMemory) {
  return { text: 'ri-sticky-note-line', image: 'ri-image-line', audio: 'ri-mic-line', video: 'ri-video-line', file: 'ri-attachment-2' }[item.mediaType]
}

function formatSize(bytes?: number) {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatTime(value: string) {
  if (!value) return ''
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    memories.value = await fetchPersonalMemories(search.value.trim(), filter.value)
    if (highlightedId.value && memories.value.some((item) => item.id === highlightedId.value)) {
      await nextTick()
      document.getElementById(`memory-${highlightedId.value}`)?.scrollIntoView({ block: 'center' })
    }
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '个人记忆读取失败'
  } finally {
    loading.value = false
  }
}

watch([search, filter], () => {
  if (queryTimer.value != null) window.clearTimeout(queryTimer.value)
  queryTimer.value = window.setTimeout(() => void load(), 250)
})

watch([title, content, category], () => {
  if (!composerOpen.value || editing.value) return
  localStorage.setItem(DRAFT_KEY, JSON.stringify({ title: title.value, content: content.value, category: category.value }))
})

function resetForm() {
  title.value = ''
  content.value = ''
  category.value = 'experience'
  selectedFile.value = null
  editing.value = null
  draftRestored.value = false
  if (fileInput.value) fileInput.value.value = ''
}

async function openCreate() {
  resetForm()
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    if (raw) {
      const draft = JSON.parse(raw) as { title?: string; content?: string; category?: MemoryCategory }
      title.value = draft.title ?? ''
      content.value = draft.content ?? ''
      if (categories.some((item) => item.value === draft.category)) category.value = draft.category!
      draftRestored.value = !!(title.value || content.value)
    }
  } catch { localStorage.removeItem(DRAFT_KEY) }
  composerOpen.value = true
  await nextTick()
  titleInput.value?.focus()
}

async function openEdit(item: PersonalMemory) {
  resetForm()
  editing.value = item
  title.value = item.title
  content.value = item.content
  category.value = item.category
  composerOpen.value = true
  await nextTick()
  titleInput.value?.focus()
}

function closeComposer() {
  if (!editing.value && (title.value.trim() || content.value.trim())) {
    localStorage.setItem(DRAFT_KEY, JSON.stringify({ title: title.value, content: content.value, category: category.value }))
  }
  composerOpen.value = false
  resetForm()
}

function chooseFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0] ?? null
  if (file && file.size > MAX_FILE_BYTES) {
    toast('单个记忆文件不能超过 50MB', 'ri-error-warning-line')
    if (fileInput.value) fileInput.value.value = ''
    return
  }
  selectedFile.value = file
  if (file && !title.value.trim()) title.value = file.name.replace(/\.[^.]+$/, '')
}

async function save() {
  const payload = { title: title.value.trim(), content: content.value.trim(), category: category.value }
  if (!payload.title) return toast('请填写一个以后能搜到的标题', 'ri-error-warning-line')
  if (!editing.value && !selectedFile.value && !payload.content) return toast('请填写记忆正文或选择文件', 'ri-error-warning-line')
  saving.value = true
  try {
    const wasEditing = !!editing.value
    const saved = editing.value
      ? await updatePersonalMemory(editing.value.id, payload)
      : selectedFile.value
        ? await uploadPersonalMemory(payload, selectedFile.value)
        : await createPersonalMemory(payload)
    memories.value = [saved, ...memories.value.filter((item) => item.id !== saved.id)]
    localStorage.removeItem(DRAFT_KEY)
    composerOpen.value = false
    resetForm()
    toast(wasEditing ? '个人记忆已更新' : '个人记忆已保存', 'ri-checkbox-circle-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '保存失败，草稿已保留', 'ri-error-warning-line')
  } finally {
    saving.value = false
  }
}

async function remove(item: PersonalMemory) {
  if (!window.confirm(`删除个人记忆“${item.title}”？附件原件也会一并删除。`)) return
  deletingId.value = item.id
  try {
    await deletePersonalMemory(item.id)
    memories.value = memories.value.filter((candidate) => candidate.id !== item.id)
    toast('个人记忆已删除', 'ri-delete-bin-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '删除失败，请重试', 'ri-error-warning-line')
  } finally { deletingId.value = '' }
}

onMounted(() => void load())
</script>

<template>
  <main class="memory-page">
    <header class="memory-hero">
      <div>
        <div class="eyebrow"><i class="ri-brain-line"></i> PERSONAL MEMORY</div>
        <h1>个人记忆</h1>
        <p>把生活经历、偏好、声音、影像和文件留在自己的账号里。人工上传与 Codex 采集共用同一份可核对资料，但不同成员互不可见。</p>
      </div>
      <button class="primary" @click="openCreate"><i class="ri-add-line"></i>收集一条记忆</button>
    </header>

    <section class="privacy-card">
      <i class="ri-shield-user-line"></i>
      <div><strong>当前账号私有</strong><span>列表、搜索、附件下载和 MCP 检索都会在服务端校验成员身份；组织知识库不会混进个人记忆。</span></div>
      <a href="/settings?tab=memory-mcp"><i class="ri-robot-2-line"></i>连接 Codex 自动采集</a>
    </section>

    <section class="stats" aria-label="记忆统计">
      <span><b>{{ stats.total }}</b><small>当前结果</small></span>
      <span><b>{{ stats.manual }}</b><small>人工采集</small></span>
      <span><b>{{ stats.ai }}</b><small>AI 采集</small></span>
      <span><b>{{ stats.media }}</b><small>多模态原件</small></span>
    </section>

    <section class="toolbar">
      <label class="search"><i class="ri-search-line"></i><input v-model="search" placeholder="搜索标题、正文或文件名" /></label>
      <select v-model="filter" aria-label="按分类筛选">
        <option value="">全部分类</option>
        <option v-for="item in categories" :key="item.value" :value="item.value">{{ item.label }}</option>
      </select>
    </section>

    <section v-if="loading" class="state-card"><i class="ri-loader-4-line spin"></i><span>正在读取当前账号的记忆…</span></section>
    <section v-else-if="loadError" class="state-card error" role="alert"><i class="ri-error-warning-line"></i><span>{{ loadError }}</span><button @click="load">重试</button></section>
    <section v-else-if="!memories.length" class="state-card empty">
      <i class="ri-inbox-archive-line"></i><strong>{{ search || filter ? '没有匹配的个人记忆' : '还没有个人记忆' }}</strong>
      <span>{{ search || filter ? '换个关键词或清除筛选试试。' : '先记录一段文字，或上传照片、录音、视频与文件。' }}</span>
      <button v-if="!search && !filter" @click="openCreate">收集第一条</button>
    </section>

    <section v-else class="memory-grid">
      <article v-for="item in memories" :id="`memory-${item.id}`" :key="item.id" class="memory-card" :class="{ highlighted: highlightedId === item.id }">
        <div class="card-top">
          <span class="media-icon"><i :class="mediaIcon(item)"></i></span>
          <div><h2>{{ item.title }}</h2><p>{{ formatTime(item.updatedAt) }}</p></div>
          <span class="capture" :class="item.captureMode"><i :class="item.captureMode === 'ai' ? 'ri-robot-2-line' : 'ri-user-line'"></i>{{ item.captureMode === 'ai' ? 'AI 采集' : '人工采集' }}</span>
        </div>
        <p v-if="item.content" class="content">{{ item.content }}</p>
        <a v-if="item.downloadUrl" class="attachment" :href="item.downloadUrl" target="_blank">
          <i :class="mediaIcon(item)"></i><span><b>{{ item.fileName }}</b><small>{{ mediaLabel(item) }} · {{ formatSize(item.byteSize) }}</small></span><i class="ri-download-2-line"></i>
        </a>
        <div class="card-footer">
          <span><i :class="categoryMeta(item.category).icon"></i>{{ categoryMeta(item.category).label }}</span>
          <div><button title="编辑" @click="openEdit(item)"><i class="ri-edit-line"></i></button><button class="danger" title="删除" :disabled="deletingId === item.id" @click="remove(item)"><i :class="deletingId === item.id ? 'ri-loader-4-line spin' : 'ri-delete-bin-line'"></i></button></div>
        </div>
      </article>
    </section>

    <div v-if="composerOpen" class="dialog-mask" @click.self="closeComposer">
      <section class="composer" role="dialog" aria-modal="true" aria-label="收集个人记忆">
        <header><div><h2>{{ editing ? '编辑个人记忆' : '收集一条个人记忆' }}</h2><p>{{ editing ? '修改标题、说明或分类，已归档原件保持不变。' : '文字可直接保存，也可以附带一份图片、录音、视频或文件原件。' }}</p></div><button title="关闭" @click="closeComposer"><i class="ri-close-line"></i></button></header>
        <div v-if="draftRestored" class="draft-note"><i class="ri-draft-line"></i>已恢复上次未保存的文字草稿</div>
        <label><span>标题</span><input ref="titleInput" v-model="title" maxlength="120" placeholder="例如：我喜欢安静地晨跑" /></label>
        <label><span>分类</span><select v-model="category"><option v-for="item in categories" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
        <label><span>{{ selectedFile || editing?.fileName ? '说明（可选）' : '正文' }}</span><textarea v-model="content" maxlength="20000" rows="7" placeholder="写清时间、人物、地点、原因和以后值得记住的结论…"></textarea><small>{{ content.length }} / 20000</small></label>
        <label v-if="!editing" class="file-field"><span>多模态原件（可选）</span><button type="button" @click="fileInput?.click()"><i class="ri-attachment-2"></i>{{ selectedFile ? selectedFile.name : '选择图片、录音、视频或文件' }}</button><small>单个不超过 50MB；文件保存在当前账号的个人记忆中。</small><input ref="fileInput" type="file" hidden @change="chooseFile" /></label>
        <div v-else-if="editing.fileName" class="kept-file"><i class="ri-lock-line"></i>原件“{{ editing.fileName }}”保持不变；如需替换，请新建一条记忆。</div>
        <footer><span><i class="ri-save-3-line"></i>{{ saving ? '保存中，请勿关闭…' : '输入会自动保存在本机草稿中' }}</span><div><button @click="closeComposer">取消</button><button class="primary" :disabled="saving" @click="save"><i :class="saving ? 'ri-loader-4-line spin' : 'ri-save-line'"></i>{{ saving ? '保存中…' : '保存记忆' }}</button></div></footer>
      </section>
    </div>
  </main>
</template>

<style scoped>
.memory-page{max-width:1180px;margin:0 auto;padding:28px 26px 48px}.memory-hero{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-bottom:20px}.eyebrow{display:flex;align-items:center;gap:7px;color:var(--wao-primary-text);font-size:11px;font-weight:750;letter-spacing:.12em}.memory-hero h1{margin:7px 0 0;font-size:clamp(30px,4vw,44px);letter-spacing:-.045em}.memory-hero p{max-width:700px;margin:8px 0 0;color:var(--wao-text-3);font-size:13px;line-height:1.75}.primary{border:0;background:var(--wao-primary);color:#fff}.memory-hero>button{min-height:40px;padding:0 16px;border-radius:10px;font-weight:700;white-space:nowrap;cursor:pointer}.memory-hero button i{margin-right:6px}.privacy-card{display:flex;align-items:center;gap:12px;padding:13px 15px;border:1px solid var(--wao-success-line);border-radius:13px;background:var(--wao-success-bg)}.privacy-card>i{font-size:24px;color:var(--wao-success-strong)}.privacy-card div{display:grid;gap:2px;flex:1}.privacy-card strong{font-size:12px}.privacy-card span{color:var(--wao-text-3);font-size:11px;line-height:1.5}.privacy-card a{padding:7px 10px;border-radius:8px;color:var(--wao-primary-text);background:var(--wao-bg-card);font-size:11px;text-decoration:none;white-space:nowrap}.privacy-card a i{margin-right:5px}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:14px 0}.stats span{padding:12px 14px;border:1px solid var(--wao-line);border-radius:11px;background:var(--wao-bg-card)}.stats b,.stats small{display:block}.stats b{font-size:19px}.stats small{margin-top:2px;color:var(--wao-text-weak);font-size:10px}.toolbar{display:flex;gap:10px;margin-bottom:14px}.search{min-height:38px;display:flex;align-items:center;gap:8px;flex:1;padding:0 11px;border:1px solid var(--wao-line-control);border-radius:9px;background:var(--wao-bg-card)}.search i{color:var(--wao-text-faint)}.search input{width:100%;border:0;outline:0;background:transparent;color:var(--wao-text);font-size:12px}.toolbar select,.composer select,.composer input,.composer textarea{border:1px solid var(--wao-line-control);border-radius:9px;background:var(--wao-bg-card);color:var(--wao-text);outline:0}.toolbar select{min-width:135px;padding:0 10px}.state-card{min-height:280px;display:grid;place-items:center;align-content:center;gap:10px;border:1px solid var(--wao-line);border-radius:15px;background:var(--wao-bg-card);color:var(--wao-text-weak);font-size:12px}.state-card>i{font-size:26px}.state-card button{padding:7px 13px;border:0;border-radius:8px;background:var(--wao-primary-soft);color:var(--wao-primary-text);cursor:pointer}.state-card.error{color:var(--wao-danger-strong)}.memory-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.memory-card{min-width:0;padding:15px;border:1px solid var(--wao-line);border-radius:14px;background:var(--wao-bg-card);box-shadow:var(--wao-shadow-card)}.card-top{display:flex;align-items:center;gap:10px}.media-icon{width:36px;height:36px;display:grid;place-items:center;flex:0 0 auto;border-radius:10px;background:var(--wao-primary-soft);color:var(--wao-primary-text);font-size:18px}.card-top>div{min-width:0;flex:1}.card-top h2{overflow:hidden;margin:0;font-size:14px;text-overflow:ellipsis;white-space:nowrap}.card-top p{margin:3px 0 0;color:var(--wao-text-weak);font-size:10px}.capture{padding:4px 7px;border-radius:999px;background:var(--wao-bg-sub);color:var(--wao-text-3);font-size:9px;white-space:nowrap}.capture.ai{background:var(--wao-primary-soft);color:var(--wao-primary-text)}.capture i{margin-right:3px}.content{display:-webkit-box;overflow:hidden;margin:12px 0 0;color:var(--wao-text-2);font-size:12px;line-height:1.7;-webkit-box-orient:vertical;-webkit-line-clamp:4;white-space:pre-wrap}.attachment{margin-top:12px;padding:9px 10px;display:flex;align-items:center;gap:8px;border:1px solid var(--wao-divider);border-radius:9px;background:var(--wao-bg-sub);color:var(--wao-text);text-decoration:none}.attachment>i:first-child{color:var(--wao-primary-text);font-size:17px}.attachment span{min-width:0;display:grid;flex:1}.attachment b{overflow:hidden;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.attachment small{margin-top:2px;color:var(--wao-text-weak);font-size:9px}.attachment>i:last-child{color:var(--wao-text-faint)}.card-footer{display:flex;align-items:center;justify-content:space-between;margin-top:13px;padding-top:10px;border-top:1px solid var(--wao-divider)}.card-footer>span{color:var(--wao-text-3);font-size:10px}.card-footer>span i{margin-right:5px}.card-footer div{display:flex;gap:4px}.card-footer button,.composer header>button{width:28px;height:28px;border:0;border-radius:7px;background:transparent;color:var(--wao-text-3);cursor:pointer}.card-footer button:hover{background:var(--wao-bg-sub)}.card-footer button.danger{color:var(--wao-danger)}.dialog-mask{position:fixed;inset:0;z-index:1000;display:grid;place-items:center;padding:20px;background:rgba(15,23,42,.44);backdrop-filter:blur(3px)}.composer{width:min(620px,100%);max-height:min(780px,calc(100vh - 32px));overflow:auto;padding:19px;border:1px solid var(--wao-line);border-radius:16px;background:var(--wao-bg-card);box-shadow:0 24px 70px rgba(15,23,42,.25)}.composer header{display:flex;gap:12px}.composer header div{flex:1}.composer h2{margin:0;font-size:18px}.composer header p{margin:5px 0 0;color:var(--wao-text-weak);font-size:11px;line-height:1.55}.composer header>button{font-size:19px}.draft-note,.kept-file{margin-top:12px;padding:8px 10px;border-radius:8px;background:var(--wao-warning-bg);color:var(--wao-warning-strong);font-size:10px}.composer>label{position:relative;display:grid;gap:6px;margin-top:13px}.composer label>span{font-size:11px;font-weight:700}.composer input,.composer select,.composer textarea{width:100%;box-sizing:border-box;padding:9px 10px;font:inherit;font-size:12px}.composer textarea{resize:vertical;line-height:1.6}.composer label>small{color:var(--wao-text-weak);font-size:9px}.composer label:not(.file-field)>small{text-align:right}.file-field>button{min-height:42px;border:1px dashed var(--wao-primary-line);border-radius:9px;background:var(--wao-primary-soft);color:var(--wao-primary-text);cursor:pointer}.file-field>button i{margin-right:6px}.composer footer{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:18px;padding-top:13px;border-top:1px solid var(--wao-divider)}.composer footer>span{color:var(--wao-text-weak);font-size:9px}.composer footer div{display:flex;gap:7px}.composer footer button{min-height:35px;padding:0 13px;border:0;border-radius:8px;background:var(--wao-bg-sub);color:var(--wao-text-2);cursor:pointer}.composer footer button.primary{background:var(--wao-primary);color:#fff}.composer button:disabled{opacity:.6;cursor:wait}.spin{display:inline-block;animation:spin .8s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
.memory-card.highlighted{border-color:var(--wao-primary);box-shadow:0 0 0 3px var(--wao-primary-soft),var(--wao-shadow-card)}
@media(max-width:760px){.memory-page{padding:18px 12px 34px}.memory-hero{display:grid;gap:14px}.memory-hero>button{width:100%}.privacy-card{align-items:flex-start;flex-wrap:wrap}.privacy-card div{min-width:calc(100% - 48px)}.privacy-card a{margin-left:36px}.stats{grid-template-columns:repeat(2,1fr)}.toolbar{display:grid}.toolbar select{min-height:38px}.memory-grid{grid-template-columns:1fr}.dialog-mask{padding:8px;align-items:end}.composer{max-height:calc(100vh - 16px);padding:15px;border-radius:15px 15px 10px 10px}.composer footer{align-items:stretch;flex-direction:column}.composer footer div{display:grid;grid-template-columns:1fr 1fr}.composer footer button{min-height:40px}}
</style>
