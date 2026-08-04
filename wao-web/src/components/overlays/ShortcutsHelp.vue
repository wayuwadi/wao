<script setup lang="ts">
import { useUiStore } from '@/stores/ui'
import { SHORTCUT_GROUPS } from '@/composables/shortcuts'

const ui = useUiStore()

/** 把「⌘/Ctrl K」「G H」拆成一个个按键胶囊。 */
function keys(combo: string): string[] {
  return combo.split(' ')
}
</script>

<template>
  <div class="mask" @click="ui.closeHelp()"></div>
  <div class="modal">
    <div class="head">
      <div class="title"><i class="ri-keyboard-line"></i>键盘快捷键</div>
      <div class="spacer"></div>
      <button class="close" @click="ui.closeHelp()"><i class="ri-close-line"></i></button>
    </div>

    <div class="groups">
      <div v-for="g in SHORTCUT_GROUPS" :key="g.title" class="group">
        <div class="group-title">{{ g.title }}</div>
        <div v-for="(row, i) in g.items" :key="i" class="row">
          <span class="desc">{{ row[1] }}</span>
          <span class="keys">
            <kbd v-for="(k, j) in keys(row[0])" :key="j">{{ k }}</kbd>
          </span>
        </div>
      </div>
    </div>

    <div class="foot">按 <kbd>?</kbd> 或 <kbd>Esc</kbd> 关闭</div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 120;
  animation: waoFade 0.15s ease;
}
.modal {
  position: fixed;
  top: 10vh;
  /* 用 margin 居中而不是 transform：waoPop 动画也动 transform，
     两者叠在一起时居中位移会被覆盖，卡片先偏右出现再跳回中间。 */
  left: 0;
  right: 0;
  margin-inline: auto;
  width: min(600px, calc(100vw - 40px));
  max-height: 80vh;
  overflow-y: auto;
  background: var(--wao-bg-card);
  border-radius: 14px;
  z-index: 121;
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop 0.16s ease;
  padding: 20px 22px;
}
.spacer {
  flex: 1;
}
.head {
  display: flex;
  align-items: center;
}
.title {
  font-size: 16px;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 8px;
}
.title i {
  font-size: 18px;
  color: var(--wao-primary);
}
.close {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
}
.close:hover {
  background: var(--wao-bg-hover-2);
}
.groups {
  margin-top: 14px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 28px;
}
@media (max-width: 540px) {
  .groups {
    grid-template-columns: 1fr;
  }
}
.group-title {
  font-size: 12px;
  color: var(--wao-text-weak);
  letter-spacing: 0.5px;
  margin-bottom: 4px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--wao-divider);
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}
.desc {
  flex: 1;
  font-size: 13px;
  color: var(--wao-text-2);
}
.keys {
  display: inline-flex;
  gap: 4px;
  flex-shrink: 0;
}
kbd {
  font-family: inherit;
  font-size: 11px;
  min-width: 18px;
  text-align: center;
  padding: 2px 6px;
  border: 1px solid var(--wao-line-strong);
  border-bottom-width: 2px;
  border-radius: 6px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-2);
}
.foot {
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--wao-divider);
  font-size: 12px;
  color: var(--wao-text-weak);
}
.foot kbd {
  margin: 0 2px;
}
</style>
