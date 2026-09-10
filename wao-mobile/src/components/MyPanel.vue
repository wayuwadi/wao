<script setup lang="ts">
import { ref } from "vue";
import { useWorkspace } from "../composables/workspace";
import { updates as u } from "../composables/updates";
import AppIcon from "./AppIcon.vue";
const w = useWorkspace(),
  loggingOut = ref(false),
  error = ref("");
async function logout() {
  loggingOut.value = true;
  error.value = "";
  try {
    await w.logout();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "退出失败，请重试";
  } finally {
    loggingOut.value = false;
  }
}
</script>
<template>
  <section class="my-panel" aria-label="我的">
    <p class="eyebrow">YOUR OWN LITTLE SPACE</p>
    <h1>日常，由你掌握。</h1>
    <p class="muted">账号、版本，和陪你一起变好的 WAO。</p>
    <div class="my-identity">
      <span class="my-avatar">{{
        w.session.value?.member.initial ||
        w.session.value?.member.name?.slice(0, 1)
      }}</span>
      <div>
        <h2>{{ w.session.value?.member.name }}</h2>
        <p>已连接 WAO · 跨端同步日常</p>
      </div>
    </div>
    <div class="my-card">
      <div class="my-card-title">
        <AppIcon name="download" />
        <h2>应用更新</h2>
        <span v-if="u.available.value" class="new-version">有新版本</span>
      </div>
      <div class="my-version">
        WAO <strong>{{ u.version.value || "正在读取…" }}</strong>
      </div>
      <p
        class="my-update-status"
        :class="{ error: !!u.error.value }"
        role="status"
      >
        {{ u.status.value }}
      </p>
      <p v-if="u.checkedAt.value" class="my-checked">
        上次检查
        {{
          new Date(u.checkedAt.value).toLocaleString("zh-CN", {
            month: "numeric",
            day: "numeric",
            hour: "2-digit",
            minute: "2-digit",
          })
        }}
      </p>
      <button
        class="secondary"
        :disabled="u.checking.value"
        @click="u.check(true)"
      >
        <AppIcon name="refresh" :class="{ spin: u.checking.value }" />{{
          u.checking.value ? "正在检查…" : "检查更新"
        }}<AppIcon name="right" :size="17" /></button
      ><button
        v-if="u.available.value"
        class="text-button"
        @click="u.modal.value = true"
      >
        查看 {{ u.release.value!.version }} 更新
      </button>
    </div>
    <div class="my-about">
      <div class="brand-mark">w<span>·</span></div>
      <div>
        <strong>一点秩序，一点自由。</strong>
        <p>待办 · 日历 · 小记 · 聊天</p>
      </div>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <button class="my-logout" :disabled="loggingOut" @click="logout">
      <AppIcon name="logout" :size="18" />{{
        loggingOut ? "正在退出…" : "退出登录"
      }}
    </button>
    <p class="my-draft-note">退出后，本机草稿仍为当前账号保留。</p>
  </section>
</template>
