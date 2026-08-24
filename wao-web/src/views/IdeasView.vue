<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { apiCreateProject, fetchProjects } from '@/api/board'
import { convertIdeaToProject, fetchIdeas } from '@/api/ideas'
import { useSessionStore } from '@/stores/session'
import type { Idea, Project } from '@/types'

const router = useRouter()
const session = useSessionStore()
const projects = ref<Project[]>([])
const historicalIdeas = ref<Idea[]>([])
const loading = ref(true)
const error = ref('')
const search = ref('')
const owner = ref('')
const creating = ref(false)
const createState = ref<'idle' | 'saved' | 'error'>('idle')
const converting = ref('')
const convertTargets = reactive<Record<string, string>>({})
const quick = reactive({ title: '', goal: '' })

const planningProjects = computed(() => projects.value.filter((project) => {
  const q = search.value.trim().toLowerCase()
  return project.status === '规划中'
    && (!owner.value || project.owner === owner.value)
    && (!q || `${project.name} ${project.goal}`.toLowerCase().includes(q))
}))
const activeCount = computed(() => projects.value.filter((project) => ['进行中', '有风险'].includes(project.status)).length)
const pendingHistorical = computed(() => historicalIdeas.value.filter(
  (idea) => !idea.artifacts.some((artifact) => artifact.kind === 'LEGACY_ARCHIVE'),
))
const memberName = (id: string) => session.members.find((member) => member.id === id)?.name ?? id

watch(quick, (value) => {
  if (value.title || value.goal) localStorage.setItem('wao-project-idea-draft', JSON.stringify(value))
  else localStorage.removeItem('wao-project-idea-draft')
}, { deep: true })

async function load() {
  loading.value = true
  try {
    const [projectResult, ideaResult] = await Promise.all([fetchProjects(), fetchIdeas()])
    projects.value = projectResult
    historicalIdeas.value = ideaResult
  } catch (e) {
    error.value = e instanceof Error ? e.message : '想法箱加载失败'
  } finally {
    loading.value = false
  }
}

async function submitQuick() {
  if (!quick.title.trim() || creating.value) return
  creating.value = true
  createState.value = 'idle'
  try {
    const created = await apiCreateProject({
      name: quick.title.trim(), area: 'career', goal: quick.goal.trim(), visAll: true, announce: false,
    })
    projects.value.unshift(created)
    quick.title = ''
    quick.goal = ''
    createState.value = 'saved'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '记录失败，内容仍保留在本地'
    createState.value = 'error'
  } finally {
    creating.value = false
  }
}

async function convert(idea: Idea) {
  if (converting.value) return
  converting.value = idea.id
  error.value = ''
  try {
    const target = await convertIdeaToProject(idea.id, convertTargets[idea.id] || undefined)
    if (!projects.value.some((project) => project.id === target.id)) projects.value.unshift(target)
    historicalIdeas.value = await fetchIdeas()
    await router.push(`/projects/${target.id}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '历史点子归并失败'
  } finally {
    converting.value = ''
  }
}

onMounted(async () => {
  const cached = localStorage.getItem('wao-project-idea-draft')
  if (cached) {
    try { Object.assign(quick, JSON.parse(cached)) } catch { localStorage.removeItem('wao-project-idea-draft') }
  }
  await session.refreshDirectory()
  await load()
})
</script>

<template>
  <main class="idea-inbox">
    <header>
      <div><h1>点子库</h1><p>项目里的想法箱 · {{ planningProjects.length }} 个待推进 · {{ activeCount }} 个进行中</p></div>
      <button class="all-projects" @click="router.push('/projects')"><i class="ri-stack-line"></i>全部项目</button>
    </header>

    <form class="capture" @submit.prevent="submitQuick">
      <div class="capture-title"><i class="ri-lightbulb-flash-line"></i><div><b>快速记下想法</b><span>先收进项目，开始推进时再通知团队</span></div></div>
      <input v-model="quick.title" required maxlength="128" placeholder="例如：做一个可以持续迭代的桌面应用" />
      <input v-model="quick.goal" maxlength="512" placeholder="期望最终达到什么效果（可选）" />
      <button :disabled="creating"><i :class="creating ? 'ri-loader-4-line spin' : 'ri-add-line'"></i>{{ creating ? '记录中…' : '记下来' }}</button>
      <small v-if="createState === 'saved'" class="saved">已静默保存，团队尚未收到通知</small>
      <small v-else-if="createState === 'error'" class="failed">保存失败，草稿仍在，可重试</small>
    </form>

    <section class="toolbar">
      <label><i class="ri-search-line"></i><input v-model="search" type="search" placeholder="搜索项目想法或目标" /></label>
      <select v-model="owner"><option value="">全部负责人</option><option v-for="member in session.members" :key="member.id" :value="member.id">{{ member.name }}</option></select>
    </section>
    <p v-if="error" class="error">{{ error }} <button @click="error = ''">关闭</button></p>
    <p v-if="loading" class="empty">正在加载真实项目…</p>

    <section v-else>
      <div class="section-head"><div><h2>项目想法箱</h2><p>这里的每一条从现在开始就是同一个长期项目，不会因一次 AI Run 自动结束。</p></div></div>
      <div v-if="planningProjects.length" class="grid">
        <button v-for="project in planningProjects" :key="project.id" class="project-card" @click="router.push(`/projects/${project.id}`)">
          <div><span class="icon"><i class="ri-lightbulb-line"></i></span><b>{{ project.name }}</b><span class="status">规划中</span></div>
          <p>{{ project.goal || '还没有补充最终目标' }}</p>
          <footer><span><i class="ri-user-3-line"></i>{{ memberName(project.owner) }}</span><span>打开项目开始第一轮 <i class="ri-arrow-right-line"></i></span></footer>
        </button>
      </div>
      <div v-else class="empty-card"><i class="ri-inbox-2-line"></i><b>想法箱是空的</b><span>上面记下一句话，就会成为一个可长期迭代的规划中项目。</span></div>
    </section>

    <section v-if="pendingHistorical.length" class="legacy">
      <div class="section-head"><div><h2>历史点子待归并</h2><p>旧八阶段记录不会删除；归并后会生成项目历史文档，原文档和链接保持不变。</p></div></div>
      <article v-for="idea in pendingHistorical" :key="idea.id">
        <div><b>{{ idea.title }}</b><p>{{ idea.targetOutcome || idea.rawIdea || idea.problem || '没有补充说明' }}</p></div>
        <select v-model="convertTargets[idea.id]"><option value="">自动选择或新建项目</option><option v-for="project in projects" :key="project.id" :value="project.id">并入：{{ project.name }}</option></select>
        <button :disabled="converting === idea.id" @click="convert(idea)">{{ converting === idea.id ? '归并中…' : '归并并打开项目' }}</button>
      </article>
    </section>
  </main>
</template>

<style scoped>
.idea-inbox{max-width:var(--wao-content-max);margin:0 auto;padding:16px 24px 48px;color:var(--wao-text)}header,.capture,.toolbar,.section-head,.project-card>div,.project-card footer,.legacy article{display:flex;align-items:center}header{gap:16px}header h1,.section-head h2{margin:0;font-size:20px}header p,.section-head p{margin:3px 0 0;color:var(--wao-text-weak);font-size:12px}.all-projects{margin-left:auto}.capture{position:relative;margin:16px 0 12px;padding:14px;gap:10px;border:1px solid var(--wao-line);border-radius:14px;background:var(--wao-bg-card);box-shadow:var(--wao-shadow-pop)}.capture-title{display:flex;align-items:center;gap:10px;min-width:210px}.capture-title>i{width:36px;height:36px;display:grid;place-items:center;border-radius:10px;background:var(--wao-primary-soft);color:var(--wao-primary-text);font-size:19px}.capture-title span{display:block;color:var(--wao-text-weak);font-size:11px}.capture input,.toolbar input,.toolbar select,.legacy select{box-sizing:border-box;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text);padding:9px 10px}.capture input{flex:1;min-width:180px}.capture button,.legacy button,.all-projects{min-height:34px;border:0;border-radius:8px;padding:0 13px;background:var(--wao-primary);color:#fff;cursor:pointer}.all-projects{background:var(--wao-bg-card);color:var(--wao-text);border:1px solid var(--wao-line-control)}button:disabled{opacity:.6;cursor:wait}.capture small{position:absolute;right:14px;bottom:-20px;font-size:11px}.saved{color:#2c7a63}.failed{color:#b43b47}.toolbar{margin:24px 0 14px;gap:10px}.toolbar label{position:relative;flex:1}.toolbar label i{position:absolute;left:11px;top:10px;color:var(--wao-text-faint)}.toolbar input{width:100%;padding-left:32px}.section-head{justify-content:space-between;margin:18px 0 10px}.section-head h2{font-size:15px}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.project-card{border:1px solid var(--wao-line);border-radius:13px;background:var(--wao-bg-card);padding:15px;text-align:left;color:var(--wao-text);cursor:pointer}.project-card:hover{border-color:var(--wao-primary);transform:translateY(-1px)}.project-card>div{gap:9px}.project-card .icon{width:32px;height:32px;display:grid;place-items:center;border-radius:9px;background:var(--wao-primary-soft);color:var(--wao-primary-text)}.project-card b{flex:1}.status{font-size:10px;color:#8a641c;background:#fff3d7;padding:3px 7px;border-radius:999px}.project-card p{min-height:38px;color:var(--wao-text-weak);font-size:12px;line-height:1.6}.project-card footer{justify-content:space-between;color:var(--wao-text-faint);font-size:10px}.legacy{margin-top:28px;border-top:1px solid var(--wao-divider);padding-top:8px}.legacy article{gap:12px;padding:12px 14px;border:1px solid var(--wao-line);border-radius:11px;background:var(--wao-bg-card);margin-bottom:8px}.legacy article>div{flex:1;min-width:0}.legacy article p{margin:3px 0;color:var(--wao-text-weak);font-size:11px}.legacy select{max-width:260px}.empty,.empty-card{color:var(--wao-text-weak);text-align:center}.empty-card{min-height:150px;display:grid;place-content:center;gap:6px;border:1px dashed var(--wao-line-control);border-radius:13px}.empty-card i{font-size:28px}.error{padding:10px 12px;border-radius:8px;background:#fff0f1;color:#a21f31}.error button{float:right;border:0;background:none;color:inherit}.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:900px){.grid{grid-template-columns:repeat(2,minmax(0,1fr))}.capture{flex-wrap:wrap}.capture-title{width:100%}.capture input{flex-basis:calc(50% - 70px)}}
@media(max-width:620px){.idea-inbox{padding:12px 12px 36px}.grid{grid-template-columns:1fr}.capture{display:grid}.capture input{width:100%}.capture small{position:static}.toolbar{align-items:stretch}.toolbar select{max-width:130px}.legacy article{align-items:stretch;flex-direction:column}.legacy select{max-width:none}.legacy button{width:100%}}
</style>
