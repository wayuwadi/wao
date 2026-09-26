<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import {
  createPersonalPrinciple,
  fetchPersonalPrinciples,
  fetchPrincipleRevisions,
  savePrincipleCheckin,
  setPrincipleConsent,
  updatePersonalPrinciple,
  type PersonalPrinciple,
  type PrincipleCategory,
  type SavePersonalPrinciple,
} from '@/api/personalPrinciples'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const shanghaiDate = () =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
const today = ref(shanghaiDate())
let clockTimer: ReturnType<typeof setInterval> | undefined
const categories: Array<{ id: PrincipleCategory; name: string }> = [
  { id: 'RED_LINE', name: '红线' },
  { id: 'MUST', name: '必做' },
  { id: 'MORE', name: '多做' },
  { id: 'LESS', name: '少做' },
  { id: 'ALLOWED', name: '可以做' },
  { id: 'AVOID', name: '暂不做' },
  { id: 'ADVICE', name: '建议' },
]
const statusText: Record<string, string> = {
  ADVICE: '仅供参考',
  PAUSED: '已暂停',
  MANAGED_ONLY: '由我发起',
  PENDING_CONSENT: '待本人确认',
  WAITING_DATA: '待数据',
  COMPLETED: '已记录完成',
  EXCEPTION: '已记例外',
  NEEDS_ATTENTION: '本人反馈待调整',
  RECORD_DUE: '待本人记录',
  PENDING: '待判断',
}
const items = ref<PersonalPrinciple[]>([])
const loading = ref(true)
const loadError = ref('')
let loadVersion = 0
const actionError = ref('')
const savedMessage = ref('')
const busy = ref(false)
const filter = ref<'ALL' | 'ATTENTION' | 'ADVICE'>('ALL')
const selectedId = ref('')
const editorOpen = ref(false)
const editingId = ref('')
const revisions = ref<string[]>([])
const revisionsOpen = ref(false)
const revisionError = ref('')
const draftState = ref<'idle' | 'saved' | 'failed'>('idle')
let restoring = false
const form = reactive<SavePersonalPrinciple>(blankForm())
const selected = computed(() => items.value.find((item) => item.id === selectedId.value) ?? null)
function selectRule(id: string) { selectedId.value = id; actionError.value = '' }
const visible = computed(() =>
  items.value.filter(
    (item) =>
      filter.value === 'ALL' ||
      (filter.value === 'ADVICE' && item.category === 'ADVICE') ||
      (filter.value === 'ATTENTION' &&
        ['RECORD_DUE', 'NEEDS_ATTENTION', 'WAITING_DATA', 'PENDING_CONSENT'].includes(
          item.judgment,
        )),
  ),
)
const acceptedCount = computed(
  () => items.value.filter((item) => item.myConsent === 'ACCEPTED' && !item.paused).length,
)
const dueCount = computed(() => items.value.filter((item) => item.judgment === 'RECORD_DUE').length)
const dataCount = computed(
  () => items.value.filter((item) => item.judgment === 'WAITING_DATA').length,
)
const allSelected = computed(
  () =>
    session.members.length > 0 &&
    session.members.every((member) => form.targetMemberIds.includes(member.id)),
)

function blankForm(): SavePersonalPrinciple {
  return {
    title: '',
    category: 'MUST',
    targetMemberIds: [],
    triggerText: '',
    desiredAction: '',
    exceptionText: '',
    reminderTime: null,
    evidenceSource: 'MANUAL',
    paused: false,
    version: undefined,
  }
}
function draftKey() {
  return `wao-principle-draft-v1:${session.userId}:${editingId.value || 'new'}`
}
function persistDraft() {
  if (!editorOpen.value || !session.userId || restoring) return
  try {
    localStorage.setItem(draftKey(), JSON.stringify(form))
    draftState.value = 'saved'
  } catch {
    draftState.value = 'failed'
  }
}
watch(form, persistDraft, { deep: true })
watch(
  () => session.userId,
  () => {
    editorOpen.value = false
    items.value = []
    selectedId.value = ''
    void load()
  },
)
async function load() {
  const version = ++loadVersion
  if (!session.userId) {
    loading.value = false
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const result = await fetchPersonalPrinciples(today.value)
    if (version !== loadVersion) return
    items.value = result
    if (!items.value.some((item) => item.id === selectedId.value))
      selectedId.value = items.value[0]?.id ?? ''
  } catch (error) {
    if (version === loadVersion) loadError.value = message(error)
  } finally {
    if (version === loadVersion) loading.value = false
  }
}
function message(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}
function openEditor(item?: PersonalPrinciple) {
  savedMessage.value = ''
  restoring = true
  editingId.value = item?.id ?? ''
  Object.assign(
    form,
    item
      ? {
          title: item.title,
          category: item.category,
          targetMemberIds: item.targets.map((target) => target.memberId),
          triggerText: item.triggerText,
          desiredAction: item.desiredAction,
          exceptionText: item.exceptionText,
          reminderTime: item.reminderTime?.slice(0, 5) ?? null,
          evidenceSource: item.evidenceSource,
          paused: item.paused,
          version: item.version,
        }
      : { ...blankForm(), targetMemberIds: session.userId ? [session.userId] : [] },
  )
  let storageFailed = false
  try {
    const stored = localStorage.getItem(draftKey())
    if (stored) {
      if (window.confirm('发现尚未保存的准则草稿，恢复继续编辑？'))
        Object.assign(form, JSON.parse(stored))
    }
  } catch {
    storageFailed = true
  }
  actionError.value = ''
  draftState.value = storageFailed ? 'failed' : 'idle'
  editorOpen.value = true
  void nextTick(() => {
    restoring = false
  })
}
function closeEditor() {
  if (draftState.value !== 'idle' && !window.confirm('草稿仍会保存在当前浏览器，确定关闭编辑？'))
    return
  editorOpen.value = false
}
function discardDraft() {
  try {
    localStorage.removeItem(draftKey())
  } catch {
    /* 浏览器禁止存储时也允许关闭 */
  }
  editorOpen.value = false
  draftState.value = 'idle'
}
function toggleAll() {
  form.targetMemberIds = allSelected.value ? [] : session.members.map((member) => member.id)
}
function toggleMember(id: string) {
  form.targetMemberIds = form.targetMemberIds.includes(id)
    ? form.targetMemberIds.filter((value) => value !== id)
    : [...form.targetMemberIds, id]
}
async function save() {
  actionError.value = ''
  if (
    !form.title.trim() ||
    !form.triggerText.trim() ||
    !form.desiredAction.trim() ||
    !form.targetMemberIds.length
  ) {
    actionError.value = '请填写标题、判断时机、期望动作，并至少选择一位适用人'
    return
  }
  busy.value = true
  try {
    const wasEditing = Boolean(editingId.value)
    const body = {
      ...form,
      title: form.title.trim(),
      triggerText: form.triggerText.trim(),
      desiredAction: form.desiredAction.trim(),
    }
    const saved = editingId.value
      ? await updatePersonalPrinciple(editingId.value, body)
      : await createPersonalPrinciple(body)
    try {
      localStorage.removeItem(draftKey())
    } catch {
      /* 服务端已保存，不将本机清理失败误报为保存失败 */
    }
    editorOpen.value = false
    draftState.value = 'idle'
    await load()
    selectedId.value = saved.id
    savedMessage.value = wasEditing ? '准则已更新' : '准则已保存，受邀人可分别确认'
  } catch (error) {
    actionError.value = message(error)
  } finally {
    busy.value = false
  }
}
async function consent(value: 'ACCEPTED' | 'DECLINED') {
  if (!selected.value) return
  busy.value = true
  actionError.value = ''
  try {
    await setPrincipleConsent(selected.value.id, value)
    await load()
  } catch (error) {
    actionError.value = message(error)
  } finally {
    busy.value = false
  }
}
async function checkin(status: 'DONE' | 'NEEDS_WORK' | 'EXCEPTION') {
  if (!selected.value) return
  const note =
    status === 'DONE'
      ? ''
      : window.prompt(
          status === 'EXCEPTION' ? '写下今天的例外（可留空）' : '写下需要调整的地方（可留空）',
          selected.value.myCheckin?.note ?? '',
        )
  if (note === null) return
  busy.value = true
  actionError.value = ''
  try {
    await savePrincipleCheckin(selected.value.id, today.value, status, note)
    await load()
  } catch (error) {
    actionError.value = message(error)
  } finally {
    busy.value = false
  }
}
async function showRevisions() {
  if (!selected.value) return
  revisionError.value = ''
  revisionsOpen.value = true
  try {
    revisions.value = await fetchPrincipleRevisions(selected.value.id)
  } catch (error) {
    revisionError.value = message(error)
  }
}
onMounted(() => {
  void load()
  clockTimer = setInterval(() => {
    const current = shanghaiDate()
    if (today.value !== current) {
      today.value = current
      void load()
    }
  }, 60_000)
})
onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <main class="principle-page">
    <header class="principle-hero">
      <div class="hero-copy">
        <span class="eyebrow"
          ><i class="ri-shield-check-line" /> MY PRINCIPLES · 我们的生活准则</span
        >
        <h1>把想坚持的事，<br /><em>写成温柔而清楚的约定。</em></h1>
        <p>
          多人一起制定，每个人自己确认、自己记录。提醒会说明依据；没有可靠数据时，保持“无法判断”。
        </p>
        <button class="primary-action" type="button" @click="openEditor()">
          <i class="ri-add-line" /> 制定一条准则
        </button>
      </div>
      <div class="hero-art" aria-hidden="true">
        <div class="art-card art-back" />
        <div class="art-card art-mid" />
        <div class="art-card art-front">
          <i class="ri-shield-check-line" /><small>准则 → 依据 → 轻提醒</small
          ><strong>给重要的事留出边界</strong><span />
        </div>
      </div>
    </header>

    <div class="principle-body">
      <div class="overview">
        <div>
          <span class="eyebrow">YOUR SPACE</span>
          <h2>我的准则</h2>
          <p>当前登录成员：{{ session.currentUser.name }}</p>
        </div>
        <span class="overview-hint">这里只显示真实规则与本人记录</span>
      </div>
      <p v-if="savedMessage" class="save-notice" role="status">{{ savedMessage }}</p>
      <div v-if="loading" class="state-card" role="status">正在读取准则…</div>
      <div v-else-if="loadError" class="state-card error" role="alert">
        {{ loadError }} <button type="button" @click="load">重试</button>
      </div>
      <template v-else>
        <div class="stats">
          <div>
            <span class="stat-icon indigo"><i class="ri-shield-check-line" /></span
            ><span
              ><small>参与中的准则</small><strong>{{ acceptedCount }}</strong></span
            >
          </div>
          <div>
            <span class="stat-icon amber"><i class="ri-pencil-line" /></span
            ><span
              ><small>待本人记录</small><strong>{{ dueCount }}</strong></span
            >
          </div>
          <div>
            <span class="stat-icon mint"><i class="ri-database-2-line" /></span
            ><span
              ><small>待接入数据</small><strong>{{ dataCount }}</strong></span
            >
          </div>
        </div>
        <div class="content-grid">
          <section class="board" aria-label="准则列表">
            <div class="board-head">
              <div>
                <span class="eyebrow">THE RULES</span>
                <h2>一起制定的约定</h2>
              </div>
              <button class="text-button" type="button" @click="openEditor()">
                <i class="ri-add-line" /> 新建
              </button>
            </div>
            <div class="filters" role="group" aria-label="筛选准则">
              <button
                v-for="option in [
                  { id: 'ALL', text: '全部' },
                  { id: 'ATTENTION', text: '待关注' },
                  { id: 'ADVICE', text: '建议' },
                ]"
                :key="option.id"
                type="button"
                :aria-pressed="filter === option.id"
                :class="{ active: filter === option.id }"
                @click="filter = option.id as typeof filter"
              >
                {{ option.text }}
              </button>
            </div>
            <div v-if="!items.length" class="empty-state">
              <i class="ri-leaf-line" /><strong>先定一条你真的在意的准则</strong>
              <p>例如：不在床上玩手机，或早晚各散步一次。</p>
              <button type="button" @click="openEditor()">开始制定</button>
            </div>
            <div v-else-if="!visible.length" class="empty-state">
              <strong>当前筛选下没有准则</strong>
              <p>切回“全部”查看完整列表。</p>
              <button type="button" @click="filter = 'ALL'">查看全部</button>
            </div>
            <div v-else class="rule-list">
              <button
                v-for="item in visible"
                :key="item.id"
                type="button"
                class="rule-row"
                :class="{ selected: selectedId === item.id }"
                @click="selectRule(item.id)"
              >
                <span class="rule-icon"
                  ><i
                    :class="
                      item.category === 'ADVICE' ? 'ri-lightbulb-line' : 'ri-shield-check-line'
                    "
                /></span>
                <span class="rule-copy"
                  ><strong>{{ item.title }}</strong
                  ><small
                    >{{ categories.find((category) => category.id === item.category)?.name }} ·
                    {{
                      item.targets
                        .map((target) => session.memberById(target.memberId).name)
                        .join('、')
                    }}</small
                  ></span
                >
                <span class="status" :class="item.judgment.toLowerCase()">{{
                  statusText[item.judgment]
                }}</span>
              </button>
            </div>
          </section>
          <section class="board detail-board" aria-label="准则详情">
            <template v-if="selected">
              <div class="board-head">
                <div>
                  <span class="eyebrow">RULE · V{{ selected.version }}</span>
                  <h2>{{ selected.title }}</h2>
                </div>
                <button
                  v-if="selected.ownerMemberId === session.userId"
                  class="text-button"
                  type="button"
                  @click="openEditor(selected)"
                >
                  编辑
                </button>
              </div>
              <div class="judgment">
                <span class="status" :class="selected.judgment.toLowerCase()">{{
                  statusText[selected.judgment]
                }}</span>
                <p>{{ selected.judgmentReason }}</p>
              </div>
              <div class="detail-line">
                <small>如果 / 时机</small><strong>{{ selected.triggerText }}</strong>
              </div>
              <div class="detail-line">
                <small>就 / 希望怎样</small><strong>{{ selected.desiredAction }}</strong>
              </div>
              <div v-if="selected.exceptionText" class="detail-line">
                <small>例外</small><strong>{{ selected.exceptionText }}</strong>
              </div>
              <div class="detail-line">
                <small>提醒时刻</small
                ><strong>{{ selected.reminderTime?.slice(0, 5) ?? '未设置' }}</strong>
              </div>
              <div class="detail-line">
                <small>判断依据</small
                ><strong>{{
                  selected.evidenceSource === 'SCREEN_TIME'
                    ? '屏幕使用时间 · 尚无数据接口'
                    : '本人手动记录'
                }}</strong>
              </div>
              <div class="detail-line">
                <small>适用人 / 分别确认</small>
                <div class="member-tags">
                  <span v-for="target in selected.targets" :key="target.memberId"
                    >{{ session.memberById(target.memberId).name }} ·
                    {{
                      target.consent === 'ACCEPTED'
                        ? '已接受'
                        : target.consent === 'PENDING'
                          ? '待确认'
                          : '已撤回'
                    }}</span
                  >
                </div>
              </div>
              <p v-if="actionError" class="inline-error" role="alert">{{ actionError }}</p>
              <div
                v-if="selected.myConsent === 'PENDING' || selected.myConsent === 'DECLINED'"
                class="detail-actions"
              >
                <button type="button" :disabled="busy" @click="consent('ACCEPTED')">
                  接受这条准则</button
                ><button
                  v-if="selected.myConsent === 'PENDING'"
                  type="button"
                  class="quiet"
                  :disabled="busy"
                  @click="consent('DECLINED')"
                >
                  暂不参与
                </button>
              </div>
              <div v-else-if="selected.myConsent === 'ACCEPTED'" class="detail-actions">
                <button
                  v-if="!selected.paused"
                  type="button"
                  :disabled="busy"
                  @click="checkin('DONE')"
                >
                  今天已做到</button
                ><button
                  v-if="!selected.paused"
                  type="button"
                  class="quiet"
                  :disabled="busy"
                  @click="checkin('NEEDS_WORK')"
                >
                  需要调整</button
                ><button
                  v-if="!selected.paused"
                  type="button"
                  class="quiet"
                  :disabled="busy"
                  @click="checkin('EXCEPTION')"
                >
                  记个例外</button
                ><button type="button" class="quiet" :disabled="busy" @click="consent('DECLINED')">
                  撤回参与
                </button>
              </div>
              <div v-if="selected.myCheckin" class="checkin-note">
                今天的本人记录：{{
                  selected.myCheckin.status === 'DONE'
                    ? '已做到'
                    : selected.myCheckin.status === 'EXCEPTION'
                      ? '例外'
                      : '需要调整'
                }}{{ selected.myCheckin.note ? ` · ${selected.myCheckin.note}` : '' }}
              </div>
              <button class="revision-link" type="button" @click="showRevisions">
                查看版本记录 <i class="ri-arrow-right-line" />
              </button>
            </template>
            <div v-else class="detail-placeholder">
              <i class="ri-file-list-3-line" />选中一条准则，查看依据与记录。
            </div>
          </section>
        </div>
        <p class="privacy-note">
          <i class="ri-lock-line" /> 屏幕使用时间需要独立授权的数据来源。设备联网、后台 AI
          运行和单次页面打开都不代表本人正在使用，系统不会据此通报违规。
        </p>
      </template>
    </div>

    <div v-if="editorOpen" class="modal-backdrop" @click.self="closeEditor">
      <section
        class="editor"
        role="dialog"
        aria-modal="true"
        aria-labelledby="principle-editor-title"
      >
        <header>
          <div>
            <span class="eyebrow">CREATE A PRINCIPLE</span>
            <h2 id="principle-editor-title">{{ editingId ? '调整准则' : '制定一条准则' }}</h2>
          </div>
          <button type="button" class="close" aria-label="关闭" @click="closeEditor">
            <i class="ri-close-line" />
          </button>
        </header>
        <div class="editor-body">
          <label class="field"
            >你想定下什么约定？<textarea
              v-model="form.title"
              maxlength="240"
              rows="2"
              placeholder="例如：不在床上玩手机"
            />
          </label>
          <fieldset class="category-field">
            <legend>准则类型</legend>
            <div class="pills">
              <button
                v-for="category in categories"
                :key="category.id"
                type="button"
                :aria-pressed="form.category === category.id"
                :class="{ active: form.category === category.id }"
                @click="form.category = category.id"
              >
                {{ category.name }}
              </button>
            </div>
            <small>“建议”仅供参考，不计入遵守判断。</small>
          </fieldset>
          <fieldset class="member-field">
            <legend>适用人</legend>
            <label class="select-all"
              ><input type="checkbox" :checked="allSelected" @change="toggleAll" />
              全选当前成员</label
            >
            <div class="member-options">
              <label v-for="member in session.members" :key="member.id"
                ><input
                  type="checkbox"
                  :checked="form.targetMemberIds.includes(member.id)"
                  @change="toggleMember(member.id)"
                />
                {{ member.name }}</label
              >
            </div>
            <small>可多选；受邀人需本人确认。新增成员不会自动加入。</small>
          </fieldset>
          <div class="form-grid">
            <label class="field"
              >判断时机 / 场景<input
                v-model="form.triggerText"
                maxlength="240"
                placeholder="例如：每天睡前" /></label
            ><label class="field"
              >提醒时刻（可选）<input v-model="form.reminderTime" type="time"
            /></label>
          </div>
          <label class="field"
            >发现后希望怎么做？<input
              v-model="form.desiredAction"
              maxlength="500"
              placeholder="例如：放下手机，准备休息"
          /></label>
          <label class="field"
            >例外情况（可选）<input
              v-model="form.exceptionText"
              maxlength="500"
              placeholder="例如：紧急联络"
          /></label>
          <fieldset class="source-field">
            <legend>依据来源</legend>
            <label
              ><input v-model="form.evidenceSource" type="radio" value="MANUAL" />
              本人手动记录</label
            ><label
              ><input v-model="form.evidenceSource" type="radio" value="SCREEN_TIME" />
              屏幕使用时间（待接入，暂不判断）</label
            >
          </fieldset>
          <label v-if="editingId" class="pause-field"
            ><input v-model="form.paused" type="checkbox" /> 暂停这条准则</label
          >
          <p class="form-help">
            {{
              draftState === 'saved'
                ? '草稿已保存在当前浏览器'
                : draftState === 'failed'
                  ? '草稿暂未写入本机，请保持页面打开'
                  : '保存后会留下版本记录'
            }}
          </p>
          <p v-if="actionError" class="inline-error" role="alert">{{ actionError }}</p>
        </div>
        <footer>
          <button type="button" class="quiet" @click="discardDraft">丢弃草稿</button
          ><button type="button" class="quiet" @click="closeEditor">稍后再说</button
          ><button type="button" class="primary-action" :disabled="busy" @click="save">
            {{ busy ? '保存中…' : '保存准则' }}
          </button>
        </footer>
      </section>
    </div>
    <div v-if="revisionsOpen" class="modal-backdrop" @click.self="revisionsOpen = false">
      <section class="revision-modal" role="dialog" aria-modal="true" aria-label="版本记录">
        <header>
          <h2>版本记录</h2>
          <button type="button" class="close" aria-label="关闭" @click="revisionsOpen = false">
            <i class="ri-close-line" />
          </button>
        </header>
        <p v-if="revisionError" class="inline-error">
          {{ revisionError }} <button type="button" @click="showRevisions">重试</button>
        </p>
        <pre v-for="(entry, index) in revisions" :key="index">{{
          JSON.stringify(JSON.parse(entry), null, 2)
        }}</pre>
      </section>
    </div>
  </main>
</template>

<style scoped>
.principle-page {
  max-width: 1440px;
  margin: auto;
  padding: 26px 28px 60px;
  color: #29334b;
}
.principle-hero {
  min-height: 254px;
  display: grid;
  grid-template-columns: 1.2fr 0.8fr;
  background: linear-gradient(115deg, #f9faff, #edf2ff);
  border: 1px solid #e1e6f7;
  border-radius: 18px;
  overflow: hidden;
  position: relative;
}
.hero-copy {
  padding: 31px 40px;
  position: relative;
  z-index: 1;
}
.eyebrow {
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 1.5px;
  color: #6071b7;
}
.hero-copy h1 {
  font-size: 32px;
  line-height: 1.28;
  letter-spacing: -1px;
  margin: 16px 0 10px;
}
.hero-copy h1 em {
  font-style: normal;
  color: #5367c7;
}
.hero-copy p {
  font-size: 13px;
  color: #68758e;
  line-height: 1.7;
  margin: 0 0 18px;
  max-width: 540px;
}
.primary-action {
  background: #495fd0;
  color: white;
  border: 0;
  border-radius: 10px;
  padding: 11px 16px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 8px 20px #4b60c034;
}
.primary-action:hover {
  background: #3e52bd;
  transform: translateY(-1px);
}
button {
  font: inherit;
  cursor: pointer;
}
.hero-art {
  position: relative;
  min-height: 254px;
}
.art-card {
  position: absolute;
  width: 216px;
  height: 148px;
  left: 32%;
  top: 25%;
  border-radius: 16px;
  border: 1px solid #d6dff7;
  box-shadow: 0 18px 35px #40549322;
}
.art-back {
  background: #d9e2fa;
  transform: translate(40px, -22px) rotate(11deg);
}
.art-mid {
  background: #eef4ff;
  transform: translate(-28px, 16px) rotate(-9deg);
}
.art-front {
  background: linear-gradient(145deg, #fff, #f6f8ff);
  transform: rotate(2deg);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 20px;
}
.art-front i {
  font-size: 27px;
  color: #586ccc;
}
.art-front small {
  color: #8c98b4;
}
.art-front strong {
  color: #34446c;
}
.art-front span {
  width: 74px;
  height: 5px;
  border-radius: 4px;
  background: #7184d7;
}
.principle-body {
  padding-top: 26px;
}
.overview {
  display: flex;
  justify-content: space-between;
  align-items: end;
  gap: 12px;
}
.overview h2 {
  font-size: 21px;
  margin: 4px 0;
}
.overview p,
.overview-hint {
  font-size: 12px;
  color: #8b94a7;
  margin: 0;
}
.save-notice {
  margin: 14px 0 0;
  padding: 9px 12px;
  border: 1px solid #cde8db;
  border-radius: 9px;
  background: #effaf4;
  color: #27765b;
  font-size: 12px;
}
.stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin: 20px 0;
}
.stats > div {
  border: 1px solid #e8ecf5;
  background: #fff;
  border-radius: 13px;
  padding: 15px;
  display: flex;
  align-items: center;
  gap: 13px;
}
.stat-icon {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  font-size: 18px;
}
.indigo {
  background: #e9edff;
  color: #4f63c4;
}
.amber {
  background: #fff3df;
  color: #b47d24;
}
.mint {
  background: #e2f5ef;
  color: #2b967b;
}
.stats small {
  display: block;
  color: #8e98aa;
  font-size: 11px;
}
.stats strong {
  font-size: 21px;
}
.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(320px, 0.85fr);
  gap: 17px;
}
.board {
  background: white;
  border: 1px solid #e8ebf2;
  border-radius: 15px;
  padding: 20px;
  min-width: 0;
}
.board-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}
.board-head h2 {
  font-size: 18px;
  margin: 5px 0 14px;
}
.text-button {
  border: 0;
  background: #f1f4ff;
  color: #5266c4;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}
.filters {
  display: flex;
  gap: 7px;
  border-bottom: 1px solid #eef0f5;
  padding-bottom: 13px;
  margin-bottom: 13px;
}
.filters button {
  border: 1px solid transparent;
  background: transparent;
  color: #8290a5;
  border-radius: 8px;
  padding: 7px 12px;
  font-size: 12px;
}
.filters button.active {
  background: #eaf0ff;
  color: #485dbb;
  border-color: #d8e0fa;
}
.rule-list {
  display: grid;
  gap: 8px;
}
.rule-row {
  border: 1px solid #e8ecf4;
  border-radius: 11px;
  background: white;
  padding: 12px;
  display: flex;
  align-items: center;
  text-align: left;
  gap: 11px;
  width: 100%;
  min-height: 66px;
  transition:
    transform 0.18s,
    box-shadow 0.18s,
    border-color 0.18s;
}
.rule-row:hover {
  transform: translateY(-2px);
  box-shadow: 0 9px 20px #5262a415;
}
.rule-row.selected {
  border-color: #879ae1;
  background: #fafbff;
}
.rule-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: #eef1ff;
  color: #5165c5;
  display: grid;
  place-items: center;
  flex: none;
}
.rule-copy {
  min-width: 0;
  flex: 1;
}
.rule-copy strong,
.rule-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rule-copy strong {
  font-size: 13px;
}
.rule-copy small {
  font-size: 11px;
  color: #949eaf;
  margin-top: 5px;
}
.status {
  font-size: 10px;
  border-radius: 100px;
  background: #f2f3f7;
  color: #7f8798;
  padding: 5px 8px;
  white-space: nowrap;
}
.status.completed,
.status.advice {
  background: #e5f6ef;
  color: #278768;
}
.status.record_due,
.status.needs_attention {
  background: #fff3df;
  color: #ad792f;
}
.status.waiting_data,
.status.pending_consent {
  background: #eef0ff;
  color: #5364bc;
}
.judgment {
  background: linear-gradient(135deg, #f6f8ff, #fcfdff);
  border: 1px solid #e6ebfb;
  border-radius: 12px;
  padding: 15px;
  margin: 3px 0 15px;
}
.judgment p {
  font-size: 12px;
  color: #6e7b96;
  line-height: 1.6;
  margin: 9px 0 0;
}
.detail-line {
  border-bottom: 1px solid #f0f1f5;
  padding: 11px 0;
  display: grid;
  gap: 5px;
}
.detail-line small {
  color: #96a0b0;
}
.detail-line strong {
  font-size: 12px;
  line-height: 1.6;
  font-weight: 600;
}
.member-tags {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}
.member-tags span {
  font-size: 10px;
  border-radius: 7px;
  background: #f3f5fa;
  color: #66728d;
  padding: 5px;
}
.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 16px;
}
.detail-actions button,
.empty-state button {
  background: #5368ca;
  color: white;
  border: 0;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 11px;
  font-weight: 700;
}
.detail-actions button.quiet {
  background: #f1f3f9;
  color: #596782;
}
.checkin-note {
  font-size: 11px;
  color: #508d75;
  background: #effaf4;
  border-radius: 8px;
  padding: 9px;
  margin-top: 10px;
}
.revision-link {
  margin-top: 15px;
  border: 0;
  background: transparent;
  color: #586dca;
  font-size: 11px;
}
.detail-placeholder,
.empty-state,
.state-card {
  text-align: center;
  padding: 40px 20px;
  color: #8d98ac;
}
.detail-placeholder i,
.empty-state i {
  display: block;
  font-size: 29px;
  color: #9ba9dc;
  margin-bottom: 9px;
}
.empty-state strong {
  display: block;
  color: #4b5877;
}
.empty-state p {
  font-size: 12px;
}
.privacy-note {
  font-size: 11px;
  line-height: 1.6;
  color: #8590a3;
  margin: 20px 2px;
}
.state-card {
  background: white;
  border: 1px solid #e9ecf2;
  border-radius: 13px;
  margin: 20px 0;
}
.state-card.error,
.inline-error {
  color: #b85050;
}
.state-card button {
  border: 0;
  background: transparent;
  color: #5368ca;
  font-weight: 700;
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: #26324a66;
  display: flex;
  justify-content: flex-end;
  align-items: stretch;
}
.editor {
  background: #fff;
  width: min(560px, 100%);
  height: 100%;
  display: flex;
  flex-direction: column;
  box-shadow: -15px 0 40px #14213d24;
}
.editor header,
.editor footer,
.revision-modal header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid #eef0f5;
}
.editor header h2,
.revision-modal h2 {
  font-size: 20px;
  margin: 4px 0 0;
}
.close {
  border: 0;
  background: #f4f6fb;
  border-radius: 8px;
  padding: 7px;
}
.editor-body {
  overflow-y: auto;
  padding: 20px 24px;
  display: grid;
  gap: 17px;
}
.field {
  display: grid;
  gap: 7px;
  font-size: 12px;
  font-weight: 700;
}
.field input,
.field textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid #dfe4ef;
  border-radius: 9px;
  padding: 10px;
  font: inherit;
  font-size: 13px;
  color: #2c3854;
  background: white;
}
.field textarea {
  resize: vertical;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 170px;
  gap: 12px;
}
.category-field,
.member-field,
.source-field {
  border: 0;
  padding: 0;
  margin: 0;
}
.category-field legend,
.member-field legend,
.source-field legend {
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 9px;
}
.category-field small,
.member-field small {
  font-size: 10px;
  color: #929aac;
}
.pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 7px;
}
.pills button {
  border: 1px solid #dce2ef;
  background: white;
  color: #6c7690;
  border-radius: 100px;
  padding: 7px 12px;
  font-size: 11px;
}
.pills button.active {
  background: #eaf0ff;
  color: #455dc1;
  border-color: #aebbfb;
}
.select-all,
.member-options label,
.source-field label,
.pause-field {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: #4f5e7b;
  padding: 6px 0;
}
.member-options {
  display: flex;
  gap: 15px;
  flex-wrap: wrap;
}
.form-help {
  font-size: 11px;
  color: #8c96a8;
}
.editor footer {
  margin-top: auto;
  border-top: 1px solid #eef0f5;
  border-bottom: 0;
  justify-content: flex-end;
  gap: 10px;
}
.editor footer .quiet {
  border: 0;
  background: #f1f3f9;
  color: #5e6c88;
  border-radius: 8px;
  padding: 10px;
}
.revision-modal {
  align-self: center;
  margin: auto;
  background: white;
  border-radius: 15px;
  width: min(620px, calc(100% - 30px));
  max-height: 80vh;
  overflow: auto;
}
.revision-modal pre {
  font-size: 11px;
  margin: 14px 20px;
  padding: 13px;
  background: #f5f7fb;
  border-radius: 8px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.inline-error {
  font-size: 12px;
}
@media (max-width: 800px) {
  .principle-page {
    padding: 14px 14px 50px;
  }
  .principle-hero {
    grid-template-columns: 1fr;
    min-height: 0;
  }
  .hero-copy {
    padding: 25px 21px;
  }
  .hero-copy h1 {
    font-size: 25px;
  }
  .hero-art {
    display: none;
  }
  .content-grid {
    grid-template-columns: 1fr;
  }
  .stats {
    gap: 7px;
  }
  .stats > div {
    padding: 10px;
    gap: 7px;
  }
  .stats small {
    font-size: 10px;
  }
  .stats strong {
    font-size: 17px;
  }
  .stat-icon {
    width: 29px;
    height: 29px;
    font-size: 14px;
  }
  .overview-hint {
    display: none;
  }
  .board {
    padding: 15px;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
}
@media (prefers-reduced-motion: reduce) {
  .rule-row,
  .primary-action {
    transition: none !important;
  }
  .rule-row:hover,
  .primary-action:hover {
    transform: none;
  }
}
</style>
