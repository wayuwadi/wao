<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { completeVideoExport, getVideoProject, saveVideoProject, uploadRenderedVideo } from '@/api/video'
import { canRenderVideo, enabledDuration, renderVideo, type RenderProgress } from '@/videoRenderer'
import type { VideoProject, VideoScene } from '@/types'

const project = ref<VideoProject | null>(null)
const selectedId = ref('')
const loading = ref(true)
const saving = ref(false)
const dirty = ref(false)
const message = ref('')
const error = ref('')
const renderProgress = ref<RenderProgress | null>(null)
const renderedUrl = ref('')
const editRevision = ref(0)
let saveTimer: number | undefined
let suppressWatch = true
let renderController: AbortController | undefined

const selected = computed(() => project.value?.scenes.find((scene) => scene.id === selectedId.value) ?? null)
const activeScenes = computed(() => project.value?.scenes.filter((scene) => scene.enabled) ?? [])
const totalSeconds = computed(() => enabledDuration(project.value?.scenes ?? []))
const durationText = computed(() => `${Math.floor(totalSeconds.value / 60)} 分 ${totalSeconds.value % 60} 秒`)
const renderSupported = computed(() => canRenderVideo())
const rendering = computed(() => !!renderProgress.value)
const outputUrl = computed(() => renderedUrl.value || project.value?.outputUrl || '')

function applyProject(value: VideoProject, replaceScenes = true) {
  suppressWatch = true
  if (replaceScenes || !project.value) project.value = structuredClone(value)
  else Object.assign(project.value, {
    version: value.version,
    status: value.status,
    outputMediaAssetId: value.outputMediaAssetId,
    outputName: value.outputName,
    outputByteSize: value.outputByteSize,
    outputUrl: value.outputUrl,
    updatedAt: value.updatedAt,
    exportedAt: value.exportedAt,
  })
  if (!selectedId.value || !project.value?.scenes.some((scene) => scene.id === selectedId.value)) {
    selectedId.value = project.value?.scenes[0]?.id ?? ''
  }
  queueMicrotask(() => { suppressWatch = false })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    applyProject(await getVideoProject())
    dirty.value = false
    message.value = '分镜已与服务端同步'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '视频工作流读取失败'
  } finally {
    loading.value = false
  }
}

function scheduleSave() {
  if (suppressWatch || !project.value || rendering.value) return
  dirty.value = true
  editRevision.value += 1
  project.value.status = 'DRAFT'
  project.value.outputMediaAssetId = undefined
  project.value.outputUrl = undefined
  message.value = '有未保存修改'
  if (saveTimer) window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => { void saveNow() }, 900)
}

async function saveNow() {
  if (!project.value || !dirty.value || saving.value) return
  if (saveTimer) window.clearTimeout(saveTimer)
  const revision = editRevision.value
  const version = project.value.version
  const title = project.value.title
  // Vue 的响应式 Proxy 不能直接 structuredClone；提交前逐项还原为普通对象。
  const scenes = project.value.scenes.map((scene) => ({ ...scene }))
  saving.value = true
  error.value = ''
  message.value = '正在保存分镜…'
  try {
    const saved = await saveVideoProject(version, title, scenes)
    applyProject(saved, revision === editRevision.value)
    if (revision === editRevision.value) {
      dirty.value = false
      message.value = `已保存 · v${saved.version}`
    } else {
      message.value = '编辑有新变化，正在继续保存…'
      window.setTimeout(() => { void saveNow() }, 0)
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
    message.value = '保存失败，草稿仍保留在当前页面'
  } finally {
    saving.value = false
  }
}

function move(scene: VideoScene, offset: number) {
  if (!project.value) return
  const index = project.value.scenes.indexOf(scene)
  const target = index + offset
  if (target < 0 || target >= project.value.scenes.length) return
  const next = [...project.value.scenes]
  ;[next[index], next[target]] = [next[target], next[index]]
  project.value.scenes = next
  selectedId.value = scene.id
}

function formatTime(seconds: number) {
  const value = Math.max(0, Math.round(seconds))
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}`
}

function formatBytes(bytes?: number) {
  if (!bytes) return ''
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`
}

async function startRender() {
  if (!project.value || rendering.value) return
  await saveNow()
  if (dirty.value || saving.value) {
    error.value = '请先解决分镜保存失败，再开始合成'
    return
  }
  if (!activeScenes.value.length) {
    error.value = '至少启用一个分镜'
    return
  }
  renderController = new AbortController()
  error.value = ''
  message.value = '正在按真实时长合成；切换标签页时会自动暂停'
  renderProgress.value = {
    progress: 0, elapsedSeconds: 0, totalSeconds: totalSeconds.value,
    sceneIndex: 0, sceneTitle: activeScenes.value[0].title, paused: false,
  }
  try {
    const blob = await renderVideo(project.value.scenes, (progress) => { renderProgress.value = progress }, renderController.signal)
    if (renderedUrl.value) URL.revokeObjectURL(renderedUrl.value)
    renderedUrl.value = URL.createObjectURL(blob)
    message.value = '本地编码完成，正在上传真实成片…'
    const safeTitle = project.value.title.replace(/[\\/:*?"<>|]/g, '-').slice(0, 80) || 'wao-video'
    const asset = await uploadRenderedVideo(new File([blob], `${safeTitle}.webm`, { type: 'video/webm' }))
    const exported = await completeVideoExport(project.value.version, asset.id)
    applyProject(exported)
    dirty.value = false
    message.value = `成片已导出并保存 · ${formatBytes(asset.byteSize)}`
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') message.value = '已取消合成，分镜草稿不受影响'
    else error.value = cause instanceof Error ? cause.message : '视频合成失败，请重试'
  } finally {
    renderProgress.value = null
    renderController = undefined
  }
}

function cancelRender() {
  renderController?.abort()
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value && !rendering.value) return
  event.preventDefault()
}

watch(() => project.value ? [project.value.title, project.value.scenes] : null, scheduleSave, { deep: true })

onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  void load()
})
onBeforeUnmount(() => {
  if (saveTimer) window.clearTimeout(saveTimer)
  renderController?.abort()
  if (renderedUrl.value) URL.revokeObjectURL(renderedUrl.value)
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <div class="video-workflow">
    <div v-if="error" class="error" role="alert"><i class="ri-error-warning-line"></i><span>{{ error }}</span><button @click="error = ''; dirty ? saveNow() : load()">重试</button></div>
    <div v-if="message" class="notice"><i :class="saving ? 'ri-loader-4-line spin' : 'ri-information-line'"></i>{{ message }}</div>
    <div v-if="loading" class="loading"><span></span>正在读取视频分镜…</div>

    <template v-else-if="project">
      <section class="workflow-steps">
        <div class="step done"><b>1</b><span><strong>整理分镜</strong><small>{{ project.scenes.length }} 个镜头 · {{ durationText }}</small></span></div>
        <i class="ri-arrow-right-line"></i>
        <div class="step" :class="{ done: !dirty }"><b>2</b><span><strong>逐镜审核</strong><small>{{ dirty ? '修改自动保存中' : `服务端 v${project.version}` }}</small></span></div>
        <i class="ri-arrow-right-line"></i>
        <div class="step" :class="{ done: project.status === 'EXPORTED' }"><b>3</b><span><strong>合成导出</strong><small>{{ project.status === 'EXPORTED' ? '已有真实成片' : '等待合成' }}</small></span></div>
      </section>

      <section class="intro-card">
        <div>
          <span class="eyebrow">WAO VIDEO WORKFLOW</span>
          <input v-model="project.title" maxlength="200" aria-label="视频标题" />
          <p>720 × 1280 竖屏 · 全程字幕 · 原创程序化轻音乐。无需视频模型或 API Key；合成按 {{ durationText }} 真实运行。</p>
        </div>
        <div class="intro-actions">
          <button class="secondary" :disabled="!dirty || saving || rendering" @click="saveNow">{{ saving ? '保存中…' : '立即保存' }}</button>
          <button :disabled="!renderSupported || dirty || saving || rendering" @click="startRender"><i class="ri-movie-2-line"></i>{{ rendering ? '合成中…' : '开始合成' }}</button>
        </div>
      </section>

      <p v-if="!renderSupported" class="capability-warning"><i class="ri-error-warning-line"></i>当前浏览器不支持 Canvas WebM 合成，请使用最新版 Chrome 或 Edge。分镜编辑和保存仍可正常使用。</p>

      <div class="editor-layout">
        <aside class="scene-list">
          <header><strong>分镜时间线</strong><span>{{ activeScenes.length }} 段启用</span></header>
          <button v-for="(scene, index) in project.scenes" :key="scene.id" class="scene-row" :class="{ active: selectedId === scene.id, off: !scene.enabled }" @click="selectedId = scene.id">
            <em :style="{ background: scene.accent }">{{ index + 1 }}</em>
            <span><strong>{{ scene.title }}</strong><small>{{ scene.enabled ? `${scene.seconds} 秒` : '已停用' }}</small></span>
            <i class="ri-draggable"></i>
          </button>
        </aside>

        <main v-if="selected" class="scene-editor">
          <div class="preview" :style="{ '--scene-accent': selected.accent }">
            <header><span class="preview-logo">W</span><b>WAO</b><em>{{ project.scenes.indexOf(selected) + 1 }} / {{ project.scenes.length }}</em></header>
            <div class="preview-copy"><small>{{ selected.kicker }}</small><h2>{{ selected.title }}</h2><p>{{ selected.body }}</p></div>
            <footer><span></span><small>真实数据 · 人工可控 · 持续进化</small></footer>
          </div>

          <div class="controls">
            <div class="control-head"><strong>镜头设置</strong><label class="switch"><input v-model="selected.enabled" type="checkbox" /><span></span>{{ selected.enabled ? '参与合成' : '已停用' }}</label></div>
            <label>章节眉题<input v-model="selected.kicker" maxlength="40" /></label>
            <label>主标题<textarea v-model="selected.title" maxlength="80" rows="2"></textarea></label>
            <label>全程字幕<textarea v-model="selected.body" maxlength="500" rows="5"></textarea></label>
            <div class="control-grid">
              <label>时长（秒）<input v-model.number="selected.seconds" type="number" min="5" max="60" /></label>
              <label>主题色<input v-model="selected.accent" type="color" /></label>
            </div>
            <div class="order-actions">
              <button :disabled="project.scenes.indexOf(selected) === 0" @click="move(selected, -1)"><i class="ri-arrow-up-line"></i>前移</button>
              <button :disabled="project.scenes.indexOf(selected) === project.scenes.length - 1" @click="move(selected, 1)"><i class="ri-arrow-down-line"></i>后移</button>
            </div>
          </div>
        </main>
      </div>

      <section v-if="renderProgress" class="render-panel">
        <div class="render-head"><div><strong>{{ renderProgress.paused ? '标签页已隐藏，合成暂停' : `正在合成：${renderProgress.sceneTitle}` }}</strong><span>{{ formatTime(renderProgress.elapsedSeconds) }} / {{ formatTime(renderProgress.totalSeconds) }}</span></div><b>{{ renderProgress.progress }}%</b></div>
        <div class="progress"><span :style="{ width: `${renderProgress.progress}%` }"></span></div>
        <p>请保持这个标签页打开；切换到其他标签页会暂停，返回后继续，不会跳过镜头。</p>
        <button class="danger" @click="cancelRender">取消合成</button>
      </section>

      <section v-if="outputUrl" class="output-card">
        <div><strong>真实成片</strong><span>{{ project.outputName }}<template v-if="project.outputByteSize"> · {{ formatBytes(project.outputByteSize) }}</template></span></div>
        <video :src="outputUrl" controls preload="metadata"></video>
        <a :href="outputUrl" :download="project.outputName || 'wao-video.webm'"><i class="ri-download-line"></i>下载 WebM</a>
      </section>
    </template>
  </div>
</template>

<style scoped>
.video-workflow{display:grid;gap:14px}.notice,.error,.capability-warning{padding:10px 12px;display:flex;align-items:center;gap:8px;border-radius:9px;font-size:12px}.notice{color:var(--wao-primary-text);background:var(--wao-primary-soft);border:1px solid var(--wao-primary-line)}.error,.capability-warning{color:var(--wao-danger-strong);background:var(--wao-danger-bg);border:1px solid var(--wao-danger-line)}.error span{flex:1}.error button{border:0;background:transparent;color:inherit;cursor:pointer}.loading{min-height:300px;display:grid;place-items:center;align-content:center;gap:10px;color:var(--wao-text-weak)}.loading span{width:30px;height:30px;border:3px solid var(--wao-primary-line);border-top-color:var(--wao-primary);border-radius:50%;animation:spin .8s linear infinite}.workflow-steps{padding:12px 16px;display:flex;align-items:center;justify-content:center;gap:16px;border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card)}.workflow-steps>i{color:var(--wao-text-weak)}.step{display:flex;align-items:center;gap:9px;color:var(--wao-text-weak)}.step>b{width:27px;height:27px;display:grid;place-items:center;border:1px solid var(--wao-line-control);border-radius:50%;font-size:11px}.step>span{display:grid;gap:2px}.step strong{font-size:11px}.step small{font-size:9px}.step.done{color:var(--wao-primary-text)}.step.done>b{border-color:var(--wao-primary);background:var(--wao-primary);color:white}.intro-card{padding:18px;display:flex;align-items:center;gap:18px;border-radius:14px;background:linear-gradient(120deg,#29243a,#49405f);color:#fff}.intro-card>div:first-child{min-width:0;flex:1;display:grid;gap:7px}.eyebrow{color:#cfc6ef;font-size:9px;font-weight:800;letter-spacing:.12em}.intro-card input{width:100%;padding:0;border:0;background:transparent;color:#fff;font-size:22px;font-weight:800;outline:none}.intro-card p{margin:0;color:#d7d1e1;font-size:10px;line-height:1.6}.intro-actions{display:flex;gap:8px}.intro-actions button,.render-panel button,.output-card a{min-height:36px;padding:0 13px;display:inline-flex;align-items:center;justify-content:center;gap:6px;border:0;border-radius:8px;background:var(--wao-primary);color:#fff;cursor:pointer;font-weight:650;text-decoration:none}.intro-actions .secondary{border:1px solid #766e88;background:transparent}.intro-actions button:disabled{opacity:.45;cursor:not-allowed}.editor-layout{display:grid;grid-template-columns:290px minmax(0,1fr);gap:12px;align-items:start}.scene-list,.scene-editor,.render-panel,.output-card{border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card)}.scene-list{overflow:hidden}.scene-list>header{padding:11px 12px;display:flex;justify-content:space-between;border-bottom:1px solid var(--wao-divider);font-size:11px}.scene-list>header span{color:var(--wao-text-weak);font-size:9px}.scene-row{width:100%;padding:10px 11px;display:flex;align-items:center;gap:9px;border:0;border-bottom:1px solid var(--wao-divider);background:transparent;color:var(--wao-text);cursor:pointer;text-align:left}.scene-row.active{background:var(--wao-primary-soft)}.scene-row.off{opacity:.52}.scene-row em{width:24px;height:24px;display:grid;place-items:center;border-radius:7px;color:#fff;font-size:9px;font-style:normal}.scene-row span{min-width:0;flex:1;display:grid;gap:3px}.scene-row strong{overflow:hidden;font-size:10.5px;text-overflow:ellipsis;white-space:nowrap}.scene-row small{color:var(--wao-text-weak);font-size:9px}.scene-row>i{color:var(--wao-text-weak)}.scene-editor{padding:14px;display:grid;grid-template-columns:minmax(280px,.85fr) minmax(300px,1.15fr);gap:14px}.preview{--scene-accent:#6556D8;min-height:520px;padding:24px;display:flex;flex-direction:column;overflow:hidden;border-radius:16px;background:radial-gradient(circle at 90% 10%,color-mix(in srgb,var(--scene-accent) 25%,transparent),transparent 42%),#f8f7fb;box-shadow:inset 0 0 0 1px #e5e1eb;color:#292432}.preview header{display:flex;align-items:center;gap:8px}.preview-logo{width:34px;height:34px;display:grid;place-items:center;border-radius:9px;background:var(--scene-accent);color:white;font-weight:800}.preview header b{font-size:13px}.preview header em{margin-left:auto;color:#948c9e;font-size:9px;font-style:normal}.preview-copy{margin:auto 0;display:grid;gap:15px}.preview-copy small{color:var(--scene-accent);font-size:9px;font-weight:800;letter-spacing:.08em}.preview-copy h2{margin:0;font-size:28px;line-height:1.25}.preview-copy p{margin:0;color:#655e70;font-size:13px;line-height:1.75}.preview footer{display:grid;gap:15px;color:#8d8695;font-size:8px}.preview footer>span{height:5px;border-radius:99px;background:linear-gradient(90deg,var(--scene-accent) 65%,#e8e4ec 65%)}.controls{display:grid;gap:10px}.control-head{display:flex;align-items:center;justify-content:space-between}.control-head strong{font-size:12px}.controls label{display:grid;gap:5px;color:var(--wao-text-3);font-size:9px}.controls input,.controls textarea{width:100%;padding:9px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text);font:inherit;line-height:1.5}.controls textarea{resize:vertical}.control-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}.control-grid input[type=color]{height:38px;padding:4px}.switch{display:flex!important;align-items:center;gap:6px!important}.switch input{display:none}.switch span{width:30px;height:17px;position:relative;border-radius:99px;background:var(--wao-line-control)}.switch span:after{content:"";width:13px;height:13px;position:absolute;left:2px;top:2px;border-radius:50%;background:#fff;transition:.2s}.switch input:checked+span{background:var(--wao-primary)}.switch input:checked+span:after{left:15px}.order-actions{display:flex;justify-content:flex-end;gap:7px}.order-actions button{padding:7px 9px;border:1px solid var(--wao-line-control);border-radius:7px;background:var(--wao-bg-card);color:var(--wao-text-3);cursor:pointer}.order-actions button:disabled{opacity:.4}.render-panel{padding:15px;display:grid;gap:10px}.render-head{display:flex;align-items:center;gap:10px}.render-head>div{min-width:0;flex:1;display:grid;gap:3px}.render-head strong{font-size:12px}.render-head span,.render-panel p{margin:0;color:var(--wao-text-weak);font-size:9px}.render-head>b{color:var(--wao-primary-text);font-size:18px}.progress{height:8px;overflow:hidden;border-radius:99px;background:var(--wao-bg-sub)}.progress span{height:100%;display:block;background:var(--wao-primary);transition:width .2s}.render-panel .danger{width:max-content;background:var(--wao-danger-strong)}.output-card{padding:15px;display:grid;grid-template-columns:minmax(180px,.4fr) minmax(300px,1fr) auto;align-items:center;gap:14px}.output-card>div{display:grid;gap:4px}.output-card strong{font-size:13px}.output-card span{color:var(--wao-text-weak);font-size:9px}.output-card video{width:100%;max-height:420px;border-radius:10px;background:#18151f}.output-card a{white-space:nowrap}@keyframes spin{to{transform:rotate(360deg)}}.spin{animation:spin .8s linear infinite}
@media(max-width:980px){.scene-editor{grid-template-columns:1fr}.preview{min-height:430px}.output-card{grid-template-columns:1fr}.output-card a{width:max-content}}@media(max-width:760px){.workflow-steps{align-items:stretch;flex-direction:column}.workflow-steps>i{display:none}.intro-card{align-items:stretch;flex-direction:column}.intro-actions{flex-wrap:wrap}.editor-layout{grid-template-columns:1fr}.scene-list{max-height:320px;overflow:auto}.scene-editor{padding:10px}.control-grid{grid-template-columns:1fr}}
</style>
