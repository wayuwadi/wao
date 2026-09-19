<script setup lang="ts">
import KnowledgeIconPicker from '@/components/knowledge/KnowledgeIconPicker.vue'
import { isColoredKnowledgeIcon } from '@/utils/knowledgeIcons'
import { useTopShortcutsStore, resourceShortcut } from '@/stores/topShortcuts'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useTabsStore } from '@/stores/tabs'
import { useDataStore } from '@/stores/data'
import { toast } from '@/composables/toast'
import { relativeTime } from '@/utils/time'
import { useNow } from '@/composables/now'
import type { KnowledgeBase, TrashEntry } from '@/types'

import { useKnowledgeFavoritesStore } from '@/stores/knowledgeFavorites'
const knowledgeFavorites = useKnowledgeFavoritesStore()
const favoriteKbs = computed(() => data.kbs.filter((kb) => knowledgeFavorites.ids.includes(kb.id) && matchesQuery(kb) && matchesScope(kb)))
const data = useDataStore()
const shortcuts = useTopShortcutsStore()
const router = useRouter()
const tabs = useTabsStore()
const now = useNow()

onMounted(() => {
  void data.refreshTrash()
  void knowledgeFavorites.load()
})

const editGroupId = ref<string | null>(null)
const cfgKb = ref<KnowledgeBase | null>(null)
const cfgSaving = ref(false)
const cfgError = ref('')
const newKbOpen = ref(false)
const newKbName = ref('')
const newKbDesc = ref('')
const newKbIcon = ref('wao-kb-icon-notebook')
const newKbSaving = ref(false)
const newKbError = ref('')
const query = ref('')
type KnowledgeScope = 'all' | 'shared' | 'personal'
const scope = ref<KnowledgeScope>('all')

const scopeOptions: { value: KnowledgeScope; label: string }[] = [
  { value: 'all', label: '全部资料' },
  { value: 'shared', label: '共享资料' },
  { value: 'personal', label: '个人资料' },
]

function docCount(k: KnowledgeBase) {
  return k.folders.reduce((n, f) => n + f.docs.length, 0)
}

function matchesScope(kb: KnowledgeBase) {
  if (scope.value === 'shared') return kb.perm === '成员可编辑'
  if (scope.value === 'personal') return !kb.auto && kb.perm !== '成员可编辑'
  return true
}

function matchingDocs(kb: KnowledgeBase) {
  const keyword = query.value.trim().toLocaleLowerCase('zh-CN')
  if (!keyword) return []
  return kb.folders.flatMap((folder) => folder.docs)
    .map((id) => data.docById(id))
    .filter((doc): doc is NonNullable<typeof doc> => !!doc?.title.toLocaleLowerCase('zh-CN').includes(keyword))
    .slice(0, 3)
}

function matchesQuery(kb: KnowledgeBase) {
  const keyword = query.value.trim().toLocaleLowerCase('zh-CN')
  if (!keyword) return true
  return kb.name.toLocaleLowerCase('zh-CN').includes(keyword)
    || kb.desc.toLocaleLowerCase('zh-CN').includes(keyword)
    || kb.folders.some((folder) => folder.name.toLocaleLowerCase('zh-CN').includes(keyword))
    || matchingDocs(kb).length > 0
}

const groups = computed(() => {
  const fallback = data.myGroups[0]?.id || 'g1'
  return data.myGroups.map((g) => ({
    id: g.id, label: g.name, fixed: false, hint: '',
    items: data.kbs.filter((k) => (data.myGroups.some((group) => group.id === k.group) ? k.group : fallback) === g.id
      && matchesScope(k) && matchesQuery(k)),
  }))
})

const visibleCount = computed(() => groups.value.reduce((total, group) => total + group.items.length, 0))
const filtering = computed(() => scope.value !== 'all' || !!query.value.trim())

function openKb(id: string) {
  router.push(tabs.tabs.find((tab) => tab.key === `kb:${id}`)?.path || `/kb/${id}`)
}
function openDoc(kbId: string, docId: string) {
  router.push({ path: `/kb/${kbId}`, query: { doc: docId } })
}
async function newGroup() {
  editGroupId.value = await data.addGroup()
}
function onGroupInput(g: { id: string; name: string }, e: Event) {
  g.name = (e.target as HTMLInputElement).value
}
function commitGroupRename(g: { id: string; name: string }) {
  editGroupId.value = null
  data.renameGroup(g.id, g.name || '新建分组')
}
function openCfg(k: KnowledgeBase) {
  if (!k.perm) k.perm = '仅自己'
  cfgKb.value = { ...k }
  cfgError.value = ''
}
async function cfgDone() {
  if (!cfgKb.value || cfgSaving.value) return
  cfgSaving.value = true
  cfgError.value = ''
  const k = cfgKb.value
  const saved = await data.updateKb(k.id, { name: k.name, desc: k.desc, icon: k.icon, color: k.color, group: k.group, perm: k.perm })
  cfgSaving.value = false
  if (!saved) { cfgError.value = '保存失败，输入已保留，请重试。'; return }
  tabs.setMeta(`kb:${k.id}`, { label: k.name, icon: k.icon })
  cfgKb.value = null
  toast('知识库设置已保存', 'ri-checkbox-circle-line')
}

async function cfgDelete() {
  if (!cfgKb.value) return
  if (!window.confirm(`删除知识库「${cfgKb.value.name}」及其中全部文件夹和文档？删除后会进入回收站，7 天内可恢复。`)) return
  const id = cfgKb.value.id
  cfgKb.value = null
  await data.deleteKb(id)
  toast('已放进回收站，7 天内可恢复', 'ri-delete-bin-line')
}
// ---- 回收站 ----

const trashOpen = ref(false)
const TRASH_ICON: Record<string, string> = { kb: 'ri-book-2-line', folder: 'ri-folder-3-line', doc: 'ri-file-text-line' }

/** 还剩几天被彻底删除；已过期（等下一轮清理）显示「即将清理」。 */
function daysLeft(entry: TrashEntry): string {
  const ms = new Date(entry.expiresAt).getTime() - now.value.getTime()
  if (Number.isNaN(ms)) return ''
  if (ms <= 0) return '即将清理'
  const days = Math.ceil(ms / 86_400_000)
  return `${days} 天后清理`
}
function openTrash() {
  trashOpen.value = true
  void data.refreshTrash()
  void knowledgeFavorites.load()
}
function closeTrash() {
  trashOpen.value = false
}
async function restore(entry: TrashEntry) {
  await data.restoreTrash(entry.type, entry.id)
  toast(`已恢复「${entry.name}」`, 'ri-arrow-go-back-line')
}
async function purge(entry: TrashEntry) {
  if (!window.confirm(`彻底删除「${entry.name}」？此操作不可恢复。`)) return
  await data.purgeTrash(entry.type, entry.id)
  toast(`已彻底删除「${entry.name}」`, 'ri-delete-bin-7-line')
}

function openNewKb() {
  newKbName.value = ''
  newKbDesc.value = ''
  newKbIcon.value = 'wao-kb-icon-notebook'
  newKbError.value = ''
  newKbOpen.value = true
}
async function createKb() {
  if (!newKbName.value.trim() || newKbSaving.value) return
  newKbSaving.value = true
  newKbError.value = ''
  try {
    const kb = await data.addKb({ name: newKbName.value.trim(), desc: newKbDesc.value.trim(), icon: newKbIcon.value })
    if (!kb) throw new Error('知识库创建失败，请重试')
    newKbOpen.value = false
    openKb(kb.id)
  } catch { newKbError.value = '创建失败，名称、简介和图标已保留，请重试。' }
  finally { newKbSaving.value = false }
}

</script>

<template>
  <div class="know">
    <div class="ktop">
      <div class="ktitle">知识库</div>
      <span class="ktip">按知识库组织文档；任务可关联具体文档</span>
      <div class="spacer"></div>
      <button class="btn-ghost" @click="openTrash">
        <i class="ri-delete-bin-line"></i>回收站<span v-if="data.trash.length" class="trash-badge">{{ data.trash.length }}</span>
      </button>
      <button class="btn-ghost" @click="newGroup"><i class="ri-folder-add-line"></i>新建分组</button>
      <button class="btn-primary" @click="openNewKb"><i class="ri-add-line"></i>新建知识库</button>
    </div>

    <div class="kb-discovery">
      <label class="kb-search">
        <i class="ri-search-line"></i>
        <input v-model="query" type="search" placeholder="搜索知识库、文件夹或文档标题" />
        <button v-if="query" type="button" aria-label="清空搜索" @click="query = ''"><i class="ri-close-line"></i></button>
      </label>
      <div class="kb-scopes" role="tablist" aria-label="资料范围">
        <button
          v-for="option in scopeOptions"
          :key="option.value"
          role="tab"
          :aria-selected="scope === option.value"
          :class="{ on: scope === option.value }"
          @click="scope = option.value"
        >
          {{ option.label }}
        </button>
      </div>
      <span class="kb-result-count">{{ visibleCount }} 个知识库</span>
    </div>

    <section v-if="favoriteKbs.length" class="frequent-section">
      <h2>常用</h2><div class="frequent-grid"><button v-for="k in favoriteKbs" :key="k.id" class="frequent-card" @click="openKb(k.id)"><i :class="k.icon" :style="{ color: k.color }"></i><span><strong>{{ k.name }}</strong><small>{{ k.desc || `${docCount(k)} 篇文档` }}</small></span></button></div>
    </section>
    <div v-for="g in groups" v-show="g.items.length || !filtering" :key="g.id" class="kgroup">
      <div class="kgroup-head">
        <template v-if="!g.fixed && editGroupId === g.id">
          <input
            class="group-input"
            :value="g.label"
            @input="onGroupInput(data.myGroups.find((x) => x.id === g.id)!, $event)"
            @keydown.enter="commitGroupRename(data.myGroups.find((x) => x.id === g.id)!)"
          />
          <button class="mini-primary" @click="commitGroupRename(data.myGroups.find((x) => x.id === g.id)!)">确定</button>
        </template>
        <template v-else>
          <div class="kgroup-label">{{ g.label }}</div>
          <i v-if="g.fixed" class="ri-lock-line lock" title="默认分组，不可修改"></i>
          <button v-else class="rename" title="重命名分组" @click="editGroupId = g.id"><i class="ri-edit-line"></i></button>
        </template>
        <span class="kgroup-count wao-tabular">{{ g.items.length }}</span>
        <span class="kgroup-hint">{{ g.hint }}</span>
      </div>

      <div class="kb-grid">
        <div
          v-for="k in g.items"
          :key="k.id"
          class="kb-card"
          draggable="true" @dragstart="shortcuts.start(resourceShortcut('kb', k.id, k.name), $event)" @dragend="shortcuts.end"
          :style="{ borderLeftColor: k.color }"
          @click="openKb(k.id)"
        >
          <div class="kb-top">
            <div class="kb-icon" :style="{ background: isColoredKnowledgeIcon(k.icon) ? 'transparent' : k.color }"><i :class="k.icon"></i></div>
            <div class="kb-name">{{ k.name }}</div>
            <span v-if="k.auto" class="tag auto">自动</span>
            <span v-if="k.perm === '成员可编辑'" class="tag shared">已共享</span>
            <button class="gear" :title="knowledgeFavorites.ids.includes(k.id) ? '取消常用' : '添加为常用'" :aria-pressed="knowledgeFavorites.ids.includes(k.id)" :disabled="knowledgeFavorites.saving" @click.stop="knowledgeFavorites.toggle(k.id)"><i :class="knowledgeFavorites.ids.includes(k.id) ? 'ri-star-fill' : 'ri-star-line'"></i></button>
            <button class="gear" title="知识库设置" @click.stop="openCfg(k)"><i class="ri-settings-3-line"></i></button>
          </div>
          <div class="kb-desc">{{ k.desc }}</div>
          <div v-if="matchingDocs(k).length" class="kb-doc-matches">
            <button v-for="doc in matchingDocs(k)" :key="doc.id" @click.stop="openDoc(k.id, doc.id)">
              <i class="ri-file-text-line"></i><span>{{ doc.title }}</span><i class="ri-arrow-right-s-line"></i>
            </button>
          </div>
          <div class="kb-meta">
            <i class="ri-file-text-line"></i>{{ docCount(k) }} 篇文档
            <span class="spacer"></span>
          </div>
        </div>
      </div>
      <div v-if="g.items.length === 0" class="kgroup-empty">这个分组还是空的——在知识库设置中可将知识库移入此分组</div>
    </div>
    <div v-if="filtering && visibleCount === 0" class="kb-filter-empty">
      <i class="ri-search-eye-line"></i>
      <b>没有匹配的真实资料</b>
      <span>换个关键词或资料范围试试。</span>
      <button @click="query = ''; scope = 'all'">查看全部资料</button>
    </div>

    <!-- 知识库设置弹层 -->
    <template v-if="cfgKb">
      <div class="modal-mask" @click="!cfgSaving && (cfgKb = null)"></div>
      <div class="modal">
        <div class="modal-head">
          <div class="modal-icon" :style="{ background: isColoredKnowledgeIcon(cfgKb.icon) ? 'transparent' : cfgKb.color }"><i :class="cfgKb.icon"></i></div>
          <div class="modal-title">知识库设置</div>
          <div class="spacer"></div>
          <button class="icon-btn" @click="!cfgSaving && (cfgKb = null)"><i class="ri-close-line"></i></button>
        </div>
        <div class="modal-body">
          <p v-if="cfgError" role="alert">{{ cfgError }}</p>
          <label class="f-label">图标</label><KnowledgeIconPicker v-model="cfgKb.icon" :disabled="cfgSaving" />
          <label class="f-label">名称</label>
          <input v-model="cfgKb.name" class="f-input" :disabled="cfgSaving || !!cfgKb.auto" />
          <label class="f-label">简介</label>
          <textarea v-model="cfgKb.desc" class="f-textarea" rows="2" :disabled="cfgSaving || !!cfgKb.auto"></textarea>
          <div v-if="cfgKb.auto" class="f-note">
            自动化知识库跟随来源，名称与简介不可修改。
          </div>

          <div class="kb-group-setting">
            <label class="f-label">所属分组</label>
            <div class="chips">
              <button
                v-for="grp in data.myGroups"
                :key="grp.id"
                class="chip"
                :disabled="cfgSaving"
                :class="{ on: (cfgKb.group || 'g1') === grp.id }"
                @click="cfgKb.group = grp.id"
              >
                {{ grp.name }}
              </button>
            </div>
          </div>

          <label class="f-label">权限</label>
          <div class="perms">
            <button
              v-for="p in [
                { label: '仅自己', desc: '只有我可以查看和编辑', icon: 'ri-lock-line' },
                { label: '成员可编辑', desc: '组织内成员可实时协同编辑', icon: 'ri-team-line' },
              ]"
              :key="p.label"
              class="perm"
              :class="{ on: (cfgKb.perm || '仅自己') === p.label }"
              @click="cfgKb.perm = p.label"
            >
              <i :class="p.icon"></i>
              <div class="perm-main">
                <div class="perm-label">{{ p.label }}</div>
                <div class="perm-desc">{{ p.desc }}</div>
              </div>
            </button>
          </div>
          <div class="f-note">权限对组织内成员直接生效，不生成虚假的外部邀请链接。</div>
        </div>
        <div class="modal-foot">
          <button v-if="!cfgKb.auto && !cfgKb.notes" class="btn-danger" @click="cfgDelete">
            <i class="ri-delete-bin-line"></i>删除
          </button>
          <div class="spacer"></div>
          <button class="btn-primary"  :disabled="cfgSaving" @click="cfgDone">完成</button>
        </div>
      </div>
    </template>

    <!-- 回收站 -->
    <template v-if="trashOpen">
      <div class="modal-mask" @click="closeTrash"></div>
      <div class="modal">
        <div class="modal-head">
          <div class="modal-title">回收站</div>
          <span class="modal-hint">删除的内容保留 7 天，到期自动彻底删除</span>
          <div class="spacer"></div>
          <button class="icon-btn" @click="closeTrash"><i class="ri-close-line"></i></button>
        </div>
        <div class="modal-body">
          <div v-if="!data.trash.length" class="trash-empty">回收站是空的。</div>
          <div v-for="t in data.trash" :key="t.type + t.id" class="trash-row">
            <i :class="TRASH_ICON[t.type]" class="trash-icon"></i>
            <div class="trash-main">
              <div class="trash-name">{{ t.name }}</div>
              <div class="trash-sub">{{ t.parent }} · {{ relativeTime(t.deletedAt, now) }}删除 · {{ daysLeft(t) }}</div>
            </div>
            <button class="mini-ghost" @click="restore(t)"><i class="ri-arrow-go-back-line"></i>恢复</button>
            <button class="mini-danger" title="立刻彻底删除" @click="purge(t)"><i class="ri-delete-bin-7-line"></i></button>
          </div>
        </div>
      </div>
    </template>

    <!-- 新建知识库弹层 -->
    <template v-if="newKbOpen">
      <div class="modal-mask" @click="!newKbSaving && (newKbOpen = false)"></div>
      <div class="modal">
        <div class="modal-head">
          <div class="modal-title">新建知识库</div>
          <div class="spacer"></div>
          <button class="icon-btn" @click="!newKbSaving && (newKbOpen = false)"><i class="ri-close-line"></i></button>
        </div>
        <div class="modal-body">
          <p v-if="newKbError" role="alert">{{ newKbError }}</p>
          <label class="f-label">图标</label><KnowledgeIconPicker v-model="newKbIcon" :disabled="newKbSaving" />
          <label class="f-label">名称</label>
          <input :disabled="newKbSaving" v-model="newKbName" class="f-input" placeholder="给知识库起个名字" @keydown.enter="createKb" />
          <label class="f-label">简介</label>
          <textarea v-model="newKbDesc" :disabled="newKbSaving" class="f-textarea" rows="2" placeholder="选填"></textarea>
        </div>
        <div class="modal-foot">
          <div class="spacer"></div>
          <button class="btn-primary" :disabled="newKbSaving || !newKbName.trim()" @click="createKb">{{ newKbSaving ? '创建中…' : newKbError ? '重试创建' : '创建' }}</button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.frequent-section { background: var(--wao-bg-hover); border-radius: 12px; padding: 20px; margin: 24px 0; }
.frequent-section h2 { font-size: 14px; font-weight: 500; margin: 0 0 14px; color: var(--wao-text-weak); }
.frequent-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 12px; }
.frequent-card { display: flex; gap: 14px; align-items: center; padding: 18px; text-align: left; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card); color: var(--wao-text); cursor: pointer; }
.frequent-card i { font-size: 24px; }
.frequent-card strong,.frequent-card small { display: block; }
.frequent-card small { color: var(--wao-text-weak); margin-top: 4px; }
.know {
  padding: 16px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.spacer {
  flex: 1;
}
.ktop {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.ktitle {
  font-size: 20px;
  font-weight: 700;
}
.ktip {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.kb-discovery {
  margin-top: 18px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
}
.kb-search {
  min-width: 220px;
  flex: 1 1 320px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-page);
}
.kb-search:focus-within {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, .1);
}
.kb-search > i {
  font-size: 14px;
  color: var(--wao-text-weak);
}
.kb-search input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--wao-text);
  font-size: 12.5px;
}
.kb-search button {
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
}
.kb-search button:hover { background: var(--wao-bg-hover); color: var(--wao-text); }
.kb-scopes {
  display: inline-flex;
  gap: 3px;
  padding: 3px;
  border-radius: 9px;
  background: var(--wao-bg-tab);
}
.kb-scopes button {
  height: 28px;
  padding: 0 10px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text-weak);
  font-size: 11.5px;
  cursor: pointer;
  white-space: nowrap;
}
.kb-scopes button.on {
  background: var(--wao-bg-card);
  color: var(--wao-primary-text);
  box-shadow: 0 1px 4px rgba(16, 18, 28, .08);
}
.kb-result-count {
  color: var(--wao-text-faint);
  font-size: 11.5px;
  white-space: nowrap;
}
.btn-ghost {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-ghost:hover {
  background: var(--wao-bg-hover);
}
.btn-ghost i {
  font-size: 14px;
}
.btn-danger {
  height: 32px;
  padding: 0 12px;
  border: 1px solid #f0c9c9;
  background: var(--wao-bg-card);
  border-radius: 8px;
  font-size: 12.5px;
  color: #bc4141;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: 8px;
}
.btn-danger:hover {
  background: var(--wao-danger-bg);
}
.btn-danger i {
  font-size: 14px;
}
.btn-primary {
  height: 32px;
  padding: 0 13px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-primary:hover {
  background: var(--wao-primary-hover);
}
.btn-primary:disabled {
  background: var(--wao-line-strong);
  cursor: not-allowed;
}
.btn-primary i {
  font-size: 14px;
}

.kgroup {
  margin-top: 18px;
}
.kgroup-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.kgroup-label {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--wao-text-2);
}
.lock {
  font-size: 12px;
  color: var(--wao-text-faint-2);
}
.rename {
  width: 22px;
  height: 22px;
  border: none;
  background: none;
  border-radius: 6px;
  cursor: pointer;
  color: var(--wao-text-faint);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.rename:hover {
  background: var(--wao-divider);
  color: var(--wao-primary-text);
}
.rename i {
  font-size: 12px;
}
.kgroup-count {
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
.kgroup-hint {
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
.group-input {
  height: 27px;
  border: 1px solid var(--wao-primary);
  border-radius: 7px;
  padding: 0 9px;
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-text);
  outline: none;
  width: 170px;
  background: var(--wao-bg-card);
}
.mini-primary {
  height: 27px;
  padding: 0 11px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 7px;
  font-size: 12px;
  cursor: pointer;
}

.kb-grid {
  margin-top: 10px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 12px;
}
.kb-card {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-left: 4px solid #4358d6;
  border-radius: 10px;
  padding: 14px 15px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.kb-card:hover {
  box-shadow: 0 3px 12px rgba(16, 18, 28, 0.07);
}
.kb-top {
  display: flex;
  align-items: center;
  gap: 9px;
}
.kb-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  opacity: 0.92;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 16px;
}
.kb-name {
  flex: 1;
  min-width: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tag {
  font-size: 10.5px;
  border-radius: 4px;
  padding: 1px 6px;
  flex-shrink: 0;
}
.tag.auto {
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
}
.tag.shared {
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
}
.gear {
  width: 24px;
  height: 24px;
  border: none;
  background: none;
  border-radius: 6px;
  cursor: pointer;
  color: var(--wao-text-faint);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.gear:hover {
  background: var(--wao-divider);
  color: var(--wao-primary-text);
}
.gear i {
  font-size: 14px;
}
.kb-desc {
  font-size: 12px;
  color: var(--wao-text-3);
  line-height: 1.6;
  min-height: 36px;
}
.kb-doc-matches {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
}
.kb-doc-matches button {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 6px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--wao-text-3);
  font-size: 11.5px;
  text-align: left;
  cursor: pointer;
}
.kb-doc-matches button:hover { background: var(--wao-bg-card); color: var(--wao-primary-text); }
.kb-doc-matches span { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kb-doc-matches i { flex: 0 0 auto; }
.kb-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.kb-meta > i {
  font-size: 12px;
}
.kgroup-empty {
  margin-top: 10px;
  padding: 20px;
  border: 1px dashed #d9dce5;
  border-radius: 10px;
  text-align: center;
  color: var(--wao-text-faint);
  font-size: 12.5px;
}
.kb-filter-empty {
  margin-top: 18px;
  min-height: 220px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed var(--wao-line-strong);
  border-radius: 12px;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.kb-filter-empty > i { font-size: 26px; color: var(--wao-text-faint); }
.kb-filter-empty b { color: var(--wao-text-2); font-size: 13.5px; }
.kb-filter-empty button {
  margin-top: 4px;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-primary-text);
  cursor: pointer;
}

.trash-badge {
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--wao-danger-bg);
  color: #bc4141;
  font-size: 10.5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.trash-empty {
  padding: 28px 0;
  text-align: center;
  color: var(--wao-text-faint);
  font-size: 12.5px;
}
.trash-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  margin: 0 -8px;
  border-radius: 9px;
}
.trash-row:hover {
  background: var(--wao-bg-hover-2);
}
.trash-icon {
  font-size: 15px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.trash-main {
  flex: 1;
  min-width: 0;
}
.trash-name {
  font-size: 13px;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.trash-sub {
  margin-top: 1px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mini-ghost {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 7px;
  font-size: 12px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}
.mini-ghost:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary-text);
}
.mini-danger {
  width: 26px;
  height: 26px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 7px;
  color: var(--wao-text-weak);
  cursor: pointer;
  flex-shrink: 0;
}
.mini-danger:hover {
  border-color: #f0c9c9;
  background: var(--wao-danger-bg);
  color: #bc4141;
}
.modal-hint {
  font-size: 11.5px;
  color: var(--wao-text-faint);
}

/* 设置弹层 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 100;
  animation: waoFade 0.15s ease;
}
.modal {
  position: fixed;
  top: 9vh;
  left: 0;
  right: 0;
  margin-inline: auto;
  width: min(540px, calc(100vw - 40px));
  max-height: 80vh;
  overflow-y: auto;
  background: var(--wao-bg-card);
  border-radius: 14px;
  z-index: 101;
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop 0.16s ease;
}
.modal-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--wao-divider);
}
.modal-icon {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
}
.modal-title {
  font-size: 15px;
  font-weight: 600;
}
.icon-btn {
  width: 30px;
  height: 30px;
  border: none;
  background: none;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
}
.icon-btn:hover {
  background: var(--wao-bg-hover-2);
}
.modal-body {
  padding: 16px 20px;
}
.f-label {
  display: block;
  font-size: 12px;
  color: var(--wao-text-weak);
  margin: 12px 0 6px;
}
.f-label:first-child {
  margin-top: 0;
}
.f-input,
.f-textarea {
  width: 100%;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  color: var(--wao-text);
  outline: none;
  background: var(--wao-bg-card);
}
.f-input:focus,
.f-textarea:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.f-input:disabled,
.f-textarea:disabled {
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
}
.f-textarea {
  resize: vertical;
}
.f-note {
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.chip {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  border-radius: 8px;
  font-size: 12.5px;
  cursor: pointer;
}
.chip.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-color: #c7d0f1;
}
.perms {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.perm {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 9px;
  padding: 10px 12px;
  cursor: pointer;
  text-align: left;
}
.perm.on {
  border-color: var(--wao-primary);
  background: var(--wao-primary-tint);
}
.perm > i {
  font-size: 16px;
  color: var(--wao-text-3);
}
.perm-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--wao-text);
}
.perm-desc {
  font-size: 11.5px;
  color: var(--wao-text-weak);
  margin-top: 1px;
}
.modal-foot {
  display: flex;
  align-items: center;
  padding: 14px 20px;
  border-top: 1px solid var(--wao-divider);
}

@media (max-width: 760px) {
  .know { padding: 16px 12px 28px; }
  .kb-discovery { align-items: stretch; flex-direction: column; gap: 8px; }
  .kb-search { min-width: 0; flex-basis: auto; }
  .kb-scopes { width: 100%; overflow-x: auto; }
  .kb-scopes button { flex: 1 0 auto; }
  .kb-result-count { padding-left: 2px; }
  .kb-grid { grid-template-columns: 1fr; }
  .kgroup-head { flex-wrap: wrap; }
  .modal { top: 12px; width: calc(100vw - 24px); max-height: calc(100dvh - 24px); }
}
</style>
