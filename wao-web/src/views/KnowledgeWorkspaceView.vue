<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useTaskViewMemory } from '@/composables/taskViewMemory'
import KbDetailView from './KbDetailView.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import KnowledgeIconPicker from '@/components/knowledge/KnowledgeIconPicker.vue'
import PaneResizeHandle from '@/components/ui/PaneResizeHandle.vue'
import { useSessionStore } from '@/stores/session'
import { directoryRows } from '@/utils/knowledgeDirectory'
const data = useDataStore(), route = useRoute(), router = useRouter()
const memory = useTaskViewMemory('knowledge-workspace', () => ({ collapsed: false }))
const selected = computed(() => String(route.params.id || route.query.kb || ''))
const libraries = computed(() => data.kbs.filter(k => !k.notes))
const session = useSessionStore()
const directoryMemory = useTaskViewMemory('knowledge-directory', () => ({ collapsed: false }))
const directoryWidth = ref(262)
const folderOpen = ref<Record<string, boolean>>({})
const query = ref('')
const index = computed(() => libraries.value.map(kb => ({ kb, rows: directoryRows(kb, id => data.docById(id)?.title || '未命名文档', {}, kb.name.includes(query.value.trim()) ? '' : query.value) })).filter(item => item.rows.length || !query.value.trim() || item.kb.name.includes(query.value.trim())))
function open(kb = '', doc = '') { void router.push({ path: '/knowledge', query: { ...(kb ? { kb } : { home: '1' }), ...(doc ? { doc } : {}) } }) }
const creating = ref(false), saving = ref(false), name = ref(''), description = ref(''), icon = ref('wao-kb-icon-notebook'), error = ref('')
function startCreate() { name.value = ''; description.value = ''; icon.value = 'wao-kb-icon-notebook'; error.value = ''; creating.value = true }
async function create() {
  if (!name.value.trim() || saving.value) return
  saving.value = true; error.value = ''
  try {
    const kb = await data.addKb({ name: name.value.trim(), desc: description.value.trim(), icon: icon.value })
    if (!kb) throw new Error('create failed')
    creating.value = false; open(kb.id)
  } catch { error.value = '创建失败，输入已保留，请重试。' }
  finally { saving.value = false }
}
</script>
<template>
  <div class="knowledge-workspace">
    <aside class="libraries" :class="{ collapsed: memory.collapsed }" aria-label="知识库清单">
      <header><strong v-if="!memory.collapsed">知识库</strong><button class="pane-toggle" :aria-label="memory.collapsed ? '展开知识库清单' : '收起知识库清单'" :aria-expanded="!memory.collapsed" @click="memory.collapsed = !memory.collapsed"><i class="ri-side-bar-line" /></button></header>
      <div v-show="!memory.collapsed" class="library-list">
        <button :class="{ active: !selected }" @click="open()"><i class="ri-home-4-line" />全部内容</button>
        <button v-for="kb in libraries" :key="kb.id" :title="kb.name" :class="{ active: selected === kb.id }" @click="open(kb.id)"><i :class="kb.icon" /><span>{{ kb.name }}</span></button>
        <WaoButton variant="ghost" @click="startCreate"><i class="ri-add-line" />新增知识库</WaoButton>
      </div>
    </aside>
    <aside v-if="!selected" class="home-directory" :class="{ collapsed: directoryMemory.collapsed }" :style="{ width: directoryMemory.collapsed ? '44px' : `${directoryWidth}px` }">
      <button class="pane-toggle" :aria-label="directoryMemory.collapsed ? '展开知识库目录' : '收起知识库目录'" :aria-expanded="!directoryMemory.collapsed" @click="directoryMemory.collapsed = !directoryMemory.collapsed"><i class="ri-side-bar-line" /></button>
      <div v-show="!directoryMemory.collapsed" class="all-tree">
        <PaneResizeHandle :storage-key="`wao:directory-width:${session.currentUser.id}`" label="知识库目录宽度" :default-width="262" :max="520" @resize="directoryWidth = $event" />
        <strong>全部目录</strong>
        <section v-for="kb in libraries" :key="kb.id">
          <button class="library-title" @click="open(kb.id)">{{ kb.name }}</button>
          <button v-for="node in directoryRows(kb, id => data.docById(id)?.title || '未命名文档', folderOpen, '')" :key="node.key" class="tree-node" :style="{ paddingInlineStart: `${node.depth * 16}px` }" @click="node.kind === 'doc' ? open(kb.id, node.id) : folderOpen[node.key] = folderOpen[node.key] === false"><i :class="node.kind === 'doc' ? 'ri-file-text-line' : 'ri-folder-line'" /><span>{{ node.title }}</span></button>
        </section>
      </div>
    </aside>
    <main class="workspace-content">
      <KbDetailView v-if="selected" />
      <section v-else class="knowledge-home">
        <header><h1>知识库</h1><p>全部内容</p><WaoField label="查找文件" v-slot="field"><input :id="field.id" v-model="query" type="search" placeholder="搜索知识库、文件夹或文档" /></WaoField></header>
        <p v-if="!index.length" class="empty">{{ query ? '没有匹配的内容' : '暂无知识库，点击左侧新增知识库开始整理。' }}</p>
        <section v-for="item in index" :key="item.kb.id" class="index-library">
          <button class="library-title" @click="open(item.kb.id)"><i :class="item.kb.icon" />{{ item.kb.name }}<i class="ri-arrow-right-s-line" /></button>
          <div v-for="node in item.rows" :key="node.key" class="index-row" :style="{ paddingInlineStart: `${node.depth * 20}px` }">
            <button v-if="node.kind === 'doc'" @click="open(item.kb.id, node.id)"><i class="ri-file-text-line" />{{ node.title }}</button>
            <span v-else><i class="ri-folder-line" />{{ node.title }}</span>
          </div>
          <p v-if="!item.rows.length" class="empty">暂无文档</p>
        </section>
      </section>
    </main>
    <SettingsDialogShell v-if="creating" title="新增知识库" width="520px" height="auto" :close-disabled="saving" @close="creating = false">
      <form class="create-form" @submit.prevent="create">
        <WaoField label="名称" required v-slot="field"><input :id="field.id" v-model="name" :disabled="saving" required autofocus /></WaoField>
        <WaoField label="简介" v-slot="field"><textarea :id="field.id" v-model="description" :disabled="saving" rows="2" /></WaoField>
        <KnowledgeIconPicker v-model="icon" :disabled="saving" />
        <p v-if="error" role="alert">{{ error }}</p>
        <WaoButton type="submit" :disabled="saving || !name.trim()">{{ saving ? '创建中…' : '创建' }}</WaoButton>
      </form>
    </SettingsDialogShell>
  </div>
</template>
<style scoped>
.knowledge-workspace { position:relative; display:flex; height:calc(100dvh - var(--wao-header-h) - var(--wao-tabbar-h)); min-width:0; overflow:hidden; color:var(--wao-text); background:var(--wao-bg-card); }
.libraries { flex:0 0 208px; width:208px; border-right:1px solid var(--wao-line); background:var(--wao-bg-sub); overflow:auto; }
.libraries.collapsed { flex-basis:44px; width:44px; }
.libraries header { display:flex; align-items:center; justify-content:space-between; padding:var(--wao-space-1); min-height:52px; }
.libraries.collapsed header { padding:0; }
button { color:inherit; font:inherit; cursor:pointer; }
.pane-toggle { border:0; background:transparent; min-width:44px; min-height:44px; }
.library-list { display:grid; gap:var(--wao-space-1); padding:var(--wao-space-1); }
.library-list > button { display:flex; align-items:center; gap:var(--wao-space-1); text-align:left; min-height:44px; padding:var(--wao-space-1); border:0; border-radius:var(--wao-radius-control); background:transparent; }
.library-list > button.active { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
.library-list span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.home-directory { position:relative; flex-shrink:0; border-right:1px solid var(--wao-line); overflow:auto; }
.all-tree { padding:var(--wao-space-1); }
.tree-node { display:flex; gap:var(--wao-space-1); align-items:center; width:100%; min-height:36px; border:0; background:transparent; text-align:left; }
.tree-node span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.workspace-content { flex:1; min-width:0; overflow:hidden; }
.workspace-content :deep(.kb-detail) { height:100%; }
.knowledge-home { height:100%; overflow:auto; padding:var(--wao-space-3); }
h1 { font-size:var(--wao-font-title); margin:0; }
.knowledge-home header { max-width:720px; }
.index-library { margin-top:var(--wao-space-3); border-top:1px solid var(--wao-line); padding-top:var(--wao-space-2); }
.library-title, .index-row button { display:flex; gap:var(--wao-space-1); align-items:center; border:0; background:transparent; min-height:44px; text-align:left; overflow-wrap:anywhere; }
.library-title { font-size:var(--wao-font-section); font-weight:600; }
.index-row span { display:flex; gap:var(--wao-space-1); align-items:center; min-height:36px; color:var(--wao-text-3); }
.empty { color:var(--wao-text-3); }
.create-form { padding:var(--wao-space-3); display:grid; gap:var(--wao-space-2); }
@media(max-width:900px) {
  .libraries { flex-basis:44px; width:44px; overflow:visible; z-index:4; }
  .libraries:not(.collapsed) { position:absolute; inset:0 auto 0 0; width:208px; overflow:auto; box-shadow:var(--wao-shadow-pop); }
  .libraries:not(.collapsed) ~ .workspace-content { margin-left:44px; }
  .home-directory { flex-basis:44px; width:44px !important; background:var(--wao-bg-card); }
  .home-directory:not(.collapsed) { position:absolute; inset:0 auto 0 44px; width:min(262px, calc(100% - 44px)) !important; z-index:3; box-shadow:var(--wao-shadow-pop); }
  .home-directory:not(.collapsed) ~ .workspace-content { margin-left:44px; }
  .libraries:not(.collapsed) ~ .home-directory:not(.collapsed) ~ .workspace-content { margin-left:88px; }
  .knowledge-home { padding:var(--wao-space-2); }
}
</style>

