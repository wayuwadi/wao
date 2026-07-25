<script setup lang="ts">
import { toast } from '@/composables/toast'
import { startFeishuLogin } from '@/api/feishu'

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

function loginGo() {
  void startFeishuLogin('qr')
}

function authorizationLogin() { void startFeishuLogin('authorization') }

function altLogin(label: string) {
  toast(`已预留「${label}」登录入口，本次演示未接入`)
}

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
        <div class="qr-wrap">
          <i class="ri-qr-scan-2-line qr-icon"></i>
        </div>
        <div class="qr-hint">点击后进入飞书官方页面扫码，WAO 不接触你的飞书密码</div>
        <button class="primary" @click="loginGo">
          <i class="ri-qr-scan-2-line"></i>使用飞书扫码登录
        </button>
        <button class="sso" @click="authorizationLogin">使用飞书授权登录</button>
        <div class="divider"><span></span>其他登录方式（预留）<span></span></div>
        <div class="alts">
          <button v-for="a in alts" :key="a.label" class="alt" @click="altLogin(a.label)">
            <i :class="a.icon" :style="{ color: a.color }"></i>{{ a.label }}
          </button>
        </div>
        <button class="sso" disabled>企业 SSO 接入（规划中）</button>
        <div class="card-foot">登录请求由飞书官方认证服务处理</div>
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
.qr-icon {
  display: grid;
  place-items: center;
  width: 192px;
  height: 192px;
  border-radius: 8px;
  background: var(--wao-bg-sub-2);
  color: var(--wao-primary);
  font-size: 88px;
}
.qr-hint {
  margin-top: 10px;
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
