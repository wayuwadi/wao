<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { createVideo, getVideoCenter, retryVideo } from '@/api/video'
import { formatVideoSize, isVideoInFlight, videoStatusLabel } from '@/videoStatus'
import type { VideoCenter, VideoGeneration } from '@/types'

const DRAFT_KEY = 'wao-agent-video-draft-v1'
const center = ref<VideoCenter | null>(null)
const selectedId = ref('')
const loading = ref(true)
const submitting = ref(false)
const retryingId = ref('')
const error = ref('')
const message = ref('')
const draftState = ref<'saved' | 'saving'>('saved')
const form = reactive({ prompt: '', model: 'sora-2', seconds: 4, size: '720x1280' })
let pollTimer: number | undefined
let draftTimer: number | undefined

const selected = computed(() => center.value?.tasks.find((task) => task.id === selectedId.value) ?? null)

function restoreDraft() {
  const raw = window.localStorage.getItem(DRAFT_KEY)
  if (!raw) return
  try {
    const saved = JSON.parse(raw) as Partial<typeof form>
    if (typeof saved.prompt === 'string') form.prompt = saved.prompt
    if (typeof saved.model === 'string') form.model = saved.model
    if (typeof saved.seconds === 'number') form.seconds = saved.seconds
    if (typeof saved.size === 'string') form.size = saved.size
  } catch {
    window.localStorage.removeItem(DRAFT_KEY)
  }
}

watch(form, () => {
  draftState.value = 'saving'
  if (draftTimer) window.clearTimeout(draftTimer)
  draftTimer = window.setTimeout(() => {
    window.localStorage.setItem(DRAFT_KEY, JSON.stringify(form))
    draftState.value = 'saved'
  }, 250)
}, { deep: true })

async function refresh(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  try {
    center.value = await getVideoCenter()
    if (!selectedId.value && center.value.tasks[0]) selectedId.value = center.value.tasks[0].id
    if (!center.value.capability.models.includes(form.model)) form.model = center.value.capability.models[0] || 'sora-2'
    if (!center.value.capability.durations.includes(form.seconds)) form.seconds = center.value.capability.durations[0] || 4
    if (!center.value.capability.sizes.includes(form.size)) form.size = center.value.capability.sizes[0] || '720x1280'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '视频工作台读取失败'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!center.value?.capability.available || !form.prompt.trim()) return
  submitting.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await createVideo({ ...form, prompt: form.prompt.trim() })
    form.prompt = ''
    window.localStorage.removeItem(DRAFT_KEY)
    await refresh(true)
    selectedId.value = task.id
    message.value = task.status === 'FAILED' ? '上游拒绝了本次任务，可查看原因后重试' : '已提交真实视频生成任务'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '视频任务提交失败，请重试'
  } finally {
    submitting.value = false
  }
}

async function retry(task: VideoGeneration) {
  retryingId.value = task.id
  error.value = ''
  try {
    await retryVideo(task.id)
    await refresh(true)
    selectedId.value = task.id
    message.value = '已重新提交失败任务'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '重试失败'
  } finally {
    retryingId.value = ''
  }
}

onMounted(async () => {
  restoreDraft()
  await refresh()
  pollTimer = window.setInterval(() => {
    if (center.value?.tasks.some((task) => isVideoInFlight(task.status))) void refresh(true)
  }, 5000)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  if (draftTimer) window.clearTimeout(draftTimer)
})
</script>

<template>
  <div class="video-studio">
    <div v-if="message" class="notice"><i class="ri-information-line"></i>{{ message }}</div>
    <div v-if="error" class="error" role="alert">
      <i class="ri-error-warning-line"></i><span>{{ error }}</span><button @click="refresh()">重试读取</button>
    </div>
    <div v-if="loading" class="loading"><span></span>正在读取真实视频任务…</div>

    <template v-else-if="center">
      <section class="sunset" :class="{ unavailable: !center.capability.available }">
        <i class="ri-video-ai-line"></i>
        <div><strong>{{ center.capability.provider }} 视频生成</strong><span>{{ center.capability.note }}</span></div>
        <time>停用日 {{ center.capability.shutdownDate }}</time>
      </section>

      <section class="create-card">
        <div class="create-head">
          <div><strong>描述你想看到的镜头</strong><span>建议写清景别、主体、动作、环境和光线；真实调用可能产生费用。</span></div>
          <small>{{ draftState === 'saving' ? '草稿保存中…' : '草稿已保存在本机' }}</small>
        </div>
        <textarea v-model="form.prompt" rows="5" maxlength="32000"
                  placeholder="例如：广角镜头，一辆青绿色跑车驶过沙漠公路，正午硬光，镜头平稳跟拍…"
                  :disabled="!center.capability.available || submitting"></textarea>
        <div class="options">
          <label>模型<select v-model="form.model" :disabled="!center.capability.available || submitting">
            <option v-for="model in center.capability.models" :key="model" :value="model">{{ model }}</option>
          </select></label>
          <label>时长<select v-model.number="form.seconds" :disabled="!center.capability.available || submitting">
            <option v-for="seconds in center.capability.durations" :key="seconds" :value="seconds">{{ seconds }} 秒</option>
          </select></label>
          <label>画幅<select v-model="form.size" :disabled="!center.capability.available || submitting">
            <option v-for="size in center.capability.sizes" :key="size" :value="size">{{ formatVideoSize(size) }}</option>
          </select></label>
          <button :disabled="!center.capability.available || submitting || !form.prompt.trim()" @click="submit">
            <i class="ri-movie-2-line"></i>{{ submitting ? '提交中…' : '生成视频' }}
          </button>
        </div>
      </section>

      <div class="workspace">
        <aside class="task-list">
          <div class="panel-head"><strong>生成历史</strong><button title="刷新" @click="refresh()"><i class="ri-refresh-line"></i></button></div>
          <button v-for="task in center.tasks" :key="task.id" class="task" :class="{ active: selectedId === task.id }"
                  @click="selectedId = task.id">
            <span><strong>{{ task.prompt }}</strong><small>{{ task.model }} · {{ task.seconds }} 秒 · {{ videoStatusLabel(task.status) }}</small></span>
            <em>{{ task.progress }}%</em>
          </button>
          <div v-if="!center.tasks.length" class="empty"><i class="ri-movie-line"></i><b>还没有视频</b><span>提交第一条提示词后，真实任务会显示在这里。</span></div>
        </aside>

        <main v-if="selected" class="preview">
          <div class="preview-head">
            <div><strong>生成效果</strong><span :class="`status ${selected.status.toLowerCase()}`">{{ videoStatusLabel(selected.status) }}</span></div>
            <small>第 {{ selected.attempt }} 次尝试 · {{ formatVideoSize(selected.size) }} · {{ selected.seconds }} 秒</small>
          </div>
          <div v-if="isVideoInFlight(selected.status)" class="progress-card">
            <span><i :style="{ width: `${Math.max(4, selected.progress)}%` }"></i></span>
            <b>{{ selected.progress }}%</b><small>视频生成通常需要几分钟，离开页面也会继续。</small>
          </div>
          <video v-else-if="selected.status === 'COMPLETED' && selected.contentUrl"
                 :key="`${selected.id}-${selected.updatedAt}`" controls preload="metadata" :src="selected.contentUrl">
            当前浏览器不支持 MP4 播放。
          </video>
          <div v-else-if="selected.status === 'FAILED'" class="failed-card">
            <i class="ri-error-warning-line"></i><b>本次生成失败</b><p>{{ selected.errorMessage || '上游未返回具体原因' }}</p>
            <button :disabled="retryingId === selected.id || !center.capability.available" @click="retry(selected)">
              {{ retryingId === selected.id ? '重试中…' : '按原参数重试' }}
            </button>
          </div>
          <div class="prompt-detail"><strong>提示词</strong><p>{{ selected.prompt }}</p></div>
        </main>
        <main v-else class="preview empty"><i class="ri-movie-line"></i><b>等待第一条生成任务</b><span>不会展示示例或伪造效果。</span></main>
      </div>
    </template>
  </div>
</template>

<style scoped>
.video-studio{display:grid;gap:14px}.notice,.error{padding:10px 12px;display:flex;align-items:center;gap:8px;border-radius:9px;font-size:12px}.notice{color:var(--wao-primary-text);background:var(--wao-primary-soft);border:1px solid var(--wao-primary-line)}.error{color:var(--wao-danger-strong);background:var(--wao-danger-bg);border:1px solid var(--wao-danger-line)}.error span{flex:1}.error button{border:0;background:transparent;color:inherit;cursor:pointer}.loading{min-height:280px;display:grid;place-items:center;align-content:center;gap:10px;color:var(--wao-text-weak)}.loading span{width:28px;height:28px;border:3px solid var(--wao-primary-line);border-top-color:var(--wao-primary);border-radius:50%;animation:spin .8s linear infinite}.sunset{padding:12px 14px;display:flex;align-items:center;gap:10px;border:1px solid #f1c26e;border-radius:11px;background:#fff9ec;color:#80510b}.sunset.unavailable{border-color:var(--wao-danger-line);background:var(--wao-danger-bg);color:var(--wao-danger-strong)}.sunset>i{font-size:22px}.sunset>div{flex:1;display:grid;gap:3px}.sunset strong{font-size:12px}.sunset span,.sunset time{font-size:10px}.create-card{padding:16px;display:grid;gap:12px;border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card)}.create-head{display:flex;justify-content:space-between;gap:14px}.create-head>div{display:grid;gap:4px}.create-head strong{font-size:13px}.create-head span,.create-head small{color:var(--wao-text-weak);font-size:10px}.create-card textarea,.create-card select{width:100%;padding:9px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text);font:inherit}.create-card textarea{resize:vertical;line-height:1.6}.options{display:grid;grid-template-columns:1fr 1fr 1.3fr auto;align-items:end;gap:9px}.options label{display:grid;gap:5px;color:var(--wao-text-3);font-size:10px}.options button,.failed-card button{min-height:36px;padding:0 14px;border:0;border-radius:8px;background:var(--wao-primary);color:#fff;cursor:pointer;font-weight:650}.options button:disabled,.failed-card button:disabled{opacity:.45;cursor:not-allowed}.workspace{display:grid;grid-template-columns:300px minmax(0,1fr);gap:12px;align-items:start}.task-list,.preview{border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card)}.panel-head{padding:11px 12px;display:flex;justify-content:space-between;border-bottom:1px solid var(--wao-divider)}.panel-head button{border:0;background:transparent;color:var(--wao-text-weak);cursor:pointer}.task{width:100%;padding:10px 11px;display:flex;align-items:center;gap:8px;border:0;border-bottom:1px solid var(--wao-divider);background:transparent;color:var(--wao-text);cursor:pointer;text-align:left}.task.active{background:var(--wao-primary-soft)}.task>span{min-width:0;flex:1;display:grid;gap:3px}.task strong{overflow:hidden;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.task small{color:var(--wao-text-weak);font-size:9.5px}.task em{color:var(--wao-primary-text);font-size:10px;font-style:normal}.empty{min-height:180px;padding:28px;display:grid;place-items:center;align-content:center;gap:6px;color:var(--wao-text-weak);font-size:11px;text-align:center}.empty i{font-size:28px}.preview{padding:15px;display:grid;gap:14px}.preview-head{display:flex;justify-content:space-between;gap:10px}.preview-head>div{display:flex;align-items:center;gap:8px}.preview-head small{color:var(--wao-text-weak);font-size:10px}.status{padding:3px 6px;border-radius:999px;background:var(--wao-primary-soft);color:var(--wao-primary-text);font-size:9px}.status.completed{background:var(--wao-success-bg);color:var(--wao-success-strong)}.status.failed{background:var(--wao-danger-bg);color:var(--wao-danger-strong)}.progress-card{min-height:260px;display:grid;place-items:center;align-content:center;gap:9px;background:var(--wao-bg-sub);border-radius:10px}.progress-card>span{width:min(360px,80%);height:8px;overflow:hidden;border-radius:99px;background:var(--wao-primary-soft)}.progress-card>span i{height:100%;display:block;border-radius:inherit;background:var(--wao-primary);transition:width .3s}.progress-card b{font-size:20px}.progress-card small{color:var(--wao-text-weak)}video{width:100%;max-height:560px;border-radius:10px;background:#101114}.failed-card{min-height:260px;padding:24px;display:grid;place-items:center;align-content:center;gap:9px;border-radius:10px;background:var(--wao-danger-bg);color:var(--wao-danger-strong);text-align:center}.failed-card>i{font-size:30px}.failed-card p{max-width:620px;margin:0;font-size:11px;line-height:1.6}.prompt-detail{padding-top:12px;border-top:1px solid var(--wao-divider)}.prompt-detail strong{font-size:11px}.prompt-detail p{margin:6px 0 0;color:var(--wao-text-3);font-size:11px;line-height:1.65;white-space:pre-wrap}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:900px){.workspace{grid-template-columns:1fr}.task-list{max-height:260px;overflow:auto}.options{grid-template-columns:1fr 1fr}.options button{grid-column:1/-1}}@media(max-width:600px){.sunset,.create-head,.preview-head{align-items:flex-start;flex-direction:column}.options{grid-template-columns:1fr}.options button{grid-column:auto}.preview{padding:11px}}
</style>
