<script setup lang="ts">
import WaoLogo from '@/components/WaoLogo.vue'
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { startFeishuLogin } from '@/api/feishu'
import { createWechatOfficialSession, pollWechatOfficialSession, type WechatOfficialSession } from '@/api/wechat'
import { useSessionStore } from '@/stores/session'
import { mountFeishuQr } from '@/utils/feishuQr'
import { feishuInAppLogin, isInFeishu } from '@/utils/feishuInApp'
import SiteFooter from '@/components/SiteFooter.vue'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const method = ref<'feishu' | 'wechat' | 'email'>('feishu')
const mode = ref<'login' | 'register'>('login')
const name = ref('')
const email = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const qrError = ref('')
const inAppError = ref('')
const wechatLogin = ref<WechatOfficialSession | null>(null)
const wechatStatus = ref<'WAITING_SCAN' | 'WAITING_CODE' | 'COMPLETED'>('WAITING_SCAN')
const wechatError = ref('')
const wechatBusy = ref(false)
// 飞书容器内免登：卡片按钮经 AppLink 打开时自动换会话，不用再扫码
const inAppTrying = ref(isInFeishu())
let unmountQr: (() => void) | undefined
let wechatPollTimer: number | undefined

async function submitEmail() {
  error.value = ''
  busy.value = true
  try {
    if (mode.value === 'register') await session.emailRegister(name.value, email.value, password.value)
    else await session.emailLogin(email.value, password.value)
    await router.replace(String(route.query.redirect || '/'))
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '登录失败，请稍后重试。'
  } finally { busy.value = false }
}

async function loadQr() {
  unmountQr?.()
  unmountQr = undefined
  qrError.value = ''
  await nextTick()
  try { unmountQr = await mountFeishuQr('feishu-qr') }
  catch { qrError.value = '飞书二维码暂时加载失败，请使用下方“飞书授权登录”。' }
}

function stopWechatPolling() {
  if (wechatPollTimer !== undefined) window.clearTimeout(wechatPollTimer)
  wechatPollTimer = undefined
}

async function pollWechat(sessionId: string) {
  try {
    const result = await pollWechatOfficialSession(sessionId)
    if (wechatLogin.value?.sessionId !== sessionId || method.value !== 'wechat') return
    if (result.status === 'EXPIRED') {
      wechatLogin.value = null
      wechatError.value = '二维码和验证码已过期，请重新获取。'
      stopWechatPolling()
      return
    }
    wechatStatus.value = result.status
    if (result.status === 'COMPLETED' && result.resolution) {
      stopWechatPolling()
      await session.acceptResolution(result.resolution)
      await router.replace(result.resolution.claimRequired ? '/auth/claim' : String(route.query.redirect || '/'))
      return
    }
    wechatPollTimer = window.setTimeout(() => void pollWechat(sessionId), 1800)
  } catch (reason) {
    wechatLogin.value = null
    wechatError.value = reason instanceof Error ? reason.message : '公众号登录状态读取失败，请重试。'
    stopWechatPolling()
  }
}

async function loadWechat() {
  stopWechatPolling()
  wechatLogin.value = null
  wechatStatus.value = 'WAITING_SCAN'
  wechatError.value = ''
  wechatBusy.value = true
  try {
    wechatLogin.value = await createWechatOfficialSession()
    wechatPollTimer = window.setTimeout(() => void pollWechat(wechatLogin.value!.sessionId), 1000)
  } catch (reason) {
    wechatError.value = reason instanceof Error ? reason.message : '公众号登录暂不可用，请改用飞书或邮箱。'
  } finally { wechatBusy.value = false }
}

async function switchMethod(next: 'feishu' | 'wechat' | 'email') {
  method.value = next
  error.value = ''
  if (next === 'feishu') {
    stopWechatPolling()
    await loadQr()
  } else {
    unmountQr?.()
    unmountQr = undefined
    if (next === 'wechat') await loadWechat()
    else stopWechatPolling()
  }
}

async function tryInAppLogin() {
  try {
    const resolution = await feishuInAppLogin()
    if (resolution) {
      await session.acceptResolution(resolution)
      await router.replace(resolution.claimRequired ? '/auth/claim' : String(route.query.redirect || '/'))
      return true
    }
  } catch (reason) {
    inAppError.value = reason instanceof Error ? reason.message : '飞书免登未完成，请改用其他方式登录。'
  }
  return false
}

onMounted(async () => {
  if (inAppTrying.value) {
    const done = await tryInAppLogin()
    inAppTrying.value = false
    if (done) return
  }
  await loadQr()
})
onBeforeUnmount(() => { unmountQr?.(); stopWechatPolling() })
</script>

<template>
  <div class="login">
    <aside class="brand">
      <div class="brand-top"><WaoLogo /><div class="brand-name">WAO<span>Wayuwa All in One</span></div></div>
      <div>
        <h1>想法有多大，产出就有多大。</h1>
        <p class="brand-slogan">安排每天要做的事，整理文件与资料，也可以把目标交给 AI，查看执行过程和结果。</p>
        <p>一个 WAO 账号可以绑定飞书、微信和邮箱。使用已经绑定的方式登录，即可继续同一个账号下的任务与资料。</p>
        <ul><li><i class="ri-shield-user-line"></i>使用自己的账号，继续自己的工作</li><li><i class="ri-link-m"></i>多种登录方式绑定同一账号</li><li><i class="ri-node-tree"></i>从待办、文件或 AI 任务开始</li></ul>
      </div>
      <div class="brand-foot">WAO · 统一账号中心</div>
    </aside>

    <main class="panel">
      <section class="card">
        <WaoLogo class="login-logo" wordmark />
        <h2>{{ method === 'email' && mode === 'register' ? '创建 WAO 账号' : '登录 WAO' }}</h2>
        <p class="product-summary">安排待办、整理资料，与 AI 一起完成任务。</p>
        <template v-if="method === 'feishu'">
          <template v-if="inAppTrying">
            <p class="subtitle">检测到飞书环境，正在免登…</p>
            <div class="in-app-loading"><i class="ri-loader-4-line"></i>正在通过飞书身份自动登录</div>
          </template>
          <template v-else>
            <p class="subtitle">使用飞书扫码，快速进入你的 WAO</p>
            <div v-if="!qrError" id="feishu-qr" class="qr"><div class="qr-loading"><i class="ri-loader-4-line"></i>正在加载飞书二维码…</div></div>
            <div v-else class="qr-fallback"><i class="ri-qr-code-line"></i><span>二维码暂不可用</span></div>
            <div v-if="qrError" class="qr-error" role="alert">{{ qrError }}</div>
            <div v-if="inAppError" class="qr-error" role="alert">{{ inAppError }}</div>
            <button class="authorization-cta" type="button" @click="startFeishuLogin('authorization')"><i class="ri-feishu-line"></i><span>{{ qrError ? '改用飞书授权登录' : '二维码不方便？使用飞书授权登录' }}</span><i class="ri-arrow-right-s-line"></i></button>
          </template>
        </template>

        <template v-else-if="method === 'wechat'">
          <p class="subtitle">微信扫码关注公众号，发送页面验证码即可登录</p>
          <div v-if="wechatBusy" class="wechat-loading"><i class="ri-loader-4-line"></i>正在生成公众号二维码…</div>
          <template v-else-if="wechatLogin">
            <img class="wechat-qr" :src="wechatLogin.qrCodeUrl" alt="微信公众号登录二维码" />
            <ol class="wechat-steps">
              <li :class="{ active: wechatStatus === 'WAITING_SCAN' }">使用微信扫描二维码并关注公众号</li>
              <li :class="{ active: wechatStatus === 'WAITING_CODE' }">向公众号发送下方 4 位验证码</li>
            </ol>
            <div class="wechat-code" aria-label="微信公众号登录验证码">{{ wechatLogin.verificationCode }}</div>
            <p class="wechat-state">{{ wechatStatus === 'WAITING_CODE' ? '已识别扫码，等待公众号收到验证码…' : wechatStatus === 'COMPLETED' ? '验证成功，正在进入 WAO…' : '等待微信扫码…' }}</p>
          </template>
          <div v-if="wechatError" class="wechat-unavailable" role="alert"><i class="ri-wechat-fill"></i><span>{{ wechatError }}</span><button type="button" @click="loadWechat">重新获取</button></div>
        </template>

        <template v-else>
          <p class="subtitle">{{ mode === 'login' ? '使用邮箱密码登录' : '创建一个新的 WAO 账号' }}</p>
          <form class="email-form" @submit.prevent="submitEmail">
            <label v-if="mode === 'register'">显示名称<input v-model.trim="name" autocomplete="name" required placeholder="其他成员看到的名字" /></label>
            <label>邮箱<input v-model.trim="email" type="email" autocomplete="email" required placeholder="你的邮箱地址" /></label>
            <label>密码<input v-model="password" type="password" :autocomplete="mode === 'register' ? 'new-password' : 'current-password'" minlength="8" required :placeholder="mode === 'register' ? '设置密码（至少 8 位）' : '输入账号密码'" /></label>
            <div v-if="error" class="error" role="alert">{{ error }}</div>
            <button class="primary" :disabled="busy">{{ busy ? (mode === 'login' ? '正在登录…' : '正在创建账号…') : mode === 'login' ? '登录' : '创建账号' }}</button>
          </form>
          <button class="switch" type="button" @click="mode = mode === 'login' ? 'register' : 'login'; error = ''">
            {{ mode === 'login' ? '没有账号？使用邮箱注册' : '已有账号？返回登录' }}
          </button>
        </template>

        <div class="other-methods">
          <span>其他登录方式</span>
          <button v-if="method !== 'feishu'" type="button" @click="switchMethod('feishu')"><i class="ri-feishu-line"></i>飞书</button>
          <button v-if="method !== 'wechat'" type="button" @click="switchMethod('wechat')"><i class="ri-wechat-fill"></i>微信</button>
          <button v-if="method !== 'email'" type="button" @click="switchMethod('email')"><i class="ri-mail-line"></i>邮箱</button>
        </div>
        <p class="foot">已有 WAO 账号？请选择已绑定的登录方式。首次使用第三方登录时，按提示确认成员身份；登录后可在账号设置中绑定其他方式。</p>
      </section>
      <SiteFooter class="login-footer" />
    </main>
  </div>
</template>

<style scoped>
.login { position: fixed; inset: 0; display: flex; background: var(--wao-bg-page); }
.login-logo { margin-bottom: 18px; }
.brand { width: 42%; min-width: 340px; padding: 44px 48px; display: flex; flex-direction: column; justify-content: space-between; background: #20263b; color: #fff; }
.brand-top { display: flex; align-items: center; gap: 10px; }.brand-mark { width: 32px; height: 32px; display: grid; place-content: center; border-radius: 9px; background: var(--wao-primary); font-weight: 700; }.brand-name { font-weight: 600; }.brand-name span { margin-left: 8px; color: #8e96b3; font-size: 12px; font-weight: 400; }
h1 { max-width: 540px; margin: 0; font-size: 31px; line-height: 1.45; } .brand p { color: #a7aec7; font-size: 13.5px; line-height: 1.9; }.brand-slogan { max-width: 520px; margin-bottom: 20px; }.brand ul { padding: 0; list-style: none; color: #c9cedf; font-size: 13px; line-height: 2.6; }.brand li { display: flex; align-items: center; gap: 10px; }.brand li i { color: #9da8d8; font-size: 16px; }.brand-foot { color: #6e7590; font-size: 12px; }
.panel { position: relative; flex: 1; display: flex; padding: 32px 32px 68px; overflow-y: auto; background: radial-gradient(circle at 50% 20%, var(--wao-bg-card) 0, var(--wao-bg-page) 72%); }.card { width: 368px; margin: auto; padding: 32px; border: 1px solid var(--wao-line); border-radius: 20px; background: var(--wao-bg-card); box-shadow: 0 18px 52px rgba(32, 38, 59, .10); }.card h2 { margin: 0; text-align: center; font-size: 24px; letter-spacing: -.5px; }.subtitle { margin: 8px 0 20px; text-align: center; color: var(--wao-text-weak); font-size: 13px; }
.product-summary { margin: 8px 0 16px; text-align: center; color: var(--wao-text-weak); font-size: 12px; line-height: 1.6; }
.email-form label { display: block; margin-top: 12px; color: var(--wao-text-2); font-size: 13px; }
.email-form label input { margin-top: 6px; }
.login-footer { position: absolute; right: 0; bottom: 0; left: 0; }
.qr { width: 100%; max-width: 302px; aspect-ratio: 1; margin: auto; overflow: hidden; border: 1px solid var(--wao-line); border-radius: 14px; }.qr-loading { height: 100%; display: grid; place-content: center; gap: 8px; text-align: center; color: var(--wao-text-weak); font-size: 12px; }.qr-loading i { font-size: 30px; color: var(--wao-primary); }.qr-error,.error { margin-top: 8px; color: var(--wao-danger-strong); font-size: 12px; text-align: center; }
.qr-fallback { width: 100%; max-width: 302px; height: 160px; margin: auto; display: grid; place-content: center; gap: 8px; text-align: center; border: 1px dashed var(--wao-line-strong); border-radius: 14px; color: var(--wao-text-weak); font-size: 12px; }.qr-fallback i { color: var(--wao-primary); font-size: 30px; }
.in-app-loading { min-height: 160px; display: grid; place-content: center; gap: 10px; text-align: center; color: var(--wao-text-weak); font-size: 13px; }.in-app-loading i { font-size: 30px; color: var(--wao-primary); animation: in-app-spin 1s linear infinite; }@keyframes in-app-spin { to { transform: rotate(360deg); } }
.wechat-loading { min-height: 220px; display: grid; place-content: center; gap: 10px; text-align: center; color: var(--wao-text-weak); font-size: 13px; }.wechat-loading i { color: #07c160; font-size: 30px; animation: in-app-spin 1s linear infinite; }
.wechat-qr { width: 216px; height: 216px; margin: 0 auto; display: block; object-fit: contain; border: 1px solid var(--wao-line); border-radius: 14px; background: #fff; }.wechat-steps { width: 244px; margin: 14px auto 10px; padding-left: 24px; color: var(--wao-text-weak); font-size: 12px; line-height: 1.8; }.wechat-steps li.active { color: var(--wao-text); font-weight: 600; }.wechat-code { width: 116px; margin: 0 auto; padding: 8px 0; border: 1px dashed var(--wao-primary); border-radius: 10px; color: var(--wao-primary); font-size: 25px; font-weight: 700; letter-spacing: 8px; text-align: center; text-indent: 8px; }.wechat-state { margin: 8px 0 0; color: var(--wao-text-weak); font-size: 12px; text-align: center; }.wechat-unavailable { min-height: 180px; display: grid; place-content: center; justify-items: center; gap: 10px; color: var(--wao-text-weak); font-size: 12px; line-height: 1.7; text-align: center; }.wechat-unavailable > i { color: #07c160; font-size: 34px; }.wechat-unavailable button { width: auto; height: 32px; padding: 0 14px; border: 1px solid var(--wao-line-control); background: var(--wao-bg-sub-2); color: var(--wao-text); }
button,input { width: 100%; height: 42px; border-radius: 10px; font-size: 13px; } input { box-sizing: border-box; margin-top: 10px; padding: 0 13px; border: 1px solid var(--wao-line-control); outline: none; } input:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 3px rgba(67,88,214,.12); } button { cursor: pointer; }.primary { margin-top: 12px; border: none; background: var(--wao-primary); color: #fff; font-weight: 600; }.primary:disabled { opacity: .6; }.switch { height: 32px; border: none; background: none; color: var(--wao-primary); }.authorization-cta { margin-top: 12px; padding: 0 13px; display: flex; align-items: center; gap: 8px; border: 1px solid var(--wao-primary-line); background: var(--wao-primary-tint); color: var(--wao-primary-text); font-weight: 600; }.authorization-cta:hover { border-color: var(--wao-primary); background: var(--wao-primary-soft); box-shadow: 0 4px 12px rgba(51,112,255,.12); }.authorization-cta span { flex: 1; }.authorization-cta .ri-feishu-line { color: var(--wao-primary); font-size: 16px; }.authorization-cta .ri-arrow-right-s-line { font-size: 17px; }.email-form { padding-top: 4px; }
.other-methods { margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--wao-line); display: flex; align-items: center; justify-content: center; gap: 8px; }.other-methods span { margin-right: 4px; color: var(--wao-text-faint); font-size: 11px; }.other-methods button { width: auto; height: 30px; padding: 0 10px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-sub-2); color: var(--wao-text-weak); font-size: 12px; }.other-methods i { margin-right: 4px; }.other-methods .ri-feishu-line { color: var(--wao-primary); }.other-methods .ri-wechat-fill { color: #07c160; }.foot { margin: 14px 0 0; color: var(--wao-text-faint); font-size: 11px; line-height: 1.6; text-align: center; }
@media (max-width: 760px) { .brand { display: none; }.panel { padding: 16px 16px 68px; }.card { width: min(368px, calc(100vw - 32px)); box-sizing: border-box; padding: 22px; } }
</style>
