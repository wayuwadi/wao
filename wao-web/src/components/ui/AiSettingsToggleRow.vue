<script setup lang="ts">
defineProps<{
  modelValue: boolean
  title: string
  description: string
  icon: string
  controlLabel: string
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>
<template>
  <label class="ai-auto-row">
    <span class="auto-icon"><i :class="icon" aria-hidden="true"></i></span>
    <span class="auto-description"
      ><strong>{{ title }}</strong
      ><small>{{ description }}</small></span
    >
    <span class="auto-toggle"
      ><input
        type="checkbox"
        :checked="modelValue"
        :disabled="disabled"
        :aria-label="controlLabel"
        @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)" /><span
        class="switch-track"
        aria-hidden="true"
      ></span
    ></span>
  </label>
</template>
<style scoped>
.ai-auto-row {
  display: flex;
  gap: 14px;
  align-items: center;
  padding: 14px 0;
  border-bottom: 1px solid var(--wao-divider);
  cursor: pointer;
}
.ai-auto-row:last-child {
  border-bottom: 0;
}
.auto-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: var(--wao-bg-sub);
  color: var(--wao-primary-text);
  flex-shrink: 0;
  font-size: 18px;
}
.auto-description {
  min-width: 0;
  display: grid;
  gap: 3px;
}
strong {
  font-size: 13px;
  font-weight: 600;
}
small {
  color: var(--wao-text-3);
  font-size: 12px;
  line-height: 1.6;
}
.auto-toggle {
  margin-left: auto;
  position: relative;
  width: 36px;
  height: 21px;
  flex-shrink: 0;
}
input {
  position: absolute;
  inset: 0;
  margin: 0;
  opacity: 0;
  width: 100%;
  height: 100%;
  cursor: pointer;
}
.switch-track {
  display: block;
  width: 36px;
  height: 21px;
  background: var(--wao-text-weak);
  border-radius: 30px;
  pointer-events: none;
}
.switch-track::after {
  content: '';
  display: block;
  position: absolute;
  left: 3px;
  top: 3px;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: var(--wao-bg-card);
  transition: transform 0.15s;
}
input:checked + .switch-track {
  background: var(--wao-primary);
}
input:checked + .switch-track::after {
  transform: translateX(15px);
  background: white;
}
input:focus-visible + .switch-track {
  outline: 2px solid var(--wao-primary);
  outline-offset: 3px;
}
input:disabled {
  cursor: default;
}
input:disabled + .switch-track {
  opacity: 0.5;
}
@media (prefers-reduced-motion: reduce) {
  .switch-track::after {
    transition: none;
  }
}
</style>
