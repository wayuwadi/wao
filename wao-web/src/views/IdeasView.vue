<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchProjects } from '@/api/board'
import { convertIdeaToProject, fetchIdeas } from '@/api/ideas'
import type { Idea, Project } from '@/types'

const router = useRouter()
const projects = ref<Project[]>([])
const historicalIdeas = ref<Idea[]>([])
const loading = ref(true)
const error = ref('')
const converting = ref('')
const convertTargets = reactive<Record<string, string>>({})

const pendingHistorical = computed(() => historicalIdeas.value.filter(
  (idea) => !idea.artifacts.some((artifact) => artifact.kind === 'LEGACY_ARCHIVE'),
))

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

onMounted(load)
</script>

<template>
  <main class="idea-inbox">
    <header>
      <div><h1>历史点子归并</h1><p>新想法已融合进任务与项目，这里只处理旧版点子档案。</p></div>
      <button class="all-projects" @click="router.push('/projects')"><i class="ri-stack-line"></i>全部项目</button>
    </header>
    <p v-if="error" class="error">{{ error }} <button @click="error = ''">关闭</button></p>
    <p v-if="loading" class="empty">正在加载历史点子…</p>

    <section v-if="pendingHistorical.length" class="legacy">
      <div class="section-head"><div><h2>历史点子待归并</h2><p>旧八阶段记录不会删除；归并后会生成项目历史文档，原文档和链接保持不变。</p></div></div>
      <article v-for="idea in pendingHistorical" :key="idea.id">
        <div><b>{{ idea.title }}</b><p>{{ idea.targetOutcome || idea.rawIdea || idea.problem || '没有补充说明' }}</p></div>
        <select v-model="convertTargets[idea.id]"><option value="">自动选择或新建项目</option><option v-for="project in projects" :key="project.id" :value="project.id">并入：{{ project.name }}</option></select>
        <button :disabled="converting === idea.id" @click="convert(idea)">{{ converting === idea.id ? '归并中…' : '归并并打开项目' }}</button>
      </article>
    </section>
    <section v-else-if="!loading" class="empty-card">
      <i class="ri-checkbox-circle-line"></i><b>没有待归并的历史点子</b>
      <span>新想法请创建为一句任务；完成一轮并进入验收后，可在任务详情升级为项目。</span>
      <button class="all-projects" @click="router.push('/projects')">返回项目</button>
    </section>
  </main>
</template>

<style scoped>
.idea-inbox{max-width:var(--wao-content-max);margin:0 auto;padding:16px 24px 48px;color:var(--wao-text)}header,.section-head,.legacy article{display:flex;align-items:center}header{gap:16px}header h1,.section-head h2{margin:0;font-size:20px}header p,.section-head p{margin:3px 0 0;color:var(--wao-text-weak);font-size:12px}.all-projects,.legacy button{min-height:34px;border-radius:8px;padding:0 13px;cursor:pointer}.all-projects{margin-left:auto;background:var(--wao-bg-card);color:var(--wao-text);border:1px solid var(--wao-line-control)}.legacy button{border:0;background:var(--wao-primary);color:#fff}.section-head{margin:18px 0 10px}.section-head h2{font-size:15px}.legacy{margin-top:20px}.legacy article{gap:12px;padding:12px 14px;border:1px solid var(--wao-line);border-radius:11px;background:var(--wao-bg-card);margin-bottom:8px}.legacy article>div{flex:1;min-width:0}.legacy article p{margin:3px 0;color:var(--wao-text-weak);font-size:11px}.legacy select{box-sizing:border-box;max-width:260px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text);padding:9px 10px}.empty,.empty-card{color:var(--wao-text-weak);text-align:center}.empty-card{min-height:180px;display:grid;place-content:center;justify-items:center;gap:8px;border:1px dashed var(--wao-line-control);border-radius:13px;margin-top:20px}.empty-card i{font-size:28px}.empty-card .all-projects{margin:4px 0 0}.error{padding:10px 12px;border-radius:8px;background:var(--wao-danger-bg);color:var(--wao-danger-strong)}.error button{float:right;border:0;background:none;color:inherit}button:disabled{opacity:.6;cursor:wait}
@media(max-width:620px){.idea-inbox{padding:12px 12px 36px}.legacy article{align-items:stretch;flex-direction:column}.legacy select{max-width:none}.legacy button{width:100%}}
</style>
