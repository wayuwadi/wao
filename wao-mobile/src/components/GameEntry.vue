<script setup lang="ts">
import { ref } from "vue";
import { Capacitor } from "@capacitor/core";
import {
  InAppBrowser,
  DefaultWebViewOptions,
  iOSViewStyle,
} from "@capacitor/inappbrowser";
import AppIcon from "./AppIcon.vue";

const opening = ref(false);
const error = ref("");
const gameUrl = "https://game.wayuwa.cn/";

async function enterGame() {
  if (opening.value) return;
  opening.value = true;
  error.value = "";
  try {
    if (Capacitor.isNativePlatform()) {
      // Remote game owns its session; never attach WAO credentials or native bridge.
      await InAppBrowser.openInWebView({
        url: gameUrl,
        options: {
          ...DefaultWebViewOptions,
          showToolbar: true,
          showURL: false,
          showNavigationButtons: false,
          closeButtonText: "返回 WAO",
          clearCache: false,
          clearSessionCache: false,
          android: {
            ...DefaultWebViewOptions.android,
            isIsolated: true,
            hardwareBack: true,
            pauseMedia: true,
          },
          iOS: {
            ...DefaultWebViewOptions.iOS,
            viewStyle: iOSViewStyle.FULL_SCREEN,
            allowOverScroll: false,
          },
        },
      });
    } else {
      // Browser preview only; installed apps always use the native in-app window.
      const child = window.open(gameUrl, "_blank");
      if (!child)
        throw new Error("浏览器阻止了游戏窗口，请允许弹出窗口后重试。");
      child.opener = null;
    }
  } catch {
    error.value = "游戏暂时未能打开，请检查网络后重试。";
  } finally {
    opening.value = false;
  }
}
</script>

<template>
  <section class="game-entry" aria-label="松风江湖">
    <div class="game-copy">
      <AppIcon name="game" :size="28" />
      <div>
        <strong>松风江湖</strong>
        <p>去陪张三丰走一段江湖</p>
      </div>
    </div>
    <button class="secondary" :disabled="opening" @click="enterGame">
      {{ opening ? "正在打开…" : error ? "重试进入" : "进入游戏"
      }}<AppIcon name="right" />
    </button>
    <p class="game-hint">使用游戏账号继续旅程 · 横屏体验更好</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.game-entry {
  margin: 0 0 20px;
  padding: 18px;
  border: 1px solid #d6ded4;
  border-radius: 18px;
  background: #f1f5ed;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.game-copy {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 180px;
  color: #345342;
}
.game-copy p,
.game-hint {
  margin: 5px 0 0;
  font-size: 12px;
  color: #657366;
}
.game-hint,
.error {
  flex-basis: 100%;
}
</style>
