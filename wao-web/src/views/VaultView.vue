<script setup lang="ts">
import ConfirmActionDialog from '@/components/ui/ConfirmActionDialog.vue'
import { useConfirmAction } from '@/composables/confirmAction'
const { confirmation, confirmAction, dismissConfirmation } = useConfirmAction()
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { computed, nextTick, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { copyText } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import { useIsMobile } from '@/composables/viewport'
import { useClientPagination } from '@/composables/clientPagination'
import WaoPagination from '@/components/ui/WaoPagination.vue'
import WaoEmptyState from '@/components/ui/WaoEmptyState.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import type { VaultItem } from '@/types'

const data = useDataStore()
const isMobile = useIsMobile()
const groupId = ref('all')
const selId = ref<string | null>(null)
const newGroupOpen = ref(false)
const newGroupName = ref('')
const managingGroups = ref(false)
const renamingGroupId = ref<string | null>(null)
const draggedGroupId = ref<string | null>(null)

interface FlatItem extends VaultItem {
  groupId: string
  groupName: string
}

const items = computed<FlatItem[]>(() =>
  data.vault.flatMap((g) => g.items.map((it) => ({ ...it, groupId: g.id, groupName: g.group }))),
)
const query = ref('')
const filtered = computed(() => items.value.filter((item) =>
  (groupId.value === 'all' || item.groupId === groupId.value) &&
  `${item.name} ${item.user} ${item.site}`.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase()),
))
const { page: vaultPage, pageCount: vaultPageCount, visible: visibleItems } = useClientPagination(filtered, [groupId, query])
watch(vaultPage, () => { selId.value = null })
watch(query, () => { selId.value = null })
const sel = computed(() => visibleItems.value.find((i) => i.id === selId.value) ?? visibleItems.value[0] ?? null)

/**
 * 手机上详情是一页盖屏的抽屉，只有显式点过条目才打开。
 *
 * <p>桌面三栏并排，详情默认落在第一条上；手机屏幕放不下三栏，详情栏被前两栏挤出可视区，
 * 「查看」「复制」两个按钮就等于不存在了。所以手机改成「列表 → 点条目进详情页 → 返回」，
 * 并且不沿用桌面「默认选中第一条」的行为，免得一进密码库就盖着一个没人点开的详情页。
 */
const detailOpen = computed(() => !isMobile.value || (selId.value != null && sel.value != null))

/**
 * 默认分组按后端给的 isDefault 认，不按名字认：名字是用户的，改完名它还是同一个默认分组（见 ADR-036）。
 */
const groups = computed(() =>
  [
    { id: 'all', label: '全部', isDefault: false },
    ...data.vault.map((g) => ({ id: g.id, label: g.group, isDefault: !!g.isDefault })),
  ].map((g) => ({
    ...g,
    count: g.id === 'all' ? items.value.length : items.value.filter((i) => i.groupId === g.id).length,
    on: groupId.value === g.id,
    fixed: g.isDefault,
  })),
)
/** 新建条目时的默认落点：自己的默认分组，没有就用第一个分组。 */
const defaultGroupId = computed(
  () => data.vault.find((g) => g.isDefault)?.id ?? data.vault[0]?.id ?? '',
)
/** 默认落点的现名，用于新建条目弹窗的提示文案——默认分组改过名时提示也要跟着改。 */
const defaultGroupName = computed(
  () => data.vault.find((g) => g.id === defaultGroupId.value)?.group ?? '未分组',
)

const retrying = ref(false)
async function retryLoad() {
  retrying.value = true
  const ok = await data.reloadVault()
  retrying.value = false
  toast(ok ? '密码库已恢复' : '还是读不出来，稍后再试')
}

function pickGroup(id: string) {
  groupId.value = id
  selId.value = null
}
function commitGroupName(id: string, event: Event) {
  const name = (event.target as HTMLInputElement).value.trim()
  if (name) data.renameVaultGroup(id, name)
  renamingGroupId.value = null
}
async function removeGroup(id: string) {
  const group = data.vault.find((g) => g.id === id)
  if (!group || group.isDefault) return
  confirmAction({ title: '删除密码库分组', description: `确认删除「${group.group}」及其中 ${group.items.length} 个条目？此操作不可恢复。`, run: async () => {
    await data.deleteVaultGroup(id)
    if (groupId.value === id) groupId.value = 'all'
  } })
}
async function dropGroup(targetId: string) {
  const sourceId = draggedGroupId.value
  draggedGroupId.value = null
  if (!sourceId || sourceId === targetId) return
  const ids = data.vault.map((g) => g.id)
  const sourceIndex = ids.indexOf(sourceId)
  const targetIndex = ids.indexOf(targetId)
  if (sourceIndex < 0 || targetIndex < 0) return
  ids.splice(sourceIndex, 1)
  ids.splice(targetIndex, 0, sourceId)
  await data.reorderVaultGroups(ids)
}
const savingGroup = ref(false), groupError = ref('')
async function createGroup() {
  if (!newGroupName.value.trim() || savingGroup.value) return
  savingGroup.value = true; groupError.value = ''
  try {
  const g = await data.addVaultGroup(newGroupName.value.trim())
  newGroupOpen.value = false
  newGroupName.value = ''
  groupId.value = g.id
  } catch { groupError.value = '保存分组失败，名称已保留，请重试。' }
  finally { savingGroup.value = false }
}

// ---- 查看 / 复制密码 ----
const revealed = ref<string | null>(null)
const revealing = ref(false)
watch(() => sel.value?.id, () => (revealed.value = null))
async function reveal() {
  if (!sel.value || revealing.value) return
  if (!sel.value.hasSecret) {
    toast('该条目未设置密码', 'ri-lock-line')
    return
  }
  const requestedId = sel.value.id
  revealing.value = true
  try {
    const secret = await data.revealVaultItem(requestedId)
    if (sel.value?.id !== requestedId) return
    revealed.value = secret
    if (revealed.value == null) toast('未能获取密码', 'ri-error-warning-line')
  } finally {
    revealing.value = false
  }
}
async function copyPassword() {
  if (!sel.value) return
  const requestedId = sel.value.id
  if (revealed.value == null) await reveal()
  if (sel.value?.id === requestedId && revealed.value != null) copyText(revealed.value, '已复制密码')
}
function copyUser() {
  if (sel.value) copyText(sel.value.user, '已复制用户名')
}

// ---- 新建 / 编辑条目 ----
const itemOpen = ref(false)
const editingId = ref<string | null>(null)
const formGroupId = ref('')
const formName = ref('')
const formUser = ref('')
const formSite = ref('')
const formNote = ref('')
const formSecret = ref('')
const itemSnapshot = () => JSON.stringify([formGroupId.value, formName.value, formUser.value, formSite.value, formNote.value, formSecret.value])
const itemOriginal = ref('')

const nameInput = ref<HTMLInputElement | null>(null)
const savingItem = ref(false)
const itemError = ref('')

async function openNewItem() {
  editingId.value = null
  formGroupId.value = groupId.value === 'all' ? defaultGroupId.value : groupId.value
  formName.value = ''
  formUser.value = ''
  formSite.value = ''
  formNote.value = ''
  formSecret.value = ''
  itemError.value = ''
  itemOriginal.value = itemSnapshot()
  itemOpen.value = true
  await nextTick()
  nameInput.value?.focus()
}
function openEditItem() {
  if (!sel.value) return
  itemError.value = ''
  editingId.value = sel.value.id
  formGroupId.value = sel.value.groupId
  formName.value = sel.value.name
  formUser.value = sel.value.user
  formSite.value = sel.value.site
  formNote.value = sel.value.note
  formSecret.value = ''
  itemOriginal.value = itemSnapshot()
  itemOpen.value = true
}
async function saveItem() {
  if (!formName.value.trim() || savingItem.value) return
  savingItem.value = true
  itemError.value = ''
  try {
    if (editingId.value) {
      const updated = await data.updateVaultItem(editingId.value, {
        name: formName.value.trim(), user: formUser.value.trim(), site: formSite.value.trim(),
        note: formNote.value.trim(), secret: formSecret.value || undefined, groupId: formGroupId.value,
      })
      if (!updated) throw new Error('保存失败')
      groupId.value = formGroupId.value
      query.value = ''
      // Filtering and pagination watchers clear the selection on the next tick.
      // Select the edited item only after those resets have finished.
      await nextTick()
      selId.value = editingId.value
      revealed.value = null
    } else {
      const item = await data.addVaultItem({
        groupId: formGroupId.value, name: formName.value.trim(), user: formUser.value.trim(),
        site: formSite.value.trim(), note: formNote.value.trim(), secret: formSecret.value || undefined,
      })
      if (!item) throw new Error('保存失败，请重试')
      groupId.value = formGroupId.value
      query.value = ''
      await nextTick()
      selId.value = item.id
    }
    itemOpen.value = false
    formSecret.value = ''
  } catch {
    itemError.value = '保存失败，输入已保留，请重试。'
  } finally { savingItem.value = false }
}
async function deleteItem() {
  if (!sel.value) return
  const id = sel.value.id
  confirmAction({ title: '删除密码库条目', description: `确认删除「${sel.value.name}」？此操作不可恢复。`, run: async () => {
    await data.deleteVaultItem(id)
    if (selId.value === id) selId.value = null
  } })
}
async function closeItem() {
  if (savingItem.value) return
  if (itemSnapshot() !== itemOriginal.value) {
    if (!formName.value.trim()) { itemError.value = '请填写名称后保存，输入已保留。'; return }
    await saveItem()
  } else itemOpen.value = false
}
async function closeNewGroup() {
  if (savingGroup.value) return
  if (newGroupName.value.trim()) await createGroup()
  else newGroupOpen.value = false
}
useOverlayDismiss(() => itemOpen.value, closeItem)
useOverlayDismiss(() => newGroupOpen.value, closeNewGroup)
</script>

<template>
  <ConfirmActionDialog v-if="confirmation" :action="confirmation" @close="dismissConfirmation" />
  <div class="vault wao-page-frame">
    <div class="vtop">
      <div class="vtitle">密码库</div>
      <button class="group-manage" :class="{ on: managingGroups }" @click="managingGroups = !managingGroups">
        <i class="ri-settings-4-line"></i>{{ managingGroups ? '完成分组管理' : '分组管理' }}
      </button>
      <div class="spacer"></div>
      <input v-model="query" class="vault-search f-input" type="search" aria-label="搜索密码条目" placeholder="搜索名称、账号或网址" />
      <button class="btn-primary" @click="openNewItem"><i class="ri-add-line"></i>添加条目</button>
    </div>

    <div v-if="data.vaultError" class="banner error">
      <i class="ri-error-warning-line"></i>
      <span>{{ data.vault.length ? '密码库读取失败，以下为上次读取结果。请重试获取最新内容。' : '密码库读取失败，请检查连接后重试。' }}</span>
      <button class="retry" :disabled="retrying" @click="retryLoad">{{ retrying ? '重试中…' : '重试' }}</button>
    </div>

    <div class="cols">
      <div class="group-col" :class="{ managing: managingGroups }">
        <div class="col-label">分组</div>
        <template v-for="g in groups" :key="g.id">
          <button v-if="g.id === 'all' || !managingGroups" class="group-btn" :class="{ on: g.on }" @click="pickGroup(g.id)">
            <span class="gb-label">{{ g.label }}</span><span class="gb-count wao-tabular">{{ g.count }}</span>
          </button>
          <div
            v-else
            class="group-edit-row"
            draggable="true"
            @dragstart="draggedGroupId = g.id"
            @dragover.prevent
            @drop.prevent="dropGroup(g.id)"
          >
            <i class="ri-draggable drag"></i>
            <input
              v-if="renamingGroupId === g.id"
              :value="g.label"
              class="group-name-input"
              autofocus
              @keydown.enter="commitGroupName(g.id, $event)"
              @blur="commitGroupName(g.id, $event)"
            />
            <span v-else class="gb-label">{{ g.label }}</span>
            <button class="group-action" title="重命名" @click="renamingGroupId = g.id"><i class="ri-edit-line"></i></button>
            <button class="group-action danger" :disabled="g.fixed" title="删除" @click="removeGroup(g.id)"><i class="ri-delete-bin-line"></i></button>
          </div>
        </template>
        <button class="group-add" @click="newGroupOpen = true"><i class="ri-add-line"></i>新建分组</button>
      </div>

      <div class="item-col">
        <button v-for="it in visibleItems" :key="it.id" class="item-btn" :class="{ on: sel?.id === it.id }" @click="selId = it.id">
          <div class="item-icon"><i :class="it.icon"></i></div>
          <div class="item-main">
            <div class="item-name">{{ it.name }}</div>
            <div class="item-user">{{ it.user }}</div>
          </div>
          <i class="ri-arrow-right-s-line item-go"></i>
        </button>
        <WaoEmptyState v-if="!items.length && data.loadStates.vault === 'loading'" title="正在读取密码库…" icon="ri-loader-4-line" role="status" />
        <WaoEmptyState v-else-if="filtered.length === 0 && !data.vaultError" :title="query.trim() ? '没有匹配的条目' : '这个分组还没有条目'" :description="query.trim() ? '试试其他名称、账号或网址。' : '添加条目后，可以在这里查看和复制。'">
          <template #actions>
            <WaoButton v-if="query.trim()" @click="query = ''">清除搜索</WaoButton>
            <WaoButton v-else variant="primary" @click="openNewItem">添加第一个条目</WaoButton>
          </template>
        </WaoEmptyState>
        <WaoPagination v-if="vaultPageCount > 1" v-model:page="vaultPage" :page-count="vaultPageCount" :total="filtered.length" aria-label="密码条目查询结果分页" />
      </div>

      <div v-if="detailOpen" class="detail-col" :class="{ 'detail-page': isMobile }">
        <div v-if="isMobile" class="dp-head">
          <button class="dp-back" title="返回列表" @click="selId = null"><i class="ri-arrow-left-line"></i></button>
          <div class="dp-title">{{ sel?.name ?? '条目详情' }}</div>
        </div>
        <template v-if="sel">
          <div class="d-head">
            <div class="d-icon"><i :class="sel.icon"></i></div>
            <div>
              <div class="d-name">{{ sel.name }}</div>
              <div class="d-sub">{{ sel.groupName }} · 更新于 {{ sel.updated }}</div>
            </div>
            <div class="spacer"></div>
            <button class="f-icon" title="编辑" @click="openEditItem"><i class="ri-edit-line"></i></button>
            <button class="f-icon danger" title="删除" @click="deleteItem"><i class="ri-delete-bin-line"></i></button>
          </div>
          <div class="fields">
            <div class="field-row">
              <span class="f-key">用户名</span>
              <span class="f-val wao-mono">{{ sel.user }}</span>
              <button class="f-btn" @click="copyUser">复制</button>
            </div>
            <div class="field-row">
              <span class="f-key">密码</span>
              <span class="f-pass" :class="{ mono: revealed != null }">{{ revealed ?? '••••••••••' }}</span>
              <button class="f-icon" :title="revealed == null ? '查看' : '隐藏'" @click="revealed == null ? reveal() : (revealed = null)">
                <i :class="revealed == null ? 'ri-eye-off-line' : 'ri-eye-line'"></i>
              </button>
              <button class="f-btn" @click="copyPassword">复制</button>
            </div>
            <div class="field-row last">
              <span class="f-key">网址</span>
              <span class="f-site">{{ sel.site }}</span>
            </div>
          </div>
          <div v-if="sel.note" class="note"><i class="ri-sticky-note-line"></i>{{ sel.note }}</div>
        </template>
        <div v-else class="empty">选择左侧条目查看详情</div>
      </div>
    </div>

    <!-- 新建 / 编辑条目弹层 -->
    <template v-if="itemOpen">
      <div class="modal-mask" @click="closeItem()"></div>
      <div class="modal">
        <div class="modal-head">
          <div class="modal-title">{{ editingId ? '编辑条目' : '新建条目' }}</div>
          <div class="spacer"></div>
          <button class="icon-btn" @click="closeItem()"><i class="ri-close-line"></i></button>
        </div>
        <fieldset class="modal-body" :disabled="savingItem" style="border: 0; margin: 0; min-width: 0">
          <label class="f-label">分组 <span class="optional">点选分组；全部视图默认放入“{{ defaultGroupName }}”</span></label>
          <div class="group-choices" role="group" aria-label="条目分组"><button v-for="g in data.vault" :key="g.id" type="button" :aria-pressed="formGroupId === g.id" :class="{ on: formGroupId === g.id }" @click="formGroupId = g.id">{{ g.group }}</button></div>
          <label class="f-label">名称</label>
          <input ref="nameInput" v-model="formName" class="f-input" placeholder="例如「家庭 Wi-Fi」" />
          <label class="f-label">用户名</label>
          <input v-model="formUser" name="vault-user" autocomplete="off" class="f-input" placeholder="选填" />
          <label class="f-label">网址</label>
          <input v-model="formSite" name="vault-site" autocomplete="off" class="f-input" placeholder="选填" />
          <label class="f-label">密码</label>
          <input v-model="formSecret" type="password" name="vault-secret" autocomplete="new-password" class="f-input" :placeholder="editingId ? '留空则不修改' : '选填'" />
          <label class="f-label">备注</label>
          <textarea v-model="formNote" class="f-textarea" rows="2" placeholder="选填"></textarea>
        </fieldset>
        <div class="modal-foot">
          <div class="spacer"></div>
          <span v-if="itemError" role="alert">{{ itemError }}</span><button class="btn-primary" :disabled="!formName.trim() || savingItem" @click="saveItem">{{ savingItem ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </template>

    <!-- 新建分组弹层 -->
    <template v-if="newGroupOpen">
      <div class="modal-mask" @click="closeNewGroup"></div>
      <div class="modal narrow" role="dialog" aria-modal="true"><p v-if="groupError" role="alert">{{ groupError }}</p>
        <div class="modal-head">
          <div class="modal-title">新建分组</div>
          <div class="spacer"></div>
          <button class="icon-btn" @click="closeNewGroup"><i class="ri-close-line"></i></button>
        </div>
        <div class="modal-body">
          <input v-model="newGroupName" :disabled="savingGroup" class="f-input" placeholder="分组名称" @keydown.enter="createGroup" />
        </div>
        <div class="modal-foot">
          <div class="spacer"></div>
          <button class="btn-primary" :disabled="!newGroupName.trim() || savingGroup" @click="createGroup">创建</button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.vault { height: 100%; min-height: 0; display: flex; flex-direction: column; box-sizing: border-box; overflow: hidden; }
.vtop, .banner { flex-shrink: 0; }
.vault .cols { flex: 1; min-height: 0; }
.group-col, .item-col, .detail-col { min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }

.vault-search { max-width: 260px; }
.group-choices { display: flex; flex-wrap: wrap; gap: 8px; }
.group-choices button { border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text); padding: 7px 12px; cursor: pointer; }
.group-choices button.on { color: var(--wao-primary-text); border-color: var(--wao-primary); background: var(--wao-primary-soft); }
@media (max-width: 760px) { .vtop { flex-wrap: wrap; } .vault-search { max-width: none; flex: 1; min-width: 140px; } }
.vault {
  padding: 16px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.spacer {
  flex: 1;
}
.vtop {
  display: flex;
  align-items: center;
  gap: 10px;
}
.vtitle {
  font-size: var(--wao-font-title);
  font-weight: var(--wao-weight-heading);
  line-height: var(--wao-line-heading);
}
.group-manage {
  border: 0;
  background: transparent;
  font-size: 12px;
  color: var(--wao-text-weak);
  cursor: pointer;
  border-radius: 6px;
  padding: 5px 7px;
}
.group-manage:hover,
.group-manage.on {
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
}
.btn-ghost {
  height: 32px;
  padding: 0 13px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-ghost:hover {
  background: var(--wao-bg-hover);
}
.btn-ghost i {
  font-size: 14px;
}
.banner {
  margin-top: 12px;
  background: var(--wao-warning-bg);
  border: 1px solid #f0e3c4;
  border-radius: 12px;
  padding: 11px 16px;
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: #8a5b10;
  line-height: 1.7;
}
.banner i {
  font-size: 15px;
  flex-shrink: 0;
  margin-top: 2px;
}
.banner.error {
  background: var(--wao-danger-bg);
  border-color: #f3d2d2;
  color: #a4302e;
  align-items: center;
}
.banner.error .retry {
  margin-left: auto;
  flex-shrink: 0;
  border: 1px solid #e6b7b6;
  background: var(--wao-bg-card);
  color: #a4302e;
  border-radius: 8px;
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
}
.banner.error .retry:disabled {
  cursor: default;
  opacity: 0.6;
}

.cols {
  margin-top: 12px;
  display: flex;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  overflow: hidden;
  min-height: 420px;
}
.group-col {
  width: 158px;
  flex-shrink: 0;
  border-right: 1px solid var(--wao-divider);
  background: var(--wao-bg-sub);
  padding: 10px 8px;
}
.col-label {
  padding: 2px 8px 6px;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-weak);
  letter-spacing: 1.5px;
}
.group-btn {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  border: none;
  border-radius: 7px;
  padding: 7px 9px;
  cursor: pointer;
  background: transparent;
  color: var(--wao-text-2);
  font-size: 12.5px;
}
.group-btn:hover {
  background: var(--wao-bg-tab);
}
.group-btn.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-weight: 600;
}
.group-edit-row {
  min-height: 32px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 4px;
  border-radius: 7px;
}
.group-edit-row:hover { background: var(--wao-bg-tab); }
.drag { color: var(--wao-text-faint); cursor: grab; }
.group-name-input { min-width: 0; flex: 1; border: 1px solid var(--wao-line-control); border-radius: 5px; padding: 3px 5px; }
.group-action { width: 22px; height: 22px; border: 0; background: transparent; border-radius: 5px; cursor: pointer; color: var(--wao-text-weak); }
.group-action:hover { background: var(--wao-bg-card); color: var(--wao-text); }
.group-action.danger:hover { color: var(--wao-danger-strong); }
.group-action:disabled { opacity: .25; cursor: not-allowed; }
.optional { color: var(--wao-text-faint); font-weight: 400; }
.gb-label {
  flex: 1;
  text-align: left;
}
.gb-count {
  font-size: var(--wao-font-caption);
  color: var(--wao-text-faint);
}
.group-add {
  width: 100%;
  margin-top: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  border: 1px dashed #d3d6df;
  border-radius: 7px;
  padding: 6px 9px;
  cursor: pointer;
  background: none;
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
}
.group-add:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary-text);
}

.item-col {
  width: 252px;
  flex-shrink: 0;
  border-right: 1px solid var(--wao-divider);
  overflow-y: auto;
  padding: 6px;
}
.item-btn {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  border: none;
  border-radius: 9px;
  padding: 9px 10px;
  cursor: pointer;
  background: transparent;
  text-align: left;
}
.item-btn:hover {
  background: var(--wao-bg-hover);
}
.item-btn.on {
  background: var(--wao-primary-soft);
}
.item-icon {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: var(--wao-bg-tab);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.item-icon i {
  font-size: 14px;
  color: var(--wao-text-3);
}
.item-main {
  flex: 1;
  min-width: 0;
}
.item-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.item-user {
  margin-top: 1px;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-weak);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.detail-col {
  flex: 1;
  min-width: 0;
  padding: 20px 24px;
}
.d-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.d-icon {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  background: var(--wao-bg-tab);
  display: flex;
  align-items: center;
  justify-content: center;
}
.d-icon i {
  font-size: 19px;
  color: var(--wao-text-3);
}
.d-name {
  font-size: 16px;
  font-weight: 700;
}
.d-sub {
  margin-top: 2px;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-weak);
}
.fields {
  margin-top: 16px;
  border: 1px solid var(--wao-divider);
  border-radius: 10px;
  overflow: hidden;
}
.field-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--wao-divider-2);
}
.field-row.last {
  border-bottom: none;
}
.f-key {
  width: 56px;
  font-size: 12px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.f-val {
  flex: 1;
  font-size: 13px;
  color: var(--wao-text);
}
.f-pass {
  flex: 1;
  font-size: 14px;
  color: var(--wao-text-faint);
  letter-spacing: 2px;
}
.f-pass.mono {
  font-family: var(--wao-font-mono, monospace);
  letter-spacing: 0.5px;
  color: var(--wao-text);
}
.f-site {
  flex: 1;
  font-size: 13px;
  color: var(--wao-primary-text);
}
.f-btn {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 7px;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-2);
  cursor: pointer;
}
.f-btn:hover {
  background: var(--wao-bg-hover);
}
.f-icon {
  width: 26px;
  height: 26px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-weak);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.f-icon:hover {
  background: var(--wao-bg-hover);
}
.f-icon i {
  font-size: 13px;
}
.f-icon.danger {
  color: #bc4141;
  border-color: #f0c9c9;
}
.f-icon.danger:hover {
  background: var(--wao-danger-bg);
}
.empty {
  padding: 40px 12px;
  text-align: center;
  color: var(--wao-text-faint);
  font-size: 12.5px;
}
.note {
  margin-top: 12px;
  font-size: 12px;
  color: var(--wao-text-weak);
  line-height: 1.7;
}
.note i {
  font-size: 13px;
  margin-right: 5px;
}

.btn-primary {
  height: 32px;
  padding: 0 13px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
}
.btn-primary:hover {
  background: var(--wao-primary-hover);
}
.btn-primary:disabled {
  background: var(--wao-line-strong);
  cursor: not-allowed;
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 100;
  animation: waoFade 0.15s ease;
}
.modal {
  position: fixed;
  top: 9vh;
  left: 0;
  right: 0;
  margin-inline: auto;
  width: min(440px, calc(100vw - 40px));
  max-height: 80vh;
  overflow-y: auto;
  background: var(--wao-bg-card);
  border-radius: 14px;
  z-index: 101;
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop 0.16s ease;
}
.modal.narrow {
  width: min(320px, calc(100vw - 40px));
}
.modal-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--wao-divider);
}
.modal-title {
  font-size: 15px;
  font-weight: 600;
}
.icon-btn {
  width: 30px;
  height: 30px;
  border: none;
  background: none;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
}
.icon-btn:hover {
  background: var(--wao-bg-hover-2);
}
.modal-body {
  padding: 16px 20px;
}
.f-label {
  display: block;
  font-size: 12px;
  color: var(--wao-text-weak);
  margin: 12px 0 6px;
}
.f-label:first-child {
  margin-top: 0;
}
.f-input,
.f-textarea {
  width: 100%;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  color: var(--wao-text);
  outline: none;
  background: var(--wao-bg-card);
}
.f-input:focus,
.f-textarea:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.f-textarea {
  resize: vertical;
}
.modal-foot {
  display: flex;
  align-items: center;
  padding: 14px 20px;
  border-top: 1px solid var(--wao-divider);
}

.item-go {
  display: none;
  flex-shrink: 0;
  font-size: 18px;
  color: var(--wao-text-faint);
}

/* 手机上的条目详情：盖住整屏的一页，自带返回顶栏 */
.detail-page {
  position: fixed;
  inset: 0;
  z-index: 82;
  background: var(--wao-bg-card);
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0 16px calc(24px + env(safe-area-inset-bottom));
  animation: waoVaultPage 0.16s ease;
}
@keyframes waoVaultPage {
  from {
    transform: translateX(14px);
    opacity: 0.4;
  }
}
.dp-head {
  position: sticky;
  top: 0;
  z-index: 1;
  margin: 0 -16px 4px;
  padding: 0 8px;
  height: 52px;
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--wao-bg-card);
  border-bottom: 1px solid var(--wao-line);
}
.dp-back {
  width: 40px;
  height: 40px;
  border: none;
  background: none;
  border-radius: 8px;
  cursor: pointer;
  color: var(--wao-text-2);
  flex-shrink: 0;
}
.dp-back i {
  font-size: 20px;
}
.dp-title {
  min-width: 0;
  font-size: 14.5px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@media (max-width: 760px) {
  .vault { padding: 16px 12px 28px; }
  .modal { top: 12px; width: calc(100vw - 24px); max-height: calc(100dvh - 24px); }

  /* 三栏并排在手机上放不下：分组变成横向胶囊，条目占满整宽，详情走盖屏页 */
  .cols { flex-direction: column; min-height: 0; }
  .group-col {
    width: auto;
    padding: 8px;
    border-right: 0;
    border-bottom: 1px solid var(--wao-divider);
  }
  .group-col:not(.managing) {
    display: flex;
    gap: 6px;
    overflow-x: auto;
    scrollbar-width: none;
  }
  .group-col:not(.managing)::-webkit-scrollbar { display: none; }
  .group-col:not(.managing) .col-label { display: none; }
  .group-col:not(.managing) .group-btn,
  .group-col:not(.managing) .group-add {
    width: auto;
    flex: 0 0 auto;
    min-height: 34px;
    margin-top: 0;
    white-space: nowrap;
  }
  .group-edit-row { min-height: 40px; }
  .group-action { width: 34px; height: 34px; }

  .item-col { width: auto; border-right: 0; padding: 6px 6px 10px; }
  .item-btn { min-height: 56px; }
  .item-go { display: block; }

  /* 名字已经写在顶栏上，滚动时也一直看得见，正文里不再重复一遍 */
  .d-head { padding-top: 8px; }
  .detail-page .d-name { display: none; }
  .detail-page .d-sub { margin-top: 0; }

  /* 键名单独一行，值可以换行，「查看」「复制」保持 40px 触控高度 */
  .field-row { flex-wrap: wrap; row-gap: 8px; padding: 12px 14px; }
  .f-key { width: 100%; }
  .f-val,
  .f-pass,
  .f-site {
    flex: 1 1 55%;
    min-width: 0;
    word-break: break-all;
  }
  .f-btn { height: 40px; padding: 0 16px; font-size: 12.5px; }
  .f-icon { width: 40px; height: 40px; }
  .f-icon i { font-size: 16px; }
}
@media (max-width: 760px) { .vault .group-col { flex: 0 0 auto; max-height: 25%; } .vault .item-col { flex: 1; min-height: 0; } }
</style>
