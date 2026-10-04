<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import {
  createGranularWorkspaceToken,
  fetchWorkspaceResources,
  revokeWorkspaceMcpToken,
  type CreatedWorkspaceMcpToken,
  type WorkspaceCapability,
  type WorkspaceGrantRequest,
  type WorkspaceMcpToken,
} from '@/api/workspaceMcp'

const props = defineProps<{
  capabilities: WorkspaceCapability[]
  token?: WorkspaceMcpToken
  clientType?: 'CLI' | 'MCP'
}>()
const emit = defineEmits<{ changed: []; newDraft: [] }>()
const session = useSessionStore()
const draft = ref<WorkspaceGrantRequest>({
  name: '',
  clientType: 'MCP',
  permissions: [],
  projectIds: null,
  knowledgeBaseIds: null,
})
const group = ref('tasks'),
  mode = ref('groups'),
  operation = ref(''),
  busy = ref(false),
  error = ref(''),
  notice = ref('')
const resources = ref<Awaited<ReturnType<typeof fetchWorkspaceResources>> | null>(null)
const resourcesLoading = ref(false),
  resourcesError = ref(''),
  storageError = ref(''),
  confirmRevoke = ref(false)
const issued = ref<CreatedWorkspaceMcpToken | null>(null)
const groupNames: Record<string, string> = {
  projects: '项目',
  tasks: '任务与子任务',
  knowledge: '知识库与目录',
  documents: '文档',
  pages: 'HTML 发布',
}
const key = computed(() => `wao:capability-connection-draft:v1:${session.userId}`)
const groupCapabilities = computed(() => props.capabilities.filter((c) => c.group === group.value))
const visibleCapabilities = computed(() =>
  mode.value === 'direct'
    ? groupCapabilities.value.filter((c) => c.name === operation.value)
    : groupCapabilities.value,
)
const readonly = computed(() => !!props.token || !!issued.value)
const scopeKey = computed(() =>
  ['projects', 'tasks'].includes(group.value) ? 'projectIds' : 'knowledgeBaseIds',
)
const scopeOptions = computed(() =>
  scopeKey.value === 'projectIds'
    ? (resources.value?.projects ?? [])
    : (resources.value?.knowledgeBases ?? []),
)
const scopeLabel = computed(() => (scopeKey.value === 'projectIds' ? '项目' : '知识库'))
const permissions = computed(() =>
  props.token ? tokenPermissions(props.token) : draft.value.permissions,
)
const scopeIds = computed(() =>
  props.token ? (props.token[scopeKey.value] ?? null) : draft.value[scopeKey.value],
)
const viewName = computed(() => props.token?.name ?? '新建连接')
const endpoint = `${location.origin}/api/mcp/workspace`
const instruction = computed(() =>
  !issued.value
    ? ''
    : draft.value.clientType === 'CLI'
      ? `运行 wao auth login，按提示输入本次令牌。\nToken：${issued.value.secret}\n再运行 wao auth status 与 wao tools list 验证连接。`
      : `请连接 WAO 的 Streamable HTTP MCP。\n地址：${endpoint}\nToken：${issued.value.secret}\n认证：Authorization: Bearer <Token>\n安全保存令牌，验证 initialize 与 tools/list；只调用实际可见的工具。`,
)
let generation = 0
function tokenPermissions(token: WorkspaceMcpToken) {
  return token.permissionMode === 'granular'
    ? (token.permissions ?? [])
    : props.capabilities
        .filter((c) =>
          c.name === 'publish_html' ? token.canPublishHostedPage : c.readOnly || token.canWrite,
        )
        .map((c) => c.name)
}
function restoreDraft() {
  issued.value = null
  notice.value = ''
  storageError.value = ''
  error.value = ''
  draft.value = {
    name: '',
    clientType: props.clientType ?? 'MCP',
    permissions: props.capabilities.filter((c) => c.readOnly).map((c) => c.name),
    projectIds: null,
    knowledgeBaseIds: null,
  }
  try {
    const raw = localStorage.getItem(key.value)
    if (raw) {
      const saved = JSON.parse(raw) as WorkspaceGrantRequest
      if (
        typeof saved.name === 'string' &&
        ['CLI', 'MCP'].includes(saved.clientType) &&
        Array.isArray(saved.permissions) &&
        (saved.projectIds === null || Array.isArray(saved.projectIds)) &&
        (saved.knowledgeBaseIds === null || Array.isArray(saved.knowledgeBaseIds))
      )
        draft.value = saved
    }
  } catch {
    storageError.value = '无法恢复本机草稿。请保持页面打开，输入仍可使用。'
  }
}
async function loadResources() {
  if (!session.authed) return
  const ticket = ++generation
  resourcesLoading.value = true
  resourcesError.value = ''
  try {
    const value = await fetchWorkspaceResources()
    if (ticket === generation) resources.value = value
  } catch (cause) {
    if (ticket === generation) resourcesError.value = message(cause, '读取可选范围失败')
  } finally {
    if (ticket === generation) resourcesLoading.value = false
  }
}
function message(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback
}
function togglePermission(name: string, checked: boolean) {
  if (readonly.value) return
  draft.value.permissions = checked
    ? [...new Set([...draft.value.permissions, name])]
    : draft.value.permissions.filter((p) => p !== name)
}
function toggleResource(id: string, checked: boolean) {
  if (readonly.value) return
  const ids = draft.value[scopeKey.value] ?? []
  draft.value[scopeKey.value] = checked
    ? [...new Set([...ids, id])]
    : ids.filter((value) => value !== id)
}
function changeScope(value: string) {
  if (!readonly.value) draft.value[scopeKey.value] = value === 'all' ? null : []
}
function migrate() {
  if (!props.token) return
  draft.value = {
    name: `${props.token.name} · 新授权`.slice(0, 80),
    clientType: props.token.clientType ?? 'MCP',
    permissions: tokenPermissions(props.token),
    projectIds: props.token.projectIds ?? null,
    knowledgeBaseIds: props.token.knowledgeBaseIds ?? null,
  }
  emit('newDraft')
}
async function create() {
  if (busy.value || readonly.value) return
  error.value = ''
  notice.value = ''
  if (!draft.value.name.trim() || !draft.value.permissions.length) {
    error.value = '请填写连接名称，并至少选择一项操作。'
    return
  }
  if (draft.value.projectIds?.length === 0 || draft.value.knowledgeBaseIds?.length === 0) {
    error.value = '指定范围时，请至少选择一个对应资源。'
    return
  }
  if (draft.value.permissions.some((p) => !props.capabilities.some((c) => c.name === p))) {
    error.value = '草稿包含当前目录未提供的操作，请核对后重新选择。'
    return
  }
  busy.value = true
  try {
    issued.value = await createGranularWorkspaceToken({
      ...draft.value,
      name: draft.value.name.trim(),
    })
    notice.value = '连接已签发。完整令牌仅本次显示，请在离开前安全保存。'
    emit('changed')
  } catch (cause) {
    error.value = message(cause, '签发失败，请重试；草稿已保留')
  } finally {
    busy.value = false
  }
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    notice.value = '已复制，请安全保存。'
  } catch {
    error.value = '复制失败，请手动选择下方内容复制。'
  }
}
async function revoke() {
  if (!props.token || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await revokeWorkspaceMcpToken(props.token.id)
    confirmRevoke.value = false
    notice.value = '连接已吊销。'
    emit('changed')
  } catch (cause) {
    error.value = message(cause, '吊销失败，请重试')
  } finally {
    busy.value = false
  }
}
watch(
  () => session.userId,
  () => {
    restoreDraft()
    resources.value = null
    void loadResources()
  },
  { immediate: true },
)
watch(
  () => props.capabilities,
  (caps) => {
    try {
      if (!draft.value.permissions.length && !localStorage.getItem(key.value))
        draft.value.permissions = caps.filter((c) => c.readOnly).map((c) => c.name)
    } catch {
      storageError.value = '本机草稿存储不可用，请保持页面打开。'
    }
  },
  { immediate: true },
)
watch(
  groupCapabilities,
  (caps) => {
    if (!caps.some((c) => c.name === operation.value)) operation.value = caps[0]?.name ?? ''
  },
  { immediate: true },
)
watch(
  () => props.token?.id,
  () => {
    issued.value = null
    confirmRevoke.value = false
    error.value = ''
  },
)
watch(
  draft,
  (value) => {
    if (!session.userId) return
    try {
      localStorage.setItem(key.value, JSON.stringify(value))
      storageError.value = ''
    } catch {
      storageError.value = '草稿尚未保存到本机，请保持页面打开。'
    }
  },
  { deep: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  generation++
  issued.value = null
})
</script>

<template>
  <section class="connection-panel">
    <div class="cap-detail-heading">
      <span class="cap-icon"><i class="ri-shield-keyhole-line" /></span>
      <div>
        <small>{{
          token
            ? token.permissionMode === 'granular'
              ? '逐项授权连接'
              : '旧版整体授权'
            : '连接与授权'
        }}</small>
        <h2>{{ viewName }}</h2>
      </div>
    </div>
    <p class="cap-muted">逐项选择操作，再限定项目或知识库范围。CLI 与 MCP 共用业务权限判断。</p>
    <template v-if="token">
      <dl class="cap-facts">
        <dt>类型</dt>
        <dd>{{ token.clientType ?? 'CLI / MCP（历史未标记）' }}</dd>
        <dt>创建于</dt>
        <dd>{{ token.createdAt }}</dd>
        <dt>最后使用</dt>
        <dd>{{ token.lastUsedAt ?? '尚未使用' }}</dd>
        <dt>令牌摘要</dt>
        <dd>{{ token.tokenPrefix }}••••</dd>
      </dl>
      <p v-if="token.permissionMode !== 'granular'" class="cap-info">
        原连接沿用整体{{ token.canWrite ? '读写' : '只读' }}授权，HTML 发布{{
          token.canPublishHostedPage ? '已授权' : '未授权'
        }}。修改权限需创建新连接，迁移客户端后再吊销旧连接。
      </p>
    </template>
    <div v-else class="cap-form-grid">
      <label class="cap-field"
        >连接名称<input
          v-model="draft.name"
          maxlength="80"
          placeholder="例如：我的 Codex"
          :disabled="readonly || busy" /></label
      ><label class="cap-field"
        >客户端类型<select v-model="draft.clientType" :disabled="readonly || busy">
          <option>CLI</option>
          <option>MCP</option>
        </select></label
      >
    </div>
    <div class="cap-section-heading">
      <h3>已选 {{ permissions.length }} / {{ capabilities.length }} 项操作</h3>
      <div class="cap-view-modes" aria-label="权限查看方式">
        <button
          v-for="view in [
            { id: 'groups', name: '按功能' },
            { id: 'direct', name: '单项' },
            { id: 'matrix', name: '表格' },
          ]"
          :key="view.id"
          type="button"
          :class="{ active: mode === view.id }"
          :aria-pressed="mode === view.id"
          @click="mode = view.id"
        >
          {{ view.name }}
        </button>
      </div>
    </div>
    <div class="cap-group-picker">
      <button
        v-for="(name, id) in groupNames"
        :key="id"
        type="button"
        :class="{ active: group === id }"
        :aria-pressed="group === id"
        @click="group = id"
      >
        {{ name }} <small>{{ capabilities.filter((c) => c.group === id).length }}</small>
      </button>
    </div>
    <label v-if="mode === 'direct'" class="cap-field"
      >选择单项能力<select v-model="operation">
        <option v-for="item in groupCapabilities" :key="item.name" :value="item.name">
          {{ item.title }}
        </option>
      </select></label
    >
    <table v-if="mode === 'matrix'" class="cap-permission-table">
      <thead>
        <tr>
          <th>操作</th>
          <th>CLI / MCP</th>
          <th>授权</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in groupCapabilities" :key="item.name">
          <td>{{ item.title }}</td>
          <td>
            <code>{{ item.cli }}</code
            ><small>{{ item.mcp ?? '仅 CLI' }}</small>
          </td>
          <td>
            <input
              type="checkbox"
              :aria-label="`允许${item.title}`"
              :checked="permissions.includes(item.name)"
              :disabled="readonly || busy"
              @change="togglePermission(item.name, ($event.target as HTMLInputElement).checked)"
            />
          </td>
        </tr>
      </tbody>
    </table>
    <div v-else>
      <article v-for="item in visibleCapabilities" :key="item.name" class="cap-permission">
        <label
          ><input
            type="checkbox"
            :checked="permissions.includes(item.name)"
            :disabled="readonly || busy"
            :aria-label="`允许${item.title}`"
            @change="togglePermission(item.name, ($event.target as HTMLInputElement).checked)"
          /><strong>{{ item.title }}</strong
          ><span class="cap-badge">{{
            item.readOnly ? '读取' : item.name === 'publish_html' ? '公开发布' : '写入'
          }}</span></label
        >
        <p>{{ item.description }}</p>
        <details>
          <summary>CLI 与 MCP 对应能力</summary>
          <dl class="cap-command-pair">
            <dt>CLI</dt>
            <dd>
              <code>{{ item.cli }}</code>
            </dd>
            <dt>MCP</dt>
            <dd>
              <code>{{ item.mcp ?? '尚未开放' }}</code>
            </dd>
          </dl>
        </details>
      </article>
    </div>
    <div v-if="group !== 'pages'" class="cap-resource-scope">
      <h3>数据范围 · {{ scopeLabel }}</h3>
      <p v-if="resourcesLoading" role="status">正在读取可选范围…</p>
      <div v-if="resourcesError" class="cap-error" role="alert">
        {{ resourcesError }} <button type="button" @click="loadResources">重新读取</button>
      </div>
      <label class="cap-field"
        >可访问范围<select
          :value="scopeIds === null ? 'all' : 'specific'"
          :disabled="readonly || busy || resourcesLoading || !!resourcesError"
          @change="changeScope(($event.target as HTMLSelectElement).value)"
        >
          <option value="all">沿用账号当前可访问范围</option>
          <option value="specific">仅指定{{ scopeLabel }}</option>
        </select></label
      >
      <div v-if="scopeIds !== null" class="cap-scope-list">
        <label v-for="resource in scopeOptions" :key="resource.id"
          ><input
            type="checkbox"
            :checked="scopeIds.includes(resource.id)"
            :disabled="readonly || busy"
            @change="toggleResource(resource.id, ($event.target as HTMLInputElement).checked)"
          />{{ resource.name }}</label
        >
        <p v-if="!scopeOptions.length && resources" class="cap-muted">
          没有可选择的{{ scopeLabel }}。
        </p>
        <p
          v-for="id in resources && !resourcesLoading && !resourcesError
            ? scopeIds.filter((id) => !scopeOptions.some((o) => o.id === id))
            : []"
          :key="id"
          class="cap-muted"
        >
          {{ id }} · 当前不可访问，签发前请核对。
        </p>
      </div>
      <p class="cap-muted">
        范围只收窄账号权限。项目与知识库的新建操作沿用 WAO 规则，不自动扩大限定范围。
      </p>
    </div>
    <p v-else class="cap-info">HTML 发布会生成公开页面，不获得已有页面的读取、修改或删除权限。</p>
    <div v-if="error" class="cap-error" role="alert">{{ error }}</div>
    <p v-if="storageError" class="cap-error" role="alert">{{ storageError }}</p>
    <div v-if="issued" class="cap-issued">
      <h3>完整令牌仅本次显示</h3>
      <p class="cap-muted">页面离开后不能再次读取明文。令牌不会保存在草稿中。</p>
      <textarea :value="instruction" readonly aria-label="连接指令" />
      <div class="cap-actions">
        <button type="button" class="cap-button primary" @click="copy(issued.secret)">
          复制令牌</button
        ><button type="button" class="cap-button" @click="copy(instruction)">复制连接指令</button
        ><button type="button" class="cap-button" @click="issued = null">清除本次明文</button>
      </div>
    </div>
    <div class="cap-actions" v-else-if="token">
      <button type="button" class="cap-button primary" @click="migrate">复制为新授权草稿</button
      ><button
        type="button"
        class="cap-button danger"
        :disabled="busy"
        @click="confirmRevoke = true"
      >
        吊销连接
      </button>
    </div>
    <div v-if="confirmRevoke" class="cap-revoke-confirm">
      <p>吊销“{{ token?.name }}”后，该设备立即失去 WAO 访问权限。</p>
      <div class="cap-actions">
        <button type="button" class="cap-button danger" :disabled="busy" @click="revoke">
          {{ busy ? '吊销中…' : '确认吊销' }}</button
        ><button type="button" class="cap-button" :disabled="busy" @click="confirmRevoke = false">
          取消
        </button>
      </div>
    </div>
    <div v-else-if="!token && !issued" class="cap-actions">
      <button
        type="button"
        class="cap-button primary"
        :disabled="busy || !capabilities.length"
        @click="create"
      >
        {{ busy ? '签发中…' : '生成连接指令' }}</button
      ><button
        type="button"
        class="cap-button"
        :disabled="busy"
        @click="draft.permissions = capabilities.filter((c) => c.readOnly).map((c) => c.name)"
      >
        只选读取</button
      ><button
        type="button"
        class="cap-button"
        :disabled="busy || !capabilities.length"
        @click="draft.permissions = capabilities.filter((c) => c.name !== 'publish_html').map((c) => c.name)"
      >
        日常完整</button
      ><span class="cap-muted">{{ storageError ? '草稿未保存' : '草稿已保存在本机' }}</span>
    </div>
    <p v-if="notice" role="status" class="cap-notice">{{ notice }}</p>
  </section>
</template>

<style scoped>
.connection-panel {
  min-width: 0;
}
.cap-permission {
  padding: 12px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  margin: 9px 0;
}
.cap-permission > label {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
}
.cap-permission .cap-badge {
  margin-left: auto;
}
.cap-permission p {
  margin: 5px 0 0 24px;
  font-size: 12px;
  color: var(--wao-text-3);
}
.cap-permission details {
  margin: 8px 0 0 24px;
  font-size: 12px;
}
.cap-permission summary {
  color: var(--wao-primary-text);
  cursor: pointer;
}
.cap-command-pair {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 7px;
  margin: 10px 0;
}
.cap-command-pair dd {
  margin: 0;
}
.cap-permission-table {
  table-layout: fixed;
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.cap-permission-table th,
.cap-permission-table td {
  text-align: left;
  padding: 10px 5px;
  border-bottom: 1px solid var(--wao-line);
  vertical-align: top;
  overflow-wrap: anywhere;
}
.cap-permission-table th:last-child {
  width: 44px;
}
.cap-permission-table td small {
  display: block;
  overflow-wrap: anywhere;
  color: var(--wao-text-3);
}
.cap-resource-scope {
  margin-top: 18px;
  border-top: 1px solid var(--wao-line);
  padding-top: 15px;
}
.cap-scope-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin-top: 12px;
}
.cap-scope-list label {
  display: flex;
  gap: 7px;
  align-items: start;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.cap-issued {
  margin-top: 18px;
  padding: 15px;
  background: var(--wao-success-bg);
  border: 1px solid var(--wao-success-line);
  border-radius: 11px;
}
.cap-issued textarea {
  width: 100%;
  min-height: 180px;
  resize: vertical;
  padding: 10px;
  margin-top: 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font:
    12px/1.7 ui-monospace,
    monospace;
  box-sizing: border-box;
}
.cap-revoke-confirm {
  margin-top: 15px;
  padding: 12px;
  border-radius: 9px;
  background: var(--wao-danger-bg);
  font-size: 12px;
}
input[type='checkbox'] {
  accent-color: var(--wao-primary);
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  margin: 2px 0;
}
.cap-notice {
  font-size: 12px;
  color: var(--wao-success-strong);
  margin-top: 12px;
}
</style>
