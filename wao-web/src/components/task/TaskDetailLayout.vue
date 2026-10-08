<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import WaoButton from '@/components/ui/WaoButton.vue'
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
type Position = { section: Section; scroll: Record<Section, number> }
const id = useId()
const section = ref<Section>('overview')
const propertiesOpen = ref(false)
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
      scroll: { overview: 0, description: 0, subtasks: 0 },
    })
  return positions.get(key)!
}
function remember(key = props.contextKey) {
  const saved = position(key)
  saved.section = section.value
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
      <div class="detail-summary"><slot name="summary" /></div>
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
      <WaoButton
        :id="`${id}-properties-toggle`"
        class="properties-toggle"
        variant="ghost"
        :aria-expanded="propertiesOpen"
        :aria-controls="`${id}-properties`"
        aria-label="属性、关联与评论"
        @click="propertiesOpen ? closeProperties() : openProperties()"
        ><i class="ri-layout-right-line" aria-hidden="true"></i>属性</WaoButton
      >
    </div>
    <div class="detail-body">
      <div ref="scrollArea" class="detail-scroll" @scroll.passive="remember()">
        <section
          v-show="section === 'overview'"
          :id="`${id}-overview-panel`"
          role="tabpanel"
          :aria-labelledby="`${id}-overview-tab`"
        >
          <div class="preview-heading">
            <h3>描述</h3>
            <WaoButton variant="ghost" @click="selectSection('description')"
              >{{ description.trim() || imageCount ? '查看全文' : '添加描述'
              }}<i class="ri-arrow-right-line" aria-hidden="true"></i
            ></WaoButton>
          </div>
          <div class="description-preview">
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
          v-show="section === 'description'"
          :id="`${id}-description-panel`"
          role="tabpanel"
          :aria-labelledby="`${id}-description-tab`"
        >
          <slot name="description" />
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
      </div>
      <aside
        v-show="propertiesOpen"
        :id="`${id}-properties`"
        class="detail-properties"
        aria-label="属性、关联与评论"
      >
        <div class="properties-heading">
          <h3>属性与评论</h3>
          <WaoButton variant="ghost" @click="closeProperties">收起</WaoButton>
        </div>
        <slot name="properties" />
      </aside>
    </div>
  </section>
</template>

<style scoped>
.task-detail-layout {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.detail-heading {
  padding: var(--wao-space-2) var(--wao-space-3) var(--wao-space-1);
  max-height: 30%;
  overflow: auto;
  flex-shrink: 0;
}
.detail-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--wao-space-1);
  padding-top: var(--wao-space-1);
}
.detail-summary :deep(.hint) {
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
.detail-tabs button {
  padding: 10px 0;
  min-height: 44px;
  white-space: nowrap;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--wao-text-3);
  font: inherit;
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
  font-size: var(--wao-font-body);
  font-weight: 600;
}
.description-preview {
  padding: var(--wao-space-2);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-sub);
}
.description-preview p {
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
  margin-top: var(--wao-space-3);
}
.detail-properties {
  flex: 0 0 310px;
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
    gap: 0;
  }
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
