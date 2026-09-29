<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { startFeishuLogin } from '@/api/feishu'
import { useSessionStore } from '@/stores/session'
import { mountFeishuQr } from '@/utils/feishuQr'
import { feishuInAppLogin, isInFeishu } from '@/utils/feishuInApp'
import { useLoginSound } from '@/composables/useLoginSound'
import LoginCrew from '@/components/login/LoginCrew.vue'
import SiteFooter from '@/components/SiteFooter.vue'

const route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
const method = ref<'feishu' | 'email'>('feishu')
const mode = ref<'login' | 'register'>('login')
const name = ref(''),
  email = ref(''),
  password = ref('')
const busy = ref(false),
  error = ref(''),
  qrError = ref(''),
  inAppError = ref('')
const inAppTrying = ref(isInFeishu()),
  authorizationBusy = ref(false)
const dialog = ref<HTMLDialogElement | null>(null)
const opened = ref(false)
const { enabled, unavailable, toggle, play } = useLoginSound()
let unmountQr: (() => void) | undefined
let qrGeneration = 0
let disposed = false
function stopQr() {
  qrGeneration++
  unmountQr?.()
  unmountQr = undefined
}
async function loadQr() {
  stopQr()
  const generation = qrGeneration
  const active = () =>
    !disposed && opened.value && method.value === 'feishu' && generation === qrGeneration
  qrError.value = ''
  await nextTick()
  if (!active()) return
  try {
    const cleanup = await mountFeishuQr('feishu-qr', active)
    if (active()) unmountQr = cleanup
    else cleanup()
  } catch {
    if (active()) qrError.value = '飞书二维码暂时加载失败，请重试或使用下方授权登录。'
  }
}
async function openAuth(nextMode: 'login' | 'register' = 'login') {
  mode.value = nextMode
  method.value = nextMode === 'register' ? 'email' : 'feishu'
  error.value = ''
  opened.value = true
  await nextTick()
  dialog.value?.showModal()
  if (method.value === 'feishu' && !inAppTrying.value) await loadQr()
}
function onClose() {
  opened.value = false
  stopQr()
}
function closeAuth() {
  if (!busy.value) dialog.value?.close()
}
function switchMode() {
  if (busy.value) return
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = ''
}
async function switchMethod(next: 'feishu' | 'email') {
  if (busy.value) return
  stopQr()
  method.value = next
  error.value = ''
  if (next === 'feishu') {
    mode.value = 'login'
    await loadQr()
  }
}
async function authorizeFeishu() {
  if (authorizationBusy.value) return
  authorizationBusy.value = true
  inAppError.value = ''
  try {
    await startFeishuLogin('authorization')
  } catch {
    inAppError.value = '暂时无法连接飞书，请重试或使用邮箱登录。'
    play('error')
  } finally {
    authorizationBusy.value = false
  }
}
async function submitEmail() {
  if (busy.value) return
  error.value = ''
  busy.value = true
  try {
    if (mode.value === 'register')
      await session.emailRegister(name.value, email.value, password.value)
    else await session.emailLogin(email.value, password.value)
    password.value = ''
    const soundDuration = play('success')
    if (soundDuration) await new Promise((resolve) => window.setTimeout(resolve, soundDuration))
    await router.replace(String(route.query.redirect || '/'))
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '登录失败，请稍后重试。'
    play('error')
  } finally {
    busy.value = false
  }
}
function buttonSound(event: MouseEvent) {
  if ((event.target as Element).closest('button:not(.sound-toggle)')) play('click')
}
onMounted(async () => {
  if (!inAppTrying.value) return
  await openAuth()
  try {
    const resolution = await feishuInAppLogin()
    if (disposed) return
    if (resolution) {
      await session.acceptResolution(resolution)
      await router.replace(
        resolution.claimRequired ? '/auth/claim' : String(route.query.redirect || '/'),
      )
      return
    }
  } catch {
    if (!disposed) inAppError.value = '飞书免登未完成，请改用其他方式登录。'
  } finally {
    inAppTrying.value = false
  }
  if (!disposed && opened.value && method.value === 'feishu') await loadQr()
})
onBeforeUnmount(() => {
  disposed = true
  stopQr()
})
</script>
<template>
  <main class="login-stage" @click="buttonSound">
    <div class="grid" aria-hidden="true"></div>
    <header>
      <div class="brand" aria-label="WAO">
        wao<b aria-hidden="true">✳</b><span>WAYUWA<br />ALL IN ONE</span>
      </div>
      <div class="right">
        <button class="quiet sound-toggle" :aria-pressed="enabled" @click="toggle">
          {{ unavailable ? '音效暂不可用' : enabled ? '♫ 音效已开启' : '♫ 开启音效' }}</button
        ><button class="quiet header-login" @click="openAuth()">登录 ↗</button>
      </div>
    </header>
    <section class="hero">
      <p class="eyebrow">A LITTLE IDEA. A LITTLE ACTION. A LITTLE WOW.</p>
      <h1>把想法，<br />变成 <em>WOW.</em></h1>
      <p class="sub">记录灵感，安排每一天。<br />和 AI 一起，让想做的事真正发生。</p>
      <div class="actions">
        <button class="primary" @click="openAuth()">登录 WAO ↗</button
        ><button class="secondary" @click="openAuth('register')">创建账号</button>
      </div>
      <p class="hint">飞书 · 邮箱，继续你的每一个好想法</p>
    </section>
    <div class="crew">
      <p class="caption">开启音效，点点我们 ↙</p>
      <LoginCrew @sound="play('buddy', $event)" />
    </div>
    <div class="bottom">
      <span>WAO · WAYUWA ALL IN ONE</span><span class="phrase">✳ 给每个想法，一个发生的地方。</span>
    </div>
    <SiteFooter class="login-footer" />
    <dialog
      ref="dialog"
      class="auth-dialog"
      aria-labelledby="auth-title"
      @close="onClose"
      @cancel="busy && $event.preventDefault()"
    >
      <section class="card">
        <button class="close" aria-label="关闭登录弹窗" :disabled="busy" @click="closeAuth">
          ×
        </button>
        <span class="tiny">WAO / LET'S MAKE IT HAPPEN</span>
        <h2 id="auth-title">{{ mode === 'register' ? '创建 WAO 账号' : '欢迎回到 WAO' }}</h2>
        <p class="product-summary">安排待办、整理资料，与 AI 一起完成任务。</p>
        <template v-if="method === 'feishu'">
          <template v-if="inAppTrying">
            <p class="subtitle">检测到飞书环境，正在免登…</p>
            <div class="in-app-loading">
              <i class="ri-loader-4-line"></i>正在通过飞书身份自动登录
            </div>
          </template>
          <template v-else>
            <p class="subtitle">使用飞书扫码，快速进入你的 WAO</p>
            <div v-if="!qrError" id="feishu-qr" class="qr">
              <div class="qr-loading"><i class="ri-loader-4-line"></i>正在加载飞书二维码…</div>
            </div>
            <div v-else class="qr-fallback">
              <i class="ri-qr-code-line"></i><span>二维码暂不可用</span>
            </div>
            <div v-if="qrError" class="qr-error" role="alert">
              {{ qrError
              }}<button class="switch" type="button" @click="loadQr">重新加载二维码</button>
            </div>
            <div v-if="inAppError" class="qr-error" role="alert">{{ inAppError }}</div>
            <button
              class="authorization-cta"
              type="button"
              @click="authorizeFeishu"
              :disabled="authorizationBusy"
            >
              <i class="ri-feishu-line"></i
              ><span>{{ qrError ? '改用飞书授权登录' : '二维码不方便？使用飞书授权登录' }}</span
              ><i class="ri-arrow-right-s-line"></i>
            </button>
          </template>
        </template>

        <template v-else>
          <p class="subtitle">
            {{ mode === 'login' ? '使用邮箱密码登录' : '创建一个新的 WAO 账号' }}
          </p>
          <form :aria-busy="busy" class="email-form" @submit.prevent="submitEmail">
            <label v-if="mode === 'register'"
              >显示名称<input
                :disabled="busy"
                v-model.trim="name"
                autocomplete="name"
                required
                placeholder="其他成员看到的名字"
            /></label>
            <label
              >邮箱<input
                :disabled="busy"
                v-model.trim="email"
                type="email"
                autocomplete="email"
                required
                placeholder="你的邮箱地址"
            /></label>
            <label
              >密码<input
                :disabled="busy"
                v-model="password"
                type="password"
                :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
                minlength="8"
                required
                :placeholder="mode === 'register' ? '设置密码（至少 8 位）' : '输入账号密码'"
            /></label>
            <div v-if="error" class="error" role="alert">{{ error }}</div>
            <button class="primary" :disabled="busy">
              {{
                busy
                  ? mode === 'login'
                    ? '正在登录…'
                    : '正在创建账号…'
                  : mode === 'login'
                    ? '登录'
                    : '创建账号'
              }}
            </button>
          </form>
          <button class="switch" type="button" :disabled="busy" @click="switchMode">
            {{ mode === 'login' ? '没有账号？使用邮箱注册' : '已有账号？返回登录' }}
          </button>
        </template>

        <div class="other-methods">
          <span>其他登录方式</span>
          <button
            :disabled="busy"
            v-if="method !== 'feishu'"
            type="button"
            @click="switchMethod('feishu')"
          >
            <i class="ri-feishu-line"></i>飞书
          </button>
          <button
            :disabled="busy"
            v-if="method !== 'email'"
            type="button"
            @click="switchMethod('email')"
          >
            <i class="ri-mail-line"></i>邮箱
          </button>
        </div>
        <p class="foot">
          已有 WAO
          账号？请选择已绑定的登录方式。首次使用第三方登录时，按提示确认成员身份；登录后可在账号设置中绑定其他方式。
        </p>
      </section>
    </dialog>
  </main>
</template>

<style scoped>
.login-stage {
  color-scheme: dark;
  --pink: #ff36aa;
  --bg: #090a0c;
  --muted: #99999f;
}
* {
  box-sizing: border-box;
}
.login-stage {
  margin: 0;
  background: var(--bg);
  color: #f8f7f4;
  font-family: Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
button,
input {
  font: inherit;
}
button {
  cursor: pointer;
}
button:focus-visible,
input:focus-visible {
  outline: 3px solid #74efdb;
  outline-offset: 5px;
}
button {
  transition:
    transform 0.2s,
    background 0.2s;
}
button:hover {
  transform: translateY(-2px);
}
.login-stage {
  position: relative;
  min-height: 100svh;
  overflow: hidden;
  isolation: isolate;
  display: flex;
  flex-direction: column;
}
.grid {
  position: absolute;
  inset: 0;
  z-index: -2;
  background-image:
    linear-gradient(#ffffff09 1px, transparent 1px),
    linear-gradient(90deg, #ffffff09 1px, transparent 1px);
  background-size: 90px 90px;
  mask-image: radial-gradient(ellipse at 50% 48%, transparent 18%, #000 85%);
  transform: perspective(600px) rotateX(12deg) scale(1.12);
}
.login-stage:after {
  content: '';
  position: absolute;
  z-index: -1;
  inset: 0;
  background: radial-gradient(ellipse at 50% 88%, #59315621, transparent 45%);
}
header {
  padding: 28px 4.2%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}
.brand {
  font-size: 32px;
  letter-spacing: -3px;
  font-weight: 1000;
  display: flex;
  align-items: center;
  gap: 14px;
}
.brand b {
  color: var(--pink);
}
.brand span {
  font-size: 10px;
  letter-spacing: 2px;
  font-weight: 400;
  color: #a4a4aa;
  border-left: 1px solid #3a3a3e;
  padding-left: 15px;
  line-height: 1.6;
}
.right {
  display: flex;
  align-items: center;
  gap: 24px;
}
.preview {
  font-size: 11px;
  letter-spacing: 1px;
  color: #aaa;
}
.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  background: #83ebd0;
  border-radius: 50%;
  margin-right: 7px;
}
.quiet {
  border: 1px solid #3b3b40;
  color: #eee;
  background: #161619;
  border-radius: 30px;
  padding: 10px 22px;
  font-size: 13px;
}
.hero {
  text-align: center;
  padding: 35px 20px 0;
  z-index: 1;
}
.eyebrow {
  font-size: 11px;
  letter-spacing: 4px;
  color: #b5b0b8;
  margin: 0 0 22px;
}
.hero h1 {
  font-size: clamp(48px, 6.8vw, 104px);
  letter-spacing: -5px;
  line-height: 1.13;
  margin: 0;
  font-weight: 950;
}
.hero h1 em {
  font-family: Arial, sans-serif;
  font-weight: 1000;
  display: inline-block;
  color: var(--pink);
  transform: rotate(-6deg);
  letter-spacing: -7px;
  position: relative;
  font-style: italic;
}
.hero h1 em:after {
  content: '';
  position: absolute;
  bottom: -7px;
  left: 7%;
  width: 85%;
  height: 6px;
  background: var(--pink);
  border-radius: 100%;
  transform: rotate(-3deg);
}
.sub {
  font-size: 15px;
  line-height: 1.8;
  color: #aaa7af;
  margin: 29px 0 25px;
}
.actions {
  display: flex;
  justify-content: center;
  gap: 12px;
}
.primary,
.secondary {
  border-radius: 40px;
  padding: 15px 36px;
  font-weight: 700;
  font-size: 15px;
  min-width: 174px;
  border: 1px solid var(--pink);
}
.primary {
  background: var(--pink);
  color: #fff;
}
.primary:hover {
  background: #ff59ba;
  box-shadow: 0 8px 30px #ff36aa26;
}
.secondary {
  background: #161016;
  color: #ff65bf;
}
.hint {
  font-size: 11px;
  color: #77757d;
  margin: 15px 0 0;
}
.crew {
  width: min(100%, 1000px);
  margin: 20px auto 0;
  position: relative;
}
.crew svg {
  display: block;
  width: 100%;
  height: auto;
  overflow: visible;
}
.buddy {
  transition: transform 0.3s;
  transform-box: fill-box;
  transform-origin: 50% 100%;
  cursor: default;
}
.buddy:hover {
  transform: translateY(-10px) rotate(-3deg);
}
.label {
  font-size: 10px;
  fill: #9a969e;
  letter-spacing: 3px;
}
.bottom {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-top: auto;
  padding: 18px 4.2% 23px;
  color: #737179;
  font-size: 10px;
  letter-spacing: 1px;
}
.bottom .phrase {
  color: #aaa6b1;
}
.spark {
  color: #e5f284;
}
.caption {
  position: absolute;
  right: 6%;
  top: 12%;
  font-size: 11px;
  color: #a4a0aa;
  transform: rotate(8deg);
}
@media (min-width: 1500px) {
  .hero {
    padding-top: 55px;
  }
  .crew {
    width: 1100px;
    margin-top: 30px;
  }
}
@media (max-width: 700px) {
  header {
    padding: 23px;
  }
  .brand {
    font-size: 28px;
  }
  .brand span {
    display: none;
  }
  .right {
    gap: 8px;
  }
  .header-login {
    display: none;
  }
  .quiet {
    padding: 8px 14px;
  }
  .preview {
    font-size: 9px;
    max-width: 65px;
    line-height: 1.5;
  }
  .hero {
    padding-top: 50px;
  }
  .hero h1 {
    font-size: 54px;
    letter-spacing: -3px;
  }
  .hero h1 em {
    letter-spacing: -4px;
  }
  .eyebrow {
    font-size: 9px;
    letter-spacing: 2px;
  }
  .sub {
    font-size: 13px;
    max-width: 280px;
    margin: 27px auto 24px;
  }
  .primary,
  .secondary {
    min-width: 140px;
    padding: 14px 22px;
  }
  .crew {
    width: 520px;
    max-width: none;
    margin: 37px 0 0;
    left: 50%;
    transform: translateX(-50%);
    align-self: auto;
  }
  .caption {
    display: none;
  }
  .bottom {
    font-size: 9px;
    flex-direction: column;
    align-items: center;
    text-align: center;
    padding: 25px 15px;
    gap: 9px;
  }
  .hero {
    flex: 1;
  }
  .hint {
    font-size: 10px;
  }
}
@media (prefers-reduced-motion: reduce) {
  *,
  *:before,
  *:after {
    transition: none !important;
    animation: none !important;
  }
}
@media (min-width: 701px) and (max-height: 800px) {
  header {
    padding-top: 20px;
    padding-bottom: 20px;
  }
  .hero {
    padding-top: 12px;
  }
  .hero h1 {
    font-size: 70px;
  }
  .eyebrow {
    margin-bottom: 15px;
  }
  .sub {
    margin: 20px 0;
  }
  .crew {
    width: 800px;
    margin-top: 5px;
  }
  .bottom {
    padding-top: 8px;
    padding-bottom: 14px;
  }
  .hint {
    margin-top: 12px;
  }
}

.login-stage {
  position: fixed;
  inset: 0;
  overflow-y: auto;
  overflow-x: hidden;
  min-height: 0;
  color-scheme: dark;
  --pink: #ff36aa;
  --bg: #090a0c;
  --muted: #99999f;
}
.login-stage * {
  box-sizing: border-box;
}
button:disabled {
  opacity: 0.55;
  cursor: wait;
  transform: none;
}
.login-footer {
  color: #95909b;
  padding: 6px 16px 14px;
  min-height: 28px;
}
.bottom {
  padding-bottom: 7px;
}
.crew {
  flex-shrink: 0;
}
.hint {
  color: #a09aa5;
}
.caption {
  margin: 0;
}
.auth-dialog {
  color-scheme: dark;
  padding: 0;
  border: 1px solid #49404f;
  border-radius: 24px;
  background: #17161a;
  color: #f8f7fa;
  width: 420px;
  max-width: calc(100vw - 28px);
  max-height: calc(100dvh - 32px);
  overflow: auto;
  box-shadow: 0 30px 120px #000a;
}
.auth-dialog::backdrop {
  background: #070709b8;
  backdrop-filter: blur(12px);
}
.card {
  padding: 30px;
  position: relative;
}
.close {
  position: absolute;
  right: 14px;
  top: 12px;
  width: 32px;
  height: 32px;
  background: none;
  color: #d2cbd7;
  border: 0;
  font-size: 25px;
}
.tiny {
  font-size: 10px;
  color: #fc8dcc;
  letter-spacing: 1px;
}
.card h2 {
  font-size: 27px;
  margin: 18px 0 8px;
}
.product-summary,
.subtitle {
  font-size: 12px;
  line-height: 1.8;
  color: #b8b1bf;
}
.subtitle {
  text-align: center;
  margin: 16px 0;
}
.qr {
  background: white;
  width: 100%;
  max-width: 300px;
  aspect-ratio: 1;
  margin: auto;
  overflow: hidden;
  border-radius: 14px;
}
.qr-loading,
.qr-fallback,
.in-app-loading {
  min-height: 180px;
  display: grid;
  place-content: center;
  gap: 12px;
  text-align: center;
  font-size: 13px;
  color: #aaa0b4;
}
.qr-loading {
  color: #55465d;
  height: 100%;
}
.qr-error,
.error {
  color: #ffb0cd;
  font-size: 12px;
  line-height: 1.7;
  margin-top: 12px;
}
.authorization-cta {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  padding: 12px 8px;
  margin-top: 15px;
  border-radius: 12px;
  border: 1px solid #674457;
  background: #2c1c29;
  color: #ffb8df;
  font-size: 12px;
}
.email-form label {
  display: block;
  font-size: 12px;
  margin: 15px 0 7px;
  color: #ded5e4;
}
.email-form input {
  display: block;
  margin-top: 7px;
  width: 100%;
  border: 1px solid #64556e;
  background: #100e12;
  padding: 12px;
  color: #eee;
  border-radius: 10px;
}
.email-form .primary {
  width: 100%;
  margin-top: 18px;
}
.switch {
  color: #ff9fd7;
  background: none;
  border: 0;
  padding: 12px 0;
  font-size: 12px;
}
.other-methods {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  border-top: 1px solid #49404f;
  padding-top: 16px;
  margin-top: 16px;
  color: #b9afc2;
  font-size: 12px;
}
.other-methods button {
  color: #f4e7fc;
  background: #332b39;
  border: 1px solid #62536c;
  border-radius: 9px;
  padding: 8px 16px;
}
.other-methods i {
  margin-right: 6px;
}
.foot {
  color: #b2a8bc;
  font-size: 11px;
  line-height: 1.7;
  margin: 16px 0 0;
}
.card .primary {
  min-width: 0;
}
.auth-dialog:focus {
  outline: none;
}
@media (max-width: 700px) {
  .crew {
    width: min(490px, 125vw);
  }
  .bottom {
    padding-bottom: 8px;
  }
  .card {
    padding: 26px 22px;
  }
  .hero {
    flex: 0;
  }
  .login-stage {
    gap: 0;
  }
  .crew {
    margin-top: auto;
    padding-top: 32px;
  }
  .login-footer {
    font-size: 9px;
  }
}
@media (min-width: 701px) and (max-height: 800px) {
  .crew {
    width: 720px;
  }
  .hero h1 {
    font-size: 64px;
  }
  .bottom {
    margin-top: auto;
    font-size: 9px;
  }
  .sub {
    margin: 15px 0;
  }
  .hero {
    padding-top: 6px;
  }
}
</style>
