<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from "vue";
import { useWorkspace } from "./composables/workspace";
import UpdateDialog from "./components/UpdateDialog.vue";
import { updates } from "./composables/updates";
import { chat } from "./composables/chat";
import { App as NativeApp } from "@capacitor/app";
import { Capacitor, type PluginListenerHandle } from "@capacitor/core";
import AppIcon from "./components/AppIcon.vue";
const demo = import.meta.env.DEV && import.meta.env.MODE === "demo";
const w = useWorkspace(),
  booting = ref(true),
  bootError = ref(""),
  email = ref(""),
  password = ref(""),
  logging = ref(false),
  loginError = ref("");
let poll: ReturnType<typeof setTimeout> | undefined,
  nativeListener: PluginListenerHandle | undefined,
  disposed = false,
  schedule = 0;
function scheduleChat() {
  const ticket = ++schedule;
  clearTimeout(poll);
  if (!chat.account.value || !chat.active.value || disposed) return;
  void chat.refresh().finally(() => {
    if (
      ticket === schedule &&
      !disposed &&
      chat.account.value &&
      chat.active.value
    )
      poll = setTimeout(scheduleChat, 5000);
  });
}
function visibility() {
  chat.active.value = document.visibilityState !== "hidden";
  if (chat.active.value) void updates.check();
  scheduleChat();
}
watch(
  () => w.session.value,
  (session) => {
    chat.reset(session?.accountId || "", session?.member.id || "");
    scheduleChat();
  },
);
async function boot() {
  booting.value = true;
  bootError.value = "";
  try {
    await w.restore();
  } catch (e) {
    bootError.value = e instanceof Error ? e.message : "连接失败";
  } finally {
    booting.value = false;
  }
}
async function login() {
  logging.value = true;
  loginError.value = "";
  try {
    await w.login(email.value.trim(), password.value);
    password.value = "";
    bootError.value = "";
  } catch (e) {
    loginError.value = e instanceof Error ? e.message : "登录失败";
  } finally {
    logging.value = false;
  }
}
function expired() {
  w.clear();
  password.value = "";
  loginError.value = "登录已过期，请重新登录；本机草稿仍保留。";
}
onMounted(() => {
  window.addEventListener("wao:unauthorized", expired);
  document.addEventListener("visibilitychange", visibility);
  if (Capacitor.isNativePlatform())
    void NativeApp.addListener("appStateChange", ({ isActive }) => {
      chat.active.value = isActive;
      if (isActive) void updates.check();
      scheduleChat();
    }).then((handle) => {
      if (disposed) void handle.remove();
      else nativeListener = handle;
    });
  void updates.initialize();
  void boot();
});
onUnmounted(() => {
  disposed = true;
  clearTimeout(poll);
  void nativeListener?.remove();
  document.removeEventListener("visibilitychange", visibility);
  window.removeEventListener("wao:unauthorized", expired);
});
</script>
<template>
  <div v-if="demo" class="demo-ribbon">
    演示预览 · 示例数据仅在本地内存，不连接真实账号
  </div>
  <div v-if="booting" class="boot">
    <div class="brand-mark">w<span>·</span></div>
    <p>正在连接你的日常…</p>
  </div>
  <div v-else-if="!w.session.value" class="login-shell">
    <div class="login-brand">
      <div class="brand-mark">w<span>·</span></div>
      <span>WAO <small>日常，有序发生</small></span>
    </div>
    <div class="login-art" aria-hidden="true">
      <div class="art-orbit" />
      <div class="art-note">
        <AppIcon name="check" :size="24" /><span
          >给重要的小事<br />留一点空间。</span
        ><i /><i />
      </div>
      <div class="art-dot" />
    </div>
    <p class="eyebrow">A LITTLE ORDER, EVERY DAY</p>
    <h1>把日子，<br />过成喜欢的样子。</h1>
    <p class="muted">待办、日历、小记。你的日常，都在这里。</p>
    <form class="login-form" @submit.prevent="login">
      <label
        >邮箱<input
          v-model="email"
          type="email"
          autocomplete="username"
          placeholder="你的 WAO 账号"
          required
          :disabled="logging" /></label
      ><label
        >密码<input
          v-model="password"
          type="password"
          autocomplete="current-password"
          placeholder="输入密码"
          required
          :disabled="logging"
      /></label>
      <p v-if="loginError || bootError" class="error" role="alert">
        {{ loginError || bootError }}
        <button
          v-if="bootError"
          type="button"
          class="text-button"
          @click="boot"
        >
          重试连接
        </button>
      </p>
      <button class="primary" :disabled="logging">
        {{ logging ? "正在登录…" : "开始我的一天" }}<AppIcon name="next" />
      </button>
    </form>
    <p class="login-footer">使用已有 WAO 账号登录 · 与 Web 数据同步</p>
  </div>
  <RouterView v-else />
  <UpdateDialog />
</template>
