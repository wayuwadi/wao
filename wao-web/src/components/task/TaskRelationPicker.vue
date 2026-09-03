<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'

type RelationType = 'all' | 'doc' | 'task' | 'file' | 'page'
interface RelationCandidate {
  id: string
  type: Exclude<RelationType, 'all'>
  title: string
  meta: string
  icon: string
  selected: boolean
  createdAt?: string | null
}

const props = defineProps<{
  docs: Array<{ id: string; title: string; kbName: string; createdAt?: string | null }>
  tasks: Array<{ id: string; title: string; projectName: string; createdAt?: string | null }>
  files: Array<{ id: string; name: string; size: string; icon: string; createdAt?: string | null }>
  pages: Array<{ id: string; title: string; fileName: string }>
  selectedDocIds: string[]
  selectedTaskIds: string[]
  selectedFileIds: string[]
  selectedPageIds: string[]
  pageLoadState: 'idle' | 'loading' | 'loaded' | 'error'
  pageLoadError: string
}>()
const emit = defineEmits<{
  close: []
  toggle: [type: 'doc' | 'task' | 'file' | 'page', id: string]
  retryPages: []
}>()

const type = ref<RelationType>('all')
const query = ref('')
const search = ref<HTMLInputElement | null>(null)

/** 只接受可解析的真实时间；没有可靠时间的历史数据保持原顺序并排在最后。 */
function createdMillis(value?: string | null): number | null {
  if (!value) return null
  // Task/File 的历史契约是上海本地 ISO 时间，Doc 已经带 +08:00；显式补偏移，避免浏览器
  // 位于其它时区时把三类候选按不同基准解释，导致跨类型顺序错八小时。
  const localIso = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?$/
  const parsed = Date.parse(localIso.test(value) ? `${value}+08:00` : value)
  return Number.isFinite(parsed) ? parsed : null
}

const items = computed<RelationCandidate[]>(() => {
  const candidates: RelationCandidate[] = [
    ...props.docs.map((doc) => ({
      id: doc.id, type: 'doc' as const, title: doc.title || '未命名文档', meta: doc.kbName,
      icon: 'ri-book-2-line', selected: props.selectedDocIds.includes(doc.id), createdAt: doc.createdAt,
    })),
    ...props.tasks.map((task) => ({
      id: task.id, type: 'task' as const, title: task.title, meta: task.projectName,
      icon: 'ri-checkbox-circle-line', selected: props.selectedTaskIds.includes(task.id), createdAt: task.createdAt,
    })),
    ...props.files.map((file) => ({
      id: file.id, type: 'file' as const, title: file.name, meta: file.size,
      icon: file.icon || 'ri-file-line', selected: props.selectedFileIds.includes(file.id), createdAt: file.createdAt,
    })),
    ...props.pages.map((page) => ({
      id: page.id, type: 'page' as const, title: page.title, meta: page.fileName,
      icon: 'ri-html5-line', selected: props.selectedPageIds.includes(page.id),
    })),
  ]
  return candidates
    .map((item, index) => ({ item, index, createdAt: createdMillis(item.createdAt) }))
    .sort((a, b) => {
      if (a.createdAt === null && b.createdAt === null) return a.index - b.index
      if (a.createdAt === null) return 1
      if (b.createdAt === null) return -1
      return b.createdAt - a.createdAt || a.index - b.index
    })
    .map(({ item }) => item)
})
const filtered = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase()
  return items.value.filter((item) =>
    (type.value === 'all' || item.type === type.value)
      && (!keyword || `${item.title} ${item.meta}`.toLocaleLowerCase().includes(keyword)),
  )
})
const counts = computed(() => ({
  all: items.value.length,
  doc: props.docs.length,
  task: props.tasks.length,
  file: props.files.length,
  page: props.pages.length,
}))
const labels: Array<{ type: RelationType; label: string }> = [
  { type: 'all', label: '全部' }, { type: 'doc', label: '知识' },
  { type: 'task', label: '任务' }, { type: 'file', label: '文件' },
  { type: 'page', label: 'HTML' },
]

void nextTick(() => search.value?.focus())
</script>

<template>
  <div class="relation-mask" @click="emit('close')"></div>
  <section class="relation-picker" role="dialog" aria-modal="true" aria-label="关联知识、任务、文件或 HTML">
    <header>
      <div>
        <b>关联到任务</b>
        <p>从真实知识库、未完成任务、文件库和人工上传 HTML 中选择</p>
      </div>
      <button class="close" aria-label="关闭关联选择器" @click="emit('close')"><i class="ri-close-line"></i></button>
    </header>

    <label class="search">
      <i class="ri-search-line"></i>
      <input ref="search" v-model="query" placeholder="搜索标题或文件名" />
      <button v-if="query" aria-label="清空搜索" @click="query = ''"><i class="ri-close-circle-fill"></i></button>
    </label>

    <nav aria-label="关联类型">
      <button v-for="item in labels" :key="item.type" :class="{ on: type === item.type }" @click="type = item.type">
        {{ item.label }}<span>{{ counts[item.type] }}</span>
      </button>
    </nav>

    <div class="relation-list">
      <button
        v-for="item in filtered"
        :key="`${item.type}:${item.id}`"
        class="relation-row"
        :class="{ selected: item.selected }"
        :aria-pressed="item.selected"
        :title="item.selected ? '点击取消关联' : '点击关联到任务'"
        @click="emit('toggle', item.type, item.id)"
      >
        <span class="type-icon" :class="item.type"><i :class="item.icon"></i></span>
        <span class="relation-main">
          <b>{{ item.title }}</b>
          <em>{{ item.meta || (item.type === 'doc' ? '知识文档' : item.type === 'task' ? '任务' : '文件') }}</em>
        </span>
        <span class="choice"><i :class="item.selected ? 'ri-check-line' : 'ri-add-line'"></i></span>
      </button>
      <div v-if="type === 'page' && pageLoadState === 'loading' && !filtered.length" class="empty">
        <i class="ri-loader-4-line spin"></i>
        <span>正在读取人工上传的 HTML…</span>
      </div>
      <div v-else-if="type === 'page' && pageLoadState === 'error' && !filtered.length" class="empty">
        <i class="ri-error-warning-line"></i>
        <span>{{ pageLoadError || 'HTML 读取失败，已关联内容不会被改动' }}</span>
        <button class="retry" @click="emit('retryPages')">重试</button>
      </div>
      <div v-else-if="!filtered.length" class="empty">
        <i class="ri-links-line"></i>
        <span>{{ query ? '没有匹配的内容' : type === 'file' ? '文件库里还没有可关联的真实文件' : type === 'page' ? '还没有人工上传的 HTML' : '暂无可关联内容' }}</span>
      </div>
    </div>

    <footer><span>再次点击已勾选的内容即可解除关联</span><button @click="emit('close')">完成</button></footer>
  </section>
</template>

<style scoped>
.relation-mask { position: fixed; inset: 0; z-index: 140; background: rgba(18, 22, 39, .28); }
.relation-picker {
  position: fixed; z-index: 141; top: 50%; left: 50%; width: min(520px, calc(100vw - 32px));
  max-height: min(680px, calc(100dvh - 32px)); transform: translate(-50%, -50%); display: flex;
  flex-direction: column; overflow: hidden; border: 1px solid var(--wao-line); border-radius: 16px;
  background: var(--wao-bg-card); box-shadow: 0 24px 70px rgba(24, 30, 66, .24);
}
header { display: flex; align-items: flex-start; padding: 18px 18px 12px; }
header > div { flex: 1; min-width: 0; }
header b { font-size: 16px; color: var(--wao-text); }
header p { margin: 4px 0 0; color: var(--wao-text-weak); font-size: 11.5px; }
.close { width: 32px; height: 32px; border: 0; border-radius: 9px; background: var(--wao-bg-hover-2); color: var(--wao-text-3); cursor: pointer; }
.search { height: 40px; margin: 0 18px 12px; display: flex; align-items: center; gap: 8px; padding: 0 11px; border: 1px solid var(--wao-line-control); border-radius: 10px; }
.search:focus-within { border-color: #9ba9eb; box-shadow: 0 0 0 3px rgba(67, 88, 214, .1); }
.search > i { color: var(--wao-text-weak); }
.search input { flex: 1; min-width: 0; border: 0; outline: 0; font-size: 13px; color: var(--wao-text); }
.search button { width: 26px; height: 26px; border: 0; background: none; color: var(--wao-text-faint); cursor: pointer; }
nav {
  min-height: 42px; flex: 0 0 auto; display: flex; gap: 5px; padding: 0 18px 12px;
  border-bottom: 1px solid var(--wao-divider); overflow-x: auto; scrollbar-width: thin;
}
nav button {
  height: 30px; flex: 0 0 auto; padding: 0 10px; border: 0; border-radius: 8px;
  background: transparent; color: var(--wao-text-3); cursor: pointer; white-space: nowrap;
}
nav button.on { background: var(--wao-primary-soft); color: var(--wao-primary-text); font-weight: 600; }
nav span { margin-left: 5px; color: var(--wao-text-faint); font-size: 10px; }
.relation-list { min-height: 220px; overflow-y: auto; padding: 8px; }
.relation-row { width: 100%; min-height: 54px; display: flex; align-items: center; gap: 10px; padding: 7px 9px; border: 0; border-radius: 10px; background: var(--wao-bg-card); text-align: left; cursor: pointer; }
.relation-row:hover { background: var(--wao-bg-hover-2); }
.relation-row.selected { background: var(--wao-primary-soft); }
.type-icon { width: 34px; height: 34px; flex: 0 0 34px; display: grid; place-items: center; border-radius: 9px; background: var(--wao-bg-tab); color: var(--wao-area-finance); }
.type-icon.task { background: #edf1ff; color: #4358d6; }
.type-icon.file { background: #ecf7f1; color: #31805a; }
.type-icon.page { background: #fff0e8; color: #d55c22; }
.relation-main { flex: 1; min-width: 0; }
.relation-main b, .relation-main em { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.relation-main b { color: var(--wao-text); font-size: 12.5px; }
.relation-main em { margin-top: 3px; color: var(--wao-text-weak); font-size: 10.5px; font-style: normal; }
.choice { width: 25px; height: 25px; flex: 0 0 25px; display: grid; place-items: center; border: 1px solid var(--wao-line-control); border-radius: 7px; color: var(--wao-text-weak); }
.selected .choice { border-color: var(--wao-primary); background: var(--wao-primary); color: #fff; }
.empty { min-height: 210px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: var(--wao-text-weak); font-size: 12px; }
.empty i { font-size: 28px; color: var(--wao-text-faint); }
.empty .retry { height: 32px; padding: 0 14px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-primary-text); cursor: pointer; }
.spin { animation: waoSpin .9s linear infinite; }
@keyframes waoSpin { to { transform: rotate(360deg); } }
footer { min-height: 54px; display: flex; align-items: center; gap: 12px; padding: 8px 14px 8px 18px; border-top: 1px solid var(--wao-divider); }
footer span { flex: 1; color: var(--wao-text-weak); font-size: 10.5px; }
footer button { height: 36px; padding: 0 18px; border: 0; border-radius: 9px; background: var(--wao-primary); color: #fff; cursor: pointer; }
@media (max-width: 760px) {
  .relation-picker { top: auto; bottom: 0; left: 0; width: 100%; max-height: 88dvh; transform: none; border-radius: 18px 18px 0 0; padding-bottom: env(safe-area-inset-bottom); }
  header { padding-top: 16px; }
  .relation-list { min-height: 180px; }
  .relation-row { min-height: 58px; }
  footer button { height: 42px; }
}
</style>
