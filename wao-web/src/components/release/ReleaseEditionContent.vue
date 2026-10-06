<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useFeatureExperience } from '@/composables/featureExperience'
import { MODULES } from '@/config/nav'
import type { ReleaseEdition } from '@/types/featureExplorer'
import ExplorerPagination from './ExplorerPagination.vue'
import ReleaseFeatureDemo from './ReleaseFeatureDemo.vue'
import ReleaseGuideDialog from './ReleaseGuideDialog.vue'
import type { ReleaseHighlight } from '@/types/featureExplorer'
import { releaseActionLabel, resolveReleaseAction } from '@/utils/releaseActions'
const expanded = ref<string[]>([])
const guide = ref<ReleaseHighlight | null>(null)
const props = defineProps<{ edition: ReleaseEdition }>()
const emit = defineEmits<{ navigate: [] }>()
const page = ref(1)
const tryFeature = useFeatureExperience()
async function experience(item: ReleaseHighlight) {
  if (await tryFeature(item.featureId, item.action)) emit('navigate')
}
watch(
  () => props.edition.id,
  () => {
    page.value = 1
  },
)
const visible = computed(() => props.edition.highlights.slice((page.value - 1) * 3, page.value * 3))
</script>
<template>
  <div class="edition-content">
    <article v-for="(item, index) in visible" :key="`${item.featureId}-${index}`">
      <span class="edition-eyebrow">{{ item.eyebrow }}</span>
      <h3>{{ item.title }}</h3>
      <p>{{ item.summary }}</p>
      <ul v-if="item.points.length">
        <li v-for="point in item.points" :key="point">{{ point }}</li>
      </ul>
      <div v-if="item.steps?.length" class="usage">
        <b>怎么用</b>
        <ol>
          <li v-for="step in item.steps" :key="step">{{ step }}</li>
        </ol>
      </div>
      <button
        v-if="item.demo"
        type="button"
        class="demo-link"
        :aria-expanded="
          item.demo.presentation === 'inline' ? expanded.includes(item.title) : undefined
        "
        @click="
          item.demo.presentation === 'window'
            ? (guide = item)
            : (expanded = expanded.includes(item.title)
                ? expanded.filter((title) => title !== item.title)
                : [...expanded, item.title])
        "
      >
        {{
          item.demo.presentation === 'window'
            ? '打开大图演示'
            : expanded.includes(item.title)
              ? '收起功能演示'
              : '看看功能演示'
        }}
      </button>
      <ReleaseFeatureDemo
        v-if="item.demo?.presentation === 'inline' && expanded.includes(item.title)"
        :demo="item.demo"
        :edition-id="edition.id"
      />
      <button
        type="button"
        v-if="MODULES[item.featureId] && (item.featureId !== 'changelog' || resolveReleaseAction(item.featureId, item.action))"
        @click="experience(item)"
      >
        {{ releaseActionLabel(item, `去体验 ${MODULES[item.featureId].label}`) }}
        <i class="ri-arrow-right-up-line" aria-hidden="true"></i>
      </button>
    </article>
    <p v-if="!edition.highlights.length">本次版本暂未附带详细说明。</p>
    <ExplorerPagination
      v-if="edition.highlights.length > 3"
      v-model:page="page"
      :total="edition.highlights.length"
      :size="3"
    />
  </div>
  <ReleaseGuideDialog v-if="guide" :item="guide" :edition-id="edition.id" @close="guide = null" />
</template>
<style scoped>
article {
  padding: 20px 0;
  border-bottom: 1px solid var(--wao-line);
  overflow-wrap: anywhere;
}
.edition-eyebrow {
  color: var(--wao-primary-text);
  font-size: 11px;
}
h3 {
  font-size: 15px;
  margin: 6px 0 8px;
}
p,
ul {
  color: var(--wao-text-3);
  font-size: 13px;
  line-height: 1.8;
}
ul {
  padding-left: 20px;
}
li {
  margin-bottom: 5px;
}
.usage {
  margin-top: 16px;
  background: var(--wao-primary-soft);
  padding: 12px 16px;
  border-radius: 12px;
}
.usage b {
  color: var(--wao-primary-text);
  font-size: 12px;
}
.usage ol {
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--wao-text-2);
}
.demo-link {
  display: flex;
  margin: 14px 0;
}
button {
  border: 0;
  background: transparent;
  padding: 0;
  cursor: pointer;
  display: inline-flex;
  gap: 5px;
  color: var(--wao-primary-text);
  font-size: 12px;
}
</style>
