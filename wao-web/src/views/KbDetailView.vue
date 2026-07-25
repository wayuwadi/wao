<script setup lang="ts">
/**
 * 知识库详情：左边是文件夹 / 文档树，右边是文档本身。
 *
 * 文档不再有「查看 / 编辑」两态 —— 打开就能写，跟任务描述一个手感（ADR-015）。
 * 输入停顿即自动保存，右上角显示保存状态与此刻同在这篇文档里的人（ADR-017）。
 */
import { computed, defineAsyncComponent, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useTabsStore } from '@/stores/tabs'
import { useDocPresence } from '@/composables/docPresence'
import { toast } from '@/composables/toast'
import { absoluteTime, relativeTime } from '@/utils/time'
import { useNow } from '@/composables/now'
import type { DocLink } from '@/types'
// 异步加载，理由同 TaskDrawer：ProseMirror 不进首屏包。
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))

const route = useRoute()
const router = useRouter()
const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const tabs = useTabsStore()

const kbId = computed(() => String(route.params.id))
const kb = computed(() => data.kbById(kbId.value))

const firstDocId = () => {
  for (const f of kb.value?.folders ?? []) if (f.docs.length) return f.docs[0]!
  return null
}
const docId = ref<string | null>((route.query.doc as string) || firstDocId())
const docWide = ref(false)
const folderOpen = reactive<Record<string, boolean>>({})
const scrollEl = ref<HTMLElement | null>(null)
const titleEl = ref<HTMLInputElement | null>(null)
/** 正在重命名的文件夹 id。 */
const renamingFolder = ref<string | null>(null)
const { peers, setCaretLine, markTyping } = useDocPresence(docId)
/** 相对时间文案要自己走起来，否则「刚刚」会一直停在打开那一刻。 */
const now = useNow()

watch(
  () => route.query.doc,
  (v) => {
    if (v) docId.value = String(v)
  },
)
onMounted(() => {
  if (kb.value) tabs.setLabel(`kb:${kbId.value}`, kb.value.name)
})

const doc = computed(() => (docId.value ? data.docOf(docId.value) : null))
const base = computed(() => doc.value?.base ?? null)
const author = computed(() => {
  const a = base.value?.author ?? ''
  if (a === 'auto') return { name: '自动化', initial: '自', color: '#B9821F' }
  return session.memberById(a)
})
/** 正文：改一个字就走去抖写透，不需要「完成」按钮。 */
const body = computed({
  get: () => doc.value?.text ?? '',
  set: (v: string) => {
    if (!docId.value) return
    data.setDocEdit(docId.value, { text: v })
    markTyping()
  },
})
const saving = computed(() => (docId.value ? data.docPending(docId.value) : false))
const createdLabel = computed(() => (base.value?.created ? relativeTime(base.value.created, now.value) : '—'))
const updatedLabel = computed(() => (base.value?.updated ? relativeTime(base.value.updated, now.value) : '—'))

const TYPE_ICON: Record<string, string> = { project: 'ri-stack-line', task: 'ri-checkbox-circle-line', doc: 'ri-file-text-line' }
function linkName(l: DocLink) {
  if (l.type === 'project') return '项目 · ' + (data.projectById(l.id)?.name ?? '')
  if (l.type === 'task') return '任务 · ' + (data.taskById(l.id)?.title ?? '')
  return '文档 · ' + (data.docOf(l.id).title ?? '')
}
function goLink(l: DocLink) {
  if (l.type === 'project') router.push(`/projects/${l.id}`)
  else if (l.type === 'task') ui.openTask(l.id)
  else openDoc(l.id)
}
const docLinks = computed(() =>
  (base.value?.links ?? []).map((l) => ({ icon: TYPE_ICON[l.type]!, label: linkName(l), link: l })),
)
const backlinks = computed(() =>
  (base.value?.backlinks ?? []).map((b) => ({ icon: TYPE_ICON[b.type]!, label: linkName(b), snippet: b.snippet, link: b })),
)

/** 目录：正文里的二级标题。 */
const toc = computed(() => (doc.value?.blocks ?? []).map((b, i) => ({ text: b.h, i })).filter((t) => t.text))

function isOpen(fid: string) {
  return folderOpen[fid] !== false
}
function toggleFolder(fid: string) {
  folderOpen[fid] = !isOpen(fid)
}
function selectDoc(id: string) {
  docId.value = id
}
function openDoc(id: string) {
  const k = data.kbs.find((kk) => kk.folders.some((f) => f.docs.includes(id)))
  if (k && k.id !== kbId.value) router.push({ path: `/kb/${k.id}`, query: { doc: id } })
  else selectDoc(id)
}
async function addDocTo(folderId: string) {
  const id = await data.addDoc(kbId.value, folderId)
  if (id) {
    folderOpen[folderId] = true
    docId.value = id
    toast('已创建文档并关联到本知识库', 'ri-file-add-line')
    // 新文档标题是空的，光标直接落在标题上，敲进去就是自己的标题
    nextTick(() => titleEl.value?.focus())
  }
}
async function addFolder() {
  const id = await data.addFolder(kbId.value)
  if (id) renamingFolder.value = id
  toast('已创建文件夹', 'ri-folder-add-line')
}
function editTitle(e: Event) {
  if (docId.value) data.setDocEdit(docId.value, { title: (e.target as HTMLInputElement).value })
}
function commitFolderName(folderId: string, e: Event) {
  const name = (e.target as HTMLInputElement).value.trim()
  data.renameFolder(kbId.value, folderId, name || '新建文件夹')
  renamingFolder.value = null
}
async function removeFolder(folderId: string) {
  const folder = kb.value?.folders.find((f) => f.id === folderId)
  if (!folder) return
  const gone = folder.docs.includes(docId.value ?? '')
  await data.deleteFolder(kbId.value, folderId)
  if (gone) docId.value = firstDocId()
  toast(`已把「${folder.name}」放进回收站，7 天内可恢复`, 'ri-delete-bin-line')
}
async function removeDoc(id: string) {
  const title = data.docOf(id).title || '未命名文档'
  await data.deleteDoc(id)
  if (docId.value === id) docId.value = firstDocId()
  toast(`已把「${title}」放进回收站，7 天内可恢复`, 'ri-delete-bin-line')
}
function tocGo(index: number) {
  const el = scrollEl.value?.querySelectorAll('.doc-body h2')[index] as HTMLElement | undefined
  const c = scrollEl.value
  if (el && c) c.scrollTop = Math.max(0, el.getBoundingClientRect().top - c.getBoundingClientRect().top + c.scrollTop - 24)
}
function gotoProject() {
  if (kb.value?.project) router.push(`/projects/${kb.value.project}`)
}
/** 粘贴 / 拖入的图片存进图床，再把地址回插到光标处。 */
async function onUploadImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) {
    toast('图片超过 10MB，已跳过', 'ri-error-warning-line')
    done(null, '')
    return
  }
  const ref = await data.uploadImage(file, `文档 · ${doc.value?.title || '未命名文档'}`)
  if (!ref) toast('图片上传失败，请重试', 'ri-error-warning-line')
  done(ref?.url ?? null, ref?.name ?? file.name)
}
</script>

<template>
  <div v-if="!kb" class="missing">知识库不存在。</div>
  <div v-else class="kb-detail">
    <!-- 左栏 -->
    <div class="side">
      <div class="side-top">
        <button class="back" @click="router.push('/knowledge')"><i class="ri-arrow-left-line"></i>全部知识库</button>
        <div class="side-kb">
          <div class="side-icon" :style="{ background: kb.color }"><i :class="kb.icon"></i></div>
          <div class="side-name">{{ kb.name }}</div>
        </div>
        <button v-if="kb.project" class="proj-chip" @click="gotoProject">
          <i class="ri-stack-line"></i><span class="ellip">关联项目 · {{ data.projectById(kb.project)?.name }}</span>
        </button>
        <button class="new-folder" @click="addFolder"><i class="ri-folder-add-line"></i>新建文件夹</button>
      </div>

      <div class="tree">
        <div v-for="f in kb.folders" :key="f.id">
          <div class="folder-row">
            <input
              v-if="renamingFolder === f.id"
              class="folder-input"
              :value="f.name"
              autofocus
              @keydown.enter="commitFolderName(f.id, $event)"
              @blur="commitFolderName(f.id, $event)"
            />
            <template v-else>
              <button class="folder-btn" @click="toggleFolder(f.id)">
                <i :class="isOpen(f.id) ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'" class="chev"></i>
                <i class="ri-folder-3-line folder-icon"></i>
                <span class="folder-name">{{ f.name }}</span>
                <span class="folder-count wao-tabular">{{ f.docs.length }}</span>
              </button>
              <button class="row-act" title="重命名文件夹" @click="renamingFolder = f.id"><i class="ri-edit-line"></i></button>
              <button class="row-act danger" title="删除文件夹（进回收站）" @click="removeFolder(f.id)"><i class="ri-delete-bin-line"></i></button>
              <button class="row-act" title="在此文件夹新建文档" @click="addDocTo(f.id)"><i class="ri-add-line"></i></button>
            </template>
          </div>
          <div v-if="isOpen(f.id)" class="doc-tree">
            <div v-for="dId in f.docs" :key="dId" class="doc-row">
              <button class="doc-node" :class="{ on: docId === dId }" @click="selectDoc(dId)">
                <i :class="data.docById(dId)?.auto ? 'ri-flashlight-line' : 'ri-file-text-line'" class="doc-node-icon"></i>
                <span class="doc-node-title">{{ data.docOf(dId).title || '未命名文档' }}</span>
              </button>
              <button class="row-act danger" title="删除文档（进回收站）" @click="removeDoc(dId)">
                <i class="ri-delete-bin-line"></i>
              </button>
            </div>
            <div v-if="!f.docs.length" class="folder-empty">还没有文档 —— 点右侧 + 新建</div>
          </div>
        </div>
      </div>

      <div v-if="kb.auto" class="sync-bar">
        <div class="sync-title"><span class="sync-dot"></span>飞书群聊 · 每日自动同步沉淀</div>
        <div class="sync-sub">上次 今天 06:12 · 新增 12 条 · <button class="link" @click="router.push('/automation')">查看自动化</button></div>
      </div>
    </div>

    <!-- 文档区 -->
    <div ref="scrollEl" class="doc-scroll">
      <div class="doc-wrap">
        <div class="doc-col" :style="{ maxWidth: docWide ? '1080px' : '760px' }">
          <div v-if="doc" class="doc-bar">
            <span class="ellip">{{ kb.name }}</span>
            <i class="ri-arrow-right-s-line b-sep"></i>
            <span class="ellip">{{ doc.title || '未命名文档' }}</span>
            <span v-if="base?.auto" class="tag-auto">自动沉淀</span>
            <div class="spacer"></div>

            <!-- 协同：只有此刻真的打开着这篇文档的人才会出现 -->
            <span class="collab">
              <span class="c-av" :title="`${session.currentUser.name}（我）`" :style="{ background: session.currentUser.color }">
                {{ session.currentUser.initial }}
              </span>
              <span
                v-for="p in peers"
                :key="p.id"
                class="c-av peer"
                :title="`${p.name} 正在${p.editing ? '编辑' : '查看'}`"
                :style="{ background: p.color }"
              >{{ p.initial }}</span>
            </span>
            <span v-if="peers.length" class="realtime"><span class="rt-dot"></span>{{ peers.length + 1 }} 人在线</span>

            <span class="saved">
              <i :class="saving ? 'ri-loader-4-line spin' : 'ri-checkbox-circle-line'"></i>{{ saving ? '保存中' : '已保存' }}
            </span>
            <button class="icon-act" :title="docWide ? '切换为窄视图' : '切换为宽视图'" @click="docWide = !docWide">
              <i class="ri-arrow-left-right-line"></i>
            </button>
            <button class="icon-act danger" title="删除这篇文档（进回收站）" @click="removeDoc(docId!)">
              <i class="ri-delete-bin-line"></i>
            </button>
          </div>

          <template v-if="doc">
            <input ref="titleEl" class="doc-title" :value="doc.title" placeholder="未命名文档" @input="editTitle" />

            <div class="doc-meta">
              <span class="m-av" :style="{ background: author.color }">{{ author.initial }}</span>
              <span class="m-name">{{ author.name }}</span>
              <span :title="absoluteTime(base?.created ?? '')">创建于 {{ createdLabel }}</span>
              <span>·</span>
              <span :title="absoluteTime(base?.updated ?? '')">更新于 {{ updatedLabel }}</span>
            </div>
            <div v-if="docLinks.length" class="doc-links">
              <span class="dl-label">关联</span>
              <button v-for="(l, i) in docLinks" :key="i" class="dl-chip" @click="goLink(l.link)">
                <i :class="l.icon"></i><span class="ellip">{{ l.label }}</span>
              </button>
            </div>
            <div class="doc-hr"></div>

            <MarkdownEditor
              v-model="body"
              class="doc-body"
              placeholder="开始写作…敲 ## 变标题、- 变列表，可直接粘贴截图"
              :min-height="360"
              :peers="peers"
              @caret-line="setCaretLine"
              @upload-image="onUploadImage"
            />

            <div v-if="backlinks.length" class="backlinks">
              <div class="bl-title"><i class="ri-arrow-go-back-line"></i>反向链接</div>
              <div v-for="(bl, i) in backlinks" :key="i" class="bl-row" @click="goLink(bl.link)">
                <i :class="bl.icon" class="bl-icon"></i>
                <div class="bl-main">
                  <div class="bl-label">{{ bl.label }}</div>
                  <div class="bl-snippet">{{ bl.snippet }}</div>
                </div>
                <i class="ri-arrow-right-s-line bl-chev"></i>
              </div>
            </div>
          </template>
          <div v-else class="doc-empty pad">这个知识库还没有文档——在左侧文件夹点 + 新建。</div>
        </div>

        <div v-if="toc.length > 1" class="toc">
          <div class="toc-label">目录</div>
          <div class="toc-list">
            <button v-for="(t, i) in toc" :key="i" class="toc-item" @click="tocGo(i)">{{ t.text }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.missing {
  padding: 60px 24px;
  text-align: center;
  color: var(--wao-text-weak);
}
.kb-detail {
  display: flex;
  height: 100%;
  min-height: 0;
  animation: waoFade 0.2s ease;
}
.spacer {
  flex: 1;
}
.ellip {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 左栏 */
.side {
  width: 262px;
  flex-shrink: 0;
  border-right: 1px solid var(--wao-line);
  background: var(--wao-bg-sub);
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.side-top {
  padding: 10px 12px 8px;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: none;
  color: #7a7d89;
  font-size: 12px;
  cursor: pointer;
  padding: 3px 6px;
  margin-left: -6px;
  border-radius: 6px;
}
.back:hover {
  background: var(--wao-divider);
  color: var(--wao-text-2);
}
.back i {
  font-size: 13px;
}
.side-kb {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.side-icon {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 13px;
}
.side-name {
  flex: 1;
  min-width: 0;
  font-size: 13.5px;
  font-weight: 700;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.proj-chip {
  margin-top: 7px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--wao-line-control);
  background: #fff;
  border-radius: 999px;
  padding: 2.5px 10px;
  font-size: 11.5px;
  color: var(--wao-primary-text);
  cursor: pointer;
  max-width: 100%;
}
.proj-chip:hover {
  border-color: #c7d0f1;
  background: var(--wao-primary-tint);
}
.proj-chip i {
  font-size: 12px;
}
.new-folder {
  margin-top: 8px;
  width: 100%;
  height: 29px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px dashed #d3d6df;
  background: none;
  border-radius: 8px;
  cursor: pointer;
  color: var(--wao-text-3);
  font-size: 12px;
}
.new-folder:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary);
}
.new-folder i {
  font-size: 13px;
}
.tree {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px 8px;
}
.folder-row {
  display: flex;
  align-items: center;
  margin-top: 4px;
}
.folder-btn {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: none;
  border-radius: 7px;
  padding: 6px;
  cursor: pointer;
  min-width: 0;
}
.folder-btn:hover {
  background: #f0f1f4;
}
.folder-input {
  flex: 1;
  min-width: 0;
  height: 27px;
  border: 1px solid var(--wao-primary);
  border-radius: 7px;
  padding: 0 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wao-text);
  outline: none;
  background: #fff;
}
.chev {
  font-size: 14px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.folder-icon {
  font-size: 14px;
  color: #b9a15e;
  flex-shrink: 0;
}
.folder-name {
  flex: 1;
  text-align: left;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wao-text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.folder-count {
  font-size: 11px;
  color: var(--wao-text-faint);
}
/* 行内操作按钮：平时淡出，鼠标进到这一行才明显起来 */
.row-act {
  width: 22px;
  height: 22px;
  border: none;
  background: none;
  border-radius: 6px;
  cursor: pointer;
  color: var(--wao-text-faint-2);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.12s;
}
.folder-row:hover .row-act,
.doc-row:hover .row-act {
  opacity: 1;
}
.row-act:hover {
  background: #e9eaef;
  color: var(--wao-primary);
}
.row-act.danger:hover {
  background: var(--wao-danger-bg);
  color: #bc4141;
}
.row-act i {
  font-size: 13px;
}
.doc-tree {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 1px 0 2px;
}
.doc-row {
  display: flex;
  align-items: center;
}
.doc-node {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 7px;
  border: none;
  border-radius: 7px;
  padding: 5.5px 6px 5.5px 28px;
  cursor: pointer;
  background: transparent;
}
.doc-node:hover {
  background: #f0f1f4;
}
.doc-node.on {
  background: var(--wao-primary-soft);
}
.doc-node-icon {
  font-size: 13px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.doc-node.on .doc-node-icon {
  color: var(--wao-primary);
}
.doc-node-title {
  flex: 1;
  text-align: left;
  font-size: 12.5px;
  color: var(--wao-text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.doc-node.on .doc-node-title {
  color: var(--wao-primary-text);
  font-weight: 600;
}
.folder-empty {
  padding: 4px 6px 4px 28px;
  font-size: 11.5px;
  color: var(--wao-text-faint-2);
}
.sync-bar {
  border-top: 1px solid var(--wao-line);
  padding: 10px 12px;
  background: #f7f8fa;
}
.sync-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--wao-text-2);
}
.sync-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--wao-success);
  animation: waoPulse 2s infinite;
}
.sync-sub {
  margin-top: 3px;
  padding-left: 14px;
  font-size: 11px;
  color: var(--wao-text-weak);
}
.link {
  border: none;
  background: none;
  color: var(--wao-primary);
  font-size: 11px;
  cursor: pointer;
  padding: 0;
}

/* 文档区 */
.doc-scroll {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  background: #fff;
}
.doc-wrap {
  display: flex;
  justify-content: center;
  gap: 26px;
  padding: 28px 36px 64px;
}
.doc-col {
  flex: 1;
  min-width: min(420px, 100%);
}
.doc-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--wao-text-weak);
  flex-wrap: wrap;
}
.b-sep {
  font-size: 13px;
  color: var(--wao-text-faint-2);
}
.tag-auto {
  font-size: 10.5px;
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  border-radius: 4px;
  padding: 1px 6px;
}
.collab {
  display: flex;
  align-items: center;
  margin-right: 2px;
}
.c-av {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  color: #fff;
  font-size: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #fff;
}
.c-av.peer {
  margin-left: -5px;
}
.realtime {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: var(--wao-success-strong);
  white-space: nowrap;
}
.rt-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--wao-success);
  animation: waoPulse 2s infinite;
}
.saved {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: var(--wao-success-strong);
}
.saved i {
  font-size: 12px;
}
.spin {
  animation: waoSpin 0.9s linear infinite;
}
@keyframes waoSpin {
  to {
    transform: rotate(360deg);
  }
}
.icon-act {
  width: 28px;
  height: 28px;
  border: 1px solid var(--wao-line-control);
  background: #fff;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.icon-act:hover {
  background: #f4f5f8;
}
.icon-act.danger:hover {
  background: var(--wao-danger-bg);
  border-color: #f0c9c9;
  color: #bc4141;
}
.icon-act i {
  font-size: 13px;
}
.doc-title {
  margin-top: 14px;
  width: 100%;
  border: none;
  outline: none;
  font-size: 27px;
  font-weight: 700;
  color: var(--wao-text);
  background: none;
  padding: 2px 0;
  border-radius: 6px;
}
.doc-title:focus {
  background: #f7f8fb;
  box-shadow: 0 0 0 3px #f7f8fb;
}
.doc-title::placeholder {
  color: var(--wao-text-faint-2);
}
.doc-meta {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  font-size: 12.5px;
  color: var(--wao-text-weak);
}
.m-av {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  color: #fff;
  font-size: 10.5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.m-name {
  color: var(--wao-text-2);
}
.doc-links {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}
.dl-label {
  font-size: 12px;
  color: var(--wao-text-faint);
}
.dl-chip {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-sub);
  border-radius: 999px;
  font-size: 12px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 280px;
}
.dl-chip:hover {
  border-color: #c7d0f1;
  color: var(--wao-primary-text);
  background: var(--wao-primary-tint);
}
.dl-chip i {
  font-size: 13px;
}
.doc-hr {
  margin-top: 20px;
  border-top: 1px solid var(--wao-divider);
}
/* 正文按文档的字号排版，比任务描述宽松一档 */
.doc-body {
  margin-top: 12px;
}
.doc-body :deep(.tiptap) {
  font-size: 14.5px;
  line-height: 1.95;
  color: #33363f;
  padding: 4px 0;
}
.doc-body :deep(h2) {
  font-size: 18px;
  margin: 26px 0 6px;
}
.doc-body :deep(h3) {
  font-size: 16px;
}
.doc-empty {
  margin-top: 26px;
  color: var(--wao-text-faint);
  font-size: 13px;
}
.doc-empty.pad {
  padding: 40px 0;
}
.backlinks {
  margin-top: 36px;
  background: var(--wao-bg-sub);
  border: 1px solid var(--wao-divider);
  border-radius: 12px;
  padding: 14px 16px;
}
.bl-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-text-2);
}
.bl-title i {
  font-size: 14px;
  color: var(--wao-text-weak);
}
.bl-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px;
  margin: 0 -8px;
  border-radius: 8px;
  cursor: pointer;
}
.bl-row:hover {
  background: var(--wao-bg-hover-2);
}
.bl-icon {
  font-size: 14px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.bl-main {
  flex: 1;
  min-width: 0;
}
.bl-label {
  font-size: 12.5px;
  color: var(--wao-primary-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bl-snippet {
  font-size: 11.5px;
  color: var(--wao-text-weak);
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bl-chev {
  font-size: 14px;
  color: var(--wao-text-faint-2);
  flex-shrink: 0;
}

/* TOC */
.toc {
  width: 168px;
  flex-shrink: 0;
  align-self: flex-start;
  position: sticky;
  top: 20px;
  padding-top: 46px;
}
@media (max-width: 1180px) {
  .toc {
    display: none;
  }
}
.toc-label {
  font-size: 11px;
  color: var(--wao-text-weak);
  letter-spacing: 1.5px;
  margin-bottom: 8px;
}
.toc-list {
  border-left: 2px solid var(--wao-divider);
  display: flex;
  flex-direction: column;
}
.toc-item {
  border: none;
  background: none;
  text-align: left;
  font-size: 12px;
  color: var(--wao-text-3);
  cursor: pointer;
  padding: 5px 10px;
  border-left: 2px solid transparent;
  margin-left: -2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.toc-item:hover {
  color: var(--wao-primary-text);
  border-left-color: var(--wao-primary);
}
</style>
