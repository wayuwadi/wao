<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  createAccountResource,
  deleteAccountResource,
  fetchAccountResourceIntegration,
  fetchAccountResources,
  fetchVerificationMessages,
  revealAccountResourceSecret,
  retryVerificationForward,
  rotateAccountResourceToken,
  saveAccountResourceIntegration,
  updateAccountResource,
  type AccountResource,
  type AccountResourceIntegration,
  type SaveAccountResource,
  type VerificationMessage,
} from '@/api/accountResources'
import { fetchWebhookEndpoints, type FeishuWebhookEndpoint } from '@/api/system'
import { toast } from '@/composables/toast'
import { copyText } from '@/utils/clipboard'

type Tab = 'accounts' | 'messages' | 'integration'
interface Editor extends SaveAccountResource {
  id: string | null
}

const tab = ref<Tab>('accounts')
const loading = ref(true)
const loadError = ref('')
const accounts = ref<AccountResource[]>([])
const messages = ref<VerificationMessage[]>([])
const integration = ref<AccountResourceIntegration | null>(null)
const webhooks = ref<FeishuWebhookEndpoint[]>([])
const query = ref('')
const editor = ref<Editor | null>(null)
const baseline = ref('')
const saving = ref(false)
const saveError = ref('')
const deleting = ref(false)
const revealed = reactive<Record<string, string | null | undefined>>({})
const revealing = ref<string | null>(null)
const retrying = ref<string | null>(null)
const integrationSaving = ref(false)
const integrationError = ref('')
const rotatedToken = ref<{ type: 'ingest' | 'crawler'; token: string } | null>(null)
const rotating = ref<'ingest' | 'crawler' | null>(null)

const filteredAccounts = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return accounts.value
  return accounts.value.filter((item) =>
    [item.platform, item.accountLabel, item.loginIdentifier, item.phoneNumber, item.notes]
      .join(' ')
      .toLowerCase()
      .includes(needle),
  )
})
const editorSnapshot = computed(() => (editor.value ? JSON.stringify(editor.value) : ''))
const dirty = computed(() => !!editor.value && editorSnapshot.value !== baseline.value)
const canSave = computed(
  () => !!editor.value?.platform.trim() && !!editor.value?.accountLabel.trim() && !saving.value,
)
const enabledWebhooks = computed(() => webhooks.value.filter((item) => item.enabled && item.urlSet))
const baseUrl = computed(() => window.location.origin)

function draftKey(id: string | null) {
  return `wao-account-resource-draft:${id ?? 'new'}`
}

watch(
  editorSnapshot,
  (value) => {
    if (!editor.value || !dirty.value) return
    localStorage.setItem(draftKey(editor.value.id), value)
  },
  { flush: 'post' },
)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [accountResult, messageResult, integrationResult, webhookResult] = await Promise.all([
      fetchAccountResources(),
      fetchVerificationMessages(),
      fetchAccountResourceIntegration(),
      fetchWebhookEndpoints(),
    ])
    accounts.value = accountResult
    messages.value = messageResult
    integration.value = integrationResult
    webhooks.value = webhookResult
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '账号资源库加载失败，请重试'
  } finally {
    loading.value = false
  }
}

function emptyEditor(): Editor {
  return {
    id: null,
    platform: '',
    accountLabel: '',
    loginIdentifier: '',
    phoneNumber: '',
    notes: '',
    secret: '',
    enabled: true,
    revision: null,
  }
}

function restoreDraft(value: Editor) {
  const stored = localStorage.getItem(draftKey(value.id))
  if (!stored) return value
  try {
    const parsed = JSON.parse(stored) as Editor
    if (parsed.id === value.id && parsed.revision === value.revision) return parsed
  } catch {
    localStorage.removeItem(draftKey(value.id))
  }
  return value
}

function openCreate() {
  const value = restoreDraft(emptyEditor())
  editor.value = value
  baseline.value = JSON.stringify(emptyEditor())
  saveError.value = ''
}

function openEdit(item: AccountResource) {
  const clean: Editor = {
    id: item.id,
    platform: item.platform,
    accountLabel: item.accountLabel,
    loginIdentifier: item.loginIdentifier,
    phoneNumber: item.phoneNumber,
    notes: item.notes,
    secret: '',
    enabled: item.enabled,
    revision: item.revision,
  }
  editor.value = restoreDraft(clean)
  baseline.value = JSON.stringify(clean)
  saveError.value = ''
}

function closeEditor(force = false) {
  if (!force && dirty.value && !window.confirm('未保存内容已保存在本机草稿，仍要关闭吗？')) return
  editor.value = null
  saveError.value = ''
}

async function save() {
  if (!editor.value || !canSave.value) return
  saving.value = true
  saveError.value = ''
  const body: SaveAccountResource = {
    platform: editor.value.platform.trim(),
    accountLabel: editor.value.accountLabel.trim(),
    loginIdentifier: editor.value.loginIdentifier.trim(),
    phoneNumber: editor.value.phoneNumber.trim(),
    notes: editor.value.notes.trim(),
    secret: editor.value.secret?.trim() || undefined,
    enabled: editor.value.enabled,
    revision: editor.value.revision,
  }
  try {
    const saved = editor.value.id
      ? await updateAccountResource(editor.value.id, body)
      : await createAccountResource(body)
    const index = accounts.value.findIndex((item) => item.id === saved.id)
    if (index >= 0) accounts.value[index] = saved
    else accounts.value.unshift(saved)
    localStorage.removeItem(draftKey(editor.value.id))
    toast(editor.value.id ? '账号资源已保存' : '账号资源已创建', 'ri-check-line')
    closeEditor(true)
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!editor.value?.id || editor.value.revision == null) return
  if (!window.confirm(`删除账号资源“${editor.value.accountLabel}”？验证码历史会保留。`)) return
  deleting.value = true
  saveError.value = ''
  try {
    await deleteAccountResource(editor.value.id, editor.value.revision)
    accounts.value = accounts.value.filter((item) => item.id !== editor.value?.id)
    localStorage.removeItem(draftKey(editor.value.id))
    closeEditor(true)
    toast('账号资源已删除', 'ri-delete-bin-line')
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : '删除失败，请重试'
  } finally {
    deleting.value = false
  }
}

async function reveal(item: AccountResource) {
  if (!item.hasSecret) return toast('该账号未设置密码', 'ri-lock-line')
  if (revealed[item.id] !== undefined) {
    delete revealed[item.id]
    return
  }
  revealing.value = item.id
  try {
    revealed[item.id] = (await revealAccountResourceSecret(item.id)).secret
  } catch (error) {
    toast(error instanceof Error ? error.message : '密码读取失败', 'ri-error-warning-line')
  } finally {
    revealing.value = null
  }
}

async function copySecret(item: AccountResource) {
  if (revealed[item.id] === undefined) await reveal(item)
  const secret = revealed[item.id]
  if (secret) copyText(secret, '密码已复制')
}

async function refreshMessages() {
  try {
    messages.value = await fetchVerificationMessages()
    toast('验证码记录已刷新', 'ri-refresh-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '验证码记录刷新失败', 'ri-error-warning-line')
  }
}

async function retryForward(message: VerificationMessage) {
  retrying.value = message.id
  try {
    const updated = await retryVerificationForward(message.id)
    const index = messages.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) messages.value[index] = updated
    toast(updated.forwardStatus === 'SENT' ? '已转发到飞书' : updated.forwardError)
  } catch (error) {
    toast(error instanceof Error ? error.message : '转发失败，请重试', 'ri-error-warning-line')
  } finally {
    retrying.value = null
  }
}

async function saveIntegration() {
  if (!integration.value) return
  integrationSaving.value = true
  integrationError.value = ''
  try {
    integration.value = await saveAccountResourceIntegration(
      integration.value.notificationWebhookId,
    )
    toast('验证码转发目标已保存', 'ri-check-line')
  } catch (error) {
    integrationError.value = error instanceof Error ? error.message : '设置保存失败，请重试'
  } finally {
    integrationSaving.value = false
  }
}

async function rotate(type: 'ingest' | 'crawler') {
  const label = type === 'ingest' ? '短信入站' : '爬虫只读取码'
  if (!window.confirm(`轮换${label}令牌后，旧令牌立即失效。继续吗？`)) return
  rotating.value = type
  integrationError.value = ''
  try {
    rotatedToken.value = await rotateAccountResourceToken(type)
    integration.value = await fetchAccountResourceIntegration()
  } catch (error) {
    integrationError.value = error instanceof Error ? error.message : '令牌轮换失败，请重试'
  } finally {
    rotating.value = null
  }
}

function formatTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <main class="account-page">
    <header class="hero">
      <div>
        <p class="eyebrow">ACCOUNT OPERATIONS</p>
        <h1>账号资源库</h1>
        <p>
          统一保存组织账号与登录手机号；手机短信入站后加密留存、转发飞书，爬虫再按手机号读取最近验证码。
        </p>
      </div>
      <button class="primary" type="button" @click="openCreate">
        <i class="ri-add-line"></i>新增账号
      </button>
    </header>

    <aside class="boundary">
      <i class="ri-shield-keyhole-line"></i>
      <div>
        <b>敏感数据分层保护</b>
        <span
          >密码、短信正文和验证码均由服务端 AES-256-GCM
          加密；手机入站与爬虫取码使用两枚独立令牌，令牌只显示一次。</span
        >
      </div>
    </aside>

    <nav class="tabs" aria-label="账号资源库功能">
      <button :class="{ on: tab === 'accounts' }" @click="tab = 'accounts'">
        账号资源 <b>{{ accounts.length }}</b>
      </button>
      <button :class="{ on: tab === 'messages' }" @click="tab = 'messages'">
        验证码记录 <b>{{ messages.length }}</b>
      </button>
      <button :class="{ on: tab === 'integration' }" @click="tab = 'integration'">接入设置</button>
    </nav>

    <section v-if="loading" class="state-card" aria-live="polite">
      <i class="ri-loader-4-line spin"></i><span>正在读取账号与验证码记录…</span>
    </section>
    <section v-else-if="loadError" class="state-card error" role="alert">
      <i class="ri-error-warning-line"></i><span>{{ loadError }}</span>
      <button type="button" @click="load">重试</button>
    </section>

    <template v-else-if="tab === 'accounts'">
      <label class="search">
        <i class="ri-search-line"></i>
        <input v-model="query" type="search" placeholder="搜索平台、账号、手机号或备注" />
      </label>
      <section v-if="!accounts.length" class="state-card empty">
        <i class="ri-contacts-book-2-line"></i><b>还没有账号资源</b>
        <span>新增第一个真实账号后，验证码可以按登录手机号自动匹配。</span>
        <button type="button" @click="openCreate">新增账号</button>
      </section>
      <section v-else-if="!filteredAccounts.length" class="state-card empty">
        <i class="ri-search-eye-line"></i><b>没有匹配的账号</b><span>换一个关键词试试。</span>
      </section>
      <section v-else class="account-grid">
        <article v-for="item in filteredAccounts" :key="item.id" class="account-card">
          <header>
            <span class="platform">{{ item.platform }}</span>
            <span class="status" :class="{ enabled: item.enabled }">{{
              item.enabled ? '可用' : '停用'
            }}</span>
          </header>
          <h2>{{ item.accountLabel }}</h2>
          <dl>
            <div>
              <dt>登录账号</dt>
              <dd>{{ item.loginIdentifier || '未填写' }}</dd>
            </div>
            <div>
              <dt>手机号</dt>
              <dd class="mono">{{ item.phoneNumber || '未绑定' }}</dd>
            </div>
            <div>
              <dt>密码</dt>
              <dd class="secret">
                {{ revealed[item.id] ?? (item.hasSecret ? '••••••••' : '未设置') }}
              </dd>
            </div>
          </dl>
          <p>{{ item.notes || '暂无备注' }}</p>
          <footer>
            <small>版本 {{ item.revision }} · {{ formatTime(item.updatedAt) }}</small>
            <div>
              <button type="button" :disabled="revealing === item.id" @click="reveal(item)">
                {{
                  revealed[item.id] !== undefined
                    ? '隐藏'
                    : revealing === item.id
                      ? '读取中…'
                      : '查看密码'
                }}
              </button>
              <button type="button" :disabled="!item.hasSecret" @click="copySecret(item)">
                复制
              </button>
              <button type="button" @click="openEdit(item)">编辑</button>
            </div>
          </footer>
        </article>
      </section>
    </template>

    <template v-else-if="tab === 'messages'">
      <div class="section-tools">
        <div>
          <h2>最近验证码</h2>
          <p>只展示最近 100 条真实入站；爬虫默认只能取得五分钟内的最近验证码。</p>
        </div>
        <button type="button" @click="refreshMessages"><i class="ri-refresh-line"></i>刷新</button>
      </div>
      <section v-if="!messages.length" class="state-card empty">
        <i class="ri-message-3-line"></i><b>还没有短信入站</b>
        <span>在“接入设置”生成令牌并配置手机转发器后，真实验证码会出现在这里。</span>
        <button type="button" @click="tab = 'integration'">前往接入设置</button>
      </section>
      <section v-else class="message-list">
        <article v-for="message in messages" :key="message.id">
          <div class="code" :class="{ missing: !message.code }">{{ message.code || '未识别' }}</div>
          <div class="message-main">
            <header>
              <b class="mono">{{ message.phoneNumber }}</b>
              <span v-if="message.accountLabels.length">{{
                message.accountLabels.join('、')
              }}</span>
              <time>{{ formatTime(message.receivedAt) }}</time>
            </header>
            <p>{{ message.message }}</p>
            <small
              >{{ message.sender || '未知发送方'
              }}<template v-if="message.source"> · {{ message.source }}</template></small
            >
          </div>
          <div class="forward" :class="message.forwardStatus.toLowerCase()">
            <span>{{
              message.forwardStatus === 'SENT'
                ? '已转发'
                : message.forwardStatus === 'FAILED'
                  ? '转发失败'
                  : '待转发'
            }}</span>
            <button
              v-if="message.forwardStatus !== 'SENT'"
              type="button"
              :disabled="retrying === message.id"
              :title="message.forwardError"
              @click="retryForward(message)"
            >
              {{ retrying === message.id ? '重试中…' : '重试' }}
            </button>
          </div>
        </article>
      </section>
    </template>

    <template v-else-if="integration">
      <section class="integration-grid">
        <article class="setup-card">
          <div class="setup-number">1</div>
          <h2>选择飞书转发群</h2>
          <p>选择“设置 → Webhook配置”中已启用的机器人；留空则使用系统默认机器人。</p>
          <label>
            <span>飞书机器人</span>
            <select v-model="integration.notificationWebhookId">
              <option value="">系统默认机器人</option>
              <option v-for="item in enabledWebhooks" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select>
          </label>
          <button
            class="primary"
            type="button"
            :disabled="integrationSaving"
            @click="saveIntegration"
          >
            {{ integrationSaving ? '保存中…' : '保存转发目标' }}
          </button>
        </article>

        <article class="setup-card">
          <div class="setup-number">2</div>
          <h2>连接手机短信转发器</h2>
          <p>手机向下方 HTTPS 地址提交标准 JSON；请求必须携带独立入站 Bearer 令牌。</p>
          <code>{{ baseUrl }}{{ integration.ingestPath }}</code>
          <div class="token-state" :class="{ ready: integration.ingestTokenConfigured }">
            {{ integration.ingestTokenConfigured ? '入站令牌已配置' : '尚未生成入站令牌' }}
          </div>
          <button type="button" :disabled="rotating === 'ingest'" @click="rotate('ingest')">
            {{
              rotating === 'ingest'
                ? '生成中…'
                : integration.ingestTokenConfigured
                  ? '轮换入站令牌'
                  : '生成入站令牌'
            }}
          </button>
        </article>

        <article class="setup-card">
          <div class="setup-number">3</div>
          <h2>让爬虫按手机号取码</h2>
          <p>爬虫使用只读令牌请求最近验证码，不需要账号密码、短信入站令牌或 WAO 登录会话。</p>
          <code>{{ baseUrl }}/api/public/account-resources/codes/latest?phone=手机号</code>
          <div class="token-state" :class="{ ready: integration.crawlerTokenConfigured }">
            {{ integration.crawlerTokenConfigured ? '爬虫只读令牌已配置' : '尚未生成爬虫令牌' }}
          </div>
          <button type="button" :disabled="rotating === 'crawler'" @click="rotate('crawler')">
            {{
              rotating === 'crawler'
                ? '生成中…'
                : integration.crawlerTokenConfigured
                  ? '轮换爬虫令牌'
                  : '生成爬虫令牌'
            }}
          </button>
        </article>
      </section>
      <p v-if="integrationError" class="inline-error" role="alert">{{ integrationError }}</p>
      <aside class="payload-help">
        <b>手机入站 JSON</b>
        <pre>
{
  "phoneNumber": "19945783940",
  "sender": "小红书",
  "source": "主板机-1-103",
  "message": "【小红书】您的验证码是：621059，5分钟内有效",
  "externalId": "手机转发器中的唯一消息 id",
  "receivedAt": "2026-09-01T09:35:50"
}</pre>
        <span
          >externalId 用于幂等；相同消息重试不会重复发送飞书。receivedAt 可省略，由 WAO
          使用接收时间。</span
        >
      </aside>
    </template>

    <div v-if="editor" class="mask" @click.self="closeEditor()">
      <form class="editor" @submit.prevent="save">
        <header>
          <div>
            <h2>{{ editor.id ? '编辑账号资源' : '新增账号资源' }}</h2>
            <p>账号在组织内共享；密码不会出现在列表接口。</p>
          </div>
          <button type="button" aria-label="关闭" @click="closeEditor()">
            <i class="ri-close-line"></i>
          </button>
        </header>
        <div class="fields">
          <label
            ><span>平台</span
            ><input v-model="editor.platform" required maxlength="64" placeholder="例如：小红书"
          /></label>
          <label
            ><span>账号名称</span
            ><input
              v-model="editor.accountLabel"
              required
              maxlength="128"
              placeholder="便于团队识别"
          /></label>
          <label
            ><span>登录账号</span
            ><input
              v-model="editor.loginIdentifier"
              maxlength="256"
              placeholder="手机号、邮箱或用户名"
          /></label>
          <label
            ><span>接码手机号</span
            ><input
              v-model="editor.phoneNumber"
              maxlength="32"
              inputmode="tel"
              placeholder="例如：19945783940"
          /></label>
          <label
            ><span>密码</span
            ><input
              v-model="editor.secret"
              type="password"
              maxlength="1024"
              :placeholder="editor.id ? '留空则保留原密码' : '选填'"
          /></label>
          <label class="switch-field"
            ><span>当前可用</span><input v-model="editor.enabled" type="checkbox"
          /></label>
          <label class="wide"
            ><span>备注</span
            ><textarea
              v-model="editor.notes"
              maxlength="1024"
              rows="4"
              placeholder="用途、使用限制或负责人"
            ></textarea>
          </label>
        </div>
        <p v-if="dirty" class="draft-hint">
          <i class="ri-save-3-line"></i>未保存修改已保存在本机草稿
        </p>
        <p v-if="saveError" class="inline-error" role="alert">{{ saveError }}</p>
        <footer>
          <button
            v-if="editor.id"
            class="danger"
            type="button"
            :disabled="deleting || saving"
            @click="remove"
          >
            {{ deleting ? '删除中…' : '删除账号' }}</button
          ><span v-else></span>
          <div>
            <button type="button" @click="closeEditor()">稍后再改</button
            ><button class="primary" type="submit" :disabled="!canSave">
              {{ saving ? '保存中…' : saveError ? '重试保存' : '保存账号' }}
            </button>
          </div>
        </footer>
      </form>
    </div>

    <div v-if="rotatedToken" class="mask token-mask" @click.self="rotatedToken = null">
      <section class="token-modal" role="dialog" aria-modal="true" aria-label="新令牌">
        <i class="ri-key-2-line"></i>
        <h2>{{ rotatedToken.type === 'ingest' ? '短信入站令牌' : '爬虫只读令牌' }}已生成</h2>
        <p>令牌只显示这一次。请立即复制到对应设备的安全配置中；关闭后只能再次轮换。</p>
        <code>{{ rotatedToken.token }}</code>
        <button class="primary" type="button" @click="copyText(rotatedToken.token, '令牌已复制')">
          复制令牌
        </button>
        <button type="button" @click="rotatedToken = null">我已安全保存</button>
      </section>
    </div>
  </main>
</template>

<style scoped>
.account-page {
  max-width: 1180px;
  margin: 0 auto;
  padding: 30px 26px 56px;
}
.hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}
.eyebrow {
  margin: 0;
  color: var(--wao-primary-text);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.14em;
}
.hero h1 {
  margin: 6px 0 0;
  font-size: clamp(30px, 4vw, 42px);
}
.hero p:not(.eyebrow) {
  max-width: 700px;
  margin: 8px 0 0;
  color: var(--wao-text-3);
  font-size: 13px;
  line-height: 1.7;
}
button {
  min-height: 34px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  cursor: pointer;
}
button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.primary {
  border: 0;
  background: var(--wao-primary);
  color: #fff;
  padding: 0 16px;
}
.hero button i,
.section-tools button i {
  margin-right: 5px;
}
.boundary {
  display: flex;
  gap: 11px;
  margin-top: 20px;
  padding: 12px 14px;
  border: 1px solid var(--wao-success-line);
  border-radius: 12px;
  background: var(--wao-success-bg);
}
.boundary > i {
  color: var(--wao-success-strong);
  font-size: 21px;
}
.boundary div {
  display: grid;
  gap: 3px;
}
.boundary b {
  font-size: 11px;
}
.boundary span {
  color: var(--wao-text-3);
  font-size: 10px;
  line-height: 1.6;
}
.tabs {
  display: flex;
  gap: 4px;
  margin: 20px 0 16px;
  border-bottom: 1px solid var(--wao-line);
}
.tabs button {
  border: 0;
  border-radius: 8px 8px 0 0;
  padding: 0 14px;
  background: transparent;
  color: var(--wao-text-3);
}
.tabs button.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.tabs b {
  margin-left: 4px;
  font-size: 10px;
}
.state-card {
  min-height: 280px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 9px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  font-size: 12px;
}
.state-card > i {
  font-size: 28px;
}
.state-card.error {
  border-color: var(--wao-danger-line);
}
.state-card button {
  padding: 0 16px;
}
.search {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 440px;
  min-height: 40px;
  margin-bottom: 16px;
  padding: 0 11px;
  border: 1px solid var(--wao-line-control);
  border-radius: 10px;
  background: var(--wao-bg-card);
}
.search input {
  width: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--wao-text);
}
.account-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(330px, 1fr));
  gap: 14px;
}
.account-card {
  padding: 16px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
}
.account-card > header {
  display: flex;
  justify-content: space-between;
}
.platform {
  color: var(--wao-primary-text);
  font-size: 11px;
  font-weight: 700;
}
.status {
  color: var(--wao-text-faint);
  font-size: 10px;
}
.status.enabled {
  color: var(--wao-success-strong);
}
.account-card h2 {
  margin: 10px 0 12px;
  font-size: 18px;
}
.account-card dl {
  display: grid;
  gap: 8px;
  margin: 0;
}
.account-card dl div {
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 8px;
  font-size: 11px;
}
.account-card dt {
  color: var(--wao-text-faint);
}
.account-card dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.mono {
  font-family: var(--wao-font-mono);
  font-variant-numeric: tabular-nums;
}
.secret {
  font-family: var(--wao-font-mono);
}
.account-card > p {
  min-height: 34px;
  margin: 13px 0;
  color: var(--wao-text-3);
  font-size: 10px;
  line-height: 1.6;
}
.account-card > footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border-top: 1px solid var(--wao-line-soft);
  padding-top: 11px;
}
.account-card footer small {
  color: var(--wao-text-faint);
  font-size: 9px;
}
.account-card footer div {
  display: flex;
  gap: 5px;
}
.account-card footer button {
  min-height: 28px;
  padding: 0 8px;
  font-size: 9px;
}
.section-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.section-tools h2 {
  margin: 0;
  font-size: 18px;
}
.section-tools p {
  margin: 4px 0 0;
  color: var(--wao-text-3);
  font-size: 10px;
}
.section-tools button {
  padding: 0 12px;
}
.message-list {
  display: grid;
  gap: 9px;
}
.message-list article {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr) 82px;
  gap: 14px;
  align-items: center;
  padding: 14px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
}
.code {
  color: var(--wao-primary-text);
  font: 800 22px/1 var(--wao-font-mono);
  text-align: center;
}
.code.missing {
  color: var(--wao-warning-strong);
  font-size: 12px;
}
.message-main header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.message-main header span {
  padding: 2px 6px;
  border-radius: 999px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 9px;
}
.message-main time {
  margin-left: auto;
  color: var(--wao-text-faint);
  font-size: 9px;
}
.message-main p {
  margin: 6px 0;
  color: var(--wao-text-2);
  font-size: 11px;
  line-height: 1.55;
}
.message-main small {
  color: var(--wao-text-faint);
  font-size: 9px;
}
.forward {
  display: grid;
  justify-items: end;
  gap: 5px;
  font-size: 9px;
}
.forward.sent {
  color: var(--wao-success-strong);
}
.forward.failed {
  color: var(--wao-danger);
}
.forward button {
  min-height: 26px;
  padding: 0 9px;
  font-size: 9px;
}
.integration-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.setup-card {
  position: relative;
  padding: 18px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
}
.setup-number {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-weight: 800;
}
.setup-card h2 {
  margin: 12px 0 6px;
  font-size: 16px;
}
.setup-card p {
  min-height: 54px;
  margin: 0 0 12px;
  color: var(--wao-text-3);
  font-size: 10px;
  line-height: 1.7;
}
.setup-card label {
  display: grid;
  gap: 5px;
  margin-bottom: 12px;
  font-size: 10px;
}
.setup-card select {
  min-height: 38px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 0 9px;
}
.setup-card code {
  display: block;
  min-height: 50px;
  padding: 8px;
  border-radius: 8px;
  background: var(--wao-bg-soft);
  color: var(--wao-text-2);
  font-size: 9px;
  overflow-wrap: anywhere;
}
.token-state {
  margin: 12px 0 8px;
  color: var(--wao-warning-strong);
  font-size: 10px;
}
.token-state.ready {
  color: var(--wao-success-strong);
}
.setup-card > button {
  width: 100%;
  padding: 0 12px;
}
.payload-help {
  margin-top: 14px;
  padding: 15px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-soft);
}
.payload-help b {
  font-size: 11px;
}
.payload-help pre {
  overflow-x: auto;
  margin: 9px 0;
  color: var(--wao-text-2);
  font-size: 10px;
  line-height: 1.55;
}
.payload-help span {
  color: var(--wao-text-3);
  font-size: 9px;
}
.mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(0 0 0 / 46%);
}
.editor {
  width: min(720px, 100%);
  max-height: min(760px, 92vh);
  overflow: auto;
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-lg);
}
.editor > header,
.editor > footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px;
}
.editor > header {
  border-bottom: 1px solid var(--wao-line);
}
.editor > footer {
  border-top: 1px solid var(--wao-line);
}
.editor h2 {
  margin: 0;
  font-size: 18px;
}
.editor header p {
  margin: 4px 0 0;
  color: var(--wao-text-3);
  font-size: 9px;
}
.editor header button {
  min-width: 34px;
}
.editor footer > div {
  display: flex;
  gap: 8px;
}
.editor footer button {
  padding: 0 14px;
}
.danger {
  color: var(--wao-danger);
}
.fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 13px;
  padding: 18px;
}
.fields label {
  display: grid;
  gap: 6px;
  color: var(--wao-text-3);
  font-size: 10px;
}
.fields .wide {
  grid-column: 1 / -1;
}
.fields input:not([type='checkbox']),
.fields textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 9px;
  font: inherit;
}
.switch-field {
  align-content: end;
}
.switch-field input {
  width: 18px;
  height: 18px;
}
.draft-hint,
.inline-error {
  margin: 0 18px 14px;
  font-size: 10px;
}
.draft-hint {
  color: var(--wao-primary-text);
}
.inline-error {
  color: var(--wao-danger);
}
.token-modal {
  width: min(520px, 100%);
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 28px;
  border-radius: 16px;
  background: var(--wao-bg-card);
  text-align: center;
}
.token-modal > i {
  color: var(--wao-primary-text);
  font-size: 34px;
}
.token-modal h2,
.token-modal p {
  margin: 0;
}
.token-modal p {
  color: var(--wao-text-3);
  font-size: 10px;
  line-height: 1.7;
}
.token-modal code {
  width: 100%;
  box-sizing: border-box;
  padding: 12px;
  border-radius: 8px;
  background: var(--wao-bg-soft);
  overflow-wrap: anywhere;
}
.token-modal button {
  width: 100%;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 860px) {
  .integration-grid {
    grid-template-columns: 1fr;
  }
  .message-list article {
    grid-template-columns: 72px minmax(0, 1fr);
  }
  .forward {
    grid-column: 1 / -1;
    display: flex;
    justify-content: flex-end;
    align-items: center;
  }
  .setup-card p {
    min-height: 0;
  }
}
@media (max-width: 620px) {
  .account-page {
    padding: 20px 14px 42px;
  }
  .hero {
    align-items: stretch;
    flex-direction: column;
  }
  .hero button {
    width: 100%;
  }
  .tabs {
    overflow-x: auto;
  }
  .tabs button {
    flex: 0 0 auto;
  }
  .account-grid {
    grid-template-columns: 1fr;
  }
  .account-card > footer {
    align-items: flex-start;
    flex-direction: column;
  }
  .message-list article {
    grid-template-columns: 1fr;
  }
  .code {
    text-align: left;
  }
  .message-main header {
    flex-wrap: wrap;
  }
  .message-main time {
    width: 100%;
    margin: 0;
  }
  .forward {
    grid-column: auto;
  }
  .fields {
    grid-template-columns: 1fr;
  }
  .fields .wide {
    grid-column: auto;
  }
}
</style>
