<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Modal } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { MODULES } from '@/config/nav'
import { CURRENT_RELEASE, FEATURE_CATALOG } from '@/data/featureCatalog'
import {
  FEATURE_CATEGORIES,
  type FeatureCatalogEntry,
  type FeatureCategory,
} from '@/types/featureExplorer'
import ReleaseHistory from '@/components/release/ReleaseHistory.vue'
import ReleaseEditionContent from '@/components/release/ReleaseEditionContent.vue'
import ExplorerPagination from '@/components/release/ExplorerPagination.vue'
const route = useRoute()
const router = useRouter()
const tabs = [
  { id: 'all-features', label: '全部功能', icon: 'ri-layout-grid-line' },
  { id: 'recent-updates', label: '最近更新', icon: 'ri-history-line' },
  { id: 'current-update', label: '本次更新', icon: 'ri-sparkling-line' },
] as const
type Tab = (typeof tabs)[number]['id']
const active = ref<Tab>('all-features')
const category = ref<FeatureCategory | 'all'>('all')
const page = ref(1)
const narrow = ref(window.innerWidth <= 600)
const size = computed(() => (narrow.value ? 3 : 6))
const selected = ref<FeatureCatalogEntry | null>(null)
const historyLoaded = ref(false)
const featureOrder = [
  'notes',
  'projects',
  'tianren',
  'todo',
  'knowledge',
  'home',
  'reference-images',
  'calendar',
  'automation',
  'text-polish',
  'changelog',
]
const catalog = [...FEATURE_CATALOG].sort((a, b) => {
  const rank = (id: string) => {
    const index = featureOrder.indexOf(id)
    return index < 0 ? featureOrder.length : index
  }
  return rank(a.id) - rank(b.id)
})
const filtered = computed(() =>
  catalog.filter((item) => category.value === 'all' || item.category === category.value),
)
const visible = computed(() =>
  filtered.value.slice((page.value - 1) * size.value, page.value * size.value),
)
const categoryLabel = (id: FeatureCategory) =>
  FEATURE_CATEGORIES.find((item) => item.id === id)?.label ?? '功能'
watch(
  () => route.hash,
  (hash) => {
    const match = tabs.find((tab) => '#' + tab.id === hash)
    active.value = match?.id ?? 'all-features'
    if (active.value === 'recent-updates') historyLoaded.value = true
    selected.value = null
  },
  { immediate: true },
)
function switchTab(id: Tab) {
  void router.replace({ path: route.path, query: route.query, hash: '#' + id })
}
async function tabKey(event: KeyboardEvent, index: number) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next =
    event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? tabs.length - 1
        : (index + (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length
  const id = tabs[next]!.id
  await router.replace({ path: route.path, query: route.query, hash: '#' + id })
  await nextTick()
  document.getElementById(`explorer-tab-${id}`)?.focus()
}
function selectCategory(value: FeatureCategory | 'all') {
  category.value = value
  page.value = 1
}
function resize() {
  const next = window.innerWidth <= 600
  if (next !== narrow.value) {
    const first = (page.value - 1) * size.value
    narrow.value = next
    page.value = Math.floor(first / size.value) + 1
  }
}
onMounted(() => window.addEventListener('resize', resize))
onBeforeUnmount(() => window.removeEventListener('resize', resize))
async function openFeature(item: FeatureCatalogEntry) {
  selected.value = null
  if (item.id === 'changelog') return
  await router.push(MODULES[item.id].path)
}
</script>
<template>
  <main class="features-page">
    <header class="feature-heading">
      <div>
        <span class="hero-eyebrow"
          ><i class="ri-compass-discover-line" aria-hidden="true"></i> WAO / 探索</span
        >
        <h1>发现下一件好用的事 <span aria-hidden="true">✦</span></h1>
        <p>找功能、看变化，按你的节奏来。</p>
      </div>
      <div class="heading-art" aria-hidden="true">✧</div>
    </header>
    <div class="explorer-tabs" role="tablist" aria-label="探索内容">
      <button
        v-for="(tab, index) in tabs"
        :id="`explorer-tab-${tab.id}`"
        :key="tab.id"
        type="button"
        role="tab"
        :aria-selected="active === tab.id"
        :aria-controls="tab.id"
        :tabindex="active === tab.id ? 0 : -1"
        @click="switchTab(tab.id)"
        @keydown="tabKey($event, index)"
      >
        <i :class="tab.icon" aria-hidden="true"></i>{{ tab.label }}
      </button>
    </div>
    <section
      id="all-features"
      v-show="active === 'all-features'"
      role="tabpanel"
      aria-labelledby="explorer-tab-all-features"
    >
      <div class="filter-bar" role="group" aria-label="功能分类">
        <button type="button" :aria-pressed="category === 'all'" @click="selectCategory('all')">
          全部</button
        ><button
          v-for="item in FEATURE_CATEGORIES"
          :key="item.id"
          type="button"
          :aria-pressed="category === item.id"
          @click="selectCategory(item.id)"
        >
          {{ item.label }}
        </button>
      </div>
      <div class="catalog-grid">
        <button
          v-for="item in visible"
          :key="item.id"
          type="button"
          class="catalog-card"
          :aria-label="`了解${MODULES[item.id].label}`"
          @click="selected = item"
        >
          <span class="catalog-icon"><i :class="MODULES[item.id].icon" aria-hidden="true"></i></span
          ><span class="feature-tag">{{ categoryLabel(item.category) }}</span>
          <h3>{{ MODULES[item.id].label }}</h3>
          <p>{{ item.summary }}</p>
        </button>
      </div>
      <ExplorerPagination v-model:page="page" :total="filtered.length" :size="size" />
      <p class="browse-hint">先点卡片了解详情，再决定是否进入。</p>
    </section>
    <section
      id="recent-updates"
      v-show="active === 'recent-updates'"
      role="tabpanel"
      aria-labelledby="explorer-tab-recent-updates"
    >
      <ReleaseHistory v-if="historyLoaded" />
    </section>
    <section
      id="current-update"
      v-show="active === 'current-update'"
      role="tabpanel"
      aria-labelledby="explorer-tab-current-update"
    >
      <header class="current-banner">
        <div class="release-spark" aria-hidden="true"><i class="ri-sparkling-2-line"></i></div>
        <div>
          <small>当前页面版本 · {{ CURRENT_RELEASE.publishedOn }}</small>
          <h2>{{ CURRENT_RELEASE.title }}</h2>
          <p>{{ CURRENT_RELEASE.intro }}</p>
        </div>
      </header>
      <ReleaseEditionContent :edition="CURRENT_RELEASE" />
      <div class="current-footer">
        <span>内容随当前页面版本更新</span
        ><button type="button" @click="switchTab('recent-updates')">
          查看更早更新 <i class="ri-arrow-right-line" aria-hidden="true"></i>
        </button>
      </div>
    </section>
    <Modal
      :open="!!selected"
      :title="selected ? MODULES[selected.id].label : ''"
      :footer="null"
      :width="480"
      :body-style="{ maxHeight: '65dvh', overflowY: 'auto' }"
      @cancel="selected = null"
      ><template v-if="selected"
        ><span class="detail-tag">{{ categoryLabel(selected.category) }}</span>
        <p class="detail-description">{{ selected.description }}</p>
        <div class="detail-actions">
          <button
            v-if="selected.id !== 'changelog'"
            type="button"
            class="primary"
            @click="openFeature(selected)"
          >
            打开功能 <i class="ri-arrow-right-up-line" aria-hidden="true"></i></button
          ><button type="button" @click="selected = null">继续浏览</button>
        </div></template
      ></Modal
    >
  </main>
</template>
<style scoped>
.features-page {
  max-width: 1240px;
  width: 100%;
  margin: 0 auto;
  padding: 32px 36px 28px;
  color: var(--wao-text);
}
.feature-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 26px;
}
.hero-eyebrow {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  letter-spacing: 0.12em;
  color: var(--wao-primary-text);
}
h1 {
  font-size: 30px;
  letter-spacing: -0.04em;
  margin: 12px 0 9px;
  line-height: 1.3;
}
h1 span {
  color: var(--wao-primary-text);
  font-size: 20px;
}
.feature-heading p {
  color: var(--wao-text-weak);
  font-size: 13px;
  margin: 0;
}
.heading-art {
  width: 75px;
  height: 75px;
  border-radius: 25px;
  transform: rotate(9deg);
  display: grid;
  place-items: center;
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
  font-size: 46px;
  margin-right: 10px;
}
.explorer-tabs {
  display: flex;
  gap: 28px;
  border-bottom: 1px solid var(--wao-line);
}
.explorer-tabs button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 2px;
  margin-bottom: -1px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--wao-text-3);
  white-space: nowrap;
  cursor: pointer;
}
.explorer-tabs button[aria-selected='true'] {
  color: var(--wao-primary-text);
  border-color: var(--wao-primary);
  font-weight: 600;
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 20px 0;
}
.filter-bar button {
  border: 0;
  border-radius: 8px;
  padding: 7px 11px;
  background: transparent;
  color: var(--wao-text-3);
  font-size: 12px;
  cursor: pointer;
}
.filter-bar button[aria-pressed='true'] {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.catalog-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.catalog-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: start;
  text-align: left;
  min-height: 184px;
  padding: 20px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  cursor: pointer;
  transition:
    transform 0.18s,
    box-shadow 0.18s;
}
.catalog-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 22px color-mix(in srgb, var(--wao-primary) 8%, transparent);
  border-color: var(--wao-primary);
}
.catalog-icon {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 21px;
  margin-bottom: 15px;
}
.feature-tag {
  position: absolute;
  top: 26px;
  right: 17px;
  color: var(--wao-text-weak);
  font-size: 10px;
}
h3 {
  margin: 0 0 7px;
  font-size: 16px;
}
.catalog-card p {
  margin: 0;
  font-size: 13px;
  color: var(--wao-text-3);
  line-height: 1.7;
}
.browse-hint {
  text-align: center;
  font-size: 11px;
  color: var(--wao-text-weak);
  margin-top: 16px;
}
.current-banner {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 24px;
  margin: 22px 0 0;
  background: var(--wao-primary-soft);
  border-radius: 15px;
}
.release-spark {
  display: grid;
  place-items: center;
  flex: none;
  width: 56px;
  height: 60px;
  border-radius: 20px 20px 20px 7px;
  transform: rotate(-8deg);
  color: #fff;
  background: var(--wao-primary);
  font-size: 28px;
}
.current-banner small {
  color: var(--wao-primary-text);
  font-size: 11px;
}
.current-banner h2 {
  font-size: 22px;
  margin: 7px 0;
  overflow-wrap: anywhere;
}
.current-banner p {
  font-size: 13px;
  color: var(--wao-text-3);
  line-height: 1.7;
  margin: 0;
}
.current-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 20px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.current-footer button,
.detail-actions .primary {
  background: var(--wao-primary);
  color: #fff;
  border-color: var(--wao-primary);
}
.current-footer button,
.detail-actions button {
  padding: 9px 14px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  cursor: pointer;
}
.detail-tag {
  color: var(--wao-primary-text);
  font-size: 12px;
}
.detail-description {
  font-size: 14px;
  line-height: 1.8;
  margin: 18px 0 24px;
}
.detail-actions {
  display: flex;
  gap: 10px;
}
.detail-actions button {
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
.detail-actions .primary {
  background: var(--wao-primary);
  color: #fff;
}
button:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 3px;
}
@media (max-width: 1000px) {
  .features-page {
    padding: 28px 24px;
  }
  .catalog-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .features-page {
    padding: 24px 16px;
  }
  .heading-art {
    display: none;
  }
  h1 {
    font-size: 25px;
  }
  .explorer-tabs {
    gap: 0;
    justify-content: space-between;
  }
  .explorer-tabs button {
    gap: 5px;
    font-size: 12px;
  }
  .catalog-grid {
    grid-template-columns: 1fr;
  }
  .catalog-card {
    padding: 18px 16px 18px 74px;
    min-height: 114px;
    justify-content: center;
  }
  .catalog-icon {
    position: absolute;
    left: 17px;
    top: 20px;
  }
  .feature-tag {
    display: none;
  }
  .current-banner {
    padding: 18px;
    gap: 12px;
  }
  .current-banner h2 {
    font-size: 18px;
  }
  .release-spark {
    width: 42px;
    height: 48px;
  }
}
@media (pointer: coarse) {
  .explorer-tabs button,
  .filter-bar button,
  .detail-actions button {
    min-height: 44px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .catalog-card {
    transition: none;
  }
}
</style>
