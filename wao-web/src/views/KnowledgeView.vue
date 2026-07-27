<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { copyText } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import { relativeTime } from '@/utils/time'
import { useNow } from '@/composables/now'
import DemoBanner from '@/components/ui/DemoBanner.vue'
import type { KnowledgeBase, TrashEntry } from '@/types'

const data = useDataStore()
const ui = useUiStore()
const router = useRouter()
const now = useNow()

onMounted(() => {
  void data.refreshTrash()
})

const editGroupId = ref<string | null>(null)
const cfgKb = ref<KnowledgeBase | null>(null)
const newKbOpen = ref(false)
const newKbName = ref('')
const newKbDesc = ref('')

function docCount(k: KnowledgeBase) {
  return k.folders.reduce((n, f) => n + f.docs.length, 0)
}

const groups = computed(() => {
  const projGroup = {
    id: 'proj', label: '项目知识库', fixed: true,
    hint: '跟随项目与自动化 · 默认分组，不可修改',
    items: data.kbs.filter((k) => k.project || k.auto),
  }
  const userGroups = data.myGroups.map((g) => ({
    id: g.id, label: g.name, fixed: false, hint: '',
    items: data.kbs.filter((k) => !k.project && !k.auto && (k.group || 'g1') === g.id),
  }))
  return [projGroup, ...userGroups]
})

function projName(k: KnowledgeBase) {
  if (k.project) return data.projectById(k.project)?.name ?? ''
  return k.auto ? '自动化' : ''
}
function openKb(id: string) {
  router.push(`/kb/${id}`)
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
  cfgKb.value = k
}
function cfgShare() {
  if (cfgKb.value) copyText(`https://wao.wayuwa.example/kb/${cfgKb.value.id}`, '邀请链接已复制（演示）')
}
function cfgDone() {
  if (cfgKb.value) {
    const k = cfgKb.value
    data.updateKb(k.id, { name: k.name, desc: k.desc, icon: k.icon, color: k.color, group: k.group, perm: k.perm })
  }
  cfgKb.value = null
  toast('知识库设置已保存', 'ri-checkbox-circle-line')
}
async function cfgDelete() {
  if (!cfgKb.value) return
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
}
function closeTrash() {
  trashOpen.value = false
}
async function restore(entry: TrashEntry) {
  await data.restoreTrash(entry.type, entry.id)
  toast(`已恢复「${entry.name}」`, 'ri-arrow-go-back-line')
}
async function purge(entry: TrashEntry) {
  await data.purgeTrash(entry.type, entry.id)
  toast(`已彻底删除「${entry.name}」`, 'ri-delete-bin-7-line')
}

function openNewKb() {
  newKbName.value = ''
  newKbDesc.value = ''
  newKbOpen.value = true
}
async function createKb() {
  if (!newKbName.value.trim()) return
  const kb = await data.addKb({ name: newKbName.value.trim(), desc: newKbDesc.value.trim() })
  newKbOpen.value = false
  if (kb) openKb(kb.id)
}
</script>

<template>
  <div class="know">
    <DemoBanner
      icon="ri-book-2-line"
      title="知识库"
      text="知识库、分组、文件夹与文档已接入真实后端：新建 / 重命名 / 删除均会保存，删除先进回收站、7 天后自动清理。文档打开即可编辑、停手即存，右上角会显示此刻同在这篇文档里的人。AI 语义搜索仍是演示态，将在后续迭代交付。"
      storage-key="knowledge"
    />
    <div class="ktop">
      <div class="ktitle">知识库</div>
      <span class="ktip">按知识库组织文档；项目可关联自己的知识库</span>
      <div class="spacer"></div>
      <button class="ai-btn" @click="ui.openSearch(true)"><i class="ri-magic-line"></i>AI 语义搜索</button>
      <button class="btn-ghost" @click="openTrash">
        <i class="ri-delete-bin-line"></i>回收站<span v-if="data.trash.length" class="trash-badge">{{ data.trash.length }}</span>
      </button>
      <button class="btn-ghost" @click="newGroup"><i class="ri-folder-add-line"></i>新建分组</button>
      <button class="btn-primary" @click="openNewKb"><i class="ri-add-line"></i>新建知识库</button>
    </div>

    <div v-for="g in groups" :key="g.id" class="kgroup">
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
          :style="{ borderLeftColor: k.color }"
          @click="openKb(k.id)"
        >
          <div class="kb-top">
            <div class="kb-icon" :style="{ background: k.color }"><i :class="k.icon"></i></div>
            <div class="kb-name">{{ k.name }}</div>
            <span v-if="k.auto" class="tag auto">自动</span>
            <span v-if="k.perm === '成员可编辑'" class="tag shared">已共享</span>
            <button class="gear" title="知识库设置" @click.stop="openCfg(k)"><i class="ri-settings-3-line"></i></button>
          </div>
          <div class="kb-desc">{{ k.desc }}</div>
          <div class="kb-meta">
            <i class="ri-file-text-line"></i>{{ docCount(k) }} 篇文档
            <span class="spacer"></span>
            <span v-if="k.project" class="kb-proj"><i class="ri-stack-line"></i>{{ projName(k) }}</span>
          </div>
        </div>
      </div>
      <div v-if="g.items.length === 0" class="kgroup-empty">这个分组还是空的——在知识库设置中可将个人知识库移入此分组</div>
    </div>

    <!-- 知识库设置弹层 -->
    <template v-if="cfgKb">
      <div class="modal-mask" @click="cfgKb = null"></div>
      <div class="modal">
        <div class="modal-head">
          <div class="modal-icon" :style="{ background: cfgKb.color }"><i :class="cfgKb.icon"></i></div>
          <div class="modal-title">知识库设置</div>
          <div class="spacer"></div>
          <button class="icon-btn" @click="cfgKb = null"><i class="ri-close-line"></i></button>
        </div>
        <div class="modal-body">
          <label class="f-label">名称</label>
          <input v-model="cfgKb.name" class="f-input" :disabled="!!cfgKb.project || !!cfgKb.auto" />
          <label class="f-label">简介</label>
          <textarea v-model="cfgKb.desc" class="f-textarea" rows="2" :disabled="!!cfgKb.project || !!cfgKb.auto"></textarea>
          <div v-if="cfgKb.project || cfgKb.auto" class="f-note">
            {{ cfgKb.project ? '项目 · ' + projName(cfgKb) : '自动化 · 飞书群聊沉淀' }} 的知识库跟随来源，名称与简介不可修改。
          </div>

          <template v-if="!cfgKb.project && !cfgKb.auto">
            <label class="f-label">所属分组</label>
            <div class="chips">
              <button
                v-for="grp in data.myGroups"
                :key="grp.id"
                class="chip"
                :class="{ on: (cfgKb.group || 'g1') === grp.id }"
                @click="cfgKb.group = grp.id"
              >
                {{ grp.name }}
              </button>
            </div>
          </template>

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
        </div>
        <div class="modal-foot">
          <button class="btn-ghost" @click="cfgShare"><i class="ri-links-line"></i>复制邀请链接</button>
          <button v-if="!cfgKb.project && !cfgKb.auto && !cfgKb.notes" class="btn-danger" @click="cfgDelete">
            <i class="ri-delete-bin-line"></i>删除
          </button>
          <div class="spacer"></div>
          <button class="btn-primary" @click="cfgDone">完成</button>
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
      <div class="modal-mask" @click="newKbOpen = false"></div>
      <div class="modal">
        <div class="modal-head">
          <div class="modal-title">新建知识库</div>
          <div class="spacer"></div>
          <button class="icon-btn" @click="newKbOpen = false"><i class="ri-close-line"></i></button>
        </div>
        <div class="modal-body">
          <label class="f-label">名称</label>
          <input v-model="newKbName" class="f-input" placeholder="给知识库起个名字" @keydown.enter="createKb" />
          <label class="f-label">简介</label>
          <textarea v-model="newKbDesc" class="f-textarea" rows="2" placeholder="选填"></textarea>
        </div>
        <div class="modal-foot">
          <div class="spacer"></div>
          <button class="btn-primary" :disabled="!newKbName.trim()" @click="createKb">创建</button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
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
.ai-btn {
  height: 32px;
  padding: 0 12px;
  border: 1px solid #dcd3f2;
  background: #fbfafe;
  border-radius: 8px;
  font-size: 12.5px;
  color: #7a5cc6;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.ai-btn:hover {
  background: #f7f4fd;
}
.ai-btn i {
  font-size: 14px;
}
.btn-ghost {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-ghost:hover {
  background: #f4f5f8;
}
.btn-ghost i {
  font-size: 14px;
}
.btn-danger {
  height: 32px;
  padding: 0 12px;
  border: 1px solid #f0c9c9;
  background: #fff;
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
  color: var(--wao-primary);
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
  background: #fff;
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
  background: #fff;
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
  color: var(--wao-primary);
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
.kb-proj {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--wao-primary-text);
}
.kb-proj i {
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
  background: #fff;
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
  background: #fff;
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
  background: #fff;
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
  background: #fff;
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
  background: #fff;
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
  background: #fff;
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
  .knowledge { padding: 16px 12px 28px; }
  .toolbar { gap: 7px; }
  .toolbar .search { flex: 1 1 100%; }
  .toolbar .search input { width: 100%; height: 38px; }
  .kb-grid { grid-template-columns: 1fr; }
  .kgroup-head { flex-wrap: wrap; }
  .modal { top: 12px; width: calc(100vw - 24px); max-height: calc(100dvh - 24px); }
}
</style>
