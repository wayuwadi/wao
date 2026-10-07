<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
import { copyToClipboard } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import FeishuDailyArchiveSettings from './FeishuDailyArchiveSettings.vue'
import {
  fetchChatSyncConfig,
  fetchQuickCollectConfig,
  fetchFeishuChats,
  saveChatSyncConfig,
  saveQuickCollectConfig,
  type ChatSyncConfig,
  type QuickCollectConfig,
  type FeishuChat,
} from '@/api/system'

const emit = defineEmits<{ saved: [] }>()
const session = useSessionStore(),
  data = useDataStore(),
  owner = session.userId
const cfg = ref<ChatSyncConfig | null>(null),
  quick = ref<QuickCollectConfig | null>(null),
  chats = ref<FeishuChat[]>([])
const loading = ref(true),
  busy = ref(false),
  error = ref(''),
  loaded = ref(false),
  storageError = ref(false),
  restored = ref(false)
const form = reactive({
  appId: '',
  chatId: '',
  chatName: '',
  kbId: '',
  enabled: false,
  projectId: '',
})
const archiveSettings = ref<InstanceType<typeof FeishuDailyArchiveSettings> | null>(null)
const appSecret = ref(''),
  verificationToken = ref(''),
  encryptKey = ref('')
const initial = ref(JSON.stringify(form)),
  draftKey = `wao-feishu-receiver-draft-v1:${owner}`
const dirty = computed(
  () =>
    JSON.stringify(form) !== initial.value ||
    !!appSecret.value ||
    !!verificationToken.value ||
    !!encryptKey.value,
)
const eventUrl = `${['localhost', '127.0.0.1', '[::1]'].includes(location.hostname) ? 'https://wao.wayuwa.cn' : location.origin}/api/feishu/events`
let disposed = false
function alive() {
  return !disposed && owner === session.userId
}
function retainDraft() {
  try {
    if (dirty.value) localStorage.setItem(draftKey, JSON.stringify(form))
    else localStorage.removeItem(draftKey)
    storageError.value = false
  } catch {
    storageError.value = true
  }
}
watch(
  form,
  () => {
    if (loaded.value && alive()) retainDraft()
  },
  { deep: true },
)
function confirmLeave() {
  if (archiveSettings.value && !archiveSettings.value.confirmLeave()) return false
  if (busy.value) {
    toast('接收配置正在保存，请稍后再离开')
    return false
  }
  return (
    !dirty.value ||
    !(appSecret.value || verificationToken.value || encryptKey.value || storageError.value) ||
    window.confirm(
      '应用密钥与事件认证密钥只保留在当前页面；确认离开并放弃未保存的密钥吗？其他配置草稿会保留。',
    )
  )
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (
    (dirty.value &&
      (appSecret.value || verificationToken.value || encryptKey.value || storageError.value)) ||
    busy.value
  ) {
    event.preventDefault()
    event.returnValue = ''
  }
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [c, q] = await Promise.all([fetchChatSyncConfig(), fetchQuickCollectConfig()])
    if (!alive()) return
    cfg.value = c
    quick.value = q
    if (!loaded.value) {
      Object.assign(form, {
        appId: c.appId,
        chatId: c.chatId,
        chatName: c.chatName,
        kbId: c.kbId,
        enabled: q.enabled,
        projectId: q.projectId,
      })
      initial.value = JSON.stringify(form)
      try {
        const draft = JSON.parse(localStorage.getItem(draftKey) ?? 'null')
        if (
          draft &&
          typeof draft.appId === 'string' &&
          typeof draft.chatId === 'string' &&
          typeof draft.chatName === 'string' &&
          typeof draft.enabled === 'boolean' &&
          typeof draft.projectId === 'string'
        ) {
          Object.assign(form, {
            appId: draft.appId,
            chatId: draft.chatId,
            chatName: draft.chatName,
            kbId: typeof draft.kbId === 'string' ? draft.kbId : c.kbId,
            enabled: draft.enabled,
            projectId: draft.projectId,
          })
          restored.value = true
        }
      } catch {
        storageError.value = true
      }
    }
    loaded.value = true
  } catch (e) {
    if (alive()) error.value = e instanceof Error ? e.message : '接收配置读取失败'
  } finally {
    if (alive()) loading.value = false
  }
}
async function listChats() {
  if (form.appId !== cfg.value?.appId || appSecret.value) {
    error.value = '请先保存应用配置，再读取它所在的群'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const rows = await fetchFeishuChats()
    if (alive()) {
      chats.value = rows
      if (!rows.length) error.value = '该应用暂未加入可读取的群'
    }
  } catch (e) {
    if (alive()) error.value = e instanceof Error ? e.message : '群列表读取失败'
  } finally {
    if (alive()) busy.value = false
  }
}
function pickChat(id: string) {
  const chat = chats.value.find((row) => row.chatId === id)
  if (chat) {
    form.chatId = chat.chatId
    form.chatName = chat.name
  }
}
async function save() {
  if (!cfg.value || !quick.value) return false
  error.value = ''
  if (
    form.enabled &&
    (!form.appId.trim() || (!cfg.value.appSecretSet && !appSecret.value) || !form.chatId)
  ) {
    error.value = '启用 @ 快捷记录前，请先配置真实飞书应用、密钥与目标群'
    return false
  }
  busy.value = true
  let appSaved = false
  try {
    const [latestApp, latestQuick] = await Promise.all([
      fetchChatSyncConfig(),
      fetchQuickCollectConfig(),
    ])
    if (!alive()) return false
    if (
      JSON.stringify(latestApp) !== JSON.stringify(cfg.value) ||
      JSON.stringify(latestQuick) !== JSON.stringify(quick.value)
    ) {
      error.value = '共享接收配置已有更新，你的草稿已保留。请读取最新配置，再核对后保存。'
      return false
    }

    const c = await saveChatSyncConfig({
      appId: form.appId.trim(),
      appSecret: appSecret.value.trim() || undefined,
      chatId: form.chatId,
      chatName: form.chatName,
      kbId: form.kbId,
      senders: cfg.value.senders,
    })
    if (!alive()) return false
    cfg.value = c
    appSecret.value = ''
    appSaved = true
    const q = await saveQuickCollectConfig({
      enabled: form.enabled,
      projectId: form.projectId,
      verificationToken: verificationToken.value.trim() || undefined,
      encryptKey: encryptKey.value.trim() || undefined,
    })
    if (!alive()) return false
    quick.value = q
    verificationToken.value = ''
    encryptKey.value = ''
    initial.value = JSON.stringify(form)
    restored.value = false
    retainDraft()
    void archiveSettings.value?.reload()
    emit('saved')
    toast('接收应用与群配置已保存', 'ri-check-line')
    return true
  } catch (e) {
    if (alive())
      error.value = `${appSaved ? '应用与群已保存，收集配置保存失败；请重试。' : ''}${e instanceof Error ? e.message : '保存失败，请重试'}`
    return false
  } finally {
    if (alive()) busy.value = false
  }
}
async function copyUrl() {
  toast((await copyToClipboard(eventUrl)) ? '事件接收地址已复制' : '复制失败，请手动选择地址')
}
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  void load()
})
onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('beforeunload', beforeUnload)
})
useSaveBeforeRefresh(async () => {
  if (dirty.value && !(await save())) throw new Error(error.value || '接收配置尚未保存')
})
defineExpose({ confirmLeave })
</script>

<template>
  <section class="receiver-settings" aria-label="接收应用与群配置">
    <h3>接收应用与群</h3>
    <p class="muted">@ 快捷记录与每日归档共用这套应用和目标群，分别控制开关。</p>
    <p v-if="loading" role="status">正在读取接收配置…</p>
    <div v-else-if="!loaded" role="alert">
      <p class="error">{{ error }}</p>
      <button class="ghost" type="button" @click="load">重新读取</button>
    </div>
    <form v-else @submit.prevent="save">
      <fieldset :disabled="busy">
        <p v-if="restored" class="info">已恢复本账号的接收配置草稿；密钥需重新输入。</p>
        <label
          ><span>飞书应用 App ID</span
          ><input v-model="form.appId" placeholder="复用已授权的飞书应用"
        /></label>
        <label
          ><span>应用密钥</span
          ><input
            v-model="appSecret"
            type="password"
            autocomplete="new-password"
            :placeholder="cfg?.appSecretSet ? '已配置，留空保留' : 'App Secret'"
        /></label>
        <div class="group">
          <strong>目标群</strong><span>{{ form.chatName || form.chatId || '尚未选择' }}</span
          ><button
            class="ghost"
            type="button"
            :disabled="busy || !cfg?.appSecretSet"
            @click="listChats"
          >
            读取应用所在的群
          </button>
        </div>
        <label v-if="chats.length"
          ><span>选择真实群聊</span
          ><select
            :value="form.chatId"
            @change="pickChat(($event.target as HTMLSelectElement).value)"
          >
            <option value="">请选择</option>
            <option v-for="chat in chats" :key="chat.chatId" :value="chat.chatId">
              {{ chat.name || chat.chatId }}
            </option>
          </select></label
        >
        <label class="check-row"
          ><input v-model="form.enabled" type="checkbox" /><span
            >启用 @ 快捷记录（待办／小记）</span
          ></label
        >
        <label
          ><span>默认待办项目</span
          ><select v-model="form.projectId">
            <option value="">沿用默认可见项目</option>
            <option v-for="project in data.projects" :key="project.id" :value="project.id">
              {{ project.name }}
            </option>
          </select></label
        >
        <div class="info">
          <strong>事件接收地址</strong><code>{{ eventUrl }}</code
          ><button class="text-button" type="button" @click="copyUrl">复制地址</button>
          <p>
            在飞书开放平台给该应用订阅目标群消息事件 im.message.receive_v1，并配置事件认证；仅订阅 @
            当前应用机器人不能覆盖两个群自定义机器人。
          </p>
          <p>仅通过事件接收，不定时读取群消息或表情。有密钥配置不代表已经收到真实事件。</p>
        </div>
        <label
          ><span>事件 Verification Token</span
          ><input
            v-model="verificationToken"
            type="password"
            autocomplete="new-password"
            :placeholder="quick?.hasVerificationToken ? '已配置，留空保留' : '未配置'"
        /></label>
        <label
          ><span>事件 Encrypt Key</span
          ><input
            v-model="encryptKey"
            type="password"
            autocomplete="new-password"
            :placeholder="quick?.hasEncryptKey ? '已配置，留空保留' : '未配置'"
        /></label>
        <p class="muted">
          仅保存认证过的真实群消息。作者依靠飞书账号绑定，不使用这里的群昵称映射。
        </p>
        <label
          ><span>每日归档知识库</span
          ><select v-model="form.kbId">
            <option value="">请选择真实知识库</option>
            <option v-if="form.kbId && !data.kbById(form.kbId)" :value="form.kbId">
              {{ form.kbId }}（当前配置）
            </option>
            <option v-for="kb in data.kbs" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
          </select></label
        >
        <div v-if="error" class="error" role="alert">
          <p>{{ error }}</p>
          <button v-if="error.includes('已有更新')" type="button" class="ghost" @click="load">
            读取最新配置并保留草稿
          </button>
        </div>
        <p v-if="storageError" class="error" role="alert">本机草稿保存失败，请保存后再离开。</p>
        <footer>
          <span>{{ busy ? '处理中…' : dirty ? '有待保存修改' : '已保存' }}</span
          ><button class="primary" :disabled="busy || !dirty">
            {{ busy ? '处理中…' : '保存接收配置' }}
          </button>
        </footer>
      </fieldset>
    </form>
    <FeishuDailyArchiveSettings ref="archiveSettings" />
  </section>
</template>

<style scoped>
.receiver-settings {
  padding: 22px;
  color: var(--wao-text);
}
h3 {
  font-size: 16px;
  margin: 0 0 10px;
}
.muted {
  font-size: 12px;
  line-height: 1.7;
  color: var(--wao-text-3);
}
form {
  display: grid;
  gap: 16px;
}
fieldset {
  margin: 0;
  padding: 0;
  border: 0;
  min-width: 0;
  display: grid;
  gap: 16px;
}
label {
  display: grid;
  gap: 7px;
  font-size: 12px;
  color: var(--wao-text-2);
}
input:not([type='checkbox']),
select {
  box-sizing: border-box;
  width: 100%;
  height: 40px;
  background: var(--wao-bg-sub);
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  padding: 0 11px;
  color: var(--wao-text);
  font: inherit;
}
.check-row {
  display: flex;
  align-items: center;
  gap: 9px;
}
.check-row input {
  accent-color: var(--wao-primary);
}
button {
  font: inherit;
  cursor: pointer;
}
button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.ghost,
.primary {
  border: 1px solid var(--wao-line);
  min-height: 36px;
  padding: 7px 12px;
  border-radius: 9px;
  background: none;
  color: var(--wao-text-2);
}
.primary {
  background: var(--wao-primary);
  color: white;
  border-color: transparent;
}
.text-button {
  background: none;
  border: 0;
  padding: 4px 0;
  color: var(--wao-primary-text);
}
.group {
  display: grid;
  gap: 8px;
  font-size: 12px;
}
.group .ghost {
  justify-self: start;
}
.info {
  border-radius: 10px;
  background: var(--wao-primary-soft);
  padding: 14px;
  font-size: 12px;
  line-height: 1.65;
}
.info code {
  display: block;
  overflow-wrap: anywhere;
  font-size: 11px;
}
.error {
  font-size: 12px;
  color: var(--wao-danger-strong);
}
footer {
  display: flex;
  gap: 10px;
  align-items: center;
  position: sticky;
  bottom: 0;
  background: var(--wao-bg-card);
  padding-top: 12px;
}
footer > span {
  margin-right: auto;
  color: var(--wao-text-3);
  font-size: 11px;
}
@media (max-width: 800px) {
  .receiver-settings {
    padding: 16px;
  }
  footer {
    flex-wrap: wrap;
  }
}
</style>
