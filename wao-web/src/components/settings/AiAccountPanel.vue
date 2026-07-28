<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { toast } from '@/composables/toast'
import {
  fetchAiAccounts, createAiAccount, updateAiAccount, deleteAiAccount, syncAiAccount,
  type AiAccount, type AiAccountForm,
} from '@/api/aiAccount'

const providerLabels: Record<AiAccount['provider'], string> = {
  codex: 'Codex / ChatGPT',
  claude: 'Claude Code',
  qoder: 'Qoder',
}
const providerOptions: AiAccount['provider'][] = ['claude', 'codex', 'qoder']
const statusLabels: Record<string, string> = {
  UNKNOWN: '未同步',
  NO_API: '无可用接口',
  SYNCED: '已同步',
  FAILED: '同步失败',
}

const accounts = ref<AiAccount[]>([])
const loaded = ref(false)
const busy = ref(false)
const saving = ref(false)
const syncingId = ref<string | null>(null)
const deletingId = ref<string | null>(null)
const editingId = ref<string | null>(null)
const showForm = ref(false)
const form = reactive<AiAccountForm>({
  provider: 'claude',
  label: '',
  email: '',
  credentials: '',
  enabled: true,
})

const hasForm = computed(() => showForm.value || editingId.value !== null)
const isEditing = computed(() => editingId.value !== null)

async function load() {
  busy.value = true
  try {
    accounts.value = await fetchAiAccounts()
    loaded.value = true
  } catch (error) {
    toast(error instanceof Error ? error.message : '读取 AI 账号失败')
  } finally {
    busy.value = false
  }
}

function resetForm() {
  form.provider = 'claude'
  form.label = ''
  form.email = ''
  form.credentials = ''
  form.enabled = true
  editingId.value = null
  showForm.value = false
}

function openAdd() {
  resetForm()
  showForm.value = true
}

function openEdit(account: AiAccount) {
  editingId.value = account.id
  form.provider = account.provider
  form.label = account.label
  form.email = account.email ?? ''
  form.credentials = ''
  form.enabled = account.enabled
  showForm.value = true
}

async function save() {
  const label = form.label.trim()
  if (!label) {
    toast('请输入展示名称')
    return
  }
  const payload: AiAccountForm = {
    provider: form.provider,
    label,
    email: form.email?.trim() || undefined,
    credentials: form.credentials?.trim() || undefined,
    enabled: form.enabled,
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateAiAccount(editingId.value, payload)
      toast('AI 账号已更新', 'ri-check-line')
    } else {
      if (!payload.credentials) {
        toast('创建时必须填写凭据 JSON')
        saving.value = false
        return
      }
      await createAiAccount(payload as Required<Pick<AiAccountForm, 'credentials'>> & AiAccountForm)
      toast('AI 账号已创建', 'ri-check-line')
    }
    resetForm()
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(id: string) {
  deletingId.value = id
  try {
    await deleteAiAccount(id)
    toast('已删除', 'ri-check-line')
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '删除失败')
  } finally {
    deletingId.value = null
  }
}

async function sync(id: string) {
  syncingId.value = id
  try {
    await syncAiAccount(id)
    toast('额度快照已刷新', 'ri-check-line')
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '同步失败')
  } finally {
    syncingId.value = null
  }
}

async function toggle(account: AiAccount) {
  try {
    await updateAiAccount(account.id, { enabled: !account.enabled })
    toast(account.enabled ? '已停用' : '已启用', 'ri-check-line')
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '切换失败')
  }
}

watch(() => true, load, { immediate: true })
</script>

<template>
  <div class="s-h1">AI 账号</div>
  <div class="card">
    <div class="card-row wrap">
      <div class="card-title">已集中管理的账号</div>
      <div class="spacer"></div>
      <button class="btn-primary sm" :disabled="busy || hasForm" @click="openAdd">
        <i class="ri-add-line"></i>添加账号
      </button>
    </div>

    <div v-if="!loaded && busy" class="hint">加载中…</div>
    <div v-else-if="accounts.length === 0" class="hint">还没有集中管理的 AI 账号。</div>
    <div v-else class="account-list">
      <div v-for="a in accounts" :key="a.id" class="account-row">
        <div class="account-main">
          <div class="account-head">
            <span class="account-provider">{{ providerLabels[a.provider] }}</span>
            <span class="account-label">{{ a.label }}</span>
            <span class="account-email" :title="a.email ?? ''">{{ a.email || '未记录邮箱' }}</span>
          </div>
          <div class="account-meta">
            <span class="account-status" :class="a.quotaStatus?.toLowerCase()">
              {{ statusLabels[a.quotaStatus ?? 'UNKNOWN'] || a.quotaStatus }}
            </span>
            <span v-if="a.quotaMessage" class="account-message" :title="a.quotaMessage">
              {{ a.quotaMessage }}
            </span>
            <span v-if="a.lastSyncedAt" class="account-time">
              上次同步 {{ new Date(a.lastSyncedAt).toLocaleString('zh-CN') }}
            </span>
          </div>
        </div>
        <div class="account-ops">
          <button
            class="toggle"
            :class="{ on: a.enabled }"
            :disabled="deletingId === a.id || syncingId === a.id"
            :title="a.enabled ? '停用' : '启用'"
            @click="toggle(a)"
          ><span class="knob"></span></button>
          <button class="btn-ghost sm" :disabled="syncingId === a.id" @click="sync(a.id)">
            <i :class="syncingId === a.id ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>同步额度
          </button>
          <button class="btn-ghost sm" @click="openEdit(a)">
            <i class="ri-edit-line"></i>编辑
          </button>
          <button class="revoke" :disabled="deletingId === a.id" @click="remove(a.id)">
            {{ deletingId === a.id ? '删除中…' : '删除' }}
          </button>
        </div>
      </div>
    </div>

    <div class="hint">
      凭据以 AES-256-GCM 加密保存在服务端，页面不回传明文；只有已配对且启用凭据同步的 Runner 才能拉取。
      额度同步只调用真实存在的供应商接口，没有可靠接口时会明确标注「无可用接口」。
    </div>
  </div>

  <div v-if="hasForm" class="card">
    <div class="card-title">{{ isEditing ? '编辑账号' : '添加账号' }}</div>
    <form class="account-form" @submit.prevent="save">
      <label>
        <span>AI 工具</span>
        <select v-model="form.provider">
          <option v-for="p in providerOptions" :key="p" :value="p">{{ providerLabels[p] }}</option>
        </select>
      </label>
      <label>
        <span>展示名称</span>
        <input v-model.trim="form.label" maxlength="128" placeholder="例如 娃哥-Claude" required />
      </label>
      <label>
        <span>账号邮箱 / 标识</span>
        <input v-model.trim="form.email" maxlength="255" placeholder="可选，方便识别" />
      </label>
      <label class="full">
        <span>凭据 JSON</span>
        <textarea
          v-model.trim="form.credentials"
          rows="8"
          :placeholder="isEditing
            ? '留空表示不修改已存凭据；填写会覆盖服务端加密存储的旧凭据'
            : '粘贴该 AI CLI 的完整凭据 JSON，例如 ~/.claude/.credentials.json 内容'"
        ></textarea>
      </label>
      <label class="inline">
        <span>启用</span>
        <button type="button" class="toggle" :class="{ on: form.enabled }" @click="form.enabled = !form.enabled">
          <span class="knob"></span>
        </button>
      </label>
      <div class="form-actions">
        <button type="button" class="btn-ghost" :disabled="saving" @click="resetForm">取消</button>
        <button class="btn-primary" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.account-list {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.account-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--wao-divider-2);
}
.account-row:last-child {
  border-bottom: none;
}
.account-main {
  flex: 1;
  min-width: 0;
}
.account-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.account-provider {
  font-size: 11.5px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-radius: 5px;
  padding: 2px 8px;
}
.account-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--wao-text);
}
.account-email {
  font-size: 12px;
  color: var(--wao-text-weak);
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.account-meta {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.account-status {
  border-radius: 5px;
  padding: 2px 7px;
  color: var(--wao-text-2);
  background: var(--wao-bg-sub);
}
.account-status.synced {
  background: #e8f5e9;
  color: #2e7d32;
}
.account-status.failed {
  background: #ffebee;
  color: #c62828;
}
.account-status.no_api {
  background: #fff3e0;
  color: #ef6c00;
}
.account-message {
  color: var(--wao-text-weak);
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.account-time {
  color: var(--wao-text-faint);
}
.account-ops {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.account-form {
  margin-top: 14px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 13px 16px;
}
.account-form label {
  display: grid;
  gap: 6px;
  color: var(--wao-text-3);
  font-size: 12px;
}
.account-form label.full,
.account-form label.inline,
.form-actions {
  grid-column: 1 / -1;
}
.account-form label.inline {
  display: flex;
  align-items: center;
  gap: 10px;
}
.account-form input,
.account-form select,
.account-form textarea {
  box-sizing: border-box;
  padding: 0 11px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  outline: none;
  color: var(--wao-text);
  font-size: 13px;
  background: #fff;
}
.account-form input,
.account-form select {
  height: 36px;
}
.account-form textarea {
  padding: 10px 11px;
  resize: vertical;
  font-family: var(--wao-mono-font, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace);
}
.account-form input:focus,
.account-form select:focus,
.account-form textarea:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.revoke {
  height: 26px;
  padding: 0 10px;
  border: 1px solid #f0c9c9;
  background: #fff;
  border-radius: 7px;
  font-size: 11.5px;
  color: var(--wao-danger-strong);
  cursor: pointer;
}
.revoke:hover {
  background: var(--wao-danger-bg);
}
.spin {
  display: inline-block;
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
@media (max-width: 760px) {
  .account-row {
    flex-direction: column;
  }
  .account-ops {
    width: 100%;
    justify-content: flex-end;
  }
  .account-form {
    grid-template-columns: 1fr;
  }
}
</style>
