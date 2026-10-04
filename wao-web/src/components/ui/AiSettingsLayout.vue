<script setup lang="ts">
defineProps<{ items: { id: string; label: string; icon?: string }[]; active: string }>()
const emit = defineEmits<{ 'update:active': [value: string] }>()
</script>
<template>
  <div class="ai-settings-layout">
    <div class="settings-body">
      <aside>
        <span class="nav-label">设置</span>
        <nav aria-label="设置项目">
          <button
            v-for="item in items"
            :key="item.id"
            type="button"
            :aria-pressed="active === item.id"
            @click="emit('update:active', item.id)"
          >
            <i v-if="item.icon" :class="item.icon" aria-hidden="true"></i>{{ item.label }}
          </button>
        </nav>
        <p class="sidebar-foot">仅用于当前功能<br />切换设置项会保留草稿</p>
      </aside>
      <div class="settings-main">
        <div class="settings-engine"><slot name="engine" /></div>
        <div class="settings-scroll"><slot /></div>
      </div>
    </div>
    <footer class="settings-footer"><slot name="footer" /></footer>
  </div>
</template>
<style scoped>
.ai-settings-layout {
  width: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  height: 100%;
  color: var(--wao-text);
}
.settings-body {
  min-width: 0;
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 178px minmax(0, 1fr);
}
aside {
  min-width: 0;
  padding: 23px 13px;
  background: var(--wao-bg-sub);
  border-right: 1px solid var(--wao-divider);
  display: flex;
  flex-direction: column;
  gap: 13px;
}
.nav-label {
  padding-left: 12px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
nav {
  display: grid;
  gap: 6px;
}
nav button {
  border: 0;
  background: transparent;
  color: var(--wao-text-weak);
  border-radius: 8px;
  padding: 11px 12px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  display: flex;
  align-items: center;
  gap: 9px;
}
nav button[aria-pressed='true'] {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-weight: 600;
}
.sidebar-foot {
  margin-top: auto;
  padding: 20px 10px 0;
  color: var(--wao-text-weak);
  font-size: 11px;
  line-height: 1.8;
}
.settings-main {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.settings-engine {
  flex-shrink: 0;
  padding: 23px 27px 18px;
  border-bottom: 1px solid var(--wao-divider);
}
.settings-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding: 21px 27px 27px;
}
.settings-footer {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 15px 26px;
  border-top: 1px solid var(--wao-divider);
  background: var(--wao-bg-card);
}
.settings-scroll :deep(h3) {
  margin: 0 0 8px;
  font-size: 16px;
}
.settings-scroll :deep(p) {
  color: var(--wao-text-weak);
  font-size: 12px;
  line-height: 1.7;
}
.settings-scroll :deep(fieldset) {
  margin: 0;
  padding: 0;
  border: 0;
  min-width: 0;
}
.settings-scroll :deep(label) {
  font-size: 13px;
}
.settings-scroll :deep(textarea) {
  display: block;
  box-sizing: border-box;
  width: 100%;
  min-height: 250px;
  padding: 15px 16px;
  resize: vertical;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
  font-size: 13px;
  line-height: 1.9;
}
.ai-settings-layout :deep(button:focus-visible),
.ai-settings-layout :deep(select:focus-visible),
.ai-settings-layout :deep(textarea:focus-visible) {
  outline: 2px solid var(--wao-primary);
  outline-offset: 2px;
}
.ai-settings-layout :deep(button:disabled) {
  opacity: 0.5;
  cursor: default;
}
.settings-footer :deep(button),
.settings-scroll :deep(button) {
  font: inherit;
  font-size: 12px;
  padding: 7px 12px;
  border: 1px solid var(--wao-divider);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  cursor: pointer;
}
.settings-footer :deep(.primary) {
  background: var(--wao-primary);
  color: white;
  border-color: var(--wao-primary);
}
.settings-footer :deep(.save-status) {
  margin: 0;
  max-width: 100%;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.settings-footer :deep(.footer-actions) {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-left: auto;
}
.settings-scroll :deep(.error),
.settings-footer :deep(.error) {
  color: var(--wao-danger-strong);
}
.settings-scroll :deep(.prompt-toolbar),
.settings-scroll :deep(.prompt-meta) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 12px 0;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.settings-scroll :deep(.prompt-meta) {
  font-size: 11px;
  flex-wrap: wrap;
}
.settings-scroll :deep(.settings-conflict) {
  padding: 12px;
  border: 1px solid var(--wao-divider);
  border-radius: 8px;
  margin-top: 14px;
  background: var(--wao-bg-sub);
  font-size: 12px;
}
.settings-scroll :deep(.settings-conflict pre) {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
}
.settings-scroll :deep(.settings-conflict button) {
  margin: 8px 8px 0 0;
}
@media (max-width: 650px) {
  .settings-body {
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
  aside {
    min-width: 0;
    padding: 10px 12px;
    border-right: 0;
    border-bottom: 1px solid var(--wao-divider);
    gap: 0;
    flex-shrink: 0;
  }
  nav {
    display: flex;
    flex-wrap: nowrap;
    gap: 4px;
    overflow-x: auto;
  }
  nav button {
    flex-shrink: 0;
    white-space: nowrap;
    padding: 7px 9px;
    font-size: 12px;
  }
  .nav-label,
  .sidebar-foot {
    display: none;
  }
  .settings-main {
    flex: 1;
  }
  .settings-engine {
    padding: 14px 15px;
  }
  .settings-scroll {
    padding: 17px 15px;
  }
  .settings-footer {
    padding: 12px 15px;
    gap: 8px;
  }
  .settings-scroll :deep(textarea) {
    font-size: 16px;
    min-height: 220px;
  }
}
</style>
