<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { startFeishuLogin } from '@/api/feishu'
import { startWechatLogin } from '@/api/wechat'
import { useSessionStore } from '@/stores/session'
import { mountFeishuQr } from '@/utils/feishuQr'
import SiteFooter from '@/components/SiteFooter.vue'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const method = ref<'feishu' | 'email'>('feishu')
const mode = ref<'login' | 'register'>('login')
const name = ref('')
const email = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const qrError = ref('')
const providerError = ref('')
let unmountQr: (() => void) | undefined

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

async function switchMethod(next: 'feishu' | 'email') {
  method.value = next
  error.value = ''
  providerError.value = ''
  if (next === 'feishu') await loadQr()
  else { unmountQr?.(); unmountQr = undefined }
}

async function loginWithWechat() {
  providerError.value = ''
  try { await startWechatLogin() }
  catch (reason) { providerError.value = reason instanceof Error ? reason.message : '微信登录暂不可用。' }
}

onMounted(loadQr)
onBeforeUnmount(() => unmountQr?.())
</script>

<template>
  <div class="login">
    <aside class="brand">
      <div class="brand-top"><div class="brand-mark">W</div><div class="brand-name">WAO<span>Wayuwa All in One</span></div></div>
      <div>
        <h1>想法有多大，产出就有多大。</h1>
        <p class="brand-slogan">你只管把要做的事提出来——拆解、执行、交付交给 AI：多台电脑并行开工，跑完直接等你验收。</p>
        <p>一个 WAO 账号可以同时绑定飞书、微信和邮箱。无论从哪个入口登录，都会回到同一个成员身份，不再出现娃哥、娃弟会话串用。</p>
        <ul><li><i class="ri-shield-user-line"></i>服务端安全会话，不再依赖浏览器本地身份</li><li><i class="ri-link-m"></i>多种登录方式绑定同一账号</li><li><i class="ri-node-tree"></i>目标、项目、任务与知识双向关联</li></ul>
      </div>
      <div class="brand-foot">WAO · 统一账号中心</div>
    </aside>

    <main class="panel">
      <section class="card">
        <h2>登录 WAO</h2>
        <template v-if="method === 'feishu'">
          <p class="subtitle">使用飞书扫码，快速进入你的 WAO</p>
          <div v-if="!qrError" id="feishu-qr" class="qr"><div class="qr-loading"><i class="ri-loader-4-line"></i>正在加载飞书二维码…</div></div>
          <div v-else class="qr-fallback"><i class="ri-qr-code-line"></i><span>二维码暂不可用</span></div>
          <div v-if="qrError" class="qr-error">{{ qrError }}</div>
          <button class="authorization-cta" type="button" @click="startFeishuLogin('authorization')"><i class="ri-feishu-line"></i><span>{{ qrError ? '改用飞书授权登录' : '二维码不方便？使用飞书授权登录' }}</span><i class="ri-arrow-right-s-line"></i></button>
        </template>

        <template v-else>
          <p class="subtitle">{{ mode === 'login' ? '使用邮箱密码登录' : '创建一个新的 WAO 账号' }}</p>
          <form class="email-form" @submit.prevent="submitEmail">
            <input v-if="mode === 'register'" v-model.trim="name" autocomplete="name" required placeholder="成员名称" />
            <input v-model.trim="email" type="email" autocomplete="email" required placeholder="邮箱" />
            <input v-model="password" type="password" :autocomplete="mode === 'register' ? 'new-password' : 'current-password'" minlength="8" required placeholder="密码（至少 8 位）" />
            <div v-if="error" class="error">{{ error }}</div>
            <button class="primary" :disabled="busy">{{ busy ? '处理中…' : mode === 'login' ? '登录' : '创建账号' }}</button>
          </form>
          <button class="switch" type="button" @click="mode = mode === 'login' ? 'register' : 'login'; error = ''">
            {{ mode === 'login' ? '没有账号？使用邮箱注册' : '已有账号？返回登录' }}
          </button>
        </template>

        <div class="other-methods">
          <span>其他登录方式</span>
          <button v-if="method !== 'feishu'" type="button" @click="switchMethod('feishu')"><i class="ri-feishu-line"></i>飞书</button>
          <button v-if="method !== 'email'" type="button" @click="switchMethod('email')"><i class="ri-mail-line"></i>邮箱</button>
          <button type="button" @click="loginWithWechat"><i class="ri-wechat-fill"></i>微信</button>
        </div>
        <div v-if="providerError" class="error">{{ providerError }}</div>
        <p class="foot">首次使用第三方登录时只需确认一次成员身份，之后会自动进入同一账号。</p>
      </section>
      <SiteFooter class="login-footer" />
    </main>
  </div>
</template>

<style scoped>
.login { position: fixed; inset: 0; display: flex; background: #fff; }
.brand { width: 42%; min-width: 340px; padding: 44px 48px; display: flex; flex-direction: column; justify-content: space-between; background: #20263b; color: #fff; }
.brand-top { display: flex; align-items: center; gap: 10px; }.brand-mark { width: 32px; height: 32px; display: grid; place-content: center; border-radius: 9px; background: var(--wao-primary); font-weight: 700; }.brand-name { font-weight: 600; }.brand-name span { margin-left: 8px; color: #8e96b3; font-size: 12px; font-weight: 400; }
h1 { max-width: 540px; margin: 0; font-size: 31px; line-height: 1.45; } .brand p { color: #a7aec7; font-size: 13.5px; line-height: 1.9; }.brand-slogan { max-width: 520px; margin-bottom: 20px; }.brand ul { padding: 0; list-style: none; color: #c9cedf; font-size: 13px; line-height: 2.6; }.brand li { display: flex; align-items: center; gap: 10px; }.brand li i { color: #9da8d8; font-size: 16px; }.brand-foot { color: #6e7590; font-size: 12px; }
.panel { position: relative; flex: 1; display: flex; padding: 32px 32px 68px; overflow-y: auto; background: radial-gradient(circle at 50% 20%, #fff 0, #f4f6fb 72%); }.card { width: 368px; margin: auto; padding: 32px; border: 1px solid rgba(220,223,232,.9); border-radius: 20px; background: rgba(255,255,255,.96); box-shadow: 0 18px 52px rgba(32, 38, 59, .10); }.card h2 { margin: 0; text-align: center; font-size: 24px; letter-spacing: -.5px; }.subtitle { margin: 8px 0 20px; text-align: center; color: var(--wao-text-weak); font-size: 13px; }
.login-footer { position: absolute; right: 0; bottom: 0; left: 0; }
.qr { width: 100%; max-width: 302px; aspect-ratio: 1; margin: auto; overflow: hidden; border: 1px solid var(--wao-line); border-radius: 14px; }.qr-loading { height: 100%; display: grid; place-content: center; gap: 8px; text-align: center; color: var(--wao-text-weak); font-size: 12px; }.qr-loading i { font-size: 30px; color: var(--wao-primary); }.qr-error,.error { margin-top: 8px; color: var(--wao-danger-strong); font-size: 12px; text-align: center; }
.qr-fallback { width: 100%; max-width: 302px; height: 160px; margin: auto; display: grid; place-content: center; gap: 8px; text-align: center; border: 1px dashed var(--wao-line-strong); border-radius: 14px; color: var(--wao-text-weak); font-size: 12px; }.qr-fallback i { color: var(--wao-primary); font-size: 30px; }
button,input { width: 100%; height: 42px; border-radius: 10px; font-size: 13px; } input { box-sizing: border-box; margin-top: 10px; padding: 0 13px; border: 1px solid var(--wao-line-control); outline: none; } input:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 3px rgba(67,88,214,.12); } button { cursor: pointer; }.primary { margin-top: 12px; border: none; background: var(--wao-primary); color: #fff; font-weight: 600; }.primary:disabled { opacity: .6; }.switch { height: 32px; border: none; background: none; color: var(--wao-primary); }.authorization-cta { margin-top: 12px; padding: 0 13px; display: flex; align-items: center; gap: 8px; border: 1px solid rgba(51,112,255,.28); background: #f3f6ff; color: #2855c7; font-weight: 600; }.authorization-cta:hover { border-color: #3370ff; background: #eaf0ff; box-shadow: 0 4px 12px rgba(51,112,255,.12); }.authorization-cta span { flex: 1; }.authorization-cta .ri-feishu-line { color: #3370ff; font-size: 16px; }.authorization-cta .ri-arrow-right-s-line { font-size: 17px; }.email-form { padding-top: 4px; }
.other-methods { margin-top: 20px; padding-top: 16px; border-top: 1px solid #eceef3; display: flex; align-items: center; justify-content: center; gap: 8px; }.other-methods span { margin-right: 4px; color: var(--wao-text-faint); font-size: 11px; }.other-methods button { width: auto; height: 30px; padding: 0 10px; border: 1px solid #e3e5ec; border-radius: 8px; background: #fafbfc; color: var(--wao-text-weak); font-size: 12px; }.other-methods i { margin-right: 4px; }.other-methods .ri-feishu-line { color: #3370ff; }.other-methods .ri-wechat-fill { color: #2c8a5b; }.foot { margin: 14px 0 0; color: var(--wao-text-faint); font-size: 11px; line-height: 1.6; text-align: center; }
@media (max-width: 760px) { .brand { display: none; }.panel { padding: 16px 16px 68px; }.card { width: min(368px, calc(100vw - 32px)); box-sizing: border-box; padding: 22px; } }
</style>
