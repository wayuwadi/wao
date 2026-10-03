<script setup lang="ts">
import { projectCompletionLabel } from '@/utils/projectCompletion'
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { fetchSearchDocuments, fetchKbs } from '@/api/board'
import { useSessionStore } from '@/stores/session'
import { contentHit, documentSearchText } from '@/utils/contentSearch'
import type { Doc, KnowledgeBase } from '@/types'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'

import { useNavStore } from '@/stores/nav'
import { NAVIGATION_CATALOG, functionDescription, matchesFunction, menuLocation } from '@/config/navigationCatalog'
import { externalAppItemId, type NavItemId } from '@/config/nav'
const nav = useNavStore()
const data = useDataStore()
const ui = useUiStore()
const router = useRouter()

const session = useSessionStore()
const q = ref('')
const composing = ref(false)
const searchDocs = ref<Doc[]>([])
const searchKbs = ref<KnowledgeBase[]>([])
const documentState = ref<'loading' | 'loaded' | 'error'>('loading')
const documentLimit = ref(50)
let request = 0
async function loadDocuments() {
  const generation = ++request, member = session.userId
  documentState.value = 'loading'; searchDocs.value = []; searchKbs.value = []
  try {
    const [docs, kbs] = await Promise.all([fetchSearchDocuments(), fetchKbs()])
    if (generation !== request || member !== session.userId) return
    if (docs.some(doc => doc.bodyLoaded === false)) throw new Error('正文读取不完整')
    searchDocs.value = docs; searchKbs.value = kbs; documentState.value = 'loaded'
  } catch { if (generation === request) documentState.value = 'error' }
}
watch(() => session.userId, loadDocuments, { immediate: true })
onBeforeUnmount(() => { request++ })
const documentMatches = computed(() => {
  if (documentState.value !== 'loaded') return []
  const query = q.value.trim()
  return searchDocs.value.flatMap(doc => {
    const kb = searchKbs.value.find(k => k.folders.some(f => f.docs.includes(doc.id)))
    if (!kb) return []
    const titleHit = contentHit(doc.title, query), bodyHit = contentHit(documentSearchText(doc), query)
    return query && !titleHit && !bodyHit ? [] : [{ doc, kb, hit: titleHit || bodyHit, field: titleHit ? '标题' : '正文' }]
  })
})
const activeResult = ref(0)
watch(q, () => {
  activeResult.value = 0
  documentLimit.value = 50
})
const inputEl = ref<HTMLInputElement | null>(null)
onMounted(() => {
  void nav.loadMenus()
  void nextTick(() => inputEl.value?.focus())
})

interface Item {
  icon: string
  iconColor: string
  title: string
  sub: string
  snippet?: ReturnType<typeof contentHit>
  go: () => void
}

function close() {
  ui.closeSearch()
}

const groups = computed(() => {
  const kw = q.value.trim().toLowerCase()
  const gs: { label: string; items: Item[] }[] = []
  const functionIds: NavItemId[] = [
    ...NAVIGATION_CATALOG.flatMap((section) => section.items.map((item) => item.id)),
    'navigation',
    ...nav.externalApps.map((app) => externalAppItemId(app.id)),
  ]
  const functions = functionIds
    .filter((id) => matchesFunction(id, nav.itemMeta(id)?.label ?? id, q.value))
    .slice(0, kw ? 12 : 4)
    .map<Item>((id) => {
      const meta = nav.itemMeta(id)!
      return {
        icon: meta.icon,
        iconColor: 'var(--wao-primary)',
        title:
          id === 'navigation'
            ? '全部功能'
            : id === 'workspaces' && meta.label === '工作台'
              ? '链接空间'
              : meta.label,
        sub:
          (functionDescription(id) ?? (id.startsWith('external:') ? '打开接入的外部应用。' : '查找 WAO 的全部功能。')) +
          ' · ' +
          (nav.layoutLoaded ? menuLocation(id, nav.currentLayout()) : '个人位置尚未读取'),
        go: () => {
          close()
          if (meta.openMode === 'NEW_PAGE')
            window.open(
              meta.newPagePath ?? router.resolve(meta.path).href,
              '_blank',
              'noopener,noreferrer',
            )
          else void router.push(meta.path)
        },
      }
    })
  if (functions.length) gs.push({ label: '功能与应用', items: functions })
  const ps = data.projects
    .filter((p) => !kw || p.name.toLowerCase().includes(kw) || p.goal.toLowerCase().includes(kw))
    .slice(0, 4)
    .map<Item>((p) => ({
      icon: 'ri-stack-line',
      iconColor: data.areaColor(p.area),
      title: p.name,
      sub: data.taskLoadState === 'loaded' ? projectCompletionLabel(data.tasksByProject(p.id)) : '任务计数尚未读取',
      go: () => {
        close()
        router.push(`/projects/${p.id}`)
      },
    }))
  const ts = data.tasks
    .filter((t) => !kw || [t.title, t.desc, ...t.subs.map((sub) => `${sub.text} ${sub.desc ?? ''}`)].join(' ').toLowerCase().includes(kw))
    .slice(0, kw ? 50 : 4)
    .map<Item>((t) => ({
      icon: 'ri-checkbox-circle-line',
      iconColor: '#5A5E6B',
      title: t.title,
      sub: `${data.projectById(t.project)?.name ?? ''} · ${t.status}${kw && !t.title.toLowerCase().includes(kw) ? ' · 正文或子任务匹配' : ''}`,
      go: () => {
        close()
        const matchedSub = kw ? t.subs.find(sub => `${sub.text} ${sub.desc ?? ''}`.toLowerCase().includes(kw)) : undefined
        ui.openTask(t.id, matchedSub?.id)
      },
    }))
  const ds = documentMatches.value.slice(0, kw ? documentLimit.value : 4).map<Item>(({ doc, kb, hit, field }) => ({
    icon: kb.notes ? 'ri-sticky-note-line' : 'ri-file-text-line', iconColor: '#7A5CC6', title: doc.title,
    sub: `${kb.notes ? '小记' : '文档'} · ${kb.name} · ${kw ? `${field}命中` : '最近更新'}`, snippet: hit,
    go: () => {
      close()
      void router.push({ path: kb.notes ? '/notes' : `/kb/${kb.id}`,
        query: { [kb.notes ? 'note' : 'doc']: doc.id, find: q.value.trim(), findRequest: String(Date.now()), match: field === '标题' ? 'title' : 'body' } })
    },
  }))
  if (ps.length) gs.push({ label: '项目', items: ps })
  if (ts.length) gs.push({ label: '任务', items: ts })
  if (ds.length) gs.push({ label: `小记与文档（${documentMatches.value.length} 条）`, items: ds })
  return gs
})

const results = computed(() => groups.value.flatMap((group) => group.items))
function moveResult(direction: number) {
  const count = results.value.length
  if (count) activeResult.value = (activeResult.value + direction + count) % count
}
function openSelected(event: KeyboardEvent) {
  if (composing.value || event.isComposing) return
  results.value[activeResult.value]?.go()
}
</script>

<template>
  <div class="mask" @click="close"></div>
  <div class="panel">
    <div class="bar">
      <i class="ri-search-line bar-icon"></i>
      <input
        ref="inputEl"
        v-model="q"
        @compositionstart="composing = true"
        @compositionend="composing = false"
        @keydown.down.prevent="!composing && moveResult(1)"
        @keydown.up.prevent="!composing && moveResult(-1)"
        @keydown.enter.prevent="openSelected"
        placeholder="搜索功能、项目、任务、小记、文档正文…"
      />
      <span class="kbd">Esc</span>
    </div>
    <div v-if="nav.layoutError" class="g-label" role="alert">
      {{ nav.layoutError }} <button @click="nav.loadMenus(true)">重试导航</button>
    </div>
    <p v-if="documentState === 'loading'" class="g-label" role="status">正在读取可访问的小记与文档全文…</p>
    <p v-else-if="documentState === 'error'" class="g-label" role="alert">小记与文档读取失败，尚不能确认是否有匹配。<button @click="loadDocuments">重试正文读取</button></p>
    <div class="results">
      <template v-for="g in groups" :key="g.label">
        <div class="g-label">{{ g.label }}</div>
        <button
          v-for="(it, i) in g.items"
          :key="i"
          class="result"
          :class="{ selected: results[activeResult] === it }"
          @click="it.go()"
        >
          <i :class="it.icon" :style="{ color: it.iconColor }"></i>
          <span class="r-main">
            <span class="r-title">{{ it.title }}</span>
            <span class="r-sub">{{ it.sub }}</span>
            <span v-if="it.snippet" class="r-snippet">{{ it.snippet.before }}<mark>{{ it.snippet.match }}</mark>{{ it.snippet.after }}</span>
          </span>
          <i class="ri-corner-down-left-line enter"></i>
        </button>
      </template>
      <button v-if="q.trim() && documentMatches.length > documentLimit" class="g-label" @click="documentLimit += 50">显示更多内容（还有 {{ documentMatches.length - documentLimit }} 条）</button>
      <div v-if="groups.length === 0 && documentState === 'loaded'" class="empty">没有匹配结果——换个关键词试试</div>
    </div>
    <div class="footer">
      <span class="try">正文检索已保存的小记与文档，不含归档与回收站；编辑草稿保持不变</span>
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
  color: var(--wao-primary-text);
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
.result:hover,
.result.selected {
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
.r-snippet { font-size: 12px; color: var(--wao-text-weak); white-space: pre-wrap; overflow-wrap: anywhere; }
.r-snippet mark { background: #fff0ae; color: #604a08; }
</style>
