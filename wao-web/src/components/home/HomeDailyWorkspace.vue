<script setup lang="ts">
import { computed, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useNow } from '@/composables/now'
import { relativeTime } from '@/utils/time'
import { resolvedRichContent } from '@/utils/richDoc'
import { renderMarkdown } from '@/utils/markdown'
import { countCharacters } from '@/utils/characterCount'
import type { Doc, RichDocNode } from '@/types'

const data = useDataStore(),
  session = useSessionStore(),
  ui = useUiStore()
const now = useNow()
const today = computed(() => new Date(now.value.getTime() + 8 * 3600000).toISOString().slice(0, 10))
const todayTasks = computed(() =>
  data.tasks
    .filter(
      (task) =>
        session.currentUser.id &&
        (task.owner === session.currentUser.id || task.parts.includes(session.currentUser.id)) &&
        task.status !== '已完成' &&
        task.due &&
        task.due <= today.value,
    )
    .sort(
      (a, b) => a.due.localeCompare(b.due) || a.pr.localeCompare(b.pr) || a.id.localeCompare(b.id),
    ),
)
const recentDocs = computed(() =>
  Object.values(data.docs)
    .filter(
      (doc) =>
        session.currentUser.id &&
        doc.author === session.currentUser.id &&
        !doc.auto &&
        doc.kbId &&
        data.kbs.some((kb) => kb.id === doc.kbId) &&
        !data.isDocArchived(doc.id),
    )
    .sort((a, b) => timestamp(b.updated) - timestamp(a.updated) || b.id.localeCompare(a.id))
    .slice(0, 4),
)
function timestamp(value: string) {
  return Date.parse(value) || 0
}
const recentProjects = computed(() =>
  data.projects.filter((project) => !['已完成', '已搁置'].includes(project.status)).slice(0, 3),
)
const docsLoading = computed(() => ['idle', 'loading'].includes(data.loadStates.docs))
watch(
  () => session.userId,
  () => {
    if (session.userId) void data.ensureDocsLoaded()
  },
  { immediate: true },
)
watch(
  () => recentDocs.value.map((doc) => doc.id).join(','),
  () => {
    for (const doc of recentDocs.value)
      if (doc.bodyLoaded === false) void data.ensureDocLoaded(doc.id)
  },
  { immediate: true },
)
function nodeText(node: RichDocNode): string {
  return node.text ?? (node.content ?? []).map(nodeText).join(node.type === 'doc' ? '\n' : '')
}
function docText(doc: Doc): string {
  const content = resolvedRichContent(doc)
  if (content.document) return nodeText(content.document)
  return (
    new DOMParser().parseFromString(renderMarkdown(content.markdown), 'text/html').body
      .textContent ?? ''
  )
}
const writings = computed(() =>
  recentDocs.value.map((doc) => {
    const text = doc.bodyLoaded === false ? null : docText(doc)
    return {
      doc,
      excerpt: text?.replace(/\s+/g, ' ').trim().slice(0, 110),
      count: text === null ? null : countCharacters(text),
    }
  }),
)
</script>

<template>
  <div class="daily-workspace">
    <section class="daily-card" aria-labelledby="home-today-title">
      <header>
        <div>
          <h2 id="home-today-title">
            今日待办 <span v-if="data.loadStates.tasks === 'loaded'">{{ todayTasks.length }}</span>
          </h2>
          <p>我负责或参与 · 含逾期未完成</p>
        </div>
        <RouterLink to="/todo">全部待办 <i class="ri-arrow-right-s-line" /></RouterLink>
      </header>
      <p v-if="data.loadErrors.tasks" class="state error" role="alert">
        待办读取失败 <button @click="data.hydrate()">重试</button>
      </p>
      <p
        v-else-if="['idle', 'loading'].includes(data.loadStates.tasks)"
        class="state"
        role="status"
      >
        正在读取待办…
      </p>
      <ul v-else-if="todayTasks.length" class="task-list">
        <li v-for="task in todayTasks.slice(0, 5)" :key="task.id">
          <button class="task-row" @click="ui.openTask(task.id)">
            <span class="task-mark" :class="{ overdue: task.due < today }"
              ><i class="ri-checkbox-blank-circle-line"
            /></span>
            <span class="row-main"
              ><b>{{ task.title }}</b
              ><small
                >{{
                  data.projects.find((project) => project.id === task.project)?.name || '任务'
                }}
                · {{ task.status }}</small
              ></span
            >
            <span class="due" :class="{ overdue: task.due < today }">{{
              task.due < today ? `${task.due.slice(5)} 逾期` : '今天'
            }}</span>
          </button>
        </li>
      </ul>
      <div v-else class="state empty">
        <i class="ri-checkbox-circle-line" /><b>今天没有到期的待办</b
        ><span>可以看看后续安排，或继续写点什么。</span><RouterLink to="/todo">安排待办</RouterLink>
      </div>
      <RouterLink v-if="todayTasks.length > 5" class="more" to="/todo"
        >还有 {{ todayTasks.length - 5 }} 项，查看全部</RouterLink
      >
    </section>
    <section class="daily-card" aria-labelledby="home-writing-title">
      <header>
        <div>
          <h2 id="home-writing-title">最近创作</h2>
          <p>我的小记与文档 · 按更新时间</p>
        </div>
        <RouterLink to="/notes">写小记 <i class="ri-edit-line" /></RouterLink>
      </header>
      <p v-if="data.loadErrors.docs || data.loadErrors.knowledge" class="state error" role="alert">
        创作内容读取失败
        <button @click="data.loadErrors.knowledge ? data.hydrate() : data.refreshDocs()">
          重试
        </button>
      </p>
      <p
        v-else-if="docsLoading || ['idle', 'loading'].includes(data.loadStates.knowledge)"
        class="state"
        role="status"
      >
        正在读取最近创作…
      </p>
      <ul v-else-if="writings.length" class="writing-list">
        <li v-for="item in writings" :key="item.doc.id">
          <RouterLink
            class="writing-row"
            :to="{ path: `/kb/${encodeURIComponent(item.doc.kbId!)}`, query: { doc: item.doc.id } }"
          >
            <b>{{ item.doc.title || '未命名文档' }}</b>
            <p v-if="item.count !== null">{{ item.excerpt || '暂时没有正文，继续写下第一句。' }}</p>
            <small
              ><span>{{ relativeTime(item.doc.updated, now) }}</span
              ><span v-if="item.count !== null">{{ item.count.toLocaleString('zh-CN') }} 字</span
              ><span v-else>{{
                data.docLoadErrors[item.doc.id] ? '正文读取失败' : '正在读取字数…'
              }}</span
              ><span class="continue">继续编辑 <i class="ri-arrow-right-s-line" /></span
            ></small>
          </RouterLink>
          <button
            v-if="item.count === null && data.docLoadErrors[item.doc.id]"
            class="retry-body"
            @click="data.ensureDocLoaded(item.doc.id)"
          >
            重试读取正文
          </button>
        </li>
      </ul>
      <div v-else class="state empty">
        <i class="ri-quill-pen-line" /><b>从一条小记开始</b
        ><span>记录灵感，之后可以随时回来继续写。</span
        ><RouterLink to="/notes">写第一条小记</RouterLink>
      </div>
    </section>
    <section class="daily-card projects-card" aria-labelledby="home-projects-title">
      <header>
        <div>
          <h2 id="home-projects-title">项目速达</h2>
          <p>未完成的项目</p>
        </div>
        <RouterLink to="/projects">全部项目 <i class="ri-arrow-right-s-line" /></RouterLink>
      </header>
      <p v-if="data.loadErrors.projects" class="state error" role="alert">
        项目读取失败 <button @click="data.hydrate()">重试</button>
      </p>
      <p
        v-else-if="['idle', 'loading'].includes(data.loadStates.projects)"
        class="state"
        role="status"
      >
        正在读取项目…
      </p>
      <div v-else-if="recentProjects.length" class="project-list">
        <RouterLink
          v-for="project in recentProjects"
          :key="project.id"
          :to="`/projects/${project.id}`"
          class="project-link"
          ><i :class="project.icon || 'ri-stack-line'" /><span
            ><b>{{ project.name }}</b
            ><small>{{ project.status }}</small></span
          ><i class="ri-arrow-right-s-line"
        /></RouterLink>
      </div>
      <p v-else class="state">
        暂时没有未完成的项目，<RouterLink to="/projects">去项目列表看看</RouterLink>。
      </p>
    </section>
  </div>
</template>

<style scoped>
.daily-workspace {
  grid-column: 1/-1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}
.daily-card {
  min-width: 0;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  overflow: hidden;
}
.daily-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px 12px;
}
.daily-card h2 {
  font-size: 16px;
  margin: 0;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}
.daily-card h2 span {
  font-size: 12px;
  border-radius: 6px;
  padding: 2px 7px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-2);
}
header p {
  font-size: 12px;
  color: var(--wao-text-3);
  margin: 6px 0 0;
}
a {
  color: var(--wao-primary-text);
  text-decoration: none;
}
header > a {
  font-size: 12px;
  white-space: nowrap;
}
button {
  font: inherit;
  cursor: pointer;
}
.task-list,
.writing-list {
  list-style: none;
  margin: 0;
  padding: 0 20px 8px;
}
.task-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  text-align: left;
  border: 0;
  border-top: 1px solid var(--wao-line);
  padding: 13px 0;
  background: transparent;
  color: var(--wao-text);
}
.row-main {
  flex: 1;
  min-width: 0;
}
.row-main b,
.writing-row > b,
.project-link b {
  display: block;
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-main small,
.project-link small {
  display: block;
  margin-top: 5px;
  color: var(--wao-text-3);
  font-size: 11px;
}
.task-mark {
  color: var(--wao-primary);
  font-size: 18px;
}
.due {
  font-size: 11px;
  white-space: nowrap;
  color: var(--wao-text-3);
}
.overdue {
  color: var(--wao-danger);
}
.task-row:hover .row-main b,
.writing-row:hover > b {
  color: var(--wao-primary-text);
}
.writing-row {
  display: block;
  padding: 11px 0;
  border-top: 1px solid var(--wao-line);
  color: var(--wao-text);
}
.writing-row p {
  margin: 6px 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--wao-text-2);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow-wrap: anywhere;
}
.writing-row small {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  color: var(--wao-text-3);
  font-size: 11px;
}
.continue {
  margin-left: auto;
  color: var(--wao-primary-text);
}
.state {
  padding: 18px 20px;
  color: var(--wao-text-3);
  font-size: 13px;
  line-height: 1.7;
  margin: 0;
}
.state button,
.retry-body {
  border: 1px solid var(--wao-line-control);
  border-radius: 6px;
  background: var(--wao-bg-card);
  color: var(--wao-primary-text);
  padding: 3px 8px;
  margin-left: 8px;
}
.error {
  color: var(--wao-danger);
}
.empty {
  min-height: 215px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  text-align: center;
}
.empty > i {
  font-size: 28px;
  color: var(--wao-primary);
  opacity: 0.65;
}
.empty b {
  font-weight: 500;
  color: var(--wao-text-2);
}
.more {
  display: block;
  padding: 0 20px 16px;
  font-size: 12px;
}
.projects-card {
  grid-column: 1/-1;
}
.project-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 0 20px 18px;
}
.project-link {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-text);
}
.project-link > span {
  min-width: 0;
  flex: 1;
}
.project-link > i:first-child {
  color: var(--wao-primary);
  font-size: 20px;
}
.retry-body {
  margin: 0 0 8px;
}
a:focus-visible,
button:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 3px;
}
@media (max-width: 900px) {
  .daily-workspace {
    grid-template-columns: 1fr;
  }
  .project-list {
    grid-template-columns: 1fr;
  }
  .empty {
    min-height: 150px;
  }
}
@media (max-width: 480px) {
  .daily-card header {
    padding: 14px;
  }
  .task-list,
  .writing-list {
    padding-left: 14px;
    padding-right: 14px;
  }
  .project-list {
    padding: 0 14px 14px;
  }
  .due {
    white-space: normal;
    max-width: 66px;
    text-align: right;
  }
}
</style>
