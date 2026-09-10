<script setup lang="ts">
import { nextTick, onUnmounted, ref, watch } from "vue";
import { updates as u } from "../composables/updates";
import AppIcon from "./AppIcon.vue";
const dialog = ref<HTMLElement>();
let focus: HTMLElement | null = null;
function keydown(event: KeyboardEvent) {
  if (!u.modal.value) return;
  if (event.key === "Escape") {
    event.preventDefault();
    event.stopImmediatePropagation();
    if (!u.opening.value) u.later();
  }
  if (event.key === "Tab") {
    event.stopImmediatePropagation();
    const nodes = Array.from(
      dialog.value?.querySelectorAll<HTMLElement>(
        "button:not([disabled]),a[href]",
      ) || [],
    );
    const first = nodes[0],
      last = nodes.at(-1);
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  }
}
watch(
  u.modal,
  async (open) => {
    if (open) {
      focus = document.activeElement as HTMLElement;
      document.addEventListener("keydown", keydown, true);
    } else document.removeEventListener("keydown", keydown, true);
    await nextTick();
    if (open) dialog.value?.querySelector<HTMLElement>("button")?.focus();
    else focus?.focus();
  },
  { immediate: true },
);
onUnmounted(() => document.removeEventListener("keydown", keydown, true));
</script>
<template>
  <div v-if="u.modal.value" class="update-backdrop">
    <section
      ref="dialog"
      class="update-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="update-title"
      aria-describedby="update-description"
    >
      <div class="update-art">
        <AppIcon name="download" :size="32" /><span>WAO UPDATE</span>
      </div>
      <p class="eyebrow">A LITTLE BETTER, EVERY DAY</p>
      <h2 id="update-title">新版本，准备好了。</h2>
      <p id="update-description" class="muted">
        升级到 WAO {{ u.release.value?.version }}，继续体验最新功能与改进。
      </p>
      <div class="update-version">
        <span>当前 {{ u.version.value }}</span
        ><AppIcon name="next" :size="18" /><strong>{{
          u.release.value?.version
        }}</strong>
      </div>
      <p v-if="u.release.value" class="update-date">
        发布于
        {{ new Date(u.release.value.publishedAt).toLocaleDateString("zh-CN") }}
      </p>
      <p class="update-install">
        {{
          u.platform.value === "ios"
            ? "下载后需用你的个人签名工具重新签名安装。保持原应用标识，以保留本机数据。"
            : "下载完成后打开 APK，按系统提示覆盖安装。保持原应用与签名，以保留本机草稿。"
        }}
      </p>
      <p v-if="u.error.value" class="error" role="alert">{{ u.error.value }}</p>
      <button class="primary" :disabled="u.opening.value" @click="u.download">
        {{
          u.opening.value
            ? "正在打开…"
            : u.platform.value === "ios"
              ? "下载新版 iOS 包"
              : "下载新版 APK"
        }}<AppIcon name="arrow" />
      </button>
      <button
        class="text-button update-later"
        :disabled="u.opening.value"
        @click="u.later"
      >
        稍后提醒
      </button>
    </section>
  </div>
</template>
