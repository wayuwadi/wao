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
        <div class="sidebar-foot">
          <i class="ri-shield-check-line" aria-hidden="true"></i
          ><span>仅用于当前功能<br />不会修改另一处设置</span>
        </div>
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
  font: 14px/1.6 var(--wao-font, 'Segoe UI', 'Microsoft YaHei', sans-serif);
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
  padding: 24px 13px;
  background: var(--wao-bg-sub);
  border-right: 1px solid var(--wao-divider);
  display: flex;
  flex-direction: column;
  gap: 13px;
}
.nav-label {
  padding-left: 13px;
  font-size: 12px;
  color: var(--wao-text-3);
}
nav {
  display: grid;
  gap: 6px;
}
nav button {
  border: 0;
  background: transparent;
  color: var(--wao-text-3);
  border-radius: 8px;
  padding: 11px 12px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  display: flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
}
nav button i {
  font-size: 16px;
}
nav button[aria-pressed='true'] {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-weight: 600;
}
.sidebar-foot {
  margin-top: auto;
  padding: 22px 10px 0;
  color: var(--wao-text-3);
  font-size: 11px;
  line-height: 1.8;
  display: flex;
  gap: 7px;
}
.sidebar-foot i {
  font-size: 16px;
  margin-top: 4px;
  flex-shrink: 0;
}
.settings-main {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.settings-engine {
  flex-shrink: 0;
  padding: 23px 27px 20px;
  position: relative;
}
.settings-engine::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 27px;
  right: 27px;
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
  gap: 16px;
  padding: 15px 26px;
  border-top: 1px solid var(--wao-divider);
  background: var(--wao-bg-card);
}
.settings-scroll :deep(h3) {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.settings-scroll :deep(p) {
  margin: 4px 0 0;
  color: var(--wao-text-3);
  font-size: 12px;
  line-height: 1.6;
}
.settings-scroll :deep(.section-heading) {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.settings-scroll :deep(.default-tag) {
  font-size: 11px;
  white-space: nowrap;
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
  border-radius: 5px;
  padding: 3px 8px;
}
.settings-scroll :deep(.behavior-note) {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px 14px;
  margin-top: 15px;
  background: var(--wao-bg-sub);
  border-radius: 8px;
  font-size: 12px;
  color: var(--wao-text-3);
}
.settings-scroll :deep(.behavior-note i) {
  flex-shrink: 0;
  font-size: 16px;
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
  height: 250px;
  min-height: 180px;
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
  font-size: 13px;
  padding: 8px 17px;
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
.settings-footer :deep(.footer-actions) {
  display: flex;
  gap: 9px;
  flex-wrap: wrap;
  margin-left: auto;
}
.settings-footer :deep(.footer-actions > button:not(.light-action)) {
  min-height: 40px;
}
.settings-footer :deep(.light-action),
.settings-scroll :deep(.prompt-toolbar button) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: var(--wao-primary-text);
  font-size: 12px;
}
.settings-footer :deep(.light-action) {
  margin-right: 6px;
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
  margin: 0 0 9px;
  font-size: 12px;
  color: var(--wao-text-3);
}
.settings-scroll :deep(.prompt-meta) {
  font-size: 11px;
  flex-wrap: wrap;
  margin: 8px 0 0;
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
    padding: 12px 14px;
    border-right: 0;
    border-bottom: 1px solid var(--wao-divider);
    gap: 0;
    flex-shrink: 0;
  }
  nav {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
  }
  nav button {
    flex-shrink: 0;
    white-space: nowrap;
    padding: 9px 10px;
    font-size: 12px;
    gap: 6px;
  }
  .nav-label,
  .sidebar-foot {
    display: none;
  }
  .settings-main {
    flex: 1;
  }
  .settings-engine {
    padding: 20px 16px;
  }
  .settings-engine::after {
    left: 16px;
    right: 16px;
  }
  .settings-scroll {
    padding: 21px 16px 20px;
  }
  .settings-footer {
    padding: 14px 16px;
    gap: 10px;
    flex-wrap: wrap;
  }
  .settings-footer :deep(.footer-status) {
    flex-basis: 100%;
  }
  .settings-footer :deep(.footer-actions) {
    justify-content: flex-end;
  }
  .settings-scroll :deep(textarea) {
    font-size: 16px;
    min-height: 180px;
  }
}
</style>
