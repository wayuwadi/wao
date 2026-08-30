<script setup lang="ts">
/**
 * 「飞书群聊消息同步知识库」的配置页。
 *
 * 填自建应用的 App ID / Secret → 选机器人所在的群 → 选沉淀到哪个知识库文件夹 →
 * 把群成员的飞书 ID 对上人名（娃哥 / 娃弟 / 机器人通知）。配置存服务端（密钥加密），
 * 之后每天 23:59 由自动化按计划跑；这里也能补录今天或一段历史日期。
 */
import { onMounted, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { toast } from '@/composables/toast'
import { copyToClipboard } from '@/utils/clipboard'
import {
  fetchChatSyncConfig,
  fetchFeishuChatMembers,
  fetchFeishuChats,
  fetchFeishuTextPolishConfig,
  fetchQuickCollectConfig,
  fetchWebhookEndpoints,
  runChatSync,
  saveChatSyncConfig,
  saveFeishuTextPolishConfig,
  saveQuickCollectConfig,
  type ChatSyncConfig,
  type FeishuChat,
  type FeishuSender,
  type FeishuTextPolishConfig,
  type FeishuWebhookEndpoint,
  type QuickCollectConfig,
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
const historyStartDay = ref('')
const historyEndDay = ref('')
const senders = ref<FeishuSender[]>([])
const chats = ref<FeishuChat[]>([])
const chatsLoaded = ref(false)

// ---- 群聊快速收集（@机器人 直接建任务 + 「收集中」自动分享回群） ----
const quick = ref<QuickCollectConfig | null>(null)
const quickEnabled = ref(false)
const quickProjectId = ref('')
const verifyToken = ref('')
const encryptKey = ref('')
const mentionIds = ref<string[]>([])

// ---- 群聊润色（现有应用读取 @消息，指定的自定义机器人 Webhook 回群） ----
const textPolish = ref<FeishuTextPolishConfig | null>(null)
const textPolishEnabled = ref(false)
const textPolishWebhookId = ref('')
const textPolishMentionName = ref('润色机器人')
const webhookEndpoints = ref<FeishuWebhookEndpoint[]>([])

/** 事件订阅地址：填到飞书开放平台「事件订阅」里，机器人收到的消息才会送到 WAO。 */
const eventUrl = `${window.location.origin}/api/feishu/events`

function toggleMention(id: string) {
  mentionIds.value = mentionIds.value.includes(id)
    ? mentionIds.value.filter((x) => x !== id)
    : [...mentionIds.value, id]
}

async function copyEventUrl() {
  toast(
    (await copyToClipboard(eventUrl)) ? '事件订阅地址已复制' : '复制失败，请手动选中地址',
    'ri-links-line',
  )
}

onMounted(async () => {
  try {
    const [c, q, polish, webhooks] = await Promise.all([
      fetchChatSyncConfig(),
      fetchQuickCollectConfig(),
      fetchFeishuTextPolishConfig(),
      fetchWebhookEndpoints(),
    ])
    cfg.value = c
    appId.value = c.appId
    chatId.value = c.chatId
    chatName.value = c.chatName
    kbId.value = c.kbId
    senders.value = c.senders.map((s) => ({ ...s }))
    quick.value = q
    quickEnabled.value = q.enabled
    quickProjectId.value = q.projectId
    mentionIds.value = [...q.mentionOpenIds]
    textPolish.value = polish
    textPolishEnabled.value = polish.enabled
    textPolishWebhookId.value = polish.webhookId
    textPolishMentionName.value = polish.mentionName || '润色机器人'
    webhookEndpoints.value = webhooks
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

/** 快速收集单独存一份：密文字段留空表示保留已存的那一份。 */
async function persistQuick() {
  const [savedQuick, savedPolish] = await Promise.all([
    saveQuickCollectConfig({
      enabled: quickEnabled.value,
      projectId: quickProjectId.value,
      verificationToken: verifyToken.value.trim() || undefined,
      encryptKey: encryptKey.value.trim() || undefined,
      mentionOpenIds: mentionIds.value,
    }),
    saveFeishuTextPolishConfig({
      enabled: textPolishEnabled.value,
      webhookId: textPolishWebhookId.value,
      mentionName: textPolishMentionName.value.trim(),
    }),
  ])
  quick.value = savedQuick
  textPolish.value = savedPolish
  verifyToken.value = ''
  encryptKey.value = ''
}

/** 把当前表单存到服务端；App Secret 留空表示保留已存的那把。 */
async function persist() {
  await persistQuick()
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
    const result = await runChatSync(runDay.value ? { day: runDay.value } : undefined)
    await data.refreshKnowledge()
    toast(result, 'ri-check-line')
  } catch (error) { toast(error instanceof Error ? error.message : '同步失败') }
  finally { busy.value = false }
}

/** 近几小时仍按整天补录，防止局部消息覆盖当天完整归档。 */
async function runRecentHours() {
  const today = new Date().toISOString().slice(0, 10)
  runDay.value = today
  await runNow()
}

async function runThisMonthHistory() {
  const now = new Date()
  const end = new Date(now)
  end.setDate(end.getDate() - 1)
  historyStartDay.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`
  historyEndDay.value = end.toISOString().slice(0, 10)
  await runHistory()
}

async function runHistory() {
  if (!historyStartDay.value || !historyEndDay.value) {
    toast('请选择完整的历史起止日期')
    return
  }
  busy.value = true
  try {
    await persist()
    const result = await runChatSync({ startDay: historyStartDay.value, endDay: historyEndDay.value })
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

      <div class="section">
        <div class="label-row">
          <div class="label">群聊快速收集</div>
          <div class="spacer"></div>
          <label class="switch">
            <input v-model="quickEnabled" type="checkbox" />
            <span>在群里 @机器人 就记入我的任务</span>
          </label>
        </div>
        <div class="tip">
          在群里 @ 机器人说「记录任务：…」、「记录想法：…」或直接说内容，会记入发言人自己的未排期任务列表。
          每个人需要先用同一飞书账号登录过 WAO；普通群聊不会被收集。
          开启后 WAO 会用上方已授权的应用凭据主动读取新消息，通常 15 秒内完成；下方事件订阅可作为更低延迟的可选入口。
          手机上直接用飞书的语音输入说完就发即可；发语音条我认不出来，会回一句提示让你转文字。
        </div>
        <div class="event-url">
          <span class="mono ellip">{{ eventUrl }}</span>
          <button class="mini-btn" @click="copyEventUrl"><i class="ri-file-copy-line"></i>复制事件订阅地址</button>
        </div>
        <div class="two">
          <select v-model="quickProjectId" class="input">
            <option value="">默认落到「Wa All in One」</option>
            <option v-for="p in data.projects" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
        </div>
        <div class="two">
          <input
            v-model="verifyToken"
            class="input"
            type="password"
            autocomplete="new-password"
            :placeholder="quick?.hasVerificationToken ? `Verification Token（已配置 ${quick.verificationToken}，留空不改）` : 'Verification Token（可选）'"
          />
          <input
            v-model="encryptKey"
            class="input"
            type="password"
            autocomplete="new-password"
            :placeholder="quick?.hasEncryptKey ? `Encrypt Key（已配置 ${quick.encryptKey}，留空不改）` : 'Encrypt Key（可选）'"
          />
        </div>
        <div class="label sub-label">任务进入「收集中」时 @ 这些人</div>
        <div class="mention-list">
          <label v-for="s in senders.filter((x) => x.id)" :key="s.id" class="mention">
            <input type="checkbox" :checked="mentionIds.includes(s.id)" @change="toggleMention(s.id)" />
            <span>{{ s.name || s.id }}</span>
          </label>
          <div v-if="!senders.filter((x) => x.id).length" class="empty">先在上面读取群成员，这里才能勾人</div>
        </div>
        <div class="tip">操作人自己永远不会被 @；一个人都不勾就只发通知不 @ 人。</div>
      </div>

      <div class="section">
        <div class="label-row">
          <div class="label">群聊润色机器人</div>
          <div class="spacer"></div>
          <label class="switch">
            <input v-model="textPolishEnabled" type="checkbox" />
            <span>在群里 @机器人 直接润色并回群</span>
          </label>
        </div>
        <div class="tip">
          WAO 使用上方自建应用读取群消息，精确识别触发名称；润色结果由所选自定义机器人 Webhook 发出。
          发言人必须先用同一飞书账号登录 WAO，机器人会使用该成员在「润色」页面保存的提示词，成功结果也进入其个人历史。
        </div>
        <div class="two">
          <input
            v-model="textPolishMentionName"
            class="input"
            maxlength="64"
            placeholder="群内机器人名称，如：润色机器人"
          />
          <select v-model="textPolishWebhookId" class="input">
            <option value="">选择用于发送结果的 Webhook</option>
            <option
              v-for="endpoint in webhookEndpoints.filter((item) => item.enabled)"
              :key="endpoint.id"
              :value="endpoint.id"
            >
              {{ endpoint.name }}{{ endpoint.systemDefault ? ' · 系统默认' : '' }}
            </option>
            <option
              v-if="textPolishWebhookId && !webhookEndpoints.some((item) => item.id === textPolishWebhookId && item.enabled)"
              :value="textPolishWebhookId"
              disabled
            >
              已保存的 Webhook 当前不可用
            </option>
          </select>
        </div>
        <div class="tip">
          触发示例：@{{ textPolishMentionName || '润色机器人' }} 这段话帮我整理清楚。Webhook 地址请在系统设置中维护，页面不会读取明文。
        </div>
      </div>

      <div class="foot">
        <label class="run-day"><span>运行日期</span><input v-model="runDay" class="input" type="date" /></label>
        <button class="btn-ghost" :disabled="busy" @click="runNow"><i class="ri-play-line"></i>{{ runDay ? '同步指定日期' : '立即同步今天' }}</button>
        <div class="spacer"></div>
        <button class="btn-ghost" @click="emit('close')">取消</button>
        <button class="btn-primary" :disabled="busy" @click="save">保存配置</button>
      </div>
      <div class="history-run">
        <div class="label">补录历史</div>
        <div class="history-controls">
          <button class="mini-btn" :disabled="busy" @click="runRecentHours"><i class="ri-time-line"></i>同步近几小时</button>
          <button class="mini-btn" :disabled="busy" @click="runThisMonthHistory"><i class="ri-calendar-line"></i>同步本月历史</button>
          <label class="run-day"><span>从</span><input v-model="historyStartDay" class="input" type="date" /></label>
          <label class="run-day"><span>至</span><input v-model="historyEndDay" class="input" type="date" /></label>
          <button class="mini-btn" :disabled="busy" @click="runHistory"><i class="ri-history-line"></i>同步全部历史</button>
        </div>
        <div class="tip">历史同步会逐日覆盖已有归档；“近几小时”会重刷当天完整记录，“全部历史”以这里填写的起止日期为准，一次最多 366 天。</div>
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
  background: var(--wao-bg-card);
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
  background: var(--wao-bg-card);
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
  background: var(--wao-bg-card);
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
.switch { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--wao-text-2); cursor: pointer; }
.sub-label { margin-top: 12px; }
.event-url {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 9px;
  background: var(--wao-bg-sub);
}
.event-url .mono { flex: 1; min-width: 0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11.5px; color: var(--wao-text-2); }
.mention-list { margin-top: 8px; display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.mention { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--wao-text-2); cursor: pointer; }
.history-run { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--wao-divider); }
.history-controls { margin-top: 8px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.history-controls .run-day .input { width: 132px; }
.run-day { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--wao-text-weak); }
.run-day .input { width: 138px; height: 34px; }
.btn-ghost {
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-ghost:hover:not(:disabled) {
  background: var(--wao-bg-hover);
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
