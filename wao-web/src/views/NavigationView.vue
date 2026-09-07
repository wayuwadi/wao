<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NAVIGATION_CATALOG, type NavigationCatalogModuleId } from '@/config/navigationCatalog'
import { externalAppItemId } from '@/config/nav'
import { quickLinkPath, type QuickLink } from '@/config/quickLinks'
import { useQuickLinks } from '@/composables/quickLinks'
import { useFunctionHealth } from '@/composables/functionHealth'
import { toast } from '@/composables/toast'
import { useNavStore } from '@/stores/nav'
import type { NavItemId } from '@/config/nav'

const router = useRouter()
const nav = useNavStore()
const quickLinks = useQuickLinks()
const health = useFunctionHealth()
const query = ref('')
const menuActionId = ref<NavItemId | null>(null)
const failedMenuAction = ref<{ id: NavItemId; visible: boolean; message: string } | null>(null)

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
        return {
          ...item,
          meta,
          sidebarVisible: configured?.visible ?? false,
          health: health.byId.value[item.id],
        }
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
const showQuickNote = computed(() => includesQuery(['小记', '记录', '笔记', '常用入口']))
const showSettings = computed(() => includesQuery(['设置', '账号', '偏好', '菜单', '常用入口']))
const resultCount = computed(
  () =>
    sections.value.reduce((sum, section) => sum + section.items.length, 0) +
    apps.value.length +
    links.value.length +
    (showQuickNote.value ? 1 : 0) +
    (showSettings.value ? 1 : 0),
)
const loading = computed(
  () =>
    nav.builtinMenusState === 'loading' ||
    nav.externalAppsState === 'loading' ||
    quickLinks.loading.value ||
    health.loading.value,
)
const errors = computed(() =>
  [
    nav.builtinMenusError,
    nav.externalAppsError,
    quickLinks.loadError.value,
    health.error.value,
  ].filter(Boolean),
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

async function setMenuVisibility(id: NavItemId, visible: boolean) {
  if (menuActionId.value) return
  menuActionId.value = id
  failedMenuAction.value = null
  try {
    await nav.setMenuVisibility(id, visible)
    toast(visible ? '菜单已显示' : '菜单已隐藏，仍可从工作台恢复', 'ri-check-line')
  } catch (error) {
    const message = error instanceof Error ? error.message : '菜单保存失败'
    failedMenuAction.value = { id, visible, message }
    toast(`${message}，可在工作台重试`, 'ri-error-warning-line')
  } finally {
    menuActionId.value = null
  }
}

function retryMenuAction() {
  const failed = failedMenuAction.value
  if (failed) void setMenuVisibility(failed.id, failed.visible)
}

function retry() {
  void nav.loadBuiltinMenus(true)
  void nav.loadExternalApps(true)
  void quickLinks.load(true)
  void health.load(true)
}

onMounted(() => {
  void nav.loadBuiltinMenus()
  void nav.loadExternalApps()
  void quickLinks.load()
  void health.load()
})
</script>

<template>
  <main class="navigation-center">
    <header class="hero">
      <div>
        <div class="eyebrow"><i class="ri-apps-2-line"></i> WAO WORKBENCH</div>
        <h1>工作台</h1>
        <p>项目和小记留在手边；其他真实功能、接入应用和个人链接都从这里打开。</p>
      </div>
    </header>

    <label class="search">
      <i class="ri-search-line"></i>
      <input v-model="query" type="search" placeholder="搜索功能、应用或链接" />
      <span v-if="query.trim()">{{ resultCount }} 个结果</span>
    </label>

    <section class="health-summary" aria-label="功能健康度概览">
      <div>
        <b>{{ health.overview.value?.recentOpens ?? 0 }}</b
        ><span>近 30 天真实打开</span>
      </div>
      <div>
        <b>{{
          health.overview.value?.functions.filter((item) => item.recentOpens > 0).length ?? 0
        }}</b
        ><span>近期使用功能</span>
      </div>
      <p>
        <i class="ri-pulse-line"></i> AI
        巡检会同时读取这份真实使用快照，分析低频、路径和功能重叠；没有数据时不会补演示结论。
      </p>
    </section>

    <div v-if="errors.length" class="load-status error" role="alert">
      <i class="ri-error-warning-line"></i>
      <span>部分目录读取失败，当前仍可使用安全默认值：{{ errors.join('；') }}</span>
      <button type="button" @click="retry">重试</button>
    </div>
    <div v-else-if="loading" class="load-status" role="status">
      <i class="ri-loader-4-line spin"></i>正在同步最新导航…
    </div>
    <div v-if="failedMenuAction" class="load-status error menu-action-error" role="alert">
      <i class="ri-error-warning-line"></i>
      <span>菜单修改未保存：{{ failedMenuAction.message }}</span>
      <button type="button" :disabled="!!menuActionId" @click="retryMenuAction">重试</button>
    </div>

    <section v-if="showQuickNote || showSettings" class="catalog-section quick-actions-section">
      <header>
        <span><i class="ri-flashlight-line"></i></span>
        <div>
          <h2>常用入口</h2>
          <p>直接复用 WAO 的真实创建与设置链路，不新增演示入口。</p>
        </div>
      </header>
      <div class="card-grid">
        <button v-if="showQuickNote" class="nav-card" type="button" @click="router.push('/notes')">
          <span class="card-icon"><i class="ri-sticky-note-line"></i></span>
          <span class="card-copy"><b>小记</b><small>直接记录、搜索和回看全部小记。</small></span>
          <i class="ri-arrow-right-line card-arrow"></i>
        </button>
        <button
          v-if="showSettings"
          class="nav-card"
          type="button"
          @click="router.push('/settings')"
        >
          <span class="card-icon"><i class="ri-settings-3-line"></i></span>
          <span class="card-copy"
            ><b>设置</b><small>管理账号、偏好、全局菜单和个人链接。</small></span
          >
          <i class="ri-arrow-right-line card-arrow"></i>
        </button>
      </div>
    </section>

    <section v-for="section in sections" :key="section.id" class="catalog-section">
      <header>
        <span><i :class="section.icon"></i></span>
        <div>
          <h2>{{ section.label }}</h2>
          <p>{{ section.description }}</p>
        </div>
      </header>
      <div class="card-grid">
        <article v-for="item in section.items" :key="item.id" class="nav-card manageable-card">
          <button class="card-open" type="button" @click="openModule(item.id)">
            <span class="card-icon"><i :class="item.meta.icon"></i></span>
            <span class="card-copy">
              <b>{{ item.meta.label }}</b>
              <small>{{ item.description }}</small>
              <em v-if="item.health" :class="item.health.insightLevel.toLowerCase()">{{
                item.health.insight
              }}</em>
            </span>
            <span v-if="item.health" class="health-badges">
              <b>{{ item.health.score }} 分</b>
              <small
                >30 天 {{ item.health.recentOpens }} 次 ·
                {{ item.health.recentShare.toFixed(1) }}%</small
              >
            </span>
            <span v-if="!item.sidebarVisible" class="hidden-badge">侧栏已隐藏</span>
            <i
              :class="
                item.meta.openMode === 'NEW_PAGE' ? 'ri-arrow-right-up-line' : 'ri-arrow-right-line'
              "
              class="card-arrow"
            ></i>
          </button>
          <button
            type="button"
            class="menu-visibility"
            :class="{ on: item.sidebarVisible }"
            :disabled="!!menuActionId"
            :aria-label="`${item.meta.label}：${item.sidebarVisible ? '隐藏菜单' : '显示菜单'}`"
            @click="setMenuVisibility(item.id, !item.sidebarVisible)"
          >
            <i
              :class="
                menuActionId === item.id
                  ? 'ri-loader-4-line spin'
                  : item.sidebarVisible
                    ? 'ri-eye-line'
                    : 'ri-eye-off-line'
              "
            ></i>
            <span>{{ item.sidebarVisible ? '隐藏菜单' : '显示菜单' }}</span>
          </button>
        </article>
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
        <article v-for="app in apps" :key="app.id" class="nav-card manageable-card">
          <button class="card-open" type="button" @click="openApp(app.id)">
            <span class="card-icon"><i :class="app.icon"></i></span>
            <span class="card-copy"
              ><b>{{ app.label }}</b
              ><small>{{ app.appUrl }}</small></span
            >
            <span v-if="!app.visible" class="hidden-badge">侧栏已隐藏</span>
            <i
              :class="
                app.openMode === 'NEW_PAGE' ? 'ri-arrow-right-up-line' : 'ri-arrow-right-line'
              "
              class="card-arrow"
            ></i>
          </button>
          <button
            type="button"
            class="menu-visibility"
            :class="{ on: app.visible }"
            :disabled="!!menuActionId"
            :aria-label="`${app.label}：${app.visible ? '隐藏菜单' : '显示菜单'}`"
            @click="setMenuVisibility(externalAppItemId(app.id), !app.visible)"
          >
            <i
              :class="
                menuActionId === externalAppItemId(app.id)
                  ? 'ri-loader-4-line spin'
                  : app.visible
                    ? 'ri-eye-line'
                    : 'ri-eye-off-line'
              "
            ></i>
            <span>{{ app.visible ? '隐藏菜单' : '显示菜单' }}</span>
          </button>
        </article>
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
.health-summary {
  display: grid;
  grid-template-columns: auto auto 1fr;
  gap: 10px;
  margin: 10px 0 20px;
}
.health-summary > div,
.health-summary > p {
  margin: 0;
  padding: 11px 13px;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-card);
}
.health-summary > div {
  min-width: 110px;
  display: grid;
}
.health-summary > div b {
  font-size: 18px;
  color: var(--wao-primary-text);
}
.health-summary > div span {
  font-size: 9px;
  color: var(--wao-text-weak);
}
.health-summary > p {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--wao-text-2);
  font-size: 10px;
  line-height: 1.55;
}
.health-summary > p i {
  font-size: 18px;
  color: var(--wao-primary);
}
.card-copy em {
  display: block;
  margin-top: 5px;
  color: var(--wao-text-weak);
  font-size: 9px;
  font-style: normal;
}
.card-copy em.low,
.card-copy em.high {
  color: var(--wao-warning-strong);
}
.card-copy em.mature,
.card-copy em.stable {
  color: var(--wao-success-strong);
}
.health-badges {
  display: grid;
  justify-items: end;
  gap: 2px;
  margin-left: auto;
  white-space: nowrap;
}
.health-badges b {
  color: var(--wao-primary-text);
  font-size: 11px;
}
.health-badges small {
  color: var(--wao-text-faint);
  font-size: 8px;
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
.manageable-card {
  padding: 0;
  overflow: hidden;
}
.card-open {
  min-width: 0;
  min-height: 82px;
  padding: 14px 8px 14px 14px;
  display: flex;
  align-items: center;
  gap: 11px;
  flex: 1;
  align-self: stretch;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.menu-visibility {
  width: 70px;
  min-height: 82px;
  padding: 9px 6px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 5px;
  align-self: stretch;
  border: 0;
  border-left: 1px solid var(--wao-divider);
  background: var(--wao-bg-sub);
  color: var(--wao-text-faint);
  font-size: 9px;
  cursor: pointer;
}
.menu-visibility i {
  font-size: 16px;
}
.menu-visibility.on {
  color: var(--wao-primary-text);
}
.menu-visibility:hover:not(:disabled) {
  background: var(--wao-primary-soft);
  color: var(--wao-primary);
}
.menu-visibility:disabled {
  cursor: wait;
  opacity: 0.65;
}
.card-open:focus-visible,
.menu-visibility:focus-visible {
  position: relative;
  z-index: 1;
  outline: 2px solid var(--wao-primary);
  outline-offset: -2px;
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
  .search {
    margin-top: 18px;
  }
  .health-summary {
    grid-template-columns: 1fr 1fr;
  }
  .health-summary > p {
    grid-column: 1 / -1;
  }
  .health-badges {
    display: none;
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
