<script setup lang="ts">
import { ref, watch } from 'vue'
import { MODULES } from '@/config/nav'
import type { ReleaseEdition, ReleaseHighlight } from '@/types/featureExplorer'
import ReleaseFeatureDemo from './ReleaseFeatureDemo.vue'
const props = defineProps<{ edition: ReleaseEdition; pending?: boolean; busy?: boolean }>()
const emit = defineEmits<{ experience: [item: ReleaseHighlight]; demo: [item: ReleaseHighlight] }>()
const expanded = ref<number[]>([])
function knownModule(id: string) {
  return Object.prototype.hasOwnProperty.call(MODULES, id)
}
watch(
  () => props.edition.id,
  () => {
    expanded.value = []
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
    <div class="release-meta">
      <span>{{
        edition.highlights.length > 1
          ? `${edition.highlights.length} 项变化`
          : edition.highlights[0]?.eyebrow || '本次更新'
      }}</span
      ><time>{{ edition.publishedOn }}</time>
    </div>
    <p v-if="edition.highlights.length > 1" class="edition-intro">{{ edition.intro }}</p>
    <article v-for="(item, index) in edition.highlights" :key="`${item.featureId}-${index}`">
      <span v-if="edition.highlights.length > 1" class="item-eyebrow">{{ item.eyebrow }}</span>
      <h3>{{ item.title }}</h3>
      <p>{{ item.summary }}</p>
      <div v-if="item.steps?.length" class="usage">
        <span><i class="ri-signpost-line" aria-hidden="true"></i> 怎么用</span>
        <ol>
          <li v-for="step in item.steps" :key="step">{{ step }}</li>
        </ol>
      </div>
      <ul v-else-if="item.points.length" class="points">
        <li v-for="point in item.points" :key="point">{{ point }}</li>
      </ul>
      <button
        v-if="item.demo"
        type="button"
        class="demo-toggle"
        :disabled="busy"
        :aria-expanded="item.demo.presentation === 'inline' ? expanded.includes(index) : undefined"
        @click="item.demo.presentation === 'window' ? emit('demo', item) : toggle(index)"
      >
        {{
          item.demo.presentation === 'window'
            ? '打开大图演示'
            : expanded.includes(index)
              ? '收起功能演示'
              : '看看功能演示'
        }}
        <i
          :class="
            item.demo.presentation === 'window'
              ? 'ri-arrow-right-up-line'
              : expanded.includes(index)
                ? 'ri-arrow-up-s-line'
                : 'ri-arrow-down-s-line'
          "
          aria-hidden="true"
        ></i>
      </button>
      <ReleaseFeatureDemo
        v-if="item.demo?.presentation === 'inline' && expanded.includes(index)"
        :demo="item.demo"
        :edition-id="edition.id"
      />
      <button
        v-if="
          !pending &&
          knownModule(item.featureId) &&
          item.featureId !== 'changelog' &&
          edition.attention !== 'maintenance'
        "
        type="button"
        class="experience"
        :disabled="busy"
        :aria-label="`去体验：${item.title}`"
        @click="emit('experience', item)"
      >
        去体验 {{ MODULES[item.featureId].label }}
        <i class="ri-arrow-right-up-line" aria-hidden="true"></i>
      </button>
    </article>
    <template v-if="!edition.highlights.length"
      ><h3>{{ edition.title }}</h3>
      <p>{{ edition.intro }}</p>
      <p>本次没有新增功能说明，可继续使用现有功能。</p></template
    >
  </div>
</template>
<style scoped>
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
.experience {
  display: flex;
}
@media (pointer: coarse) {
  button {
    min-height: 44px;
  }
}
</style>
