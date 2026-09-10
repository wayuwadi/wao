<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAVIGATION_CATALOG, matchesFunction, menuLocation } from '@/config/navigationCatalog'
import { externalAppItemId, type NavItemId } from '@/config/nav'
import { quickLinkPath, type QuickLink } from '@/config/quickLinks'
import { useQuickLinks } from '@/composables/quickLinks'
import { useNavStore } from '@/stores/nav'
import { useTopShortcutsStore } from '@/stores/topShortcuts'
import { useMenuDrag } from '@/composables/menuDrag'
import { toast } from '@/composables/toast'
const Governance = defineAsyncComponent(() => import('@/components/nav/NavigationGovernance.vue'))
const route = useRoute()
const router = useRouter()
const nav = useNavStore()
const links = useQuickLinks()
const shortcuts = useTopShortcutsStore()
const drag = useMenuDrag()
const query = ref('')
const selected = ref('today')
const actionError = ref('')
const layout = computed(() => nav.currentLayout())
const governance = computed(() => route.query.view === 'governance')
const sections = computed(() =>
  NAVIGATION_CATALOG.map((section) => ({
    ...section,
    items: section.items.filter((item) =>
      matchesFunction(item.id, nav.itemMeta(item.id)?.label ?? item.id, query.value),
    ),
  })).filter(
    (section) =>
      section.items.length &&
      (query.value.trim() || selected.value === 'all' || selected.value === section.id),
  ),
)
const visibleApps = computed(() =>
  nav.externalApps.filter((app) =>
    matchesFunction(externalAppItemId(app.id), [app.label, app.appUrl, app.group, '应用', '外部应用'].join(' '), query.value),
  ),
)
const visibleLinks = computed(() =>
  links.links.value.filter(
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
function position(id: NavItemId) {
  const item = layout.value.items.find((item) => item.id === id)
  return item?.visible ? item.placement : 'HIDDEN'
}
async function place(id: NavItemId, event: Event) {
  const value = (event.target as HTMLSelectElement).value
  const next = nav.currentLayout()
  const item = next.items.find((item) => item.id === id)
  if (!item) return
  item.visible = value !== 'HIDDEN'
  if (value === 'MENU' || value === 'TOP' || value === 'DOCK') item.placement = value
  actionError.value = ''
  try {
    await nav.saveLayout(next)
    toast('个人导航已更新', 'ri-check-line')
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '导航未保存'
    ;(event.target as HTMLSelectElement).value = position(id)
  }
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
  <main class="navigation-center">
    <header class="hero">
      <div>
        <span class="eyebrow">WAO / 导航</span>
        <h1>{{ governance ? '功能治理' : '全部功能' }}</h1>
        <p>
          {{
            governance ? '查看真实使用与运行情况。' : '按要做的事找入口，把常用功能放在顺手的位置。'
          }}
        </p>
      </div>
      <div class="header-actions">
        <button @click="router.push('/settings?tab=navigation')">
          <i class="ri-layout-line"></i>整理我的导航</button
        ><button
          @click="
            router.replace({ path: '/navigation', query: governance ? {} : { view: 'governance' } })
          "
        >
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
      <p v-if="query.trim()" class="hint">搜索覆盖所有分类，也包含未固定的功能。</p>
      <div v-if="nav.layoutError || links.loadError.value" role="alert" class="status error">
        {{
          nav.layoutError
            ? nav.layoutError + '。当前展示已知目录，个人位置暂无法确认。'
            : '快捷链接读取失败：' + links.loadError.value
        }}<button @click="retry">重试读取</button>
      </div>
      <div v-else-if="nav.layoutLoading || links.loading.value" class="status" role="status">
        正在同步你的导航…
      </div>
      <div v-if="actionError || nav.menuMutationError" class="status error" role="alert">
        {{ actionError || nav.menuMutationError }}。原布局已恢复，草稿保留。<button
          @click="router.push('/settings?tab=navigation')"
        >
          处理保存问题
        </button>
      </div>
      <section v-for="section in sections" :key="section.id" class="catalog-section">
        <header>
          <div>
            <h2>{{ section.label }}</h2>
            <p>{{ section.description }}</p>
          </div>
          <span>{{ section.items.length }} 个功能</span>
        </header>
        <div class="function-list">
          <article v-for="item in section.items" :key="item.id" class="function-row">
            <button
              v-if="nav.menuConfig(item.id)"
              class="drag"
              :draggable="nav.canMoveMenus"
              :disabled="!nav.canMoveMenus"
              :aria-label="'拖动' + nav.itemMeta(item.id)?.label"
              title="可直接拖到左侧、顶部或底部"
              @dragstart="drag.onDragStart(item.id, $event)"
              @dragend="drag.onDragEnd"
            >
              <i class="ri-draggable"></i></button
            ><span v-else class="drag"></span>
            <button class="function-open" @click="open(item.id)">
              <i :class="nav.itemMeta(item.id)?.icon"></i
              ><span
                ><b>{{
                  item.id === 'workspaces' && nav.itemMeta(item.id)?.label === '工作台'
                    ? '链接空间'
                    : nav.itemMeta(item.id)?.label
                }}</b
                ><small>{{ item.description }}</small></span
              ><i
                :class="
                  nav.itemMeta(item.id)?.openMode === 'NEW_PAGE'
                    ? 'ri-external-link-line'
                    : 'ri-arrow-right-line'
                "
              ></i>
            </button>
            <div class="position">
              <small>{{ nav.layoutLoaded ? menuLocation(item.id, layout) : '位置同步中' }}</small
              ><select
                v-if="nav.menuConfig(item.id)"
                :value="position(item.id)"
                :disabled="!nav.canEditLayout || nav.layoutConflict"
                :aria-label="nav.itemMeta(item.id)?.label + '展示位置'"
                @change="place(item.id, $event)"
              >
                <option value="HIDDEN">不固定</option>
                <option value="MENU">左侧菜单</option>
                <option value="TOP">顶部菜单</option>
                <option value="DOCK">底部停靠</option>
              </select>
            </div>
          </article>
        </div>
      </section>
      <section v-if="dynamicVisible && visibleApps.length" class="catalog-section">
        <header>
          <h2>接入应用</h2>
          <span>{{ visibleApps.length }} 个应用</span>
        </header>
        <div class="function-list">
          <article v-for="app in visibleApps" :key="app.id" class="function-row">
            <button
              class="drag"
              :draggable="nav.canMoveMenus"
              :disabled="!nav.canMoveMenus"
              :aria-label="'拖动' + app.label"
              @dragstart="drag.onDragStart(externalAppItemId(app.id), $event)"
              @dragend="drag.onDragEnd"
            >
              <i class="ri-draggable"></i></button
            ><button class="function-open" @click="open(externalAppItemId(app.id))">
              <i :class="app.icon"></i
              ><span
                ><b>{{ app.label }}</b
                ><small>{{
                  app.openMode === 'NEW_PAGE' ? '在新页面打开' : '在 WAO 页签打开'
                }}</small></span
              ><i class="ri-arrow-right-line"></i>
            </button>
            <div class="position">
              <small>{{ menuLocation(externalAppItemId(app.id), layout) }}</small
              ><select
                :value="position(externalAppItemId(app.id))"
                :disabled="!nav.canEditLayout || nav.layoutConflict"
                :aria-label="app.label + '展示位置'"
                @change="place(externalAppItemId(app.id), $event)"
              >
                <option value="HIDDEN">不固定</option>
                <option value="MENU">左侧菜单</option>
                <option value="TOP">顶部菜单</option>
                <option value="DOCK">底部停靠</option>
              </select>
            </div>
          </article>
        </div>
      </section>
      <section v-if="dynamicVisible && visibleLinks.length" class="catalog-section">
        <header>
          <h2>我的快捷链接</h2>
          <span>可拖到顶部收藏</span>
        </header>
        <div class="function-list">
          <button
            v-for="link in visibleLinks"
            :key="link.id"
            class="link-row"
            draggable="true"
            @dragstart="
              shortcuts.start(
                {
                  key: 'link:' + link.id,
                  label: link.label,
                  icon: link.icon,
                  path: quickLinkPath(link.id),
                  quickLinkId: link.id,
                },
                $event,
              )
            "
            @dragend="shortcuts.end"
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
.navigation-center {
  max-width: 1120px;
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
  font-size: 27px;
  letter-spacing: -0.5px;
}
h2 {
  margin: 0;
  font-size: 16px;
}
p {
  color: var(--wao-text-weak);
  font-size: 13px;
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
  color: var(--wao-text-weak);
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
  color: var(--wao-primary);
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
  color: var(--wao-text-faint);
  font-size: 11px;
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
  color: var(--wao-primary);
  font-size: 19px;
}
.function-open > span,
.link-row > span {
  flex: 1;
  min-width: 0;
}
b {
  display: block;
  font-size: 13px;
  font-weight: 600;
}
small {
  display: block;
  font-size: 11px;
  color: var(--wao-text-weak);
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
  font-size: 10px;
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
