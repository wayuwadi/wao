<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import WaoButton from '@/components/ui/WaoButton.vue'

const props = defineProps<{ userId: string }>()
interface Item {
  id: string
  parent: string | null
  list: string
  title: string
  notes: string
  due: string
  done: boolean
  folded: boolean
}
interface State {
  items: Item[]
  lists: string[]
  draft: string
  listDraft: string
  childDrafts: Record<string, string>
}
const empty = (): State => ({
  items: [],
  lists: ['收集箱'],
  draft: '',
  listDraft: '',
  childDrafts: {},
})
const key = `wao:quick-list:v1:${props.userId}`
const state = ref<State>(empty())
const status = ref('已保存到本机')
const blocked = ref(false)
let lastSaved: string | null = null
try {
  lastSaved = localStorage.getItem(key)
  if (lastSaved) {
    const parsed = JSON.parse(lastSaved)
    if (
      !Array.isArray(parsed.items) ||
      !Array.isArray(parsed.lists) ||
      !parsed.lists.includes('收集箱') ||
      !parsed.lists.every((l: unknown) => typeof l === 'string') ||
      !parsed.items.every(
        (i: Item) =>
          i &&
          typeof i.id === 'string' &&
          typeof i.title === 'string' &&
          typeof i.notes === 'string' &&
          typeof i.list === 'string' &&
          typeof i.due === 'string' &&
          typeof i.done === 'boolean' &&
          typeof i.folded === 'boolean' &&
          (i.parent === null || typeof i.parent === 'string'),
      )
    )
      throw new Error('invalid')
    const items = parsed.items as Item[]
    if (
      new Set(items.map((i) => i.id)).size !== items.length ||
      typeof parsed.draft !== 'string' ||
      typeof parsed.listDraft !== 'string' ||
      !parsed.childDrafts ||
      typeof parsed.childDrafts !== 'object' ||
      !Object.values(parsed.childDrafts).every((v) => typeof v === 'string')
    )
      throw new Error('invalid')
    for (const item of items) {
      if (!parsed.lists.includes(item.list)) throw new Error('invalid')
      const seen = new Set([item.id])
      let parent = item.parent
      while (parent) {
        const ancestor = items.find((i) => i.id === parent)
        if (!ancestor || seen.has(parent) || ancestor.list !== item.list) throw new Error('invalid')
        seen.add(parent)
        parent = ancestor.parent
      }
    }
    state.value = { ...empty(), ...parsed }
  }
} catch {
  blocked.value = true
  status.value = '本机数据读取失败，已停止写入。请保留此页并检查浏览器存储。'
}
const filter = ref('全部')
const selectedId = ref(state.value.items[0]?.id ?? '')
const childDraft = computed({
  get: () => state.value.childDrafts[selectedId.value] ?? '',
  set: (value) => {
    state.value.childDrafts[selectedId.value] = value
  },
})
const search = ref('')
const showDone = ref(false)
const selected = computed(() => state.value.items.find((i) => i.id === selectedId.value))
const today = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const matches = (i: Item) =>
  (filter.value === '已完成' ? i.done : showDone.value || !i.done) &&
  (filter.value === '全部' ||
    filter.value === '已完成' ||
    (filter.value === '今天' ? i.due === today() : i.list === filter.value)) &&
  (!search.value || `${i.title} ${i.notes}`.toLowerCase().includes(search.value.toLowerCase()))
const rows = computed(() => {
  const wanted = new Set(state.value.items.filter(matches).map((i) => i.id))
  for (const id of [...wanted]) {
    let item = state.value.items.find((i) => i.id === id)
    const visited = new Set<string>()
    while (item?.parent && !visited.has(item.parent)) {
      visited.add(item.parent)
      wanted.add(item.parent)
      item = state.value.items.find((i) => i.id === item?.parent)
    }
  }
  const result: { item: Item; depth: number; context: boolean }[] = []
  const visited = new Set<string>()
  const walk = (parent: string | null, depth: number) => {
    for (const item of state.value.items.filter((i) => i.parent === parent)) {
      if (!wanted.has(item.id) || visited.has(item.id)) continue
      visited.add(item.id)
      result.push({ item, depth, context: !matches(item) })
      if (!item.folded || search.value || filter.value === '今天' || filter.value === '已完成')
        walk(item.id, depth + 1)
    }
  }
  walk(null, 0)
  return result
})
const children = (id: string) => state.value.items.filter((i) => i.parent === id)
const count = (name: string) =>
  state.value.items.filter((i) =>
    name === '已完成'
      ? i.done
      : !i.done && (name === '全部' || (name === '今天' ? i.due === today() : i.list === name)),
  ).length
const ancestors = computed(() => {
  const chain: Item[] = []
  let item = selected.value
  const visited = new Set<string>()
  while (item?.parent && !visited.has(item.parent)) {
    visited.add(item.parent)
    item = state.value.items.find((i) => i.id === item?.parent)
    if (item) chain.unshift(item)
  }
  return chain
})
function save() {
  if (blocked.value) return
  try {
    if (localStorage.getItem(key) !== lastSaved) {
      blocked.value = true
      status.value = '另一页面已修改清单。当前输入仍保留，请导出备份后刷新合并。'
      return
    }
    const next = JSON.stringify(state.value)
    localStorage.setItem(key, next)
    lastSaved = next
    status.value = '已保存到本机'
  } catch {
    status.value = '保存失败，输入仍在此页。请重试或导出备份。'
  }
}
watch(state, save, { deep: true, flush: 'sync' })
function storageChanged(e: StorageEvent) {
  if (e.key === key && e.newValue !== lastSaved) {
    blocked.value = true
    status.value = '另一页面已修改清单。当前输入仍保留，请导出备份后刷新合并。'
  }
}
window.addEventListener('storage', storageChanged)
onBeforeUnmount(() => window.removeEventListener('storage', storageChanged))
function add(parent: Item | null = null) {
  const title = (parent ? childDraft.value : state.value.draft).trim()
  if (!title || blocked.value) return
  const item: Item = {
    id: crypto.randomUUID(),
    parent: parent?.id ?? null,
    list: parent?.list ?? (state.value.lists.includes(filter.value) ? filter.value : '收集箱'),
    title,
    notes: '',
    due: filter.value === '今天' ? today() : '',
    done: false,
    folded: false,
  }
  if (parent) {
    parent.folded = false
    parent.done = false
    for (const a of parentChain(parent)) a.done = false
    childDraft.value = ''
  } else state.value.draft = ''
  state.value.items.push(item)
  if (!parent) selectedId.value = item.id
  if (filter.value === '已完成') filter.value = '全部'
}
function toggle(item: Item) {
  if (blocked.value) return
  item.done = !item.done
  if (!item.done) {
    for (const a of parentChain(item)) a.done = false
  }
}
function parentChain(item: Item) {
  const result: Item[] = []
  const seen = new Set<string>()
  let p = item.parent
  while (p && !seen.has(p)) {
    seen.add(p)
    const a = state.value.items.find((i) => i.id === p)
    if (!a) break
    result.push(a)
    p = a.parent
  }
  return result
}
function addList() {
  const name = state.value.listDraft.trim()
  if (blocked.value || !name || ['全部', '今天', '已完成', ...state.value.lists].includes(name))
    return
  state.value.lists.push(name)
  state.value.listDraft = ''
  filter.value = name
}
function changeList(item: Item) {
  const walk = (id: string) => {
    for (const child of children(id)) {
      child.list = item.list
      walk(child.id)
    }
  }
  walk(item.id)
}
function exportData() {
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(state.value, null, 2)], { type: 'application/json' }),
  )
  const a = document.createElement('a')
  a.href = url
  a.download = `随手清单-${today()}.json`
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <section class="quick-list" aria-label="随手清单">
    <div class="local-notice">
      <span><i class="ri-computer-line"></i> 本地试用 · 独立于项目任务</span
      ><span role="status">{{ status }}</span
      ><button @click="exportData">导出备份</button
      ><button v-if="status.startsWith('保存失败')" @click="save">重试保存</button>
    </div>
    <div class="workspace">
      <aside class="lists" aria-label="清单分类">
        <button
          v-for="name in ['全部', '今天']"
          :key="name"
          :class="{ active: filter === name }"
          @click="filter = name"
        >
          <i :class="name === '全部' ? 'ri-inbox-2-line' : 'ri-calendar-line'"></i>{{ name
          }}<span>{{ count(name) }}</span>
        </button>
        <div class="section-label">我的清单</div>
        <button
          v-for="name in state.lists"
          :key="name"
          :class="{ active: filter === name }"
          @click="filter = name"
        >
          <i class="ri-list-check-2"></i><b>{{ name }}</b
          ><span>{{ count(name) }}</span>
        </button>
        <form class="new-list" @submit.prevent="addList">
          <input
            v-model="state.listDraft"
            :disabled="blocked"
            aria-label="新清单名称"
            placeholder="＋ 新建清单"
            maxlength="60"
          /><button aria-label="创建清单" :disabled="blocked || !state.listDraft.trim()">↵</button>
        </form>
        <button
          class="completed-filter"
          :class="{ active: filter === '已完成' }"
          @click="filter = '已完成'"
        >
          <i class="ri-checkbox-circle-line"></i>已完成<span>{{ count('已完成') }}</span>
        </button>
        <p class="local-help">想到就记下来，<br />再慢慢拆成小事。</p>
      </aside>
      <main class="task-list">
        <header>
          <h2>{{ filter }}</h2>
          <span>{{ rows.length }} 项</span
          ><label class="show-done"><input v-model="showDone" type="checkbox" />显示完成</label>
        </header>
        <form class="capture" @submit.prevent="add()">
          <i class="ri-add-line"></i
          ><input
            v-model="state.draft"
            :disabled="blocked"
            aria-label="快速添加任务"
            placeholder="想到什么，先记下来…"
          /><button :disabled="blocked || !state.draft.trim()" aria-label="添加任务">↵</button>
        </form>
        <div class="search">
          <i class="ri-search-line"></i
          ><input v-model="search" aria-label="搜索清单" placeholder="搜索标题和内容" />
        </div>
        <div class="tree" aria-label="任务层级">
          <div
            v-for="{ item, depth, context } in rows"
            :key="item.id"
            class="task-row"
            :class="{ selected: item.id === selectedId, done: item.done, context }"
            :style="{ paddingInlineStart: `${Math.min(depth, 5) * 18 + 8}px` }"
          >
            <button
              class="fold"
              :aria-label="`${item.folded ? '展开' : '收起'} ${item.title}`"
              :aria-expanded="!item.folded"
              :disabled="!children(item.id).length || blocked"
              @click="item.folded = !item.folded"
            >
              <i
                v-if="children(item.id).length"
                :class="item.folded ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"
              ></i>
            </button>
            <input
              type="checkbox"
              :checked="item.done"
              :disabled="blocked || (!item.done && children(item.id).some((c) => !c.done))"
              :aria-label="`完成 ${item.title}`"
              :title="
                children(item.id).some((c) => !c.done)
                  ? '先完成子任务，再完成这件事'
                  : '完成 / 重新打开'
              "
              @change="toggle(item)"
            />
            <button
              class="row-title"
              :aria-pressed="item.id === selectedId"
              @click="selectedId = item.id"
            >
              <span>{{ item.title || '未命名任务' }}</span
              ><small v-if="item.notes"><i class="ri-file-text-line"></i></small
              ><small v-if="children(item.id).length"
                >{{ children(item.id).filter((c) => c.done).length }}/{{
                  children(item.id).length
                }}</small
              ><small v-if="item.due">{{ item.due.slice(5) }}</small>
            </button>
          </div>
          <div v-if="!rows.length" class="empty-state">
            <i class="ri-checkbox-multiple-blank-line"></i>
            <h3>{{ search ? '没有找到匹配的任务' : '给想做的事，一个落脚处' }}</h3>
            <p>
              {{
                search ? '试试其他关键词，或切换清单。' : '在上方写下一件事，按 Enter 收进清单。'
              }}
            </p>
          </div>
        </div>
        <footer>Enter 快速收集 · 点选任务，直接写详情</footer>
      </main>
      <section class="details" aria-label="任务详情">
        <template v-if="selected">
          <div class="detail-top">
            <span>{{ selected.list }}</span
            ><label
              >日期
              <input v-model="selected.due" :disabled="blocked" type="date" aria-label="任务日期"
            /></label>
          </div>
          <nav v-if="ancestors.length" class="breadcrumbs" aria-label="父级任务">
            <template v-for="a in ancestors" :key="a.id"
              ><button @click="selectedId = a.id">{{ a.title }}</button><span> / </span></template
            >
          </nav>
          <textarea
            v-model="selected.title"
            rows="2"
            class="detail-title"
            :disabled="blocked"
            aria-label="任务标题"
            placeholder="任务标题"
          ></textarea>
          <textarea
            v-model="selected.notes"
            :disabled="blocked"
            aria-label="任务内容"
            placeholder="记下想法、进展，或这件事的来龙去脉…"
          />
          <div class="sub-heading">
            <h3>子任务</h3>
            <span>{{ children(selected.id).length }} 项</span>
          </div>
          <div v-for="child in children(selected.id)" :key="child.id" class="child-row">
            <input
              type="checkbox"
              :checked="child.done"
              :disabled="blocked || (!child.done && children(child.id).some((c) => !c.done))"
              :aria-label="`完成子任务 ${child.title}`"
              @change="toggle(child)"
            /><button @click="selectedId = child.id">
              {{ child.title }}<i class="ri-arrow-right-s-line"></i>
            </button>
          </div>
          <form class="capture child-capture" @submit.prevent="add(selected)">
            <i class="ri-add-line"></i
            ><input
              v-model="childDraft"
              :disabled="blocked"
              aria-label="添加子任务"
              placeholder="拆成下一件小事…"
            /><button :disabled="blocked || !childDraft.trim()" aria-label="创建子任务">↵</button>
          </form>
          <p v-if="childDraft" class="draft-hint">将添加到「{{ selected.title }}」下</p>
          <div class="detail-bottom">
            <label
              >所属清单
              <select
                v-model="selected.list"
                :disabled="blocked || !!selected.parent"
                @change="changeList(selected)"
              >
                <option v-for="name in state.lists" :key="name">{{ name }}</option>
              </select></label
            ><WaoButton
              v-if="selected.parent"
              variant="ghost"
              :disabled="blocked"
              @click="selected.parent = null"
              >提升为主任务</WaoButton
            >
          </div>
        </template>
        <div v-else class="empty-state detail-empty">
          <i class="ri-edit-2-line"></i>
          <h3>让事情在这里展开</h3>
          <p>选中一项，直接写内容、拆分子任务。<br />清单和详情一直在眼前。</p>
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.quick-list {
  color: var(--wao-text);
  font-size: var(--wao-font-body);
}
:global([data-wao-theme='dark']) .quick-list {
  color-scheme: dark;
}
button,
input,
textarea,
select {
  font: inherit;
  color: inherit;
}
button {
  cursor: pointer;
}
button:disabled {
  cursor: default;
  opacity: 0.45;
}
button:focus-visible,
input:focus-visible,
textarea:focus-visible,
select:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 2px;
}
input[type='checkbox'] {
  accent-color: var(--wao-primary);
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}
.local-notice {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--wao-space-2);
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
  padding: var(--wao-space-1) 0 var(--wao-space-2);
}
.local-notice span:first-child {
  margin-right: auto;
}
.local-notice button,
.breadcrumbs button {
  border: 0;
  background: transparent;
  color: var(--wao-primary-text);
}
.workspace {
  display: grid;
  grid-template-columns: 184px minmax(260px, 1fr) minmax(300px, 1.15fr);
  min-height: 65vh;
  border-top: 1px solid var(--wao-line);
}
.lists {
  padding: var(--wao-space-3) var(--wao-space-2) var(--wao-space-3) 0;
  border-right: 1px solid var(--wao-line);
}
.lists > button {
  display: flex;
  width: 100%;
  align-items: center;
  gap: var(--wao-space-1);
  border: 0;
  border-radius: var(--wao-radius-control);
  background: transparent;
  min-height: 44px;
  padding: var(--wao-space-1);
  text-align: left;
}
.lists button.active {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.lists button b {
  font-weight: 400;
  overflow-wrap: anywhere;
  min-width: 0;
}
.lists button span {
  margin-left: auto;
  font-size: var(--wao-font-caption);
}
.section-label {
  margin: var(--wao-space-4) var(--wao-space-1) var(--wao-space-1);
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
.new-list {
  display: flex;
  margin-top: var(--wao-space-1);
}
.new-list input {
  width: 100%;
  min-width: 0;
  padding: var(--wao-space-1);
}
.new-list input,
.new-list button {
  background: transparent;
  border: 0;
}
.lists .completed-filter {
  margin-top: var(--wao-space-4);
}
.local-help {
  margin: var(--wao-space-4) var(--wao-space-1);
  color: var(--wao-text-3);
  line-height: 1.9;
  font-size: var(--wao-font-caption);
}
.task-list {
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: var(--wao-space-3) var(--wao-space-2);
  border-right: 1px solid var(--wao-line);
}
header {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  margin-bottom: var(--wao-space-3);
}
h2 {
  font-size: 20px;
  margin: 0;
  overflow-wrap: anywhere;
}
header > span,
.show-done,
footer,
.sub-heading > span {
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
}
.show-done {
  display: flex;
  align-items: center;
  margin-left: auto;
  white-space: nowrap;
}
.capture {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  background: var(--wao-bg-sub-2);
  border-radius: var(--wao-radius-control);
  padding: var(--wao-space-1);
  min-height: 44px;
}
.capture input,
.search input {
  min-width: 0;
  width: 100%;
  background: transparent;
  border: 0;
  outline-offset: 4px;
}
.capture button {
  border: 0;
  background: transparent;
  color: var(--wao-primary-text);
  min-width: 28px;
}
.search {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  margin: var(--wao-space-2) var(--wao-space-1);
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
}
.tree {
  flex: 1;
}
.task-row {
  display: flex;
  align-items: center;
  min-height: 46px;
  border-radius: var(--wao-radius-control);
  margin: 2px 0;
  gap: var(--wao-space-1);
}
.task-row:hover {
  background: var(--wao-bg-hover);
}
.task-row.selected {
  background: var(--wao-primary-soft);
}
.task-row.context {
  color: var(--wao-text-3);
}
.fold {
  width: 16px;
  flex-shrink: 0;
  padding: 0;
  border: 0;
  background: transparent;
}
.row-title {
  display: flex;
  align-items: center;
  text-align: left;
  min-width: 0;
  flex: 1;
  gap: var(--wao-space-1);
  padding: var(--wao-space-1);
  border: 0;
  background: transparent;
}
.row-title > span {
  flex: 1;
  overflow-wrap: anywhere;
}
.row-title small {
  flex-shrink: 0;
  color: var(--wao-text-3);
}
.done .row-title > span {
  text-decoration: line-through;
  color: var(--wao-text-3);
}
footer {
  padding-top: var(--wao-space-4);
}
.details {
  min-width: 0;
  padding: var(--wao-space-3);
  display: flex;
  flex-direction: column;
}
.detail-top {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--wao-space-1);
  justify-content: space-between;
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
  margin-bottom: var(--wao-space-3);
}
.detail-top input,
select {
  background: var(--wao-bg-page);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-control);
  padding: var(--wao-space-1);
  max-width: 100%;
}
.breadcrumbs {
  color: var(--wao-text-3);
  margin-bottom: var(--wao-space-2);
  overflow-wrap: anywhere;
}
.details .detail-title {
  width: 100%;
  min-height: 80px;
  margin-top: 0;
  border: 0;
  background: transparent;
  font-size: 22px;
  font-weight: 600;
  padding: var(--wao-space-1) 0;
  line-height: 1.5;
}
textarea {
  width: 100%;
  min-height: 220px;
  resize: vertical;
  line-height: 1.9;
  margin-top: var(--wao-space-2);
  padding: var(--wao-space-1) 0;
  border: 0;
  background: transparent;
}
.sub-heading {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  margin-top: var(--wao-space-3);
  border-top: 1px solid var(--wao-line);
}
h3 {
  font-size: var(--wao-font-body);
  font-weight: 600;
}
.child-row {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  min-height: 44px;
}
.child-row button {
  display: flex;
  justify-content: space-between;
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
  text-align: left;
  border: 0;
  background: transparent;
  padding: var(--wao-space-1) 0;
}
.child-capture {
  margin-top: var(--wao-space-1);
}
.detail-bottom {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--wao-space-1);
  margin-top: auto;
  padding-top: var(--wao-space-4);
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
}
.empty-state {
  text-align: center;
  padding: 64px var(--wao-space-2);
  color: var(--wao-text-3);
  line-height: 1.9;
}
.empty-state > i {
  font-size: 32px;
  color: var(--wao-text-weak);
}
.empty-state h3 {
  color: var(--wao-text-2);
}
.empty-state p {
  font-size: var(--wao-font-caption);
}
.detail-empty {
  margin: auto 0;
}
.draft-hint {
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
@media (max-width: 1100px) {
  .workspace {
    grid-template-columns: 150px minmax(220px, 1fr) minmax(260px, 1fr);
  }
  .details {
    padding: var(--wao-space-2);
  }
}
@media (max-width: 800px) {
  .workspace {
    grid-template-columns: minmax(0, 1fr);
  }
  .lists {
    display: flex;
    flex-wrap: wrap;
    gap: var(--wao-space-1);
    border-right: 0;
    padding: var(--wao-space-2) 0;
    border-bottom: 1px solid var(--wao-line);
  }
  .lists > button {
    width: auto;
  }
  .section-label,
  .local-help {
    display: none;
  }
  .lists .completed-filter {
    margin: 0;
  }
  .new-list {
    max-width: 180px;
    margin: 0;
  }
  .task-list {
    border-right: 0;
    border-bottom: 1px solid var(--wao-line);
    padding: var(--wao-space-2) 0;
  }
  .details {
    padding: var(--wao-space-3) 0;
  }
  .detail-empty {
    display: none;
  }
  input,
  textarea {
    font-size: 16px;
  }
}
</style>
