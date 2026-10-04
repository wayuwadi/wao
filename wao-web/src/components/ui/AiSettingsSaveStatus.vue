<script setup lang="ts">
defineProps<{ text: string; dirty: boolean; loading: boolean; saving: boolean; error: boolean }>()
</script>
<template>
  <div class="footer-status">
    <div
      class="save-status"
      :class="{ 'is-dirty': dirty || loading || saving, 'is-error': error }"
      role="status"
      aria-live="polite"
    >
      <i
        :class="
          error
            ? 'ri-error-warning-line'
            : saving || loading
              ? 'ri-loader-4-line'
              : dirty
                ? 'ri-edit-circle-line'
                : 'ri-checkbox-circle-line'
        "
        aria-hidden="true"
      ></i
      ><span>{{ text }}</span>
    </div>
    <span class="save-hint">保存后用于下一次生成</span>
  </div>
</template>
<style scoped>
.footer-status {
  min-width: 0;
}
.save-status {
  display: flex;
  gap: 7px;
  align-items: center;
  font-size: 12px;
  line-height: 1.6;
  color: var(--wao-success-strong);
}
.save-status.is-dirty {
  color: var(--wao-text-3);
}
.save-status.is-error {
  color: var(--wao-danger-strong);
}
i {
  font-size: 16px;
  flex-shrink: 0;
}
.save-hint {
  display: block;
  font-size: 11px;
  color: var(--wao-text-3);
  padding-left: 23px;
  line-height: 1.6;
}
</style>
