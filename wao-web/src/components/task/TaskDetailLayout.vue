<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { useIsMobile } from '@/composables/viewport'
import { renderChatReviewMarkdown } from '@/utils/markdown'

const props = withDefaults(
  defineProps<{
    contextKey: string
    description: string
    imageCount?: number
    subtaskCount: number
  }>(),
  { imageCount: 0 },
)
type Section = 'overview' | 'description' | 'subtasks'
type Position = { section: Section; propertiesOpen: boolean; scroll: Record<Section, number> }
const id = useId()
const section = ref<Section>('overview')
const isMobile = useIsMobile()
const propertiesOpen = ref(!isMobile.value)
const scrollArea = ref<HTMLElement>()
const tabs = computed(() => [
  { id: 'overview' as const, label: '概览' },
  { id: 'description' as const, label: '描述' },
  { id: 'subtasks' as const, label: `子任务 ${props.subtaskCount}` },
])
const positions = new Map<string, Position>()
let restoration = 0
function position(key = props.contextKey): Position {
  if (!positions.has(key))
    positions.set(key, {
      section: 'overview',
      propertiesOpen: !isMobile.value,
      scroll: { overview: 0, description: 0, subtasks: 0 },
    })
  return positions.get(key)!
}
function remember(key = props.contextKey) {
  const saved = position(key)
  saved.section = section.value
  saved.propertiesOpen = propertiesOpen.value
  saved.scroll[section.value] = scrollArea.value?.scrollTop ?? 0
}
async function restore() {
  const generation = ++restoration
  await nextTick()
  if (generation === restoration && scrollArea.value)
    scrollArea.value.scrollTop = position().scroll[section.value]
}
async function selectSection(next: Section) {
  if (section.value === next) return
  remember()
  section.value = next
  position().section = next
  await restore()
}
watch(
  () => props.contextKey,
  (_next, previous) => {
    remember(previous)
    section.value = position().section
    propertiesOpen.value = position().propertiesOpen
    void restore()
  },
)
function onTabKey(event: KeyboardEvent, current: Section) {
  const index = tabs.value.findIndex((tab) => tab.id === current)
  let target = index
  if (event.key === 'ArrowRight') target = (index + 1) % tabs.value.length
  else if (event.key === 'ArrowLeft') target = (index + tabs.value.length - 1) % tabs.value.length
  else if (event.key === 'Home') target = 0
  else if (event.key === 'End') target = tabs.value.length - 1
  else return
  event.preventDefault()
  const next = tabs.value[target]!.id
  void selectSection(next)
  ;(event.currentTarget as HTMLElement).parentElement
    ?.querySelector<HTMLButtonElement>(`[data-section="${next}"]`)
    ?.focus()
}
function openProperties() {
  propertiesOpen.value = true
}
function closeProperties() {
  propertiesOpen.value = false
  void nextTick(() => document.getElementById(`${id}-properties-toggle`)?.focus())
}
function onEscape(event: KeyboardEvent) {
  if (!propertiesOpen.value || event.defaultPrevented) return
  event.preventDefault()
  event.stopPropagation()
  closeProperties()
}
/** 仅预览原文，不生成摘要；移除链接、图片等外联能力，长图不参与首屏高度。 */
const preview = computed(() => {
  const html = renderChatReviewMarkdown(props.description)
  const document = new DOMParser().parseFromString(html, 'text/html')
  return (document.body.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 280)
})
const containsImages = computed(
  () => props.imageCount > 0 || /!\[[^\]]*\]\(|<img\b|<wao-image\b/i.test(props.description),
)
defineExpose({ selectSection, openProperties })
</script>

<template>
  <section
    class="task-detail-layout"
    :class="{ 'properties-open': propertiesOpen }"
    @keydown.esc="onEscape"
  >
    <header class="detail-heading">
      <slot name="heading" />
      <div class="detail-summary"><slot name="summary" :properties-open="propertiesOpen" /></div>
    </header>
    <div class="detail-navigation">
      <nav class="detail-tabs" role="tablist" aria-label="任务内容">
        <button
          v-for="tab in tabs"
          :id="`${id}-${tab.id}-tab`"
          :key="tab.id"
          type="button"
          role="tab"
          :data-section="tab.id"
          :aria-selected="section === tab.id"
          :aria-controls="`${id}-${tab.id}-panel`"
          :tabindex="section === tab.id ? 0 : -1"
          @click="selectSection(tab.id)"
          @keydown="onTabKey($event, tab.id)"
        >
          {{ tab.label }}
        </button>
      </nav>
      <div v-if="$slots.tools" class="detail-tools"><slot name="tools" /></div>
      <WaoButton
        :id="`${id}-properties-toggle`"
        class="properties-toggle"
        variant="ghost"
        :aria-expanded="propertiesOpen"
        :aria-controls="`${id}-properties`"
        aria-label="属性与关联"
        @click="propertiesOpen ? closeProperties() : openProperties()"
        ><i class="ri-layout-right-line" aria-hidden="true"></i>属性</WaoButton
      >
    </div>
    <div class="detail-body">
      <div ref="scrollArea" class="detail-scroll" tabindex="0" role="region" aria-label="任务正文" @scroll.passive="remember()">
        <section
          v-show="section === 'overview'"
          :id="`${id}-overview-panel`"
          role="tabpanel"
          :aria-labelledby="`${id}-overview-tab`"
        >
          <div class="preview-heading">
            <h3>描述</h3>
            <WaoButton v-if="!$slots.description" variant="ghost" @click="selectSection('description')"
              >{{ description.trim() || imageCount ? '查看全文' : '添加描述'
              }}<i class="ri-arrow-right-line" aria-hidden="true"></i
            ></WaoButton>
          </div>
          <div v-if="!$slots.description" class="description-preview">
            <p v-if="preview">{{ preview }}</p>
            <p v-else>
              {{
                description.trim() || imageCount
                  ? '描述包含图片或富文本内容'
                  : '暂无描述，补充背景、目标与验收标准。'
              }}
            </p>
            <span v-if="containsImages" class="preview-image-hint"
              ><i class="ri-image-line" aria-hidden="true"></i>包含图片 · 查看全文浏览</span
            >
          </div>
        </section>
        <!-- v-show 保留编辑器、上传和输入法会话；分区切换不重建草稿。 -->
        <section
          v-show="section !== 'subtasks'"
          :id="`${id}-description-panel`"
          :class="{ 'overview-description': section === 'overview' }"
          :role="section === 'description' ? 'tabpanel' : undefined"
          :aria-labelledby="section === 'description' ? `${id}-description-tab` : undefined"
        >
          <slot name="description" :compact="section === 'overview'" />
        </section>
        <!-- 概览与子任务页签复用同一份列表，保留新增输入、筛选和拖拽状态。 -->
        <section
          v-show="section !== 'description'"
          :id="`${id}-subtasks-panel`"
          :class="{ 'overview-subtasks': section === 'overview' }"
          :role="section === 'subtasks' ? 'tabpanel' : undefined"
          :aria-labelledby="section === 'subtasks' ? `${id}-subtasks-tab` : undefined"
        >
          <slot name="subtasks" /><slot name="activity" />
        </section>
        <section v-if="$slots.comments" class="detail-comments" aria-label="评论">
          <slot name="comments" />
        </section>
      </div>
      <aside
        v-show="propertiesOpen"
        :id="`${id}-properties`"
        class="detail-properties"
        tabindex="0"
        aria-label="属性与关联"
      >
        <div class="properties-heading">
          <h3>属性</h3>
          <WaoButton variant="ghost" @click="closeProperties">收起</WaoButton>
        </div>
        <slot name="properties" />
      </aside>
    </div>
  </section>
</template>

<style scoped>
/* Keep every detail region scrollable without displaying scrollbar tracks. */
.task-detail-layout,
.task-detail-layout :deep(*) {
  scrollbar-width: none;
}
.task-detail-layout::-webkit-scrollbar,
.task-detail-layout :deep(*::-webkit-scrollbar) {
  display: none;
}
.overview-description {
  max-height: min(360px, 42vh);
  overflow: auto;
  overscroll-behavior: contain;
}
.overview-description :deep(.field) { padding: 0; margin: 0; }
.task-detail-layout {
  font: 400 var(--wao-font-body)/1.6 var(--wao-font);
  color: var(--wao-text);
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.detail-heading {
  padding: var(--wao-space-2) var(--wao-space-3) var(--wao-space-1);
  max-height: 35%;
  overflow: auto;
  flex-shrink: 0;
}
.detail-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--wao-space-1);
}
.detail-summary:not(:has(> *)),
.detail-summary:has(> .save-feedback:only-child:empty) { display: none; }
.detail-summary :deep(.hint) {
  font-size: var(--wao-font-help, 13px);
  white-space: normal;
  overflow-wrap: anywhere;
}
.detail-navigation {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wao-space-1);
  padding: 0 var(--wao-space-3);
  border-bottom: 1px solid var(--wao-line);
  flex-shrink: 0;
}
.detail-tabs {
  display: flex;
  gap: var(--wao-space-3);
  min-width: 0;
  overflow-x: auto;
}
.detail-tools {
  display: flex;
  justify-content: flex-end;
  min-width: 0;
  max-width: 50%;
  margin-left: auto;
}
.properties-toggle { flex-shrink: 0; }
.detail-tabs button {
  padding: 10px 0;
  min-height: 44px;
  white-space: nowrap;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--wao-text-3);
  font: inherit;
  font-size: var(--wao-font-body);
  cursor: pointer;
}
.detail-tabs button[aria-selected='true'] {
  color: var(--wao-primary-text);
  border-bottom-color: var(--wao-primary);
}
.detail-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.detail-scroll {
  flex: 1;
  min-width: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding: var(--wao-space-2) var(--wao-space-3) var(--wao-space-3);
}
.preview-heading,
.properties-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wao-space-1);
}
h3 {
  margin: 0;
  font-size: var(--wao-font-section);
  font-weight: var(--wao-weight-heading);
  line-height: var(--wao-line-heading);
}
.description-preview {
  padding: var(--wao-space-2);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-sub);
}
.description-preview p {
  font-size: var(--wao-font-body);
  line-height: var(--wao-line-body);
  margin: 0;
  color: var(--wao-text-2);
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.preview-image-hint {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  margin-top: var(--wao-space-1);
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
}
.overview-subtasks {
  margin-top: var(--wao-space-2);
}
.preview-heading { margin-bottom: var(--wao-space-1); }
.detail-comments {
  margin-top: var(--wao-space-2);
  padding-top: var(--wao-space-2);
  border-top: 1px solid var(--wao-line);
}
/* Task descriptions use the same body size as subtasks, properties and comments. */
.detail-scroll :deep(.md-body .tiptap) {
  font-size: var(--wao-font-body);
  line-height: var(--wao-line-body);
  color: var(--wao-text-2);
  overflow-wrap: anywhere;
}
.detail-scroll :deep(.md-body h1) { font-size: 1.5em; }
.detail-scroll :deep(.md-body h2) { font-size: 1.25em; }
.detail-scroll :deep(.md-body h3) { font-size: 1.125em; }
.detail-scroll :deep(.md-body h4) { font-size: 1em; }
.detail-scroll :deep(.md-body p.is-editor-empty:first-child::before) { color: var(--wao-text-3); }
.detail-scroll :deep(.image-actions) { position: static; justify-content: flex-end; padding: var(--wao-space-1); }
.detail-scroll :deep(.image-upload) { font-size: var(--wao-font-help, 13px); color: var(--wao-text-3); }
/* 概览里正文和图片入口共用一行；上传错误仍独占整行，编辑器从不卸载。 */
.overview-description :deep(.md-editor-wrap) { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: start; gap: var(--wao-space-1); }
.overview-description :deep(.md-body) { min-width: 0; }
.overview-description :deep(.image-actions) { grid-column: 2; grid-row: 1; padding: 0; align-self: start; }
.overview-description :deep(.image-upload) { min-height: 40px; border-color: transparent; background: transparent; }
.overview-description :deep(.upload-queue) { grid-column: 1 / -1; }
.overview-description :deep(.md-body .tiptap) { box-sizing: border-box; padding: var(--wao-space-1); }
.detail-properties {
  box-sizing: border-box;
  flex: 0 0 280px;
  min-width: 0;
  overflow: auto;
  overscroll-behavior: contain;
  border-left: 1px solid var(--wao-line);
  padding: var(--wao-space-2);
  background: var(--wao-bg-sub);
}
.properties-heading {
  margin-bottom: var(--wao-space-1);
}
@media (max-width: 760px) {
  .detail-heading {
    padding-inline: var(--wao-space-2);
  }
  .detail-navigation {
    padding-inline: var(--wao-space-2);
    gap: 0 var(--wao-space-1);
    flex-wrap: wrap;
  }
  .detail-tools { order: 1; flex: 1 1 100%; max-width: none; justify-content: flex-start; margin-left: 0; }
  .properties-toggle { margin-left: auto; }
  .detail-tabs {
    gap: var(--wao-space-1);
  }
  .detail-tabs button {
    min-width: 44px;
    padding-inline: var(--wao-space-1);
  }
  .properties-toggle {
    padding-inline: var(--wao-space-1);
  }
  .detail-scroll {
    padding-inline: var(--wao-space-2);
  }
  .detail-properties {
    flex: 1;
    border-left: 0;
  }
  .properties-open .detail-scroll {
    display: none;
  }
}
</style>
