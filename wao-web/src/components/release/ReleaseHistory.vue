<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { MODULES } from '@/config/nav'
import { CURRENT_RELEASE, RELEASE_HISTORY } from '@/data/featureCatalog'

const limit = ref(5)
const visible = computed(() => RELEASE_HISTORY.slice(0, limit.value))
const route = useRoute()
const section = ref<HTMLElement>()
async function revealHistory() {
  if (route.hash !== '#recent-updates') return
  await nextTick()
  section.value?.scrollIntoView?.({ block: 'start' })
}
onMounted(revealHistory)
watch(() => route.hash, revealHistory)
</script>

<template>
  <section id="recent-updates" ref="section" class="release-history" aria-labelledby="history-title">
    <header class="history-heading">
      <div><small>RECENT UPDATES</small><h2 id="history-title">最近更新</h2></div>
      <p>已收录 {{ RELEASE_HISTORY.length }} 次更新 · 按时间倒序，随时回顾</p>
    </header>
    <ol class="release-list">
      <li v-for="edition in visible" :key="edition.id">
        <details class="release-edition" :open="edition.id === CURRENT_RELEASE.id">
          <summary>
            <span class="release-meta"><time :datetime="edition.publishedOn">{{ edition.publishedOn }}</time><span v-if="edition.id === CURRENT_RELEASE.id" class="current-badge">本次更新</span></span>
            <span class="release-title">{{ edition.title }}</span>
            <span class="release-intro">{{ edition.intro }}</span>
            <i class="ri-arrow-down-s-line expand-icon" aria-hidden="true"></i>
          </summary>
          <div class="edition-content">
            <article v-for="(item, index) in edition.highlights" :key="`${item.featureId}-${index}`">
              <h3>{{ item.title }}</h3>
              <p>{{ item.summary }}</p>
              <ul><li v-for="point in item.points" :key="point">{{ point }}</li></ul>
              <RouterLink v-if="MODULES[item.featureId]" :to="MODULES[item.featureId].path" :aria-label="`去体验：${item.title}`">去体验 {{ MODULES[item.featureId].label }} <i class="ri-arrow-right-up-line" aria-hidden="true"></i></RouterLink>
            </article>
          </div>
        </details>
      </li>
    </ol>
    <button v-if="limit < RELEASE_HISTORY.length" class="older-updates" type="button" @click="limit += 5">查看更多更新（还有 {{ RELEASE_HISTORY.length - limit }} 次）</button>
    <p v-else class="history-end">已展示全部已收录更新</p>
  </section>
</template>

<style scoped>
.release-history { width: min(1180px, calc(100% - 48px)); margin: 0 auto; padding: 48px 0; scroll-margin-top: 16px; }
.history-heading { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: end; gap: 12px; margin-bottom: 24px; }
.history-heading small { color: var(--wao-text-weak); font-size: 10px; letter-spacing: .12em; }
.history-heading h2 { margin: 8px 0 0; font-size: 28px; }
.history-heading p, .history-end { color: var(--wao-text-weak); font-size: 12px; line-height: 1.7; }
.release-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 12px; }
.release-edition { border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); overflow-wrap: anywhere; }
summary { display: grid; position: relative; gap: 9px; padding: 20px 48px 20px 22px; list-style: none; cursor: pointer; }
summary::-webkit-details-marker { display: none; }
.release-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; color: var(--wao-text-weak); font-size: 12px; }
.current-badge { color: var(--wao-primary-text); background: var(--wao-primary-soft); border-radius: 6px; padding: 3px 7px; font-size: 11px; }
.release-title { font-size: 18px; font-weight: 750; }
.release-intro { color: var(--wao-text-3); font-size: 13px; line-height: 1.7; }
.expand-icon { position: absolute; right: 20px; top: 25px; color: var(--wao-text-weak); }
details[open] .expand-icon { transform: rotate(180deg); }
.edition-content { margin: 0 22px; border-top: 1px solid var(--wao-line); padding: 4px 0 20px; display: grid; gap: 12px; }
.edition-content h3 { font-size: 15px; margin: 16px 0 8px; }
.edition-content p, .edition-content ul { color: var(--wao-text-3); font-size: 13px; line-height: 1.8; }
.edition-content ul { padding-left: 20px; }
.edition-content a { display: inline-flex; gap: 5px; color: var(--wao-primary-text); text-decoration: none; font-size: 12px; }
.older-updates { display: block; margin: 24px auto 0; padding: 10px 16px; border: 1px solid var(--wao-line); border-radius: 10px; color: var(--wao-text); background: var(--wao-bg-card); cursor: pointer; }
.history-end { text-align: center; margin-top: 24px; }
summary:focus-visible, a:focus-visible, button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; }
@media (max-width: 600px) { .release-history { width: calc(100% - 28px); padding: 32px 0; } summary { padding: 18px 36px 18px 16px; } .edition-content { margin: 0 16px; } .expand-icon { right: 14px; } }
</style>
