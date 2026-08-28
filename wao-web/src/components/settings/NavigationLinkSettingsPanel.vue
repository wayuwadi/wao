<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  fetchBuiltinMenuRegistry,
  fetchExternalAppRegistry,
  saveBuiltinMenuRegistry,
  saveExternalAppRegistry,
} from '@/api/system'
import { toast } from '@/composables/toast'
import { useQuickLinks } from '@/composables/quickLinks'
import type { QuickLink } from '@/config/quickLinks'
import {
  normalizeBuiltinMenu,
  normalizeExternalApp,
  type BuiltinMenuConfig,
  type BuiltinMenuGroupCode,
  type ExternalAppConfig,
  type MenuOpenMode,
  type MenuPlacement,
} from '@/config/nav'
import { useNavStore } from '@/stores/nav'
import IconPicker from '@/components/ui/IconPicker.vue'

type MenuSource = 'builtin' | 'external'

interface MenuDraft {
  key: string
  source: MenuSource
  id: string
  label: string
  icon: string
  group: BuiltinMenuGroupCode
  order: number
  visible: boolean
  openMode: MenuOpenMode
  placement: MenuPlacement
}

const GROUPS: Array<{ code: BuiltinMenuGroupCode; label: string }> = [
  { code: 'ROOT', label: '顶层' },
  { code: 'PUBLIC', label: '公共' },
  { code: 'PERSONAL', label: '个人' },
  { code: 'TOOLS', label: '工具' },
]

const nav = useNavStore()
const quickLinks = useQuickLinks()

const menuItems = ref<MenuDraft[]>([])
const externalOriginals = ref<Record<string, ExternalAppConfig>>({})
const menuLoading = ref(false)
const menuSaving = ref(false)
const menuError = ref('')
const builtinBaseline = ref('')
const externalBaseline = ref('')
const draggedMenuKey = ref('')

const qlEditing = ref<QuickLink[]>([])
const qlLoading = ref(false)
const qlSaving = ref(false)
const qlError = ref('')
const qlBaseline = ref('')
const draggedQuickLinkId = ref('')

function cloneQuickLinks(links: readonly QuickLink[]): QuickLink[] {
  return links.map((link) => ({ ...link, keywords: [...link.keywords] }))
}

function draftFromBuiltin(menu: BuiltinMenuConfig): MenuDraft {
  return { ...menu, placement: menu.placement ?? 'MENU', key: `builtin:${menu.id}`, source: 'builtin' }
}

function draftFromExternal(app: ExternalAppConfig): MenuDraft {
  return { ...app, placement: app.placement ?? 'MENU', key: `external:${app.id}`, source: 'external' }
}

function orderedInGroup(group: BuiltinMenuGroupCode): MenuDraft[] {
  return menuItems.value
    .filter((item) => item.group === group)
    .sort((left, right) => Number(right.visible) - Number(left.visible) || left.order - right.order)
}

const menuGroups = computed(() => GROUPS.map((group) => ({
  ...group,
  items: orderedInGroup(group.code),
})))

function sourceSnapshot(source: MenuSource): string {
  return JSON.stringify(
    menuItems.value
      .filter((item) => item.source === source)
      .map(({ id, label, icon, group, order, visible, openMode, placement }) => ({
        id, label, icon, group, order, visible, openMode, placement,
      }))
      .sort((left, right) => left.id.localeCompare(right.id)),
  )
}

const menuDirty = computed(() =>
  sourceSnapshot('builtin') !== builtinBaseline.value
  || sourceSnapshot('external') !== externalBaseline.value,
)
const qlDirty = computed(() => JSON.stringify(qlEditing.value) !== qlBaseline.value)

function resequenceMenuGroups() {
  for (const group of GROUPS) {
    for (const placement of ['MENU', 'DOCK'] as const) {
      orderedInGroup(group.code)
        .filter((item) => item.placement === placement)
        .forEach((item, index) => { item.order = (index + 1) * 10 })
    }
  }
}

function canMoveMenu(item: MenuDraft, direction: -1 | 1): boolean {
  const sameVisibility = orderedInGroup(item.group).filter(
    (candidate) => candidate.visible === item.visible && candidate.placement === item.placement,
  )
  const index = sameVisibility.findIndex((candidate) => candidate.key === item.key)
  return index >= 0 && index + direction >= 0 && index + direction < sameVisibility.length
}

function moveMenu(item: MenuDraft, direction: -1 | 1) {
  const sameVisibility = orderedInGroup(item.group).filter(
    (candidate) => candidate.visible === item.visible && candidate.placement === item.placement,
  )
  const index = sameVisibility.findIndex((candidate) => candidate.key === item.key)
  const target = sameVisibility[index + direction]
  if (!target) return
  const currentOrder = item.order
  item.order = target.order
  target.order = currentOrder
  resequenceMenuGroups()
}

function toggleMenuVisibility(item: MenuDraft) {
  item.visible = !item.visible
  resequenceMenuGroups()
}

function changeMenuGroup(item: MenuDraft) {
  if (item.source === 'external' && item.group === 'ROOT') item.group = 'PUBLIC'
  resequenceMenuGroups()
}

function dragMenu(item: MenuDraft, event: DragEvent) {
  draggedMenuKey.value = item.key
  event.dataTransfer?.setData('text/plain', item.key)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

function dropMenu(group: BuiltinMenuGroupCode, targetKey = '') {
  const source = menuItems.value.find((item) => item.key === draggedMenuKey.value)
  if (!source || source.key === targetKey) return
  if (source.source === 'external' && group === 'ROOT') {
    toast('外部应用不能移动到顶层', 'ri-error-warning-line')
    return
  }
  source.group = group
  const groupItems = orderedInGroup(group).filter((item) => item.key !== source.key)
  const targetIndex = targetKey ? groupItems.findIndex((item) => item.key === targetKey) : -1
  const insertAt = targetIndex >= 0 ? targetIndex : groupItems.length
  groupItems.splice(insertAt, 0, source)
  const visible = groupItems.filter((item) => item.visible)
  const hidden = groupItems.filter((item) => !item.visible)
  ;[...visible, ...hidden].forEach((item, index) => { item.order = (index + 1) * 10 })
  resequenceMenuGroups()
  draggedMenuKey.value = ''
}

async function loadMenuSettings() {
  if (menuLoading.value) return
  menuLoading.value = true
  menuError.value = ''
  try {
    const [builtinRegistry, externalRegistry] = await Promise.all([
      fetchBuiltinMenuRegistry(),
      fetchExternalAppRegistry(),
    ])
    const builtin = builtinRegistry.menus.map(normalizeBuiltinMenu)
    const external = externalRegistry.apps.map(normalizeExternalApp)
    externalOriginals.value = Object.fromEntries(external.map((app) => [app.id, { ...app }]))
    menuItems.value = [
      ...builtin.map(draftFromBuiltin),
      ...external.map(draftFromExternal),
    ]
    resequenceMenuGroups()
    builtinBaseline.value = sourceSnapshot('builtin')
    externalBaseline.value = sourceSnapshot('external')
    nav.applyBuiltinMenus(builtin)
    nav.applyExternalApps(external)
  } catch (error) {
    menuError.value = error instanceof Error ? error.message : '菜单配置读取失败'
  } finally {
    menuLoading.value = false
  }
}

async function saveMenuSettings() {
  if (!menuDirty.value || menuSaving.value) return
  resequenceMenuGroups()
  menuSaving.value = true
  menuError.value = ''

  const builtinDirty = sourceSnapshot('builtin') !== builtinBaseline.value
  const externalDirty = sourceSnapshot('external') !== externalBaseline.value
  const builtin = menuItems.value
    .filter((item) => item.source === 'builtin')
    .map(({ id, label, icon, group, order, visible, openMode, placement }) => ({
      id, label, icon, group, order, visible, openMode, placement,
    }) as BuiltinMenuConfig)
  const external = menuItems.value
    .filter((item) => item.source === 'external')
    .map(({ id, label, icon, group, order, visible, openMode, placement }) => ({
      ...externalOriginals.value[id]!,
      id,
      label,
      icon,
      group: group === 'ROOT' ? 'PUBLIC' : group,
      order,
      visible,
      openMode,
      placement,
    }))

  const failures: string[] = []
  try {
    if (builtinDirty) {
      try {
        const registry = await saveBuiltinMenuRegistry(builtin)
        nav.applyBuiltinMenus(registry.menus)
        builtinBaseline.value = sourceSnapshot('builtin')
      } catch (error) {
        failures.push(error instanceof Error ? error.message : '原生菜单保存失败')
      }
    }
    if (externalDirty) {
      try {
        const registry = await saveExternalAppRegistry(external)
        const normalized = registry.apps.map(normalizeExternalApp)
        externalOriginals.value = Object.fromEntries(normalized.map((app) => [app.id, { ...app }]))
        nav.applyExternalApps(normalized)
        externalBaseline.value = sourceSnapshot('external')
      } catch (error) {
        failures.push(error instanceof Error ? error.message : '应用菜单保存失败')
      }
    }
    if (failures.length) {
      menuError.value = `部分配置未保存：${failures.join('；')}`
      toast(menuError.value, 'ri-error-warning-line')
    } else {
      toast('菜单顺序与分组已保存并立即生效', 'ri-check-line')
    }
  } finally {
    menuSaving.value = false
  }
}

function selectMenuIcon() {
  // 图标选择是明确的单击操作，选择后立即写回统一注册表。
  if (menuSaving.value) return
  queueMicrotask(() => void saveMenuSettings())
}

async function loadQuickLinkSettings(force = false) {
  if (qlLoading.value) return
  qlLoading.value = true
  qlError.value = ''
  try {
    await quickLinks.load(force)
    qlEditing.value = cloneQuickLinks(quickLinks.links.value)
    qlBaseline.value = JSON.stringify(qlEditing.value)
    if (quickLinks.loadError.value) qlError.value = quickLinks.loadError.value
  } finally {
    qlLoading.value = false
  }
}

function addQuickLink() {
  qlEditing.value.push({
    id: `custom-${Date.now()}`,
    label: '',
    subtitle: '',
    icon: 'ri-links-line',
    url: '',
    openMode: 'NEW_PAGE',
    keywords: [],
  })
}

function moveQuickLink(index: number, direction: -1 | 1) {
  const target = index + direction
  if (target < 0 || target >= qlEditing.value.length) return
  const moved = qlEditing.value.splice(index, 1)[0]
  if (moved) qlEditing.value.splice(target, 0, moved)
}

function dropQuickLink(targetId: string) {
  const from = qlEditing.value.findIndex((link) => link.id === draggedQuickLinkId.value)
  const to = qlEditing.value.findIndex((link) => link.id === targetId)
  if (from < 0 || to < 0 || from === to) return
  const moved = qlEditing.value.splice(from, 1)[0]
  if (moved) qlEditing.value.splice(to, 0, moved)
  draggedQuickLinkId.value = ''
}

function resetQuickLinks() {
  qlError.value = ''
  qlEditing.value = cloneQuickLinks(quickLinks.defaults)
  toast('已恢复默认草稿，保存后才会生效', 'ri-history-line')
}

async function saveQuickLinks() {
  if (!qlDirty.value || qlSaving.value) return
  qlSaving.value = true
  qlError.value = ''
  try {
    await quickLinks.save(qlEditing.value)
    qlEditing.value = cloneQuickLinks(quickLinks.links.value)
    qlBaseline.value = JSON.stringify(qlEditing.value)
    toast('快捷链接已保存并立即生效', 'ri-check-line')
  } catch (error) {
    qlError.value = error instanceof Error ? error.message : '快捷链接保存失败'
    toast(qlError.value, 'ri-error-warning-line')
  } finally {
    qlSaving.value = false
  }
}

function confirmLeave(): boolean {
  return !(menuDirty.value || qlDirty.value)
    || window.confirm('菜单或快捷链接还有未保存的修改，确认放弃吗？')
}

defineExpose({ confirmLeave })

onMounted(() => {
  void loadMenuSettings()
  void loadQuickLinkSettings()
})
</script>

<template>
  <div class="settings-heading">
    <div>
      <h1>菜单与快捷链接</h1>
      <p>左侧管理 WAO 一级菜单，右侧管理成员自己的左下角链接；两个区域独立保存。</p>
    </div>
  </div>

  <div class="navigation-settings-grid">
    <section class="settings-card menu-card">
      <div class="card-head">
        <div>
          <h2>菜单管理</h2>
          <p>按真实导航分组排列；拖拽可跨组移动，也可选择在侧栏底部显示为图标。</p>
        </div>
        <button type="button" class="primary" :disabled="menuLoading || menuSaving || !menuDirty" @click="saveMenuSettings">
          {{ menuSaving ? '保存中…' : menuDirty ? '保存菜单' : '已保存' }}
        </button>
      </div>

      <div v-if="menuLoading" class="status"><i class="ri-loader-4-line spin"></i>正在读取菜单…</div>
      <div v-else-if="menuError && !menuItems.length" class="status error">
        <span>{{ menuError }}</span>
        <button type="button" @click="loadMenuSettings">重新读取</button>
      </div>
      <div v-else class="menu-groups">
        <section
          v-for="group in menuGroups"
          :key="group.code"
          class="menu-group"
          @dragover.prevent
          @drop.prevent="dropMenu(group.code)"
        >
          <header><strong>{{ group.label }}</strong><span>{{ group.items.length }} 项</span></header>
          <div v-if="!group.items.length" class="empty-drop">拖到这里即可移动到“{{ group.label }}”</div>
          <article
            v-for="item in group.items"
            :key="item.key"
            class="menu-row"
            :class="{ hidden: !item.visible, dragging: draggedMenuKey === item.key }"
            @dragover.prevent
            @drop.stop.prevent="dropMenu(group.code, item.key)"
          >
            <button
              type="button"
              class="drag-handle"
              draggable="true"
              title="拖拽调整顺序或分组"
              @dragstart="dragMenu(item, $event)"
              @dragend="draggedMenuKey = ''"
            ><i class="ri-draggable"></i></button>
            <IconPicker v-model="item.icon" @select="selectMenuIcon" />
            <div class="menu-fields">
              <label><span>名称</span><input v-model.trim="item.label" maxlength="24" /></label>
              <label>
                <span>所属分组</span>
                <select v-model="item.group" aria-label="所属分组" @change="changeMenuGroup(item)">
                  <option v-for="option in GROUPS" :key="option.code" :value="option.code" :disabled="item.source === 'external' && option.code === 'ROOT'">{{ option.label }}</option>
                </select>
              </label>
              <label>
                <span>打开方式</span>
                <select v-model="item.openMode" aria-label="打开方式">
                  <option value="TAB">WAO 页签</option>
                  <option value="NEW_PAGE">新页面</option>
                </select>
              </label>
              <label>
                <span>展示位置</span>
                <select v-model="item.placement" aria-label="展示位置" @change="resequenceMenuGroups">
                  <option value="MENU">分组菜单</option>
                  <option value="DOCK">底部图标</option>
                </select>
              </label>
            </div>
            <div class="menu-meta">
              <span>{{ item.source === 'external' ? '已注册应用' : item.id }}</span>
              <div class="row-actions">
                <button type="button" :disabled="!canMoveMenu(item, -1)" title="上移" @click="moveMenu(item, -1)"><i class="ri-arrow-up-s-line"></i></button>
                <button type="button" :disabled="!canMoveMenu(item, 1)" title="下移" @click="moveMenu(item, 1)"><i class="ri-arrow-down-s-line"></i></button>
                <button type="button" class="visibility" :class="{ on: item.visible }" @click="toggleMenuVisibility(item)">
                  <i :class="item.visible ? 'ri-eye-line' : 'ri-eye-off-line'"></i>{{ item.visible ? '显示' : '隐藏' }}
                </button>
              </div>
            </div>
          </article>
        </section>
      </div>
      <div v-if="menuError && menuItems.length" class="status error" role="alert">{{ menuError }}；未保存草稿仍保留，可直接重试。</div>
      <p class="footnote">外部应用的独立注册表编辑器已隐藏；真实注册接口和应用数据不变，注册成功的应用会自动作为普通菜单项出现在这里。</p>
    </section>

    <section class="settings-card links-card">
      <div class="card-head">
        <div>
          <h2>左下角快捷链接</h2>
          <p>拖拽或用上下按钮排序；“WAO 页签”会在站内嵌入目标页面。</p>
        </div>
        <button type="button" class="primary" :disabled="qlLoading || qlSaving || !qlDirty" @click="saveQuickLinks">
          {{ qlSaving ? '保存中…' : qlDirty ? '保存链接' : '已保存' }}
        </button>
      </div>

      <div v-if="qlLoading" class="status"><i class="ri-loader-4-line spin"></i>正在读取快捷链接…</div>
      <div v-else-if="quickLinks.loadError.value" class="status error">
        <span>当前显示安全默认值：{{ quickLinks.loadError.value }}</span>
        <button type="button" @click="loadQuickLinkSettings(true)">重试</button>
      </div>

      <div class="quick-link-list">
        <article
          v-for="(link, index) in qlEditing"
          :key="link.id"
          class="quick-link-row"
          @dragover.prevent
          @drop.prevent="dropQuickLink(link.id)"
        >
          <button
            type="button"
            class="drag-handle"
            draggable="true"
            title="拖拽调整顺序"
            @dragstart="draggedQuickLinkId = link.id"
            @dragend="draggedQuickLinkId = ''"
          ><i class="ri-draggable"></i></button>
          <IconPicker v-model="link.icon" />
          <div class="quick-link-fields">
            <label><span>名称</span><input v-model.trim="link.label" placeholder="例如 Jenkins" /></label>
            <label><span>地址</span><input v-model.trim="link.url" placeholder="/path 或 https://…" /></label>
            <label><span>描述</span><input v-model.trim="link.subtitle" placeholder="可选" /></label>
            <label class="open-mode">
              <span>打开方式</span>
              <select v-model="link.openMode">
                <option value="TAB">WAO 页签</option>
                <option value="NEW_PAGE">新页面</option>
              </select>
            </label>
          </div>
          <div class="row-actions quick-actions">
            <button type="button" :disabled="index === 0" title="上移" @click="moveQuickLink(index, -1)"><i class="ri-arrow-up-s-line"></i></button>
            <button type="button" :disabled="index === qlEditing.length - 1" title="下移" @click="moveQuickLink(index, 1)"><i class="ri-arrow-down-s-line"></i></button>
            <button type="button" class="remove" title="删除" @click="qlEditing.splice(index, 1)"><i class="ri-delete-bin-line"></i></button>
          </div>
        </article>
      </div>

      <div v-if="qlError" class="status error" role="alert">{{ qlError }}；草稿仍保留，可修改后重试。</div>
      <div class="link-actions">
        <button type="button" @click="addQuickLink"><i class="ri-add-line"></i>添加链接</button>
        <button type="button" @click="resetQuickLinks"><i class="ri-history-line"></i>恢复默认</button>
      </div>
      <p class="footnote">部分网站禁止被 iframe 嵌入；遇到目标站限制时，把该链接改为“新页面”即可。</p>
    </section>
  </div>
</template>

<style scoped>
.settings-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.settings-heading h1 { margin: 0; font-size: 18px; }
.settings-heading p, .card-head p, .footnote { margin: 5px 0 0; color: var(--wao-text-weak); font-size: 12px; line-height: 1.55; }
.navigation-settings-grid { display: grid; grid-template-columns: minmax(520px, 1.45fr) minmax(330px, .9fr); gap: 14px; align-items: start; margin-top: 14px; }
.settings-card { min-width: 0; padding: 16px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); }
.card-head { display: flex; align-items: flex-start; gap: 12px; }
.card-head > div { flex: 1; min-width: 0; }
.card-head h2 { margin: 0; font-size: 14px; }
.primary { min-height: 34px; padding: 0 13px; border: 0; border-radius: 8px; background: var(--wao-primary); color: #fff; cursor: pointer; white-space: nowrap; }
.primary:disabled { opacity: .55; cursor: default; }
.status { display: flex; align-items: center; gap: 7px; margin-top: 12px; padding: 9px 10px; border-radius: 8px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 12px; }
.status span { flex: 1; }
.status button { border: 0; background: none; color: inherit; cursor: pointer; }
.status.error { background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
.spin { animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.menu-groups { display: grid; gap: 12px; margin-top: 14px; }
.menu-group { min-height: 54px; border: 1px solid var(--wao-divider); border-radius: 10px; background: var(--wao-bg-sub); overflow: hidden; }
.menu-group > header { display: flex; align-items: center; gap: 8px; padding: 9px 11px; color: var(--wao-text-2); font-size: 12px; }
.menu-group > header span { color: var(--wao-text-faint); font-size: 10.5px; }
.empty-drop { margin: 0 8px 8px; padding: 12px; border: 1px dashed var(--wao-line-strong); border-radius: 7px; color: var(--wao-text-faint); text-align: center; font-size: 11px; }
.menu-row, .quick-link-row { display: grid; align-items: center; gap: 8px; padding: 10px; border-top: 1px solid var(--wao-divider); background: var(--wao-bg-card); }
.menu-row { grid-template-columns: 24px 30px minmax(0, 1fr) auto; }
.menu-row.hidden { opacity: .68; }
.menu-row.dragging { opacity: .4; }
.drag-handle { width: 24px; height: 30px; display: grid; place-items: center; padding: 0; border: 0; border-radius: 6px; background: transparent; color: var(--wao-text-faint); cursor: grab; }
.drag-handle:hover { background: var(--wao-bg-hover); color: var(--wao-text-2); }
.menu-icon { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 8px; background: var(--wao-bg-sub); color: var(--wao-text-3); }
.menu-fields, .quick-link-fields { display: grid; gap: 7px; }
.menu-fields { grid-template-columns: 1.1fr 90px 100px 92px; }
.menu-fields label, .quick-link-fields label { display: grid; gap: 4px; min-width: 0; color: var(--wao-text-weak); font-size: 10.5px; }
.menu-fields input, .menu-fields select, .quick-link-fields input, .quick-link-fields select { min-width: 0; height: 32px; box-sizing: border-box; padding: 0 8px; border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text); outline: none; }
.menu-fields input:focus, .menu-fields select:focus, .quick-link-fields input:focus, .quick-link-fields select:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 2px var(--wao-primary-soft); }
.menu-meta { display: grid; justify-items: end; gap: 7px; min-width: 112px; }
.menu-meta > span { max-width: 150px; overflow: hidden; color: var(--wao-text-faint); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.row-actions { display: flex; align-items: center; gap: 3px; }
.row-actions button, .link-actions button { min-height: 28px; padding: 0 7px; border: 1px solid var(--wao-line); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-text-3); cursor: pointer; }
.row-actions button:disabled { opacity: .3; cursor: default; }
.row-actions .visibility { display: inline-flex; align-items: center; gap: 4px; min-width: 62px; justify-content: center; }
.row-actions .visibility.on { color: var(--wao-primary); background: var(--wao-primary-tint); }
.quick-link-list { margin-top: 14px; border: 1px solid var(--wao-divider); border-radius: 10px; overflow: hidden; }
.quick-link-row { grid-template-columns: 24px 30px minmax(0, 1fr) auto; }
.quick-link-row:first-child { border-top: 0; }
.quick-link-fields { grid-template-columns: 1fr 1fr; }
.quick-link-fields .open-mode { grid-column: 1 / -1; }
.quick-actions { flex-direction: column; }
.row-actions .remove { color: var(--wao-danger); }
.link-actions { display: flex; gap: 8px; margin-top: 12px; }
.link-actions button { display: inline-flex; align-items: center; gap: 5px; }
.footnote { padding-top: 10px; }
@media (max-width: 1180px) { .navigation-settings-grid { grid-template-columns: 1fr; } }
@media (max-width: 760px) {
  .settings-heading p { display: block; }
  .card-head { flex-direction: column; }
  .primary { width: 100%; justify-content: center; }
  .menu-row, .quick-link-row { grid-template-columns: 24px 30px minmax(0, 1fr); }
  .menu-fields, .quick-link-fields { grid-template-columns: 1fr 1fr; }
  .menu-fields { grid-column: 3; }
  .menu-meta, .quick-actions { grid-column: 1 / -1; justify-items: stretch; flex-direction: row; }
  .menu-meta { display: flex; justify-content: space-between; align-items: center; }
}
</style>
