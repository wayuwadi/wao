<script setup lang="ts">
import { computed, reactive, ref, watch, watchEffect } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'
import { updateEmailCredential, updateProfile, unlinkIdentity } from '@/api/auth'
import { startFeishuLogin } from '@/api/feishu'
import { startWechatLogin } from '@/api/wechat'
import {
  fetchBuiltinMenuRegistry, fetchExternalAppRegistry, fetchSiteInfo, fetchWebhookConfig,
  saveBuiltinMenuRegistry, saveExternalAppRegistry, saveSiteInfo, saveWebhookConfig, testWebhook,
  type FeishuWebhookConfig, type SiteInfo,
} from '@/api/system'
import { useDataStore } from '@/stores/data'
import { useNavStore } from '@/stores/nav'
import { usePreferenceStore } from '@/stores/preferences'
import AiAccountPanel from '@/components/settings/AiAccountPanel.vue'
import MemoryMcpPanel from '@/components/settings/MemoryMcpPanel.vue'
import { useTheme, type ThemeMode } from '@/composables/theme'
import { useQuickLinks } from '@/composables/quickLinks'
import type { QuickLink } from '@/config/quickLinks'
import {
  DEFAULT_BUILTIN_MENUS,
  DEFAULT_EXTERNAL_APPS,
  externalAppPath,
  type BuiltinMenuConfig,
  type ExternalAppConfig,
} from '@/config/nav'

const session = useSessionStore()
const theme = useTheme()
const quickLinks = useQuickLinks()
const data = useDataStore()
const nav = useNavStore()
const preferences = usePreferenceStore()
const router = useRouter()
const route = useRoute()

const NAV = [
  { label: '组织管理', icon: 'ri-community-line', items: [['组织与成员', 'members'], ['日历账户 · CalDAV', 'caldav']] },
  { label: '系统', icon: 'ri-settings-4-line', items: [['网站信息 · 备案', 'site'], ['菜单配置', 'navigation'], ['通知 · 飞书 Webhook', 'webhook'], ['AI 账号', 'ai-accounts']] },
  { label: '个人', icon: 'ri-user-3-line', items: [['个人资料', 'profile'], ['账号与登录', 'account'], ['偏好', 'prefs'], ['快捷链接', 'quick-links'], ['Codex 记忆 · MCP', 'memory-mcp']] },
] as const

const requestedTab = (route.query.tab as string) || 'members'
const tab = ref<string>(requestedTab === 'api' ? 'memory-mcp' : requestedTab)
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

const musicPreferenceForm = reactive({ defaultOpen: false, launcherVisible: true })
const musicPreferenceSaving = ref(false)
const musicPreferenceError = ref('')

watch(
  [() => preferences.loaded, () => preferences.musicPlayerDefaultOpen, () => preferences.musicPlayerLauncherVisible],
  ([loaded, defaultOpen, launcherVisible]) => {
    if (!loaded || musicPreferenceSaving.value) return
    musicPreferenceForm.defaultOpen = defaultOpen
    musicPreferenceForm.launcherVisible = launcherVisible
  },
  { immediate: true },
)

async function reloadPreferences() {
  musicPreferenceError.value = ''
  await preferences.load(true)
  if (preferences.loadError) musicPreferenceError.value = preferences.loadError
}

async function saveMusicPreferences() {
  musicPreferenceSaving.value = true
  musicPreferenceError.value = ''
  try {
    await preferences.setMusicPlayerSettings({ ...musicPreferenceForm })
    toast('音乐播放器偏好已保存', 'ri-checkbox-circle-line')
  } catch (error) {
    musicPreferenceError.value = error instanceof Error ? error.message : '音乐播放器偏好保存失败'
  } finally {
    musicPreferenceSaving.value = false
  }
}

function cycleTheme() {
  const order: ThemeMode[] = ['light', 'dark', 'system']
  const idx = order.indexOf(theme.mode.value)
  const next = order[(idx + 1) % order.length]
  theme.setTheme(next ?? 'system')
}

// ---- 快捷链接管理 ----
const qlEditing = ref<QuickLink[]>([])
const qlBusy = ref(false)

function syncQlEditing() {
  qlEditing.value = quickLinks.links.value.map((l) => ({ ...l }))
}

watch(() => quickLinks.loaded.value, (loaded) => {
  if (loaded && qlEditing.value.length === 0) syncQlEditing()
})

watch(() => quickLinks.links.value, () => {
  if (quickLinks.loaded.value) syncQlEditing()
})

function addQlLink() {
  qlEditing.value.push({
    id: `custom-${Date.now()}`,
    label: '',
    subtitle: '',
    icon: 'ri-link',
    url: '',
    keywords: [],
  })
}

function removeQlLink(index: number) {
  qlEditing.value.splice(index, 1)
}

function moveQlLink(index: number, dir: -1 | 1) {
  const target = index + dir
  if (target < 0 || target >= qlEditing.value.length) return
  const arr = qlEditing.value
  const a = arr[index]
  const b = arr[target]
  if (a && b) {
    arr[index] = b
    arr[target] = a
  }
}

async function saveQlLinks() {
  const valid = qlEditing.value.filter((l) => l.label.trim() && l.url.trim())
  qlBusy.value = true
  try {
    await quickLinks.save(valid)
    syncQlEditing()
    toast('快捷链接已保存', 'ri-check-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '保存失败')
  } finally {
    qlBusy.value = false
  }
}

function resetQlLinks() {
  quickLinks.resetToDefaults()
  syncQlEditing()
  toast('已恢复默认链接，记得点保存', 'ri-history-line')
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
const externalApps = ref<ExternalAppConfig[]>(DEFAULT_EXTERNAL_APPS.map((app) => ({ ...app })))
const externalAppsLoaded = ref(false)
const externalAppsLoading = ref(false)
const externalAppsSaving = ref(false)
const externalAppsError = ref('')
const builtinMenus = ref<BuiltinMenuConfig[]>(DEFAULT_BUILTIN_MENUS.map((menu) => ({ ...menu })))
const builtinMenusLoaded = ref(false)
const builtinMenusSaving = ref(false)
const externalEditorMode = ref<'none' | 'edit' | 'new'>('none')
const editingExternalAppId = ref('')

function emptyExternalApp(): ExternalAppConfig {
  return {
    id: '', label: '', icon: 'ri-apps-2-line', group: 'PUBLIC', order: 20,
    visible: true, appUrl: '/', healthUrl: '',
  }
}

const externalAppForm = reactive<ExternalAppConfig>(emptyExternalApp())
const selectedExternalApp = computed(() =>
  externalApps.value.find((app) => app.id === editingExternalAppId.value),
)
const externalAppDirty = computed(() => {
  if (externalEditorMode.value === 'none') return false
  const baseline = externalEditorMode.value === 'edit' ? selectedExternalApp.value : emptyExternalApp()
  return JSON.stringify(externalAppForm) !== JSON.stringify(baseline)
})

function assignExternalApp(app: ExternalAppConfig) {
  Object.assign(externalAppForm, { ...app })
}

function confirmDiscardExternalDraft() {
  return !externalAppDirty.value || window.confirm('当前外部应用配置尚未保存，确认放弃这些修改吗？')
}

function editExternalApp(id: string, skipConfirm = false) {
  if (!skipConfirm && !confirmDiscardExternalDraft()) return
  const app = externalApps.value.find((item) => item.id === id)
  if (!app) return
  editingExternalAppId.value = id
  externalEditorMode.value = 'edit'
  assignExternalApp(app)
}

function startAddExternalApp() {
  if (!confirmDiscardExternalDraft()) return
  editingExternalAppId.value = ''
  externalEditorMode.value = 'new'
  assignExternalApp(emptyExternalApp())
}

function cancelExternalAppEdit() {
  if (!confirmDiscardExternalDraft()) return
  if (externalEditorMode.value === 'edit' && selectedExternalApp.value) {
    assignExternalApp(selectedExternalApp.value)
    return
  }
  const first = externalApps.value[0]
  if (first) editExternalApp(first.id, true)
  else externalEditorMode.value = 'none'
}

async function loadExternalAppSettings() {
  if (externalAppsLoading.value) return
  externalAppsLoading.value = true
  externalAppsError.value = ''
  try {
    const registry = await fetchExternalAppRegistry()
    externalApps.value = registry.apps.map((app) => ({ ...app }))
    externalAppsLoaded.value = true
    nav.applyExternalApps(registry.apps)
    const preferred = externalApps.value.find((app) => app.id === editingExternalAppId.value)
      ?? externalApps.value[0]
    if (preferred) editExternalApp(preferred.id, true)
    else externalEditorMode.value = 'none'
  } catch (error) {
    externalAppsError.value = error instanceof Error ? error.message : '读取外部应用注册表失败'
  } finally {
    externalAppsLoading.value = false
  }
}

async function loadBuiltinMenuSettings() {
  try {
    builtinMenus.value = (await fetchBuiltinMenuRegistry()).menus.map((menu) => ({ ...menu }))
    builtinMenusLoaded.value = true
    nav.applyBuiltinMenus(builtinMenus.value)
  } catch (error) {
    toast(error instanceof Error ? error.message : '读取内置菜单注册表失败', 'ri-error-warning-line')
  }
}

async function saveBuiltinMenuSettings() {
  builtinMenusSaving.value = true
  try {
    const registry = await saveBuiltinMenuRegistry(builtinMenus.value)
    builtinMenus.value = registry.menus.map((menu) => ({ ...menu }))
    nav.applyBuiltinMenus(registry.menus)
    toast('内置菜单注册表已保存并立即生效', 'ri-check-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '保存内置菜单注册表失败', 'ri-error-warning-line')
  } finally {
    builtinMenusSaving.value = false
  }
}

function canonicalExternalAppForm(): ExternalAppConfig | null {
  const app = {
    ...externalAppForm,
    id: externalAppForm.id.trim(),
    label: externalAppForm.label.trim(),
    icon: externalAppForm.icon.trim(),
    appUrl: externalAppForm.appUrl.trim(),
    healthUrl: externalAppForm.healthUrl.trim(),
    order: Number(externalAppForm.order),
  }
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(app.id)) {
    toast('应用标识只能使用小写字母、数字和中划线', 'ri-error-warning-line')
    return null
  }
  if (!app.label || !app.icon || !app.appUrl) {
    toast('请填写菜单名称、图标和应用入口', 'ri-error-warning-line')
    return null
  }
  const duplicate = externalApps.value.some(
    (item) => item.id === app.id && item.id !== editingExternalAppId.value,
  )
  if (duplicate) {
    toast('应用标识不能重复', 'ri-error-warning-line')
    return null
  }
  return app
}

async function persistExternalApps(next: ExternalAppConfig[], selectedId: string, successMessage: string) {
  externalAppsSaving.value = true
  externalAppsError.value = ''
  try {
    const registry = await saveExternalAppRegistry(next)
    externalApps.value = registry.apps.map((app) => ({ ...app }))
    externalAppsLoaded.value = true
    nav.applyExternalApps(registry.apps)
    const selected = externalApps.value.find((app) => app.id === selectedId) ?? externalApps.value[0]
    if (selected) editExternalApp(selected.id, true)
    else externalEditorMode.value = 'none'
    toast(successMessage, 'ri-check-line')
  } catch (error) {
    externalAppsError.value = error instanceof Error ? error.message : '保存外部应用注册表失败'
    toast(externalAppsError.value, 'ri-error-warning-line')
  } finally {
    externalAppsSaving.value = false
  }
}

async function saveExternalAppSettings() {
  const app = canonicalExternalAppForm()
  if (!app) return
  const next = externalApps.value.map((item) => ({ ...item }))
  const index = next.findIndex((item) => item.id === editingExternalAppId.value)
  if (externalEditorMode.value === 'edit' && index >= 0) next[index] = app
  else next.push(app)
  await persistExternalApps(next, app.id, '外部应用注册表已保存并立即生效')
}

async function deleteExternalApp(app: ExternalAppConfig) {
  if (!confirmDiscardExternalDraft()) return
  if (!window.confirm(`确认删除「${app.label}」的菜单注册吗？应用服务和数据不会被删除。`)) return
  const next = externalApps.value.filter((item) => item.id !== app.id)
  await persistExternalApps(next, next[0]?.id ?? '', `已删除「${app.label}」的菜单注册`)
}

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
  if (t === 'navigation') {
    if (!externalAppsLoaded.value) loadExternalAppSettings()
    if (!builtinMenusLoaded.value) loadBuiltinMenuSettings()
  }
  if (t === 'webhook' && !webhook.value) loadWebhook()
  if (t === 'quick-links') quickLinks.load()
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

        <!-- 系统 · 菜单配置 -->
        <template v-else-if="tab === 'navigation'">
          <div class="s-h1">一级菜单</div>
          <div class="card">
            <div class="card-row wrap">
              <div class="card-title">内置菜单注册表</div>
              <span class="connected"><span class="c-dot"></span>{{ builtinMenus.length }} 个内置菜单</span>
              <div class="spacer"></div>
              <button class="btn-primary" :disabled="builtinMenusSaving" @click="saveBuiltinMenuSettings">
                {{ builtinMenusSaving ? '保存中…' : '保存并立即生效' }}
              </button>
            </div>
            <div class="builtin-menu-list">
              <div v-for="menuItem in builtinMenus" :key="menuItem.id" class="builtin-menu-row">
                <code>{{ menuItem.id }}</code>
                <input v-model.trim="menuItem.label" maxlength="24" aria-label="菜单名称" />
                <input v-model.trim="menuItem.icon" maxlength="64" aria-label="菜单图标" />
                <input v-model="menuItem.group" list="builtin-menu-groups" aria-label="所属分组" />
                <input v-model.number="menuItem.order" inputmode="numeric" aria-label="排序值" />
                <button
                  type="button"
                  class="visibility-choice compact"
                  :class="{ on: menuItem.visible }"
                  @click="menuItem.visible = !menuItem.visible"
                >
                  <i :class="menuItem.visible ? 'ri-eye-line' : 'ri-eye-off-line'"></i>
                  {{ menuItem.visible ? '显示' : '隐藏' }}
                </button>
              </div>
              <datalist id="builtin-menu-groups">
                <option value="ROOT">顶层</option>
                <option value="PUBLIC">公共</option>
                <option value="PERSONAL">个人</option>
                <option value="TOOLS">工具</option>
              </datalist>
            </div>
            <div class="hint">稳定标识和路由仍由代码保护；名称、图标、分组、排序和显隐与外部应用一样统一在注册表维护。隐藏菜单不会禁用直达地址。</div>
          </div>

          <div class="card">
            <div class="card-row wrap">
              <div class="card-title">外部应用菜单注册表</div>
              <span v-if="externalAppsLoaded && !externalAppsError" class="connected"><span class="c-dot"></span>{{ externalApps.length }} 个应用</span>
              <span v-else-if="externalAppsError" class="connected err"><span class="c-dot"></span>{{ externalAppsError }}</span>
              <div class="spacer"></div>
              <button type="button" class="btn-primary sm" :disabled="externalAppsLoading || externalAppsSaving" @click="startAddExternalApp"><i class="ri-add-line"></i>注册应用</button>
            </div>

            <div v-if="externalAppsLoading" class="menu-loading">正在读取外部应用注册表…</div>
            <div v-else-if="externalAppsError && !externalAppsLoaded" class="registry-load-error">
              <span>注册表暂时无法读取，现有菜单不会被覆盖。</span>
              <button type="button" class="btn-ghost" @click="loadExternalAppSettings">重新读取</button>
            </div>
            <div v-else class="registry-layout">
              <div class="registry-list">
                <div v-for="app in externalApps" :key="app.id" class="registry-row" :class="{ active: externalEditorMode === 'edit' && editingExternalAppId === app.id }">
                  <button type="button" class="registry-select" @click="editExternalApp(app.id)">
                    <span class="registry-icon"><i :class="app.icon"></i></span>
                    <span class="registry-meta">
                      <strong>{{ app.label }}</strong>
                      <small>{{ app.id }} · {{ { PUBLIC: '公共', PERSONAL: '个人', TOOLS: '工具' }[app.group] }} · 排序 {{ app.order }}</small>
                    </span>
                    <span class="registry-state" :class="{ off: !app.visible }">{{ app.visible ? '显示中' : '已隐藏' }}</span>
                  </button>
                  <button type="button" class="registry-delete" :disabled="externalAppsSaving" :title="`删除 ${app.label}`" @click="deleteExternalApp(app)"><i class="ri-delete-bin-line"></i></button>
                </div>
                <div v-if="!externalApps.length" class="registry-empty">
                  <i class="ri-apps-2-add-line"></i>
                  <strong>还没有外部应用</strong>
                  <span>注册后会自动生成菜单、页签和嵌入页面。</span>
                </div>
              </div>

              <form v-if="externalEditorMode !== 'none'" class="site-form menu-form registry-editor" @submit.prevent="saveExternalAppSettings">
                <div class="editor-title">{{ externalEditorMode === 'new' ? '注册新应用' : `编辑「${selectedExternalApp?.label ?? externalAppForm.label}」` }}</div>
                <label>
                  <span>应用标识</span>
                  <input v-model="externalAppForm.id" maxlength="40" required :disabled="externalEditorMode === 'edit'" placeholder="例如 data-studio" />
                  <small>保存后生成 {{ externalAppForm.id ? externalAppPath(externalAppForm.id) : '/apps/{应用标识}' }}，标识不可修改。</small>
                </label>
                <label>
                  <span>菜单名称</span>
                  <input v-model="externalAppForm.label" maxlength="24" required placeholder="例如 数据工作台" />
                </label>
                <label>
                  <span>菜单图标</span>
                  <input v-model="externalAppForm.icon" list="external-app-icons" maxlength="64" required placeholder="ri-apps-2-line" />
                  <datalist id="external-app-icons">
                    <option value="ri-apps-2-line"></option>
                    <option value="ri-sparkling-2-line"></option>
                    <option value="ri-robot-2-line"></option>
                    <option value="ri-dashboard-3-line"></option>
                    <option value="ri-global-line"></option>
                    <option value="ri-code-box-line"></option>
                    <option value="ri-gamepad-line"></option>
                  </datalist>
                </label>
                <label>
                  <span>所属分组</span>
                  <select v-model="externalAppForm.group">
                    <option value="PUBLIC">公共</option>
                    <option value="PERSONAL">个人</option>
                    <option value="TOOLS">工具</option>
                  </select>
                </label>
                <label>
                  <span>排序值</span>
                  <input v-model.number="externalAppForm.order" type="number" min="0" max="999" required />
                  <small>数值越小越靠前。</small>
                </label>
                <label>
                  <span>菜单可见性</span>
                  <button type="button" class="visibility-choice" :class="{ on: externalAppForm.visible }" :aria-pressed="externalAppForm.visible" @click="externalAppForm.visible = !externalAppForm.visible">
                    <span class="choice-icon"><i :class="externalAppForm.visible ? 'ri-eye-line' : 'ri-eye-off-line'"></i></span>
                    <span>{{ externalAppForm.visible ? '显示在一级导航中' : '从一级导航中隐藏' }}</span>
                  </button>
                </label>
                <label class="wide-field">
                  <span>应用入口</span>
                  <input v-model="externalAppForm.appUrl" maxlength="255" required placeholder="/my-app/ 或 https://app.example.com/" />
                  <small>同域路径会沿用 WAO Session；跨域 HTTPS 地址是否可嵌入取决于目标应用的 Cookie 和 CSP。</small>
                </label>
                <label class="wide-field">
                  <span>健康检查地址（可选）</span>
                  <input v-model="externalAppForm.healthUrl" maxlength="255" placeholder="/api/my-app/health" />
                  <small>配置后会先探测服务再加载页面；留空则直接加载应用入口。</small>
                </label>
                <div class="menu-preview">
                  <span class="preview-label">导航预览</span>
                  <span class="menu-preview-group">{{ { PUBLIC: '公共', PERSONAL: '个人', TOOLS: '工具' }[externalAppForm.group] }}</span>
                  <span v-if="externalAppForm.visible" class="menu-preview-item"><i :class="externalAppForm.icon || 'ri-apps-2-line'"></i>{{ externalAppForm.label || '未命名应用' }}</span>
                  <span v-else class="menu-preview-hidden">菜单已隐藏，注册路由仍然可直接访问</span>
                </div>
                <div class="site-actions">
                  <button type="button" class="btn-ghost" :disabled="externalAppsSaving" @click="cancelExternalAppEdit">取消</button>
                  <button class="btn-primary" :disabled="externalAppsSaving || !externalAppDirty">
                    {{ externalAppsSaving ? '保存中…' : externalAppDirty ? '保存并立即生效' : '已保存' }}
                  </button>
                </div>
              </form>
              <div v-else class="registry-no-selection">选择一个应用编辑，或注册新应用。</div>
            </div>
            <div class="hint">注册表全局生效：新增、改名、换组、调序、隐藏或删除菜单都只写服务端配置，不需要重新构建 WAO。删除注册只移除菜单和通用页面配置，不会删除独立应用的服务或数据。</div>
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
          <div class="card thin music-preferences">
            <div class="card-row wrap">
              <div>
                <div class="card-title">音乐播放器</div>
                <div class="pref-desc">设置按当前成员保存，换设备登录后仍会生效。</div>
              </div>
              <span v-if="musicPreferenceSaving" class="connected"><span class="c-dot"></span>保存中…</span>
              <span v-else-if="preferences.loading || !preferences.loaded" class="connected"><span class="c-dot"></span>读取中…</span>
              <span v-else-if="!preferences.loadError && !musicPreferenceError" class="connected"><span class="c-dot"></span>已读取个人偏好</span>
            </div>
            <div v-if="preferences.loadError || musicPreferenceError" class="preference-error" role="alert">
              <span>{{ musicPreferenceError || preferences.loadError }}</span>
              <button type="button" class="btn-ghost sm" :disabled="preferences.loading" @click="reloadPreferences">
                {{ preferences.loading ? '重新读取中…' : '重新读取' }}
              </button>
            </div>
            <div v-else-if="preferences.loading || !preferences.loaded" class="preference-loading" aria-live="polite">
              <i class="ri-loader-4-line"></i>正在读取当前成员的播放器偏好…
            </div>
            <template v-else>
              <div class="pref-row">
                <div class="pref-main">
                  <div class="pref-name">登录后默认打开</div>
                  <div class="pref-desc">关闭时只显示右下角入口，不会自动弹出播放器面板。</div>
                </div>
                <button type="button" class="toggle" :class="{ on: musicPreferenceForm.defaultOpen }" aria-label="登录后默认打开播放器" :aria-pressed="musicPreferenceForm.defaultOpen" :disabled="musicPreferenceSaving" @click="musicPreferenceForm.defaultOpen = !musicPreferenceForm.defaultOpen"><span class="knob"></span></button>
              </div>
              <div class="pref-row">
                <div class="pref-main">
                  <div class="pref-name">显示播放器入口</div>
                  <div class="pref-desc">关闭后隐藏右下角音乐按钮；需要恢复时返回此页开启。</div>
                </div>
                <button type="button" class="toggle" :class="{ on: musicPreferenceForm.launcherVisible }" aria-label="显示播放器入口" :aria-pressed="musicPreferenceForm.launcherVisible" :disabled="musicPreferenceSaving" @click="musicPreferenceForm.launcherVisible = !musicPreferenceForm.launcherVisible"><span class="knob"></span></button>
              </div>
              <div class="music-preference-actions">
                <span>“默认打开”在下次进入 WAO 时应用；当前播放不会被中断。</span>
                <button type="button" class="btn-primary" :disabled="musicPreferenceSaving" @click="saveMusicPreferences">
                  {{ musicPreferenceSaving ? '保存中…' : '保存播放器设置' }}
                </button>
              </div>
            </template>
          </div>
        </template>

        <!-- 快捷链接 -->
        <template v-else-if="tab === 'quick-links'">
          <div class="s-h1">快捷链接</div>
          <div class="card thin">
            <div class="ql-desc">侧边栏左下角的快捷入口，点击直接跳转外部地址。可自由增删和调整顺序，保存后生效。</div>
            <div v-for="(link, i) in qlEditing" :key="link.id" class="ql-edit-row">
              <div class="ql-edit-move">
                <button class="ql-move-btn" :disabled="i === 0" title="上移" @click="moveQlLink(i, -1)"><i class="ri-arrow-up-s-line"></i></button>
                <button class="ql-move-btn" :disabled="i === qlEditing.length - 1" title="下移" @click="moveQlLink(i, 1)"><i class="ri-arrow-down-s-line"></i></button>
              </div>
              <i :class="link.icon || 'ri-link'" class="ql-edit-icon"></i>
              <div class="ql-edit-fields">
                <input v-model.trim="link.label" class="ql-input" placeholder="名称（如 GitHub 仓库）" />
                <input v-model.trim="link.url" class="ql-input" placeholder="https://…" />
                <input v-model.trim="link.subtitle" class="ql-input ql-input-sub" placeholder="描述（可选）" />
                <input v-model.trim="link.icon" class="ql-input ql-input-icon" placeholder="图标类名（如 ri-github-fill）" />
              </div>
              <button class="ql-remove" title="删除" @click="removeQlLink(i)"><i class="ri-delete-bin-line"></i></button>
            </div>
            <div class="ql-edit-actions">
              <button class="btn-ghost" @click="addQlLink"><i class="ri-add-line"></i>添加链接</button>
              <button class="btn-ghost" @click="resetQlLinks"><i class="ri-history-line"></i>恢复默认</button>
              <button class="btn-primary" :disabled="qlBusy" @click="saveQlLinks">{{ qlBusy ? '保存中…' : '保存' }}</button>
            </div>
          </div>
        </template>

        <!-- Codex 共享记忆 MCP -->
        <template v-else-if="tab === 'memory-mcp'">
          <MemoryMcpPanel />
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.builtin-menu-list { display: grid; gap: 7px; margin-top: 14px; }
.builtin-menu-row {
  display: grid;
  grid-template-columns: 92px 1fr 1.25fr 100px 72px 82px;
  gap: 8px;
  align-items: center;
}
.builtin-menu-row code { color: var(--wao-text-weak); font-size: 11.5px; }
.builtin-menu-row input {
  min-width: 0;
  height: 34px;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
  padding: 0 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
.visibility-choice.compact { min-height: 34px; padding: 0 8px; justify-content: center; }
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
.music-preferences { margin-top: 14px; }
.preference-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 0 2px; color: var(--wao-danger); font-size: 12.5px; }
.preference-loading { display: flex; align-items: center; gap: 7px; padding: 16px 0 2px; color: var(--wao-text-weak); font-size: 12.5px; }
.preference-loading i { animation: preference-spin 1s linear infinite; }
.music-preference-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-top: 14px; color: var(--wao-text-weak); font-size: 12px; }
@keyframes preference-spin { to { transform: rotate(360deg); } }
.webhook-form { grid-template-columns: 1fr auto auto; }
.toggle:disabled { opacity: .55; cursor: not-allowed; }
.btn-ghost:disabled { opacity: .55; cursor: not-allowed; }
.credential-form input { min-width: 0; height: 34px; box-sizing: border-box; border: 1px solid var(--wao-line-control); border-radius: 8px; padding: 0 10px; outline: none; background: var(--wao-bg-card); color: var(--wao-text); }
.credential-form input:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 3px var(--wao-primary-soft); }
.site-form { margin-top: 16px; display: grid; grid-template-columns: 1fr 1fr; gap: 13px 16px; }
.site-form label { display: grid; gap: 6px; color: var(--wao-text-3); font-size: 12px; }
.site-form input { height: 36px; box-sizing: border-box; padding: 0 11px; border: 1px solid var(--wao-line-control); border-radius: 8px; outline: none; background: var(--wao-bg-card); color: var(--wao-text); }
.site-form input:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 3px var(--wao-primary-soft); }
.site-form select { height: 36px; box-sizing: border-box; padding: 0 10px; border: 1px solid var(--wao-line-control); border-radius: 8px; outline: none; background: var(--wao-bg-card); color: var(--wao-text); }
.site-form select:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 3px var(--wao-primary-soft); }
.site-form small { color: var(--wao-text-faint); line-height: 1.5; }
.site-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 10px; }
.menu-loading { margin-top: 16px; padding: 28px 12px; border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-weak); text-align: center; font-size: 12.5px; }
.registry-load-error { margin-top: 16px; padding: 22px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 12.5px; }
.registry-layout { margin-top: 16px; display: grid; grid-template-columns: minmax(230px, 300px) minmax(0, 1fr); gap: 16px; align-items: start; }
.registry-list { display: flex; flex-direction: column; gap: 7px; }
.registry-row { display: flex; min-width: 0; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card); overflow: hidden; }
.registry-row.active { border-color: color-mix(in srgb, var(--wao-primary) 45%, transparent); box-shadow: 0 0 0 3px var(--wao-primary-soft); }
.registry-select { flex: 1; min-width: 0; padding: 10px; display: flex; align-items: center; gap: 9px; border: 0; background: transparent; cursor: pointer; text-align: left; }
.registry-select:hover, .registry-delete:hover { background: var(--wao-bg-hover-2); }
.registry-icon { width: 32px; height: 32px; flex: none; display: grid; place-items: center; border-radius: 8px; background: var(--wao-primary-soft); color: var(--wao-primary); font-size: 17px; }
.registry-meta { flex: 1; min-width: 0; display: grid; gap: 3px; }
.registry-meta strong { overflow: hidden; color: var(--wao-text); font-size: 12.5px; text-overflow: ellipsis; white-space: nowrap; }
.registry-meta small { overflow: hidden; color: var(--wao-text-faint); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.registry-state { flex: none; color: var(--wao-success-strong); font-size: 10.5px; }
.registry-state.off { color: var(--wao-text-faint); }
.registry-delete { width: 34px; flex: none; border: 0; border-left: 1px solid var(--wao-line); background: transparent; color: var(--wao-text-faint); cursor: pointer; }
.registry-delete:hover { color: var(--wao-danger); }
.registry-empty, .registry-no-selection { min-height: 150px; padding: 20px; display: grid; place-items: center; align-content: center; gap: 7px; border: 1px dashed var(--wao-line-strong); border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-faint); text-align: center; font-size: 11.5px; }
.registry-empty i { font-size: 28px; }
.registry-empty strong { color: var(--wao-text-3); font-size: 12.5px; }
.registry-editor { margin-top: 0; padding: 15px; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-sub); }
.editor-title { grid-column: 1 / -1; padding-bottom: 10px; border-bottom: 1px solid var(--wao-line); color: var(--wao-text); font-size: 13px; font-weight: 600; }
.wide-field { grid-column: 1 / -1; }
.visibility-choice { height: 36px; padding: 0 11px; display: flex; align-items: center; gap: 8px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-3); cursor: pointer; text-align: left; }
.visibility-choice.on { border-color: color-mix(in srgb, var(--wao-primary) 35%, transparent); background: var(--wao-primary-soft); color: var(--wao-primary); }
.choice-icon { width: 20px; display: inline-grid; place-items: center; }
.menu-preview { grid-column: 1 / -1; padding: 13px 14px; display: grid; grid-template-columns: 72px 1fr; gap: 8px 12px; border: 1px dashed var(--wao-line-strong); border-radius: 9px; background: var(--wao-bg-sub); font-size: 12px; }
.menu-preview .preview-label { width: auto; margin: 0; text-align: left; }
.menu-preview-group { color: var(--wao-text-faint); font-weight: 600; }
.menu-preview-item { grid-column: 2; display: inline-flex; align-items: center; gap: 7px; color: var(--wao-text); font-weight: 500; }
.menu-preview-hidden { grid-column: 2; color: var(--wao-text-faint); }
.connected.err { color: var(--wao-danger); }
.connected.err .c-dot { background: var(--wao-danger); }
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

@media (max-width: 900px) {
  .builtin-menu-row { grid-template-columns: 72px 1fr 1fr; }
  .registry-layout { grid-template-columns: 1fr; }
}

@media (max-width: 760px) {
  .settings { display: block; height: auto; min-height: 100%; }
  .snav { width: 100%; height: auto; padding: 8px; border-right: 0; border-bottom: 1px solid var(--wao-line); overflow-x: auto; overflow-y: hidden; }
  .snav-title, .snav-group, .snav-foot { display: none; }
  .snav-items { flex-direction: row; width: max-content; }
  .snav-item { height: 34px; padding: 0 10px; white-space: nowrap; }
  .music-preference-actions { align-items: stretch; flex-direction: column; }
  .music-preference-actions .btn-primary { justify-content: center; }
  .spane { overflow: visible; }
  .scontent { padding: 16px 12px 28px; }
  .card { padding: 14px; }
  .credential-form, .profile-form, .webhook-form { grid-template-columns: 1fr; }
  .site-form { grid-template-columns: 1fr; }
  .builtin-menu-row { grid-template-columns: 64px minmax(0, 1fr); }
  .registry-load-error { align-items: stretch; flex-direction: column; }
  .bind-actions, .token-input, .fresh-row { align-items: stretch; flex-direction: column; }
  .btn-primary, .btn-ghost { justify-content: center; }
  .grid-tokens { grid-template-columns: minmax(130px, 1fr) 70px; }
  .grid-tokens > :nth-child(2), .grid-tokens > :nth-child(3), .grid-tokens > :nth-child(4) { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .preference-loading i { animation: none; }
}

/* 快捷链接管理 */
.ql-desc {
  font-size: 12.5px;
  color: var(--wao-text-3);
  padding: 8px 0 4px;
}
.ql-edit-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 0;
  border-top: 1px solid var(--wao-divider);
}
.ql-edit-move {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  padding-top: 4px;
}
.ql-move-btn {
  border: none;
  background: none;
  cursor: pointer;
  color: var(--wao-text-faint);
  padding: 1px;
  border-radius: 4px;
  display: flex;
}
.ql-move-btn:hover:not(:disabled) {
  color: var(--wao-text-2);
  background: var(--wao-bg-hover);
}
.ql-move-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
.ql-move-btn i {
  font-size: 15px;
}
.ql-edit-icon {
  font-size: 18px;
  color: var(--wao-text-3);
  padding-top: 6px;
  flex-shrink: 0;
}
.ql-edit-fields {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}
.ql-input {
  height: 30px;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
  padding: 0 9px;
  font-size: 12.5px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
}
.ql-input:focus {
  border-color: var(--wao-primary);
}
.ql-input-sub, .ql-input-icon {
  font-size: 11.5px;
  color: var(--wao-text-3);
}
.ql-remove {
  border: none;
  background: none;
  cursor: pointer;
  color: var(--wao-text-faint);
  padding: 6px;
  border-radius: 6px;
  flex-shrink: 0;
}
.ql-remove:hover {
  color: var(--wao-danger);
  background: var(--wao-danger-bg);
}
.ql-remove i {
  font-size: 15px;
}
.ql-edit-actions {
  display: flex;
  gap: 8px;
  padding: 12px 0 6px;
  flex-wrap: wrap;
}
</style>
