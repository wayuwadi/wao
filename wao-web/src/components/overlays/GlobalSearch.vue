<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'

const data = useDataStore()
const ui = useUiStore()
const router = useRouter()

const q = ref('')
const inputEl = ref<HTMLInputElement | null>(null)
onMounted(() => nextTick(() => inputEl.value?.focus()))

interface Item {
  icon: string
  iconColor: string
  title: string
  sub: string
  go: () => void
}

function close() {
  ui.closeSearch()
}
function openDoc(id: string) {
  const k = data.kbs.find((kk) => kk.folders.some((f) => f.docs.includes(id)))
  close()
  if (k) router.push({ path: `/kb/${k.id}`, query: { doc: id } })
  else router.push('/knowledge')
}

const groups = computed(() => {
  const kw = q.value.trim().toLowerCase()
  const gs: { label: string; items: Item[] }[] = []
  const ps = data.projects
    .filter((p) => !kw || p.name.toLowerCase().includes(kw) || p.goal.toLowerCase().includes(kw))
    .slice(0, 4)
    .map<Item>((p) => ({
      icon: 'ri-stack-line', iconColor: data.areaColor(p.area), title: p.name,
      sub: `${p.status} · 进度 ${p.progress}%`,
      go: () => { close(); router.push(`/projects/${p.id}`) },
    }))
  const ts = data.tasks
    .filter((t) => !kw || t.title.toLowerCase().includes(kw))
    .slice(0, 4)
    .map<Item>((t) => ({
      icon: 'ri-checkbox-circle-line', iconColor: '#5A5E6B', title: t.title,
      sub: `${data.projectById(t.project)?.name ?? ''} · ${t.status}`,
      go: () => { close(); ui.openTask(t.id) },
    }))
  const ds = Object.values(data.docs)
    .filter((d) => !kw || d.title.toLowerCase().includes(kw))
    .slice(0, 4)
    .map<Item>((d) => ({
      icon: d.auto ? 'ri-flashlight-line' : 'ri-file-text-line', iconColor: '#7A5CC6', title: d.title,
      sub: `更新于 ${d.updated}`, go: () => openDoc(d.id),
    }))
  if (ps.length) gs.push({ label: '项目', items: ps })
  if (ts.length) gs.push({ label: '任务', items: ts })
  if (ds.length) gs.push({ label: '文档', items: ds })
  return gs
})

</script>

<template>
  <div class="mask" @click="close"></div>
  <div class="panel">
    <div class="bar">
      <i class="ri-search-line bar-icon"></i>
      <input
        ref="inputEl"
        v-model="q"
        placeholder="搜索真实项目、任务、文档…"
      />
      <span class="kbd">Esc</span>
    </div>
    <div class="results">
      <template v-for="g in groups" :key="g.label">
        <div class="g-label">{{ g.label }}</div>
        <button v-for="(it, i) in g.items" :key="i" class="result" @click="it.go()">
          <i :class="it.icon" :style="{ color: it.iconColor }"></i>
          <span class="r-main">
            <span class="r-title">{{ it.title }}</span>
            <span class="r-sub">{{ it.sub }}</span>
          </span>
          <i class="ri-corner-down-left-line enter"></i>
        </button>
      </template>
      <div v-if="groups.length === 0" class="empty">没有匹配结果——换个关键词试试</div>
    </div>
    <div class="footer">
      <span class="try">按真实项目名称与目标、任务标题、文档标题匹配</span>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 110;
  animation: waoFade 0.12s ease;
}
.panel {
  position: fixed;
  top: 11vh;
  /* 用 margin 居中而不是 transform：waoPop 动画也动 transform，
     两者叠在一起时居中位移会被覆盖，卡片先偏右出现再跳回中间。 */
  left: 0;
  right: 0;
  margin-inline: auto;
  width: min(620px, calc(100vw - 40px));
  background: var(--wao-bg-card);
  border-radius: 14px;
  z-index: 111;
  box-shadow: 0 24px 64px rgba(16, 18, 28, 0.26);
  animation: waoPop 0.16s ease;
  overflow: hidden;
}
.bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--wao-divider);
}
.bar-icon {
  font-size: 17px;
  color: var(--wao-primary);
}
.bar input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 15px;
  color: var(--wao-text);
  background: none;
  min-width: 0;
}
.kbd {
  font-size: 11px;
  border: 1px solid var(--wao-line-control);
  border-radius: 5px;
  padding: 1px 6px;
  background: var(--wao-bg-page);
  color: var(--wao-text-weak);
}
.results {
  max-height: 50vh;
  overflow-y: auto;
  padding: 8px;
}
.g-label {
  padding: 8px 10px 3px;
  font-size: 11px;
  color: var(--wao-text-weak);
  letter-spacing: 1.5px;
}
.result {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 11px;
  border: none;
  background: none;
  border-radius: 9px;
  padding: 8px 10px;
  cursor: pointer;
  text-align: left;
}
.result:hover {
  background: var(--wao-bg-hover);
}
.result > i {
  font-size: 15px;
  flex-shrink: 0;
}
.r-main {
  flex: 1;
  min-width: 0;
}
.r-title {
  display: block;
  font-size: 13.5px;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.r-sub {
  display: block;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  margin-top: 1px;
}
.enter {
  font-size: 13px;
  color: #d9dce5;
  flex-shrink: 0;
}
.empty {
  padding: 30px;
  text-align: center;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 16px;
  border-top: 1px solid var(--wao-divider);
  background: var(--wao-bg-sub);
}
.try {
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
</style>
