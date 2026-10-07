<script setup lang="ts">
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoCard from '@/components/ui/WaoCard.vue'
import WaoField from '@/components/ui/WaoField.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import DigitalAssetImage from '@/components/ui/DigitalAssetImage.vue'
import {
  fetchDigitalLibrary,
  createDigitalSubject,
  updateDigitalSubject,
  uploadDigitalAsset,
  deleteDigitalAsset,
  createDigitalLook,
  createDigitalVersion,
  confirmDigitalVersion,
  saveDigitalBinding,
  generateDigitalLook,
  fetchDigitalGeneration,
  type DigitalLibrary,
  type DigitalSubject,
  type DigitalAsset,
  type DigitalLook,
  type DigitalVersion,
} from '@/api/digitalMembers'
import {
  readDigitalDraft,
  rememberDigitalDraft,
  type DigitalMemberDraft,
} from '@/utils/digitalMemberDraft'
import { newPolishRequestId } from '@/api/polishJobs'

const session = useSessionStore()
const library = ref<DigitalLibrary | null>(null),
  loading = ref(true),
  loadError = ref('')
const selectedId = ref(''),
  editingNew = ref(false),
  filter = ref('all'),
  search = ref('')
const tab = ref('basic'),
  saving = ref(false),
  error = ref(''),
  status = ref(''),
  conflict = ref<DigitalSubject | null>(null)
const uploadInput = ref<HTMLInputElement>(),
  uploadRole = ref<DigitalAsset['role']>('reference')
const failedFiles = ref<Array<{ file: File; role: DigitalAsset['role']; reason: string }>>([])
const draft = ref<DigitalMemberDraft | null>(null),
  draftStorageError = ref(false)
const newVersionLook = ref<DigitalLook | null>(null),
  lookConflict = ref<DigitalLook | null>(null)
let epoch = 0,
  loadTicket = 0,
  restoring = false,
  pollTimer: ReturnType<typeof setTimeout> | undefined
const subject = computed(
  () => library.value?.subjects.find((item) => item.id === selectedId.value) ?? null,
)
const draftId = computed(() => (editingNew.value ? 'new' : selectedId.value))
const filtered = computed(() =>
  (library.value?.subjects ?? []).filter(
    (item) =>
      (filter.value === 'archived' ? item.state === 'archived' : item.state === 'active') &&
      (filter.value === 'all' ||
        filter.value === 'archived' ||
        (filter.value === 'pet' && item.kind === 'pet') ||
        (filter.value === 'self' && item.id === library.value?.binding.subjectId) ||
        (filter.value === 'family' &&
          item.kind === 'human' &&
          item.id !== library.value?.binding.subjectId)) &&
      item.name.toLowerCase().includes(search.value.toLowerCase()),
  ),
)
const dirty = computed(
  () =>
    !!draft.value &&
    (editingNew.value ||
      JSON.stringify(draft.value.form) !== JSON.stringify(subject.value && formOf(subject.value))),
)
const canDefault = computed(
  () => subject.value?.id === library.value?.binding.subjectId && subject.value?.kind === 'human',
)
const tabs = [
  { id: 'basic', name: '基本信息' },
  { id: 'photos', name: '参考照片' },
  { id: 'looks', name: '形象版本' },
  { id: 'history', name: '使用记录' },
]
const formOf = (item: DigitalSubject) => ({
  name: item.name,
  kind: item.kind,
  relation: item.relation,
  gender: item.gender,
  traits: item.traits,
})
const blank = (): DigitalMemberDraft => ({
  form: { name: '', kind: 'human', relation: '', gender: 'unknown', traits: '' },
  expectedRevision: 0,
  look: { name: '', style: '', assetIds: [], portraitAssetId: null },
})
const message = (cause: unknown) => (cause instanceof Error ? cause.message : '操作失败，请重试')
function historyStatus(value: string) {
  const labels: Record<string, string> = {
    QUEUED: '等待生成',
    RUNNING: '生成中',
    SUCCEEDED: '已生成',
    FAILED: '生成失败',
  }
  return labels[value] ?? value
}
function storeDraft() {
  if (restoring || !draftId.value || !draft.value) return
  draftStorageError.value = !rememberDigitalDraft(session.userId, draftId.value, draft.value)
}
watch(draft, storeDraft, { deep: true, flush: 'sync' })
function open(item?: DigitalSubject) {
  if (pollTimer) clearTimeout(pollTimer)
  lookConflict.value = null
  restoring = true
  editingNew.value = !item
  selectedId.value = item?.id ?? ''
  tab.value = 'basic'
  error.value = ''
  status.value = ''
  conflict.value = null
  failedFiles.value = []
  newVersionLook.value = null
  draft.value =
    readDigitalDraft(session.userId, item?.id ?? 'new') ??
    (item ? { ...blank(), form: formOf(item), expectedRevision: item.revision } : blank())
  if (item && draft.value.expectedRevision !== item.revision) conflict.value = item
  if (item && draft.value.look.lookId) {
    const look = item.looks.find((value) => value.id === draft.value!.look.lookId)
    if (look) {
      newVersionLook.value = {
        ...look,
        revision: draft.value.look.expectedRevision ?? look.revision,
      }
      if (newVersionLook.value.revision !== look.revision) lookConflict.value = look
    } else error.value = '原形象已不可用，候选草稿仍保留，请取消后重新创建'
  }
  restoring = false
}
function back() {
  storeDraft()
  selectedId.value = ''
  editingNew.value = false
  draft.value = null
  error.value = ''
  conflict.value = null
}
async function load() {
  const ticket = ++loadTicket,
    current = epoch
  loading.value = true
  loadError.value = ''
  try {
    const value = await fetchDigitalLibrary()
    if (current !== epoch || ticket !== loadTicket) return
    library.value = value
    if (subject.value && draft.value && draft.value.expectedRevision !== subject.value.revision)
      conflict.value = subject.value
  } catch (cause) {
    if (current === epoch && ticket === loadTicket) loadError.value = message(cause)
  } finally {
    if (current === epoch && ticket === loadTicket) loading.value = false
  }
}
function replace(item: DigitalSubject) {
  if (!library.value) return
  library.value.subjects = [...library.value.subjects.filter((old) => old.id !== item.id), item]
}
async function act(operation: () => Promise<void>) {
  if (saving.value || loading.value || loadError.value) return
  const current = epoch
  saving.value = true
  error.value = ''
  status.value = ''
  try {
    await operation()
    if (current === epoch) status.value = '已保存'
  } catch (cause) {
    if (current !== epoch) return
    error.value = message(cause)
    if ((cause as { status?: number })?.status === 409) {
      await load()
      if (newVersionLook.value)
        lookConflict.value =
          subject.value?.looks.find((look) => look.id === newVersionLook.value!.id) ?? null
    }
  } finally {
    if (current === epoch) saving.value = false
  }
}
async function saveBasic() {
  if (!draft.value || conflict.value) return
  if (!draft.value.form.name.trim()) {
    error.value = '请填写成员名称'
    return
  }
  const input = { ...draft.value.form, name: draft.value.form.name.trim() },
    current = epoch,
    id = selectedId.value,
    revision = draft.value.expectedRevision
  await act(async () => {
    const value = editingNew.value
      ? await createDigitalSubject(input)
      : await updateDigitalSubject(id, { ...input, expectedRevision: revision })
    if (current !== epoch) return
    rememberDigitalDraft(session.userId, draftId.value, null)
    replace(value)
    restoring = true
    editingNew.value = false
    selectedId.value = value.id
    draft.value = { ...draft.value!, form: formOf(value), expectedRevision: value.revision }
    restoring = false
    storeDraft()
  })
}
function resolveConflict(keep: boolean) {
  if (!conflict.value || !draft.value) return
  if (!keep) draft.value.form = formOf(conflict.value)
  draft.value.expectedRevision = conflict.value.revision
  conflict.value = null
  error.value = ''
  storeDraft()
  status.value = keep ? '已保留草稿，请再次保存' : '已使用账号资料'
}
async function patchSubject(patch: { mainAssetId?: string | null; state?: 'active' | 'archived' }) {
  if (!subject.value || !draft.value || conflict.value) return
  const item = subject.value,
    current = epoch
  await act(async () => {
    const value = await updateDigitalSubject(item.id, {
      ...formOf(item),
      expectedRevision: item.revision,
      ...patch,
    })
    if (current !== epoch) return
    replace(value)
    draft.value!.expectedRevision = value.revision
    if (patch.state === 'archived') {
      await load()
      back()
    }
  })
}
async function upload(files: File[], role = uploadRole.value) {
  const id = selectedId.value,
    current = epoch
  await act(async () => {
    for (const file of files) {
      try {
        const value = await uploadDigitalAsset(id, file, role)
        if (current !== epoch) return
        subject.value!.assets = [
          ...subject.value!.assets.filter((asset) => asset.id !== value.id),
          value,
        ]
        failedFiles.value = failedFiles.value.filter((item) => item.file !== file)
      } catch (cause) {
        if (current === epoch)
          failedFiles.value = [
            ...failedFiles.value.filter((item) => item.file !== file),
            { file, role, reason: message(cause) },
          ]
      }
    }
    if (failedFiles.value.length)
      throw new Error('部分照片上传失败，文件仍保留在本页，请重试；刷新后需重新选择文件。')
  })
}
function chooseFiles(event: Event) {
  const input = event.target as HTMLInputElement
  void upload(Array.from(input.files ?? []))
  input.value = ''
}
async function removeAsset(asset: DigitalAsset) {
  if (!window.confirm(`移除“${asset.name}”？已被形象引用的照片需先解除引用。`)) return
  const current = epoch
  await act(async () => {
    await deleteDigitalAsset(asset.id)
    if (current !== epoch) return
    subject.value!.assets = subject.value!.assets.filter((item) => item.id !== asset.id)
    draft.value!.look.assetIds = draft.value!.look.assetIds.filter((id) => id !== asset.id)
    if (draft.value!.look.portraitAssetId === asset.id) draft.value!.look.portraitAssetId = null
    if (subject.value!.mainAssetId === asset.id) subject.value!.mainAssetId = null
  })
}
function chooseReference(id: string) {
  if (!draft.value) return
  const ids = draft.value.look.assetIds
  draft.value.look.assetIds = ids.includes(id) ? ids.filter((value) => value !== id) : [...ids, id]
}
async function createLook() {
  if (!draft.value || !subject.value || lookConflict.value) return
  if (draft.value.look.lookId && !newVersionLook.value) {
    error.value = '原形象已不可用，候选草稿仍保留，请取消后重新创建'
    return
  }
  if (!draft.value.look.name.trim() || (!draft.value.look.assetIds.length && !draft.value.look.portraitAssetId)) {
    error.value = '请填写形象名称，并选择参考照片或候选封面'
    return
  }
  const input = JSON.parse(JSON.stringify(draft.value.look)) as DigitalMemberDraft['look'],
    item = subject.value,
    current = epoch,
    previous = newVersionLook.value
  if (!input.assetIds.length && input.portraitAssetId) input.assetIds = [input.portraitAssetId]
  await act(async () => {
    const value = previous
      ? await createDigitalVersion(previous.id, {
          assetIds: input.assetIds,
          portraitAssetId: input.portraitAssetId,
          expectedRevision: previous.revision,
        })
      : await createDigitalLook(item.id, {
          name: input.name,
          style: input.style,
          assetIds: input.assetIds,
          portraitAssetId: input.portraitAssetId,
        })
    if (current !== epoch) return
    item.looks = [...item.looks.filter((look) => look.id !== value.id), value]
    draft.value!.look = blank().look
    newVersionLook.value = null
    storeDraft()
  })
}
function prepareVersion(look: DigitalLook) {
  if (!draft.value) return
  newVersionLook.value = look
  draft.value.look = {
    name: look.name,
    style: look.style,
    assetIds: [...(look.versions[look.versions.length - 1]?.assetIds ?? [])],
    portraitAssetId: look.versions[look.versions.length - 1]?.portraitAssetId ?? null,
    lookId: look.id,
    expectedRevision: look.revision,
  }
}
function resolveLookConflict() {
  newVersionLook.value = lookConflict.value
  if (draft.value && lookConflict.value)
    draft.value.look.expectedRevision = lookConflict.value.revision
  lookConflict.value = null
  error.value = ''
}
function cancelLookDraft() {
  lookConflict.value = null
  newVersionLook.value = null
  if (draft.value) draft.value.look = blank().look
}
async function confirm(version: DigitalVersion) {
  const current = epoch
  await act(async () => {
    const value = await confirmDigitalVersion(version.id)
    if (current !== epoch) return
    replace(value)
    if (draft.value) draft.value.expectedRevision = value.revision
  })
}
async function bind(versionId: string | null = null) {
  const current = epoch,
    item = subject.value,
    binding = library.value?.binding
  if (!item || !binding || item.kind !== 'human') return
  await act(async () => {
    const value = await saveDigitalBinding({
      subjectId: item.id,
      versionId,
      expectedRevision: binding.revision,
    })
    if (current === epoch) library.value!.binding = value
  })
}
async function pollGeneration(id: string, runId: string, current: number) {
  if (current !== epoch || draft.value?.generation?.runId !== runId) return
  try {
    const result = await fetchDigitalGeneration(id, runId)
    if (current !== epoch || draft.value?.generation?.runId !== runId) return
    status.value = result.message || '正在生成形象…'
    if (result.status === 'FAILED') {
      error.value = result.message || '生成失败，请重试'
      draft.value!.generation = undefined
      return
    }
    if (result.status === 'SUCCEEDED') {
      draft.value!.generation = undefined
      await load()
      status.value = '候选形象已生成，请查看并确认'
      return
    }
    pollTimer = setTimeout(() => void pollGeneration(id, runId, current), 2500)
  } catch (cause) {
    if (current === epoch) error.value = `${message(cause)}；保留原任务，可继续查询`
  }
}
async function generateLook() {
  if (!draft.value || !subject.value || !library.value?.capability.referenceImage) return
  if (!draft.value.look.name.trim() || !draft.value.look.assetIds.length) {
    error.value = '请填写形象名称，并选择参考照片'
    return
  }
  const id = subject.value.id,
    current = epoch
  if (draft.value.generation?.runId) {
    void pollGeneration(id, draft.value.generation.runId, current)
    return
  }
  draft.value.generation ??= { requestId: newPolishRequestId() }
  const input = {
    name: draft.value.look.name,
    style: draft.value.look.style,
    assetIds: [...draft.value.look.assetIds],
    requestId: draft.value.generation.requestId,
  }
  await act(async () => {
    const result = await generateDigitalLook(id, input)
    if (current !== epoch) return
    draft.value!.generation!.runId = result.runId
    void pollGeneration(id, result.runId, current)
  })
}
watch(
  () => session.userId,
  () => {
    epoch++
    loadTicket++
    if (pollTimer) clearTimeout(pollTimer)
    restoring = true
    library.value = null
    draft.value = null
    selectedId.value = ''
    editingNew.value = false
    saving.value = false
    error.value = ''
    conflict.value = null
    restoring = false
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  storeDraft()
  epoch++
  if (pollTimer) clearTimeout(pollTimer)
})
</script>

<template>
  <main class="digital-page wao-page-frame">
    <header class="heading">
      <div>
        <span class="eyebrow">让熟悉的形象，陪你创作</span>
        <h1>数字成员图库</h1>
        <p>把本人、家人与宠物的照片和形象留在一起，下次小记直接使用。</p>
      </div>
      <WaoButton
        v-if="!draft"
        class="primary"
        variant="primary"
        :disabled="loading || !!loadError"
        @click="open()"
      >
        <i class="ri-add-line"></i>新建成员
      </WaoButton>
    </header>
    <p v-if="loading" class="empty" role="status">正在读取成员图库…</p>
    <div v-if="loadError" class="error state" role="alert">
      {{ loadError }} <WaoButton @click="load">重试读取</WaoButton>
    </div>
    <template v-if="library && !loading">
      <template v-if="!draft">
        <div class="filters">
          <WaoButton
            v-for="item in [
              { id: 'all', name: '全部成员' },
              { id: 'self', name: '本人' },
              { id: 'family', name: '家人' },
              { id: 'pet', name: '宠物' },
              { id: 'archived', name: '已归档' },
            ]"
            :key="item.id"
            :aria-pressed="filter === item.id"
            @click="filter = item.id"
          >
            {{ item.name }}</WaoButton
          ><input v-model="search" type="search" aria-label="查找成员" placeholder="按名称查找" />
        </div>
        <WaoCard v-if="!filtered.length" class="empty panel">
          <i class="ri-group-line"></i>
          <h2>
            {{
              library.subjects.some((item) => item.state === 'active')
                ? '没有找到匹配的成员'
                : '还没有数字成员'
            }}
          </h2>
          <p>从一个熟悉的名字开始，再上传照片。</p>
          <WaoButton class="primary" variant="primary" @click="open()">新建成员</WaoButton>
        </WaoCard>
        <section v-else class="members" aria-label="成员图库">
          <WaoButton v-for="item in filtered" :key="item.id" class="member" @click="open(item)">
            <span class="cover"
              ><DigitalAssetImage
                :asset-id="
                  item.assets.find((asset) => asset.id === item.mainAssetId)?.id ||
                  item.assets[0]?.id
                "
                :alt="item.name"
                :retryable="false" /></span
            ><span class="meta"
              ><strong>{{ item.name }}</strong
              ><span class="badge">{{
                item.id === library.binding.subjectId
                  ? '本人'
                  : item.kind === 'pet'
                    ? '宠物'
                    : item.relation || '家人'
              }}</span
              ><span class="muted"
                >{{ item.assets.length }} 张素材 ·
                {{
                  item.looks.reduce(
                    (sum, look) =>
                      sum + look.versions.filter((version) => version.state === 'confirmed').length,
                    0,
                  )
                }}
                个已确认版本</span
              ></span
            >
          </WaoButton>
        </section>
        <div class="capability">
          <i class="ri-shield-check-line"></i>原照片与形象素材仅当前账号可读。<span>{{
            library.capability.message
          }}</span>
        </div>
      </template>
      <template v-else>
        <WaoButton class="back" :disabled="saving" @click="back">
          <i class="ri-arrow-left-line"></i>返回图库
        </WaoButton>
        <WaoCard class="panel detail">
          <div class="detail-head">
            <span class="avatar"
              ><DigitalAssetImage
                :asset-id="subject?.mainAssetId || subject?.assets[0]?.id"
                :alt="draft.form.name || '新成员'"
            /></span>
            <div>
              <h2>{{ editingNew ? '新建数字成员' : subject?.name }}</h2>
              <p>
                {{
                  subject?.id === library.binding.subjectId
                    ? '已绑定本人 · 可设置小记默认形象'
                    : editingNew
                      ? '先建档，再上传参考照片'
                      : '照片、形象与使用记录独立保存'
                }}
              </p>
            </div>
            <WaoButton
              v-if="subject?.kind === 'human' && subject.state === 'active' && !canDefault"
              :disabled="saving"
              @click="bind()"
            >
              这是本人
            </WaoButton>
          </div>
          <nav v-if="!editingNew" class="tabs" aria-label="成员详情">
            <WaoButton
              v-for="item in tabs"
              :key="item.id"
              :aria-pressed="tab === item.id"
              @click="tab = item.id"
            >
              {{ item.name }}
            </WaoButton>
          </nav>
          <p v-if="subject?.state === 'archived'" class="muted">已归档，素材和形象可继续查看；恢复后可修改或使用。</p>
          <div v-if="conflict" class="conflict" role="alert">
            <strong>账号资料已更新，草稿仍保留</strong>
            <p>
              账号：{{ conflict.name }} ·
              {{ { male: '男性', female: '女性', unknown: '未知' }[conflict.gender] }} ·
              {{ conflict.relation || '未填写关系' }}
            </p>
            <p>账号特征：{{ conflict.traits || '未填写' }}</p>
            <p>
              本机：{{ draft.form.name }} ·
              {{ { male: '男性', female: '女性', unknown: '未知' }[draft.form.gender] }} ·
              {{ draft.form.relation || '未填写关系' }}
            </p>
            <p>本机特征：{{ draft.form.traits || '未填写' }}</p>
            <div class="actions">
              <WaoButton @click="resolveConflict(true)">保留草稿，基于最新版本</WaoButton
              ><WaoButton @click="resolveConflict(false)">使用账号资料</WaoButton>
            </div>
          </div>
          <div v-if="lookConflict" class="conflict" role="alert">
            形象已有更新，所选素材仍保留。<WaoButton @click="resolveLookConflict">
              基于最新形象版本重新保存</WaoButton
            ><WaoButton @click="cancelLookDraft"> 取消此候选草稿 </WaoButton>
          </div>
          <fieldset :disabled="saving || !!draft.generation?.runId || (subject?.state === 'archived' && tab !== 'basic')">
            <form v-if="tab === 'basic'" class="form" @submit.prevent="saveBasic">
              <WaoField v-slot="field" label="成员名称" required
                ><input :id="field.id" v-model="draft.form.name" maxlength="64" required
              /></WaoField>
              <WaoField v-if="editingNew" v-slot="field" label="成员类型"
                ><select :id="field.id" v-model="draft.form.kind">
                  <option value="human">人</option>
                  <option value="pet">宠物</option>
                </select></WaoField
              >
              <WaoField v-slot="field" label="与我的关系"
                ><input
                  :id="field.id"
                  v-model="draft.form.relation"
                  maxlength="64"
                  placeholder="本人、家人，或宠物的称呼"
              /></WaoField>
              <WaoField v-slot="field" label="已确认的性别"
                ><select :id="field.id" v-model="draft.form.gender">
                  <option value="unknown">未知 / 暂不填写</option>
                  <option value="male">男性 / 雄性</option>
                  <option value="female">女性 / 雌性</option>
                </select></WaoField
              >
              <WaoField v-slot="field" class="full" label="确认特征">
                <textarea
                  :id="field.id"
                  v-model="draft.form.traits"
                  maxlength="4000"
                  rows="4"
                  placeholder="只填写你确认过的外观特征；不填也可以建档。"
                ></textarea>
              </WaoField>
              <p class="muted full">此档案独立保存，不推测其他家人或宠物的性别。</p>
            </form>
            <section v-else-if="tab === 'photos' && subject">
              <WaoCard class="upload" variant="soft">
                <i class="ri-image-add-line"></i>
                <h3>留住真实的参考照片</h3>
                <p>建议清晰、光线自然；形象图片可作为候选版本的封面。</p>
                <div class="actions">
                  <select v-model="uploadRole" aria-label="上传照片用途">
                    <option value="reference">参考照片</option>
                    <option value="portrait">形象图片</option></select
                  ><WaoButton class="primary" variant="primary" @click="uploadInput?.click()"
                    >上传照片</WaoButton
                  >
                </div>
                <input
                  ref="uploadInput"
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  multiple
                  hidden
                  @change="chooseFiles"
                />
              </WaoCard>
              <div v-for="(item, index) in failedFiles" :key="index" class="error state">
                {{ item.file.name }}：{{ item.reason }}
                <WaoButton @click="upload([item.file], item.role)">重试上传</WaoButton>
              </div>
              <div v-if="!subject.assets.length" class="empty">
                还没有照片。上传后可选择主参考和创建形象。
              </div>
              <div v-else class="assets">
                <article v-for="asset in subject.assets" :key="asset.id" class="asset">
                  <span class="photo"
                    ><DigitalAssetImage :asset-id="asset.id" :alt="asset.name" /></span
                  ><strong>{{ asset.name }}</strong
                  ><span class="muted"
                    >{{ asset.role === 'reference' ? '参考照片' : '形象图片' }} ·
                    {{ asset.width }} × {{ asset.height }}</span
                  >
                  <div class="actions">
                    <WaoButton
                      v-if="asset.role === 'reference'"
                      :disabled="!!conflict"
                      @click="patchSubject({ mainAssetId: asset.id })"
                    >
                      {{ subject.mainAssetId === asset.id ? '主参考' : '设为主参考' }}</WaoButton
                    ><WaoButton @click="removeAsset(asset)">移除</WaoButton>
                  </div>
                </article>
              </div>
            </section>
            <section v-else-if="tab === 'looks' && subject">
              <div class="look-form">
                <h3>
                  {{ newVersionLook ? `为 ${newVersionLook.name} 创建新版本` : '创建一套形象' }}
                </h3>
                <p class="muted">候选只引用所选素材；上传成图或生成后，查看并确认才可用于小记。</p>
                <div class="form">
                  <WaoField v-slot="field" label="形象名称"
                    ><input
                      :id="field.id"
                      v-model="draft.look.name"
                      maxlength="64"
                      :disabled="!!newVersionLook"
                  /></WaoField>
                  <WaoField v-slot="field" label="画风与要求"
                    ><input
                      :id="field.id"
                      v-model="draft.look.style"
                      maxlength="2000"
                      :disabled="!!newVersionLook"
                      placeholder="自然写实、温暖插画…"
                  /></WaoField>
                  <WaoField v-slot="field" class="full" label="候选封面"
                    ><select :id="field.id" v-model="draft.look.portraitAssetId">
                      <option :value="null">沿用所选参考照片</option>
                      <option
                        v-for="asset in subject.assets.filter((item) => item.role === 'portrait')"
                        :key="asset.id"
                        :value="asset.id"
                      >
                        {{ asset.name }}
                      </option>
                    </select></WaoField
                  >
                </div>
                <div
                  v-if="!subject.assets.some((asset) => asset.role === 'reference')"
                  class="empty"
                >
                  可选择已上传的形象图片作为候选封面，也可上传参考照片。<WaoButton @click="tab = 'photos'">去上传</WaoButton>
                </div>
                <div v-else class="references">
                  <WaoButton
                    v-for="asset in subject.assets.filter((item) => item.role === 'reference')"
                    :key="asset.id"
                    :aria-pressed="draft.look.assetIds.includes(asset.id)"
                    @click="chooseReference(asset.id)"
                  >
                    <span class="small-photo"
                      ><DigitalAssetImage
                        :asset-id="asset.id"
                        :alt="asset.name"
                        :retryable="false" /></span
                    >{{ asset.name
                    }}<i v-if="draft.look.assetIds.includes(asset.id)" class="ri-check-line"></i>
                  </WaoButton>
                </div>
                <div class="actions">
                  <WaoButton class="primary" variant="primary" @click="createLook"
                    >保存候选版本</WaoButton
                  ><WaoButton
                    v-if="library.capability.referenceImage && !newVersionLook"
                    @click="generateLook"
                  >
                    {{ draft.generation ? '继续原任务' : '根据参考照片生成' }}</WaoButton
                  ><WaoButton v-if="newVersionLook || draft.look.lookId" @click="cancelLookDraft">
                    取消新版本
                  </WaoButton>
                </div>
                <p class="muted">{{ library.capability.message }}</p>
              </div>
              <div v-if="!subject.looks.length" class="empty">
                还没有形象版本。已确认版本会保留，下次修改创建新版本。
              </div>
              <div v-else class="looks">
                <WaoCard v-for="look in subject.looks" :key="look.id" class="look" role="article">
                  <header>
                    <h3>{{ look.name }}</h3>
                    <WaoButton @click="prepareVersion(look)">新版本</WaoButton>
                  </header>
                  <p class="muted">{{ look.style || '未指定画风' }}</p>
                  <div
                    v-for="version in [...look.versions].reverse()"
                    :key="version.id"
                    class="version"
                  >
                    <span class="photo"
                      ><DigitalAssetImage
                        :asset-id="version.portraitAssetId || version.assetIds[0]"
                        :alt="`${look.name} 第 ${version.number} 版`"
                    /></span>
                    <p>
                      第 {{ version.number }} 版
                      <span class="badge">{{
                        version.state === 'confirmed' ? '已确认' : '候选 · 待确认'
                      }}</span>
                    </p>
                    <div class="actions">
                      <WaoButton v-if="version.state === 'candidate'" @click="confirm(version)">
                        确认此形象</WaoButton
                      ><WaoButton v-else-if="canDefault" @click="bind(version.id)">
                        {{
                          library.binding.versionId === version.id ? '小记默认形象' : '设为小记默认'
                        }}
                      </WaoButton>
                    </div>
                  </div>
                </WaoCard>
              </div>
            </section>
            <section v-else-if="tab === 'history' && subject">
              <div v-if="!subject.history.length" class="empty">
                还没有使用记录。小记配图使用此成员后，会在这里留下记录。
              </div>
              <article v-for="use in subject.history" :key="use.runId" class="history">
                <strong>{{ use.purpose === 'DIGITAL_IMAGE' ? '形象创建' : '小记配图' }}</strong
                ><span>{{ historyStatus(use.status) }}</span
                ><time>{{ use.createdAt }}</time
                ><span class="muted"
                  >版本
                  {{
                    subject.looks
                      .flatMap((look) => look.versions)
                      .find((version) => version.id === use.versionId)?.number ?? '已留存'
                  }}</span
                >
              </article>
            </section>
          </fieldset>
          <footer class="savebar">
            <div>
              <span v-if="error" class="error" role="alert">{{ error }}</span
              ><span v-else role="status">{{
                saving
                  ? '正在保存…'
                  : status || (dirty ? '草稿已留在本机，保存后生效' : '已读取账号资料')
              }}</span
              ><span v-if="draftStorageError" class="error"
                >浏览器无法保存本机草稿，请保持此页打开并保存。</span
              ><span v-if="draft.generation?.runId" role="status"
                >形象生成任务已保存 <WaoButton @click="generateLook">继续查询</WaoButton></span
              >
            </div>
            <div class="actions">
              <WaoButton
                v-if="subject && tab === 'basic'"
                :disabled="saving || !!conflict"
                @click="
                  patchSubject({ state: subject.state === 'archived' ? 'active' : 'archived' })
                "
              >
                {{ subject.state === 'archived' ? '恢复成员' : '归档' }}</WaoButton
              ><WaoButton
                v-if="tab === 'basic'"
                class="primary"
                variant="primary"
                :disabled="saving || !!conflict || !dirty"
                @click="saveBasic"
              >
                {{ saving ? '保存中…' : editingNew ? '创建成员' : '保存资料' }}
              </WaoButton>
            </div>
          </footer>
        </WaoCard>
      </template>
    </template>
  </main>
</template>

<style scoped>
.digital-page {
  max-width: 1120px;
  padding: 28px 30px 90px;
  color: var(--wao-text);
  background: var(--wao-bg-page);
  min-height: 100%;
  box-sizing: border-box;
  font-size: var(--wao-font-body);
}
.digital-page * {
  box-sizing: border-box;
}
h1 {
  font-size: var(--wao-font-title);
  font-weight: 500;
  margin: 6px 0 8px;
}
h2 {
  overflow-wrap: anywhere;
  font-size: 19px;
  margin: 0;
}
h3 {
  overflow-wrap: anywhere;
  min-width: 0;
  font-size: 15px;
  margin: 0;
}
p {
  margin: 8px 0;
  color: var(--wao-text-3);
  line-height: 1.6;
}
.eyebrow {
  color: var(--wao-primary-text);
  font-size: 12px;
}
.heading,
.detail-head {
  display: flex;
  align-items: center;
  gap: 17px;
  justify-content: space-between;
  flex-wrap: wrap;
  margin-bottom: var(--wao-space-3);
}
.heading > div {
  flex: 1;
  min-width: 200px;
}
button,
input,
select,
textarea {
  font: inherit;
  min-width: 0;
}
button {
  min-height: 40px;
  padding: 8px 13px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  border-radius: var(--wao-radius-control);
  cursor: pointer;
}
button:hover {
  border-color: var(--wao-primary);
}
button.primary {
  background: var(--wao-primary);
  color: var(--wao-on-primary);
  border-color: var(--wao-primary);
}
button:disabled {
  opacity: 0.55;
  cursor: default;
}
button[aria-pressed='true'] {
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
  border-color: var(--wao-primary);
}
.filters,
.actions {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  flex-wrap: wrap;
}
.filters {
  margin: 20px 0;
}
.filters input {
  margin-left: auto;
  max-width: 240px;
}
.members {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--wao-space-2);
}
.member {
  padding: 0;
  text-align: left;
  overflow: hidden;
  border-radius: var(--wao-radius-card);
  display: flex;
  flex-direction: column;
  align-items: stretch;
  justify-content: flex-start;
  gap: 0;
}
.cover {
  height: 160px;
  flex-shrink: 0;
  display: block;
  background: linear-gradient(140deg, var(--wao-primary-tint), var(--wao-bg-sub));
}
.meta {
  display: grid;
  padding: 14px;
  gap: 9px;
}
.meta strong {
  font-size: 16px;
  overflow-wrap: anywhere;
}
.badge {
  display: inline-block;
  border-radius: var(--wao-radius-pill);
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  padding: 3px 9px;
  font-size: 12px;
  width: max-content;
  max-width: 100%;
  overflow-wrap: anywhere;
}
.muted {
  color: var(--wao-text-3);
  font-size: 12px;
}
.capability {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  color: var(--wao-text-3);
  font-size: 12px;
  margin-top: 24px;
}
.panel {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  padding: 23px;
}
.back {
  border: 0;
  background: transparent;
  margin: 0 0 16px;
  padding-left: 0;
  color: var(--wao-text-3);
}
.detail-head > div {
  flex: 1;
  min-width: 150px;
}
.avatar {
  width: 80px;
  height: 80px;
  display: block;
  border-radius: var(--wao-radius-card);
  overflow: hidden;
}
.tabs {
  display: flex;
  gap: 7px;
  flex-wrap: wrap;
  padding-bottom: 16px;
  margin: 18px 0;
  border-bottom: 1px solid var(--wao-line);
}
.tabs button {
  border-color: transparent;
}
.form {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 17px;
  margin: 20px 0;
}
.form label {
  display: grid;
  gap: 7px;
}
.form .full {
  grid-column: 1/-1;
}
input,
select,
textarea {
  width: 100%;
  padding: 10px 11px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-sub);
  color: var(--wao-text);
  min-height: 40px;
}
textarea {
  resize: vertical;
}
.empty {
  text-align: center;
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 34px 16px;
  color: var(--wao-text-3);
}
.empty > i {
  font-size: 30px;
  color: var(--wao-primary-text);
}
.state,
.conflict {
  padding: 13px;
  border-radius: var(--wao-radius-control);
  background: var(--wao-danger-bg);
  margin: 12px 0;
}
.error {
  color: var(--wao-danger-strong);
}
.conflict {
  border: 1px solid var(--wao-danger-strong);
  overflow-wrap: anywhere;
}
.upload {
  padding: 24px;
  background: var(--wao-bg-sub);
  border: 1px dashed var(--wao-line-control);
  border-radius: var(--wao-radius-card);
  text-align: center;
  display: grid;
  justify-items: center;
  gap: var(--wao-space-1);
}
.upload > i {
  font-size: 28px;
  color: var(--wao-primary-text);
}
.upload select {
  width: auto;
}
.assets,
.looks {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 15px;
  margin-top: 20px;
}
.asset {
  display: grid;
  gap: 9px;
  min-width: 0;
}
.asset strong {
  overflow-wrap: anywhere;
}
.photo {
  display: block;
  height: 160px;
  border-radius: var(--wao-radius-control);
  overflow: hidden;
}
.look {
  padding: 15px;
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  min-width: 0;
}
.look header {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  justify-content: space-between;
}
.version {
  border-top: 1px solid var(--wao-line);
  padding: 13px 0;
}
.look-form {
  padding: 17px;
  border-radius: var(--wao-radius-card);
  background: var(--wao-bg-sub);
}
.references {
  display: flex;
  gap: 9px;
  overflow-x: auto;
  padding: 5px 0 14px;
}
.references button {
  display: grid;
  gap: 6px;
  min-width: 100px;
  max-width: 140px;
  overflow-wrap: anywhere;
}
.small-photo {
  display: block;
  width: 90px;
  height: 70px;
}
.history {
  display: flex;
  gap: var(--wao-space-2);
  flex-wrap: wrap;
  padding: 15px 0;
  border-bottom: 1px solid var(--wao-line);
}
.savebar {
  position: sticky;
  bottom: 0;
  margin: 25px -23px -23px;
  padding: 15px 23px;
  background: var(--wao-bg-card);
  border-top: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  border-top-left-radius: 0;
  border-top-right-radius: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
}
.savebar > div:first-child {
  display: grid;
  gap: 5px;
  overflow-wrap: anywhere;
}
.savebar .actions {
  flex-shrink: 0;
}
fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.detail-head p {
  font-size: 12px;
}
button:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 3px;
}
@media (max-width: 950px) {
  .members {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .assets,
  .looks {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 650px) {
  .digital-page {
    padding: 20px 14px 100px;
  }
  .members {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .cover {
    height: 140px;
  }
  .meta {
    padding: 11px;
  }
  .panel {
    padding: 16px;
  }
  .form {
    grid-template-columns: 1fr;
  }
  .assets,
  .looks {
    grid-template-columns: 1fr 1fr;
    gap: 11px;
  }
  .looks {
    grid-template-columns: 1fr;
  }
  .savebar {
    margin: 20px -16px -16px;
    padding: 13px 16px;
    flex-wrap: wrap;
    bottom: 0;
  }
  .savebar .actions {
    margin-left: auto;
  }
  .filters input {
    max-width: none;
    flex-basis: 100%;
    margin-top: 5px;
  }
  .digital-page :deep(.wao-button),
  .tabs button {
    min-height: 44px;
  }
  .form input,
  .form select,
  .form textarea {
    font-size: 16px;
  }
  .detail-head {
    gap: 12px;
  }
  .avatar {
    width: 62px;
    height: 62px;
  }
  .photo {
    height: 140px;
  }
  .asset .actions button {
    width: 100%;
  }
  .heading h1 {
    font-size: 24px;
  }
}
</style>
