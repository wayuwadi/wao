<script setup lang="ts">
import { computed, ref, watch } from 'vue'
const props = withDefaults(
  defineProps<{
    modelValue: string
    defaultValue?: string
    label: string
    editorId: string
    emptyUsesDefault?: boolean
  }>(),
  { defaultValue: '', emptyUsesDefault: false },
)
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const comparing = ref(false)
const preview = ref(false)
const usingDefault = computed(() =>
  props.emptyUsesDefault ? !props.modelValue.trim() : props.modelValue === props.defaultValue,
)
watch(
  () => props.editorId,
  () => {
    comparing.value = false
    preview.value = false
  },
)
function restore() {
  emit('update:modelValue', props.emptyUsesDefault ? '' : props.defaultValue)
  comparing.value = false
}
</script>
<template>
  <div class="ai-prompt-editor">
    <div class="prompt-toolbar">
      <label :for="editorId">{{ usingDefault ? '当前使用默认提示词' : '自定义提示词' }}</label>
      <button
        v-if="!usingDefault"
        type="button"
        :disabled="!defaultValue"
        @click="comparing = !comparing"
      >
        恢复默认
      </button>
    </div>
    <textarea
      :id="editorId"
      :aria-label="label"
      :value="usingDefault && emptyUsesDefault ? defaultValue : modelValue"
      :readonly="usingDefault && emptyUsesDefault"
      maxlength="4000"
      rows="11"
      :placeholder="usingDefault && !defaultValue ? '当前服务尚未提供默认内容，可重新读取设置' : ''"
      @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
    />
    <div class="prompt-meta">
      <span>{{
        usingDefault && emptyUsesDefault
          ? '默认内容只读，基于默认修改后可编辑'
          : '切换设置项会保留草稿'
      }}</span>
      <span
        >{{ (usingDefault && emptyUsesDefault ? defaultValue : modelValue).length }} / 4000 字</span
      >
    </div>
    <div class="prompt-actions">
      <button
        v-if="usingDefault && emptyUsesDefault"
        type="button"
        :disabled="!defaultValue"
        @click="emit('update:modelValue', defaultValue)"
      >
        基于默认修改
      </button>
      <button
        v-if="!usingDefault"
        type="button"
        :disabled="!defaultValue"
        :aria-expanded="preview"
        @click="preview = !preview"
      >
        {{ preview ? '收起默认内容' : '查看默认内容' }}
      </button>
    </div>
    <p v-if="!defaultValue" role="status">
      当前服务未提供默认内容，重新读取后再试；自定义草稿仍保留。
    </p>
    <div v-if="comparing" class="prompt-comparison" role="region" aria-label="恢复默认前的内容对比">
      <strong>恢复默认只修改本段草稿，保存后用于下一次生成。</strong>
      <div class="comparison-columns">
        <section>
          <h4>当前内容</h4>
          <pre>{{ modelValue }}</pre>
        </section>
        <section>
          <h4>默认内容</h4>
          <pre>{{ defaultValue }}</pre>
        </section>
      </div>
      <div class="prompt-actions">
        <button type="button" @click="comparing = false">保留当前内容</button
        ><button type="button" @click="restore">确认恢复默认</button>
      </div>
    </div>
    <div v-else-if="preview" class="prompt-comparison">
      <h4>默认内容</h4>
      <pre>{{ defaultValue }}</pre>
    </div>
    <p class="prompt-boundary">这里配置表达偏好；事实保真、权限与输出格式要求仍由系统保护。</p>
  </div>
</template>
<style scoped>
.prompt-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 12px;
}
.prompt-comparison {
  margin-top: 14px;
  padding: 14px;
  border: 1px solid var(--wao-divider);
  border-radius: 9px;
  background: var(--wao-bg-sub);
  font-size: 12px;
}
.comparison-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}
.prompt-comparison h4 {
  font-size: 12px;
  margin: 12px 0 6px;
}
.prompt-comparison pre {
  font: inherit;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 180px;
  overflow: auto;
  margin: 0;
}
.prompt-boundary {
  margin-top: 12px !important;
}
textarea[readonly] {
  background: var(--wao-bg-sub) !important;
}
@media (max-width: 650px) {
  .comparison-columns {
    grid-template-columns: 1fr;
  }
}
</style>
