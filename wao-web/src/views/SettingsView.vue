<script setup lang="ts">
import { computed, reactive, ref, watch, watchEffect } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { copyText } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import { updateEmailCredential, updateProfile, unlinkIdentity } from '@/api/auth'
import { startFeishuLogin } from '@/api/feishu'
import { startWechatLogin } from '@/api/wechat'
import {
  fetchSiteInfo, fetchWebhookConfig, saveSiteInfo, saveWebhookConfig, testWebhook,
  type FeishuWebhookConfig, type SiteInfo,
} from '@/api/system'
import { useDataStore } from '@/stores/data'
import AiAccountPanel from '@/components/settings/AiAccountPanel.vue'
import { useTheme, type ThemeMode } from '@/composables/theme'

const session = useSessionStore()
const theme = useTheme()
const data = useDataStore()
const router = useRouter()

const NAV = [
  { label: '组织管理', icon: 'ri-community-line', items: [['组织与成员', 'members'], ['日历账户 · CalDAV', 'caldav']] },
  { label: '系统', icon: 'ri-settings-4-line', items: [['网站信息 · 备案', 'site'], ['通知 · 飞书 Webhook', 'webhook'], ['AI 账号', 'ai-accounts']] },
  { label: '个人', icon: 'ri-user-3-line', items: [['个人资料', 'profile'], ['账号与登录', 'account'], ['偏好', 'prefs'], ['开发者 · API', 'api']] },
] as const

const tab = ref('members')
const accountForm = reactive({ email: '', currentPassword: '', newPassword: '' })
const profileForm = reactive({ name: '', initial: '' })
const accountBusy = ref(false)
const identities = computed(() => session.account?.identities ?? [])
const providerNames: Record<string, string> = { email: '邮箱密码', feishu: '飞书', wechat: '微信' }
const hasIdentity = (provider: string) => identities.value.some((identity) => identity.provider === provider)
const emailIdentity = computed(() => identities.value.find((identity) => identity.provider === 'email'))
const joinedAt = computed(() => {
  if (!session.account?.createdAt) return '—'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'long' }).format(new Date(session.account.createdAt))
})

watchEffect(() => {
  if (!profileForm.name) profileForm.name = session.currentUser.name
  if (!profileForm.initial) profileForm.initial = session.currentUser.initial
  if (!accountForm.email && emailIdentity.value?.email) accountForm.email = emailIdentity.value.email
})

const orgMembers = computed(() =>
  session.members.map((m) => ({
    name: m.name, initial: m.initial, color: m.color, role: m.role,
    roleBg: m.role === '管理员' ? '#E9EDFB' : '#F0F1F4',
    roleColor: m.role === '管理员' ? '#3A4CC2' : '#5A5E6B',
    meTag: m.id === session.currentUser.id ? '（我）' : '',
  })),
)

const caldavCals = reactive([
  { name: '家庭日历', dot: '#0E8C8C', on: true },
  { name: '工作日历', dot: '#C05D8E', on: true },
  { name: '节假日订阅', dot: '#B9821F', on: false },
])

const THEME_LABELS: Record<ThemeMode, string> = {
  light: '明亮',
  dark: '深色',
  system: '跟随系统',
}

const prefRows = [
  { name: '语言', desc: '界面显示语言', value: '简体中文' },
  { name: '每周起始日', desc: '影响日历与「本周」分组', value: '周一' },
  { name: '庆祝动画', desc: '创建任务 / 项目、完成任务时的全屏反馈', value: '细腻纸屑' },
]

function cycleTheme() {
  const order: ThemeMode[] = ['light', 'dark', 'system']
  const idx = order.indexOf(theme.mode.value)
  const next = order[(idx + 1) % order.length]
  theme.setTheme(next ?? 'system')
}

// 开发者 · API
interface Token {
  name: string
  masked: string
  scope: string
  created: string
  last: string
}
const newTokenName = ref('')
const freshToken = ref<string | null>(null)
const tokens = ref<Token[]>([])

function makeToken() {
  const name = newTokenName.value.trim() || '未命名令牌'
  const rnd = (n: number) => {
    const c = 'abcdefghijklmnopqrstuvwxyz0123456789'
    let o = ''
    for (let i = 0; i < n; i++) o += c[Math.floor(Math.random() * c.length)]
    return o
  }
  const full = 'wao_pat_' + rnd(28)
  tokens.value = [{ name, masked: full.slice(0, 12) + '••••••••' + full.slice(-4), scope: '读写 · 全部模块', created: '刚刚', last: '—' }, ...tokens.value]
  newTokenName.value = ''
  freshToken.value = full
  toast('令牌已生成，请立即复制保存', 'ri-key-2-line')
}
function revoke(i: number) {
  const t = tokens.value[i]
  tokens.value = tokens.value.filter((_, j) => j !== i)
  toast(`已吊销「${t?.name}」，使用该令牌的调用将立即失效`, 'ri-close-circle-line')
}
// ---- 系统 · 飞书通知 Webhook ----
// 全局只有一个：所有需要「说一声」的地方都发到这一个群机器人。地址加密存服务端，
// 界面上只回脱敏形态，留空保存即表示不改地址。
const webhook = ref<FeishuWebhookConfig | null>(null)
const webhookInput = ref('')
const webhookBusy = ref(false)
const site = reactive<SiteInfo>({ siteName: '', description: '', icpNumber: '', icpUrl: '' })
const siteLoaded = ref(false)
const siteBusy = ref(false)

async function loadSiteInfo() {
  try {
    Object.assign(site, await fetchSiteInfo())
    siteLoaded.value = true
  } catch (error) {
    toast(error instanceof Error ? error.message : '读取网站信息失败')
  }
}
async function saveSiteInformation() {
  siteBusy.value = true
  try {
    Object.assign(site, await saveSiteInfo({ ...site }))
    toast('网站信息与备案信息已保存', 'ri-check-line')
  } catch (error) { toast(error instanceof Error ? error.message : '保存失败') }
  finally { siteBusy.value = false }
}

async function loadWebhook() {
  try {
    webhook.value = await fetchWebhookConfig()
  } catch (error) {
    toast(error instanceof Error ? error.message : '读取通知配置失败')
  }
}
async function saveWebhook() {
  webhookBusy.value = true
  try {
    webhook.value = await saveWebhookConfig({
      url: webhookInput.value.trim() || undefined,
      enabled: webhook.value?.enabled ?? false,
    })
    webhookInput.value = ''
    toast('飞书通知配置已保存', 'ri-check-line')
  } catch (error) { toast(error instanceof Error ? error.message : '保存失败') }
  finally { webhookBusy.value = false }
}
async function toggleWebhook() {
  if (!webhook.value) return
  const next = !webhook.value.enabled
  webhookBusy.value = true
  try {
    webhook.value = await saveWebhookConfig({ enabled: next })
    toast(next ? '已启用飞书通知' : '已停用飞书通知', 'ri-notification-3-line')
  } catch (error) { toast(error instanceof Error ? error.message : '保存失败') }
  finally { webhookBusy.value = false }
}
async function sendTestWebhook() {
  webhookBusy.value = true
  try {
    await testWebhook()
    toast('测试消息已发出，去群里看看', 'ri-send-plane-2-line')
  } catch (error) { toast(error instanceof Error ? error.message : '发送失败') }
  finally { webhookBusy.value = false }
}
// ---- 系统 · 按项目通知 ----
// 群机器人原本对所有项目一视同仁地播报，可多数项目的新建、状态变化并不需要有人立刻知道；
// 通知一多整块就被无视，真正等验收的那条也跟着被划过去。开关落在项目上，
// 粒度正好等于「我关心哪个项目」，总开关仍然一票否决。
const notifyBusy = ref<string | null>(null)
const notifyProjects = computed(() =>
  data.projects.map((p) => ({
    id: p.id,
    name: p.name,
    color: data.areaColor(p.area),
    on: p.notifyEnabled !== false,
  })),
)
const notifyOnCount = computed(() => notifyProjects.value.filter((p) => p.on).length)

async function toggleProjectNotify(id: string) {
  const project = data.projectById(id)
  if (!project || notifyBusy.value) return
  const next = project.notifyEnabled === false
  notifyBusy.value = id
  const saved = await data.updateProject(id, { notifyEnabled: next })
  notifyBusy.value = null
  if (!saved) {
    // 没落库就把开关拨回去，别让界面显示一个服务端并不知道的状态
    project.notifyEnabled = !next
    toast('保存失败，请重试', 'ri-error-warning-line')
    return
  }
  toast(next ? `「${project.name}」的动静会发到飞书群` : `已静音「${project.name}」`, 'ri-notification-3-line')
}

watch(tab, (t) => {
  if (t === 'site' && !siteLoaded.value) loadSiteInfo()
  if (t === 'webhook' && !webhook.value) loadWebhook()
}, { immediate: true })

function toggleCal(c: { on: boolean }) {
  c.on = !c.on
  toast('演示：订阅开关在正式版本生效', 'ri-calendar-line')
}
async function logout() {
  await session.logout()
  await router.push('/login')
}
async function saveEmailCredential() {
  accountBusy.value = true
  try {
    session.accept(await updateEmailCredential(accountForm.email, accountForm.currentPassword, accountForm.newPassword))
    accountForm.currentPassword = ''
    accountForm.newPassword = ''
    toast('邮箱与密码已更新', 'ri-shield-check-line')
  } catch (error) { toast(error instanceof Error ? error.message : '更新失败') }
  finally { accountBusy.value = false }
}
async function saveProfile() {
  accountBusy.value = true
  try {
    session.accept(await updateProfile(profileForm.name, profileForm.initial))
    await session.refreshDirectory()
    toast('成员资料已保存到服务器', 'ri-user-smile-line')
  } catch (error) { toast(error instanceof Error ? error.message : '资料保存失败') }
  finally { accountBusy.value = false }
}
async function unlink(provider: string) {
  accountBusy.value = true
  try { session.accept(await unlinkIdentity(provider)); toast(`已解绑${providerNames[provider] ?? provider}`) }
  catch (error) { toast(error instanceof Error ? error.message : '解绑失败') }
  finally { accountBusy.value = false }
}
async function bindWechat() {
  try { await startWechatLogin('link') }
  catch (error) { toast(error instanceof Error ? error.message : '微信登录暂不可用') }
}
async function bindFeishu() {
  try { await startFeishuLogin('link') }
  catch (error) { toast(error instanceof Error ? error.message : '飞书登录暂不可用') }
}
function demo(msg: string, icon?: string) {
  toast(msg, icon)
}
</script>

<template>
  <div class="settings">
    <!-- 左侧分类 -->
    <div class="snav">
      <div class="snav-title">设置</div>
      <div v-for="g in NAV" :key="g.label">
        <div class="snav-group"><i :class="g.icon"></i>{{ g.label }}</div>
        <div class="snav-items">
          <button v-for="it in g.items" :key="it[1]" class="snav-item" :class="{ on: tab === it[1] }" @click="tab = it[1]">
            {{ it[0] }}
          </button>
        </div>
      </div>
      <div class="snav-foot">
        <button class="logout" @click="logout"><i class="ri-logout-box-r-line"></i>退出登录</button>
      </div>
    </div>

    <!-- 右侧内容 -->
    <div class="spane">
      <div class="scontent">
        <!-- 组织与成员 -->
        <template v-if="tab === 'members'">
          <div class="s-h1">组织与成员</div>
          <div class="card org">
            <div class="org-badge">W</div>
            <div class="org-main">
              <div class="org-name">{{ session.org.name }}</div>
              <div class="org-sub">组织 ID：{{ session.org.id }} · {{ session.org.memberCount }} 名真实成员</div>
            </div>
            <span class="org-tag">当前组织</span>
          </div>
          <div class="card">
            <div class="card-row">
              <div class="card-title">成员</div>
              <div class="spacer"></div>
              <button class="btn-primary sm" disabled title="邀请流程将在后续成员管理需求中实现"><i class="ri-user-add-line"></i>邀请成员（待实现）</button>
            </div>
            <div v-for="(m, i) in orgMembers" :key="i" class="mem-row">
              <span class="mem-av" :style="{ background: m.color }">{{ m.initial }}</span>
              <span class="mem-name">{{ m.name }}<span class="me-tag">{{ m.meTag }}</span></span>
              <span class="mem-role" :style="{ background: m.roleBg, color: m.roleColor }">{{ m.role }}</span>
            </div>
            <div class="hint">WAO 不区分「个人版 / 团队版」：内容归属由负责人、参与人与可见范围表达。新建项目默认仅创建者可见，可邀请成员并设置查看或编辑权限。</div>
          </div>
        </template>

        <!-- CalDAV -->
        <template v-else-if="tab === 'caldav'">
          <div class="s-h1">日历账户 · CalDAV</div>
          <div class="card">
            <div class="card-row wrap">
              <div class="card-title">主账户</div>
              <span class="connected"><span class="c-dot"></span>已连接</span>
              <div class="spacer"></div>
              <button class="btn-ghost sm" @click="demo('正在同步 CalDAV…已是最新（演示）', 'ri-refresh-line')"><i class="ri-refresh-line"></i>立即同步</button>
              <button class="btn-ghost sm" @click="demo('演示：添加 CalDAV 账户在正式版本提供')"><i class="ri-add-line"></i>添加账户</button>
            </div>
            <div class="kv-list">
              <div class="kv"><span class="kv-k">服务器</span><span class="kv-v wao-mono">https://dav.wayuwa.example/caldav</span></div>
              <div class="kv"><span class="kv-k">账号</span><span class="kv-v">wg@wayuwa.example</span></div>
              <div class="kv"><span class="kv-k">同步</span><span class="kv-v">每 15 分钟 · 上次同步 今天 12:10</span></div>
            </div>
            <div class="sub-block">
              <div class="sub-label">订阅的日历（与系统日历合并显示）</div>
              <div v-for="(c, i) in caldavCals" :key="i" class="cal-row">
                <span class="cal-dot" :style="{ background: c.dot }"></span>
                <span class="cal-name">{{ c.name }}</span>
                <button class="toggle" :class="{ on: c.on }" title="启用 / 停用" @click="toggleCal(c)"><span class="knob"></span></button>
              </div>
            </div>
          </div>
        </template>

        <!-- 系统 · 网站信息与备案 -->
        <template v-else-if="tab === 'site'">
          <div class="s-h1">网站信息 · 备案</div>
          <div class="card">
            <div class="card-row wrap">
              <div class="card-title">公开展示信息</div>
              <span v-if="siteLoaded" class="connected"><span class="c-dot"></span>已读取线上配置</span>
            </div>
            <form class="site-form" @submit.prevent="saveSiteInformation">
              <label><span>网站名称</span><input v-model.trim="site.siteName" maxlength="64" required placeholder="例如 WAO Life OS" /></label>
              <label><span>网站简介</span><input v-model.trim="site.description" maxlength="160" placeholder="一句话介绍网站用途" /></label>
              <label><span>ICP备案号</span><input v-model.trim="site.icpNumber" maxlength="64" required placeholder="例如 豫ICP备XXXXXXXX号-X" /></label>
              <label><span>备案查询链接</span><input v-model.trim="site.icpUrl" type="url" maxlength="255" required placeholder="https://beian.miit.gov.cn" /></label>
              <div class="site-actions"><button class="btn-primary" :disabled="siteBusy">{{ siteBusy ? '保存中…' : '保存并更新页脚' }}</button></div>
            </form>
            <div class="filing-preview">
              <span class="preview-label">页脚预览</span>
              <span>© {{ new Date().getFullYear() }} {{ site.siteName || '网站名称' }}</span>
              <span v-if="site.description">· {{ site.description }}</span>
              <a v-if="site.icpNumber" :href="site.icpUrl || undefined" target="_blank" rel="noopener noreferrer">· {{ site.icpNumber }}</a>
            </div>
            <div class="hint">保存后，备案号会自动展示在登录页、公开任务分享页和登录后的系统页脚；无需修改代码或重新发布前端。</div>
          </div>
        </template>

        <!-- 系统 · 飞书通知 Webhook -->
        <template v-else-if="tab === 'webhook'">
          <div class="s-h1">通知 · 飞书 Webhook</div>
          <div class="card">
            <div class="card-row wrap">
              <div class="card-title">群机器人地址</div>
              <span v-if="webhook?.urlSet" class="connected"><span class="c-dot"></span>已配置</span>
              <div class="spacer"></div>
              <button class="toggle" :class="{ on: webhook?.enabled }" :disabled="!webhook?.urlSet || webhookBusy" title="启用 / 停用通知" @click="toggleWebhook"><span class="knob"></span></button>
            </div>
            <div class="kv-list">
              <div class="kv"><span class="kv-k w76">当前地址</span><span class="kv-v wao-mono">{{ webhook?.urlMask || '尚未配置' }}</span></div>
              <div class="kv"><span class="kv-k w76">状态</span><span class="kv-v">{{ webhook?.enabled ? '已启用 · 系统通知会发到这个群' : '未启用' }}</span></div>
            </div>
            <form class="credential-form webhook-form" @submit.prevent="saveWebhook">
              <input v-model.trim="webhookInput" type="url" placeholder="粘贴飞书群机器人 Webhook 地址（留空即不修改）" />
              <button class="btn-primary" :disabled="webhookBusy">保存</button>
              <button type="button" class="btn-ghost" :disabled="webhookBusy || !webhook?.urlSet" @click="sendTestWebhook">发送测试消息</button>
            </form>
            <div class="hint">全局只用这一个 webhook：在飞书群里添加「自定义机器人」拿到地址，粘进来即可。地址加密保存在服务端，页面上只显示脱敏形态，不会随接口回传明文。</div>
          </div>

          <div class="card">
            <div class="card-row wrap">
              <div class="card-title">按项目通知</div>
              <div class="spacer"></div>
              <span class="pn-count">{{ notifyOnCount }} / {{ notifyProjects.length }} 个项目会发通知</span>
            </div>
            <div class="sub-block">
              <div v-for="p in notifyProjects" :key="p.id" class="cal-row">
                <span class="cal-dot" :style="{ background: p.color }"></span>
                <span class="cal-name pn-name">{{ p.name }}</span>
                <span class="pn-state" :class="{ off: !p.on }">{{ p.on ? '通知中' : '已静音' }}</span>
                <button
                  class="toggle"
                  :class="{ on: p.on }"
                  :disabled="notifyBusy === p.id"
                  :title="p.on ? '静音这个项目' : '开启这个项目的通知'"
                  @click="toggleProjectNotify(p.id)"
                ><span class="knob"></span></button>
              </div>
              <div v-if="!notifyProjects.length" class="hint">还没有项目。</div>
            </div>
            <div class="hint">静音的项目不再往群里发新建任务、状态变化、新增子任务、评论与 AI 执行的卡片，页面内的记录一条不少。上面的总开关关掉时所有项目都不发。</div>
          </div>
        </template>

        <!-- 系统 · AI 账号 -->
        <template v-else-if="tab === 'ai-accounts'">
          <AiAccountPanel />
        </template>

        <!-- 个人资料 -->
        <template v-else-if="tab === 'profile'">
          <div class="s-h1">个人资料</div>
          <div class="card">
            <div class="profile-head">
              <div class="p-av" :style="{ background: session.currentUser.color }">{{ session.currentUser.initial }}</div>
              <div class="p-main">
                <div class="p-name">{{ session.currentUser.name }}</div>
                <div class="p-sub">{{ session.currentUser.role }} · {{ session.org.name }}</div>
              </div>
            </div>
            <form class="credential-form profile-form" @submit.prevent="saveProfile">
              <input v-model.trim="profileForm.name" maxlength="64" required placeholder="成员名称" />
              <input v-model.trim="profileForm.initial" maxlength="4" required placeholder="头像文字" />
              <button class="btn-primary" :disabled="accountBusy">保存资料</button>
            </form>
            <div class="kv-list bordered">
              <div class="kv"><span class="kv-k w76">飞书绑定</span><span class="kv-v" :class="{ ok: hasIdentity('feishu') }"><i :class="hasIdentity('feishu') ? 'ri-checkbox-circle-fill' : 'ri-close-circle-line'"></i>{{ hasIdentity('feishu') ? '已绑定 · 扫码登录可用' : '尚未绑定' }}</span></div>
              <div class="kv"><span class="kv-k w76">邮箱</span><span class="kv-v">{{ emailIdentity?.email || '尚未设置' }}</span></div>
              <div class="kv"><span class="kv-k w76">加入时间</span><span class="kv-v">{{ joinedAt }}</span></div>
            </div>
          </div>
        </template>

        <!-- 账号与登录 -->
        <template v-else-if="tab === 'account'">
          <div class="s-h1">账号与登录</div>
          <div class="card">
            <div class="card-title">已绑定的登录方式</div>
            <div v-for="identity in identities" :key="identity.provider" class="mem-row">
              <span class="identity-icon"><i :class="identity.provider === 'feishu' ? 'ri-feishu-line' : identity.provider === 'wechat' ? 'ri-wechat-fill' : 'ri-mail-line'"></i></span>
              <span class="mem-name">{{ providerNames[identity.provider] ?? identity.provider }}<span class="dim">{{ identity.email || identity.displayName }}</span></span>
              <button class="revoke" :disabled="identities.length === 1 || accountBusy" @click="unlink(identity.provider)">解绑</button>
            </div>
            <div class="hint">至少保留一种登录方式。新绑定的飞书、微信或邮箱都会进入当前的 {{ session.currentUser.name }} 账号。</div>
          </div>
          <div class="card">
            <div class="card-title">绑定第三方账号</div>
            <div class="bind-actions">
              <button v-if="!hasIdentity('feishu')" class="btn-ghost" @click="bindFeishu"><i class="ri-feishu-line"></i>绑定飞书</button>
              <button v-if="!hasIdentity('wechat')" class="btn-ghost" @click="bindWechat"><i class="ri-wechat-fill"></i>绑定微信</button>
              <span v-if="hasIdentity('feishu') && hasIdentity('wechat')" class="hint">飞书和微信均已绑定，无需重复操作。</span>
            </div>
          </div>
          <div class="card">
            <div class="card-title">设置邮箱与密码</div>
            <form class="credential-form" @submit.prevent="saveEmailCredential">
              <input v-model.trim="accountForm.email" type="email" autocomplete="email" required placeholder="登录邮箱" />
              <input v-model="accountForm.currentPassword" type="password" autocomplete="current-password" placeholder="当前密码（首次设置可留空）" />
              <input v-model="accountForm.newPassword" type="password" autocomplete="new-password" minlength="8" required placeholder="新密码（至少 8 位）" />
              <button class="btn-primary" :disabled="accountBusy">保存</button>
            </form>
          </div>
        </template>

        <!-- 偏好 -->
        <template v-else-if="tab === 'prefs'">
          <div class="s-h1">偏好</div>
          <div class="card thin">
            <div class="pref-row">
              <div class="pref-main">
                <div class="pref-name">主题</div>
                <div class="pref-desc">明亮 / 深色 / 跟随系统</div>
              </div>
              <button class="value-btn" @click="cycleTheme">{{ THEME_LABELS[theme.mode.value] }}<i class="ri-arrow-down-s-line"></i></button>
            </div>
            <div v-for="(p, i) in prefRows" :key="i" class="pref-row">
              <div class="pref-main">
                <div class="pref-name">{{ p.name }}</div>
                <div class="pref-desc">{{ p.desc }}</div>
              </div>
              <button class="value-btn" @click="demo('演示：偏好切换在正式版本生效', 'ri-settings-3-line')">{{ p.value }}<i class="ri-arrow-down-s-line"></i></button>
            </div>
          </div>
        </template>

        <!-- 开发者 · API -->
        <template v-else>
          <div class="s-h1">开发者 · API</div>
          <div class="card">
            <div class="card-title">API 端点</div>
            <div class="kv-list">
              <div class="kv"><span class="kv-k w76">Base URL</span><span class="kv-v wao-mono flex1">https://api.wayuwa.example/v1</span><button class="mini-btn" @click="copyText('https://api.wayuwa.example/v1', 'Base URL 已复制')">复制</button></div>
              <div class="kv"><span class="kv-k w76">认证方式</span><span class="kv-v wao-mono">Authorization: Bearer &lt;token&gt;</span></div>
              <div class="kv"><span class="kv-k w76">文档</span><span class="kv-v link-c">api.wayuwa.example/docs（规划中）</span></div>
            </div>
          </div>
          <div class="card">
            <div class="card-title">个人访问令牌（Personal Access Token）</div>
            <div class="token-note">令牌与你的账号权限一致，用于脚本、自动化与第三方工具接入；请像密码一样妥善保管。</div>
            <div class="token-input">
              <input v-model="newTokenName" placeholder="令牌用途，例如「Obsidian 同步」" @keydown.enter="makeToken" />
              <button class="btn-primary" @click="makeToken"><i class="ri-key-2-line"></i>生成新令牌</button>
            </div>

            <div v-if="freshToken" class="fresh">
              <div class="fresh-head"><i class="ri-checkbox-circle-fill"></i>令牌已生成——仅此一次完整显示，请立即复制保存</div>
              <div class="fresh-row">
                <span class="fresh-token wao-mono">{{ freshToken }}</span>
                <button class="fresh-copy" @click="copyText(freshToken, '令牌已复制')">复制</button>
                <button class="fresh-dismiss" @click="freshToken = null">我已保存</button>
              </div>
            </div>

            <div class="token-table">
              <div class="grid-tokens th">
                <div>名称</div><div>令牌</div><div>创建于</div><div>最近使用</div><div class="right">操作</div>
              </div>
              <div v-for="(t, i) in tokens" :key="i" class="grid-tokens row">
                <div class="tk-name-wrap">
                  <div class="tk-name">{{ t.name }}</div>
                  <div class="tk-scope">{{ t.scope }}</div>
                </div>
                <div class="tk-masked wao-mono">{{ t.masked }}</div>
                <div class="tk-time">{{ t.created }}</div>
                <div class="tk-time">{{ t.last }}</div>
                <div class="tk-ops"><button class="revoke" @click="revoke(i)">吊销</button></div>
              </div>
              <div v-if="tokens.length === 0" class="tk-empty">还没有令牌——在上方输入用途并生成</div>
            </div>
            <div class="token-foot">演示环境：令牌为虚构字符串，不可真实调用；正式版本支持过期时间与最小权限范围（scope）。</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  height: 100%;
  min-height: 0;
  animation: waoFade 0.2s ease;
}
.spacer {
  flex: 1;
}
.snav {
  width: 224px;
  flex-shrink: 0;
  border-right: 1px solid var(--wao-line);
  background: var(--wao-bg-sub);
  overflow-y: auto;
  padding: 16px 10px;
}
.snav-title {
  padding: 0 10px 10px;
  font-size: 16px;
  font-weight: 700;
}
.snav-group {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 10px 10px 5px;
  font-size: 11px;
  color: var(--wao-text-weak);
  letter-spacing: 1.5px;
}
.snav-group i {
  font-size: 13px;
}
.snav-items {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.snav-item {
  display: flex;
  align-items: center;
  height: 31px;
  padding: 0 10px 0 28px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text-2);
  font-size: 12.5px;
  cursor: pointer;
  text-align: left;
}
.snav-item:hover {
  background: var(--wao-bg-hover-2);
}
.snav-item.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-weight: 600;
}
.snav-foot {
  margin-top: 14px;
  border-top: 1px solid var(--wao-divider);
  padding-top: 10px;
}
.logout {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 31px;
  padding: 0 10px;
  border: none;
  border-radius: 7px;
  background: none;
  color: var(--wao-text-3);
  font-size: 12.5px;
  cursor: pointer;
}
.logout:hover {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.logout i {
  font-size: 14px;
}

.spane {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
}
.scontent {
  padding: 22px 32px 48px;
  max-width: 860px;
}
.s-h1 {
  font-size: 18px;
  font-weight: 700;
}
.card {
  margin-top: 14px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 14px 18px;
}
.card.thin {
  padding: 8px 18px;
}
.card.org {
  padding: 16px 18px;
  display: flex;
  align-items: center;
  gap: 13px;
}
.org-badge {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  background: var(--wao-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 19px;
}
.org-main {
  flex: 1;
}
.org-name {
  font-size: 15px;
  font-weight: 700;
}
.org-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.org-tag {
  font-size: 11.5px;
  background: var(--wao-primary-soft-2);
  color: var(--wao-primary-text);
  border-radius: 999px;
  padding: 3px 11px;
}
.card-row {
  display: flex;
  align-items: center;
}
.card-row.wrap {
  flex-wrap: wrap;
  gap: 10px;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
}
.btn-primary {
  height: 34px;
  padding: 0 14px;
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
.btn-primary i {
  font-size: 13px;
}
.btn-primary.sm {
  height: 30px;
  padding: 0 12px;
}
.btn-ghost {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.btn-ghost:hover {
  background: var(--wao-bg-hover);
}
.btn-ghost.sm {
  height: 29px;
  padding: 0 11px;
  font-size: 12px;
}
.btn-ghost i {
  font-size: 13px;
}
.mem-row {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 10px 0;
  border-bottom: 1px solid var(--wao-divider-2);
}
.mem-av {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  color: #fff;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.mem-name {
  flex: 1;
  font-size: 13.5px;
  color: var(--wao-text);
}
.me-tag {
  color: var(--wao-text-weak);
  font-size: 12px;
}
.mem-role {
  font-size: 11.5px;
  border-radius: 5px;
  padding: 2px 8px;
}
.identity-icon { width: 30px; height: 30px; display: grid; place-content: center; border-radius: 8px; background: var(--wao-bg-sub); color: var(--wao-primary); font-size: 17px; }
.bind-actions { display: flex; gap: 10px; margin-top: 12px; }
.credential-form { display: grid; grid-template-columns: 1fr 1fr 1fr auto; gap: 8px; margin-top: 12px; }
.profile-form { grid-template-columns: 1fr 120px auto; margin: 14px 0; }
.webhook-form { grid-template-columns: 1fr auto auto; }
.toggle:disabled { opacity: .55; cursor: not-allowed; }
.btn-ghost:disabled { opacity: .55; cursor: not-allowed; }
.credential-form input { min-width: 0; height: 34px; box-sizing: border-box; border: 1px solid var(--wao-line-control); border-radius: 8px; padding: 0 10px; outline: none; background: var(--wao-bg-card); color: var(--wao-text); }
.credential-form input:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 3px var(--wao-primary-soft); }
.site-form { margin-top: 16px; display: grid; grid-template-columns: 1fr 1fr; gap: 13px 16px; }
.site-form label { display: grid; gap: 6px; color: var(--wao-text-3); font-size: 12px; }
.site-form input { height: 36px; box-sizing: border-box; padding: 0 11px; border: 1px solid var(--wao-line-control); border-radius: 8px; outline: none; background: var(--wao-bg-card); color: var(--wao-text); }
.site-form input:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 3px var(--wao-primary-soft); }
.site-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; }
.filing-preview { margin-top: 14px; padding: 12px 14px; display: flex; flex-wrap: wrap; justify-content: center; gap: 5px; border: 1px dashed var(--wao-line-strong); border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 11.5px; }
.filing-preview a { color: var(--wao-primary); text-decoration: none; }
.preview-label { width: 100%; margin-bottom: 2px; color: var(--wao-text-faint); text-align: center; font-size: 10.5px; }
.btn-primary:disabled { opacity: .55; cursor: not-allowed; }
.hint {
  margin-top: 10px;
  font-size: 12px;
  color: var(--wao-text-weak);
  line-height: 1.8;
}
.connected {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--wao-success-strong);
}
.c-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--wao-success);
}
.kv-list {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 9px;
  font-size: 12.5px;
}
.kv-list.bordered {
  margin-top: 14px;
  border-top: 1px solid var(--wao-divider-2);
  padding-top: 12px;
}
.kv {
  display: flex;
  gap: 10px;
  align-items: center;
}
.kv-k {
  width: 64px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.kv-k.w76 {
  width: 76px;
}
.kv-v {
  color: var(--wao-text-2);
}
.kv-v.flex1 {
  flex: 1;
}
.kv-v.ok {
  color: var(--wao-success-strong);
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.kv-v.ok i {
  font-size: 13px;
}
.kv-v.link-c {
  color: var(--wao-primary);
}
.dim {
  margin-left: 8px;
  font-size: 11px;
  color: var(--wao-text-faint);
}
.mini-btn {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 7px;
  font-size: 11.5px;
  color: var(--wao-text-2);
  cursor: pointer;
}
.mini-btn:hover {
  background: var(--wao-bg-hover);
}
.sub-block {
  margin-top: 12px;
  border-top: 1px solid var(--wao-divider-2);
  padding-top: 10px;
}
.sub-label {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.cal-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7px 0;
}
.cal-dot {
  width: 9px;
  height: 9px;
  border-radius: 3px;
}
.cal-name {
  flex: 1;
  font-size: 13px;
  color: var(--wao-text);
}
.pn-name {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.pn-count {
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.pn-state {
  font-size: 11.5px;
  color: var(--wao-success-strong);
}
.pn-state.off {
  color: var(--wao-text-faint);
}
.toggle {
  width: 34px;
  height: 18px;
  border: none;
  border-radius: 999px;
  background: var(--wao-line-strong);
  cursor: pointer;
  position: relative;
  padding: 0;
}
.toggle.on {
  background: var(--wao-primary);
}
.knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--wao-bg-card);
  transition: left 0.15s ease;
}
.toggle.on .knob {
  left: 16px;
}

.profile-head {
  display: flex;
  align-items: center;
  gap: 13px;
}
.p-av {
  width: 46px;
  height: 46px;
  border-radius: 50%;
  color: #fff;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.p-main {
  flex: 1;
}
.p-name {
  font-size: 15px;
  font-weight: 700;
}
.p-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--wao-text-weak);
}

.pref-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 0;
  border-bottom: 1px solid var(--wao-divider-2);
}
.pref-row:last-child {
  border-bottom: none;
}
.pref-main {
  flex: 1;
  min-width: 0;
}
.pref-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--wao-text);
}
.pref-desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.value-btn {
  height: 29px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 7px;
  font-size: 12px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.value-btn:hover {
  background: var(--wao-bg-hover);
}
.value-btn i {
  font-size: 13px;
  color: var(--wao-text-weak);
}

.token-note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.token-input {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}
.token-input input {
  flex: 1;
  height: 34px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 0 12px;
  font-size: 13px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
}
.token-input input:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px var(--wao-primary-soft);
}
.fresh {
  margin-top: 12px;
  background: var(--wao-success-bg);
  border: 1px solid var(--wao-success-line);
  border-radius: 10px;
  padding: 12px 14px;
}
.fresh-head {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  color: var(--wao-success-strong);
  font-weight: 600;
}
.fresh-head i {
  font-size: 14px;
}
.fresh-row {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.fresh-token {
  flex: 1;
  font-size: 12.5px;
  color: var(--wao-text);
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-success-line);
  border-radius: 7px;
  padding: 7px 11px;
  white-space: nowrap;
  overflow-x: auto;
}
.fresh-copy {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--wao-success-line);
  background: var(--wao-bg-card);
  border-radius: 7px;
  font-size: 12px;
  color: var(--wao-success-strong);
  cursor: pointer;
  flex-shrink: 0;
}
.fresh-copy:hover {
  background: var(--wao-success-bg);
}
.fresh-dismiss {
  height: 32px;
  padding: 0 12px;
  border: none;
  background: none;
  border-radius: 7px;
  font-size: 12px;
  color: var(--wao-text-weak);
  cursor: pointer;
  flex-shrink: 0;
}
.fresh-dismiss:hover {
  background: var(--wao-success-bg);
}
.token-table {
  margin-top: 14px;
  border: 1px solid var(--wao-divider);
  border-radius: 10px;
  overflow: hidden;
}
.grid-tokens {
  display: grid;
  grid-template-columns: minmax(150px, 1.3fr) minmax(150px, 1.2fr) 90px 90px 64px;
  gap: 10px;
  padding: 11px 14px;
  align-items: center;
}
.grid-tokens.th {
  padding: 8px 14px;
  background: var(--wao-bg-sub);
  border-bottom: 1px solid var(--wao-divider);
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.grid-tokens.row {
  border-bottom: 1px solid var(--wao-divider-2);
}
.right {
  text-align: right;
}
.tk-name-wrap {
  min-width: 0;
}
.tk-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tk-scope {
  margin-top: 1px;
  font-size: 11px;
  color: var(--wao-text-weak);
}
.tk-masked {
  font-size: 11.5px;
  color: var(--wao-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tk-time {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.tk-ops {
  display: flex;
  justify-content: flex-end;
}
.revoke {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--wao-danger-line);
  background: var(--wao-bg-card);
  border-radius: 7px;
  font-size: 11.5px;
  color: var(--wao-danger-strong);
  cursor: pointer;
}
.revoke:hover {
  background: var(--wao-danger-bg);
}
.tk-empty {
  padding: 24px;
  text-align: center;
  color: var(--wao-text-faint);
  font-size: 12.5px;
}
.token-foot {
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--wao-text-faint);
}

@media (max-width: 760px) {
  .settings { display: block; height: auto; min-height: 100%; }
  .snav { width: 100%; height: auto; padding: 8px; border-right: 0; border-bottom: 1px solid var(--wao-line); overflow-x: auto; overflow-y: hidden; }
  .snav-title, .snav-group, .snav-foot { display: none; }
  .snav-items { flex-direction: row; width: max-content; }
  .snav-item { height: 34px; padding: 0 10px; white-space: nowrap; }
  .spane { overflow: visible; }
  .scontent { padding: 16px 12px 28px; }
  .card { padding: 14px; }
  .credential-form, .profile-form, .webhook-form { grid-template-columns: 1fr; }
  .site-form { grid-template-columns: 1fr; }
  .bind-actions, .token-input, .fresh-row { align-items: stretch; flex-direction: column; }
  .btn-primary, .btn-ghost { justify-content: center; }
  .grid-tokens { grid-template-columns: minmax(130px, 1fr) 70px; }
  .grid-tokens > :nth-child(2), .grid-tokens > :nth-child(3), .grid-tokens > :nth-child(4) { display: none; }
}
</style>
