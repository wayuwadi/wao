<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchWorkspaceMcpTokens, type WorkspaceMcpToken } from '@/api/workspaceMcp'
import { connectionState } from '@/utils/capabilityCenter'

const props = defineProps<{ token: WorkspaceMcpToken; clientType?: 'CLI' | 'MCP' }>()
const emit = defineEmits<{ refreshed: [token: WorkspaceMcpToken] }>()
const busy = ref(false),
  error = ref(''),
  notice = ref('')
const latest = ref<WorkspaceMcpToken | null>(null)
const current = computed(() => latest.value ?? props.token)
const state = computed(() => connectionState(current.value))
const endpoint = `${location.origin}/api/mcp/workspace`
const cli = computed(() => (current.value.clientType ?? props.clientType) === 'CLI')
const cliInstruction = computed(() =>
  ['wao auth login', 'wao auth status', 'wao tools list']
    .map((command) => `${command} --url ${endpoint}`)
    .join('\n'),
)
let generation = 0
watch(
  () => props.token,
  (next, previous) => {
    latest.value = null
    if (next.id === previous.id) return
    generation++
    error.value = ''
    notice.value = ''
    busy.value = false
  },
)
async function refresh() {
  if (busy.value) return
  const ticket = ++generation
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const tokens = await fetchWorkspaceMcpTokens()
    if (ticket !== generation) return
    const token = tokens.find((token) => token.id === props.token.id)
    if (!token) {
      error.value = '该连接已吊销或当前不可访问，请刷新连接列表。'
      return
    }
    latest.value = token
    emit('refreshed', token)
    notice.value =
      connectionState(token) === '已验证'
        ? '已确认客户端成功读取工具目录。'
        : connectionState(token) === '验证失败'
          ? '最近的工具目录读取失败，请按下方步骤重试。'
          : '尚未收到该连接的工具目录读取结果，请先在客户端完成接入。'
  } catch (cause) {
    if (ticket === generation)
      error.value = cause instanceof Error ? cause.message : '验证状态读取失败，请重试。'
  } finally {
    if (ticket === generation) busy.value = false
  }
}
onBeforeUnmount(() => {
  generation++
})
</script>

<template>
  <section class="cap-setup" aria-label="接入与验证">
    <div class="cap-section-heading">
      <h3>接入与验证</h3>
      <span class="cap-badge">{{ state }}</span>
    </div>
    <ol class="cap-setup-steps">
      <li>
        <strong>1 · 授权已签发</strong><small>使用“{{ token.name }}”的令牌接入。</small>
      </li>
      <li>
        <strong>2 · 接入客户端</strong
        ><small>{{
          cli ? '在终端登录 WAO CLI。' : '配置 Streamable HTTP 地址与 Bearer 令牌。'
        }}</small>
      </li>
      <li>
        <strong>3 · 读取工具目录</strong
        ><small>{{
          current.lastVerifiedAt ? `最近成功：${current.lastVerifiedAt}` : '完成后刷新验证状态。'
        }}</small>
      </li>
    </ol>
    <code v-if="cli">{{ cliInstruction }}</code>
    <div v-else>
      <code>{{ endpoint }}</code>
      <p class="cap-muted">
        认证：Authorization: Bearer &lt;该连接令牌&gt;。在 AI 客户端连接后，执行 initialize 与
        tools/list。
      </p>
    </div>
    <p v-if="current.lastVerifiedAt" class="cap-muted">
      最近成功读取 {{ current.verifiedToolCount ?? '—' }} 项 MCP 工具。此记录表示当时验证成功。
    </p>
    <p v-if="state === '验证失败'" role="alert" class="cap-error">
      工具目录读取失败：{{ current.lastVerificationFailedAt }}。请重新执行验证，成功后会更新状态。
    </p>
    <div class="cap-actions">
      <button type="button" class="cap-button primary" :disabled="busy" @click="refresh">
        {{ busy ? '读取中…' : '刷新验证状态' }}
      </button>
    </div>
    <p v-if="error" role="alert" class="cap-error">{{ error }}</p>
    <p v-if="notice" role="status" class="cap-muted">{{ notice }}</p>
    <details>
      <summary>接入失败时怎么处理</summary>
      <p class="cap-muted">
        连接失败时核对地址与网络；令牌无效时核对是否使用此连接的令牌、是否已吊销；工具缺失时查看本连接的逐项授权。CLI
        可运行 wao doctor 检查。处理后重新读取工具目录，再刷新状态。
      </p>
    </details>
  </section>
</template>

<style scoped>
.cap-setup {
  border: 1px solid var(--wao-line);
  background: var(--wao-bg-sub);
  border-radius: 12px;
  padding: 14px;
  margin: 18px 0;
}
.cap-setup .cap-section-heading {
  margin: 0 0 12px;
}
.cap-setup-steps {
  list-style: none;
  padding: 0;
  margin: 0 0 12px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.cap-setup-steps strong {
  font-size: 12px;
}
.cap-setup-steps small {
  display: block;
  color: var(--wao-text-3);
  margin-top: 5px;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.cap-setup summary {
  cursor: pointer;
  font-size: 12px;
  color: var(--wao-primary-text);
  margin-top: 10px;
}
@media (max-width: 650px) {
  .cap-setup-steps {
    grid-template-columns: 1fr;
  }
}
</style>
