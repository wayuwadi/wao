<script setup lang="ts">
/**
 * 「飞书群聊消息同步知识库」的配置页。
 *
 * 填自建应用的 App ID / Secret → 选机器人所在的群 → 选沉淀到哪个知识库文件夹 →
 * 把群成员的飞书 ID 对上人名（娃哥 / 娃弟 / 机器人通知）。配置存服务端（密钥加密），
 * 之后每天 23:59 由自动化按计划跑；这里也能「立即同步今天」先验证一遍。
 */
import { onMounted, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { toast } from '@/composables/toast'
import {
  fetchChatSyncConfig,
  fetchFeishuChatMembers,
  fetchFeishuChats,
  runChatSync,
  saveChatSyncConfig,
  type ChatSyncConfig,
  type FeishuChat,
  type FeishuSender,
} from '@/api/system'

const emit = defineEmits<{ close: [] }>()

const data = useDataStore()

const loading = ref(true)
const busy = ref(false)
const cfg = ref<ChatSyncConfig | null>(null)
const appId = ref('')
const appSecret = ref('')
const chatId = ref('')
const chatName = ref('')
const kbId = ref('')
const runDay = ref('')
const senders = ref<FeishuSender[]>([])
const chats = ref<FeishuChat[]>([])
const chatsLoaded = ref(false)


onMounted(async () => {
  try {
    const c = await fetchChatSyncConfig()
    cfg.value = c
    appId.value = c.appId
    chatId.value = c.chatId
    chatName.value = c.chatName
    kbId.value = c.kbId
    senders.value = c.senders.map((s) => ({ ...s }))
  } catch (error) {
    toast(error instanceof Error ? error.message : '读取配置失败')
  } finally {
    loading.value = false
  }
})

/** 拉机器人所在的群 —— 需要先把 App ID / Secret 存下来，服务端才有凭据可用。 */
async function loadChats() {
  busy.value = true
  try {
    await persist()
    chats.value = await fetchFeishuChats()
    chatsLoaded.value = true
    if (!chats.value.length) toast('这个应用还没有被拉进任何群')
  } catch (error) { toast(error instanceof Error ? error.message : '获取群列表失败') }
  finally { busy.value = false }
}

function pickChat(c: FeishuChat) {
  chatId.value = c.chatId
  chatName.value = c.name
}

/** 拉群成员并把没映射过的补进列表，用户只要把名字改成「娃哥」这种就行。 */
async function loadMembers() {
  if (!chatId.value) {
    toast('请先选择要同步的群聊')
    return
  }
  busy.value = true
  try {
    await persist()
    const members = await fetchFeishuChatMembers(chatId.value)
    const known = new Map(senders.value.map((s) => [s.id, s]))
    senders.value = members.map((m) => known.get(m.id) ?? { ...m })
    toast(`已读取 ${members.length} 位成员，确认下名字`, 'ri-user-search-line')
  } catch (error) { toast(error instanceof Error ? error.message : '获取群成员失败') }
  finally { busy.value = false }
}

function addSender() {
  senders.value = [...senders.value, { id: '', name: '' }]
}
function removeSender(i: number) {
  senders.value = senders.value.filter((_, j) => j !== i)
}

/** 把当前表单存到服务端；App Secret 留空表示保留已存的那把。 */
async function persist() {
  const saved = await saveChatSyncConfig({
    appId: appId.value.trim(),
    appSecret: appSecret.value.trim() || undefined,
    chatId: chatId.value,
    chatName: chatName.value,
    kbId: kbId.value,
    senders: senders.value.filter((s) => s.id.trim()),
  })
  cfg.value = saved
  appSecret.value = ''
  return saved
}

async function save() {
  busy.value = true
  try {
    await persist()
    toast('飞书群聊同步配置已保存', 'ri-check-line')
  } catch (error) { toast(error instanceof Error ? error.message : '保存失败') }
  finally { busy.value = false }
}

async function runNow() {
  busy.value = true
  try {
    await persist()
    const result = await runChatSync(runDay.value || undefined)
    await data.refreshKnowledge()
    toast(result, 'ri-check-line')
  } catch (error) { toast(error instanceof Error ? error.message : '同步失败') }
  finally { busy.value = false }
}
</script>

<template>
  <div class="mask" @click="emit('close')"></div>
  <div class="modal">
    <div class="head">
      <div class="title">飞书群聊同步知识库</div>
      <span class="head-tip">每天 23:59 把当天 00:00 起的群聊记录写成一篇文档</span>
      <div class="spacer"></div>
      <button class="close" @click="emit('close')"><i class="ri-close-line"></i></button>
    </div>

    <div v-if="loading" class="loading">读取配置中…</div>
    <template v-else>
      <div class="section">
        <div class="label">飞书自建应用</div>
        <div class="two">
          <input v-model.trim="appId" class="input" placeholder="App ID，形如 cli_xxxxxxxx" />
          <input
            v-model="appSecret"
            class="input"
            type="password"
            autocomplete="new-password"
            :placeholder="cfg?.appSecretSet ? `App Secret（已配置 ${cfg.appSecretMask}，留空不改）` : 'App Secret'"
          />
        </div>
        <div class="tip">凭据加密保存在服务端，页面上只显示脱敏形态。机器人需要已经在目标群里，并开通读取群消息与群成员的权限。</div>
      </div>

      <div class="section">
        <div class="label-row">
          <div class="label">目标群聊</div>
          <div class="spacer"></div>
          <button class="mini-btn" :disabled="busy || !appId" @click="loadChats">
            <i class="ri-refresh-line"></i>读取机器人所在的群
          </button>
        </div>
        <div v-if="chatId" class="picked"><i class="ri-wechat-2-line"></i>{{ chatName || chatId }}<span class="pid">{{ chatId }}</span></div>
        <div v-if="chatsLoaded" class="chat-list">
          <button v-for="c in chats" :key="c.chatId" class="chat-row" :class="{ on: c.chatId === chatId }" @click="pickChat(c)">
            <span class="chat-name">{{ c.name || '未命名群' }}</span>
            <span class="pid">{{ c.chatId }}</span>
          </button>
          <div v-if="!chats.length" class="empty">没有读到群 —— 确认机器人已经被拉进群里</div>
        </div>
      </div>

      <div class="section">
        <div class="label">沉淀到</div>
        <div class="two">
          <select v-model="kbId" class="input">
            <option value="">选择知识库</option>
            <option v-for="k in data.kbs" :key="k.id" :value="k.id">{{ k.name }}</option>
          </select>
        </div>
        <div class="tip">按同步日期自动归档到「YYYY 年 M 月」文件夹；一天一篇，同一天重复同步会覆盖同一篇，不会堆重复文档。</div>
      </div>

      <div class="section">
        <div class="label-row">
          <div class="label">发言人对应关系</div>
          <div class="spacer"></div>
          <button class="mini-btn" :disabled="busy || !chatId" @click="loadMembers"><i class="ri-user-search-line"></i>读取群成员</button>
          <button class="mini-btn" @click="addSender"><i class="ri-add-line"></i>手动添加</button>
        </div>
        <div v-for="(s, i) in senders" :key="i" class="sender-row">
          <input v-model.trim="s.id" class="input mono" placeholder="飞书 open_id，如 ou_xxx" />
          <input v-model.trim="s.name" class="input" placeholder="显示名，如 娃哥 / 娃弟 / 机器人通知" />
          <button class="row-del" title="移除" @click="removeSender(i)"><i class="ri-close-line"></i></button>
        </div>
        <div v-if="!senders.length" class="empty">还没有映射 —— 点「读取群成员」把 ID 和人对上</div>
      </div>

      <div class="foot">
        <label class="run-day"><span>运行日期</span><input v-model="runDay" class="input" type="date" /></label>
        <button class="btn-ghost" :disabled="busy" @click="runNow"><i class="ri-play-line"></i>{{ runDay ? '同步指定日期' : '立即同步今天' }}</button>
        <div class="spacer"></div>
        <button class="btn-ghost" @click="emit('close')">取消</button>
        <button class="btn-primary" :disabled="busy" @click="save">保存配置</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 100;
  animation: waoFade 0.15s ease;
}
.modal {
  position: fixed;
  top: 6vh;
  left: 0;
  right: 0;
  margin-inline: auto;
  width: min(620px, calc(100vw - 40px));
  max-height: 86vh;
  overflow-y: auto;
  background: #fff;
  border-radius: 14px;
  z-index: 101;
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop 0.16s ease;
  padding: 20px 22px;
}
.spacer {
  flex: 1;
}
.head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.title {
  font-size: 16px;
  font-weight: 700;
}
.head-tip {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.close {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
}
.close:hover {
  background: var(--wao-bg-hover-2);
}
.loading {
  padding: 30px 0;
  text-align: center;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.section {
  margin-top: 18px;
}
.label {
  font-size: 12px;
  color: var(--wao-text-weak);
  letter-spacing: 1px;
}
.label-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.two {
  margin-top: 8px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.input {
  height: 36px;
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 0 10px;
  font-size: 13px;
  background: #fff;
  color: var(--wao-text);
  outline: none;
}
.input:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.input:disabled {
  background: var(--wao-bg-sub);
}
.input.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.tip {
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  line-height: 1.8;
}
.mini-btn {
  height: 28px;
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
}
.mini-btn:hover:not(:disabled) {
  background: var(--wao-bg-hover-2);
}
.mini-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.picked {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  color: var(--wao-text-2);
}
.pid {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: var(--wao-text-faint);
}
.chat-list {
  margin-top: 8px;
  border: 1px solid var(--wao-divider);
  border-radius: 10px;
  max-height: 168px;
  overflow-y: auto;
}
.chat-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  background: none;
  cursor: pointer;
  text-align: left;
}
.chat-row:hover {
  background: var(--wao-bg-hover-2);
}
.chat-row.on {
  background: var(--wao-primary-soft);
}
.chat-name {
  font-size: 13px;
  color: var(--wao-text);
  flex: 1;
  min-width: 0;
}
.sender-row {
  margin-top: 8px;
  display: grid;
  grid-template-columns: 1.3fr 1fr 28px;
  gap: 8px;
  align-items: center;
}
.row-del {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  border-radius: 7px;
  color: var(--wao-text-3);
  cursor: pointer;
}
.row-del:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-danger-strong);
}
.empty {
  margin-top: 8px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.foot {
  margin-top: 22px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.run-day { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--wao-text-weak); }
.run-day .input { width: 138px; height: 34px; }
.btn-ghost {
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  color: var(--wao-text-2);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-ghost:hover:not(:disabled) {
  background: #f4f5f8;
}
.btn-ghost:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.btn-primary {
  height: 34px;
  padding: 0 16px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}
.btn-primary:hover:not(:disabled) {
  background: var(--wao-primary-hover);
}
.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
</style>
