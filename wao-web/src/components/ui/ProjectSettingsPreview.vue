<script setup lang="ts">
defineProps<{ name: string; icon: string; visible: boolean }>()
</script>
<template>
  <aside class="settings-preview" aria-hidden="true">
    <div class="preview-caption">清单预览</div>
    <div class="preview-window">
      <div class="preview-title"><i :class="icon"></i><span>{{ name || '未命名清单' }}</span></div>
      <div class="preview-input"></div>
      <div v-for="n in 5" :key="n" class="preview-row"><i class="ri-checkbox-blank-circle-line"></i><span :class="{ short: n % 2 === 0 }"></span></div>
      <div class="preview-rule"></div>
      <div class="preview-note"><i :class="visible ? 'ri-eye-line' : 'ri-eye-off-line'"></i>{{ visible ? '显示在智能清单' : '不显示在智能清单' }}</div>
    </div>
  </aside>
</template>
<style scoped>
.settings-preview { flex: 0 0 34%; min-width: 0; padding: var(--wao-space-3); background: var(--wao-bg-sub); }
.preview-caption { margin-bottom: var(--wao-space-2); color: var(--wao-text-3); font-size: var(--wao-font-help); }
.preview-window { padding: var(--wao-space-2); min-height: 260px; background: var(--wao-bg-card); border: 1px solid var(--wao-line); border-radius: var(--wao-radius-card); box-shadow: var(--wao-shadow-pop); }
.preview-title { display: flex; gap: var(--wao-space-1); align-items: center; color: var(--wao-text); font-weight: 600; }
.preview-title i { color: var(--wao-primary-text); }
.preview-title span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.preview-input, .preview-row span, .preview-rule { height: 8px; background: var(--wao-bg-sub); border-radius: var(--wao-radius-control); }
.preview-input { height: 24px; margin: var(--wao-space-2) 0; }
.preview-row { display: flex; align-items: center; gap: var(--wao-space-1); margin: var(--wao-space-1) 0; color: var(--wao-primary-text); }
.preview-row span { flex: 1; }
.preview-row span.short { flex: 0 0 55%; }
.preview-rule { margin-top: var(--wao-space-3); }
.preview-note { display: flex; gap: var(--wao-space-1); align-items: center; margin-top: var(--wao-space-2); color: var(--wao-text-3); font-size: var(--wao-font-help); }
@media (max-width: 650px) { .settings-preview { display: none; } }
</style>
