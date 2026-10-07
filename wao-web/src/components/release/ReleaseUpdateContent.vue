<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { MODULES } from '@/config/nav'
import type { ReleaseEdition, ReleaseHighlight } from '@/types/featureExplorer'
import ReleaseFeatureDemo from './ReleaseFeatureDemo.vue'
const props = defineProps<{ edition: ReleaseEdition; selectedIndex?: number; busy?: boolean }>()
const emit = defineEmits<{ select: [index: number]; demo: [item: ReleaseHighlight] }>()
const expanded = ref<number[]>([])
const details = ref<number[]>([])
const currentIndex = computed(() =>
  Math.max(0, Math.min(props.selectedIndex ?? 0, props.edition.highlights.length - 1)),
)
const visible = computed(() =>
  props.edition.highlights.slice(currentIndex.value, currentIndex.value + 1),
)
function moreCount(item: ReleaseHighlight) {
  return Math.max(0, item.points.length - 2) + Math.max(0, (item.steps?.length ?? 0) - 2)
}
function toggleDetails(index: number) {
  details.value = details.value.includes(index)
    ? details.value.filter((item) => item !== index)
    : [...details.value, index]
}
watch(
  () => props.edition.id,
  () => {
    expanded.value = []
    details.value = []
  },
)
function toggle(index: number) {
  expanded.value = expanded.value.includes(index)
    ? expanded.value.filter((item) => item !== index)
    : [...expanded.value, index]
}
</script>
<template>
  <div class="release-update-content">
    <fieldset v-if="edition.highlights.length > 1" class="change-picker" :disabled="busy">
      <legend class="sr-only">选择本次变化</legend>
      <label
        v-for="(item, index) in edition.highlights"
        :key="item.id || index"
        :class="{ selected: index === currentIndex }"
      >
        <input
          type="radio"
          name="release-highlight"
          :checked="index === currentIndex"
          :aria-label="`查看变化：${item.title}`"
          @change="emit('select', index)"
        />
        <span>{{ item.eyebrow || MODULES[item.featureId]?.label || '本次变化' }}</span>
      </label>
    </fieldset>
    <div class="release-meta">
      <span>{{
        edition.highlights.length > 1
          ? `${edition.highlights.length} 项变化`
          : edition.highlights[0]?.eyebrow || '本次更新'
      }}</span
      ><time>{{ edition.publishedOn }}</time>
    </div>
    <article v-for="item in visible" :key="item.id || `${item.featureId}-${currentIndex}`">
      <h3>{{ item.title }}</h3>
      <p>{{ item.summary }}</p>
      <ul v-if="item.points.length" class="points" aria-label="变化说明">
        <li
          v-for="point in item.points.slice(0, details.includes(currentIndex) ? undefined : 2)"
          :key="point"
        >
          {{ point }}
        </li>
      </ul>
      <div v-if="item.steps?.length" class="usage">
        <span><i class="ri-signpost-line" aria-hidden="true"></i> 怎么用</span>
        <ol>
          <li
            v-for="step in item.steps.slice(0, details.includes(currentIndex) ? undefined : 2)"
            :key="step"
          >
            {{ step }}
          </li>
        </ol>
      </div>
      <button
        v-if="moreCount(item)"
        type="button"
        class="details-toggle"
        :disabled="busy"
        :aria-expanded="details.includes(currentIndex)"
        @click="toggleDetails(currentIndex)"
      >
        {{
          details.includes(currentIndex)
            ? '收起完整说明'
            : `展开完整说明 · 另有 ${moreCount(item)} 条`
        }}
      </button>
      <button
        v-if="item.demo"
        type="button"
        class="demo-toggle"
        :disabled="busy"
        :aria-expanded="
          item.demo.presentation === 'inline' ? expanded.includes(currentIndex) : undefined
        "
        @click="item.demo.presentation === 'window' ? emit('demo', item) : toggle(currentIndex)"
      >
        {{
          item.demo.presentation === 'window'
            ? '打开大图演示'
            : expanded.includes(currentIndex)
              ? '收起功能演示'
              : '看看功能演示'
        }}
        <i
          :class="
            item.demo.presentation === 'window'
              ? 'ri-arrow-right-up-line'
              : expanded.includes(currentIndex)
                ? 'ri-arrow-up-s-line'
                : 'ri-arrow-down-s-line'
          "
          aria-hidden="true"
        ></i>
      </button>
      <ReleaseFeatureDemo
        v-if="item.demo?.presentation === 'inline' && expanded.includes(currentIndex)"
        :demo="item.demo"
        :edition-id="edition.id"
      />
    </article>
    <template v-if="!edition.highlights.length"
      ><h3>{{ edition.title }}</h3>
      <p>{{ edition.intro }}</p>
      <p>本次没有新增功能说明，可继续使用现有功能。</p></template
    >
  </div>
</template>
<style scoped>
.change-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  border: 0;
  padding: 0;
  margin: 16px 0 0;
  min-width: 0;
}
.change-picker label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border-radius: var(--wao-radius-control);
  padding: 6px 9px;
  background: var(--wao-bg-soft);
  color: var(--wao-text-2);
  font-size: 12px;
  cursor: pointer;
  max-width: 100%;
  overflow-wrap: anywhere;
}
.change-picker label.selected {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.change-picker input {
  margin: 0;
  accent-color: var(--wao-primary);
  flex: none;
}
.change-picker:disabled label {
  opacity: 0.6;
  cursor: wait;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.release-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  font-size: 11px;
  color: var(--wao-text-weak);
  margin: 16px 0 10px;
}
.release-meta > span {
  border-radius: 7px;
  padding: 4px 8px;
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
}
article {
  padding: 0 0 20px;
  overflow-wrap: anywhere;
}
article + article {
  border-top: 1px solid var(--wao-line);
  padding-top: 18px;
}
h3 {
  font-size: 16px;
  line-height: 1.5;
  margin: 10px 0;
}
p {
  color: var(--wao-text-weak);
  font-size: 13px;
  line-height: 1.8;
  margin: 0;
}
.item-eyebrow {
  font-size: 11px;
  color: var(--wao-primary-text);
}
.usage {
  background: var(--wao-primary-soft);
  padding: 14px 15px;
  border-radius: 12px;
  margin: 16px 0 0;
}
.usage > span {
  color: var(--wao-primary-text);
  font-size: 12px;
  font-weight: 600;
}
ol,
.points {
  padding-left: 19px;
  margin: 10px 0 0;
}
li {
  font-size: 12px;
  line-height: 1.8;
  color: var(--wao-text-2);
  padding-left: 3px;
  margin-top: 4px;
}
li::marker {
  color: var(--wao-primary-text);
}
button {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  border: 0;
  background: none;
  color: var(--wao-primary-text);
  font: inherit;
  font-size: 12px;
  padding: 9px 0;
  cursor: pointer;
  margin-top: 8px;
}
button:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 3px;
}
button:disabled {
  opacity: 0.5;
  cursor: wait;
}
.feature-demo {
  margin-top: 6px;
}
@media (pointer: coarse) {
  button {
    min-height: 44px;
  }
}
</style>
