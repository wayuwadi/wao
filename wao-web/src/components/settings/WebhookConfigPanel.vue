<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  RouterLink,
  useRoute,
  useRouter,
} from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
import { toast } from '@/composables/toast'
import { readBotDraft, rememberBotDraft } from '@/utils/botConfigDraft'
import FeishuReceiverSettings from '@/components/capabilities/FeishuReceiverSettings.vue'
import {
  createWebhookEndpoint,
  deleteWebhookEndpoint,
  fetchWebhookEndpoints,
  fetchWebhookActivity,
  testWebhookEndpoint,
  updateWebhookEndpoint,
  type FeishuWebhookEndpoint,
  type FeishuWebhookEndpointInput,
  type FeishuWebhookActivity,
} from '@/api/system'

const session = useSessionStore(),
  route = useRoute(),
  router = useRouter(),
  owner = session.userId
const canManage = computed(() => ['管理员', '超级管理员'].includes(session.currentUser.role))
const endpoints = ref<FeishuWebhookEndpoint[]>([]),
  selectedId = ref(''),
  mode = ref<'none' | 'new' | 'edit'>('none')
const pane = ref<'intro' | 'settings' | 'records' | 'receiver'>('settings'),
  keyword = ref(''),
  filter = ref('全部')
const loading = ref(true),
  loadError = ref(''),
  saving = ref(false),
  testingId = ref(''),
  deletingId = ref('')
const saveError = ref(''),
  restored = ref(false),
  storageError = ref(false)
const activity = ref<FeishuWebhookActivity | null>(null),
  activityLoading = ref(false),
  activityError = ref('')
const itemErrors = reactive<Record<string, string>>({}),
  receiverEditor = ref<{ confirmLeave: () => boolean } | null>(null)
let listGeneration = 0,
  activityGeneration = 0,
  disposed = false
const selected = computed(() => endpoints.value.find((row) => row.id === selectedId.value))
const fresh = (): FeishuWebhookEndpointInput => ({
  name: '',
  description: '',
  url: '',
  enabled: true,
  systemDefault: endpoints.value.length === 0,
  captureKind: 'NONE',
  mentionId: '',
  mentionName: '',
  chatId: '',
  appId: '',
  revision: undefined,
})
const fromItem = (row: FeishuWebhookEndpoint): FeishuWebhookEndpointInput => ({
  name: row.name,
  description: row.description,
  url: '',
  enabled: row.enabled,
  systemDefault: row.systemDefault,
  captureKind: row.captureKind ?? 'NONE',
  mentionId: row.mentionId ?? '',
  mentionName: row.mentionName ?? '',
  chatId: row.chatId ?? '',
  appId: row.appId ?? '',
  revision: row.revision,
})
const form = reactive(fresh())
const baseline = computed(() =>
  mode.value === 'edit' && selected.value ? fromItem(selected.value) : fresh(),
)
const dirty = computed(
  () =>
    mode.value !== 'none' &&
    JSON.stringify({ ...form, url: form.url?.trim() ?? '' }) !== JSON.stringify(baseline.value),
)
const conflicted = computed(
  () => mode.value === 'edit' && form.revision !== selected.value?.revision,
)
const visible = computed(() =>
  endpoints.value.filter(
    (row) =>
      (filter.value === '全部' ||
        (filter.value === '已停用'
          ? !row.enabled
          : (row.captureKind ?? 'NONE') === filter.value)) &&
      `${row.name} ${row.description} ${kindLabel(row.captureKind)}`
        .toLowerCase()
        .includes(keyword.value.trim().toLowerCase()),
  ),
)
const labels: Record<string, string> = {
  SUCCESS: '成功',
  FAILED: '失败',
  CREATED: '已保存',
  UPDATED: '已更新',
  REJECTED: '未收下',
  IGNORED: '已忽略',
  RECALLED: '已撤回',
  PROCESSING: '处理中',
  RECEIVED: '已接收',
  CONFLICT: '编辑冲突',
}
const errors: Record<string, string> = {
  SENDER_NOT_BOUND: '发言人尚未绑定飞书，请到账号设置绑定',
  SENDER_IDENTITY_CONFLICT: '身份绑定冲突，请核对账号',
  EMPTY_CONTENT: '内容为空',
  NO_PROJECT: '没有可写入的目标项目',
  SOURCE_CONFLICT: 'WAO 内容已有修改，请核对后再同步',
  TARGET_CHANGED: 'WAO 内容已有修改，本次同步已停止',
  ROUTE_CHANGED: '机器人收集用途已变更，请重新发送消息',
  CAPTURE_FAILED: '收集处理失败，请核对接收配置后重试',
  DELIVERY_FAILED: '发送失败，请检查配置',
  AMBIGUOUS_MENTION: '同时 @ 多个入口，请只选择一个',
}
const captureVerified = computed(() =>
  activity.value?.recentCaptures.some((row) => ['CREATED', 'UPDATED'].includes(row.status)),
)
function kindLabel(kind?: string) {
  return kind === 'TASK' ? '创建待办' : kind === 'NOTE' ? '创建小记' : '发送通知'
}
function alive() {
  return !disposed && owner === session.userId
}
function sort(items: FeishuWebhookEndpoint[]) {
  return [...items].sort(
    (a, b) =>
      Number(b.systemDefault) - Number(a.systemDefault) || b.updatedAt.localeCompare(a.updatedAt),
  )
}
function draftId() {
  return mode.value === 'new' ? 'new' : selectedId.value
}
watch(
  form,
  () => {
    if (alive() && mode.value !== 'none')
      storageError.value = !rememberBotDraft(owner, draftId(), dirty.value ? { ...form } : null)
  },
  { deep: true, flush: 'post' },
)
function confirmLeave() {
  if (saving.value || testingId.value || deletingId.value) {
    toast('操作正在进行，请稍后再离开')
    return false
  }
  if (receiverEditor.value && !receiverEditor.value.confirmLeave()) return false
  return (
    !(dirty.value && (form.url?.trim() || storageError.value)) ||
    window.confirm(
      'Webhook 密钥只保留在当前页面；确认离开并放弃未保存的密钥吗？其他配置草稿会保留。',
    )
  )
}
function selectForm(item?: FeishuWebhookEndpoint) {
  mode.value = item ? 'edit' : 'new'
  selectedId.value = item?.id ?? ''
  const draft = readBotDraft(owner, item?.id ?? 'new')
  Object.assign(form, item ? fromItem(item) : fresh(), draft ?? {})
  restored.value = !!draft
  saveError.value = ''
  pane.value = 'settings'
}
async function edit(item: FeishuWebhookEndpoint) {
  if (!confirmLeave()) return
  selectForm(item)
  if (route.query.bot !== item.id) void router.replace({ query: { ...route.query, bot: item.id } })
  await loadActivity()
}
function startCreate() {
  if (!confirmLeave()) return
  activityGeneration++
  activity.value = null
  activityError.value = ''
  activityLoading.value = false
  selectForm()
  void router.replace({ query: { ...route.query, bot: undefined } })
}
function restoreServer() {
  if (selected.value && confirmLeave()) {
    rememberBotDraft(owner, selectedId.value, null)
    Object.assign(form, fromItem(selected.value))
    restored.value = false
    saveError.value = ''
  }
}
function rebaseDraft() {
  if (selected.value) {
    form.revision = selected.value.revision
    saveError.value = ''
    restored.value = true
  }
}
function showReceiver() {
  if (confirmLeave()) pane.value = 'receiver'
}
function attachReceiver() {
  if (!activity.value) return
  form.appId = activity.value.receiver.appId
  form.chatId = activity.value.receiver.chatId
}
function changePane(next: 'intro' | 'settings' | 'records') {
  if (!receiverEditor.value || receiverEditor.value.confirmLeave()) pane.value = next
}
async function loadActivity() {
  const id = selectedId.value,
    generation = ++activityGeneration
  activity.value = null
  activityError.value = ''
  if (!id || !canManage.value) return
  activityLoading.value = true
  try {
    const result = await fetchWebhookActivity(id)
    if (alive() && generation === activityGeneration) activity.value = result
  } catch (error) {
    if (alive() && generation === activityGeneration)
      activityError.value = error instanceof Error ? error.message : '引用与记录读取失败'
  } finally {
    if (alive() && generation === activityGeneration) activityLoading.value = false
  }
}
async function load(force = false) {
  if (force && !confirmLeave()) return
  const generation = ++listGeneration
  loading.value = true
  loadError.value = ''
  try {
    const rows = await fetchWebhookEndpoints()
    if (!alive() || generation !== listGeneration) return
    endpoints.value = sort(rows)
    const id = selectedId.value || String(route.query.bot ?? ''),
      target = rows.find((row) => row.id === id) ?? endpoints.value[0]
    if (!dirty.value) {
      if (target) selectForm(target)
      else if (readBotDraft(owner, 'new')) selectForm()
      else mode.value = 'none'
    }
    await loadActivity()
    if (route.query.pane === 'receiver') pane.value = 'receiver'
  } catch (error) {
    if (alive() && generation === listGeneration)
      loadError.value = error instanceof Error ? error.message : '机器人清单读取失败'
  } finally {
    if (alive() && generation === listGeneration) loading.value = false
  }
}
function impactText(meta: FeishuWebhookActivity, action: string, item: FeishuWebhookEndpoint) {
  const uses =
    meta.usages
      .map((use) => `${use.label}${use.count > 1 ? `（${use.count} 项）` : ''}`)
      .join('、') || '暂无已登记的功能引用'
  return `${action}「${item.name}」？\n被以下功能使用：${uses}。\n${action === '删除' && item.systemDefault && meta.replacementName ? `删除后系统默认通知将由「${meta.replacementName}」接管。` : '依赖该配置的功能可能暂停，已保存内容保留。'}`
}
async function save() {
  saveError.value = ''
  if (!form.name.trim()) {
    saveError.value = '请填写配置名称'
    return false
  }
  if ((mode.value === 'new' || selected.value?.urlSet === false) && !form.url?.trim()) {
    saveError.value = '请填写飞书官方 Webhook 地址'
    return false
  }
  if (conflicted.value) {
    saveError.value = '服务器配置已有更新，请先处理草稿冲突'
    return false
  }
  saving.value = true
  const id = selectedId.value,
    creating = mode.value === 'new'
  try {
    let impactConfirmed = false
    if (
      selected.value &&
      ((selected.value.enabled && !form.enabled) ||
        selected.value.captureKind !== form.captureKind ||
        selected.value.systemDefault !== form.systemDefault ||
        selected.value.mentionId !== form.mentionId ||
        selected.value.mentionName !== form.mentionName ||
        selected.value.appId !== form.appId ||
        selected.value.chatId !== form.chatId)
    ) {
      const meta = await fetchWebhookActivity(id)
      if (!alive()) return false
      if (
        meta.usages.length &&
        !window.confirm(impactText(meta, !form.enabled ? '停用' : '变更接入规则', selected.value))
      )
        return false
      impactConfirmed = true
    }
    if (form.systemDefault && !selected.value?.systemDefault && endpoints.value.length) {
      const old = endpoints.value.find((row) => row.systemDefault && row.id !== id)
      if (old) {
        const meta = await fetchWebhookActivity(old.id)
        if (!alive() || !window.confirm(impactText(meta, '切换系统默认通知', old))) return false
        impactConfirmed = true
      }
    }
    const payload = {
      ...form,
      name: form.name.trim(),
      description: form.description.trim(),
      url: form.url?.trim() || undefined,
      appId: form.appId || undefined,
      chatId: form.chatId || undefined,
      impactConfirmed,
    }
    const saved = creating
      ? await createWebhookEndpoint(payload)
      : await updateWebhookEndpoint(id, payload)
    if (!alive()) return false
    rememberBotDraft(owner, creating ? 'new' : id, null)
    endpoints.value = sort([
      ...endpoints.value
        .filter((row) => row.id !== saved.id)
        .map((row) => ({ ...row, systemDefault: saved.systemDefault ? false : row.systemDefault })),
      saved,
    ])
    selectForm(saved)
    restored.value = false
    itemErrors[saved.id] = ''
    await loadActivity()
    toast(creating ? '机器人已新增' : '机器人配置已保存', 'ri-check-line')
    return true
  } catch (error) {
    if (alive()) {
      saveError.value = error instanceof Error ? error.message : '保存失败，请重试'
      await load()
    }
    return false
  } finally {
    if (alive()) saving.value = false
  }
}
async function remove(item: FeishuWebhookEndpoint) {
  if (!confirmLeave()) return
  deletingId.value = item.id
  itemErrors[item.id] = ''
  try {
    const meta = await fetchWebhookActivity(item.id)
    if (!alive() || !window.confirm(impactText(meta, '删除', item))) return
    await deleteWebhookEndpoint(item.id, { revision: item.revision, impactConfirmed: true })
    if (!alive()) return
    rememberBotDraft(owner, item.id, null)
    endpoints.value = endpoints.value.filter((row) => row.id !== item.id)
    selectedId.value = ''
    mode.value = 'none'
    Object.assign(form, fresh())
    await load()
    toast('机器人配置已删除', 'ri-delete-bin-line')
  } catch (error) {
    if (alive())
      itemErrors[item.id] =
        error instanceof Error ? error.message : '影响信息读取或删除失败，请重试'
  } finally {
    if (alive()) deletingId.value = ''
  }
}
async function sendTest(item: FeishuWebhookEndpoint) {
  testingId.value = item.id
  itemErrors[item.id] = ''
  try {
    await testWebhookEndpoint(item.id)
    if (alive()) toast('测试消息已发出；群聊收集需真实 @ 验证', 'ri-check-line')
  } catch (error) {
    if (alive()) itemErrors[item.id] = error instanceof Error ? error.message : '测试消息发送失败'
  } finally {
    if (alive()) {
      testingId.value = ''
      if (selectedId.value === item.id) await loadActivity()
    }
  }
}
function beforeUnload(event: BeforeUnloadEvent) {
  if ((dirty.value && (form.url?.trim() || storageError.value)) || saving.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => (to.query.area !== from.query.area ? confirmLeave() : true))
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  if (canManage.value) void load()
  else loading.value = false
})
onBeforeUnmount(() => {
  disposed = true
  listGeneration++
  activityGeneration++
  window.removeEventListener('beforeunload', beforeUnload)
})
useSaveBeforeRefresh(async () => {
  if (pane.value !== 'receiver' && dirty.value && !(await save()))
    throw new Error(saveError.value || '机器人配置尚未保存')
})
defineExpose({ confirmLeave })
</script>

<template>
  <section class="webhook-panel" aria-label="机器人管理">
    <div v-if="!canManage" class="state">
      <i class="ri-robot-2-line" /><strong>机器人由管理员统一配置</strong>
      <p>绑定自己的飞书账号后，在群里 @ 机器人即可保存到你的待办或小记。</p>
      <RouterLink to="/settings?tab=account">查看我的飞书绑定</RouterLink>
    </div>
    <template v-else>
      <header class="bot-header">
        <div>
          <h2>机器人</h2>
          <p>统一管理群通知与 @ 收集，沿用现有配置。</p>
        </div>
        <button type="button" class="ghost" @click="showReceiver">接收应用与群</button
        ><button
          class="primary small"
          type="button"
          :disabled="loading || saving"
          @click="startCreate"
        >
          <i class="ri-add-line" />新增机器人
        </button>
      </header>
      <div class="toolbar">
        <label class="search"
          ><i class="ri-search-line" /><input
            v-model="keyword"
            type="search"
            aria-label="搜索机器人"
            placeholder="搜索名称或用途" /></label
        ><select v-model="filter" aria-label="按机器人用途筛选">
          <option>全部</option>
          <option value="TASK">创建待办</option>
          <option value="NOTE">创建小记</option>
          <option value="NONE">发送通知</option>
          <option>已停用</option></select
        ><button class="ghost" type="button" :disabled="loading || saving" @click="load(true)">
          重新读取
        </button>
      </div>
      <div v-if="loading && !endpoints.length" class="state" role="status">正在读取机器人…</div>
      <div v-else-if="loadError && !endpoints.length" class="state error" role="alert">
        {{ loadError }}<button class="ghost" type="button" @click="load()">重新读取</button>
      </div>
      <template v-else>
        <div v-if="loadError" class="inline-load-error" role="alert">
          {{ loadError }}；当前列表可能不是最新配置。<button class="ghost" @click="load()">
            重新读取
          </button>
        </div>
        <div class="config-layout">
          <aside class="config-list" aria-label="机器人清单">
            <article
              v-for="item in visible"
              :key="item.id"
              class="config-row"
              :class="{ selected: selectedId === item.id }"
            >
              <button
                class="config-select"
                type="button"
                :aria-pressed="selectedId === item.id"
                @click="edit(item)"
              >
                <span class="bot-icon"><i class="ri-robot-2-line" /></span
                ><span class="config-summary"
                  ><span class="name-line"
                    ><strong>{{ item.name }}</strong
                    ><em v-if="item.systemDefault">系统默认</em></span
                  ><small
                    >{{ kindLabel(item.captureKind) }} ·
                    {{ item.enabled ? '已启用' : '已停用' }}</small
                  ><small>{{ item.description || '暂无用途说明' }}</small
                  ><small v-if="itemErrors[item.id]" class="error">{{
                    itemErrors[item.id]
                  }}</small></span
                >
              </button>
            </article>
            <div v-if="!visible.length" class="empty">
              <i class="ri-robot-2-line" /><strong>{{
                endpoints.length ? '没有匹配的机器人' : '还没有机器人配置'
              }}</strong
              ><span>{{
                endpoints.length
                  ? '换个关键词或筛选条件'
                  : '添加现有群机器人的 Webhook，即可统一管理。'
              }}</span>
            </div>
          </aside>
          <div class="detail">
            <FeishuReceiverSettings
              v-if="pane === 'receiver'"
              ref="receiverEditor"
              @saved="loadActivity"
            />
            <template v-else-if="mode !== 'none'">
              <nav class="detail-tabs" aria-label="机器人详情">
                <button
                  v-for="tab in [
                    { id: 'intro', label: '能力介绍' },
                    { id: 'settings', label: '机器人配置' },
                    { id: 'records', label: '运行记录' },
                  ]"
                  :key="tab.id"
                  type="button"
                  :aria-pressed="pane === tab.id"
                  @click="changePane(tab.id as 'intro' | 'settings' | 'records')"
                >
                  {{ tab.label }}
                </button>
              </nav>
              <div v-if="pane === 'intro'" class="intro">
                <h3>{{ selected?.name ?? '新增机器人' }}</h3>
                <p>{{ selected?.description || '群通知与个人快速收集入口。' }}</p>
                <p v-if="selected?.captureKind && selected.captureKind !== 'NONE'">
                  群里 @ 此机器人后，内容按发言人的绑定身份保存为{{
                    kindLabel(selected.captureKind).replace('创建', '')
                  }}。
                </p>
                <RouterLink to="/settings?tab=account">查看我的飞书绑定</RouterLink>
              </div>
              <form v-else-if="pane === 'settings'" class="editor" @submit.prevent="save">
                <fieldset :disabled="saving">
                  <div class="editor-title">
                    {{
                      mode === 'new' ? '新增飞书机器人' : `编辑「${selected?.name ?? form.name}」`
                    }}
                  </div>
                  <p v-if="restored" class="draft-hint">
                    已恢复本账号的配置草稿；Webhook 密钥需重新输入。
                  </p>
                  <div v-if="conflicted" class="error conflict" role="alert">
                    <p>其他页面已更新此配置，你的草稿已保留。</p>
                    <button type="button" class="ghost" @click="restoreServer">
                      使用服务器配置</button
                    ><button type="button" class="ghost" @click="rebaseDraft">
                      保留草稿，基于最新版本保存
                    </button>
                  </div>
                  <label
                    ><span>配置名称</span
                    ><input v-model="form.name" maxlength="64" required placeholder="例如 WAO 主群"
                  /></label>
                  <label
                    ><span>用途说明</span
                    ><textarea
                      v-model="form.description"
                      maxlength="255"
                      rows="2"
                      placeholder="例如 系统通知、群聊快速收集"
                    />
                  </label>
                  <label
                    ><span>Webhook 地址</span
                    ><input
                      v-model="form.url"
                      type="password"
                      maxlength="1024"
                      autocomplete="new-password"
                      :placeholder="
                        mode === 'new'
                          ? '粘贴飞书自定义机器人 Webhook 地址'
                          : '留空则保留已加密地址'
                      "
                    /><small v-if="selected"
                      >当前：<code>{{ selected.urlMask }}</code></small
                    ></label
                  >
                  <label
                    ><span>群聊收集用途</span
                    ><select v-model="form.captureKind">
                      <option value="NONE">仅发送通知</option>
                      <option value="TASK">@ 后创建待办</option>
                      <option value="NOTE">@ 后创建小记</option>
                    </select></label
                  >
                  <template v-if="form.captureKind !== 'NONE'"
                    ><label
                      ><span>机器人 @ 名称</span
                      ><input
                        v-model="form.mentionName"
                        maxlength="64"
                        :placeholder="form.captureKind === 'TASK' ? '任务待办' : '小记创作'"
                      /><small>填写群中实际名称；有稳定标识时优先按标识匹配。</small></label
                    ><label
                      ><span>机器人稳定标识（可选）</span
                      ><input
                        v-model="form.mentionId"
                        maxlength="128"
                        placeholder="飞书 @ 元数据中的机器人标识"
                    /></label>
                    <div class="info">
                      <strong>按消息发送者归属</strong>
                      <p>
                        待办创建人和负责人、小记作者均使用发送者的绑定账号。未绑定或身份冲突时拒绝保存。
                      </p>
                      <button type="button" class="text-button" @click="showReceiver">
                        配置接收应用与目标群
                      </button>
                      <template
                        v-if="
                          activity?.receiver.appConfigured &&
                          activity?.receiver.chatConfigured &&
                          (form.appId !== activity.receiver.appId ||
                            form.chatId !== activity.receiver.chatId)
                        "
                      >
                        <p>此机器人尚未关联当前接收应用与群。</p>
                        <button type="button" class="text-button" @click="attachReceiver">
                          关联当前接收配置
                        </button>
                      </template>
                    </div></template
                  >
                  <label class="check-row"
                    ><input v-model="form.enabled" type="checkbox" /><span
                      ><b>启用此机器人</b
                      ><small>停用后通知和关联收集暂停；仍可手动发送测试。</small></span
                    ></label
                  >
                  <label class="check-row"
                    ><input
                      v-model="form.systemDefault"
                      type="checkbox"
                      :disabled="selected?.systemDefault"
                    /><span
                      ><b>作为系统默认通知</b
                      ><small>保持现有通知引用，具体使用情况见下方。</small></span
                    ></label
                  >
                  <p v-if="storageError" class="error" role="alert">
                    本机草稿保存失败，请保存后再离开。
                  </p>
                  <p v-if="saveError" class="error" role="alert">{{ saveError }}</p>
                  <footer class="actions">
                    <span>{{ saving ? '保存中…' : dirty ? '有待保存修改' : '已保存' }}</span
                    ><button
                      v-if="selected"
                      type="button"
                      class="ghost"
                      :disabled="saving"
                      @click="restoreServer"
                    >
                      使用服务器配置</button
                    ><button
                      class="primary"
                      :disabled="saving || conflicted || (!dirty && mode === 'edit')"
                    >
                      {{ saving ? '保存中…' : '保存机器人配置' }}
                    </button>
                  </footer>
                </fieldset>
              </form>
              <div v-else class="records">
                <h3>发送记录</h3>
                <p v-if="!activity?.recentSends.length" class="muted">
                  {{
                    activityLoading ? '正在读取…' : activityError ? '记录读取失败' : '暂无发送记录'
                  }}
                </p>
                <article v-for="(row, index) in activity?.recentSends" :key="index" class="record">
                  <span
                    >{{ row.kind === 'TEST' ? '发送测试' : '业务通知' }} ·
                    {{ labels[row.status] ?? row.status }}</span
                  ><time>{{ row.occurredAt }}</time
                  ><small v-if="row.errorCode" class="error">{{
                    errors[row.errorCode] ?? '发送失败，请检查配置后重试'
                  }}</small>
                </article>
                <h3>群聊收集记录</h3>
                <p v-if="!activity?.recentCaptures.length" class="muted">
                  {{ activityError ? '记录读取失败' : '暂无真实 @ 收集记录' }}
                </p>
                <article
                  v-for="row in activity?.recentCaptures"
                  :key="row.messageId"
                  class="record"
                >
                  <span
                    >{{ kindLabel(row.captureKind) }} · {{ labels[row.status] ?? row.status }}</span
                  ><time>{{ row.occurredAt }}</time
                  ><small v-if="row.errorCode" class="error">{{
                    errors[row.errorCode] ?? '收集未成功，请核对绑定和接入配置'
                  }}</small
                  ><RouterLink
                    v-if="row.targetId && ['CREATED', 'UPDATED'].includes(row.status)"
                    :to="
                      row.captureKind === 'NOTE'
                        ? `/notes?note=${encodeURIComponent(row.targetId)}`
                        : `/todo?task=${encodeURIComponent(row.targetId)}`
                    "
                    >查看保存结果</RouterLink
                  >
                </article>
              </div>
              <div v-if="selected" class="usage">
                <h3>被哪些功能使用</h3>
                <p v-if="activityLoading" role="status">正在核对引用与接入…</p>
                <div v-else-if="activityError" class="error" role="alert">
                  {{ activityError
                  }}<button type="button" class="text-button" @click="loadActivity">
                    重新读取
                  </button>
                </div>
                <template v-else-if="activity"
                  ><ul v-if="activity.usages.length">
                    <li v-for="use in activity.usages" :key="use.kind">
                      {{ use.label }}<span v-if="use.count > 1"> · {{ use.count }} 项</span>
                    </li>
                  </ul>
                  <p v-else class="muted">暂无已登记的功能引用</p>
                  <div
                    v-if="selected.captureKind && selected.captureKind !== 'NONE'"
                    class="receiver-status"
                  >
                    <p>{{ activity.receiver.enabled ? '群聊收集已启用' : '群聊收集已暂停' }}</p>
                    <p>
                      接收群：{{
                        activity.receiver.chatName || activity.receiver.chatId || '尚未配置'
                      }}
                    </p>
                    <p>
                      {{
                        activity.receiver.callbackVerified ? '事件认证已配置' : '事件认证尚未配置'
                      }}
                      · {{ captureVerified ? '已有真实收集成功记录' : '群聊收集尚未验证' }}
                    </p>
                    <small
                      >轮询默认每
                      {{ activity.receiver.pollIntervalMinutes }}
                      分钟兜底；发送测试不代表收集已验证。</small
                    >
                  </div></template
                >
                <div class="bot-actions">
                  <button
                    type="button"
                    class="ghost"
                    :disabled="!!testingId || !!deletingId || saving"
                    @click="sendTest(selected)"
                  >
                    {{ testingId === selected.id ? '发送中…' : '发送测试' }}</button
                  ><button
                    type="button"
                    class="ghost danger"
                    :disabled="!!deletingId || !!testingId || saving"
                    @click="remove(selected)"
                  >
                    {{ deletingId === selected.id ? '核对影响中…' : '删除配置' }}
                  </button>
                </div>
              </div>
            </template>
            <div v-else class="empty">
              <strong>选择机器人查看配置</strong
              ><span>也可先配置接收应用与群，再接入现有机器人。</span>
            </div>
          </div>
        </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.webhook-panel {
  color: var(--wao-text);
  min-width: 0;
}
fieldset {
  border: 0;
  margin: 0;
  padding: 0;
  min-width: 0;
  display: grid;
  gap: 16px;
}
.bot-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 18px 0;
}
.bot-header > div {
  flex: 1;
  min-width: 0;
}
h2 {
  margin: 0;
  font-size: 20px;
}
h3 {
  font-size: 14px;
  margin: 0 0 10px;
}
.bot-header p,
.muted {
  color: var(--wao-text-3);
  font-size: 12px;
}
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}
.search {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  padding: 0 12px;
  background: var(--wao-bg-card);
}
.search input {
  border: 0;
  outline: none;
  background: none;
  color: var(--wao-text);
  width: 100%;
  height: 38px;
  min-width: 0;
}
.config-layout {
  display: grid;
  grid-template-columns: minmax(220px, 300px) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
.config-list {
  display: grid;
  gap: 10px;
}
.config-row {
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
}
.config-row.selected {
  border-color: var(--wao-primary);
  background: var(--wao-primary-soft);
}
button {
  font: inherit;
  cursor: pointer;
}
button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.config-select {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  width: 100%;
  border: 0;
  background: none;
  padding: 15px;
  text-align: left;
  color: inherit;
}
.bot-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 20px;
}
.config-summary {
  display: grid;
  gap: 5px;
  min-width: 0;
}
.config-summary small {
  font-size: 11px;
  color: var(--wao-text-3);
  overflow-wrap: anywhere;
}
.name-line {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}
.name-line strong {
  overflow-wrap: anywhere;
}
.name-line em {
  font-size: 10px;
  font-style: normal;
  border-radius: 8px;
  padding: 2px 6px;
  color: var(--wao-success-strong);
  background: var(--wao-success-bg);
}
.detail {
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  min-width: 0;
  overflow: hidden;
}
.detail-tabs {
  display: flex;
  gap: 16px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--wao-line);
  flex-wrap: wrap;
}
.detail-tabs button {
  border: 0;
  background: none;
  color: var(--wao-text-3);
  padding: 6px 0;
  font-size: 13px;
}
.detail-tabs button[aria-pressed='true'] {
  color: var(--wao-primary-text);
  font-weight: 650;
}
.editor,
.intro,
.records {
  padding: 22px;
  display: grid;
  gap: 14px;
}
.editor-title {
  font-size: 15px;
  font-weight: 650;
}
.editor label:not(.check-row) {
  display: grid;
  gap: 7px;
  font-size: 12px;
  color: var(--wao-text-2);
}
input,
textarea,
select {
  box-sizing: border-box;
  font: inherit;
  color: var(--wao-text);
  max-width: 100%;
}
.editor input:not([type='checkbox']),
textarea,
select {
  width: 100%;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-sub);
  padding: 9px 11px;
  outline: none;
}
.editor input:not([type='checkbox']),
select {
  min-height: 40px;
}
.toolbar select {
  width: auto;
  max-width: 140px;
}
.editor input:focus,
textarea:focus,
select:focus {
  border-color: var(--wao-primary);
}
.editor small,
code {
  font-size: 11px;
  color: var(--wao-text-3);
  overflow-wrap: anywhere;
}
.check-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  font-size: 12px;
}
.check-row input {
  accent-color: var(--wao-primary);
  margin-top: 3px;
}
.check-row span {
  display: grid;
  gap: 4px;
}
.primary,
.ghost {
  border: 1px solid var(--wao-line);
  min-height: 36px;
  padding: 7px 12px;
  border-radius: 9px;
  white-space: normal;
  background: none;
  color: var(--wao-text-2);
}
.primary {
  background: var(--wao-primary);
  color: white;
  border-color: transparent;
}
.primary.small {
  display: flex;
  align-items: center;
  gap: 5px;
}
.danger,
.error {
  color: var(--wao-danger-strong) !important;
}
.error {
  font-size: 12px;
  overflow-wrap: anywhere;
}
.info,
.draft-hint,
.receiver-status {
  padding: 13px;
  border-radius: 10px;
  background: var(--wao-primary-soft);
  font-size: 12px;
  line-height: 1.65;
}
.info p {
  margin: 6px 0;
}
.draft-hint {
  margin: 0;
  color: var(--wao-primary-text);
}
.text-button {
  border: 0;
  background: none;
  color: var(--wao-primary-text);
  padding: 4px 0;
}
.usage {
  padding: 20px;
  border-top: 1px solid var(--wao-line);
  font-size: 12px;
}
.usage ul {
  padding-left: 20px;
  line-height: 2;
}
.bot-actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}
.actions {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 0 0;
  background: var(--wao-bg-card);
}
.actions > span {
  margin-right: auto;
  font-size: 11px;
  color: var(--wao-text-3);
}
.record {
  display: grid;
  gap: 6px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  padding: 12px;
  font-size: 12px;
}
.record time {
  font-size: 11px;
  color: var(--wao-text-3);
}
a {
  color: var(--wao-primary-text);
}
.empty,
.state {
  display: grid;
  gap: 10px;
  justify-items: center;
  place-content: center;
  min-height: 180px;
  padding: 20px;
  text-align: center;
  font-size: 12px;
  color: var(--wao-text-3);
}
.state strong {
  font-size: 16px;
  color: var(--wao-text);
}
.inline-load-error {
  padding: 12px;
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  margin-bottom: 12px;
}
.conflict .ghost {
  margin-right: 8px;
}
@media (max-width: 800px) {
  .config-layout {
    grid-template-columns: 1fr;
  }
  .config-list {
    display: flex;
    overflow-x: auto;
    gap: 8px;
    padding-bottom: 6px;
  }
  .config-row {
    flex: 0 0 230px;
  }
  .bot-header {
    flex-wrap: wrap;
  }
  .bot-header > div {
    flex-basis: 100%;
  }
  .toolbar {
    flex-wrap: wrap;
  }
  .toolbar .search {
    flex-basis: 100%;
  }
  .editor,
  .intro,
  .records {
    padding: 16px;
  }
  .actions {
    flex-wrap: wrap;
  }
  .actions > span {
    flex-basis: 100%;
  }
  .detail-tabs {
    padding: 10px 16px;
  }
}
</style>
