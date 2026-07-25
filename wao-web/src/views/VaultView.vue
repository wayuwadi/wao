<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { copyText } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import DemoBanner from '@/components/ui/DemoBanner.vue'
import type { VaultItem } from '@/types'

const data = useDataStore()
const groupId = ref('all')
const selId = ref<string | null>(null)
const newGroupOpen = ref(false)
const newGroupName = ref('')

interface FlatItem extends VaultItem {
  groupId: string
  groupName: string
}

const items = computed<FlatItem[]>(() =>
  data.vault.flatMap((g) => g.items.map((it) => ({ ...it, groupId: g.id, groupName: g.group }))),
)
const filtered = computed(() => (groupId.value === 'all' ? items.value : items.value.filter((i) => i.groupId === groupId.value)))
const sel = computed(() => filtered.value.find((i) => i.id === selId.value) ?? filtered.value[0] ?? null)

const groups = computed(() =>
  [{ id: 'all', label: '全部' }, ...data.vault.map((g) => ({ id: g.id, label: g.group }))].map((g) => ({
    ...g,
    count: g.id === 'all' ? items.value.length : items.value.filter((i) => i.groupId === g.id).length,
    on: groupId.value === g.id,
  })),
)

function pickGroup(id: string) {
  groupId.value = id
  selId.value = null
}
async function createGroup() {
  if (!newGroupName.value.trim()) return
  const g = await data.addVaultGroup(newGroupName.value.trim())
  newGroupOpen.value = false
  newGroupName.value = ''
  groupId.value = g.id
}

// ---- 查看 / 复制密码 ----
const revealed = ref<string | null>(null)
const revealing = ref(false)
watch(selId, () => (revealed.value = null))
async function reveal() {
  if (!sel.value || revealing.value) return
  if (!sel.value.hasSecret) {
    toast('该条目未设置密码', 'ri-lock-line')
    return
  }
  revealing.value = true
  try {
    revealed.value = await data.revealVaultItem(sel.value.id)
    if (revealed.value == null) toast('未能获取密码', 'ri-error-warning-line')
  } finally {
    revealing.value = false
  }
}
async function copyPassword() {
  if (!sel.value) return
  if (revealed.value == null) await reveal()
  if (revealed.value != null) copyText(revealed.value, '已复制密码')
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

function openNewItem() {
  editingId.value = null
  formGroupId.value = data.vault[0]?.id ?? ''
  formName.value = ''
  formUser.value = ''
  formSite.value = ''
  formNote.value = ''
  formSecret.value = ''
  itemOpen.value = true
}
function openEditItem() {
  if (!sel.value) return
  editingId.value = sel.value.id
  formGroupId.value = sel.value.groupId
  formName.value = sel.value.name
  formUser.value = sel.value.user
  formSite.value = sel.value.site
  formNote.value = sel.value.note
  formSecret.value = ''
  itemOpen.value = true
}
async function saveItem() {
  if (!formName.value.trim() || !formGroupId.value) return
  if (editingId.value) {
    await data.updateVaultItem(editingId.value, {
      name: formName.value.trim(), user: formUser.value.trim(), site: formSite.value.trim(),
      note: formNote.value.trim(), secret: formSecret.value || undefined, groupId: formGroupId.value,
    })
    revealed.value = null
  } else {
    const item = await data.addVaultItem({
      groupId: formGroupId.value, name: formName.value.trim(), user: formUser.value.trim(),
      site: formSite.value.trim(), note: formNote.value.trim(), secret: formSecret.value || undefined,
    })
    if (item) selId.value = item.id
  }
  itemOpen.value = false
}
async function deleteItem() {
  if (!sel.value) return
  const id = sel.value.id
  selId.value = null
  await data.deleteVaultItem(id)
  toast('已删除条目', 'ri-delete-bin-line')
}
</script>

<template>
  <div class="vault">
    <DemoBanner
      icon="ri-key-2-line"
      title="密码库"
      text="密钥库已接入真实后端：分组与条目的新增 / 编辑 / 删除均会保存，密码以 AES-256-GCM 在服务端静态加密存储，列表接口从不返回明文，仅在点击「查看」时按需解密一次。这是服务端加密而非零知识端到端加密——真正的客户端主密码派生留待后续独立立项。"
      storage-key="vault"
    />
    <div class="vtop">
      <div class="vtitle">密码库</div>
      <span class="vtip">分组管理</span>
      <div class="spacer"></div>
      <button class="btn-ghost" @click="openNewItem"><i class="ri-add-line"></i>添加条目</button>
    </div>

    <div class="banner">
      <i class="ri-shield-check-line"></i>
      <span>密码以 AES-256-GCM 加密存储在服务端；拥有数据库与加密密钥的一方仍可解密，尚不是零知识端到端加密，请评估后再存放高敏感凭据。</span>
    </div>

    <div class="cols">
      <div class="group-col">
        <div class="col-label">分组</div>
        <button v-for="g in groups" :key="g.id" class="group-btn" :class="{ on: g.on }" @click="pickGroup(g.id)">
          <span class="gb-label">{{ g.label }}</span><span class="gb-count wao-tabular">{{ g.count }}</span>
        </button>
        <button class="group-add" @click="newGroupOpen = true"><i class="ri-add-line"></i>新建分组</button>
      </div>

      <div class="item-col">
        <button v-for="it in filtered" :key="it.id" class="item-btn" :class="{ on: sel?.id === it.id }" @click="selId = it.id">
          <div class="item-icon"><i :class="it.icon"></i></div>
          <div class="item-main">
            <div class="item-name">{{ it.name }}</div>
            <div class="item-user">{{ it.user }}</div>
          </div>
        </button>
        <div v-if="filtered.length === 0" class="empty">这个分组还没有条目</div>
      </div>

      <div class="detail-col">
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
      <div class="modal-mask" @click="itemOpen = false"></div>
      <div class="modal">
        <div class="modal-head">
          <div class="modal-title">{{ editingId ? '编辑条目' : '新建条目' }}</div>
          <div class="spacer"></div>
          <button class="icon-btn" @click="itemOpen = false"><i class="ri-close-line"></i></button>
        </div>
        <div class="modal-body">
          <label class="f-label">分组</label>
          <select v-model="formGroupId" class="f-input">
            <option v-for="g in data.vault" :key="g.id" :value="g.id">{{ g.group }}</option>
          </select>
          <label class="f-label">名称</label>
          <input v-model="formName" class="f-input" placeholder="例如「家庭 Wi-Fi」" />
          <label class="f-label">用户名</label>
          <input v-model="formUser" class="f-input" placeholder="选填" />
          <label class="f-label">网址</label>
          <input v-model="formSite" class="f-input" placeholder="选填" />
          <label class="f-label">密码</label>
          <input v-model="formSecret" type="password" class="f-input" :placeholder="editingId ? '留空则不修改' : '选填'" />
          <label class="f-label">备注</label>
          <textarea v-model="formNote" class="f-textarea" rows="2" placeholder="选填"></textarea>
        </div>
        <div class="modal-foot">
          <div class="spacer"></div>
          <button class="btn-primary" :disabled="!formName.trim() || !formGroupId" @click="saveItem">保存</button>
        </div>
      </div>
    </template>

    <!-- 新建分组弹层 -->
    <template v-if="newGroupOpen">
      <div class="modal-mask" @click="newGroupOpen = false"></div>
      <div class="modal narrow">
        <div class="modal-head">
          <div class="modal-title">新建分组</div>
          <div class="spacer"></div>
          <button class="icon-btn" @click="newGroupOpen = false"><i class="ri-close-line"></i></button>
        </div>
        <div class="modal-body">
          <input v-model="newGroupName" class="f-input" placeholder="分组名称" @keydown.enter="createGroup" />
        </div>
        <div class="modal-foot">
          <div class="spacer"></div>
          <button class="btn-primary" :disabled="!newGroupName.trim()" @click="createGroup">创建</button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
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
  font-size: 20px;
  font-weight: 700;
}
.vtip {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.btn-ghost {
  height: 32px;
  padding: 0 13px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-ghost:hover {
  background: #f4f5f8;
}
.btn-ghost i {
  font-size: 14px;
}
.banner {
  margin-top: 12px;
  background: #fffdf7;
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

.cols {
  margin-top: 12px;
  display: flex;
  background: #fff;
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
  font-size: 11px;
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
  background: #f0f1f4;
}
.group-btn.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-weight: 600;
}
.gb-label {
  flex: 1;
  text-align: left;
}
.gb-count {
  font-size: 11px;
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
  font-size: 11.5px;
}
.group-add:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary);
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
  background: #f4f5f8;
}
.item-btn.on {
  background: var(--wao-primary-soft);
}
.item-icon {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: #f0f1f4;
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
  font-size: 11.5px;
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
  background: #f0f1f4;
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
  font-size: 11.5px;
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
  background: #fff;
  border-radius: 7px;
  font-size: 11.5px;
  color: var(--wao-text-2);
  cursor: pointer;
}
.f-btn:hover {
  background: #f4f5f8;
}
.f-icon {
  width: 26px;
  height: 26px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-weak);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.f-icon:hover {
  background: #f4f5f8;
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
  left: 50%;
  transform: translateX(-50%);
  width: min(440px, calc(100vw - 40px));
  max-height: 80vh;
  overflow-y: auto;
  background: #fff;
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
  background: #fff;
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
</style>
