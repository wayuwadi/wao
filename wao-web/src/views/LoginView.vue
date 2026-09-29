<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { startFeishuLogin } from '@/api/feishu'
import { useSessionStore } from '@/stores/session'
import { mountFeishuQr } from '@/utils/feishuQr'
import { feishuInAppLogin, isInFeishu } from '@/utils/feishuInApp'
import { useLoginSound } from '@/composables/useLoginSound'
import { loginGreetings, useLoginGreetings } from '@/composables/useLoginGreetings'
import LoginCrew from '@/components/login/LoginCrew.vue'
import SiteFooter from '@/components/SiteFooter.vue'

const route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
const method = ref<'feishu' | 'email'>('feishu')
const email = ref(''),
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
const { active, sounding, greet, dismiss } = useLoginGreetings(enabled, (index) =>
  play('buddy', index),
)
const viewportHeight = ref(0),
  viewportTop = ref(0)
function measureViewport() {
  viewportHeight.value = window.visualViewport?.height ?? window.innerHeight
  viewportTop.value = window.visualViewport?.offsetTop ?? 0
}
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
async function openAuth() {
  dismiss()
  method.value = 'feishu'
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
async function switchMethod(next: 'feishu' | 'email') {
  if (busy.value) return
  stopQr()
  method.value = next
  error.value = ''
  if (next === 'feishu') {
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
    await session.emailLogin(email.value, password.value)
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
  if (!sounding.value && (event.target as Element).closest('button:not(.sound-toggle)'))
    play('click')
}
onMounted(async () => {
  measureViewport()
  window.visualViewport?.addEventListener('resize', measureViewport)
  window.visualViewport?.addEventListener('scroll', measureViewport)
  window.addEventListener('resize', measureViewport)
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
  window.visualViewport?.removeEventListener('resize', measureViewport)
  window.visualViewport?.removeEventListener('scroll', measureViewport)
  window.removeEventListener('resize', measureViewport)
  stopQr()
})
</script>
<template>
  <main
    class="login-stage"
    @click="buttonSound"
    :style="
      viewportHeight
        ? {
            '--login-viewport-height': `${viewportHeight}px`,
            '--login-viewport-top': `${viewportTop}px`,
          }
        : undefined
    "
  >
    <div class="grid" aria-hidden="true"></div>
    <header>
      <div class="brand" aria-label="WAO">
        wao<b aria-hidden="true">✳</b><span>WAYUWA<br />ALL IN ONE</span>
      </div>
      <div class="right">
        <button class="quiet sound-toggle" :aria-pressed="enabled" @click="toggle">
          {{ unavailable ? '音效暂不可用' : enabled ? '♫ 音效已开启' : '♫ 开启音效' }}</button
        ><button class="quiet header-login" @click="openAuth()">登录 WAO ↗</button>
      </div>
    </header>
    <div class="stage-content">
      <section class="hero">
        <p class="eyebrow">A LITTLE IDEA. A LITTLE ACTION. A LITTLE WOW.</p>
        <h1>把想法，<br />变成 <em>WOW.</em></h1>
        <p class="sub">记录灵感，安排每一天。<br />和 AI 一起，让想做的事真正发生。</p>
      </section>
      <div class="crew">
        <div class="greeting-space">
          <p
            v-if="active !== null"
            class="greeting-bubble"
            :class="{ sounding }"
            :style="{ '--anchor': loginGreetings[active]!.anchor + '%' }"
            role="status"
          >
            {{ loginGreetings[active]!.text }}
          </p>
          <p v-else class="crew-hint">
            {{ enabled ? '掠过或轻点伙伴，听一声轻响' : '掠过或轻点伙伴，打个招呼' }}
          </p>
        </div>
        <LoginCrew :active="active" @greet="greet" />
      </div>
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
        <h2 id="auth-title">欢迎回到 WAO</h2>
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
          <p class="subtitle">使用邮箱密码登录</p>
          <form :aria-busy="busy" class="email-form" @submit.prevent="submitEmail">
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
                autocomplete="current-password"
                minlength="8"
                required
                placeholder="输入账号密码"
            /></label>
            <div v-if="error" class="error" role="alert">{{ error }}</div>
            <button class="primary" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
          </form>
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
  box-sizing: border-box;
  --pink: #ff36aa;
  color-scheme: dark;
  position: fixed;
  inset: 0;
  height: 100dvh;
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: clamp(4px, 1.5dvh, 16px);
  padding: clamp(10px, 2.5dvh, 28px) clamp(16px, 4.2vw, 64px) max(8px, env(safe-area-inset-bottom));
  overflow: hidden;
  isolation: isolate;
  background: #090a0c;
  color: #f8f7f4;
  font-family: Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.login-stage * {
  box-sizing: border-box;
}
.grid {
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background-image:
    linear-gradient(#ffffff09 1px, transparent 1px),
    linear-gradient(90deg, #ffffff09 1px, transparent 1px);
  background-size: 90px 90px;
  mask-image: radial-gradient(ellipse at 50% 48%, transparent 18%, #000 85%);
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 32px;
  font-weight: 950;
  letter-spacing: -3px;
  line-height: 1;
}
.brand b {
  color: var(--pink);
}
.brand span {
  padding-left: 15px;
  border-left: 1px solid #3a3a3e;
  color: #a4a4aa;
  font-size: 10px;
  font-weight: 400;
  letter-spacing: 2px;
  line-height: 1.6;
}
.right {
  display: flex;
  align-items: center;
  gap: 12px;
}
button,
input {
  font: inherit;
}
button {
  cursor: pointer;
  transition:
    transform 0.2s,
    background 0.2s;
}
button:hover {
  transform: translateY(-2px);
}
button:disabled {
  opacity: 0.55;
  cursor: wait;
  transform: none;
}
button:focus-visible,
input:focus-visible {
  outline: 3px solid #74efdb;
  outline-offset: 3px;
}
.quiet {
  border: 1px solid #514753;
  border-radius: 30px;
  color: #eee;
  background: #161619;
  padding: 10px 18px;
  font-size: 13px;
  white-space: nowrap;
}
.header-login {
  background: var(--pink);
  border-color: var(--pink);
  color: white;
  font-weight: 700;
}
.stage-content {
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: clamp(8px, 2dvh, 24px);
  width: min(1100px, 100%);
  margin-inline: auto;
}
.hero {
  text-align: center;
  flex-shrink: 0;
}
.eyebrow {
  font-size: clamp(9px, 1.4dvh, 11px);
  letter-spacing: 3px;
  color: #b5b0b8;
  margin: 0 0 clamp(8px, 2dvh, 22px);
}
.hero h1 {
  font-size: clamp(30px, min(7.5vw, 11dvh), 104px);
  letter-spacing: -4px;
  line-height: 1.12;
  margin: 0;
  font-weight: 950;
}
.hero h1 em {
  display: inline-block;
  position: relative;
  color: var(--pink);
  transform: rotate(-6deg);
  font-weight: 1000;
  font-style: italic;
  letter-spacing: -5px;
}
.hero h1 em:after {
  content: '';
  position: absolute;
  bottom: -6px;
  left: 7%;
  width: 85%;
  height: 5px;
  background: var(--pink);
  border-radius: 100%;
  transform: rotate(-3deg);
}
.sub {
  color: #b7b0bd;
  font-size: clamp(11px, 1.9dvh, 15px);
  line-height: 1.7;
  margin: clamp(14px, 3dvh, 32px) 0 0;
}
.crew {
  position: relative;
  --crew-height: min(38dvh, 34vw);
  --greeting-height: 42px;
  width: min(100%, 1000px, calc((var(--crew-height) - var(--greeting-height)) * 2.7096774));
  height: var(--crew-height);
  min-height: 0;
  flex-shrink: 1;
  display: flex;
  flex-direction: column;
}
.crew :deep(svg) {
  width: 100%;
  height: 100%;
  min-height: 0;
  flex: 1;
}
.greeting-space {
  position: relative;
  height: var(--greeting-height);
  flex-shrink: 0;
  display: grid;
  place-items: center;
}
.crew-hint {
  margin: 0;
  color: #b6aebe;
  font-size: 11px;
  letter-spacing: 1px;
}
.greeting-bubble {
  --bubble-width: 240px;
  position: absolute;
  left: clamp(0px, calc(var(--anchor) - var(--bubble-width) / 2), calc(100% - var(--bubble-width)));
  width: var(--bubble-width);
  max-width: 100%;
  margin: 0;
  padding: 9px 12px;
  border: 1px solid #796374;
  border-radius: 15px;
  background: #261b28;
  color: #ffe6f5;
  font-size: 13px;
  text-align: center;
  box-shadow: 0 8px 30px #0005;
  pointer-events: none;
}
.greeting-bubble.sounding {
  border-color: #ff72bf;
  box-shadow: 0 0 18px #ff36aa26;
}
.login-footer {
  min-height: 0;
  padding: 0;
  color: #aaa1b0;
  font-size: 10px;
  flex-shrink: 0;
}
.primary {
  border: 1px solid var(--pink);
  border-radius: 30px;
  padding: 13px 25px;
  background: var(--pink);
  color: white;
  font-size: 14px;
  font-weight: 700;
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

.auth-dialog {
  position: fixed;
  inset: auto;
  left: 50%;
  top: calc(var(--login-viewport-top, 0px) + var(--login-viewport-height, 100dvh) / 2);
  transform: translate(-50%, -50%);
  margin: 0;
  max-height: calc(var(--login-viewport-height, 100dvh) - 24px);
  overscroll-behavior: contain;
}
@media (max-width: 700px) {
  .brand {
    font-size: 28px;
    gap: 8px;
  }
  .brand span {
    display: none;
  }
  .right {
    gap: 8px;
  }
  .quiet {
    padding: 9px 12px;
    font-size: 12px;
  }
  .hero h1 {
    font-size: clamp(30px, min(14vw, 11dvh), 64px);
    letter-spacing: -3px;
  }
  .eyebrow {
    letter-spacing: 1.5px;
    font-size: 9px;
  }
  .crew {
    --crew-height: min(34dvh, 44vw);
  }
  .greeting-bubble {
    --bubble-width: 210px;
    font-size: 12px;
  }
  .card {
    padding: 26px 22px;
  }
  .login-footer {
    font-size: 9px;
    line-height: 1.4;
  }
  .login-stage :deep(.site-footer) {
    padding: 0;
  }
  .sub {
    font-size: 13px;
  }
}
@media (max-height: 480px) {
  .login-stage {
    padding-top: 8px;
    gap: 4px;
  }
  .brand {
    font-size: 25px;
  }
  .brand span {
    display: none;
  }
  .quiet {
    padding: 7px 12px;
    font-size: 11px;
  }
  .eyebrow {
    display: none;
  }
  .hero h1 {
    font-size: clamp(24px, 9dvh, 42px);
    letter-spacing: -2px;
  }
  .hero h1 br {
    display: none;
  }
  .hero h1 em {
    letter-spacing: -2px;
  }
  .hero h1 em:after {
    height: 3px;
    bottom: -3px;
  }
  .sub {
    margin-top: 10px;
    font-size: 11px;
  }
  .sub br {
    display: none;
  }
  .stage-content {
    gap: 4px;
  }
  .crew {
    --crew-height: 42dvh;
    --greeting-height: 30px;
  }
  .crew-hint {
    font-size: 9px;
  }
  .greeting-bubble {
    padding: 4px 8px;
    font-size: 11px;
  }
  .login-footer {
    font-size: 9px;
  }
}
@media (prefers-reduced-motion: reduce) {
  *,
  *:before,
  *:after {
    transition: none !important;
    animation: none !important;
  }
  button:hover {
    transform: none;
  }
}
</style>
