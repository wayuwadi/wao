<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { startFeishuLogin } from '@/api/feishu'
import { useSessionStore } from '@/stores/session'
import { mountFeishuQr } from '@/utils/feishuQr'
import { feishuInAppLogin, isInFeishu } from '@/utils/feishuInApp'
import { safeAuthTarget } from '@/composables/breathingAuth'
import {
  preferredLoginMethod,
  rememberLoginMethod,
  prefersFeishuAuthorization,
} from '@/utils/loginPreference'
import { reauthenticateWithFeishu } from '@/utils/reauthPopup'
import CompanionAvatar from '@/components/companions/CompanionAvatar.vue'
import { COMPANION_IDS, selectCompanion } from '@/utils/companionIdentity'

const props = defineProps<{
  target: string
  beforeNavigate: (url: string) => void
  recovery?: boolean
}>()
const emit = defineEmits<{ opened: []; authenticated: []; closed: []; claim: [] }>()
const router = useRouter(),
  session = useSessionStore()
const method = ref<'feishu' | 'email'>('feishu')
const authorizationFirst = ref(false)
const showPassword = ref(false)
const email = ref(''),
  password = ref('')
const busy = ref(false),
  error = ref(''),
  qrError = ref(''),
  inAppError = ref('')
const inAppTrying = ref(false),
  authorizationBusy = ref(false)
const working = computed(() => busy.value || authorizationBusy.value || inAppTrying.value)
let returnFocus: HTMLElement | null = null
const dialog = ref<HTMLDialogElement | null>(null)
const opened = ref(false)
const welcome = ref(false)
let welcomed = false
const recoveryCompanion = computed(() => selectCompanion('reauth', session.userId))
const viewportHeight = ref(0),
  viewportTop = ref(0)
function measureViewport() {
  viewportHeight.value = window.visualViewport?.height ?? window.innerHeight
  viewportTop.value = window.visualViewport?.offsetTop ?? 0
}
let unmountQr: (() => void) | undefined
let qrGeneration = 0
let disposed = false
let recoveryAbort: AbortController | undefined
function stopQr() {
  qrGeneration++
  unmountQr?.()
  unmountQr = undefined
}
async function loadQr() {
  stopQr()
  const generation = qrGeneration
  const active = () =>
    !disposed &&
    opened.value &&
    method.value === 'feishu' &&
    !authorizationFirst.value &&
    generation === qrGeneration
  qrError.value = ''
  await nextTick()
  if (!active()) return
  try {
    const cleanup = await mountFeishuQr(
      'feishu-qr',
      active,
      (url) => {
        try {
          props.beforeNavigate(url)
        } catch (reason) {
          inAppError.value =
            reason instanceof Error ? reason.message : '无法暂存练习，请使用邮箱登录。'
          return false
        }
      },
      () => {
        if (!active()) return
        qrError.value = '二维码已过期，请刷新后重新扫码。'
        stopQr()
      },
    )
    if (active()) unmountQr = cleanup
    else cleanup()
  } catch (reason) {
    if (active()) {
      qrError.value =
        reason instanceof Error && reason.message
          ? reason.message
          : '飞书二维码暂时加载失败，请重试或使用下方授权登录。'
    }
  }
}
async function openAuth() {
  if (opened.value) return
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  emit('opened')
  measureViewport()
  method.value = isInFeishu() ? 'feishu' : preferredLoginMethod()
  authorizationFirst.value = !!props.recovery || !!prefersFeishuAuthorization()
  error.value = ''
  qrError.value = ''
  inAppError.value = ''
  welcome.value = false
  if (!props.recovery && !welcomed) {
    try {
      welcome.value = !sessionStorage.getItem('wao-companion-welcome:v1')
      sessionStorage.setItem('wao-companion-welcome:v1', 'seen')
    } catch {
      welcome.value = true
    }
    welcomed = true
  }
  opened.value = true
  await nextTick()
  if (disposed || !opened.value) return
  dialog.value?.showModal()
  if (isInFeishu()) await tryInApp()
  else if (method.value === 'feishu' && !authorizationFirst.value) await loadQr()
}
function onClose() {
  opened.value = false
  showPassword.value = false
  recoveryAbort?.abort()
  stopQr()
  emit('closed')
  if (returnFocus?.isConnected) returnFocus.focus()
}
function closeAuth() {
  if (!working.value && !props.recovery) dialog.value?.close()
}
async function switchMethod(next: 'feishu' | 'email') {
  if (working.value) return
  stopQr()
  method.value = next
  showPassword.value = false
  error.value = ''
  if (next === 'feishu' && !authorizationFirst.value) {
    await loadQr()
  }
}
async function authorizeFeishu() {
  if (authorizationBusy.value) return
  authorizationBusy.value = true
  inAppError.value = ''
  try {
    if (props.recovery) {
      recoveryAbort = new AbortController()
      await reauthenticateWithFeishu(recoveryAbort.signal)
      if (disposed) return
      await session.recover()
      rememberLoginMethod('feishu')
      dialog.value?.close()
      emit('authenticated')
      return
    }
    await startFeishuLogin('authorization', (url) => {
      if (disposed || !opened.value) return false
      props.beforeNavigate(url)
    })
  } catch (reason) {
    if (disposed) return
    inAppError.value =
      reason instanceof Error && (props.recovery || 'status' in reason)
        ? reason.message
        : `暂时无法连接飞书，请重试或使用邮箱登录。${reason instanceof Error ? reason.message : ''}`
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
    rememberLoginMethod('email')
    password.value = ''
    if (disposed || !opened.value) return
    dialog.value?.close()
    emit('authenticated')
    if (!props.recovery && props.target !== '/breathe')
      await router.replace(safeAuthTarget(props.target))
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '登录失败，请稍后重试。'
  } finally {
    busy.value = false
  }
}
onMounted(() => {
  measureViewport()
  window.visualViewport?.addEventListener('resize', measureViewport)
  window.visualViewport?.addEventListener('scroll', measureViewport)
  window.addEventListener('resize', measureViewport)
})
async function tryInApp() {
  inAppTrying.value = true
  try {
    const resolution = await feishuInAppLogin()
    if (disposed || !opened.value) return
    if (resolution) {
      await session.acceptResolution(resolution)
      if (disposed || !opened.value) return
      if (resolution.claimRequired) {
        emit('claim')
        await router.replace('/auth/claim')
      } else {
        rememberLoginMethod('feishu')
        dialog.value?.close()
        emit('authenticated')
        if (!props.recovery && props.target !== '/breathe')
          await router.replace(safeAuthTarget(props.target))
      }
      return
    }
  } catch {
    if (!disposed) inAppError.value = '飞书免登未完成，请改用其他方式登录。'
  } finally {
    inAppTrying.value = false
  }
  if (!disposed && opened.value && method.value === 'feishu' && !authorizationFirst.value)
    await loadQr()
}
async function showQr() {
  authorizationFirst.value = false
  await loadQr()
}
async function checkRecovery() {
  if (busy.value) return
  busy.value = true
  inAppError.value = ''
  try {
    await session.recover()
    if (disposed) return
    dialog.value?.close()
    emit('authenticated')
  } catch (reason) {
    if (!disposed)
      inAppError.value = reason instanceof Error ? reason.message : '尚未恢复登录，请重试。'
  } finally {
    busy.value = false
  }
}
defineExpose({ open: openAuth })
onBeforeUnmount(() => {
  disposed = true
  recoveryAbort?.abort()
  window.visualViewport?.removeEventListener('resize', measureViewport)
  window.visualViewport?.removeEventListener('scroll', measureViewport)
  window.removeEventListener('resize', measureViewport)
  stopQr()
})
</script>
<template>
  <dialog
    ref="dialog"
    class="auth-dialog"
    :style="{
      '--login-viewport-height': `${viewportHeight}px`,
      '--login-viewport-top': `${viewportTop}px`,
    }"
    aria-labelledby="auth-title"
    @close="onClose"
    @cancel="(working || recovery) && $event.preventDefault()"
  >
    <section class="card">
      <button
        v-if="!recovery"
        class="close"
        aria-label="关闭登录弹窗"
        :disabled="working"
        @click="closeAuth"
      >
        ×
      </button>
      <header class="auth-welcome" :class="{ recovery }">
        <CompanionAvatar v-if="recovery" :identity="recoveryCompanion" :size="44" />
        <div>
          <span class="tiny">WAO · 准备好，再出发</span>
          <h2 id="auth-title"><template v-if="recovery">重新登录，<span class="recovery-tail">继续刚才的内容</span></template><template v-else>欢迎回到 WAO</template></h2>
          <p class="product-summary">
            {{
              recovery
                ? `登录已过期，请使用 ${session.currentUser.name} 的原账号登录。当前页面和草稿已保留。`
                : '安排待办、整理资料，与 AI 一起完成任务。'
            }}
          </p>
        </div>
      </header>
      <div v-if="welcome && !recovery" class="welcome-companions" aria-hidden="true">
        <CompanionAvatar
          v-for="identity in COMPANION_IDS"
          :key="identity"
          :identity="identity"
          :size="36"
        />
      </div>
      <template v-if="method === 'feishu'">
        <template v-if="inAppTrying">
          <p class="subtitle">检测到飞书环境，正在免登…</p>
          <div class="in-app-loading"><i class="ri-loader-4-line"></i>正在通过飞书身份自动登录</div>
        </template>
        <template v-else>
          <p class="subtitle">
            {{
              authorizationFirst
                ? '使用飞书授权，快速进入你的 WAO'
                : '使用飞书扫码，快速进入你的 WAO'
            }}
          </p>
          <div v-if="!authorizationFirst && !qrError" id="feishu-qr" class="qr">
            <div class="qr-loading"><i class="ri-loader-4-line"></i>正在加载飞书二维码…</div>
          </div>
          <div v-else-if="!authorizationFirst" class="qr-fallback">
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
            ><span>{{
              authorizationBusy
                ? '正在等待飞书登录…'
                : authorizationFirst
                  ? '使用飞书授权登录'
                  : qrError
                    ? '改用飞书授权登录'
                    : '二维码不方便？使用飞书授权登录'
            }}</span
            ><i class="ri-arrow-right-s-line"></i>
          </button>
          <button
            v-if="authorizationFirst && !recovery"
            type="button"
            class="switch"
            @click="showQr"
          >
            改用二维码扫码
          </button>
          <button
            v-if="recovery"
            type="button"
            class="switch"
            :disabled="busy"
            @click="checkRecovery"
          >
            已在其他窗口登录？检查登录状态
          </button>
        </template>
      </template>

      <template v-else>
        <p class="subtitle">使用邮箱密码登录</p>
        <form :aria-busy="busy" class="email-form" @submit.prevent="submitEmail">
          <label
            >邮箱<input
              :disabled="working"
              v-model.trim="email"
              type="email"
              autocomplete="email"
              required
              placeholder="你的邮箱地址"
          /></label>
          <label
            >密码<input
              :disabled="working"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              minlength="8"
              required
              placeholder="输入账号密码"
          /></label>
          <button
            class="switch"
            type="button"
            :disabled="working"
            :aria-pressed="showPassword"
            @click="showPassword = !showPassword"
          >
            {{ showPassword ? '隐藏密码' : '显示密码' }}
          </button>
          <div v-if="error" class="error" role="alert">{{ error }}</div>
          <button class="primary" :disabled="working">{{ busy ? '正在登录…' : '登录' }}</button>
        </form>
      </template>

      <div class="other-methods">
        <span>其他登录方式</span>
        <button
          :disabled="working"
          v-if="method !== 'feishu'"
          type="button"
          @click="switchMethod('feishu')"
        >
          <i class="ri-feishu-line"></i>飞书
        </button>
        <button
          :disabled="working"
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
</template>
<style scoped>
.auth-welcome.recovery {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.auth-welcome.recovery > div {
  min-width: 0;
}
.recovery-tail { display:inline-block; }
.welcome-companions {
  display: flex;
  gap: 8px;
  margin: 10px 0 0;
}
.primary {
  border: 1px solid var(--wao-primary-text);
  border-radius: 30px;
  padding: 13px 25px;
  background: var(--wao-primary-text);
  color: white;
  font-size: 14px;
  font-weight: 700;
}
.auth-dialog {
  color-scheme: inherit;
  padding: 0;
  border: 1px solid #49404f;
  border-radius: 24px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
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
.qr-error .switch {
  display: block;
  margin-top: 6px;
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
  background: var(--wao-bg-page);
  padding: 12px;
  color: var(--wao-text);
  border-radius: 10px;
}
.email-form .primary {
  width: 100%;
  margin-top: 18px;
}
.switch {
  color: var(--wao-primary-text);
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

.auth-dialog {
  --wao-primary-text: #666273;
  color: var(--wao-text);
}
.card :is(p, label, .tiny, .close, .other-methods) {
  color: var(--wao-text-weak);
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
  outline: 2px solid #a7a2b4;
  outline-offset: 3px;
}
.authorization-cta {
  background: var(--wao-bg-page);
  color: var(--wao-text);
  border-color: var(--wao-line);
}
.error,
.qr-error {
  color: var(--wao-danger-strong);
}
.card,
.email-form input {
  box-sizing: border-box;
}
@media (max-width: 480px) {
  .card {
    padding: 26px 20px;
  }
}
</style>
<style scoped>
.auth-dialog {
  color-scheme: light;
  background: #f7f7f9;
  color: #34343b;
  border-color: #d1d1d8;
  --wao-text: #34343b;
  --wao-text-weak: #666670;
  --wao-bg-page: #ededf0;
  --wao-line: #d1d1d8;
}
:global(.login-stage[data-entry-theme='dark'] .auth-dialog) {
  color-scheme: dark;
  background: #17171b;
  color: #e5e5e8;
  border-color: #38383f;
  --wao-text: #e5e5e8;
  --wao-text-weak: #a1a1aa;
  --wao-bg-page: #090a0c;
  --wao-line: #38383f;
}
.other-methods button {
  background: var(--wao-bg-page);
  color: var(--wao-text);
  border-color: var(--wao-line);
}
.email-form input {
  border-color: var(--wao-line);
}
</style>
