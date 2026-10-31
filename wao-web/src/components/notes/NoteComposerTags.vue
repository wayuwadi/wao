<script setup lang="ts">
defineProps<{ choices: string[]; selected: string[]; saving: boolean; creating: boolean; createOpen: boolean; error: string; addButton?: boolean }>()
const name = defineModel<string>({ required: true })
const emit = defineEmits<{ toggle: [tag: string]; create: []; 'toggle-create': []; 'close-create': []; menu: [event: MouseEvent | KeyboardEvent, tag: string] }>()
</script>

<template>
  <div class="fullscreen-quick-tags composer-tags" role="group" aria-label="快捷标签">
    <i class="ri-price-tag-3-line" aria-hidden="true"></i>
    <button v-for="tag in choices" :key="tag" type="button" :class="{ selected: selected.includes(tag), 'tag-chip': selected.includes(tag) }" :aria-pressed="selected.includes(tag)" :aria-label="`${selected.includes(tag) ? '移除' : '添加'}标签 ${tag}`" :disabled="saving || creating || (!selected.includes(tag) && selected.length >= 20)" @contextmenu="emit('menu', $event, tag)" @keydown="emit('menu', $event, tag)" @mousedown.prevent @click="emit('toggle', tag)"><span class="quick-tag-hash" aria-hidden="true">#</span>{{ tag }}<i v-if="selected.includes(tag)" class="ri-check-line" aria-hidden="true"></i></button>
    <button v-if="addButton" class="add-tag" type="button" :disabled="saving || creating" :aria-label="createOpen ? '收起新增标签' : '新增标签'" :title="createOpen ? '收起新增标签' : '新增标签'" @click="emit('toggle-create')"><i :class="createOpen ? 'ri-close-line' : 'ri-add-line'"></i></button>
    <span v-if="createOpen" class="inline-tag-create"><input v-model="name" aria-label="新标签名称" placeholder="新标签名称" maxlength="32" :disabled="saving || creating" @keydown.enter.prevent="emit('create')" @keydown.esc="emit('close-create')"><button type="button" :disabled="saving || creating || !name.trim()" @click="emit('create')">{{ creating ? '…' : '↵' }}</button><span v-if="error" class="error" role="alert">{{ error }}</span></span>
  </div>
</template>

<style scoped>
.composer-tags { display:flex; align-items:center; flex-wrap:wrap; gap:7px; min-width:0; }
.composer-tags > i { color:var(--wao-text-weak); font-size:14px; }
.composer-tags button { display:inline-flex; align-items:center; gap:5px; border:0; border-radius:20px; padding:5px 8px; background:var(--wao-bg-sub-2); color:var(--wao-text-3); font-size:var(--wao-font-caption); max-width:100%; overflow-wrap:anywhere; cursor:pointer; }
.composer-tags button:hover, .composer-tags button.selected { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
.composer-tags button:disabled { opacity:.5; cursor:default; }
.composer-tags button:focus-visible, .composer-tags input:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
.quick-tag-hash { opacity:.6; font-size:var(--wao-font-caption); }
.inline-tag-create { display:flex; flex-wrap:wrap; gap:6px; min-width:0; }
.inline-tag-create input { min-width:0; width:140px; padding:6px; border:1px solid var(--wao-line-control); border-radius:6px; background:var(--wao-bg-card); color:var(--wao-text-1); }
.add-tag { flex:none; padding:4px 9px; border-radius:7px; }
.error { color:var(--wao-danger-strong); font-size:var(--wao-font-help); }
@media(max-width:760px) { .composer-tags { gap:6px; flex-wrap:nowrap; overflow-x:auto; flex-shrink:0; } .composer-tags button { min-height:44px; flex-shrink:0; } .inline-tag-create { flex:none; } }
@media(pointer:coarse) { .composer-tags button { min-height:44px; } }
</style>
