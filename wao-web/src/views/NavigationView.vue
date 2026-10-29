<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAVIGATION_CATALOG, matchesFunction } from '@/config/navigationCatalog'
import { externalAppItemId, type NavItemId } from '@/config/nav'
import { quickLinkPath, type QuickLink } from '@/config/quickLinks'
import { useQuickLinks } from '@/composables/quickLinks'
import { useNavStore } from '@/stores/nav'
import { useClientPagination } from '@/composables/clientPagination'
import WaoPagination from '@/components/ui/WaoPagination.vue'
const Governance = defineAsyncComponent(() => import('@/components/nav/NavigationGovernance.vue'))
const route = useRoute()
const router = useRouter()
const nav = useNavStore()
const links = useQuickLinks()
const query = ref('')
const selected = ref('today')
const governance = computed(() => route.query.view === 'governance')
const sections = computed(() =>
  NAVIGATION_CATALOG.map((section) => ({
    ...section,
    items: section.items
      .filter((item) => matchesFunction(item.id, nav.itemMeta(item.id)?.label ?? item.id, query.value))
      .sort((a, b) => Number(!!a.preview) - Number(!!b.preview)),
  })).filter(
    (section) =>
      section.items.length &&
      (query.value.trim() || selected.value === 'all' || selected.value === section.id),
  ),
)
const previewsVisible = computed(() =>
  sections.value.some((section) => section.items.some((item) => item.preview)),
)
const visibleApps = computed(() =>
  nav.externalApps.filter((app) =>
    matchesFunction(externalAppItemId(app.id), [app.label, app.appUrl, app.group, '应用', '外部应用'].join(' '), query.value),
  ),
)
const visibleLinks = computed(() =>
  links.links.value.filter(link => link.id !== 'wao-jenkins').filter(
    (link) =>
      !query.value.trim() ||
      [link.label, link.subtitle, link.url, ...link.keywords]
        .join(' ')
        .toLowerCase()
        .includes(query.value.trim().toLowerCase()),
  ),
)
const dynamicVisible = computed(
  () => !!query.value.trim() || ['all', 'links'].includes(selected.value),
)
const resultCount = computed(
  () =>
    sections.value.reduce((n, section) => n + section.items.length, 0) +
    (dynamicVisible.value ? visibleApps.value.length + visibleLinks.value.length : 0),
)
const catalogResults = computed(() => [
  ...sections.value.flatMap((section) => section.items.map((item) => `function:${item.id}`)),
  ...(dynamicVisible.value ? visibleApps.value.map((app) => `app:${app.id}`) : []),
  ...(dynamicVisible.value ? visibleLinks.value.map((link) => `link:${link.id}`) : []),
])
const { page: catalogPage, pageCount: catalogPageCount, visible: visibleCatalogResults } = useClientPagination(catalogResults, [query, selected])
const pageKeys = computed(() => new Set(visibleCatalogResults.value))
const pagedSections = computed(() => sections.value.map((section) => ({
  ...section, items: section.items.filter((item) => pageKeys.value.has(`function:${item.id}`)),
})).filter((section) => section.items.length))
const pagedApps = computed(() => visibleApps.value.filter((app) => pageKeys.value.has(`app:${app.id}`)))
const pagedLinks = computed(() => visibleLinks.value.filter((link) => pageKeys.value.has(`link:${link.id}`)))
function open(id: NavItemId) {
  const meta = nav.itemMeta(id)
  if (!meta?.path) return
  if (meta.openMode === 'NEW_PAGE') {
    const target = meta.newPagePath ?? router.resolve(meta.path).href
    window.open(target, '_blank', 'noopener,noreferrer')
  } else void router.push(meta.path)
}
function openLink(link: QuickLink) {
  if (link.openMode === 'TAB') void router.push(quickLinkPath(link.id))
  else window.open(link.url, '_blank', 'noopener,noreferrer')
}
async function retry() {
  await nav.loadMenus(true)
  await links.load(true)
}
onMounted(() => {
  void nav.loadMenus()
  void links.load()
})
</script>

<template>
  <main class="navigation-center wao-page-frame">
    <header class="hero">
      <div>
        <span class="eyebrow">WAO / 导航</span>
        <h1>{{ governance ? '功能治理' : '全部功能' }}</h1>
        <p>
          {{
            governance ? '查看真实使用与运行情况。' : '按要做的事查找功能，点击即可打开。'
          }}
        </p>
      </div>
      <div class="header-actions">
        <button v-if="!governance" @click="router.push('/features')"><i class="ri-compass-discover-line" aria-hidden="true"></i>版本更新</button>
        <button v-if="governance" @click="router.replace({ path: '/navigation', query: governance ? {} : { view: 'governance' } })">
          {{ governance ? '返回全部功能' : '功能治理' }}
        </button>
      </div>
    </header>
    <Governance v-if="governance" />
    <template v-else>
      <label class="search"
        ><i class="ri-search-line"></i
        ><input
          v-model="query"
          type="search"
          placeholder="搜索功能、旧名称、应用或链接"
          aria-label="搜索全部功能"
        /><span v-if="query.trim()">{{ resultCount }} 个结果</span
        ><kbd v-else>全局搜索也能找功能</kbd></label
      >
      <nav class="domains" aria-label="功能分类">
        <button :class="{ active: selected === 'all' }" @click="selected = 'all'">全部</button
        ><button
          v-for="section in NAVIGATION_CATALOG"
          :key="section.id"
          :class="{ active: selected === section.id }"
          @click="selected = section.id"
        >
          <i :class="section.icon"></i>{{ section.label }}</button
        ><button :class="{ active: selected === 'links' }" @click="selected = 'links'">
          应用与链接
        </button>
      </nav>
      <p v-if="previewsVisible || query.trim()" class="hint">
        <template v-if="previewsVisible">同一分类中，规划与示例入口列在最后，仍可打开查看。</template>
        <template v-if="query.trim()">搜索覆盖所有分类，也包含未固定的功能。</template>
      </p>
      <div v-if="nav.layoutError || links.loadError.value" role="alert" class="status error">
        {{
          nav.layoutError
            ? nav.layoutError + '。当前展示已知目录，外部应用暂无法确认。'
            : '快捷链接读取失败：' + links.loadError.value
        }}<button @click="retry">重试读取</button>
      </div>
      <div v-else-if="nav.layoutLoading || links.loading.value" class="status" role="status">
        正在读取功能目录…
      </div>
      <section v-for="section in pagedSections" :key="section.id" class="catalog-section">
        <header>
          <div>
            <h2>{{ section.label }}</h2>
            <p>{{ section.description }}</p>
          </div>
          <span>{{ section.items.length }} 个功能</span>
        </header>
        <div class="function-list">
          <article v-for="item in section.items" :key="item.id" class="function-row">
            <button class="function-open" @click="open(item.id)">
              <i :class="nav.itemMeta(item.id)?.icon"></i
              ><span
                ><b>{{
                  item.id === 'workspaces' && nav.itemMeta(item.id)?.label === '工作台'
                    ? '链接空间'
                    : nav.itemMeta(item.id)?.label
                }}<em v-if="item.preview" class="maturity-label">{{ item.preview === 'planned' ? '规划中' : '示例预览' }}</em></b
                ><small>{{ item.description }}</small></span
              ><i
                :class="
                  nav.itemMeta(item.id)?.openMode === 'NEW_PAGE'
                    ? 'ri-external-link-line'
                    : 'ri-arrow-right-line'
                "
              ></i>
            </button>
          </article>
        </div>
      </section>
      <section v-if="dynamicVisible && pagedApps.length" class="catalog-section">
        <header>
          <h2>接入应用</h2>
          <span>{{ pagedApps.length }} 个应用</span>
        </header>
        <div class="function-list">
          <article v-for="app in pagedApps" :key="app.id" class="function-row">
            <button class="function-open" @click="open(externalAppItemId(app.id))">
              <i :class="app.icon"></i
              ><span
                ><b>{{ app.label }}</b
                ><small>{{
                  app.openMode === 'NEW_PAGE' ? '在新页面打开' : '在 WAO 页签打开'
                }}</small></span
              ><i class="ri-arrow-right-line"></i>
            </button>
          </article>
        </div>
      </section>
      <section v-if="dynamicVisible && pagedLinks.length" class="catalog-section">
        <header>
          <h2>我的快捷链接</h2>
        </header>
        <div class="function-list">
          <button
            v-for="link in pagedLinks"
            :key="link.id"
            class="link-row"
            @click="openLink(link)"
          >
            <i :class="link.icon"></i
            ><span
              ><b>{{ link.label }}</b
              ><small>{{ link.subtitle }}</small></span
            ><i class="ri-arrow-right-up-line"></i>
          </button>
        </div>
      </section>
      <WaoPagination v-if="catalogPageCount > 1" v-model:page="catalogPage" :page-count="catalogPageCount" :total="resultCount" aria-label="功能与快捷链接查询结果分页" />
      <div v-if="!resultCount" class="empty">
        <i class="ri-search-line"></i>
        <h2>没有找到匹配入口</h2>
        <p>试试功能原名、用途或旧名称。</p>
        <button
          @click="
            query = ''; selected = 'all'
          "
        >
          查看全部功能
        </button>
      </div>
    </template>
  </main>
</template>

<style scoped>
.maturity-label {
  display: inline-block;
  margin-left: 8px;
  padding: 1px 6px;
  border: 1px solid var(--wao-line-control);
  border-radius: 6px;
  font-size: 12px;
  font-weight: 400;
  font-style: normal;
  color: var(--wao-text-2);
  background: var(--wao-bg-sub);
}
.navigation-center {
  max-width: none;
  margin: 0 auto;
  padding: 32px clamp(16px, 3vw, 44px) 64px;
  color: var(--wao-text);
}
.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 26px;
}
.eyebrow {
  color: var(--wao-text-weak);
  font-size: 11px;
  letter-spacing: 1px;
}
h1 {
  margin: 8px 0;
  font-size: var(--wao-font-title);
  letter-spacing: -0.5px;
  font-weight: var(--wao-weight-heading);
  line-height: var(--wao-line-heading);
}
h2 {
  margin: 0;
  font-size: var(--wao-font-section);
  font-weight: var(--wao-weight-heading);
  line-height: var(--wao-line-heading);
}
p {
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
  line-height: 1.7;
  margin: 4px 0;
}
.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
button {
  cursor: pointer;
}
button:disabled {
  cursor: default;
  opacity: 0.5;
}
.header-actions button,
.status button,
.empty button {
  padding: 9px 12px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
.search {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 17px;
  border: 1px solid var(--wao-line-control);
  border-radius: 12px;
  background: var(--wao-bg-card);
}
.search:focus-within {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px var(--wao-primary-soft);
}
.search input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: none;
  color: inherit;
  font-size: 15px;
}
.search span,
.search kbd {
  font-size: 11px;
  color: var(--wao-text-3);
}
.domains {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin: 18px 0 24px;
}
.domains button {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 9px 11px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
  font-size: 12px;
}
.domains .active {
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
  border-color: var(--wao-primary);
}
.catalog-section {
  margin-top: 28px;
}
.catalog-section header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 11px;
}
.catalog-section header > span {
  color: var(--wao-text-3);
  font-size: 12px;
}
.function-list {
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  overflow: hidden;
  background: var(--wao-bg-card);
}
.function-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 10px 14px 10px 5px;
  border-bottom: 1px solid var(--wao-divider);
}
@media (min-width: 1080px) {
  .function-list {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr));
    gap: 10px;
    border: 0;
    border-radius: 0;
    overflow: visible;
    background: transparent;
  }
  .function-row,
  .function-row:last-child {
    min-width: 0;
    border: 1px solid var(--wao-line);
    border-radius: 12px;
    background: var(--wao-bg-card);
  }
  .function-row:hover {
    border-color: var(--wao-line-control);
  }
}
.navigation-center:not(.customizing) .function-row {
  padding-left: 14px;
}
.function-row:last-child {
  border-bottom: 0;
}
.function-row:hover,
.link-row:hover {
  background: var(--wao-bg-hover);
}
.drag {
  width: 25px;
  flex-shrink: 0;
  border: 0;
  background: none;
  color: var(--wao-text-faint);
  cursor: grab;
  padding: 8px 3px;
}
.function-open {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 6px;
  flex: 1;
  min-width: 0;
  text-align: left;
  border: 0;
  background: none;
  color: inherit;
}
.function-open > i:first-child {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  border-radius: 9px;
  background: var(--wao-bg-sub);
  color: var(--wao-primary-text);
  font-size: 19px;
}
.function-open > span,
.link-row > span {
  flex: 1;
  min-width: 0;
}
b {
  display: block;
  font-size: var(--wao-font-body);
  font-weight: 600;
}
small {
  display: block;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
  margin-top: 5px;
  line-height: 1.5;
}
.function-open > i:last-child {
  color: var(--wao-text-faint);
  margin: 0 12px;
}
.position {
  min-width: 138px;
  display: grid;
  justify-items: end;
  gap: 5px;
}
.position small {
  margin: 0;
  font-size: var(--wao-font-caption);
}
select {
  font-size: 11px;
  padding: 5px 8px;
  border: 1px solid var(--wao-line);
  border-radius: 6px;
  color: var(--wao-text-3);
  background: var(--wao-bg-card);
}
.link-row {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 16px;
  width: 100%;
  border: 0;
  border-bottom: 1px solid var(--wao-divider);
  background: none;
  color: inherit;
  text-align: left;
}
.status {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 14px 0;
  padding: 12px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  font-size: 12px;
}
.error {
  color: var(--wao-danger-strong);
  background: var(--wao-danger-bg);
}
.empty {
  padding: 50px 20px;
  text-align: center;
}
.empty > i {
  font-size: 26px;
  color: var(--wao-text-weak);
}
.hint {
  font-size: 12px;
}
@media (max-width: 720px) {
  .hero {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }
  .navigation-center {
    padding-top: 20px;
  }
  .search kbd {
    display: none;
  }
  .function-row {
    flex-wrap: wrap;
    padding-right: 10px;
  }
  .position {
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-left: 36px;
  }
  .function-open > i:last-child {
    margin: 0;
  }
}
</style>
