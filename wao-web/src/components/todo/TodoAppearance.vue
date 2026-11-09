<script setup lang="ts">
import { ref } from 'vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { TODO_PALETTES, type TodoPalette } from '@/utils/todoAppearance'
import '@/styles/todo-palettes.css'

defineProps<{ ready?: boolean; error?: string }>()
const palette = defineModel<TodoPalette>({ required: true })
defineEmits<{ retry: [] }>()
const open = ref(false)
</script>

<template>
  <WaoButton variant="ghost" aria-label="待办背景颜色" title="待办背景颜色" @click="open = true"><i class="ri-palette-line" aria-hidden="true" /></WaoButton>
  <SettingsDialogShell v-if="open" title="待办外观" icon="ri-palette-line" width="640px" height="auto" @close="open = false">
    <div class="appearance-content">
      <h3>颜色系列</h3>
      <div class="palette-grid" role="group" aria-label="待办颜色系列">
        <button v-for="item in TODO_PALETTES" :key="item.id" type="button" :aria-pressed="palette === item.id" :disabled="ready === false" @click="palette = item.id">
          <span class="palette-swatch" :data-todo-palette="item.id"><i v-if="palette === item.id" class="ri-check-line" aria-hidden="true" /></span>
          <span>{{ item.name }}</span>
        </button>
      </div>
      <div class="palette-preview" :data-todo-palette="palette" aria-label="待办背景预览"><div>清单导航</div><div>任务列表</div><div>任务详情</div></div>
      <p v-if="error" role="status">{{ error }} <WaoButton variant="ghost" @click="$emit('retry')">重试</WaoButton></p>
      <p v-else-if="ready === false" role="status">正在读取外观设置…</p>
    </div>
  </SettingsDialogShell>
</template>

<style scoped>
.appearance-content { padding:var(--wao-space-3); color:var(--wao-text); font:400 var(--wao-font-body)/1.5 var(--wao-font); }
h3 { margin:0 0 var(--wao-space-2); font-size:var(--wao-font-section); font-weight:var(--wao-weight-heading); }
.palette-grid { display:grid; grid-template-columns:repeat(8,minmax(0,1fr)); gap:var(--wao-space-2) var(--wao-space-1); }
.palette-grid button { display:flex; flex-direction:column; align-items:center; gap:var(--wao-space-1); min-width:0; padding:0; border:0; background:transparent; color:var(--wao-text-3); font:inherit; font-size:var(--wao-font-help); cursor:pointer; }
.palette-grid button:disabled { opacity:.5; cursor:wait; }
.palette-grid button:focus-visible { outline:2px solid var(--wao-primary); outline-offset:4px; }
.palette-swatch { display:grid; place-items:center; width:100%; max-width:56px; aspect-ratio:1; border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-card); background:linear-gradient(160deg,var(--wao-todo-gradient-start),var(--wao-todo-gradient-end)); color:var(--wao-on-primary); }
.palette-swatch i { background:var(--wao-primary); border-radius:var(--wao-radius-pill); padding:2px; }
.palette-preview { display:grid; grid-template-columns:1fr 1.5fr 1.5fr; margin-top:var(--wao-space-3); overflow:hidden; border:1px solid var(--wao-line); border-radius:var(--wao-radius-control); color:var(--wao-text); }
.palette-preview div { min-height:100px; display:grid; place-items:center; padding:var(--wao-space-1); background:var(--wao-todo-list-bg); }
.palette-preview div:first-child { background:var(--wao-todo-nav-bg); }
.palette-preview div:last-child { background:var(--wao-todo-detail-bg); }
@media(max-width:480px) { .appearance-content { padding:var(--wao-space-2); } .palette-grid { grid-template-columns:repeat(4,minmax(0,1fr)); } }
</style>
