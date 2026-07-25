<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { startFeishuLogin } from '@/api/feishu'
import { startWechatLogin } from '@/api/wechat'
import { useSessionStore } from '@/stores/session'
import { mountFeishuQr } from '@/utils/feishuQr'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const mode = ref<'login' | 'register'>('login')
const name = ref('')
const email = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const qrError = ref('')
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
  qrError.value = ''
  await nextTick()
  try { unmountQr = await mountFeishuQr('feishu-qr') }
  catch { qrError.value = '飞书二维码暂时加载失败，请使用下方“飞书授权登录”。' }
}

onMounted(loadQr)
onBeforeUnmount(() => unmountQr?.())
</script>

<template>
  <div class="login">
    <aside class="brand">
      <div class="brand-top"><div class="brand-mark">W</div><div class="brand-name">WAO<span>Wayuwa All in One</span></div></div>
      <div>
        <h1>把人生的目标、项目与知识，放进同一个系统</h1>
        <p>一个 WAO 账号可以同时绑定飞书、微信和邮箱。无论从哪个入口登录，都会回到同一个成员身份，不再出现娃哥、娃弟会话串用。</p>
        <ul><li><i class="ri-shield-user-line"></i>服务端安全会话，不再依赖浏览器本地身份</li><li><i class="ri-link-m"></i>多种登录方式绑定同一账号</li><li><i class="ri-node-tree"></i>目标、项目、任务与知识双向关联</li></ul>
      </div>
      <div class="brand-foot">WAO · 统一账号中心</div>
    </aside>

    <main class="panel">
      <section class="card">
        <h2>登录 WAO</h2>
        <p class="subtitle">飞书扫码无需电脑安装飞书客户端</p>

        <div id="feishu-qr" class="qr"><div class="qr-loading"><i class="ri-loader-4-line"></i>正在加载飞书二维码…</div></div>
        <div v-if="qrError" class="qr-error">{{ qrError }}</div>
        <button class="secondary" type="button" @click="startFeishuLogin('authorization')"><i class="ri-feishu-line"></i>使用飞书授权登录</button>

        <div class="divider"><span></span>或使用邮箱<span></span></div>
        <form @submit.prevent="submitEmail">
          <input v-if="mode === 'register'" v-model.trim="name" autocomplete="name" required placeholder="姓名（例如：娃哥）" />
          <input v-model.trim="email" type="email" autocomplete="email" required placeholder="邮箱" />
          <input v-model="password" type="password" :autocomplete="mode === 'register' ? 'new-password' : 'current-password'" minlength="8" required placeholder="密码（至少 8 位）" />
          <div v-if="error" class="error">{{ error }}</div>
          <button class="primary" :disabled="busy">{{ busy ? '处理中…' : mode === 'login' ? '邮箱登录' : '创建账号' }}</button>
        </form>
        <button class="switch" type="button" @click="mode = mode === 'login' ? 'register' : 'login'; error = ''">
          {{ mode === 'login' ? '首次使用？创建邮箱账号' : '已有账号？返回登录' }}
        </button>

        <div class="divider"><span></span>其他方式<span></span></div>
        <button class="wechat" type="button" @click="startWechatLogin()"><i class="ri-wechat-fill"></i>微信扫码登录</button>
        <p class="foot">首次使用第三方登录时，系统会让你确认已有成员；确认后永久绑定同一 WAO 账号。</p>
      </section>
    </main>
  </div>
</template>

<style scoped>
.login { position: fixed; inset: 0; display: flex; background: #fff; }
.brand { width: 42%; min-width: 340px; padding: 44px 48px; display: flex; flex-direction: column; justify-content: space-between; background: #20263b; color: #fff; }
.brand-top { display: flex; align-items: center; gap: 10px; }.brand-mark { width: 32px; height: 32px; display: grid; place-content: center; border-radius: 9px; background: var(--wao-primary); font-weight: 700; }.brand-name { font-weight: 600; }.brand-name span { margin-left: 8px; color: #8e96b3; font-size: 12px; font-weight: 400; }
h1 { max-width: 540px; margin: 0; font-size: 31px; line-height: 1.45; } .brand p { color: #a7aec7; font-size: 13.5px; line-height: 1.9; }.brand ul { padding: 0; list-style: none; color: #c9cedf; font-size: 13px; line-height: 2.6; }.brand li { display: flex; align-items: center; gap: 10px; }.brand li i { color: #9da8d8; font-size: 16px; }.brand-foot { color: #6e7590; font-size: 12px; }
.panel { flex: 1; display: flex; padding: 32px; overflow-y: auto; background: #fafbfc; }.card { width: 390px; margin: auto; padding: 28px; border: 1px solid var(--wao-line); border-radius: 16px; background: #fff; box-shadow: 0 12px 36px rgba(32, 38, 59, .08); }.card h2 { margin: 0; text-align: center; font-size: 22px; }.subtitle { margin: 7px 0 16px; text-align: center; color: var(--wao-text-weak); font-size: 13px; }
.qr { width: 222px; height: 222px; margin: auto; overflow: hidden; border: 1px solid var(--wao-line); border-radius: 12px; }.qr-loading { height: 100%; display: grid; place-content: center; gap: 8px; text-align: center; color: var(--wao-text-weak); font-size: 12px; }.qr-loading i { font-size: 30px; color: var(--wao-primary); }.qr-error,.error { margin-top: 8px; color: var(--wao-danger-strong); font-size: 12px; text-align: center; }
button,input { width: 100%; height: 40px; border-radius: 9px; font-size: 13px; } input { box-sizing: border-box; margin-top: 9px; padding: 0 12px; border: 1px solid var(--wao-line-control); outline: none; } input:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 3px rgba(67,88,214,.12); } button { cursor: pointer; }.primary { margin-top: 10px; border: none; background: var(--wao-primary); color: #fff; font-weight: 600; }.primary:disabled { opacity: .6; }.secondary,.wechat { border: 1px solid var(--wao-line-strong); background: #fff; color: var(--wao-text-2); }.secondary { margin-top: 12px; }.secondary i { color: #3370ff; }.wechat i { margin-right: 6px; color: #2c8a5b; font-size: 16px; }.switch { height: 30px; border: none; background: none; color: var(--wao-primary); }
.divider { margin: 16px 0 3px; display: flex; align-items: center; gap: 10px; color: #b9bcc6; font-size: 12px; }.divider span { flex: 1; height: 1px; background: #ecedf1; }.foot { margin: 13px 0 0; color: var(--wao-text-faint); font-size: 11.5px; line-height: 1.6; text-align: center; }
@media (max-width: 760px) { .brand { display: none; }.panel { padding: 16px; }.card { padding: 22px; } }
</style>
