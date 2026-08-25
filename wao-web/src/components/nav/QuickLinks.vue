<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useQuickLinks } from '@/composables/quickLinks'
import type { QuickLink } from '@/config/quickLinks'

const router = useRouter()
const { links, load, loading, loadError } = useQuickLinks()

const open = ref(false)
const panelRef = ref<HTMLElement | null>(null)
const triggerRef = ref<HTMLButtonElement | null>(null)

onMounted(() => load())

function toggle() {
  open.value = !open.value
}

function visit(link: QuickLink) {
  window.open(link.url, '_blank', 'noopener')
  open.value = false
}

function goManage() {
  open.value = false
  router.push('/settings?tab=quick-links')
}

function retryLoad() {
  void load(true)
}

/** 点击面板外部或触发按钮外部时关闭。 */
function onDocClick(e: MouseEvent) {
  const target = e.target as Node
  if (panelRef.value?.contains(target) || triggerRef.value?.contains(target)) return
  open.value = false
}

onMounted(() => document.addEventListener('mousedown', onDocClick))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocClick))
</script>

<template>
  <div class="quick-links-wrap">
    <button
      ref="triggerRef"
      class="ql-trigger"
      title="快捷链接（Jenkins、桌面宠物、GitHub…）"
      @click="toggle"
    >
      <i class="ri-links-line"></i>
    </button>

    <Transition name="ql-pop">
      <div v-if="open" ref="panelRef" class="ql-panel">
        <div class="ql-head">
          <i class="ri-links-line"></i>
          <span>快捷链接</span>
          <button class="ql-manage" title="管理快捷链接" @click="goManage">
            <i class="ri-settings-3-line"></i>
          </button>
        </div>

        <div v-if="loading" class="ql-state"><i class="ri-loader-4-line spin"></i>正在读取快捷链接…</div>
        <div v-else-if="loadError" class="ql-state error">
          <span>读取失败，当前显示默认链接</span>
          <button @click="retryLoad">重试</button>
        </div>

        <button v-for="link in links" :key="link.id" class="ql-item" @click="visit(link)">
          <i :class="link.icon"></i>
          <span class="ql-text">
            <span class="ql-label">{{ link.label }}</span>
            <span v-if="link.subtitle" class="ql-sub">{{ link.subtitle }}</span>
          </span>
          <i class="ri-external-link-line ql-ext"></i>
        </button>

        <div v-if="links.length === 0" class="ql-empty">
          暂无快捷链接，<a class="ql-empty-link" @click="goManage">前往添加</a>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.quick-links-wrap {
  position: relative;
}
.ql-trigger {
  width: 32px;
  height: 32px;
  border: none;
  background: none;
  border-radius: 8px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
}
.ql-trigger:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-text-2);
}
.ql-trigger i {
  font-size: 16px;
}

.ql-panel {
  position: absolute;
  left: calc(100% + 8px);
  bottom: 0;
  z-index: 200;
  width: 260px;
  max-height: min(70vh, 520px);
  overflow-y: auto;
  padding: 8px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  box-shadow: 0 8px 30px rgba(20, 25, 45, 0.12);
}

.ql-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--wao-text-2);
}
.ql-head i {
  font-size: 14px;
}
.ql-head span {
  flex: 1;
}
.ql-manage {
  border: none;
  background: none;
  cursor: pointer;
  color: var(--wao-text-faint);
  padding: 2px;
  border-radius: 5px;
  display: flex;
  align-items: center;
}
.ql-manage:hover {
  color: var(--wao-text-2);
  background: var(--wao-bg-hover);
}
.ql-manage i {
  font-size: 13px;
}

.ql-empty {
  padding: 16px 8px;
  text-align: center;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.ql-state {
  margin: 0 5px 5px;
  padding: 7px 8px;
  border-radius: 7px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  font-size: 10.5px;
  display: flex;
  align-items: center;
  gap: 5px;
}
.ql-state.error { color: var(--wao-danger-strong); }
.ql-state span { flex: 1; }
.ql-state button { border: 0; background: none; color: inherit; cursor: pointer; }
.spin { animation: ql-spin .9s linear infinite; }
@keyframes ql-spin { to { transform: rotate(360deg); } }
.ql-empty-link {
  color: var(--wao-primary);
  cursor: pointer;
  text-decoration: underline;
}

.ql-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text);
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}
.ql-item:hover {
  background: var(--wao-bg-hover);
}
.ql-item > i:first-child {
  width: 18px;
  text-align: center;
  font-size: 15px;
  color: var(--wao-text-3);
  flex-shrink: 0;
}
.ql-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}
.ql-label {
  font-weight: 500;
}
.ql-sub {
  font-size: 10.5px;
  color: var(--wao-text-weak);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ql-ext {
  font-size: 12px;
  color: var(--wao-text-faint);
  flex-shrink: 0;
}
.ql-item:hover .ql-ext {
  color: var(--wao-text-3);
}

/* 过渡动画 */
.ql-pop-enter-active,
.ql-pop-leave-active {
  transition: opacity 0.12s ease, transform 0.12s ease;
}
.ql-pop-enter-from,
.ql-pop-leave-to {
  opacity: 0;
  transform: translateX(-4px) scale(0.97);
}

/* 侧栏折叠时隐藏面板 */
@media (max-width: 760px) {
  .quick-links-wrap {
    display: none;
  }
}
</style>
