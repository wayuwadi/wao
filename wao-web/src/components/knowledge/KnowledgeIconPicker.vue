<script setup lang="ts">
import { computed, ref } from 'vue'
import { knowledgeIcons } from '@/utils/knowledgeIcons'
const model = defineModel<string>({ required: true })
const props = defineProps<{ disabled?: boolean; initiallyExpanded?: boolean }>()
const expanded = ref(props.initiallyExpanded ?? false)
const currentName = computed(() => knowledgeIcons.find(icon => icon.value === model.value)?.label ?? '原有图标')
function choose(value: string) { model.value = value; expanded.value = false }
</script>
<template>
  <div class="knowledge-icon-picker" @keydown.esc.stop="expanded = false">
    <span class="icon-field-label">知识库图标</span>
    <button type="button" class="icon-choice" :disabled="disabled" :aria-expanded="expanded" aria-label="选择知识库图标" @click="expanded = !expanded">
      <i :class="model || 'ri-book-2-line'" aria-hidden="true"></i><span>{{ currentName }}</span><i class="ri-arrow-down-s-line" aria-hidden="true"></i>
    </button>
    <div v-if="expanded" class="icon-options" role="group" aria-label="彩色知识库图标">
      <button v-for="icon in knowledgeIcons" :key="icon.value" type="button" :disabled="disabled" :aria-label="icon.label" :title="icon.label" :aria-pressed="model === icon.value" @click="choose(icon.value)"><i :class="icon.value" aria-hidden="true"></i></button>
    </div>
  </div>
</template>
<style scoped>
.knowledge-icon-picker { margin-bottom: 12px; }
.icon-field-label { display: block; margin-bottom: 8px; font-size: 13px; color: var(--wao-text-3); }
.icon-choice { display: inline-flex; align-items: center; gap: 9px; padding: 8px 10px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.icon-choice > i:first-child { font-size: 30px; }
.icon-options { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 4px; width: min(100%, 310px); margin-top: 8px; padding: 8px; box-sizing: border-box; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); }
.icon-options button { display: grid; place-items: center; min-width: 0; height: 44px; padding: 5px; border: 1px solid transparent; border-radius: 6px; background: transparent; cursor: pointer; }
.icon-options button:hover, .icon-options button[aria-pressed="true"] { background: var(--wao-bg-hover); border-color: var(--wao-line-control); }
.icon-options i { font-size: 32px; }
button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
button:disabled { opacity: .5; cursor: default; }
</style>
