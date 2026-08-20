<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { toast } from '@/composables/toast'
import {
  createWebhookEndpoint,
  deleteWebhookEndpoint,
  fetchWebhookEndpoints,
  testWebhookEndpoint,
  updateWebhookEndpoint,
  type FeishuWebhookEndpoint,
  type FeishuWebhookEndpointInput,
} from '@/api/system'

type EditorMode = 'none' | 'new' | 'edit'

const endpoints = ref<FeishuWebhookEndpoint[]>([])
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const deletingId = ref('')
const testingId = ref('')
const editorMode = ref<EditorMode>('none')
const selectedId = ref('')
const form = reactive<FeishuWebhookEndpointInput>({
  name: '',
  description: '',
  url: '',
  enabled: true,
  systemDefault: false,
})

const selected = computed(() => endpoints.value.find((item) => item.id === selectedId.value))
const baseline = computed<FeishuWebhookEndpointInput>(() => {
  if (editorMode.value !== 'edit' || !selected.value) {
    return { name: '', description: '', url: '', enabled: true, systemDefault: endpoints.value.length === 0 }
  }
  return {
    name: selected.value.name,
    description: selected.value.description,
    url: '',
    enabled: selected.value.enabled,
    systemDefault: selected.value.systemDefault,
  }
})
const dirty = computed(() => editorMode.value !== 'none'
  && JSON.stringify({ ...form, url: form.url?.trim() ?? '' }) !== JSON.stringify(baseline.value))

function sortEndpoints(items: FeishuWebhookEndpoint[]) {
  return [...items].sort((left, right) => {
    if (left.systemDefault !== right.systemDefault) return left.systemDefault ? -1 : 1
    return right.updatedAt.localeCompare(left.updatedAt)
  })
}

function assignForm(value: FeishuWebhookEndpointInput) {
  Object.assign(form, value)
}

function confirmDiscardDraft() {
  return !dirty.value || window.confirm('当前 Webhook 配置尚未保存，确认放弃这些修改吗？')
}

function confirmLeave() {
  return confirmDiscardDraft()
}

function protectBrowserLeave(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

function edit(item: FeishuWebhookEndpoint, skipConfirm = false) {
  if (!skipConfirm && !confirmDiscardDraft()) return
  selectedId.value = item.id
  editorMode.value = 'edit'
  assignForm({
    name: item.name,
    description: item.description,
    url: '',
    enabled: item.enabled,
    systemDefault: item.systemDefault,
  })
}

function startCreate() {
  if (!confirmDiscardDraft()) return
  selectedId.value = ''
  editorMode.value = 'new'
  assignForm({
    name: '',
    description: '',
    url: '',
    enabled: true,
    systemDefault: endpoints.value.length === 0,
  })
}

function cancelEdit() {
  if (!confirmDiscardDraft()) return
  const fallback = selected.value ?? endpoints.value[0]
  if (fallback) edit(fallback, true)
  else editorMode.value = 'none'
}

async function load(): Promise<boolean> {
  if (loading.value && endpoints.value.length) return false
  loading.value = true
  loadError.value = ''
  try {
    endpoints.value = sortEndpoints(await fetchWebhookEndpoints())
    const target = endpoints.value.find((item) => item.id === selectedId.value) ?? endpoints.value[0]
    if (target) edit(target, true)
    else editorMode.value = 'none'
    return true
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : 'Webhook 配置读取失败'
    return false
  } finally {
    loading.value = false
  }
}

async function save() {
  const name = form.name.trim()
  const description = form.description.trim()
  const url = form.url?.trim() ?? ''
  if (!name) {
    toast('请填写配置名称', 'ri-error-warning-line')
    return
  }
  if ((editorMode.value === 'new' || (editorMode.value === 'edit' && selected.value?.urlSet === false)) && !url) {
    toast(editorMode.value === 'new'
      ? '新增配置时必须填写 Webhook 地址'
      : '当前地址无法安全使用，请填写新的 Webhook 地址', 'ri-error-warning-line')
    return
  }
  saving.value = true
  const creating = editorMode.value === 'new'
  try {
    const payload: FeishuWebhookEndpointInput = {
      name,
      description,
      url: url || undefined,
      enabled: form.enabled,
      systemDefault: form.systemDefault,
    }
    const saved = editorMode.value === 'edit' && selectedId.value
      ? await updateWebhookEndpoint(selectedId.value, payload)
      : await createWebhookEndpoint(payload)
    const next = endpoints.value.filter((item) => item.id !== saved.id).map((item) => ({
      ...item,
      systemDefault: saved.systemDefault ? false : item.systemDefault,
    }))
    next.push(saved)
    endpoints.value = sortEndpoints(next)
    edit(saved, true)
    toast(creating ? '飞书机器人配置已新增' : '飞书机器人配置已保存', 'ri-check-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : 'Webhook 配置保存失败', 'ri-error-warning-line')
  } finally {
    saving.value = false
  }
}

async function remove(item: FeishuWebhookEndpoint) {
  if (!confirmDiscardDraft()) return
  if (!window.confirm(`确认删除「${item.name}」吗？使用该配置的通知需要改选其它机器人。`)) return
  deletingId.value = item.id
  try {
    await deleteWebhookEndpoint(item.id)
    endpoints.value = endpoints.value.filter((endpoint) => endpoint.id !== item.id)
    // 删除默认项时后端会提升一项剩余配置，重新读取才能拿到真实默认状态。
    selectedId.value = ''
    editorMode.value = 'none'
    const refreshed = await load()
    toast(refreshed
      ? `已删除「${item.name}」`
      : `已删除「${item.name}」，但最新默认状态读取失败，请重试`,
    refreshed ? 'ri-delete-bin-line' : 'ri-error-warning-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '删除失败', 'ri-error-warning-line')
  } finally {
    deletingId.value = ''
  }
}

async function sendTest(item: FeishuWebhookEndpoint) {
  testingId.value = item.id
  try {
    await testWebhookEndpoint(item.id)
    toast(`测试消息已通过「${item.name}」发出`, 'ri-send-plane-2-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '测试消息发送失败', 'ri-error-warning-line')
  } finally {
    testingId.value = ''
  }
}

onMounted(() => {
  window.addEventListener('beforeunload', protectBrowserLeave)
  load()
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', protectBrowserLeave))
defineExpose({ confirmLeave })
</script>

<template>
  <section class="webhook-panel">
    <h1>Webhook配置</h1>
    <div class="webhook-card">
      <header>
        <div>
          <strong>飞书机器人配置</strong>
          <p>集中维护自定义机器人，供系统通知和后续其它功能复用。</p>
        </div>
        <span v-if="!loading && !loadError" class="count">{{ endpoints.length }} 个配置</span>
        <button type="button" class="primary small" :disabled="loading || saving" @click="startCreate">
          <i class="ri-add-line"></i>新增配置
        </button>
      </header>

      <div v-if="loading && !endpoints.length" class="state"><i class="ri-loader-4-line spin"></i>正在读取 Webhook 配置…</div>
      <div v-else-if="loadError && !endpoints.length" class="state error" role="alert">
        <span>{{ loadError }}</span>
        <button type="button" class="ghost" @click="load">重新读取</button>
      </div>
      <template v-else>
        <div v-if="loadError" class="inline-load-error" role="alert">
          <span><i class="ri-error-warning-line"></i>{{ loadError }}；当前列表可能不是最新默认状态。</span>
          <button type="button" class="ghost" :disabled="loading" @click="load">
            {{ loading ? '读取中…' : '重新读取' }}
          </button>
        </div>
        <div class="config-layout">
        <div class="config-list">
          <article
            v-for="item in endpoints"
            :key="item.id"
            class="config-row"
            :class="{ selected: editorMode === 'edit' && selectedId === item.id }"
          >
            <button type="button" class="config-select" @click="edit(item)">
              <span class="bot-icon"><i class="ri-robot-2-line"></i></span>
              <span class="config-summary">
                <span class="name-line">
                  <strong>{{ item.name }}</strong>
                  <em v-if="item.systemDefault">系统默认</em>
                  <em v-else-if="!item.enabled" class="off">已停用</em>
                </span>
                <small>{{ item.description || '暂无用途说明' }}</small>
                <code>{{ item.urlMask }}</code>
              </span>
            </button>
            <button
              type="button"
              class="icon-button"
              :disabled="testingId === item.id || deletingId === item.id"
              :title="`通过 ${item.name} 发送测试消息`"
              @click="sendTest(item)"
            ><i :class="testingId === item.id ? 'ri-loader-4-line spin' : 'ri-send-plane-line'"></i></button>
            <button
              type="button"
              class="icon-button danger"
              :disabled="deletingId === item.id || testingId === item.id"
              :title="`删除 ${item.name}`"
              @click="remove(item)"
            ><i :class="deletingId === item.id ? 'ri-loader-4-line spin' : 'ri-delete-bin-line'"></i></button>
          </article>
          <div v-if="!endpoints.length" class="empty">
            <i class="ri-robot-2-line"></i>
            <strong>还没有机器人配置</strong>
            <span>新增后，第一项会自动成为系统通知的默认机器人。</span>
          </div>
        </div>

        <form v-if="editorMode !== 'none'" class="editor" @submit.prevent="save">
          <div class="editor-title">{{ editorMode === 'new' ? '新增飞书机器人' : `编辑「${selected?.name ?? form.name}」` }}</div>
          <label>
            <span>配置名称</span>
            <input v-model="form.name" maxlength="64" required placeholder="例如 WAO 主群" />
          </label>
          <label>
            <span>用途说明</span>
            <textarea v-model="form.description" maxlength="255" rows="2" placeholder="例如 系统与项目通知"></textarea>
          </label>
          <label>
            <span>Webhook 地址</span>
            <input
              v-model="form.url"
              type="password"
              maxlength="1024"
              :required="editorMode === 'new' || (editorMode === 'edit' && !selected?.urlSet)"
              autocomplete="new-password"
              :placeholder="editorMode === 'new'
                ? '粘贴飞书自定义机器人 Webhook 地址'
                : selected?.urlSet ? '留空则保留已加密地址' : '当前地址不可用，请粘贴新的官方地址'"
            />
            <small v-if="editorMode === 'edit' && selected">当前：<code>{{ selected.urlMask }}</code></small>
          </label>
          <label class="check-row">
            <input v-model="form.enabled" type="checkbox" />
            <span><b>启用此配置</b><small>停用后不会被业务主动发送，仍可手动测试连接。</small></span>
          </label>
          <label class="check-row">
            <input
              v-model="form.systemDefault"
              type="checkbox"
              :disabled="editorMode === 'edit' && selected?.systemDefault"
            />
            <span><b>作为系统默认通知</b><small>任务、评论和 AI 运行等现有通知发送到这里。</small></span>
          </label>
          <div class="actions">
            <button type="button" class="ghost" :disabled="saving" @click="cancelEdit">取消</button>
            <button class="primary" :disabled="saving || (!dirty && editorMode === 'edit')">
              {{ saving ? '保存中…' : !dirty && editorMode === 'edit' ? '已保存' : '保存配置' }}
            </button>
          </div>
        </form>
        <div v-else class="no-selection">选择一项配置编辑，或新增飞书机器人。</div>
        </div>
      </template>

      <p class="security-hint">
        仅接受飞书官方 HTTPS 自定义机器人地址。地址使用 AES-256-GCM 加密保存，页面和接口始终只显示脱敏值；删除系统默认项时会自动选择一项剩余配置接管通知。
      </p>
      <p class="channel-hint">
        <i class="ri-information-line"></i>
        今日节奏里显示的“WAO”群来自“自动化 → 飞书群聊同步知识库”的自建应用群列表，不是这里的自定义机器人 Webhook；两条真实通知通道分别维护。
      </p>
    </div>
  </section>
</template>

<style scoped>
.webhook-panel h1 { margin: 0 0 18px; color: var(--text, #20232d); font-size: 22px; }
.webhook-card { padding: 22px; border: 1px solid var(--line, #e4e6ec); border-radius: 14px; background: var(--surface, #fff); }
header { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
header > div { min-width: 0; }
header strong { color: var(--text, #20232d); font-size: 15px; }
header p { margin: 5px 0 0; color: var(--text-3, #7b7f8d); font-size: 12px; }
.count { margin-left: auto; color: var(--text-3, #7b7f8d); font-size: 12px; white-space: nowrap; }
button { border: 0; font: inherit; cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: .55; }
.primary, .ghost { min-height: 36px; padding: 0 15px; border-radius: 8px; }
.primary { background: var(--primary, #445bd4); color: #fff; }
.primary.small { display: inline-flex; align-items: center; gap: 5px; min-height: 32px; white-space: nowrap; }
.ghost { border: 1px solid var(--line, #dfe2e9); background: transparent; color: var(--text-2, #525765); }
.state { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 150px; color: var(--text-3, #777c89); }
.state.error { flex-direction: column; color: #b33b3b; }
.inline-load-error { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; padding: 9px 11px; border-radius: 8px; background: #fff3e5; color: #8b5b19; font-size: 11px; }
.inline-load-error span { min-width: 0; flex: 1; }
.inline-load-error i { margin-right: 5px; }
.inline-load-error .ghost { min-height: 30px; white-space: nowrap; }
.config-layout { display: grid; grid-template-columns: minmax(300px, .9fr) minmax(340px, 1.1fr); gap: 18px; align-items: start; }
.config-list { display: grid; gap: 8px; }
.config-row { display: grid; grid-template-columns: minmax(0, 1fr) 36px 36px; align-items: stretch; border: 1px solid var(--line, #e3e5eb); border-radius: 10px; background: var(--surface-2, #fafbfc); overflow: hidden; }
.config-row.selected { border-color: var(--primary, #445bd4); box-shadow: 0 0 0 1px color-mix(in srgb, var(--primary, #445bd4) 20%, transparent); }
.config-select { display: flex; min-width: 0; gap: 11px; padding: 12px; background: transparent; text-align: left; }
.bot-icon { display: grid; flex: 0 0 34px; width: 34px; height: 34px; place-items: center; border-radius: 9px; background: #e9edfb; color: #4055c8; font-size: 17px; }
.config-summary { display: grid; min-width: 0; gap: 4px; }
.name-line { display: flex; align-items: center; gap: 6px; min-width: 0; }
.name-line strong { overflow: hidden; color: var(--text, #20232d); text-overflow: ellipsis; white-space: nowrap; }
.name-line em { padding: 2px 6px; border-radius: 999px; background: #e7f6ef; color: #16805c; font-size: 10px; font-style: normal; white-space: nowrap; }
.name-line em.off { background: #eceef2; color: #767b87; }
.config-summary small { overflow: hidden; color: var(--text-3, #7a7e8a); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
code { overflow: hidden; color: var(--text-3, #6e7380); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.icon-button { display: grid; place-items: center; border-left: 1px solid var(--line, #e3e5eb); background: transparent; color: var(--text-3, #737887); }
.icon-button:hover { background: #eef1fb; color: var(--primary, #445bd4); }
.icon-button.danger:hover { background: #fff0f0; color: #bd3d3d; }
.editor { display: grid; gap: 13px; padding: 17px; border: 1px solid var(--line, #e3e5eb); border-radius: 10px; background: var(--surface-2, #fafbfc); }
.editor-title { color: var(--text, #20232d); font-size: 15px; font-weight: 650; }
.editor label:not(.check-row) { display: grid; gap: 6px; color: var(--text-2, #555a67); font-size: 12px; }
.editor input:not([type='checkbox']), .editor textarea { box-sizing: border-box; width: 100%; border: 1px solid var(--line, #dfe2e9); border-radius: 8px; outline: none; background: var(--surface, #fff); color: var(--text, #20232d); font: inherit; }
.editor input:not([type='checkbox']) { height: 38px; padding: 0 11px; }
.editor textarea { resize: vertical; padding: 9px 11px; }
.editor input:focus, .editor textarea:focus { border-color: var(--primary, #445bd4); box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary, #445bd4) 12%, transparent); }
.editor label > small { color: var(--text-3, #7a7e8a); }
.check-row { display: flex; align-items: flex-start; gap: 9px; cursor: pointer; }
.check-row input { margin-top: 3px; accent-color: var(--primary, #445bd4); }
.check-row span { display: grid; gap: 2px; color: var(--text-2, #4f5462); font-size: 12px; }
.check-row small { color: var(--text-3, #7a7e8a); }
.actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 3px; }
.empty, .no-selection { display: grid; min-height: 180px; place-content: center; justify-items: center; gap: 7px; border: 1px dashed var(--line, #dfe2e9); border-radius: 10px; color: var(--text-3, #7a7e8a); text-align: center; }
.empty i { font-size: 28px; }
.empty strong { color: var(--text-2, #525765); }
.empty span, .no-selection { font-size: 12px; }
.security-hint { margin: 16px 0 0; color: var(--text-3, #7a7e8a); font-size: 11px; line-height: 1.7; }
.channel-hint { display: flex; align-items: flex-start; gap: 6px; margin: 8px 0 0; padding: 9px 11px; border-radius: 8px; background: #f2f5fc; color: var(--text-2, #5a6070); font-size: 11px; line-height: 1.6; }
.channel-hint i { margin-top: 1px; color: var(--primary, #445bd4); font-size: 14px; }
.spin { display: inline-block; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 820px) {
  .webhook-card { padding: 16px; }
  header { align-items: flex-start; flex-wrap: wrap; }
  .count { margin-left: 0; }
  header .primary { margin-left: auto; }
  .config-layout { grid-template-columns: 1fr; }
}
</style>
