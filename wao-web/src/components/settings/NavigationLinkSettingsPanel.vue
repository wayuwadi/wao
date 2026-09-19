<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { toast } from '@/composables/toast'
import { useQuickLinks } from '@/composables/quickLinks'
import type { QuickLink } from '@/config/quickLinks'
import { type BuiltinMenuGroupCode, type MenuOpenMode, type MenuPlacement } from '@/config/nav'
import { useNavStore } from '@/stores/nav'
import { recommendedLayout } from '@/config/navigationCatalog'
import { validateNavigationLayout, type NavigationLayout, type NavigationGroup } from '@/config/navigationLayout'
import { useSessionStore } from '@/stores/session'
import { externalAppItemId, type NavItemId } from '@/config/nav'
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

const nav = useNavStore()
const quickLinks = useQuickLinks()
const session = useSessionStore()
const draftOwner = session.userId
const draftKey = `wao-navigation-settings-draft-v1:${draftOwner}`
const draftReady = ref(false)
const localDraftStatus = ref('')
let unreadDraft = false

const menuItems = ref<MenuDraft[]>([])
const draftGroups = ref<NavigationGroup[]>([])
const GROUPS = computed(() =>
  draftGroups.value.map((g) => ({ code: g.id, label: g.label || '顶层' })),
)
const groupsBaseline = ref('')
const draftLocked = ref(false)
const lockedBaseline = ref(false)
const draftRevision = ref('none')
const menuQuery = ref('')
const previousLayout = ref<NavigationLayout | null>(null)
const previewing = ref(false)
const newGroupName = ref('')
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

function orderedInGroup(group: BuiltinMenuGroupCode): MenuDraft[] {
  return menuItems.value
    .filter((item) => item.group === group)
    .sort((left, right) => Number(right.visible) - Number(left.visible) || left.order - right.order)
}

const menuGroups = computed(() =>
  GROUPS.value.map((group) => ({
    ...group,
    items: orderedInGroup(group.code).filter(
      (item) =>
        !menuQuery.value.trim() ||
        (item.label + item.id).toLowerCase().includes(menuQuery.value.trim().toLowerCase()),
    ),
  })),
)

function sourceSnapshot(source: MenuSource): string {
  return JSON.stringify(
    menuItems.value
      .filter((item) => item.source === source)
      .map(({ id, label, icon, group, order, visible, openMode, placement }) => ({
        id,
        label,
        icon,
        group,
        order,
        visible,
        openMode,
        placement,
      }))
      .sort((left, right) => left.id.localeCompare(right.id)),
  )
}

const menuDirty = computed(
  () =>
    sourceSnapshot('builtin') !== builtinBaseline.value ||
    sourceSnapshot('external') !== externalBaseline.value ||
    JSON.stringify(draftGroups.value) !== groupsBaseline.value ||
    draftLocked.value !== lockedBaseline.value,
)
const qlDirty = computed(() => JSON.stringify(qlEditing.value) !== qlBaseline.value)

function resequenceMenuGroups() {
  for (const group of GROUPS.value) {
    orderedInGroup(group.code)
      .filter((item) => item.placement === 'MENU')
      .forEach((item, index) => {
        item.order = (index + 1) * 10
      })
  }
  for (const placement of ['DOCK', 'TOP'] as const) {
    menuItems.value
      .filter((item) => item.placement === placement)
      .sort((left, right) => left.order - right.order)
      .forEach((item, index) => {
        item.order = (index + 1) * 10
      })
  }
}

function orderedPeers(item: MenuDraft) {
  return menuItems.value
    .filter(
      (candidate) =>
        candidate.visible === item.visible &&
        candidate.placement === item.placement &&
        (item.placement !== 'MENU' || candidate.group === item.group),
    )
    .sort((left, right) => left.order - right.order)
}

function canMoveMenu(item: MenuDraft, direction: -1 | 1): boolean {
  const peers = orderedPeers(item)
  const index = peers.findIndex((candidate) => candidate.key === item.key)
  return index >= 0 && index + direction >= 0 && index + direction < peers.length
}

function moveMenu(item: MenuDraft, direction: -1 | 1) {
  const peers = orderedPeers(item)
  const index = peers.findIndex((candidate) => candidate.key === item.key)
  const target = peers[index + direction]
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

function changeMenuGroup() {
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
  source.group = group
  const groupItems = orderedInGroup(group).filter((item) => item.key !== source.key)
  const targetIndex = targetKey ? groupItems.findIndex((item) => item.key === targetKey) : -1
  const insertAt = targetIndex >= 0 ? targetIndex : groupItems.length
  groupItems.splice(insertAt, 0, source)
  const visible = groupItems.filter((item) => item.visible)
  const hidden = groupItems.filter((item) => !item.visible)
  ;[...visible, ...hidden].forEach((item, index) => {
    item.order = (index + 1) * 10
  })
  resequenceMenuGroups()
  draggedMenuKey.value = ''
}

function readDraft(layout: NavigationLayout) {
  draftGroups.value = layout.groups.map((g) => ({ ...g }))
  draftLocked.value = layout.locked
  menuItems.value = layout.items.map((item) => ({
    ...item,
    key: item.id,
    source: item.id.startsWith('external:') ? 'external' : 'builtin',
    id: item.id.replace(/^external:/, ''),
  }))
}
function markBaseline() {
  builtinBaseline.value = sourceSnapshot('builtin')
  externalBaseline.value = sourceSnapshot('external')
  groupsBaseline.value = JSON.stringify(draftGroups.value)
  lockedBaseline.value = draftLocked.value
  draftRevision.value = nav.layoutRevision
}
function draftLayout(): NavigationLayout {
  return {
    schemaVersion: 1,
    groups: draftGroups.value.map((g) => ({ ...g })),
    locked: draftLocked.value,
    items: menuItems.value.map(
      ({ id, source, label, icon, group, order, visible, openMode, placement }) => ({
        id: source === 'external' ? externalAppItemId(id) : (id as NavItemId),
        label,
        icon,
        group,
        order,
        visible,
        openMode,
        placement,
      }),
    ),
  }
}
async function loadMenuSettings() {
  if (menuLoading.value) return
  menuLoading.value = true
  menuError.value = ''
  try {
    await nav.loadMenus()
    if (!nav.layoutLoaded || nav.layoutError) throw new Error(nav.layoutError || '导航尚未读取完成')
    readDraft(nav.currentLayout())
    markBaseline()
    previewing.value = false
    if (nav.pendingLayout) {
      readDraft(nav.pendingLayout)
      menuError.value = nav.menuMutationError
    }
  } catch (error) {
    menuError.value = error instanceof Error ? error.message : '菜单配置读取失败'
  } finally {
    menuLoading.value = false
  }
}
async function saveMenuSettings() {
  if (!menuDirty.value || menuSaving.value) return
  menuSaving.value = true
  menuError.value = ''
  const before = nav.currentLayout()
  try {
    await nav.saveLayout(draftLayout(), draftRevision.value)
    previousLayout.value = before
    markBaseline()
    previewing.value = false
    toast('个人导航已保存并立即生效', 'ri-check-line')
  } catch (error) {
    menuError.value = error instanceof Error ? error.message : '导航未保存，草稿仍保留'
  } finally {
    menuSaving.value = false
  }
}
function previewRecommendation() {
  readDraft(recommendedLayout(draftLayout()))
  previewing.value = true
}
async function undoLayout() {
  if (!previousLayout.value) return
  readDraft(previousLayout.value)
  await saveMenuSettings()
}
function addGroup() {
  const label = newGroupName.value.trim()
  if (!label || draftGroups.value.length >= 30) return
  draftGroups.value.push({ id: 'custom:' + crypto.randomUUID(), label })
  newGroupName.value = ''
}
function moveGroup(id: string, direction: -1 | 1) {
  const index = draftGroups.value.findIndex((group) => group.id === id)
  const target = index + direction
  if (id === 'ROOT' || target < 1 || target >= draftGroups.value.length) return
  const [group] = draftGroups.value.splice(index, 1)
  if (group) draftGroups.value.splice(target, 0, group)
}
function removeGroup(id: string) {
  if (id === 'ROOT' || menuItems.value.some((item) => item.group === id)) return
  draftGroups.value = draftGroups.value.filter((group) => group.id !== id)
}
async function reloadDraft() {
  if (
    (menuDirty.value || nav.pendingLayout) &&
    !window.confirm('重新读取会放弃当前未保存的导航草稿，继续吗？')
  )
    return
  await nav.discardLayoutDraft()
  await loadMenuSettings()
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

function persistDraft(): boolean {
  if (!draftReady.value || !draftOwner || draftOwner !== session.userId) return false
  try {
    if (!menuDirty.value && !qlDirty.value) {
      if (unreadDraft) return true
      localStorage.removeItem(draftKey)
      localDraftStatus.value = ''
    } else {
      localStorage.setItem(draftKey, JSON.stringify({
        revision: draftRevision.value,
        layout: menuDirty.value ? draftLayout() : null,
        links: qlDirty.value ? qlEditing.value : null,
      }))
      localDraftStatus.value = '未保存到账号，草稿已留在本机；切换功能或刷新后可继续编辑。'
    }
    return true
  } catch {
    localDraftStatus.value = '本机草稿保存失败，请先保存到账号再离开。'
    return false
  }
}

function restoreDraft() {
  if (!draftOwner || draftOwner !== session.userId) return
  try {
    const raw = localStorage.getItem(draftKey)
    if (!raw) return
    const draft = JSON.parse(raw)
    if (draft.layout && typeof draft.revision === 'string') {
      readDraft(validateNavigationLayout(draft.layout))
      // 保留编辑开始时的版本，不能用最新版本为旧草稿绕过并发校验。
      draftRevision.value = draft.revision
    }
    if (Array.isArray(draft.links) && draft.links.every((link: QuickLink) =>
      link && ['id', 'label', 'subtitle', 'icon', 'url'].every(key =>
        typeof link[key as keyof QuickLink] === 'string') &&
      ['TAB', 'NEW_PAGE'].includes(link.openMode) && Array.isArray(link.keywords) &&
      link.keywords.every(keyword => typeof keyword === 'string'))) {
      qlEditing.value = cloneQuickLinks(draft.links)
    }
    localDraftStatus.value = '已恢复本机未保存的草稿；保存后才会应用到账号。'
  } catch {
    unreadDraft = true
    localDraftStatus.value = '本机草稿无法读取，原记录仍保留；账号布局已正常读取。'
  }
}

function confirmLeave(): boolean {
  return !(menuDirty.value || qlDirty.value) || persistDraft() ||
    window.confirm('菜单或快捷链接还有未保存的修改，确认放弃吗？')
}

function beforeUnload(event: BeforeUnloadEvent) {
  if ((menuDirty.value || qlDirty.value) && !persistDraft()) {
    event.preventDefault()
    event.returnValue = ''
  }
}

watch([menuItems, draftGroups, draftLocked, qlEditing, builtinBaseline, externalBaseline,
  groupsBaseline, lockedBaseline, qlBaseline], () => {
  if (!menuLoading.value && !qlLoading.value && !menuSaving.value && !qlSaving.value) persistDraft()
}, { deep: true })
watch([menuSaving, qlSaving], () => {
  if (!menuSaving.value && !qlSaving.value) persistDraft()
})

defineExpose({ confirmLeave })

onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload)
  await Promise.all([loadMenuSettings(), loadQuickLinkSettings()])
  if (!nav.layoutLoaded || nav.layoutError || quickLinks.loadError.value) return
  restoreDraft()
  draftReady.value = true
})
onUnmounted(() => {
  persistDraft()
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <div class="settings-heading">
    <div>
      <h1>菜单与快捷链接</h1>
      <p>菜单布局和快捷链接只影响你的账号。左侧、顶部、底部与右侧工作区页签继续保留。</p>
    </div>
  </div>

  <p v-if="localDraftStatus" class="status" role="status">{{ localDraftStatus }}</p>
  <div class="navigation-settings-grid">
    <section class="settings-card menu-card">
      <div class="card-head">
        <div>
          <h2>我的导航布局</h2>
          <p>
            按真实导航分组排列；拖拽可跨组移动，也可选择在侧栏底部显示为图标或在顶部显示为按钮。
          </p>
        </div>
        <button
          type="button"
          class="primary"
          :disabled="menuLoading || menuSaving || !menuDirty"
          @click="saveMenuSettings"
        >
          {{ menuSaving ? '保存中…' : menuDirty ? '保存菜单' : '已保存' }}
        </button>
      </div>

      <fieldset class="menu-editor" :disabled="menuSaving || menuLoading">
      <div class="layout-tools">
        <input
          v-model="menuQuery"
          type="search"
          aria-label="查找要调整的菜单"
          placeholder="查找要调整的菜单"
        />
        <button type="button" :disabled="menuLoading || menuSaving" @click="previewRecommendation">
          预览推荐分类
        </button>
        <button type="button" :disabled="!previousLayout || menuSaving" @click="undoLayout">
          撤销上次保存
        </button>
        <button type="button" :disabled="menuSaving" @click="reloadDraft">重新读取</button>
        <label><input v-model="draftLocked" type="checkbox" />锁定日常拖拽（仍可在此编辑）</label>
      </div>
      <p v-if="previewing" class="status" role="status">
        推荐分类预览中：仅调整左侧菜单归属，保存后生效；顶部、底部、名称与显隐均保留。
      </p>
      <div class="layout-tools">
        <input
          v-model="newGroupName"
          maxlength="24"
          aria-label="新分组名称"
          placeholder="新分组名称"
          @keydown.enter.prevent="addGroup"
        />
        <button
          type="button"
          :disabled="!newGroupName.trim() || draftGroups.length >= 30"
          @click="addGroup"
        >
          添加分组
        </button>
      </div>
      <div v-if="menuLoading" class="status">
        <i class="ri-loader-4-line spin"></i>正在读取菜单…
      </div>
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
          <header>
            <strong v-if="group.code === 'ROOT'">顶层</strong
            ><input
              v-else
              v-model="draftGroups.find((g) => g.id === group.code)!.label"
              maxlength="24"
              :aria-label="'分组名称：' + group.label"
            /><span>{{ group.items.length }} 项</span
            ><template v-if="group.code !== 'ROOT'"
              ><button
                type="button"
                :disabled="draftGroups.findIndex((g) => g.id === group.code) <= 1"
                :aria-label="group.label + '分组上移'"
                @click="moveGroup(group.code, -1)"
              >
                ↑</button
              ><button
                type="button"
                :disabled="
                  draftGroups.findIndex((g) => g.id === group.code) === draftGroups.length - 1
                "
                :aria-label="group.label + '分组下移'"
                @click="moveGroup(group.code, 1)"
              >
                ↓
              </button></template
            ><button
              v-if="group.code !== 'ROOT'"
              type="button"
              :disabled="menuItems.some((item) => item.group === group.code)"
              title="先移出组内所有菜单再删除分组"
              @click="removeGroup(group.code)"
            >
              删除空分组
            </button>
          </header>
          <div v-if="!group.items.length" class="empty-drop">
            {{
              menuQuery.trim() ? '此分组没有匹配菜单' : '拖到这里即可移动到“' + group.label + '”'
            }}
          </div>
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
            >
              <i class="ri-draggable"></i>
            </button>
            <IconPicker v-model="item.icon" />
            <div class="menu-fields">
              <label><span>名称</span><input v-model.trim="item.label" maxlength="24" /></label>
              <label>
                <span>所属分组</span>
                <select v-model="item.group" aria-label="所属分组" @change="changeMenuGroup()">
                  <option v-for="option in GROUPS" :key="option.code" :value="option.code">
                    {{ option.label }}
                  </option>
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
                <select
                  v-model="item.placement"
                  aria-label="展示位置"
                  @change="resequenceMenuGroups"
                >
                  <option value="MENU">分组菜单</option>
                  <option value="DOCK">底部图标</option>
                  <option value="TOP">顶部按钮</option>
                </select>
              </label>
            </div>
            <div class="menu-meta">
              <span>{{ item.source === 'external' ? '已注册应用' : item.id }}</span>
              <div class="row-actions">
                <button
                  type="button"
                  :disabled="!canMoveMenu(item, -1)"
                  title="上移"
                  @click="moveMenu(item, -1)"
                >
                  <i class="ri-arrow-up-s-line"></i>
                </button>
                <button
                  type="button"
                  :disabled="!canMoveMenu(item, 1)"
                  title="下移"
                  @click="moveMenu(item, 1)"
                >
                  <i class="ri-arrow-down-s-line"></i>
                </button>
                <button
                  type="button"
                  class="visibility"
                  :class="{ on: item.visible }"
                  @click="toggleMenuVisibility(item)"
                >
                  <i :class="item.visible ? 'ri-eye-line' : 'ri-eye-off-line'"></i
                  >{{ item.visible ? '显示' : '隐藏' }}
                </button>
              </div>
            </div>
          </article>
        </section>
      </div>
      <div v-if="menuError && menuItems.length" class="status error" role="alert">
        {{ menuError }}；未保存草稿仍保留。版本冲突时请重新读取后调整。
      </div>
      </fieldset>
      <p class="footnote">
        隐藏菜单仍可从“全部功能”打开；分组名称和位置属于个人偏好。应用地址与授权仍由系统管理。
      </p>
    </section>

    <section class="settings-card links-card">
      <div class="card-head">
        <div>
          <h2>左下角快捷链接</h2>
          <p>拖拽或用上下按钮排序；“WAO 页签”会在站内嵌入目标页面。</p>
        </div>
        <button
          type="button"
          class="primary"
          :disabled="qlLoading || qlSaving || !qlDirty"
          @click="saveQuickLinks"
        >
          {{ qlSaving ? '保存中…' : qlDirty ? '保存链接' : '已保存' }}
        </button>
      </div>

      <div v-if="qlLoading" class="status">
        <i class="ri-loader-4-line spin"></i>正在读取快捷链接…
      </div>
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
          >
            <i class="ri-draggable"></i>
          </button>
          <IconPicker v-model="link.icon" />
          <div class="quick-link-fields">
            <label
              ><span>名称</span><input v-model.trim="link.label" placeholder="例如 Jenkins"
            /></label>
            <label
              ><span>地址</span><input v-model.trim="link.url" placeholder="/path 或 https://…"
            /></label>
            <label
              ><span>描述</span><input v-model.trim="link.subtitle" placeholder="可选"
            /></label>
            <label class="open-mode">
              <span>打开方式</span>
              <select v-model="link.openMode">
                <option value="TAB">WAO 页签</option>
                <option value="NEW_PAGE">新页面</option>
              </select>
            </label>
          </div>
          <div class="row-actions quick-actions">
            <button
              type="button"
              :disabled="index === 0"
              title="上移"
              @click="moveQuickLink(index, -1)"
            >
              <i class="ri-arrow-up-s-line"></i>
            </button>
            <button
              type="button"
              :disabled="index === qlEditing.length - 1"
              title="下移"
              @click="moveQuickLink(index, 1)"
            >
              <i class="ri-arrow-down-s-line"></i>
            </button>
            <button type="button" class="remove" title="删除" @click="qlEditing.splice(index, 1)">
              <i class="ri-delete-bin-line"></i>
            </button>
          </div>
        </article>
      </div>

      <div v-if="qlError" class="status error" role="alert">
        {{ qlError }}；草稿仍保留，可修改后重试。
      </div>
      <div class="link-actions">
        <button type="button" @click="addQuickLink"><i class="ri-add-line"></i>添加链接</button>
        <button type="button" @click="resetQuickLinks">
          <i class="ri-history-line"></i>恢复默认
        </button>
      </div>
      <p class="footnote">
        部分网站禁止被 iframe 嵌入；遇到目标站限制时，把该链接改为“新页面”即可。
      </p>
    </section>
  </div>
</template>

<style scoped>
.menu-editor { min-width: 0; border: 0; margin: 0; padding: 0; }

.layout-tools {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  font-size: 12px;
}
.layout-tools input[type='search'] {
  flex: 1;
  min-width: 170px;
}
.layout-tools button,
.layout-tools input:not([type='checkbox']),
.menu-group header input,
.menu-group header button {
  padding: 7px 9px;
  border: 1px solid var(--wao-line);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
.layout-tools button:disabled,
.menu-group header button:disabled {
  opacity: 0.45;
}
.menu-group header input {
  width: 150px;
  min-width: 0;
}

.settings-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}
.settings-heading h1 {
  margin: 0;
  font-size: 18px;
}
.settings-heading p,
.card-head p,
.footnote {
  margin: 5px 0 0;
  color: var(--wao-text-weak);
  font-size: 12px;
  line-height: 1.55;
}
.navigation-settings-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 580px), 1fr));
  gap: 14px;
  align-items: start;
  margin-top: 14px;
}
.settings-card {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
}
.card-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.card-head > div {
  flex: 1;
  min-width: 0;
}
.card-head h2 {
  margin: 0;
  font-size: 14px;
}
.primary {
  min-height: 34px;
  padding: 0 13px;
  border: 0;
  border-radius: 8px;
  background: var(--wao-primary);
  color: #fff;
  cursor: pointer;
  white-space: nowrap;
}
.primary:disabled {
  opacity: 0.55;
  cursor: default;
}
.status {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 12px;
  padding: 9px 10px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  font-size: 12px;
}
.status span {
  flex: 1;
}
.status button {
  border: 0;
  background: none;
  color: inherit;
  cursor: pointer;
}
.status.error {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.menu-groups {
  display: grid;
  gap: 12px;
  margin-top: 14px;
}
.menu-group {
  min-height: 54px;
  border: 1px solid var(--wao-divider);
  border-radius: 10px;
  background: var(--wao-bg-sub);
  overflow: hidden;
}
.menu-group > header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 9px 11px;
  color: var(--wao-text-2);
  font-size: 12px;
}
.menu-group > header span {
  white-space: nowrap;
  color: var(--wao-text-faint);
  font-size: 10.5px;
}
.empty-drop {
  margin: 0 8px 8px;
  padding: 12px;
  border: 1px dashed var(--wao-line-strong);
  border-radius: 7px;
  color: var(--wao-text-faint);
  text-align: center;
  font-size: 11px;
}
.menu-row,
.quick-link-row {
  display: grid;
  align-items: center;
  gap: 8px;
  padding: 10px;
  border-top: 1px solid var(--wao-divider);
  background: var(--wao-bg-card);
}
.menu-row {
  grid-template-columns: 24px 30px minmax(0, 1fr) auto;
}
.menu-row.hidden {
  opacity: 0.68;
}
.menu-row.dragging {
  opacity: 0.4;
}
.drag-handle {
  width: 24px;
  height: 30px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--wao-text-faint);
  cursor: grab;
}
.drag-handle:hover {
  background: var(--wao-bg-hover);
  color: var(--wao-text-2);
}
.menu-icon {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
}
.menu-fields,
.quick-link-fields {
  display: grid;
  gap: 7px;
}
.menu-fields {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.menu-fields label,
.quick-link-fields label {
  display: grid;
  gap: 4px;
  min-width: 0;
  color: var(--wao-text-weak);
  font-size: 10.5px;
}
.menu-fields input,
.menu-fields select,
.quick-link-fields input,
.quick-link-fields select {
  min-width: 0;
  height: 32px;
  box-sizing: border-box;
  padding: 0 8px;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
}
.menu-fields input:focus,
.menu-fields select:focus,
.quick-link-fields input:focus,
.quick-link-fields select:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 2px var(--wao-primary-soft);
}
.menu-meta {
  display: grid;
  justify-items: end;
  gap: 7px;
  min-width: 112px;
}
.menu-meta > span {
  max-width: 150px;
  overflow: hidden;
  color: var(--wao-text-faint);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 3px;
}
.row-actions button,
.link-actions button {
  min-height: 28px;
  padding: 0 7px;
  border: 1px solid var(--wao-line);
  border-radius: 6px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  cursor: pointer;
}
.row-actions button:disabled {
  opacity: 0.3;
  cursor: default;
}
.row-actions .visibility {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 62px;
  justify-content: center;
}
.row-actions .visibility.on {
  color: var(--wao-primary-text);
  background: var(--wao-primary-tint);
}
.quick-link-list {
  margin-top: 14px;
  border: 1px solid var(--wao-divider);
  border-radius: 10px;
  overflow: hidden;
}
.quick-link-row {
  grid-template-columns: 24px 30px minmax(0, 1fr) auto;
}
.quick-link-row:first-child {
  border-top: 0;
}
.quick-link-fields {
  grid-template-columns: 1fr 1fr;
}
.quick-link-fields .open-mode {
  grid-column: 1 / -1;
}
.quick-actions {
  flex-direction: column;
}
.row-actions .remove {
  color: var(--wao-danger);
}
.link-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.link-actions button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.footnote {
  padding-top: 10px;
}
@media (max-width: 1180px) {
  .navigation-settings-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 760px) {
  .settings-heading p {
    display: block;
  }
  .card-head {
    flex-direction: column;
  }
  .primary {
    width: 100%;
    justify-content: center;
  }
  .menu-row,
  .quick-link-row {
    grid-template-columns: 24px 30px minmax(0, 1fr);
  }
  .menu-fields,
  .quick-link-fields {
    grid-template-columns: 1fr 1fr;
  }
  .menu-fields {
    grid-column: 1 / -1;
  }
  .menu-fields input,
  .menu-fields select {
    min-height: 36px;
    font-size: 12px;
  }
  .menu-meta,
  .quick-actions {
    grid-column: 1 / -1;
    justify-items: stretch;
    flex-direction: row;
  }
  .menu-meta {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
}
</style>
