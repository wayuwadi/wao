<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  createMemoryAccessToken,
  fetchMemoryAccessTokens,
  fetchMemoryGrants,
  revokeMemoryAccessToken,
  saveMemoryGrants,
  type MemoryGrant,
  type MemoryAccessToken,
} from '@/api/memory'
import { fetchProjects } from '@/api/board'
import { useSessionStore } from '@/stores/session'
import type { Project } from '@/types'
import { copyText } from '@/utils/clipboard'
import { toast } from '@/composables/toast'

const tokens = ref<MemoryAccessToken[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const newTokenName = ref('')
const freshToken = ref('')
const session = useSessionStore()
const projects = ref<Project[]>([])
const grantToken = ref<MemoryAccessToken | null>(null)
const grantsLoading = ref(false)
const orgGranted = ref(false)
const projectGrants = ref<string[]>([])

const endpoint = computed(() => `${window.location.origin}/api/mcp/memory/personal`)
const addCommand = computed(() =>
  `codex mcp add wao-memory --url ${endpoint.value} --bearer-token-env-var WAO_MEMORY_TOKEN`,
)
const configToml = computed(() => `[mcp_servers.wao-memory]
url = "${endpoint.value}"
bearer_token_env_var = "WAO_MEMORY_TOKEN"
enabled_tools = ["search_memory", "save_memory", "memory_source_options"]`)
const contextConfig = computed(() => {
  const blocks = [`[mcp_servers.wao-personal-memory]\nurl = "${endpoint.value}"\nbearer_token_env_var = "WAO_MEMORY_TOKEN"\nenabled_tools = ["search_memory", "get_memory", "save_memory", "memory_source_options"]`]
  if (orgGranted.value && session.org.id) blocks.push(`[mcp_servers.wao-organization-memory]\nurl = "${window.location.origin}/api/mcp/memory/organization/${session.org.id}"\nbearer_token_env_var = "WAO_MEMORY_TOKEN"\nenabled_tools = ["search_memory", "get_memory", "propose_memory", "memory_source_options"]`)
  for (const id of projectGrants.value) {
    const project = projects.value.find((item) => item.id === id)
    blocks.push(`[mcp_servers.wao-project-${id}]\nurl = "${window.location.origin}/api/mcp/memory/project/${id}"\nbearer_token_env_var = "WAO_MEMORY_TOKEN"\nenabled_tools = ["search_memory", "get_memory", "propose_memory", "memory_source_options"]\n# ${project?.name || id}`)
  }
  return blocks.join('\n\n')
})

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const [tokenResult, projectResult] = await Promise.all([fetchMemoryAccessTokens(), fetchProjects()])
    tokens.value = tokenResult
    projects.value = projectResult
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '记忆令牌读取失败'
  } finally {
    loading.value = false
  }
}

async function manageGrants(token: MemoryAccessToken) {
  grantToken.value = token
  grantsLoading.value = true
  error.value = ''
  try {
    const values = await fetchMemoryGrants(token.id)
    orgGranted.value = values.some((item) => item.scopeType === 'organization' && item.scopeId === session.org.id)
    projectGrants.value = values.filter((item) => item.scopeType === 'project').map((item) => item.scopeId)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '设备授权读取失败' }
  finally { grantsLoading.value = false }
}

async function persistGrants() {
  if (!grantToken.value) return
  busy.value = true
  const values: MemoryGrant[] = []
  if (orgGranted.value && session.org.id) values.push({ scopeType: 'organization', scopeId: session.org.id, canRead: true, canPropose: true, canDirectWrite: false })
  projectGrants.value.forEach((scopeId) => values.push({ scopeType: 'project', scopeId, canRead: true, canPropose: true, canDirectWrite: false }))
  try { await saveMemoryGrants(grantToken.value.id, values); toast('设备记忆范围已更新', 'ri-checkbox-circle-line') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '设备授权保存失败' }
  finally { busy.value = false }
}

async function create() {
  const name = newTokenName.value.trim()
  if (!name || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const created = await createMemoryAccessToken(name)
    tokens.value = [created.token, ...tokens.value]
    freshToken.value = created.secret
    newTokenName.value = ''
    toast('记忆令牌已生成，请立即复制保存', 'ri-key-2-line')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '令牌生成失败，请重试'
  } finally {
    busy.value = false
  }
}

async function revoke(token: MemoryAccessToken) {
  if (busy.value || !window.confirm(`吊销“${token.name}”的记忆访问权限？该设备会立即断开。`)) return
  busy.value = true
  error.value = ''
  try {
    await revokeMemoryAccessToken(token.id)
    tokens.value = tokens.value.filter((item) => item.id !== token.id)
    if (grantToken.value?.id === token.id) grantToken.value = null
    toast(`已吊销“${token.name}”`, 'ri-close-circle-line')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '吊销失败，请重试'
  } finally {
    busy.value = false
  }
}

function formatTime(value?: string) {
  if (!value) return '尚未使用'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <div class="memory-panel">
    <div class="s-h1">Codex 记忆中心 · MCP</div>

    <section class="card memory-overview">
      <div class="memory-title"><span><i class="ri-brain-line"></i></span><div><b>WAO 是跨设备长期记忆的唯一权威源</b><small>个人域直接写入；组织和项目域只提交审核候选</small></div></div>
      <div class="memory-flow">
        <span>人工文字 / 多模态原件</span><i class="ri-arrow-right-line"></i>
        <span>当前账号的个人记忆</span><i class="ri-arrow-right-line"></i>
        <span>MCP</span><i class="ri-arrow-right-line"></i>
        <span>Codex 桌面端 / CLI / IDE</span>
      </div>
      <p>当前数据量直接检索按成员隔离的权威业务表，更新后立即可查。没有稳定 embedding 来源时不建立会过期的空向量副本；图片、录音和视频只保存真实原件与人工说明，不伪造自动理解结果。</p>
    </section>

    <section class="card">
      <div class="card-title">1. 为这台设备生成令牌</div>
      <p class="token-note">一台设备一枚，方便丢失或停用时单独吊销。服务端只保存摘要，完整令牌仅显示一次。</p>
      <form class="token-input" @submit.prevent="create">
        <input v-model="newTokenName" maxlength="80" placeholder="设备或用途，例如“家里 Windows Codex”" :disabled="busy" />
        <button class="btn-primary" :disabled="busy || !newTokenName.trim()"><i class="ri-key-2-line"></i>{{ busy ? '生成中…' : '生成令牌' }}</button>
      </form>
      <div v-if="freshToken" class="fresh">
        <div class="fresh-head"><i class="ri-checkbox-circle-fill"></i>仅此一次完整显示，请立即复制到安全位置</div>
        <div class="fresh-row">
          <code>{{ freshToken }}</code>
          <button @click="copyText(freshToken, '令牌已复制')">复制</button>
          <button @click="freshToken = ''">我已保存</button>
        </div>
      </div>
      <div v-if="error" class="memory-error"><i class="ri-error-warning-line"></i>{{ error }}<button v-if="!tokens.length" @click="load">重试</button></div>
      <div v-if="loading" class="memory-empty"><i class="ri-loader-4-line spin"></i>正在读取设备令牌…</div>
      <div v-else class="token-list">
        <div v-for="token in tokens" :key="token.id" class="token-row">
          <span class="device-icon"><i class="ri-computer-line"></i></span>
          <span class="token-meta"><b>{{ token.name }}</b><small><code>{{ token.tokenPrefix }}••••</code> · 创建 {{ formatTime(token.createdAt) }} · 最近使用 {{ formatTime(token.lastUsedAt) }}</small></span>
          <button class="grant" :disabled="busy" @click="manageGrants(token)">授权范围</button>
          <button class="revoke" :disabled="busy" @click="revoke(token)">吊销</button>
        </div>
        <div v-if="!tokens.length && !error" class="memory-empty">还没有设备令牌</div>
      </div>
    </section>

    <section v-if="grantToken" class="card grant-card">
      <div class="card-title">2. 设置“{{ grantToken.name }}”可访问的上下文</div>
      <p class="token-note">个人域始终只属于当前账号；组织和项目仅授予读取与提交候选权限，Codex 不能直接发布。</p>
      <div v-if="grantsLoading" class="memory-empty"><i class="ri-loader-4-line spin"></i>正在读取授权…</div>
      <div v-else class="grant-options">
        <label><input type="checkbox" checked disabled /><span><b>个人记忆</b><small>读取与明确要求时直接保存</small></span></label>
        <label><input v-model="orgGranted" type="checkbox" /><span><b>{{ session.org.name || '当前组织' }}</b><small>读取正式版本、提交待审核候选</small></span></label>
        <label v-for="project in projects" :key="project.id"><input v-model="projectGrants" type="checkbox" :value="project.id" /><span><b>{{ project.name }}</b><small>仅当前项目上下文</small></span></label>
      </div>
      <div class="grant-actions"><button @click="grantToken = null">关闭</button><button class="btn-primary" :disabled="busy" @click="persistGrants">保存授权</button></div>
    </section>

    <section class="card">
      <div class="card-title">3. 在设备上连接 Codex</div>
      <ol class="setup-steps">
        <li><b>设置环境变量</b><span>把刚复制的令牌保存为 <code>WAO_MEMORY_TOKEN</code>。重启 Codex 后生效。</span></li>
        <li><b>运行接入命令</b><span>在 Codex CLI 所在终端执行；桌面端、CLI 与 IDE 会共用同一主机配置。</span></li>
      </ol>
      <div class="command-row"><code>{{ addCommand }}</code><button @click="copyText(addCommand, 'Codex 接入命令已复制')">复制命令</button></div>
      <details>
        <summary>也可以手动写入 config.toml</summary>
        <div class="command-row multiline"><pre>{{ configToml }}</pre><button @click="copyText(configToml, 'MCP 配置已复制')">复制配置</button></div>
      </details>
      <details v-if="grantToken">
        <summary>复制包含已授权组织 / 项目的完整配置</summary>
        <div class="command-row multiline"><pre>{{ contextConfig }}</pre><button @click="copyText(contextConfig, '上下文 MCP 配置已复制')">复制完整配置</button></div>
      </details>
      <div class="platform-notes">
        <span><b>Windows PowerShell（持久）</b><code>[Environment]::SetEnvironmentVariable("WAO_MEMORY_TOKEN", "&lt;令牌&gt;", "User")</code></span>
        <span><b>Linux / macOS</b><code>export WAO_MEMORY_TOKEN="&lt;令牌&gt;"</code></span>
      </div>
    </section>

    <section class="card">
      <div class="card-title">4. 验证真实记忆链路</div>
      <div class="verify-grid">
        <span><i class="ri-search-eye-line"></i><b>检索</b><small>对 Codex 说：“先查 WAO 记忆，再告诉我目前的工作职责和长期规划。”</small></span>
        <span><i class="ri-save-3-line"></i><b>保存</b><small>对 Codex 说：“请记住：以后每个任务都主动给出一条体验建议。”</small></span>
        <span><i class="ri-book-open-line"></i><b>核对</b><small>打开“记忆中心”，确认个人记录已发布，组织/项目记录仍是待审核候选。</small></span>
      </div>
      <p class="safety-note"><i class="ri-shield-keyhole-line"></i>不要让 Codex 保存密码、令牌、私钥或临时闲聊。外部 Codex 历史没有官方可靠导出接口，因此不会伪造自动导入；需要跨设备复用的结论通过 <code>save_memory</code> 明确沉淀。</p>
    </section>
  </div>
</template>

<style scoped>
.memory-panel { display: grid; gap: 14px; }
.s-h1 { font-size: 18px; font-weight: 700; }
.card { background: var(--wao-bg-card); border: 1px solid var(--wao-line); border-radius: 12px; padding: 16px 18px; }
.card-title { color: var(--wao-text); font-size: 14px; font-weight: 650; }
.memory-overview { background: linear-gradient(135deg, color-mix(in srgb, var(--wao-primary-soft) 62%, var(--wao-bg-card)), var(--wao-bg-card)); }
.memory-title { display: flex; align-items: center; gap: 11px; }
.memory-title > span { width: 38px; height: 38px; display: grid; place-items: center; border-radius: 10px; background: var(--wao-primary); color: white; font-size: 20px; }
.memory-title b, .memory-title small { display: block; }
.memory-title b { color: var(--wao-text); font-size: 15px; }
.memory-title small { margin-top: 2px; color: var(--wao-text-weak); font-size: 11.5px; }
.memory-flow { margin-top: 15px; display: flex; flex-wrap: wrap; align-items: center; gap: 7px; }
.memory-flow span { padding: 5px 9px; border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-2); font-size: 11.5px; }
.memory-flow i { color: var(--wao-text-faint); }
.memory-overview p, .token-note, .safety-note { margin: 11px 0 0; color: var(--wao-text-weak); font-size: 12px; line-height: 1.7; }
.token-input { margin-top: 12px; display: grid; grid-template-columns: 1fr auto; gap: 8px; }
.token-input input { min-width: 0; height: 36px; box-sizing: border-box; padding: 0 11px; border: 1px solid var(--wao-line-control); border-radius: 8px; outline: none; background: var(--wao-bg-card); color: var(--wao-text); }
.token-input input:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 3px var(--wao-primary-soft); }
.btn-primary { height: 36px; padding: 0 14px; display: inline-flex; align-items: center; gap: 6px; border: 0; border-radius: 8px; background: var(--wao-primary); color: white; cursor: pointer; }
.btn-primary:disabled { opacity: .55; cursor: not-allowed; }
.fresh { margin-top: 12px; padding: 11px 12px; border: 1px solid color-mix(in srgb, var(--wao-success) 38%, var(--wao-line)); border-radius: 9px; background: var(--wao-success-bg); }
.fresh-head { color: var(--wao-success-strong); font-size: 12px; }
.fresh-row { margin-top: 8px; display: flex; align-items: center; gap: 8px; }
.fresh-row code { flex: 1; min-width: 0; overflow: hidden; color: var(--wao-text); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.fresh-row button, .command-row button { flex: none; min-height: 29px; padding: 0 10px; border: 1px solid var(--wao-line-strong); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.token-list { margin-top: 12px; border-top: 1px solid var(--wao-divider-2); }
.token-row { padding: 11px 0; display: flex; align-items: center; gap: 10px; border-bottom: 1px solid var(--wao-divider-2); }
.device-icon { width: 31px; height: 31px; flex: none; display: grid; place-items: center; border-radius: 8px; background: var(--wao-primary-soft); color: var(--wao-primary); }
.token-meta { flex: 1; min-width: 0; display: grid; gap: 3px; }
.token-meta b { color: var(--wao-text); font-size: 12.5px; }
.token-meta small { overflow: hidden; color: var(--wao-text-faint); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.revoke { border: 0; background: none; color: var(--wao-danger); cursor: pointer; }
.grant { border: 0; background: none; color: var(--wao-primary-text); cursor: pointer; }
.grant-options { margin-top: 12px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.grant-options label { display: flex; align-items: flex-start; gap: 8px; padding: 10px; border: 1px solid var(--wao-line); border-radius: 8px; }
.grant-options span { display: grid; gap: 2px; }
.grant-options b { font-size: 11.5px; }
.grant-options small { color: var(--wao-text-weak); font-size: 9.5px; }
.grant-actions { margin-top: 12px; display: flex; justify-content: flex-end; gap: 8px; }
.grant-actions > button:first-child { border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); }
.memory-empty, .memory-error { padding: 16px 4px 4px; color: var(--wao-text-weak); font-size: 12px; text-align: center; }
.memory-error { color: var(--wao-danger); }
.memory-error button { margin-left: 8px; border: 0; background: none; color: var(--wao-primary); cursor: pointer; }
.setup-steps { margin: 13px 0; padding-left: 22px; color: var(--wao-text-2); }
.setup-steps li { margin: 8px 0; padding-left: 3px; font-size: 12px; }
.setup-steps b, .setup-steps span { display: block; }
.setup-steps span { margin-top: 2px; color: var(--wao-text-weak); }
.command-row { padding: 10px 11px; display: flex; align-items: center; gap: 10px; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-sub); }
.command-row code, .command-row pre { flex: 1; min-width: 0; overflow-x: auto; color: var(--wao-text); font-size: 11.5px; white-space: pre-wrap; word-break: break-all; }
details { margin-top: 10px; }
summary { color: var(--wao-primary); font-size: 12px; cursor: pointer; }
.command-row.multiline { margin-top: 8px; align-items: flex-start; }
.platform-notes { margin-top: 12px; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.platform-notes span { min-width: 0; padding: 9px 10px; display: grid; gap: 5px; border: 1px solid var(--wao-line); border-radius: 8px; }
.platform-notes b { color: var(--wao-text-2); font-size: 11.5px; }
.platform-notes code { overflow-x: auto; color: var(--wao-text-weak); font-size: 10.5px; white-space: nowrap; }
.verify-grid { margin-top: 12px; display: grid; grid-template-columns: repeat(3, 1fr); gap: 9px; }
.verify-grid span { padding: 12px; display: grid; gap: 5px; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-sub); }
.verify-grid i { color: var(--wao-primary); font-size: 18px; }
.verify-grid b { color: var(--wao-text); font-size: 12.5px; }
.verify-grid small { color: var(--wao-text-weak); font-size: 11px; line-height: 1.6; }
.safety-note { padding-top: 11px; border-top: 1px solid var(--wao-divider-2); }
.safety-note i { margin-right: 5px; color: var(--wao-success-strong); }
.spin { animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) {
  .platform-notes, .verify-grid { grid-template-columns: 1fr; }
  .grant-options { grid-template-columns: 1fr; }
  .token-input { grid-template-columns: 1fr; }
  .fresh-row, .command-row { align-items: stretch; flex-direction: column; }
  .fresh-row button, .command-row button { min-height: 34px; }
}
</style>
