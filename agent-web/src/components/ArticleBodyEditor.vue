<script setup lang="ts">
import { nextTick, reactive, ref, watch } from 'vue'

const props = defineProps<{ modelValue: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [] }>()
type InsertKind = 'code' | 'diagram' | ''

const textarea = ref<HTMLTextAreaElement | null>(null)
const draft = ref(props.modelValue)
const insertKind = ref<InsertKind>('')
const insertAt = reactive({ start: 0, end: 0 })
const codeForm = reactive({ language: 'javascript', source: '' })
const diagramForm = reactive({ type: 'flowchart', source: '' })
const languages = [
  ['text', '纯文本'], ['javascript', 'JavaScript'], ['typescript', 'TypeScript'],
  ['java', 'Java'], ['python', 'Python'], ['sql', 'SQL'], ['bash', 'Shell'],
  ['json', 'JSON'], ['html', 'HTML'], ['css', 'CSS'], ['vue', 'Vue'], ['yaml', 'YAML'],
] as const
const diagramTypes = [
  ['flowchart', '流程图'], ['sequenceDiagram', '时序图'], ['mindmap', '思维导图'],
] as const
const diagramTemplates: Record<string, string> = {
  flowchart: 'flowchart LR\n  A[开始] --> B[处理] --> C[完成]',
  sequenceDiagram: 'sequenceDiagram\n  用户->>WAO: 发起请求\n  WAO-->>用户: 返回结果',
  mindmap: 'mindmap\n  root((中心主题))\n    分支一\n      子主题\n    分支二',
}

watch(() => props.modelValue, (value) => {
  if (value !== draft.value) draft.value = value
})

function update(value: string) {
  draft.value = value
  emit('update:modelValue', value)
  emit('change')
}

function openInsert(kind: Exclude<InsertKind, ''>) {
  if (props.disabled) return
  const input = textarea.value
  insertAt.start = input?.selectionStart ?? draft.value.length
  insertAt.end = input?.selectionEnd ?? insertAt.start
  codeForm.source = kind === 'code' && input ? draft.value.slice(insertAt.start, insertAt.end) : ''
  if (kind === 'diagram') {
    diagramForm.type = 'flowchart'
    diagramForm.source = diagramTemplates.flowchart!
  }
  insertKind.value = kind
}

function changeDiagramType() {
  diagramForm.source = diagramTemplates[diagramForm.type] ?? diagramTemplates.flowchart!
}

function insertBlock() {
  if (!insertKind.value || props.disabled) return
  const language = insertKind.value === 'diagram' ? 'mermaid' : codeForm.language
  const source = insertKind.value === 'diagram' ? diagramForm.source.trim() : codeForm.source.trimEnd()
  if (!source) return
  const before = draft.value.slice(0, insertAt.start).replace(/\s*$/, '')
  const after = draft.value.slice(insertAt.end).replace(/^\s*/, '')
  const block = `\`\`\`${language}\n${source}\n\`\`\``
  const value = [before, block, after].filter(Boolean).join('\n\n')
  const caret = before.length + (before ? 2 : 0) + block.length
  update(value)
  insertKind.value = ''
  void nextTick(() => {
    textarea.value?.focus()
    textarea.value?.setSelectionRange(caret, caret)
  })
}
</script>

<template>
  <div class="article-body-editor" :class="{ disabled }">
    <div class="embed-toolbar" role="toolbar" aria-label="正文嵌入工具">
      <button type="button" :disabled="disabled" @click="openInsert('diagram')"><i class="ri-flow-chart"></i> 画图</button>
      <button type="button" :disabled="disabled" @click="openInsert('code')"><i class="ri-code-box-line"></i> 代码块</button>
      <span>嵌入内容会随文章保存，并同步显示在预览和导出中</span>
    </div>
    <textarea ref="textarea" :value="draft" rows="18" :disabled="disabled" aria-label="文章正文" @input="update(($event.target as HTMLTextAreaElement).value)"></textarea>

    <div v-if="insertKind" class="embed-mask" @click.self="insertKind = ''">
      <form class="embed-dialog" role="dialog" aria-modal="true" :aria-label="insertKind === 'code' ? '插入代码块' : '插入画图'" @submit.prevent="insertBlock">
        <header>
          <div><strong>{{ insertKind === 'code' ? '插入代码块' : '插入画图' }}</strong><small>{{ insertKind === 'code' ? '语言会写入文章并显示在代码块顶部' : '使用可移植的 Mermaid 文本保存图形' }}</small></div>
          <button type="button" aria-label="关闭" @click="insertKind = ''"><i class="ri-close-line"></i></button>
        </header>
        <label v-if="insertKind === 'code'">语言<select v-model="codeForm.language"><option v-for="item in languages" :key="item[0]" :value="item[0]">{{ item[1] }}</option></select></label>
        <label v-else>图类型<select v-model="diagramForm.type" @change="changeDiagramType"><option v-for="item in diagramTypes" :key="item[0]" :value="item[0]">{{ item[1] }}</option></select></label>
        <label>{{ insertKind === 'code' ? '代码' : '图形内容' }}
          <textarea v-if="insertKind === 'code'" v-model="codeForm.source" rows="10" spellcheck="false" placeholder="粘贴或输入代码"></textarea>
          <textarea v-else v-model="diagramForm.source" rows="10" spellcheck="false"></textarea>
        </label>
        <p v-if="insertKind === 'diagram'">支持流程连接、参与者消息和缩进层级；右侧实时预览会立即绘制结果。</p>
        <footer><button type="button" class="secondary" @click="insertKind = ''">取消</button><button type="submit">插入正文</button></footer>
      </form>
    </div>
  </div>
</template>

<style scoped>
.article-body-editor{display:grid;gap:0}.embed-toolbar{min-height:38px;padding:6px;display:flex;align-items:center;gap:6px;border:1px solid var(--wao-line-control);border-bottom:0;border-radius:8px 8px 0 0;background:var(--wao-bg-sub)}
.embed-toolbar button{min-height:27px;padding:0 8px;border:1px solid var(--wao-line-control);border-radius:6px;background:var(--wao-bg-card);color:var(--wao-text-3);font:inherit;font-size:10px;cursor:pointer}.embed-toolbar button:disabled{opacity:.45;cursor:not-allowed}.embed-toolbar span{margin-left:auto;color:var(--wao-text-weak);font-size:9px}
.article-body-editor>textarea{min-height:360px;border-radius:0 0 8px 8px!important;resize:vertical;line-height:1.55;font-family:ui-monospace,SFMono-Regular,Consolas,monospace!important}
.embed-mask{position:fixed;inset:0;z-index:70;padding:18px;display:grid;place-items:center;background:rgba(10,30,27,.58)}.embed-dialog{width:min(620px,100%);max-height:calc(100vh - 36px);padding:18px;display:grid;gap:13px;overflow:auto;border-radius:15px;background:var(--wao-bg-card);box-shadow:0 24px 70px rgba(0,0,0,.3)}
.embed-dialog header,.embed-dialog footer{display:flex;align-items:center;justify-content:space-between;gap:10px}.embed-dialog header>div{display:grid;gap:3px}.embed-dialog header small,.embed-dialog p{margin:0;color:var(--wao-text-weak);font-size:10px}.embed-dialog header>button{border:0;background:transparent;color:var(--wao-text-weak);font-size:18px}.embed-dialog label{display:grid;gap:5px;color:var(--wao-text-3);font-size:10px}.embed-dialog select,.embed-dialog textarea{box-sizing:border-box;width:100%;padding:9px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text);font:inherit}.embed-dialog textarea{resize:vertical;font-family:ui-monospace,SFMono-Regular,Consolas,monospace}.embed-dialog footer{justify-content:flex-end}.embed-dialog footer button{min-height:35px;padding:0 13px;border:0;border-radius:8px;background:var(--wao-primary);color:#fff}.embed-dialog footer .secondary{border:1px solid var(--wao-line-control);background:var(--wao-bg-card);color:var(--wao-text-3)}
@media(max-width:600px){.embed-toolbar{flex-wrap:wrap}.embed-toolbar span{width:100%;margin:0}.embed-mask{padding:8px}.embed-dialog{max-height:calc(100vh - 16px);padding:14px}}
</style>
