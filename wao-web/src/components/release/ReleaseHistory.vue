<script setup lang="ts">
import { computed, ref, toRefs, watch } from 'vue'
import { useBrowsingState, validPage, oneOf } from '@/composables/browsingState'
import { Modal } from 'ant-design-vue'
import { useRoute } from 'vue-router'
import GitCommitHistory from './GitCommitHistory.vue'
import ExplorerPagination from './ExplorerPagination.vue'
import ReleaseEditionContent from './ReleaseEditionContent.vue'
import { CURRENT_RELEASE, RELEASE_HISTORY } from '@/data/featureCatalog'
import type { ReleaseEdition } from '@/types/featureExplorer'
const { mode, page } = toRefs(useBrowsingState('release-history', { mode: 'curated' as 'curated' | 'commits', page: 1 }, { mode: oneOf('curated', 'commits'), page: validPage }))
const loadedCommits = ref(mode.value === 'commits')
watch(mode, value => { if (value === 'commits') loadedCommits.value = true })
const selected = ref<ReleaseEdition | null>(null)
const editions = RELEASE_HISTORY.filter((item) => item.id !== CURRENT_RELEASE.id)
watch(page, () => { page.value = Math.min(page.value, Math.max(1, Math.ceil(editions.length / 3))) }, { immediate: true })
const visible = computed(() => editions.slice((page.value - 1) * 3, page.value * 3))
function selectMode(value: 'curated' | 'commits') {
  mode.value = value
  if (value === 'commits') loadedCommits.value = true
}
const route = useRoute()
watch(
  () => route.fullPath,
  () => {
    selected.value = null
  },
)
</script>
<template>
  <section class="release-history" aria-label="最近更新">
    <div class="history-toolbar">
      <div class="history-switch" role="group" aria-label="更新类型">
        <button type="button" :aria-pressed="mode === 'curated'" @click="selectMode('curated')">
          精选介绍</button
        ><button type="button" :aria-pressed="mode === 'commits'" @click="selectMode('commits')">
          全部记录
        </button>
      </div>
      <span v-if="mode === 'curated'">按发布批次回顾 · 共 {{ editions.length }} 批</span>
    </div>
    <div v-show="mode === 'curated'">
      <ol class="release-list">
        <li v-for="edition in visible" :key="edition.id">
          <button class="release-edition" type="button" @click="selected = edition">
            <time :datetime="edition.publishedOn"
              ><b>{{ edition.publishedOn.slice(5).replace('-', '/') }}</b
              >{{ edition.publishedOn.slice(0, 4) }}</time
            ><span class="release-copy"
              ><strong>{{ edition.title }}</strong
              ><span>{{ edition.intro }}</span></span
            ><span class="release-count"
              >{{ edition.highlights.length }} 项变化
              <i class="ri-arrow-right-up-line" aria-hidden="true"></i
            ></span>
          </button>
        </li>
      </ol>
      <p v-if="!editions.length">尚无已收录的精选更新，可查看全部记录。</p>
      <ExplorerPagination v-model:page="page" :total="editions.length" :size="3" />
    </div>
    <GitCommitHistory v-if="loadedCommits" v-show="mode === 'commits'" />
    <Modal
      :open="!!selected"
      :title="selected?.title"
      :footer="null"
      :width="560"
      :body-style="{ maxHeight: 'min(65dvh, 640px)', overflowY: 'auto' }"
      @cancel="selected = null"
    >
      <template v-if="selected"
        ><p class="detail-date">{{ selected.publishedOn }}</p>
        <p>{{ selected.intro }}</p>
        <ReleaseEditionContent :edition="selected" @navigate="selected = null"
      /></template>
    </Modal>
  </section>
</template>
<style scoped>
.history-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin: 20px 0 6px;
}
.history-toolbar > span,
.detail-date {
  color: var(--wao-text-weak);
  font-size: 12px;
}
.history-switch {
  display: flex;
  padding: 3px;
  gap: 3px;
  background: var(--wao-bg-hover-2);
  border-radius: 9px;
}
.history-switch button {
  padding: 7px 13px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.history-switch button[aria-pressed='true'] {
  background: var(--wao-bg-card);
  color: var(--wao-text);
  box-shadow: 0 1px 4px rgb(0 0 0 / 6%);
}
.release-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.release-edition {
  display: flex;
  width: 100%;
  align-items: center;
  text-align: left;
  gap: 20px;
  padding: 24px 0;
  border: 0;
  border-bottom: 1px solid var(--wao-line);
  background: transparent;
  color: var(--wao-text);
  cursor: pointer;
}
.release-edition:hover strong {
  color: var(--wao-primary-text);
}
time {
  display: flex;
  flex-direction: column;
  flex: none;
  width: 54px;
  font-size: 11px;
  color: var(--wao-text-weak);
}
time b {
  font-size: 19px;
  color: var(--wao-text);
  font-weight: 500;
}
.release-copy {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 7px;
}
.release-copy strong {
  font-size: 15px;
}
.release-copy > span {
  font-size: 13px;
  color: var(--wao-text-3);
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.release-count {
  color: var(--wao-primary-text);
  font-size: 12px;
  white-space: nowrap;
}
button:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 2px;
}
@media (max-width: 600px) {
  .release-edition {
    gap: 12px;
    padding: 20px 0;
  }
  .release-count {
    display: none;
  }
}
</style>
