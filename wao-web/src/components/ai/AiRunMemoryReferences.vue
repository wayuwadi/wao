<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { ProjectMemoryReference } from '@/types'

const props = defineProps<{
  reads?: ProjectMemoryReference[]
  writes?: ProjectMemoryReference[]
}>()
const router = useRouter()
const groups = computed(() => [
  { key: 'reads', title: '本次读取', hint: '领取任务时实际注入的不可信项目引用', items: props.reads ?? [] },
  { key: 'writes', title: '本次写入', hint: '成功合并、部署并验收后实际沉淀', items: props.writes ?? [] },
].filter((group) => group.items.length))

function openMemory(memory: ProjectMemoryReference) {
  router.push({ path: `/projects/${memory.projectId}`, query: { tab: '记忆', memory: memory.id } })
}

function openSource(memory: ProjectMemoryReference) {
  if (!memory.sourceTaskId) return
  router.push({
    path: `/projects/${memory.projectId}`,
    query: { task: memory.sourceTaskId, run: memory.sourceRunId || undefined },
  })
}
</script>

<template>
  <section v-if="groups.length" class="run-memories">
    <div v-for="group in groups" :key="group.key" class="memory-group">
      <header><b>{{ group.title }}</b><span>{{ group.hint }}</span></header>
      <div class="memory-chips">
        <article v-for="memory in group.items" :key="memory.id">
          <button class="memory-title" @click="openMemory(memory)"><i class="ri-brain-line"></i>{{ memory.title }}</button>
          <p>{{ memory.content }}</p>
          <footer>
            <button v-if="memory.sourceTaskId" @click="openSource(memory)"><i class="ri-route-line"></i>来源 Task / Run</button>
            <a v-for="link in memory.evidenceLinks" :key="link" :href="link" target="_blank" rel="noopener"><i class="ri-external-link-line"></i>证据</a>
          </footer>
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
.run-memories { display:grid;gap:11px;margin-top:12px; }.memory-group { border:1px solid var(--wao-line);border-radius:11px;background:var(--wao-bg-sub);padding:11px; }.memory-group>header { display:flex;align-items:baseline;gap:8px;margin-bottom:8px; }.memory-group>header b { font-size:12px; }.memory-group>header span { color:var(--wao-text-weak);font-size:10px; }.memory-chips { display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px; }.memory-chips article { min-width:0;border:1px solid var(--wao-line);border-radius:9px;background:var(--wao-bg-card);padding:9px; }.memory-title { max-width:100%;padding:0;border:0;background:none;color:var(--wao-primary-text);font-weight:650;font-size:11.5px;cursor:pointer;display:flex;align-items:center;gap:5px;text-align:left; }.memory-chips p { margin:6px 0;color:var(--wao-text-3);font-size:10.5px;line-height:1.55;white-space:pre-wrap;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:3;overflow:hidden;overflow-wrap:anywhere; }.memory-chips footer { display:flex;gap:9px;flex-wrap:wrap; }.memory-chips footer button,.memory-chips footer a { padding:0;border:0;background:none;color:var(--wao-text-weak);font-size:10px;text-decoration:none;cursor:pointer;display:inline-flex;gap:3px;align-items:center; }
@media(max-width:640px){.memory-group>header{align-items:flex-start;flex-direction:column;gap:2px}.memory-chips{grid-template-columns:1fr}}
</style>
