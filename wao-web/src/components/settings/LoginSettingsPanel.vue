<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  fetchWechatOfficialLoginConfig,
  saveWechatOfficialLoginConfig,
  verifyWechatOfficialLoginConfig,
  type WechatOfficialLoginConfig,
} from '@/api/system'
import { toast } from '@/composables/toast'

const saved = ref<WechatOfficialLoginConfig | null>(null)
const form = reactive({ enabled: false, appId: '', appSecret: '', token: '' })
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const verification = ref<'idle' | 'checking' | 'ready' | 'error'>('idle')
const verificationMessage = ref('')

const dirty = computed(() => !!saved.value && (
  form.enabled !== saved.value.enabled
  || form.appId !== saved.value.appId
  || form.appSecret.length > 0
  || form.token.length > 0
))
const credentialsReady = computed(() => form.appId.trim().length > 0
  && (form.appSecret.trim().length > 0 || !!saved.value?.appSecretSet)
  && (form.token.trim().length > 0 || !!saved.value?.tokenSet))
const callbackUrl = computed(() => saved.value
  ? `${window.location.origin}${saved.value.callbackPath}`
  : '')

function accept(config: WechatOfficialLoginConfig) {
  saved.value = config
  form.enabled = config.enabled
  form.appId = config.appId
  form.appSecret = ''
  form.token = ''
  verification.value = 'idle'
  verificationMessage.value = ''
}

async function verifyConnection() {
  if (!saved.value?.configured || verification.value === 'checking') return
  verification.value = 'checking'
  verificationMessage.value = ''
  try {
    const result = await verifyWechatOfficialLoginConfig()
    verification.value = result.ready ? 'ready' : 'error'
    verificationMessage.value = result.message
  } catch (cause) {
    verification.value = 'error'
    verificationMessage.value = cause instanceof Error
      ? cause.message
      : '微信接口验证失败，请重试'
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const config = await fetchWechatOfficialLoginConfig()
    accept(config)
    if (config.configured) void verifyConnection()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '系统登录配置读取失败，请重试'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!dirty.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    accept(await saveWechatOfficialLoginConfig({
      enabled: form.enabled,
      appId: form.appId.trim(),
      appSecret: form.appSecret.trim() || undefined,
      token: form.token.trim() || undefined,
    }))
    toast('系统登录配置已保存，正在验证微信接口', 'ri-shield-check-line')
    await verifyConnection()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '系统登录配置保存失败，请重试'
  } finally {
    saving.value = false
  }
}

function confirmLeave() {
  return !dirty.value || window.confirm('系统登录配置还有未保存的修改，确认离开吗？')
}

defineExpose({ confirmLeave })
onMounted(load)
</script>

<template>
  <section class="login-settings-panel">
    <header class="panel-head">
      <div>
        <h1>系统登录配置</h1>
        <p>管理登录页可用的系统级认证方式；凭据保存后立即在所有节点生效。</p>
      </div>
      <span v-if="saving" class="state"><i class="ri-loader-4-line spin"></i>保存中…</span>
      <span v-else-if="dirty" class="state warning"><i class="ri-edit-line"></i>有未保存修改</span>
      <span v-else-if="saved" class="state"><i class="ri-checkbox-circle-line"></i>已保存</span>
    </header>

    <div v-if="loading && !saved" class="load-state" aria-live="polite">
      <i class="ri-loader-4-line spin"></i>正在读取系统登录配置…
    </div>
    <div v-else-if="error && !saved" class="load-state error" role="alert">
      <span>{{ error }}</span>
      <button type="button" @click="load">重新读取</button>
    </div>

    <form v-else-if="saved" class="config-card" @submit.prevent="save">
      <div class="provider-head">
        <span class="wechat-mark"><i class="ri-wechat-fill"></i></span>
        <div><b>微信公众号扫码登录</b><small>扫码关注公众号，发送登录页四位验证码后进入 WAO。</small></div>
        <button
          type="button"
          class="toggle"
          :class="{ on: form.enabled }"
          :aria-pressed="form.enabled"
          aria-label="启用微信公众号扫码登录"
          :disabled="saving"
          @click="form.enabled = !form.enabled"
        ><span></span></button>
      </div>

      <div class="fields">
        <label>
          <span>公众号 App ID</span>
          <input v-model.trim="form.appId" maxlength="128" autocomplete="off" placeholder="wx 开头的认证公众号 App ID" />
        </label>
        <label>
          <span>公众号 App Secret</span>
          <input
            v-model="form.appSecret"
            type="password"
            maxlength="255"
            autocomplete="new-password"
            :placeholder="saved.appSecretSet ? `已设置（${saved.appSecretMask}），留空不修改` : '请输入公众号 App Secret'"
          />
        </label>
        <label>
          <span>消息回调 Token</span>
          <input
            v-model="form.token"
            type="password"
            maxlength="255"
            autocomplete="new-password"
            :placeholder="saved.tokenSet ? `已设置（${saved.tokenMask}），留空不修改` : '请输入微信后台填写的 Token'"
          />
        </label>
        <label class="wide">
          <span>微信后台服务器地址</span>
          <input :value="callbackUrl" readonly aria-label="微信后台服务器地址" />
          <small>在微信公众平台选择明文模式，并把此地址与上方 Token 一并保存。</small>
        </label>
      </div>

      <div v-if="form.enabled && !credentialsReady" class="feedback warning" role="status">
        启用前需要完整填写 App ID、App Secret 与消息回调 Token；已有密钥可继续留空。
      </div>
      <div v-if="error" class="feedback error" role="alert">{{ error }}，表单草稿已保留。</div>
      <div v-if="!dirty && verification === 'checking'" class="feedback checking" role="status">
        <i class="ri-loader-4-line spin"></i>正在通过微信官方接口验证凭据与服务器出口 IP…
      </div>
      <div v-else-if="!dirty && verification === 'ready'" class="feedback success" role="status">
        <i class="ri-checkbox-circle-fill"></i>{{ verificationMessage }}
      </div>
      <div v-else-if="!dirty && verification === 'error'" class="feedback error" role="alert">
        <i class="ri-error-warning-line"></i>{{ verificationMessage }}
      </div>
      <footer>
        <span v-if="saved.configured && !dirty && verification === 'ready'"><i class="ri-checkbox-circle-fill"></i>公众号登录已通过真实接口验证。</span>
        <span v-else-if="saved.configured && !dirty">字段已保存，需通过微信接口验证后才算可用。</span>
        <span v-else-if="!form.enabled">当前已停用，不影响飞书和邮箱登录。</span>
        <span v-else>保存完整配置后，登录页无需重启即可使用。</span>
        <div class="footer-actions">
          <button
            v-if="saved.configured && !dirty"
            type="button"
            class="secondary"
            :disabled="verification === 'checking' || saving"
            @click="verifyConnection"
          >{{ verification === 'checking' ? '验证中…' : '重新验证' }}</button>
          <button type="submit" :disabled="!dirty || saving">
            {{ saving ? '保存中…' : error ? '重试保存' : dirty ? '保存登录配置' : '已保存' }}
          </button>
        </div>
      </footer>
    </form>
  </section>
</template>

<style scoped>
.login-settings-panel { max-width: 820px; }
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.panel-head h1 { margin: 0; font-size: 20px; }
.panel-head p { margin: 6px 0 0; color: var(--wao-text-weak); font-size: 12px; line-height: 1.6; }
.state { display: flex; align-items: center; gap: 5px; flex: 0 0 auto; color: var(--wao-success-strong); font-size: 11px; }
.state.warning { color: var(--wao-warning-strong); }
.load-state { min-height: 180px; display: grid; place-content: center; justify-items: center; gap: 12px; border: 1px solid var(--wao-line); border-radius: 14px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 12px; }
.load-state button { height: 32px; padding: 0 14px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-sub-2); color: var(--wao-text); }
.load-state.error { color: var(--wao-danger-strong); }
.config-card { overflow: hidden; border: 1px solid var(--wao-line); border-radius: 14px; background: var(--wao-bg-sub); }
.provider-head { display: grid; grid-template-columns: 44px 1fr auto; align-items: center; gap: 12px; padding: 18px; border-bottom: 1px solid var(--wao-divider); }
.provider-head b, .provider-head small { display: block; }.provider-head b { font-size: 14px; }.provider-head small { margin-top: 4px; color: var(--wao-text-weak); font-size: 11px; line-height: 1.5; }
.wechat-mark { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 12px; background: #e8f8ee; color: #07c160; font-size: 23px; }
.toggle { position: relative; width: 42px; height: 24px; padding: 0; border: none; border-radius: 12px; background: var(--wao-line-control); cursor: pointer; transition: background .2s; }.toggle.on { background: #07c160; }.toggle span { position: absolute; top: 3px; left: 3px; width: 18px; height: 18px; border-radius: 50%; background: white; box-shadow: 0 1px 4px #0003; transition: transform .2s; }.toggle.on span { transform: translateX(18px); }
.fields { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; padding: 18px; }.fields label { display: grid; gap: 6px; }.fields label:first-child, .fields .wide { grid-column: 1 / -1; }.fields label > span { color: var(--wao-text-2); font-size: 11px; font-weight: 600; }.fields input { width: 100%; height: 38px; padding: 0 11px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-input); color: var(--wao-text); font-size: 12px; }.fields input:focus { border-color: var(--wao-primary); outline: none; }.fields input[readonly] { color: var(--wao-text-weak); }.fields small { color: var(--wao-text-faint); font-size: 10px; }
.feedback { display: flex; align-items: flex-start; gap: 6px; margin: 0 18px 14px; padding: 10px 12px; border-radius: 8px; font-size: 11px; line-height: 1.5; }.feedback.warning { background: var(--wao-warning-bg); color: var(--wao-warning-strong); }.feedback.error { background: var(--wao-danger-bg); color: var(--wao-danger-strong); }.feedback.checking { background: var(--wao-primary-soft); color: var(--wao-primary-text); }.feedback.success { background: var(--wao-success-bg); color: var(--wao-success-strong); }
footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 14px 18px; border-top: 1px solid var(--wao-divider); color: var(--wao-text-weak); font-size: 11px; }footer span { display: flex; align-items: center; gap: 5px; }footer span i { color: var(--wao-success); }footer button { height: 34px; padding: 0 15px; border: none; border-radius: 8px; background: var(--wao-primary); color: white; font-size: 12px; font-weight: 600; }footer button:disabled { opacity: .5; cursor: not-allowed; }
.footer-actions { display: flex; flex: 0 0 auto; gap: 8px; }.footer-actions button.secondary { border: 1px solid var(--wao-line-control); background: var(--wao-bg-sub-2); color: var(--wao-text-2); }
.spin { animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 680px) { .panel-head { flex-direction: column; }.fields { grid-template-columns: 1fr; }.fields label { grid-column: 1 !important; }.provider-head { grid-template-columns: 40px 1fr auto; padding: 14px; }footer { align-items: stretch; flex-direction: column; }.footer-actions { width: 100%; }.footer-actions button { flex: 1; } }
</style>
