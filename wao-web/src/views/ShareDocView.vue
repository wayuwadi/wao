<script setup lang="ts">
/**
 * 公开分享页（免登录）—— 文档或小记的只读视图。
 *
 * 后端已把块结构渲染成标准 Markdown，这里只负责展示，
 * 并给出 Markdown 直读地址，便于整篇粘给 AI。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { apiShareDoc } from '@/api/board'
import { renderMarkdown } from '@/utils/markdown'
import { docShareMarkdownUrl } from '@/utils/share'
import { copyToClipboard } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import type { SharedDoc } from '@/types'

const route = useRoute()
const docId = computed(() => String(route.params.id))

const shared = ref<SharedDoc | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    shared.value = await apiShareDoc(docId.value)
  } catch {
    shared.value = null
    error.value = '这个分享链接无效，或者对应的文档已被删除。'
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(docId, load)

const bodyHtml = computed(() => renderMarkdown(shared.value?.md ?? ''))
const mdUrl = computed(() => docShareMarkdownUrl(docId.value))
const originalUrl = computed(() =>
  shared.value ? `/kb/${shared.value.kbId}?doc=${encodeURIComponent(shared.value.id)}` : '/kb',
)

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
          <span class="brand-dot"></span>WAO · {{ shared.note ? '小记分享' : '文档分享' }}
        </div>
        <a class="locate" :href="originalUrl"><i class="ri-corner-up-left-line"></i>回到 WAO 中的原文档</a>
        <h1 class="title">{{ shared.title || (shared.note ? '未命名小记' : '未命名文档') }}</h1>

        <div class="meta">
          <span v-if="shared.updated" class="chip plain">更新于 {{ shared.updated.slice(0, 10) }}</span>
        </div>

        <div v-if="bodyHtml" class="md-body doc-body" v-html="bodyHtml"></div>
        <div v-else class="empty">（暂无内容）</div>

        <div class="foot">
          <span class="foot-tip">要交给 AI？复制下面的 Markdown 地址，它一次请求就能读到全文。</span>
          <button class="foot-btn" @click="copyMd"><i class="ri-file-copy-line"></i>复制 Markdown 地址</button>
        </div>
      </template>
    </div>
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
.empty {
  margin-top: 20px;
  font-size: 13.5px;
  color: var(--wao-text-faint);
}
.doc-body {
  margin-top: 20px;
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
.md-body :deep(ol) {
  list-style-type: decimal;
}
.md-body :deep(ol ol) {
  list-style-type: lower-alpha;
}
.md-body :deep(ol ol ol) {
  list-style-type: lower-roman;
}
.md-body :deep(ol ol ol ol) {
  list-style-type: decimal;
}
.md-body :deep(ol ol ol ol ol) {
  list-style-type: lower-alpha;
}
.md-body :deep(ol ol ol ol ol ol) {
  list-style-type: lower-roman;
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
