<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { toast } from '@/composables/toast'
import { startFeishuLogin } from '@/api/feishu'

const qrCanvas = ref<HTMLCanvasElement | null>(null)

const feats = [
  { icon: 'ri-node-tree', text: '目标、项目、任务与知识双向关联' },
  { icon: 'ri-team-line', text: '用负责人与可见范围表达归属，而非个人版 / 团队版' },
  { icon: 'ri-flashlight-line', text: '自动化沉淀日常：群聊、照片、账单' },
]
const alts = [
  { icon: 'ri-mail-line', color: '#5A5E6B', label: '邮箱' },
  { icon: 'ri-google-fill', color: '#D65454', label: 'Google' },
  { icon: 'ri-wechat-fill', color: '#2C8A5B', label: '微信' },
]

/** 伪二维码：确定性随机点阵 + 三个定位角，纯 canvas 生成（无真实登录信息）。 */
function drawQR(seed: number) {
  const el = qrCanvas.value
  if (!el) return
  const n = 25
  el.width = 384
  el.height = 384
  const s = 384 / n
  const ctx = el.getContext('2d')
  if (!ctx) return
  let state = seed
  const rnd = () => {
    state = (state * 1103515245 + 12345) % 2147483648
    return state / 2147483648
  }
  ctx.fillStyle = '#FFFFFF'
  ctx.fillRect(0, 0, 384, 384)
  ctx.fillStyle = '#23262F'
  const fin = (x: number, y: number) =>
    (x < 8 && y < 8) || (x >= n - 8 && y < 8) || (x < 8 && y >= n - 8)
  for (let y = 0; y < n; y++)
    for (let x = 0; x < n; x++) {
      if (fin(x, y)) continue
      if (rnd() > 0.55) ctx.fillRect(x * s + 0.5, y * s + 0.5, s - 1, s - 1)
    }
  const box = (cx: number, cy: number) => {
    ctx.fillRect(cx * s, cy * s, 7 * s, s)
    ctx.fillRect(cx * s, (cy + 6) * s, 7 * s, s)
    ctx.fillRect(cx * s, cy * s, s, 7 * s)
    ctx.fillRect((cx + 6) * s, cy * s, s, 7 * s)
    ctx.fillRect((cx + 2) * s, (cy + 2) * s, 3 * s, 3 * s)
  }
  box(0, 0)
  box(n - 7, 0)
  box(0, n - 7)
}

function refreshQR() {
  drawQR(Math.floor(Math.random() * 2 ** 31))
}

function loginGo() {
  void startFeishuLogin('qr')
}

function authorizationLogin() { void startFeishuLogin('authorization') }

function altLogin(label: string) {
  toast(`已预留「${label}」登录入口，本次演示未接入`)
}

onMounted(() => drawQR(20260723))
</script>

<template>
  <div class="login">
    <aside class="brand">
      <div class="brand-top">
        <div class="brand-mark">W</div>
        <div class="brand-name">WAO<span>Wayuwa All in One</span></div>
      </div>
      <div>
        <h1 class="brand-title">把人生的目标、项目与知识，放进同一个系统</h1>
        <p class="brand-sub">
          面向少数成员长期共同维护的 Life OS——所有内容彼此关联，处于同一个可查询的上下文网络。
        </p>
        <ul class="feats">
          <li v-for="f in feats" :key="f.text">
            <span class="feat-icon"><i :class="f.icon"></i></span>{{ f.text }}
          </li>
        </ul>
      </div>
      <div class="brand-foot">演示原型 · v0.1 · 2026 年 7 月</div>
    </aside>

    <section class="panel">
      <div class="card">
        <div class="card-title">登录「娃屋」</div>
        <div class="card-sub">初始成员 2 人 · 随时可邀请更多成员加入</div>
        <div class="qr-wrap"><canvas ref="qrCanvas" class="qr"></canvas></div>
        <div class="qr-status">
          <span class="dot"></span>二维码有效
          <button class="link" @click="refreshQR">刷新</button>
        </div>
        <div class="qr-hint">打开 <b>飞书 App</b> 扫一扫，安全登录</div>
        <button class="primary" @click="loginGo">
          <i class="ri-qr-scan-2-line"></i>模拟扫码成功，进入系统
        </button>
        <button class="sso" @click="authorizationLogin">使用飞书授权登录</button>
        <div class="divider"><span></span>其他登录方式（预留）<span></span></div>
        <div class="alts">
          <button v-for="a in alts" :key="a.label" class="alt" @click="altLogin(a.label)">
            <i :class="a.icon" :style="{ color: a.color }"></i>{{ a.label }}
          </button>
        </div>
        <button class="sso" disabled>企业 SSO 接入（规划中）</button>
        <div class="card-foot">本页仅为交互演示，不发起真实认证请求</div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login {
  position: fixed;
  inset: 0;
  display: flex;
  background: #ffffff;
}
.brand {
  width: 42%;
  min-width: 340px;
  background: #20263b;
  color: #ffffff;
  padding: 44px 48px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.brand-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--wao-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 16px;
}
.brand-name {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.3px;
}
.brand-name span {
  margin-left: 8px;
  color: #8e96b3;
  font-weight: 400;
  font-size: 12px;
}
.brand-title {
  font-size: 31px;
  font-weight: 700;
  line-height: 1.45;
  letter-spacing: 0.5px;
  margin: 0;
  text-wrap: balance;
}
.brand-sub {
  margin-top: 14px;
  color: #a7aec7;
  font-size: 13.5px;
  line-height: 1.9;
}
.feats {
  margin-top: 30px;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.feats li {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #c9cedf;
  font-size: 13px;
}
.feat-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 14px;
  color: #9da8d8;
}
.brand-foot {
  color: #6e7590;
  font-size: 12px;
}

.panel {
  flex: 1;
  display: flex;
  padding: 40px;
  overflow-y: auto;
}
.card {
  width: 380px;
  margin: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.card-title {
  font-size: 22px;
  font-weight: 700;
}
.card-sub {
  margin-top: 8px;
  font-size: 13px;
  color: var(--wao-text-weak);
}
.qr-wrap {
  margin-top: 22px;
  padding: 12px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
}
.qr {
  display: block;
  width: 192px;
  height: 192px;
}
.qr-status {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--wao-text-3);
}
.qr-status .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--wao-success);
}
.link {
  border: none;
  background: none;
  color: var(--wao-primary);
  font-size: 12px;
  cursor: pointer;
  padding: 2px 4px;
}
.qr-hint {
  margin-top: 2px;
  font-size: 13px;
  color: var(--wao-text-3);
}
.primary {
  margin-top: 18px;
  width: 100%;
  height: 40px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 9px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
.primary:hover {
  background: var(--wao-primary-hover);
}
.primary i {
  font-size: 16px;
}
.divider {
  margin-top: 20px;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  color: #b9bcc6;
  font-size: 12px;
}
.divider span {
  flex: 1;
  height: 1px;
  background: #ecedf1;
}
.alts {
  margin-top: 12px;
  width: 100%;
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 10px;
}
.alt {
  height: 38px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 9px;
  font-size: 13px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
}
.alt:hover {
  background: #f4f5f8;
}
.alt i {
  font-size: 15px;
}
.sso {
  margin-top: 10px;
  width: 100%;
  height: 36px;
  border: 1px dashed var(--wao-line-strong);
  background: var(--wao-bg-sub-2);
  border-radius: 9px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.card-foot {
  margin-top: 18px;
  font-size: 12px;
  color: #b9bcc6;
  text-align: center;
}
</style>
