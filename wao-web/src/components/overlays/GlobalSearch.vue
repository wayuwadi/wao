<script setup lang="ts">
import { projectCompletionLabel } from '@/utils/projectCompletion'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useSearchDocumentsStore } from '@/stores/searchDocuments'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { fileDisplayName } from '@/utils/files'
import { contentHit, documentSearchText } from '@/utils/contentSearch'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'

import { useNavStore } from '@/stores/nav'
import {
  NAVIGATION_CATALOG,
  functionDescription,
  matchesFunction,
  menuLocation,
} from '@/config/navigationCatalog'
import { externalAppItemId, type NavItemId } from '@/config/nav'
const nav = useNavStore()
const data = useDataStore()
const ui = useUiStore()
const router = useRouter()

const q = ref('')
const composing = ref(false)
const cache = useSearchDocumentsStore()
const { state: documentState } = storeToRefs(cache)
const documentLimit = ref(50)
const scope = ref('all')
const panel = ref<HTMLElement>()
function loadDocuments() {
  return cache.load(true)
}
watch(
  () => cache.identity,
  () => {
    q.value = ''
    scope.value = 'all'
    void cache.load()
  },
  { immediate: true },
)
const documentMatches = computed(() => {
  const query = q.value.trim()
  return cache.documents.flatMap(({ doc, kb, text }) => {
    const titleHit = contentHit(doc.title, query),
      bodyHit = contentHit(text, query)
    return query && !titleHit && !bodyHit
      ? []
      : [{ doc, kb, hit: titleHit || bodyHit, field: titleHit ? '标题' : '正文' }]
  })
})
const activeResult = ref(0)
watch([q, scope], () => {
  activeResult.value = 0
  documentLimit.value = 50
})
const inputEl = ref<HTMLInputElement | null>(null)
onMounted(() => {
  void nav.loadMenus()
  void nextTick(() => nextTick(() => inputEl.value?.focus()))
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

const taskEntries = computed(() =>
  data.tasks.flatMap((task) => {
    const project = data.projectById(task.project)?.name || '收集箱'
    return [
      {
        title: task.title,
        body: documentSearchText({ blocks: [{ p: task.desc || '' }] }),
        taskId: task.id,
        subId: undefined as string | undefined,
        sub: `任务 · ${project} · ${task.status}`,
        icon: 'ri-checkbox-line',
      },
      ...(task.subs ?? [])
        .filter((sub) => sub.id)
        .map((sub) => ({
          title: sub.text,
          body: documentSearchText({ blocks: [{ p: sub.desc || '' }] }),
          taskId: task.id,
          subId: sub.id,
          sub: `子任务 · ${task.title} · ${project}`,
          icon: 'ri-node-tree',
        })),
    ]
  }),
)

const groups = computed(() => {
  const kw = q.value.trim().toLowerCase()
  const gs: { kind: string; label: string; items: Item[] }[] = []
  const functionIds: NavItemId[] = [
    ...NAVIGATION_CATALOG.flatMap((section) => section.items.map((item) => item.id)),
    'navigation',
    ...nav.externalApps.map((app) => externalAppItemId(app.id)),
  ]
  const functions = functionIds
    .filter((id) => matchesFunction(id, nav.itemMeta(id)?.label ?? id, q.value))
    .slice(0, kw ? documentLimit.value : 4)
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
          (functionDescription(id) ??
            (id.startsWith('external:') ? '打开接入的外部应用。' : '查找 WAO 的全部功能。')) +
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
  if (functions.length) gs.push({ kind: 'functions', label: '功能与应用', items: functions })
  const ps = data.projects
    .filter((p) => !kw || p.name.toLowerCase().includes(kw) || p.goal.toLowerCase().includes(kw))
    .slice(0, kw ? documentLimit.value : 4)
    .map<Item>((p) => ({
      icon: 'ri-stack-line',
      iconColor: data.areaColor(p.area),
      title: p.name,
      sub:
        data.taskLoadState === 'loaded'
          ? projectCompletionLabel(data.tasksByProject(p.id))
          : '任务计数尚未读取',
      go: () => {
        close()
        router.push(`/projects/${p.id}`)
      },
    }))
  const ds = documentMatches.value
    .slice(0, kw ? documentLimit.value : 4)
    .map<Item>(({ doc, kb, hit, field }) => ({
      icon: kb.notes ? 'ri-sticky-note-line' : 'ri-file-text-line',
      iconColor: 'var(--wao-primary-text)',
      title: doc.title,
      sub: `${kb.notes ? '小记' : '文档'} · ${kb.name} · ${kw ? `${field}命中` : '最近更新'}`,
      snippet: hit,
      go: () => {
        close()
        void router.push({
          path: kb.notes ? '/notes' : `/kb/${kb.id}`,
          query: {
            [kb.notes ? 'note' : 'doc']: doc.id,
            find: q.value.trim(),
            findRequest: String(Date.now()),
            match: field === '标题' ? 'title' : 'body',
          },
        })
      },
    }))
  if (ps.length) gs.push({ kind: 'projects', label: '项目', items: ps })
  if (ds.length)
    gs.push({ kind: 'docs', label: `小记与文档（${documentMatches.value.length} 条）`, items: ds })
  if (kw) {
    const tasks = taskEntries.value.flatMap<Item>((entry) => {
      const hit = contentHit(entry.title, q.value) || contentHit(entry.body, q.value)
      return hit
        ? [
            {
              icon: entry.icon,
              iconColor: 'var(--wao-primary-text)',
              title: entry.title,
              sub: entry.sub,
              snippet: hit,
              go: () => {
                close()
                void nextTick(() => ui.openTask(entry.taskId, entry.subId))
              },
            },
          ]
        : []
    })
    if (tasks.length)
      gs.push({
        kind: 'tasks',
        label: `任务与子任务（${tasks.length} 条）`,
        items: tasks.slice(0, documentLimit.value),
      })
    const files = data.files
      .filter((file) => contentHit(`${fileDisplayName(file)} ${file.name}`, q.value))
      .map<Item>((file) => ({
        icon: file.icon || 'ri-file-line',
        iconColor: 'var(--wao-primary-text)',
        title: fileDisplayName(file),
        sub: `文件 · ${file.size}`,
        go: () => {
          close()
          void router.push({
            path: '/files',
            query: { file: file.id, findRequest: String(Date.now()) },
          })
        },
      }))
    if (files.length)
      gs.push({
        kind: 'files',
        label: `文件（${files.length} 条）`,
        items: files.slice(0, documentLimit.value),
      })
  }
  return gs.filter((group) => scope.value === 'all' || group.kind === scope.value)
})

const results = computed(() => groups.value.flatMap((group) => group.items))
watch(results, () => {
  activeResult.value = Math.min(activeResult.value, Math.max(0, results.value.length - 1))
})
watch(activeResult, async () => {
  await nextTick()
  panel.value?.querySelector('.result.selected')?.scrollIntoView?.({ block: 'nearest' })
})
const neededDomains = computed(() =>
  scope.value === 'tasks'
    ? (['tasks'] as const)
    : scope.value === 'files'
      ? (['files'] as const)
      : scope.value === 'projects'
        ? (['projects'] as const)
        : scope.value === 'all'
          ? (['tasks', 'files', 'projects'] as const)
          : [],
)
const incompleteDomains = computed(() =>
  neededDomains.value.filter((domain) => data.loadStates[domain] !== 'loaded'),
)
const domainLabels = { tasks: '任务', files: '文件', projects: '项目' }
const needsDocs = computed(() => scope.value === 'all' || scope.value === 'docs')

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
  <SettingsDialogShell
    title="搜索功能与内容"
    width="680px"
    height="min(680px, calc(100dvh - 48px))"
    appearance="compact"
    @close="close"
  >
    <div ref="panel" class="panel">
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
          role="combobox"
          aria-label="搜索功能与内容"
          aria-controls="global-search-results"
          aria-expanded="true"
          aria-autocomplete="list"
          :aria-activedescendant="results.length ? `search-result-${activeResult}` : undefined"
          placeholder="搜索任务、文件、小记、文档正文…"
        />
        <WaoButton variant="ghost" :disabled="cache.refreshing" @click="loadDocuments"
          >刷新正文</WaoButton
        >
      </div>
      <label class="search-filter"
        >搜索范围<select v-model="scope" aria-label="搜索范围">
          <option value="all">全部</option>
          <option value="tasks">任务与子任务</option>
          <option value="files">文件</option>
          <option value="docs">小记与文档</option>
          <option value="projects">项目</option>
          <option value="functions">功能与应用</option>
        </select></label
      >
      <p
        v-for="domain in incompleteDomains"
        :key="domain"
        class="g-label"
        :role="data.loadStates[domain] === 'error' ? 'alert' : 'status'"
      >
        {{ domainLabels[domain]
        }}{{ data.loadStates[domain] === 'error' ? '读取失败，无法确认完整结果。' : '正在读取…'
        }}<WaoButton
          v-if="data.loadStates[domain] === 'error'"
          variant="ghost"
          @click="data.retryDomain(domain)"
          >重试{{ domainLabels[domain] }}</WaoButton
        >
      </p>
      <div v-if="nav.layoutError" class="g-label" role="alert">
        {{ nav.layoutError }} <button @click="nav.loadMenus(true)">重试导航</button>
      </div>
      <p v-if="needsDocs && documentState === 'loading'" class="g-label" role="status">
        正在读取可访问的小记与文档全文…{{
          cache.documents.length ? '当前显示上次读取的内容。' : ''
        }}
      </p>
      <p v-else-if="needsDocs && documentState === 'error'" class="g-label" role="alert">
        小记与文档读取失败，尚不能确认是否有匹配。{{
          cache.documents.length ? '以下为上次读取的结果。' : ''
        }}<button @click="loadDocuments">重试正文读取</button>
      </p>
      <div id="global-search-results" class="results" role="listbox" aria-label="搜索结果">
        <template v-for="g in groups" :key="g.label">
          <div class="g-label">{{ g.label }}</div>
          <button
            v-for="(it, i) in g.items"
            :key="i"
            class="result"
            role="option"
            :id="`search-result-${results.indexOf(it)}`"
            :aria-selected="results[activeResult] === it"
            :class="{ selected: results[activeResult] === it }"
            @click="it.go()"
          >
            <i :class="it.icon" :style="{ color: it.iconColor }"></i>
            <span class="r-main">
              <span class="r-title">{{ it.title }}</span>
              <span class="r-sub">{{ it.sub }}</span>
              <span v-if="it.snippet" class="r-snippet"
                >{{ it.snippet.before }}<mark>{{ it.snippet.match }}</mark
                >{{ it.snippet.after }}</span
              >
            </span>
            <i class="ri-corner-down-left-line enter"></i>
          </button>
        </template>
        <button
          v-if="q.trim() && groups.some((group) => group.items.length >= documentLimit)"
          class="g-label"
          @click="documentLimit += 50"
        >
          显示更多内容
        </button>
        <div
          v-if="
            groups.length === 0 &&
            (!needsDocs || documentState === 'loaded') &&
            !incompleteDomains.length
          "
          class="empty"
        >
          没有匹配结果——换个关键词试试
        </div>
      </div>
      <div class="footer">
        <span class="try"
          >仅检索当前可访问内容；文档正文不含草稿、归档和回收站。上下键选择，Enter 打开，Esc
          关闭。</span
        >
      </div>
    </div>
  </SettingsDialogShell>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  color: var(--wao-text);
}
.bar {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  padding: var(--wao-space-1);
  border-bottom: 1px solid var(--wao-line);
}
.bar input {
  flex: 1;
  min-width: 0;
  min-height: 44px;
  border: 0;
  background: transparent;
  color: var(--wao-text);
  font: inherit;
  font-size: var(--wao-font-body);
}
.search-filter {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--wao-space-1);
  padding: var(--wao-space-1);
  font-size: var(--wao-font-help);
}
select {
  min-height: 44px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
}
.results {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--wao-space-1);
  scrollbar-gutter: stable;
}
.g-label {
  padding: var(--wao-space-1);
  font-size: var(--wao-font-help);
  color: var(--wao-text-3);
  margin: 0;
}
.result {
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  min-height: 44px;
  padding: var(--wao-space-1);
  border: 0;
  border-radius: var(--wao-radius-control);
  background: transparent;
  color: var(--wao-text);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.result:hover,
.result.selected {
  background: var(--wao-primary-soft);
}
.r-main {
  flex: 1;
  min-width: 0;
}
.r-title {
  display: block;
  font-size: var(--wao-font-body);
  overflow-wrap: anywhere;
}
.r-sub,
.r-snippet {
  display: block;
  font-size: var(--wao-font-help);
  color: var(--wao-text-3);
  overflow-wrap: anywhere;
}
.r-snippet {
  white-space: pre-wrap;
}
.r-snippet mark {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.enter,
.bar-icon {
  color: var(--wao-text-3);
}
.empty {
  padding: var(--wao-space-3);
  text-align: center;
  color: var(--wao-text-3);
}
.footer {
  padding: var(--wao-space-1);
  border-top: 1px solid var(--wao-line);
  font-size: var(--wao-font-help);
  color: var(--wao-text-3);
}
@media (max-width: 480px) {
  .bar input {
    font-size: var(--wao-font-reading);
  }
}
</style>
