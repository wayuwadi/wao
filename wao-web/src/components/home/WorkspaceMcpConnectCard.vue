<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  createWorkspaceMcpToken,
  fetchWorkspaceMcpTokens,
  revokeWorkspaceMcpToken,
  type WorkspaceMcpToken,
} from '@/api/workspaceMcp'

const opened = ref(false)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const tokens = ref<WorkspaceMcpToken[]>([])
const canWrite = ref(true)
const secret = ref('')
const tokenCopied = ref(false)
const issuedCanWrite = ref(false)
const endpoint = computed(() => `${window.location.origin}/api/mcp/workspace`)
const instruction = computed(() => `请将以下 WAO MCP Server 添加到当前 MCP Client，并完成连接验证。

服务名称：WAO
传输方式：Streamable HTTP
服务地址：${endpoint.value}
认证方式：Bearer Token
Token：${secret.value}

配置要求：
- 优先使用客户端的 Bearer Token 配置项，仅填入 Token 值；如需通过请求头配置，使用 Authorization: Bearer <Token>。
- 使用当前客户端支持的安全凭据配置，不得将 Token 写入项目代码、版本库或日志，也不要在回复中重复展示完整 Token。
- 如果无法自动配置，请提供适配当前客户端的配置片段、文件位置和生效方式；配置片段中的 Token 使用占位符。
- 这枚令牌${issuedCanWrite.value ? '可读写' : '只读'}，不得尝试调用超出 tools/list 实际返回的工具。

配置完成后，请连接服务并调用 tools/list 验证，报告连接结果和实际可用工具。

验证成功后，仅根据实际可用的工具推荐 3 个 WAO 项目、未完成任务与知识库场景。每个场景简要说明场景名称、使用的工具和带来的价值。不得推荐无权使用的能力，不得虚构工具。`)

async function open() {
  opened.value = true
  error.value = ''
  loading.value = true
  try { tokens.value = await fetchWorkspaceMcpTokens() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '令牌列表读取失败' }
  finally { loading.value = false }
}

async function quickGenerate() {
  if (busy.value || loading.value) return
  busy.value = true
  await open()
  busy.value = false
  await generate()
}

function close() {
  opened.value = false
  secret.value = ''
  tokenCopied.value = false
  error.value = ''
}

async function generate() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  secret.value = ''
  tokenCopied.value = false
  try {
    const name = `首页快捷连接 · ${new Date().toLocaleString('zh-CN', { hour12: false })}`
    const created = await createWorkspaceMcpToken(name, canWrite.value)
    tokens.value.unshift(created.token)
    secret.value = created.secret
    issuedCanWrite.value = created.token.canWrite
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '生成连接令牌失败' }
  finally { busy.value = false }
}

async function copyInstruction() {
  try { await navigator.clipboard.writeText(instruction.value) }
  catch { error.value = '复制失败，请手动选择并复制连接指令' }
}

async function copyToken() {
  if (!secret.value) return
  try {
    await navigator.clipboard.writeText(secret.value)
    tokenCopied.value = true
  } catch { error.value = '复制令牌失败，请从下方指令的 Token 行手动复制令牌值' }
}

async function revoke(token: WorkspaceMcpToken) {
  if (busy.value || !window.confirm(`吊销“${token.name}”？已连接的 AI 会立即失去访问权限。`)) return
  busy.value = true
  error.value = ''
  try {
    await revokeWorkspaceMcpToken(token.id)
    tokens.value = tokens.value.filter((item) => item.id !== token.id)
    if (secret.value && token.tokenPrefix === secret.value.slice(0, token.tokenPrefix.length)) secret.value = ''
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '吊销失败' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="mcp-card" aria-label="WAO MCP 快捷连接">
    <div class="card-copy"><span class="eyebrow">MCP · AI 连接</span><h2>让 AI 使用你的 WAO</h2><p>连接项目、未完成任务与知识库；点击后生成一次性认证令牌和完整连接指令。</p></div>
    <div class="card-actions"><label><input v-model="canWrite" type="checkbox" :disabled="busy || !!secret" />允许写入</label><button type="button" class="primary" :disabled="busy || loading" @click="quickGenerate"><i class="ri-plug-line"></i>生成连接指令</button><button type="button" class="manage" @click="open">管理连接</button></div>
  </section>

  <div v-if="opened" class="mcp-mask" @click.self="close">
    <section class="mcp-dialog" role="dialog" aria-modal="true" aria-labelledby="workspace-mcp-title">
      <header><div><span class="eyebrow">WAO MCP</span><h2 id="workspace-mcp-title">连接 AI 客户端</h2></div><button type="button" aria-label="关闭" @click="close"><i class="ri-close-line"></i></button></header>
      <p>每台设备用独立令牌。令牌仅在创建时显示一次，关闭窗口后只能重新生成；可随时在这里吊销。知识库沿用 WAO 当前的成员共享范围。</p>
      <label class="permission"><input v-model="canWrite" type="checkbox" :disabled="busy || !!secret" /><span><b>允许 AI 写入</b><small>可创建和更新项目、任务、知识库与文档。关闭后只提供读取工具。</small></span></label>
      <button type="button" class="primary generate" :disabled="busy" @click="generate">{{ busy ? '生成中…' : '生成连接指令' }}</button>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <div v-if="secret" class="instruction"><strong>仅此一次显示完整令牌</strong><p>连接 WAO CLI 时只复制令牌；连接其他 AI 客户端可复制完整指令。请勿发到公开对话或提交到代码仓库。</p><textarea readonly :value="instruction" aria-label="WAO MCP 连接指令" /><div class="instruction-actions"><button type="button" class="primary" @click="copyToken">{{ tokenCopied ? '令牌已复制' : '复制令牌（用于 CLI）' }}</button><button type="button" @click="copyInstruction">复制授权指令</button></div></div>
      <div class="existing"><h3>已创建的连接</h3><span v-if="loading">正在读取…</span><span v-else-if="!tokens.length">暂无连接</span><div v-for="token in tokens" :key="token.id" class="token"><div><b>{{ token.name }}</b><small>{{ token.canWrite ? '可读写' : '只读' }} · {{ token.tokenPrefix }}••••</small></div><button type="button" :disabled="busy" @click="revoke(token)">吊销</button></div></div>
    </section>
  </div>
</template>

<style scoped>
.mcp-card{grid-column:1/-1;display:flex;align-items:center;justify-content:space-between;gap:18px;padding:17px 20px;border:1px solid var(--wao-line);border-radius:13px;background:linear-gradient(110deg,var(--wao-bg-card),var(--wao-primary-soft))}.mcp-card h2{margin:3px 0;font-size:16px}.mcp-card p,.mcp-dialog p{margin:0;color:var(--wao-text-3);font-size:12px;line-height:1.6}.card-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.card-actions label{display:flex;align-items:center;gap:4px;color:var(--wao-text-2);font-size:11px;white-space:nowrap}.manage{border:1px solid var(--wao-line-control);border-radius:9px;padding:8px 10px;background:var(--wao-bg-card);color:var(--wao-text-2);white-space:nowrap;cursor:pointer}.eyebrow{color:var(--wao-primary-text);font-size:10px;font-weight:800;letter-spacing:.1em}.primary{border:0;border-radius:9px;padding:9px 13px;background:var(--wao-primary);color:white;white-space:nowrap;cursor:pointer}.primary:disabled{opacity:.55}.mcp-mask{position:fixed;inset:0;z-index:2000;display:grid;place-items:center;padding:16px;background:rgba(10,20,40,.54)}.mcp-dialog{width:min(700px,100%);max-height:calc(100dvh - 32px);overflow:auto;box-sizing:border-box;padding:20px;border-radius:15px;background:var(--wao-bg-card);color:var(--wao-text);box-shadow:var(--wao-shadow-lg)}.mcp-dialog header{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:10px}.mcp-dialog h2{margin:3px 0;font-size:20px}.mcp-dialog header button{border:0;background:none;color:var(--wao-text-2);font-size:20px;cursor:pointer}.permission{display:flex;gap:10px;margin:16px 0;padding:12px;border:1px solid var(--wao-line);border-radius:10px}.permission span{display:grid;gap:3px}.permission small{color:var(--wao-text-3)}.generate{margin-bottom:12px}.error{color:var(--wao-danger-strong)!important}.instruction{margin:10px 0;padding:14px;border:1px solid var(--wao-success-line);border-radius:10px;background:var(--wao-success-bg)}.instruction strong{font-size:12px}.instruction textarea{box-sizing:border-box;width:100%;height:270px;margin:10px 0;padding:10px;border:1px solid var(--wao-line-control);border-radius:8px;resize:vertical;background:var(--wao-bg-card);color:var(--wao-text);font:11px/1.6 ui-monospace,monospace}.existing{display:grid;gap:8px;margin-top:18px}.existing h3{margin:0;font-size:13px}.token{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:9px 0;border-top:1px solid var(--wao-line)}.token div{display:grid;gap:3px;min-width:0}.token b{font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.token small{color:var(--wao-text-weak);font-size:10px}.token button{border:0;background:none;color:var(--wao-danger-strong);cursor:pointer}@media(max-width:600px){.mcp-card{align-items:flex-start;flex-direction:column}.mcp-dialog{padding:14px}}
.mcp-card{grid-column:1/-1;display:flex;align-items:center;justify-content:space-between;gap:18px;padding:17px 20px;border:1px solid var(--wao-line);border-radius:13px;background:linear-gradient(110deg,var(--wao-bg-card),var(--wao-primary-soft))}.mcp-card h2{margin:3px 0;font-size:16px}.mcp-card p,.mcp-dialog p{margin:0;color:var(--wao-text-3);font-size:12px;line-height:1.6}.card-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.card-actions label{display:flex;align-items:center;gap:4px;color:var(--wao-text-2);font-size:11px;white-space:nowrap}.manage{border:1px solid var(--wao-line-control);border-radius:9px;padding:8px 10px;background:var(--wao-bg-card);color:var(--wao-text-2);white-space:nowrap;cursor:pointer}.eyebrow{color:var(--wao-primary-text);font-size:10px;font-weight:800;letter-spacing:.1em}.primary{border:0;border-radius:9px;padding:9px 13px;background:var(--wao-primary);color:white;white-space:nowrap;cursor:pointer}.primary:disabled{opacity:.55}.mcp-mask{position:fixed;inset:0;z-index:2000;display:grid;place-items:center;padding:16px;background:rgba(10,20,40,.54)}.mcp-dialog{width:min(700px,100%);max-height:calc(100dvh - 32px);overflow:auto;box-sizing:border-box;padding:20px;border-radius:15px;background:var(--wao-bg-card);color:var(--wao-text);box-shadow:var(--wao-shadow-lg)}.mcp-dialog header{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:10px}.mcp-dialog h2{margin:3px 0;font-size:20px}.mcp-dialog header button{border:0;background:none;color:var(--wao-text-2);font-size:20px;cursor:pointer}.permission{display:flex;gap:10px;margin:16px 0;padding:12px;border:1px solid var(--wao-line);border-radius:10px}.permission span{display:grid;gap:3px}.permission small{color:var(--wao-text-3)}.generate{margin-bottom:12px}.error{color:var(--wao-danger-strong)!important}.instruction{margin:10px 0;padding:14px;border:1px solid var(--wao-success-line);border-radius:10px;background:var(--wao-success-bg)}.instruction strong{font-size:12px}.instruction textarea{box-sizing:border-box;width:100%;height:270px;margin:10px 0;padding:10px;border:1px solid var(--wao-line-control);border-radius:8px;resize:vertical;background:var(--wao-bg-card);color:var(--wao-text);font:11px/1.6 ui-monospace,monospace}.instruction-actions{display:flex;gap:8px;flex-wrap:wrap}.instruction-actions button{border:1px solid var(--wao-line-control);border-radius:9px;padding:9px 13px;background:var(--wao-bg-card);color:var(--wao-text-2);cursor:pointer}.instruction-actions .primary{border:0;color:white}.existing{display:grid;gap:8px;margin-top:18px}.existing h3{margin:0;font-size:13px}.token{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:9px 0;border-top:1px solid var(--wao-line)}.token div{display:grid;gap:3px;min-width:0}.token b{font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.token small{color:var(--wao-text-weak);font-size:10px}.token button{border:0;background:none;color:var(--wao-danger-strong);cursor:pointer}@media(max-width:600px){.mcp-card{align-items:flex-start;flex-direction:column}.mcp-dialog{padding:14px}}
</style>
