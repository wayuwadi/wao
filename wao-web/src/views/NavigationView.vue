<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NAVIGATION_CATALOG, type NavigationCatalogModuleId } from '@/config/navigationCatalog'
import { externalAppItemId } from '@/config/nav'
import { quickLinkPath, type QuickLink } from '@/config/quickLinks'
import { useQuickLinks } from '@/composables/quickLinks'
import { useNavStore } from '@/stores/nav'

const router = useRouter()
const nav = useNavStore()
const quickLinks = useQuickLinks()
const query = ref('')

function includesQuery(values: Array<string | undefined>) {
  const needle = query.value.trim().toLocaleLowerCase()
  return !needle || values.some((value) => value?.toLocaleLowerCase().includes(needle))
}

const sections = computed(() =>
  NAVIGATION_CATALOG.map((section) => ({
    ...section,
    items: section.items
      .map((item) => {
        const meta = nav.itemMeta(item.id)!
        const configured = nav.builtinMenus.find((menu) => menu.id === item.id)
        return { ...item, meta, sidebarVisible: configured?.visible ?? false }
      })
      .filter((item) =>
        includesQuery([
          item.meta.label,
          item.description,
          ...(item.keywords ?? []),
          section.label,
          section.description,
        ]),
      ),
  })).filter((section) => section.items.length),
)

const apps = computed(() =>
  nav.externalApps.filter((app) =>
    includesQuery([app.label, app.id, app.group, app.appUrl, '应用', '外部应用']),
  ),
)
const links = computed(() =>
  quickLinks.links.value.filter((link) =>
    includesQuery([link.label, link.subtitle, link.url, ...link.keywords, '链接', '快捷链接']),
  ),
)
const resultCount = computed(
  () =>
    sections.value.reduce((sum, section) => sum + section.items.length, 0) +
    apps.value.length +
    links.value.length,
)
const loading = computed(
  () =>
    nav.builtinMenusState === 'loading' ||
    nav.externalAppsState === 'loading' ||
    quickLinks.loading.value,
)
const errors = computed(() =>
  [nav.builtinMenusError, nav.externalAppsError, quickLinks.loadError.value].filter(Boolean),
)

function openTarget(path: string, newPagePath: string | undefined, openMode: 'TAB' | 'NEW_PAGE') {
  if (openMode === 'NEW_PAGE') {
    const target = newPagePath ?? path
    const href = /^https:\/\//i.test(target) ? target : router.resolve(target).href
    window.open(href, '_blank', 'noopener,noreferrer')
    return
  }
  void router.push(path)
}

function openModule(id: NavigationCatalogModuleId) {
  const meta = nav.itemMeta(id)
  if (meta) openTarget(meta.path, meta.newPagePath, meta.openMode)
}

function openApp(id: string) {
  const meta = nav.itemMeta(externalAppItemId(id))
  if (meta) openTarget(meta.path, meta.newPagePath, meta.openMode)
}

function openLink(link: QuickLink) {
  if (link.openMode === 'TAB') void router.push(quickLinkPath(link.id))
  else window.open(link.url, '_blank', 'noopener,noreferrer')
}

function retry() {
  void nav.loadBuiltinMenus(true)
  void nav.loadExternalApps(true)
  void quickLinks.load(true)
}

onMounted(() => {
  void nav.loadBuiltinMenus()
  void nav.loadExternalApps()
  void quickLinks.load()
})
</script>

<template>
  <main class="navigation-center">
    <header class="hero">
      <div>
        <div class="eyebrow"><i class="ri-compass-discover-line"></i> WAO NAVIGATION</div>
        <h1>导航</h1>
        <p>侧栏只放每天常用的入口；WAO 全部功能、接入应用和个人链接都能从这里找到。</p>
      </div>
      <button class="manage" type="button" @click="router.push('/settings?tab=navigation')">
        <i class="ri-settings-3-line"></i>管理菜单与链接
      </button>
    </header>

    <label class="search">
      <i class="ri-search-line"></i>
      <input v-model="query" type="search" placeholder="搜索功能、应用或链接" autofocus />
      <span v-if="query.trim()">{{ resultCount }} 个结果</span>
    </label>

    <div v-if="errors.length" class="load-status error" role="alert">
      <i class="ri-error-warning-line"></i>
      <span>部分目录读取失败，当前仍可使用安全默认值：{{ errors.join('；') }}</span>
      <button type="button" @click="retry">重试</button>
    </div>
    <div v-else-if="loading" class="load-status" role="status">
      <i class="ri-loader-4-line spin"></i>正在同步最新导航…
    </div>

    <section v-for="section in sections" :key="section.id" class="catalog-section">
      <header>
        <span><i :class="section.icon"></i></span>
        <div>
          <h2>{{ section.label }}</h2>
          <p>{{ section.description }}</p>
        </div>
      </header>
      <div class="card-grid">
        <button
          v-for="item in section.items"
          :key="item.id"
          class="nav-card"
          type="button"
          @click="openModule(item.id)"
        >
          <span class="card-icon"><i :class="item.meta.icon"></i></span>
          <span class="card-copy">
            <b>{{ item.meta.label }}</b>
            <small>{{ item.description }}</small>
          </span>
          <span v-if="!item.sidebarVisible" class="hidden-badge">侧栏已隐藏</span>
          <i
            :class="
              item.meta.openMode === 'NEW_PAGE' ? 'ri-arrow-right-up-line' : 'ri-arrow-right-line'
            "
            class="card-arrow"
          ></i>
        </button>
      </div>
    </section>

    <section v-if="apps.length" class="catalog-section dynamic-section">
      <header>
        <span><i class="ri-apps-2-line"></i></span>
        <div>
          <h2>接入应用</h2>
          <p>来自 WAO 真实应用注册表，侧栏隐藏后仍可在这里打开。</p>
        </div>
      </header>
      <div class="card-grid">
        <button
          v-for="app in apps"
          :key="app.id"
          class="nav-card"
          type="button"
          @click="openApp(app.id)"
        >
          <span class="card-icon"><i :class="app.icon"></i></span>
          <span class="card-copy"
            ><b>{{ app.label }}</b
            ><small>{{ app.appUrl }}</small></span
          >
          <span v-if="!app.visible" class="hidden-badge">侧栏已隐藏</span>
          <i
            :class="app.openMode === 'NEW_PAGE' ? 'ri-arrow-right-up-line' : 'ri-arrow-right-line'"
            class="card-arrow"
          ></i>
        </button>
      </div>
    </section>

    <section v-if="links.length" class="catalog-section dynamic-section">
      <header>
        <span><i class="ri-links-line"></i></span>
        <div>
          <h2>我的快捷链接</h2>
          <p>来自当前账号的跨设备配置，不会展示其他成员的链接。</p>
        </div>
      </header>
      <div class="card-grid">
        <button
          v-for="link in links"
          :key="link.id"
          class="nav-card"
          type="button"
          @click="openLink(link)"
        >
          <span class="card-icon"><i :class="link.icon"></i></span>
          <span class="card-copy"
            ><b>{{ link.label }}</b
            ><small>{{ link.subtitle || link.url }}</small></span
          >
          <i
            :class="link.openMode === 'NEW_PAGE' ? 'ri-arrow-right-up-line' : 'ri-arrow-right-line'"
            class="card-arrow"
          ></i>
        </button>
      </div>
    </section>

    <section v-if="!loading && !resultCount" class="empty-state">
      <i class="ri-search-eye-line"></i><b>没有找到匹配入口</b
      ><span>换个名称、用途或关键词试试。</span>
      <button type="button" @click="query = ''">清除搜索</button>
    </section>
  </main>
</template>

<style scoped>
.navigation-center {
  width: min(1220px, 100%);
  box-sizing: border-box;
  margin: 0 auto;
  padding: 28px 28px 48px;
}
.hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}
.eyebrow {
  color: var(--wao-primary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
}
.hero h1 {
  margin: 7px 0 5px;
  color: var(--wao-text);
  font-size: clamp(28px, 4vw, 42px);
  line-height: 1.1;
}
.hero p,
.catalog-section header p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 12.5px;
  line-height: 1.6;
}
.manage {
  min-height: 36px;
  padding: 0 13px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
  white-space: nowrap;
}
.manage:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary);
}
.search {
  height: 46px;
  margin: 24px 0 8px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: 12px;
  background: var(--wao-bg-card);
  box-shadow: 0 8px 28px color-mix(in srgb, var(--wao-text) 5%, transparent);
}
.search:focus-within {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px var(--wao-primary-soft);
}
.search > i {
  color: var(--wao-text-weak);
  font-size: 18px;
}
.search input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--wao-text);
  font: inherit;
}
.search span {
  color: var(--wao-text-faint);
  font-size: 11px;
  white-space: nowrap;
}
.load-status {
  margin-top: 12px;
  padding: 9px 11px;
  display: flex;
  align-items: center;
  gap: 7px;
  border-radius: 8px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 11.5px;
}
.load-status span {
  flex: 1;
}
.load-status.error {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.load-status button {
  border: 0;
  background: none;
  color: inherit;
  cursor: pointer;
}
.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.catalog-section {
  margin-top: 28px;
}
.catalog-section > header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 11px;
}
.catalog-section > header > span {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary);
  font-size: 17px;
}
.catalog-section h2 {
  margin: 0;
  color: var(--wao-text);
  font-size: 15px;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.nav-card {
  min-width: 0;
  min-height: 84px;
  padding: 14px;
  display: flex;
  align-items: center;
  gap: 11px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.14s ease,
    transform 0.14s ease,
    box-shadow 0.14s ease;
}
.nav-card:hover {
  border-color: color-mix(in srgb, var(--wao-primary) 50%, var(--wao-line));
  box-shadow: 0 8px 20px color-mix(in srgb, var(--wao-text) 6%, transparent);
  transform: translateY(-1px);
}
.card-icon {
  width: 38px;
  height: 38px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: var(--wao-bg-sub);
  color: var(--wao-primary);
  font-size: 19px;
}
.card-copy {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 4px;
}
.card-copy b {
  font-size: 13px;
}
.card-copy small {
  overflow: hidden;
  color: var(--wao-text-weak);
  font-size: 10.5px;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.hidden-badge {
  align-self: flex-start;
  flex: none;
  padding: 2px 5px;
  border-radius: 5px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-faint);
  font-size: 9px;
  white-space: nowrap;
}
.card-arrow {
  flex: none;
  color: var(--wao-text-faint);
}
.dynamic-section {
  padding-top: 2px;
  border-top: 1px solid var(--wao-divider);
}
.empty-state {
  min-height: 260px;
  margin-top: 26px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 7px;
  border: 1px dashed var(--wao-line-strong);
  border-radius: 14px;
  color: var(--wao-text-weak);
  text-align: center;
}
.empty-state > i {
  font-size: 28px;
}
.empty-state b {
  color: var(--wao-text-2);
  font-size: 14px;
}
.empty-state span {
  font-size: 12px;
}
.empty-state button {
  margin-top: 4px;
  border: 0;
  background: none;
  color: var(--wao-primary);
  cursor: pointer;
}
@media (max-width: 980px) {
  .card-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 620px) {
  .navigation-center {
    padding: 20px 14px 96px;
  }
  .hero {
    align-items: stretch;
    flex-direction: column;
  }
  .manage {
    justify-content: center;
  }
  .search {
    margin-top: 18px;
  }
  .card-grid {
    grid-template-columns: 1fr;
  }
  .nav-card {
    min-height: 76px;
  }
  .hidden-badge {
    display: none;
  }
}
</style>
