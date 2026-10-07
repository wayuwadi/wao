<script setup lang="ts">
import { computed, useId } from 'vue'
const props = withDefaults(
  defineProps<{ label: string; hint?: string; error?: string; required?: boolean }>(),
  { hint: '', error: '', required: false },
)
const id = `wao-field-${useId()}`
const describedBy = computed(
  () =>
    [props.hint ? `${id}-hint` : '', props.error ? `${id}-error` : ''].filter(Boolean).join(' ') ||
    undefined,
)
</script>
<template>
  <div class="wao-field" :class="{ 'wao-field--error': error }">
    <label :for="id">{{ label }}<span v-if="required" aria-hidden="true"> *</span></label>
    <slot :id="id" :described-by="describedBy" :invalid="!!error" :required="required" />
    <p v-if="hint" :id="`${id}-hint`" class="field-hint">{{ hint }}</p>
    <p v-if="error" :id="`${id}-error`" class="field-error" role="alert">
      <i class="ri-error-warning-line" aria-hidden="true"></i>{{ error }}
    </p>
  </div>
</template>
<style scoped>
.wao-field {
  display: grid;
  gap: var(--wao-space-1);
  min-width: 0;
}
label {
  font-size: var(--wao-font-body);
  color: var(--wao-text);
}
p {
  margin: 0;
  font-size: var(--wao-font-caption);
  line-height: 1.6;
}
.field-hint {
  color: var(--wao-text-3);
}
.field-error {
  display: flex;
  gap: 6px;
  align-items: start;
  color: var(--wao-danger-strong);
}
.wao-field :deep(input),
.wao-field :deep(select),
.wao-field :deep(textarea) {
  width: 100%;
  min-width: 0;
  min-height: var(--wao-control-height);
  padding: 8px 12px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
  font-size: var(--wao-font-body);
  line-height: 1.6;
}
.wao-field :deep(textarea) {
  resize: vertical;
}
.wao-field--error :deep([aria-invalid='true']) {
  border-color: var(--wao-danger-strong);
}
@media (max-width: 650px) {
  .wao-field :deep(input),
  .wao-field :deep(select),
  .wao-field :deep(textarea) {
    font-size: 16px;
  }
}
</style>
