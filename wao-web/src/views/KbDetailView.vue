<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useTabsStore } from '@/stores/tabs'
import { toast } from '@/composables/toast'
import type { DocBlock, DocLink } from '@/types'

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
const knEditing = ref(false)
const docWide = ref(false)
const folderOpen = reactive<Record<string, boolean>>({})
const scrollEl = ref<HTMLElement | null>(null)
const bodyEl = ref<HTMLTextAreaElement | null>(null)

watch(
  () => route.query.doc,
  (v) => {
    if (v) {
      docId.value = String(v)
      knEditing.value = false
    }
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
  return session.memberById(a || 'wg')
})
const peer = computed(() => session.memberById(session.currentUser.id === 'wg' ? 'wd' : 'wg'))
const hasEdit = computed(() => (docId.value ? data.docEdits[docId.value]?.text != null : false))
const updatedLabel = computed(() => (hasEdit.value ? '刚刚' : (base.value?.updated ?? '—')))

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

/** 渲染块，给标题分配锚点 id。 */
const renderBlocks = computed(() => {
  let hi = 0
  return (doc.value?.blocks ?? []).map((b: DocBlock) => {
    const hid = b.h ? `doc-h-${hi++}` : ''
    return { ...b, hid }
  })
})
const toc = computed(() => renderBlocks.value.filter((b) => b.h).map((b) => ({ text: b.h!, hid: b.hid })))

function isOpen(fid: string) {
  return folderOpen[fid] !== false
}
function toggleFolder(fid: string) {
  folderOpen[fid] = !isOpen(fid)
}
function selectDoc(id: string) {
  docId.value = id
  knEditing.value = false
}
function openDoc(id: string) {
  const k = data.kbs.find((kk) => kk.folders.some((f) => f.docs.includes(id)))
  if (k && k.id !== kbId.value) router.push({ path: `/kb/${k.id}`, query: { doc: id } })
  else selectDoc(id)
}
function addDocTo(folderId: string) {
  const id = data.addDoc(kbId.value, folderId)
  if (id) {
    folderOpen[folderId] = true
    docId.value = id
    knEditing.value = true
    toast('已创建文档，可直接编辑（实时保存）', 'ri-file-add-line')
    nextTick(() => bodyEl.value?.focus())
  }
}
function addFolder() {
  data.addFolder(kbId.value)
  toast('已创建文件夹（演示，仅本次会话保留）', 'ri-folder-add-line')
}
function editTitle(e: Event) {
  if (docId.value) data.setDocEdit(docId.value, { title: (e.target as HTMLInputElement).value })
}
function editBody(e: Event) {
  if (docId.value) data.setDocEdit(docId.value, { text: (e.target as HTMLTextAreaElement).value })
}
function enterEdit() {
  knEditing.value = true
  nextTick(() => bodyEl.value?.focus())
}
function tocGo(hid: string) {
  const el = scrollEl.value?.querySelector(`#${hid}`) as HTMLElement | null
  const c = scrollEl.value
  if (el && c) c.scrollTop = Math.max(0, el.getBoundingClientRect().top - c.getBoundingClientRect().top + c.scrollTop - 24)
}
function gotoProject() {
  if (kb.value?.project) router.push(`/projects/${kb.value.project}`)
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
            <button class="folder-btn" @click="toggleFolder(f.id)">
              <i :class="isOpen(f.id) ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'" class="chev"></i>
              <i class="ri-folder-3-line folder-icon"></i>
              <span class="folder-name">{{ f.name }}</span>
              <span class="folder-count wao-tabular">{{ f.docs.length }}</span>
            </button>
            <button class="folder-add" title="在此文件夹新建文档" @click="addDocTo(f.id)"><i class="ri-add-line"></i></button>
          </div>
          <div v-if="isOpen(f.id)" class="doc-tree">
            <button
              v-for="dId in f.docs"
              :key="dId"
              class="doc-node"
              :class="{ on: docId === dId }"
              @click="selectDoc(dId)"
            >
              <i :class="data.docById(dId)?.auto ? 'ri-flashlight-line' : 'ri-file-text-line'" class="doc-node-icon"></i>
              <span class="doc-node-title">{{ data.docOf(dId).title || '未命名文档' }}</span>
            </button>
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
            <span class="ellip">{{ doc.title }}</span>
            <span v-if="base?.auto" class="tag-auto">自动沉淀</span>
            <div class="spacer"></div>
            <span class="collab">
              <span class="c-av" :title="`${session.currentUser.name}（我）`" :style="{ background: session.currentUser.color }">{{ session.currentUser.initial }}</span>
              <span class="c-av peer" :title="`${peer.name} 正在查看`" :style="{ background: peer.color }">{{ peer.initial }}</span>
            </span>
            <span class="realtime"><span class="rt-dot"></span>实时协同</span>
            <span v-if="hasEdit" class="saved"><i class="ri-checkbox-circle-line"></i>已保存</span>
            <button class="width-toggle" :title="docWide ? '切换为窄视图' : '切换为宽视图'" @click="docWide = !docWide"><i class="ri-arrow-left-right-line"></i></button>
            <button v-if="knEditing" class="done-btn" @click="knEditing = false"><i class="ri-check-line"></i>完成</button>
          </div>

          <template v-if="doc">
            <input class="doc-title" :value="doc.title" placeholder="未命名文档" @input="editTitle" />

            <template v-if="knEditing">
              <div class="edit-hint">正在编辑 · 每次输入实时保存并同步给协作者（演示保存在本机）· 「## 」标题 · 「- 」列表 · 「&gt; 」引用</div>
              <textarea ref="bodyEl" class="doc-body" :value="doc.text" rows="18" placeholder="开始写作…" @input="editBody"></textarea>
            </template>

            <template v-else>
              <div class="doc-meta">
                <span class="m-av" :style="{ background: author.color }">{{ author.initial }}</span>
                <span class="m-name">{{ author.name }}</span>
                <span>创建于 {{ base?.created ?? '—' }}</span><span>·</span><span>更新于 {{ updatedLabel }}</span>
              </div>
              <div v-if="docLinks.length" class="doc-links">
                <span class="dl-label">关联</span>
                <button v-for="(l, i) in docLinks" :key="i" class="dl-chip" @click="goLink(l.link)">
                  <i :class="l.icon"></i><span class="ellip">{{ l.label }}</span>
                </button>
              </div>
              <div class="doc-hr"></div>
              <div class="doc-render" title="点击开始编辑" @click="enterEdit">
                <div v-if="renderBlocks.length === 0" class="doc-empty">这篇文档还是空的——点击此处开始写作。</div>
                <template v-for="(b, i) in renderBlocks" :key="i">
                  <div v-if="b.call" class="blk-call"><i class="ri-information-line"></i><span>{{ b.call }}</span></div>
                  <h2 v-else-if="b.h" :id="b.hid" class="blk-h">{{ b.h }}</h2>
                  <p v-else-if="b.p" class="blk-p">{{ b.p }}</p>
                  <div v-else-if="b.ul" class="blk-ul">
                    <div v-for="(li, j) in b.ul" :key="j" class="blk-li"><span class="li-dot"></span><span>{{ li }}</span></div>
                  </div>
                  <div v-else-if="b.q" class="blk-q"><div class="q-text">{{ b.q[0] }}</div><div class="q-by">—— {{ b.q[1] }}</div></div>
                </template>
              </div>
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
          </template>
          <div v-else class="doc-empty pad">这个知识库还没有文档——在左侧文件夹点 + 新建。</div>
        </div>

        <div v-if="toc.length > 1 && !knEditing" class="toc">
          <div class="toc-label">目录</div>
          <div class="toc-list">
            <button v-for="(t, i) in toc" :key="i" class="toc-item" @click="tocGo(t.hid)">{{ t.text }}</button>
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
.folder-add {
  width: 24px;
  height: 24px;
  border: none;
  background: none;
  border-radius: 6px;
  cursor: pointer;
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.folder-add:hover {
  background: #e9eaef;
  color: var(--wao-primary);
}
.folder-add i {
  font-size: 14px;
}
.doc-tree {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 1px 0 2px;
}
.doc-node {
  width: 100%;
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
.width-toggle {
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
.width-toggle:hover {
  background: #f4f5f8;
}
.width-toggle i {
  font-size: 13px;
}
.done-btn {
  height: 28px;
  padding: 0 12px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 7px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.done-btn:hover {
  background: var(--wao-primary-hover);
}
.done-btn i {
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
.edit-hint {
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
.doc-body {
  margin-top: 10px;
  width: 100%;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  padding: 14px 16px;
  font-size: 14px;
  line-height: 1.9;
  color: #33363f;
  outline: none;
  resize: vertical;
  background: #fff;
}
.doc-body:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.1);
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
.doc-render {
  cursor: text;
}
.doc-empty {
  margin-top: 26px;
  color: var(--wao-text-faint);
  font-size: 13px;
}
.doc-empty.pad {
  padding: 40px 0;
}
.blk-call {
  margin: 20px 0 0;
  background: var(--wao-primary-tint);
  border: 1px solid #e3e9fb;
  border-radius: 10px;
  padding: 12px 16px;
  font-size: 13px;
  color: var(--wao-text-2);
  line-height: 1.8;
  display: flex;
  gap: 10px;
}
.blk-call i {
  color: var(--wao-primary);
  font-size: 15px;
  flex-shrink: 0;
  margin-top: 3px;
}
.blk-h {
  margin: 30px 0 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--wao-text);
}
.blk-p {
  margin: 12px 0 0;
  font-size: 14.5px;
  line-height: 1.95;
  color: #33363f;
}
.blk-ul {
  margin: 10px 0 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.blk-li {
  display: flex;
  gap: 10px;
  font-size: 14px;
  line-height: 1.85;
  color: #33363f;
}
.li-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #9da8d8;
  flex-shrink: 0;
  margin-top: 11px;
}
.blk-q {
  margin: 16px 0 0;
  border-left: 3px solid #c7d0f1;
  padding: 4px 0 4px 16px;
}
.q-text {
  font-size: 14.5px;
  line-height: 1.85;
  color: #4a4e5b;
}
.q-by {
  margin-top: 4px;
  font-size: 12px;
  color: var(--wao-text-weak);
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
