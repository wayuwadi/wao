<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useBrowsingState, oneOf } from '@/composables/browsingState'
import { CURRENT_RELEASE } from '@/data/featureCatalog'
import ReleaseHistory from '@/components/release/ReleaseHistory.vue'
import ReleaseEditionContent from '@/components/release/ReleaseEditionContent.vue'

const route = useRoute()
const router = useRouter()
const tabs = [
  { id: 'current-update', label: '本次更新', icon: 'ri-sparkling-line' },
  { id: 'recent-updates', label: '最近更新', icon: 'ri-history-line' },
] as const
type Tab = typeof tabs[number]['id']
const browse = useBrowsingState('features', { tab: 'current-update' as Tab }, { tab: oneOf(...tabs.map(item => item.id)) })
const active = ref<Tab>(browse.tab)
const historyLoaded = ref(false)
watch(() => route.hash, hash => {
  // 保留旧书签的目的地，所有功能查找交给唯一目录。
  if (hash === '#all-features') { void router.replace('/navigation'); return }
  active.value = tabs.find(tab => '#' + tab.id === hash)?.id ?? browse.tab
  browse.tab = active.value
  if (active.value === 'recent-updates') historyLoaded.value = true
}, { immediate: true })
function switchTab(id: Tab) { void router.replace({ path: route.path, query: route.query, hash: '#' + id }) }
async function tabKey(event: KeyboardEvent, index: number) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
    : (index + (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length
  const id = tabs[next]!.id
  await router.replace({ path: route.path, query: route.query, hash: '#' + id })
  await nextTick()
  document.getElementById(`explorer-tab-${id}`)?.focus()
}
</script>

<template>
  <main class="features-page wao-page-frame">
    <header class="feature-heading">
      <div><span class="hero-eyebrow">WAO / 更新</span><h1>版本更新</h1><p>查看当前版本的变化，回看过去的更新。</p></div>
      <RouterLink class="directory-link" to="/navigation">全部功能 <i class="ri-arrow-right-up-line" aria-hidden="true"></i></RouterLink>
    </header>
    <div class="explorer-tabs" role="tablist" aria-label="版本更新内容">
      <button v-for="(tab, index) in tabs" :id="`explorer-tab-${tab.id}`" :key="tab.id" type="button" role="tab"
        :aria-selected="active === tab.id" :aria-controls="tab.id" :tabindex="active === tab.id ? 0 : -1"
        @click="switchTab(tab.id)" @keydown="tabKey($event, index)"><i :class="tab.icon" aria-hidden="true"></i>{{ tab.label }}</button>
    </div>
    <section id="recent-updates" v-show="active === 'recent-updates'" role="tabpanel" aria-labelledby="explorer-tab-recent-updates">
      <ReleaseHistory v-if="historyLoaded" />
    </section>
    <section id="current-update" v-show="active === 'current-update'" role="tabpanel" aria-labelledby="explorer-tab-current-update">
      <header class="current-banner"><div><small>当前页面版本 · {{ CURRENT_RELEASE.publishedOn }}</small><h2>{{ CURRENT_RELEASE.title }}</h2><p>{{ CURRENT_RELEASE.intro }}</p></div></header>
      <ReleaseEditionContent :edition="CURRENT_RELEASE" />
      <div class="current-footer"><span>内容随当前页面版本更新</span><button type="button" @click="switchTab('recent-updates')">查看更早更新 <i class="ri-arrow-right-line" aria-hidden="true"></i></button></div>
    </section>
  </main>
</template>

<style scoped>
.features-page { max-width:1240px; width:100%; margin:0 auto; padding:28px 32px; color:var(--wao-text); }
.feature-heading { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:16px; margin-bottom:24px; }
.hero-eyebrow { font-size:11px; letter-spacing:.1em; color:var(--wao-primary-text); }
h1 { font-size: var(--wao-font-title); font-weight: var(--wao-weight-heading); margin:10px 0 8px;  line-height: var(--wao-line-heading);}
.feature-heading p,.current-banner p { font-size:14px; line-height:1.7; color:var(--wao-text-2); margin:0; }
.directory-link { display:flex; align-items:center; gap:8px; padding:9px 12px; border:1px solid var(--wao-line); border-radius:10px; color:var(--wao-text-2); text-decoration:none; font-size:13px; }
.directory-link:hover { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
.explorer-tabs { display:flex; gap:24px; border-bottom:1px solid var(--wao-line); }
.explorer-tabs button { display:flex; align-items:center; gap:8px; padding:12px 2px; margin-bottom:-1px; border:0; border-bottom:2px solid transparent; background:transparent; color:var(--wao-text-3); cursor:pointer; }
.explorer-tabs button[aria-selected='true'] { color:var(--wao-primary-text); border-color:var(--wao-primary); font-weight:600; }
.current-banner { padding:22px 0; }
.current-banner small,.current-footer { font-size:12px; color:var(--wao-text-weak); }
.current-banner h2 { font-size:22px; margin:8px 0; }
.current-footer { display:flex; justify-content:space-between; flex-wrap:wrap; gap:12px; padding:22px 0; }
.current-footer button { border:0; background:transparent; color:var(--wao-primary-text); cursor:pointer; }
@media(max-width:600px) { .features-page { padding:20px 16px; } h1 { font-size: var(--wao-font-title);  font-weight: var(--wao-weight-heading); line-height: var(--wao-line-heading);} }
</style>
