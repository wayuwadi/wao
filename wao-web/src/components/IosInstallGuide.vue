<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { IOS_INSTALL_DISMISSED_KEY, shouldOfferIosInstall } from '@/utils/iosInstall'

const visible = ref(false)
const expanded = ref(false)

onMounted(() => {
  visible.value = shouldOfferIosInstall() && localStorage.getItem(IOS_INSTALL_DISMISSED_KEY) !== 'dismissed'
})

function dismiss() {
  localStorage.setItem(IOS_INSTALL_DISMISSED_KEY, 'dismissed')
  visible.value = false
}
</script>

<template>
  <section v-if="visible" class="install-guide" aria-label="把 WAO 安装到 iPhone">
    <span class="install-icon" aria-hidden="true">W</span>
    <div class="install-copy">
      <strong>把 WAO 放到主屏幕</strong>
      <span>项目、AI 运行和自动化可像 App 一样独立打开。</span>
      <ol v-if="expanded" class="install-steps">
        <li>点浏览器工具栏的“分享” <i class="ri-share-box-line"></i></li>
        <li>选择“添加到主屏幕”</li>
        <li>确认名称为“WAO”，以后从主屏幕进入</li>
      </ol>
    </div>
    <button
      v-if="!expanded"
      class="install-details-button"
      type="button"
      @click="expanded = true"
    >安装方法</button>
    <button v-else class="install-done-button" type="button" @click="dismiss">知道了</button>
    <button class="install-close-button" type="button" aria-label="不再提示安装" @click="dismiss">
      <i class="ri-close-line"></i>
    </button>
  </section>
</template>

<style scoped>
.install-guide {
  margin: 10px 12px 0;
  padding: 11px 38px 11px 11px;
  border: 1px solid color-mix(in srgb, var(--wao-primary) 22%, var(--wao-line));
  border-radius: 13px;
  background: color-mix(in srgb, var(--wao-primary-soft) 52%, var(--wao-bg-card));
  color: var(--wao-text-2);
  display: flex;
  align-items: center;
  gap: 10px;
  position: relative;
  flex-shrink: 0;
}
.install-icon {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  background: linear-gradient(145deg, #202b69, #4358d6);
  color: #fff;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  font-size: 21px;
  font-weight: 750;
}
.install-copy { min-width: 0; flex: 1; display: grid; gap: 2px; }
.install-copy strong { color: var(--wao-text-1); font-size: 13px; }
.install-copy > span { color: var(--wao-text-weak); font-size: 11px; line-height: 1.45; }
.install-steps { margin: 8px 0 1px; padding-left: 20px; color: var(--wao-text-2); font-size: 11px; line-height: 1.8; }
.install-details-button,
.install-done-button {
  min-height: 36px;
  padding: 0 11px;
  border: 1px solid var(--wao-primary-line);
  border-radius: 9px;
  background: var(--wao-bg-card);
  color: var(--wao-primary-text);
  cursor: pointer;
  flex: 0 0 auto;
  font-size: 12px;
  font-weight: 650;
}
.install-close-button {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
}
.install-close-button i { font-size: 18px; }

@media (max-width: 430px) {
  .install-guide { align-items: flex-start; flex-wrap: wrap; }
  .install-copy { flex-basis: calc(100% - 48px); }
  .install-details-button,
  .install-done-button { width: 100%; margin-left: 48px; }
}
</style>
