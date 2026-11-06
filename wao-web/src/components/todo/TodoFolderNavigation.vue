<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { Project } from '@/types'
import { useTaskOrganizationPreference } from '@/composables/taskOrganizationPreference'
import { useSessionStore } from '@/stores/session'
import { decodeTaskFolders, organizedProjectGroups, type TaskFolderSettings } from '@/utils/taskOrganization'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
const props = defineProps<{ projects: Project[]; selection: string }>()
const emit = defineEmits<{ select: [value: string]; menu: [project: Project, event: MouseEvent]; create: [] }>()
const preferences = useTaskOrganizationPreference('todo-list-folders-v1', decodeTaskFolders)
const session = useSessionStore(), search = ref(''), collapsed = ref<string[]>([]), manage = ref(false)
const name = ref(''), formError = ref(''), editing = ref(''), editorSearch = ref('')
const settings = preferences.value
const ready = computed(() => preferences.loaded.value && preferences.state.value !== 'loading' && preferences.state.value !== 'saving' && !preferences.error.value)
const visibleProjects = computed(() => props.projects.filter(project => !search.value.trim() || project.name.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase())))
const groups = computed(() => organizedProjectGroups(visibleProjects.value, settings.value))
const editProjects = computed(() => props.projects.filter(project => !editorSearch.value.trim() || project.name.toLocaleLowerCase().includes(editorSearch.value.trim().toLocaleLowerCase())))
const identity = computed(() => `${session.account?.accountId ?? ''}:${session.userId}`)
const draftKey = () => `wao-folder-name-draft:${identity.value}`
let generation = 0
onBeforeUnmount(() => { generation++ })
watch(identity, () => {
  generation++
  search.value = ''; collapsed.value = []; manage.value = false; name.value = ''; editing.value = ''; formError.value = ''; editorSearch.value = ''
  try { const draft = JSON.parse(localStorage.getItem(draftKey()) || 'null'); name.value = draft?.name || ''; editing.value = draft?.editing || '' }
  catch { formError.value = '文件夹名称草稿无法读取。' }
}, { immediate:true, flush:'sync' })
function protect() {
  try { localStorage.setItem(draftKey(), JSON.stringify({ name:name.value, editing:editing.value })) }
  catch { formError.value = '名称草稿未能写入本机，请保持窗口打开。' }
}
async function save(value: TaskFolderSettings) {
  if (!ready.value) return false
  if (JSON.stringify(value).length>60_000) {formError.value='清单组织内容过多，请减少文件夹后重试。';return false}
  const attempt = generation, owner = identity.value
  await preferences.set(value)
  return attempt === generation && owner === identity.value && !preferences.error.value
}
async function saveFolder() {
  formError.value = ''
  const label = name.value.trim()
  if (!label) { formError.value = '请输入文件夹名称。'; return }
  if (settings.value.folders.some(folder => folder.name === label && folder.id !== editing.value)) { formError.value = '已有同名文件夹。'; return }
  if (!editing.value && settings.value.folders.length >= 30) { formError.value = '最多创建 30 个文件夹。'; return }
  protect()
  const id = editing.value || crypto.randomUUID()
  const folders = settings.value.folders.some(folder => folder.id === id)
    ? settings.value.folders.map(folder => folder.id === id ? { ...folder, name:label } : folder)
    : [...settings.value.folders, { id, name:label }]
  if (await save({ ...settings.value, folders })) { name.value='';editing.value='';protect() }
}
function rename(id: string) { editing.value=id;name.value=settings.value.folders.find(folder=>folder.id===id)?.name||'';protect() }
async function remove(id: string) {
  if (await save({ folders: settings.value.folders.filter(folder => folder.id !== id), assignments: Object.fromEntries(Object.entries(settings.value.assignments).filter(([,folder]) => folder !== id)) })) {
    if (editing.value === id) { name.value='';editing.value='';protect() }
  }
}
async function move(id: string, direction: number) {
  const folders = [...settings.value.folders], index = folders.findIndex(folder=>folder.id===id), target = index + direction
  if (index < 0 || target < 0 || target >= folders.length) return
  const item = folders.splice(index,1)[0]!
  folders.splice(target,0,item)
  await save({ ...settings.value, folders })
}
async function assign(project: string, event: Event) {
  const folder=(event.target as HTMLSelectElement).value, assignments={ ...settings.value.assignments }
  if (folder) assignments[project]=folder
  else delete assignments[project]
  await save({ ...settings.value, assignments })
}
function toggle(id: string) { collapsed.value=collapsed.value.includes(id)?collapsed.value.filter(value=>value!==id):[...collapsed.value,id] }
</script>
<template>
  <section class="folder-navigation" aria-label="清单与文件夹">
    <div class="list-heading"><span>清单</span><div><button type="button" aria-label="整理清单文件夹" title="整理清单文件夹" @click="manage=true"><i class="ri-folder-settings-line" aria-hidden="true" /></button><button type="button" aria-label="新建清单" title="新建清单" @click="emit('create')"><i class="ri-add-line" aria-hidden="true" /></button></div></div>
    <input v-if="projects.length>7 || search" v-model="search" class="list-search" type="search" aria-label="搜索清单" placeholder="搜索清单" />
    <div v-for="group in groups" :key="group.id" class="folder-group">
      <button v-if="group.name" class="folder-heading" :aria-expanded="!!search || !collapsed.includes(group.id)" @click="toggle(group.id)"><i :class="!search && collapsed.includes(group.id)?'ri-arrow-right-s-line':'ri-arrow-down-s-line'" aria-hidden="true" /><i class="ri-folder-line" aria-hidden="true" /><span>{{ group.name }}</span><small>{{ group.projects.length }}</small></button>
      <div v-show="!!search || !collapsed.includes(group.id)">
        <div v-for="project in group.projects" :key="project.id" class="list-entry">
          <button :class="{active:selection==='project:'+project.id}" :aria-current="selection==='project:'+project.id ? 'page' : undefined" @click="emit('select','project:'+project.id)"><i class="ri-list-check" aria-hidden="true" /><span>{{ project.name }}</span></button>
          <button :aria-label="`清单操作 ${project.name}`" @click="emit('menu',project,$event)"><i class="ri-more-line" aria-hidden="true" /></button>
        </div>
        <small v-if="group.id && !group.projects.length && !search" class="empty-folder">暂无清单</small>
      </div>
    </div>
    <small v-if="search && !visibleProjects.length" class="empty-folder">没有匹配的清单</small>
    <p v-if="preferences.error.value" class="feedback" role="status">{{ preferences.error.value }}<button @click="preferences.retry()">重试</button></p>
    <SettingsDialogShell v-if="manage" title="整理清单文件夹" description="文件夹只整理你的导航，不移动任务或改变共享权限；修改随账号保存。" width="640px" height="min(680px, calc(100dvh - 48px))" icon="ri-folder-settings-line" :close-disabled="preferences.state.value==='saving'" @close="protect();manage=false">
      <div class="folder-form">
        <p v-if="!preferences.loaded.value" role="status">{{ preferences.error.value ? '恢复同步后可继续编辑。' : '正在读取账号文件夹…' }}</p>
        <form @submit.prevent="saveFolder"><WaoField v-slot="field" :label="editing ? '重命名文件夹' : '新建文件夹'"><input :id="field.id" v-model="name" maxlength="40" :disabled="!ready" @input="protect" placeholder="例如：工作、生活" /></WaoField><div class="name-actions"><WaoButton type="submit" :disabled="!ready || !name.trim()">{{ editing?'保存名称':'创建文件夹' }}</WaoButton><WaoButton v-if="editing" type="button" variant="ghost" @click="name='';editing='';protect()">取消重命名</WaoButton></div></form>
        <p v-if="formError || preferences.error.value" class="error" role="alert">{{ formError || preferences.error.value }}<WaoButton v-if="preferences.error.value" variant="ghost" @click="preferences.retry()">重试同步</WaoButton></p>
        <div v-if="preferences.conflict.value" class="conflict-choice" role="status"><details><summary>查看账号版本</summary><ul><li v-for="group in organizedProjectGroups(projects,preferences.remote.value)" :key="group.id">{{ group.name || '未分组' }}：{{ group.projects.map(project=>project.name).join('、') || '暂无清单' }}</li></ul></details><WaoButton :disabled="preferences.state.value==='loading' || !preferences.remoteReady.value" @click="preferences.useRemote()">使用账号版本</WaoButton><WaoButton :disabled="preferences.state.value==='loading' || !preferences.remoteReady.value" @click="preferences.keepLocal()">保留本机版本并保存</WaoButton></div>
        <div class="folder-order" aria-label="文件夹顺序"><div v-for="(folder,index) in settings.folders" :key="folder.id"><span>{{ folder.name }}</span><WaoButton variant="ghost" :disabled="!ready || index===0" :aria-label="`上移 ${folder.name}`" @click="move(folder.id,-1)"><i class="ri-arrow-up-line" /></WaoButton><WaoButton variant="ghost" :disabled="!ready || index===settings.folders.length-1" :aria-label="`下移 ${folder.name}`" @click="move(folder.id,1)"><i class="ri-arrow-down-line" /></WaoButton><WaoButton variant="ghost" :disabled="!ready" @click="rename(folder.id)">改名</WaoButton><WaoButton variant="ghost" :disabled="!ready" title="移除文件夹后，清单回到未分组；任务不变" @click="remove(folder.id)">解散</WaoButton></div></div>
        <WaoField v-slot="field" label="搜索要整理的清单"><input :id="field.id" v-model="editorSearch" type="search" placeholder="按清单名称搜索" /></WaoField>
        <div class="assignments"><label v-for="project in editProjects" :key="project.id"><span>{{ project.name }}</span><select :value="settings.assignments[project.id]||''" :disabled="!ready" :aria-label="`${project.name}的文件夹`" @change="assign(project.id,$event)"><option value="">未分组</option><option v-for="folder in settings.folders" :key="folder.id" :value="folder.id">{{ folder.name }}</option></select></label><p v-if="!editProjects.length">没有匹配的清单。</p></div>
      </div>
      <template #footer><div class="folder-footer"><small role="status">{{ preferences.state.value==='saving'?'正在保存…':preferences.error.value?'本机修改已保留，等待同步':preferences.loaded.value?'已同步到账号':'读取中…' }}</small><WaoButton :disabled="preferences.state.value==='saving'" @click="protect();manage=false">完成</WaoButton></div></template>
    </SettingsDialogShell>
  </section>
</template>
<style scoped>
.conflict-choice { padding:var(--wao-space-1); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); display:flex; gap:var(--wao-space-1); flex-wrap:wrap; } .conflict-choice details { flex:1 0 100%; }
.folder-navigation { margin-top:var(--wao-space-2); min-width:0; color:var(--wao-text); font:400 var(--wao-font-body)/var(--wao-line-body) var(--wao-font); }
.list-heading,.list-heading>div,.list-entry,.folder-heading,.folder-order>div,.name-actions,.folder-footer { display:flex; align-items:center; gap:var(--wao-space-1); }
.list-heading,.folder-footer { justify-content:space-between; }.list-heading>span { color:var(--wao-text-3); font-size:var(--wao-font-caption); }
button { font:inherit; color:inherit; border:0; border-radius:var(--wao-radius-control); background:transparent; cursor:pointer; padding:var(--wao-space-1); min-height:38px; }
button:hover { background:var(--wao-bg-hover); }button.active { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
.list-entry>button:first-child { display:flex; align-items:center; gap:var(--wao-space-1); flex:1; min-width:0; text-align:left; }.list-entry span,.folder-heading>span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.folder-heading { width:100%; min-width:0; text-align:left; }.folder-heading>span { flex:1; }.folder-heading small { color:var(--wao-text-3); }
.empty-folder,.feedback { display:block; font-size:var(--wao-font-caption); color:var(--wao-text-3); margin:var(--wao-space-1); }.feedback { overflow-wrap:anywhere; }
.list-search,input,select { box-sizing:border-box; min-width:0; max-width:100%; color:var(--wao-text); background:var(--wao-bg-card); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); padding:var(--wao-space-1); font:inherit; }.list-search { width:100%; }
.folder-form { padding:var(--wao-space-2); display:grid; gap:var(--wao-space-2); color:var(--wao-text); font:400 var(--wao-font-body)/var(--wao-line-body) var(--wao-font); }.name-actions { margin-top:var(--wao-space-1); flex-wrap:wrap; }
.folder-order>div { flex-wrap:wrap; border-bottom:1px solid var(--wao-line); }.folder-order span { flex:1; overflow-wrap:anywhere; min-width:80px; }.assignments { display:grid; gap:var(--wao-space-1); }.assignments label { display:grid; grid-template-columns:minmax(0,1fr) minmax(100px,1fr); align-items:center; gap:var(--wao-space-2); }.assignments label span { overflow-wrap:anywhere; }
.error { color:var(--wao-danger-strong); margin:0; }.folder-footer { width:100%; }.folder-footer small { color:var(--wao-text-3); font-size:var(--wao-font-caption); }
button:focus-visible,input:focus-visible,select:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
@media(pointer:coarse) { button,input,select { min-height:44px; } }
</style>
