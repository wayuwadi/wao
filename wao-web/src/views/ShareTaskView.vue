<script setup lang="ts">
/**
 * 公开分享页（免登录）—— 任务或子任务的只读视图。
 *
 * 主任务链接展示描述 + 全部子任务；子任务链接只展示它自身的详情与描述。
 * 页面上给出 Markdown 直读地址，便于整条粘给 AI。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { apiShareSub, apiShareTask } from '@/api/board'
import { renderMarkdown } from '@/utils/markdown'
import { shareMarkdownUrl } from '@/utils/share'
import { copyToClipboard } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import type { SharedTask } from '@/types'
import SiteFooter from '@/components/SiteFooter.vue'

const route = useRoute()
const taskId = computed(() => String(route.params.id))
const subId = computed(() => (route.params.subId ? String(route.params.subId) : null))

/** ?openOnly=true 时只展示未完成的子任务，和复制链接时勾的开关一致。 */
const openOnly = computed(() => route.query.openOnly === 'true')

const shared = ref<SharedTask | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    shared.value = subId.value
      ? await apiShareSub(taskId.value, subId.value)
      : await apiShareTask(taskId.value, openOnly.value)
  } catch {
    shared.value = null
    error.value = '这个分享链接无效，或者对应的任务已被删除。'
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch([taskId, subId, openOnly], load)

const descHtml = computed(() => renderMarkdown(shared.value?.desc ?? ''))
const mdUrl = computed(() => shareMarkdownUrl(taskId.value, subId.value, openOnly.value))
const originalUrl = computed(() => {
  const base = shared.value?.projectId ? `/projects/${shared.value.projectId}` : '/projects'
  const query = new URLSearchParams({ task: taskId.value })
  if (subId.value) query.set('sub', subId.value)
  return `${base}?${query}`
})

async function copyMd() {
  toast(
    (await copyToClipboard(mdUrl.value)) ? 'Markdown 地址已复制，可直接发给 AI' : '复制失败，请手动选中复制',
    'ri-file-copy-line',
  )
}
</script>

<template>
  <div class="share-page">
    <div class="share-card">
      <div v-if="loading" class="state">正在加载…</div>
      <div v-else-if="error" class="state err">{{ error }}</div>

      <template v-else-if="shared">
        <div class="brand">
          <span class="brand-dot"></span>WAO · {{ shared.kind === 'sub' ? '子任务分享' : '任务分享' }}
        </div>
        <a class="locate" :href="originalUrl"><i class="ri-corner-up-left-line"></i>回到 WAO 中的原任务</a>
        <h1 class="title">{{ shared.title }}</h1>

        <div class="meta">
          <span class="chip">{{ shared.status }}</span>
          <span v-if="shared.pr" class="chip">{{ shared.pr }}</span>
          <span v-if="shared.due" class="chip plain">截止 {{ shared.due }}</span>
          <span v-if="shared.project" class="chip plain">{{ shared.project }}</span>
        </div>

        <div class="section-label">描述</div>
        <div v-if="descHtml" class="md-body" v-html="descHtml"></div>
        <div v-else class="empty">（暂无描述）</div>

        <template v-if="shared.images.length">
          <div class="section-label">图片</div>
          <div class="imgs">
            <img v-for="(im, i) in shared.images" :key="i" :src="im.url" :alt="im.name" />
          </div>
        </template>

        <template v-if="shared.pdcaCycles.length">
          <div class="section-label">PDCA 记录 · {{ shared.pdcaCycles.length }} 轮</div>
          <div class="shared-pdca">
            <article v-for="(cycle, index) in shared.pdcaCycles" :key="cycle.id">
              <header><b>第 {{ index + 1 }} 轮</b><span>{{ cycle.reviewedAt ? cycle.outcome : '进行中' }}</span></header>
              <p><em>P</em>{{ cycle.plan || '（尚未填写计划）' }}</p>
              <p><em>C</em>{{ cycle.check || '（尚未复盘）' }}</p>
              <p><em>A</em>{{ cycle.act || '（尚未形成改进行动）' }}</p>
            </article>
          </div>
        </template>

        <template v-if="shared.subs.length">
          <div class="section-label">
            子任务 · {{ shared.subs.length }}
            <span v-if="openOnly" class="filter-note">已过滤掉完成的条目</span>
          </div>
          <div v-for="(s, i) in shared.subs" :key="s.id ?? i" class="sub">
            <div class="sub-head">
              <span class="sub-box" :class="{ done: s.done }"><i class="ri-check-line"></i></span>
              <span class="sub-text" :class="{ done: s.done }">{{ s.text }}</span>
            </div>
            <div v-if="s.desc" class="md-body sub-desc" v-html="renderMarkdown(s.desc)"></div>
            <div v-if="s.images?.length" class="imgs sub-imgs">
              <img v-for="(im, j) in s.images" :key="j" :src="im.url" :alt="im.name" />
            </div>
          </div>
        </template>

        <div class="foot">
          <span class="foot-tip">要交给 AI？复制下面的 Markdown 地址，它一次请求就能读到全文。</span>
          <button class="foot-btn" @click="copyMd"><i class="ri-file-copy-line"></i>复制 Markdown 地址</button>
        </div>
      </template>
    </div>
    <SiteFooter />
  </div>
</template>

<style scoped>
.share-page {
  min-height: 100vh;
  background: var(--wao-bg-page, #f6f7fa);
  padding: 40px 20px 60px;
}
.share-card {
  max-width: 760px;
  margin: 0 auto;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-divider);
  border-radius: 14px;
  padding: 28px 32px 24px;
  box-shadow: 0 1px 3px rgba(16, 18, 28, 0.05);
}
.state {
  padding: 40px 0;
  text-align: center;
  color: var(--wao-text-weak);
  font-size: 13.5px;
}
.state.err {
  color: #bc4141;
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 11.5px;
  letter-spacing: 1px;
  color: var(--wao-text-weak);
}
.locate {
  float: right;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 10px;
  border: 1px solid var(--wao-line-control, #d9dce6);
  border-radius: 8px;
  color: var(--wao-primary, #4358d6);
  font-size: 12px;
  text-decoration: none;
  background: var(--wao-bg-card);
}
.locate:hover { background: var(--wao-primary-soft, #eef1fc); }
.brand-dot {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  background: var(--wao-primary, #4358d6);
}
.title {
  margin: 10px 0 0;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.35;
  color: var(--wao-text, #1a1c23);
}
.meta {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}
.chip {
  font-size: 11.5px;
  border-radius: 999px;
  padding: 3px 10px;
  background: var(--wao-primary-soft, #eef1fc);
  color: var(--wao-primary-text, #3a4cc2);
}
.chip.plain {
  background: var(--wao-divider, #f0f1f4);
  color: var(--wao-text-3, #5a5e6b);
}
.section-label {
  margin-top: 24px;
  margin-bottom: 8px;
  font-size: 11.5px;
  letter-spacing: 1.2px;
  color: var(--wao-text-weak);
}
.empty {
  font-size: 13.5px;
  color: var(--wao-text-faint);
}
.filter-note {
  margin-left: 8px;
  letter-spacing: 0;
  color: var(--wao-text-faint);
}
.shared-pdca { display: grid; gap: 9px; }
.shared-pdca article { padding: 11px 12px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-sub); }
.shared-pdca header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.shared-pdca header b { font-size: 12.5px; }
.shared-pdca header span { color: var(--wao-primary-text); font-size: 11px; }
.shared-pdca p { margin: 7px 0 0; color: var(--wao-text-3); font-size: 12px; line-height: 1.55; white-space: pre-wrap; }
.shared-pdca p em { margin-right: 7px; color: var(--wao-primary-text); font-style: normal; font-weight: 700; }
.imgs {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.imgs img {
  max-width: 100%;
  border-radius: 10px;
  border: 1px solid var(--wao-divider);
  display: block;
}
.sub {
  border-top: 1px solid var(--wao-divider);
  padding: 12px 0;
}
.sub-head {
  display: flex;
  align-items: center;
  gap: 9px;
}
.sub-box {
  width: 16px;
  height: 16px;
  border: 1.5px solid #c9cdda;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.sub-box i {
  font-size: 11px;
  color: #fff;
  opacity: 0;
}
.sub-box.done {
  background: var(--wao-primary, #4358d6);
  border-color: var(--wao-primary, #4358d6);
}
.sub-box.done i {
  opacity: 1;
}
.sub-text {
  font-size: 14px;
  color: var(--wao-text-2);
}
.sub-text.done {
  color: var(--wao-text-weak);
  text-decoration: line-through;
}
.sub-desc,
.sub-imgs {
  margin-top: 8px;
  padding-left: 25px;
}
.foot {
  margin-top: 28px;
  padding-top: 16px;
  border-top: 1px solid var(--wao-divider);
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.foot-tip {
  flex: 1;
  min-width: 220px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.foot-btn {
  height: 32px;
  padding: 0 13px;
  border: 1px solid var(--wao-line-strong, #d9dce5);
  background: var(--wao-bg-card);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.foot-btn:hover {
  border-color: var(--wao-primary, #4358d6);
  color: var(--wao-primary, #4358d6);
}

/* 分享页正文排版 */
.md-body {
  font-size: 14px;
  line-height: 1.85;
  color: var(--wao-text-2);
}
.md-body :deep(> *:first-child) {
  margin-top: 0;
}
.md-body :deep(> *:last-child) {
  margin-bottom: 0;
}
.md-body :deep(p) {
  margin: 0 0 10px;
}
.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3) {
  margin: 18px 0 8px;
  color: var(--wao-text);
  line-height: 1.4;
}
.md-body :deep(h1) {
  font-size: 18px;
}
.md-body :deep(h2) {
  font-size: 16px;
}
.md-body :deep(h3) {
  font-size: 14.5px;
}
.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 0 0 10px;
  padding-left: 22px;
}
.md-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12.5px;
  background: var(--wao-divider);
  border-radius: 4px;
  padding: 1px 5px;
}
.md-body :deep(pre) {
  background: var(--wao-bg-sub, #fafbfd);
  border: 1px solid var(--wao-divider);
  border-radius: 8px;
  padding: 12px 14px;
  overflow-x: auto;
  margin: 0 0 10px;
}
.md-body :deep(pre code) {
  background: none;
  padding: 0;
}
.md-body :deep(blockquote) {
  margin: 0 0 10px;
  padding-left: 13px;
  border-left: 3px solid var(--wao-line, #e3e5ec);
  color: var(--wao-text-3);
}
.md-body :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}
.md-body :deep(a) {
  color: var(--wao-primary-text, #3a4cc2);
}
.md-body :deep(table) {
  border-collapse: collapse;
  font-size: 13px;
  display: block;
  overflow-x: auto;
  margin: 0 0 10px;
}
.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid var(--wao-divider);
  padding: 6px 10px;
  text-align: left;
}
</style>
