<script setup lang="ts">
import NoteDiscoveryPreference from '@/components/notes/NoteDiscoveryPreference.vue'
import PageLayoutPreference from '@/components/settings/PageLayoutPreference.vue'
import HomeDateReminderSettings from '@/components/settings/HomeDateReminderSettings.vue'
import NoteAiSettingsForm from '@/components/notes/NoteAiSettingsForm.vue'
import { computed, onUnmounted, reactive, ref, watch, watchEffect } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { companionQuiet, setCompanionQuiet } from '@/composables/quietCompanions'
import { toast } from '@/composables/toast'
import { apiCreateAccount, updateEmailCredential, updateProfile, unlinkIdentity } from '@/api/auth'
import { startFeishuLogin } from '@/api/feishu'
import {
  fetchSiteInfo,
  saveSiteInfo,
  type SiteInfo,
} from '@/api/system'
import {
  usePreferenceStore,
  type Celebration,
  type ControlStyle,
  type NoteFullscreenReminderSettings,
  type WeekStart,
} from '@/stores/preferences'
import { useUiStore } from '@/stores/ui'
import AiAccountPanel from '@/components/settings/AiAccountPanel.vue'
import WebhookConfigPanel from '@/components/settings/WebhookConfigPanel.vue'
import NavigationLinkSettingsPanel from '@/components/settings/NavigationLinkSettingsPanel.vue'
import WebPetSettingsPanel from '@/components/settings/WebPetSettingsPanel.vue'
import NotificationSettingsPanel from '@/components/settings/NotificationSettingsPanel.vue'
import TianrenNotificationSettingsPanel from '@/components/settings/TianrenNotificationSettingsPanel.vue'
import ProjectNotificationSettingsPanel from '@/components/settings/ProjectNotificationSettingsPanel.vue'
import { useTheme, type ThemeMode } from '@/composables/theme'
import { SETTINGS_CATEGORIES, SETTINGS_ENTRIES, normalizeSettingsTab, settingsCategory } from '@/utils/settingsNavigation'
import { readReminderDraft, rememberReminderDraft } from '@/utils/settingsReminderDraft'

const session = useSessionStore()
const theme = useTheme()
const preferences = usePreferenceStore()
const ui = useUiStore()
const router = useRouter()
const route = useRoute()
const webhookPanel = ref<{ confirmLeave: () => boolean } | null>(null)
const navigationPanel = ref<{ confirmLeave: () => boolean } | null>(null)
const webPetPanel = ref<{ confirmLeave: () => boolean } | null>(null)
const tianrenNotificationPanel = ref<{ confirmLeave: () => boolean } | null>(null)
const notificationSettingsPanel = ref<{ confirmLeave: () => boolean } | null>(null)
const discoveryPanel = ref<{ confirmLeave: () => boolean } | null>(null)
const homeDateSettings = ref<{ confirmLeave: () => boolean } | null>(null)

const tab = ref(normalizeSettingsTab(route.query.tab, route.hash))
const category = computed(() => settingsCategory(tab.value))
const categoryInfo = computed(() => SETTINGS_CATEGORIES.find(item => item.id === category.value)!)
const categoryEntries = computed(() => SETTINGS_ENTRIES[tab.value as keyof typeof SETTINGS_ENTRIES] ?? [])
const isDetail = computed(() => tab.value !== category.value)
let skipWebhookRouteGuardOnce = false

function selectTab(next: string) {
  if (next === tab.value) return
  // 路由守卫只检查当前面板；导航成功后由 query watcher 切换内容。
  void router.replace({ query: { ...route.query, tab: next === 'common' ? undefined : next }, hash: '' })
}

watch(
  () => [route.query.tab, route.hash] as const,
  ([value, hash]) => {
    tab.value = normalizeSettingsTab(value, hash)
  },
)

const removeWebhookRouteGuard = router.beforeEach(() => {
  if (skipWebhookRouteGuardOnce) {
    skipWebhookRouteGuardOnce = false
    return true
  }
  return confirmCurrentSettings()
})
function confirmCurrentSettings(): boolean {
  if (['common','appearance'].includes(tab.value) && homeDateSettings.value && !homeDateSettings.value.confirmLeave()) return false
  if (tab.value === 'webhook' && webhookPanel.value && !webhookPanel.value.confirmLeave()) return false
  if (tab.value === 'navigation' && navigationPanel.value
      && !navigationPanel.value.confirmLeave()) return false
  if (tab.value === 'tianren-notifications' && tianrenNotificationPanel.value && !tianrenNotificationPanel.value.confirmLeave()) return false
  if (tab.value === 'notification-policy' && notificationSettingsPanel.value
      && !notificationSettingsPanel.value.confirmLeave()) return false
  if (tab.value === 'pet' && webPetPanel.value && !webPetPanel.value.confirmLeave()) return false
  if (tab.value === 'notes' && discoveryPanel.value && !discoveryPanel.value.confirmLeave()) return false
  if (tab.value === 'notes' && noteReminderSaving.value) {
    toast('小记提醒正在保存，请稍后再离开')
    return false
  }
  if (tab.value === 'notes' && noteReminderDirty.value && !window.confirm('小记提醒还有未保存的修改，确认离开吗？')) return false
  return true
}
onUnmounted(removeWebhookRouteGuard)
const accountForm = reactive({ email: '', currentPassword: '', newPassword: '' })
const profileForm = reactive({ name: '', initial: '' })
const accountBusy = ref(false)
const identities = computed(() => session.account?.identities ?? [])
const providerNames: Record<string, string> = { email: '邮箱密码', feishu: '飞书' }
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

const THEME_LABELS: Record<ThemeMode, string> = {
  light: '明亮',
  dark: '深色',
  system: '跟随系统',
}

const CONTROL_STYLE_OPTIONS: Array<{ value: ControlStyle; name: string; desc: string }> = [
  { value: 'system', name: '系统默认', desc: '当前跟随极简风格，以后可随系统设计升级' },
  { value: 'minimal', name: '极简风格', desc: '收起可从选中值判断的标签，适合窄屏和紧凑卡片' },
  { value: 'detailed', name: '详细风格', desc: '始终显示控件的用途标签' },
]
const controlStyleForm = ref<ControlStyle>('system')
const controlStyleSaving = ref(false)
const controlStyleError = ref('')
const lastControlStyleAttempt = ref<ControlStyle>('system')
const personalPreferenceForm = reactive<{ weekStart: WeekStart; celebration: Celebration }>({
  weekStart: '周一',
  celebration: '细腻纸屑',
})
const personalPreferenceSaving = ref<'weekStart' | 'celebration' | ''>('')
const personalPreferenceError = ref('')
const lastPersonalPreferenceAttempt = ref<{ key: 'weekStart' | 'celebration'; value: WeekStart | Celebration } | null>(null)

const reminderDraft = readReminderDraft(session.currentUser.id)
const noteReminderForm = reactive<NoteFullscreenReminderSettings>(reminderDraft ?? { ...preferences.noteFullscreenReminder })
const noteReminderBaseline = ref({ ...preferences.noteFullscreenReminder })
const noteReminderDirty = computed(() => noteReminderForm.enabled !== noteReminderBaseline.value.enabled
  || noteReminderForm.delayMinutes !== noteReminderBaseline.value.delayMinutes)
const noteReminderStorageError = ref('')
const noteReminderSaving = ref(false)
const noteReminderError = ref('')
watch(
  [() => preferences.loaded, () => preferences.controlStyle],
  ([loaded, style]) => {
    if (!loaded || controlStyleSaving.value) return
    controlStyleForm.value = style
  },
  { immediate: true },
)

watch(
  [() => preferences.loaded, () => preferences.weekStart, () => preferences.celebration],
  ([loaded, weekStart, celebration]) => {
    if (!loaded || personalPreferenceSaving.value) return
    personalPreferenceForm.weekStart = weekStart
    personalPreferenceForm.celebration = celebration
  },
  { immediate: true },
)

watch(
  [() => preferences.loaded, () => preferences.noteFullscreenReminder.enabled, () => preferences.noteFullscreenReminder.delayMinutes],
  ([loaded, enabled, delayMinutes]) => {
    if (!loaded) return
    const keepDraft = noteReminderDirty.value
    noteReminderBaseline.value = { enabled, delayMinutes }
    if (noteReminderSaving.value || keepDraft) return
    Object.assign(noteReminderForm, { enabled, delayMinutes })
  },
  { immediate: true },
)

watch([() => noteReminderForm.enabled, () => noteReminderForm.delayMinutes, noteReminderDirty], () => {
  noteReminderStorageError.value = rememberReminderDraft(session.currentUser.id, noteReminderDirty.value ? { ...noteReminderForm } : null)
    ? '' : '浏览器暂不能保留提醒草稿，刷新前请先保存。'
})
function protectReminderUnload(event: BeforeUnloadEvent) {
  if (!noteReminderDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}
window.addEventListener('beforeunload', protectReminderUnload)
onUnmounted(() => window.removeEventListener('beforeunload', protectReminderUnload))

async function reloadPreferences() {
  controlStyleError.value = ''
  noteReminderError.value = ''
  personalPreferenceError.value = ''
  await preferences.load(true)
}

async function savePersonalPreference(
  key: 'weekStart' | 'celebration',
  value: WeekStart | Celebration,
) {
  if (personalPreferenceSaving.value) return
  lastPersonalPreferenceAttempt.value = { key, value }
  personalPreferenceSaving.value = key
  personalPreferenceError.value = ''
  try {
    if (key === 'weekStart') {
      await preferences.setWeekStart(value as WeekStart)
      personalPreferenceForm.weekStart = preferences.weekStart
      toast('每周起始日已保存并应用到日历', 'ri-calendar-check-line')
    } else {
      await preferences.setCelebration(value as Celebration)
      personalPreferenceForm.celebration = preferences.celebration
      if (value !== '关闭') ui.celebrate(value)
      toast(value === '关闭' ? '庆祝动画已关闭' : '庆祝动画已保存', 'ri-checkbox-circle-line')
    }
  } catch (error) {
    personalPreferenceForm.weekStart = preferences.weekStart
    personalPreferenceForm.celebration = preferences.celebration
    personalPreferenceError.value = error instanceof Error ? error.message : '个人偏好保存失败'
  } finally {
    personalPreferenceSaving.value = ''
  }
}

function retryPersonalPreference() {
  const attempt = lastPersonalPreferenceAttempt.value
  if (attempt) void savePersonalPreference(attempt.key, attempt.value)
}

async function saveControlStyle(style: ControlStyle) {
  if (controlStyleSaving.value) return
  lastControlStyleAttempt.value = style
  controlStyleForm.value = style
  controlStyleSaving.value = true
  controlStyleError.value = ''
  try {
    await preferences.setControlStyle(style)
    toast('界面控件风格已保存', 'ri-checkbox-circle-line')
  } catch (error) {
    controlStyleForm.value = preferences.controlStyle
    controlStyleError.value = error instanceof Error ? error.message : '界面控件风格保存失败'
  } finally {
    controlStyleSaving.value = false
  }
}

async function saveNoteReminderPreferences() {
  if (noteReminderSaving.value) return
  noteReminderSaving.value = true
  noteReminderError.value = ''
  try {
    await preferences.setNoteFullscreenReminder({ ...noteReminderForm })
    toast('小记全屏提醒设置已保存', 'ri-checkbox-circle-line')
  } catch (error) {
    noteReminderError.value = error instanceof Error ? error.message : '小记全屏提醒设置保存失败'
  } finally {
    noteReminderSaving.value = false
  }
}

const canManageMembers = computed(() => ['管理员', '超级管理员'].includes(session.currentUser.role))
const accountCreateOpen = ref(false)
const accountCreateForm = reactive({ name: '', email: '', password: '' })
const accountCreateBusy = ref(false)
const accountCreateError = ref('')

function closeAccountCreate(force = false) {
  if (accountCreateBusy.value && !force) return
  accountCreateOpen.value = false
  accountCreateError.value = ''
  accountCreateForm.name = ''
  accountCreateForm.email = ''
  accountCreateForm.password = ''
}

async function createMemberAccount() {
  if (accountCreateBusy.value) return
  accountCreateBusy.value = true
  accountCreateError.value = ''
  try {
    await apiCreateAccount(accountCreateForm.name, accountCreateForm.email, accountCreateForm.password)
    await session.refreshDirectory()
    const memberName = accountCreateForm.name
    closeAccountCreate(true)
    toast(`已创建成员账号「${memberName}」`, 'ri-user-add-line')
  } catch (error) {
    accountCreateError.value = error instanceof Error ? error.message : '成员账号创建失败'
  } finally {
    accountCreateBusy.value = false
  }
}

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

watch(tab, (t) => {
  if (t === 'site' && !siteLoaded.value) loadSiteInfo()

}, { immediate: true })
async function logout() {
  if (!confirmCurrentSettings()) return
  await session.logout()
  skipWebhookRouteGuardOnce = true
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
async function bindFeishu() {
  try { await startFeishuLogin('link') }
  catch (error) { toast(error instanceof Error ? error.message : '飞书登录暂不可用') }
}
</script>

<template>
  <div class="settings wao-page-frame">
    <!-- 左侧分类 -->
    <nav class="snav" aria-label="设置分类">
      <div class="snav-title">设置<span>让 WAO 更顺手</span></div>
      <div class="snav-items">
        <template v-for="(item, index) in SETTINGS_CATEGORIES" :key="item.id">
          <div v-if="index === 5" class="snav-divider">更多</div>
          <button type="button" class="snav-item" :class="{ on: category === item.id }" :aria-current="category === item.id ? 'page' : undefined" @click="selectTab(item.id)"><i :class="item.icon"></i>{{ item.label }}</button>
        </template>
      </div>
      <div class="snav-foot">
        <button class="logout" @click="logout"><i class="ri-logout-box-r-line"></i>退出登录</button>
      </div>
    </nav>

    <!-- 右侧内容 -->
    <div class="spane">
      <div class="scontent" :class="{ 'navigation-wide': tab === 'navigation', 'content-wide': ['members', 'navigation', 'ai-accounts'].includes(tab) }">
        <button v-if="isDetail" class="settings-back" @click="selectTab(category)"><i class="ri-arrow-left-line"></i>返回{{ categoryInfo.label }}</button>
        <template v-if="categoryEntries.length && tab !== 'appearance'">
          <h1 class="s-h1">{{ categoryInfo.label }}</h1><p class="s-intro">{{ categoryInfo.description }}</p>
          <p v-if="tab === 'management'" class="settings-scope">编辑权限与影响范围在各项设置中展示。</p>
          <div class="card settings-entry-list">
            <button v-for="entry in categoryEntries" :key="entry[0]" class="settings-entry" @click="selectTab(entry[0])"><i class="entry-icon" :class="entry[3]"></i><span><strong>{{ entry[1] }}</strong><small>{{ entry[2] }}</small></span><i class="ri-arrow-right-s-line"></i></button>
          </div>
        </template>
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
              <button
                v-if="canManageMembers"
                class="btn-primary sm"
                type="button"
                @click="accountCreateOpen = !accountCreateOpen"
              ><i class="ri-user-add-line"></i>新建成员账号</button>
            </div>
            <form v-if="accountCreateOpen" class="credential-form member-account-form" @submit.prevent="createMemberAccount">
              <input v-model.trim="accountCreateForm.name" maxlength="64" required placeholder="成员姓名" />
              <input v-model.trim="accountCreateForm.email" type="email" autocomplete="off" required placeholder="登录邮箱" />
              <input v-model="accountCreateForm.password" type="password" autocomplete="new-password" minlength="8" maxlength="128" required placeholder="初始密码（至少 8 位）" />
              <div class="member-account-actions">
                <button type="button" class="btn-ghost" :disabled="accountCreateBusy" @click="closeAccountCreate()">取消</button>
                <button class="btn-primary" :disabled="accountCreateBusy">{{ accountCreateBusy ? '创建中…' : '创建账号' }}</button>
              </div>
              <div v-if="accountCreateError" class="form-error" role="alert">{{ accountCreateError }}</div>
            </form>
            <div v-for="(m, i) in orgMembers" :key="i" class="mem-row">
              <span class="mem-av" :style="{ background: m.color }">{{ m.initial }}</span>
              <span class="mem-name">{{ m.name }}<span class="me-tag">{{ m.meTag }}</span></span>
              <span class="mem-role" :style="{ background: m.roleBg, color: m.roleColor }">{{ m.role }}</span>
            </div>
            <div class="hint">WAO 不区分「个人版 / 团队版」：内容归属由负责人、参与人与可见范围表达。新建项目默认仅创建者可见，可邀请成员并设置查看或编辑权限。</div>
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

        <!-- 通知按来源分开，继续复用原面板与保存边界。 -->
        <NotificationSettingsPanel v-else-if="tab === 'notification-policy'" ref="notificationSettingsPanel" />
        <ProjectNotificationSettingsPanel v-else-if="tab === 'project-notifications'" />
        <TianrenNotificationSettingsPanel v-else-if="tab === 'tianren-notifications'" ref="tianrenNotificationPanel" />

        <!-- 系统 · 菜单配置 -->
        <template v-else-if="tab === 'navigation'">
          <NavigationLinkSettingsPanel :key="session.userId" ref="navigationPanel" />
        </template>

        <!-- 系统 · 通用 Webhook 配置 -->
        <template v-else-if="tab === 'webhook'">
          <WebhookConfigPanel ref="webhookPanel" />
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
              <span class="identity-icon"><i :class="identity.provider === 'feishu' ? 'ri-feishu-line' : 'ri-mail-line'"></i></span>
              <span class="mem-name">{{ providerNames[identity.provider] ?? identity.provider }}<span class="dim">{{ identity.email || identity.displayName }}</span></span>
              <button class="revoke" :disabled="identities.length === 1 || accountBusy" @click="unlink(identity.provider)">解绑</button>
            </div>
            <div class="hint">至少保留一种登录方式。新绑定的飞书或邮箱都会进入当前的 {{ session.currentUser.name }} 账号。</div>
          </div>
          <div class="card">
            <div class="card-title">绑定第三方账号</div>
            <div class="bind-actions">
              <button v-if="!hasIdentity('feishu')" class="btn-ghost" @click="bindFeishu"><i class="ri-feishu-line"></i>绑定飞书</button>
            </div>
          </div>
          <div class="card">
            <div class="card-title">飞书个人表情</div>
            <div class="hint">让提醒上的「做到了 / 没做到」以你本人的表情回复显示。首次使用需本人授权，不会另发群消息。</div>
            <a class="btn-ghost" href="/api/auth/feishu/reactions">授权个人表情</a>
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

        <!-- 当前成员的网页宠物偏好 -->
        <template v-else-if="tab === 'pet'">
          <WebPetSettingsPanel :key="session.userId" ref="webPetPanel" />
        </template>

        <template v-else-if="['common', 'appearance'].includes(tab)">
          <h1 class="s-h1">{{ tab === 'common' ? '把 WAO 调成你的习惯' : '外观与陪伴' }}</h1>
          <p class="s-intro">{{ categoryInfo.description }}</p>
          <h2 class="section-label">随手调整</h2>
          <PageLayoutPreference :key="session.userId" />
          <div class="card thin personal-preferences">
            <div class="pref-row">
              <div class="pref-main">
                <div class="pref-name">主题</div>
                <div class="pref-desc">明亮 / 深色 / 跟随系统</div>
              </div>
              <select class="value-select" aria-label="主题" :value="theme.mode.value" @change="theme.setTheme(($event.target as HTMLSelectElement).value as ThemeMode)"><option v-for="(label, value) in THEME_LABELS" :key="value" :value="value">{{ label }}</option></select>
            </div>
            <div class="pref-row">
              <div class="pref-main"><div class="pref-name">安静陪伴</div><div class="pref-desc">暂停伙伴动作和主动回应，保留操作结果提示。本次浏览器会话生效。</div></div>
              <button class="value-btn" type="button" role="switch" aria-label="安静陪伴" :aria-checked="companionQuiet" @click="setCompanionQuiet(!companionQuiet)">{{ companionQuiet ? '已开启' : '偶尔回应' }}</button>
            </div>
            <div class="pref-row">
              <div class="pref-main">
                <div class="pref-name">庆祝动画</div>
                <div class="pref-desc">创建任务 / 项目、完成任务时的全屏反馈；开启后保存时立即预览。</div>
              </div>
              <select
                v-model="personalPreferenceForm.celebration"
                class="value-select"
                aria-label="庆祝动画"
                :disabled="!!personalPreferenceSaving || preferences.loading || !preferences.loaded || !!preferences.loadError"
                @change="savePersonalPreference('celebration', personalPreferenceForm.celebration)"
              >
                <option value="细腻纸屑">细腻纸屑</option>
                <option value="关闭">关闭</option>
              </select>
              <span v-if="personalPreferenceSaving === 'celebration'" class="row-saving">保存中…</span>
            </div>
            <div v-if="personalPreferenceError" class="preference-error personal-preference-save-error" role="alert">
              <span>{{ personalPreferenceError }}，已保留原有设置。</span>
              <button type="button" class="btn-ghost sm" @click="retryPersonalPreference">重试保存</button>
            </div>

            <div v-if="preferences.loadError" class="preference-error" role="alert"><span>{{ preferences.loadError }}</span><button class="btn-ghost" :disabled="preferences.loading" @click="reloadPreferences">重新读取</button></div>
            <p v-else-if="preferences.loading || !preferences.loaded" class="preference-loading" role="status">正在读取个人偏好…</p>
            <p v-else class="hint">{{ personalPreferenceSaving ? '保存中…' : '主题和安静陪伴即时应用；庆祝动画跨设备保存。' }}</p>
          </div>
          <HomeDateReminderSettings :key="session.userId" ref="homeDateSettings" />
          <div v-if="tab === 'common'" class="settings-shortcuts">
            <button class="settings-shortcut" @click="selectTab('notes')"><i class="ri-quill-pen-line"></i><span>小记怎么帮我？<small>提醒与 AI 整理 →</small></span></button>
            <button class="settings-shortcut" @click="selectTab('reminders')"><i class="ri-notification-3-line"></i><span>调整通知<small>按提醒来源设置 →</small></span></button>
          </div>
          <template v-else>
            <button class="settings-entry companion-entry" @click="selectTab('pet')"><i class="entry-icon ri-bear-smile-line"></i><span><strong>伙伴设置</strong><small>外观与互动</small></span><i class="ri-arrow-right-s-line"></i></button>
            <details class="settings-disclosure"><summary>更多外观设置</summary>
          <div class="card thin control-style-preferences">
            <div class="card-row wrap">
              <div>
                <div class="card-title">界面控件风格</div>
                <div class="pref-desc">按当前成员跨设备保存，不会改变功能和已选数据。</div>
              </div>
              <span v-if="controlStyleSaving" class="connected"><span class="c-dot"></span>保存中…</span>
              <span v-else-if="preferences.loading || !preferences.loaded" class="connected"><span class="c-dot"></span>读取中…</span>
              <span v-else-if="!preferences.loadError && !controlStyleError" class="connected"><span class="c-dot"></span>已保存</span>
            </div>
            <div v-if="preferences.loadError" class="preference-error" role="alert">
              <span>{{ preferences.loadError }}</span>
              <button type="button" class="btn-ghost sm" :disabled="preferences.loading" @click="reloadPreferences">
                {{ preferences.loading ? '重新读取中…' : '重新读取' }}
              </button>
            </div>
            <div v-else-if="preferences.loading || !preferences.loaded" class="preference-loading" aria-live="polite">
              <i class="ri-loader-4-line"></i>正在读取当前成员的界面风格…
            </div>
            <template v-else>
              <div class="control-style-options" role="radiogroup" aria-label="界面控件风格">
                <button
                  v-for="option in CONTROL_STYLE_OPTIONS"
                  :key="option.value"
                  type="button"
                  role="radio"
                  :aria-checked="controlStyleForm === option.value"
                  :class="{ on: controlStyleForm === option.value }"
                  :disabled="controlStyleSaving"
                  @click="saveControlStyle(option.value)"
                >
                  <i :class="controlStyleForm === option.value ? 'ri-radio-button-line' : 'ri-checkbox-blank-circle-line'"></i>
                  <span><b>{{ option.name }}</b><small>{{ option.desc }}</small></span>
                </button>
              </div>
              <div v-if="controlStyleError" class="preference-error control-style-save-error" role="alert">
                <span>{{ controlStyleError }}，已保留原有风格。</span>
                <button type="button" class="btn-ghost sm" @click="saveControlStyle(lastControlStyleAttempt)">重试保存</button>
              </div>
            </template>
          </div>

              <div class="card thin">
            <div class="pref-row">
              <div class="pref-main">
                <div class="pref-name">每周起始日</div>
                <div class="pref-desc">按当前成员跨设备保存，立即影响日历与「本周」分组。</div>
              </div>
              <select
                v-model="personalPreferenceForm.weekStart"
                class="value-select"
                aria-label="每周起始日"
                :disabled="!!personalPreferenceSaving || preferences.loading || !preferences.loaded || !!preferences.loadError"
                @change="savePersonalPreference('weekStart', personalPreferenceForm.weekStart)"
              >
                <option value="周一">周一</option>
                <option value="周日">周日</option>
              </select>
              <span v-if="personalPreferenceSaving === 'weekStart'" class="row-saving">保存中…</span>
            </div>
            <div class="pref-row">
              <div class="pref-main"><div class="pref-name">快捷键</div><div class="pref-desc">查看记录、搜索和页面操作的快捷方式。</div></div>
              <button class="value-btn" type="button" @click="ui.toggleHelp()">查看快捷键</button>
            </div>
                <div class="hint language-boundary">界面当前仅提供完整的简体中文版本。</div>
              </div>
            </details>
          </template>
        </template>
        <template v-else-if="tab === 'notes'">
          <h1 class="s-h1">小记与 AI</h1><p class="s-intro">{{ categoryInfo.description }}</p>
          <div class="card note-ai-defaults-preferences"><h2 class="card-title">保存后的 AI 整理</h2><NoteAiSettingsForm progressive /></div>
          <details class="settings-disclosure"><summary>编辑中的未保存提醒{{ noteReminderDirty ? ' · 待保存' : '' }}</summary>
          <div class="card thin note-reminder-preferences">
            <div class="card-row wrap">
              <div>
                <div class="card-title">小记全屏提醒</div>
                <div class="pref-desc">编辑内容一段时间未保存时，轻声提示进入全屏；只在小记编辑器内显示。</div>
              </div>
              <span v-if="noteReminderSaving" class="connected"><span class="c-dot"></span>保存中…</span>
              <span v-else-if="preferences.loading || !preferences.loaded" class="connected"><span class="c-dot"></span>读取中…</span>
              <span v-else-if="!preferences.loadError && !noteReminderError" class="connected"><span class="c-dot"></span>{{ noteReminderDirty ? '有未保存修改' : '已保存' }}</span>
            </div>
            <div v-if="preferences.loadError || noteReminderError" class="preference-error" role="alert">
              <span>{{ noteReminderError || preferences.loadError }}</span>
              <button type="button" class="btn-ghost sm" :disabled="preferences.loading || noteReminderSaving" @click="reloadPreferences">
                {{ preferences.loading ? '重新读取中…' : '重新读取' }}
              </button>
              <button v-if="noteReminderError" type="button" class="btn-ghost sm" :disabled="noteReminderSaving || !noteReminderDirty" @click="saveNoteReminderPreferences">重试保存</button>
            </div>
            <div v-if="preferences.loading || !preferences.loaded" class="preference-loading" aria-live="polite">
              <i class="ri-loader-4-line"></i>正在读取小记提醒偏好…
            </div>
            <template v-else-if="!preferences.loadError">
              <div class="pref-row">
                <div class="pref-main">
                  <div class="pref-name">启用未保存提醒</div>
                  <div class="pref-desc">提醒会显示「进入全屏」「稍后提醒」和关闭操作。</div>
                </div>
                <button type="button" class="toggle" :class="{ on: noteReminderForm.enabled }" aria-label="启用小记全屏提醒" :aria-pressed="noteReminderForm.enabled" :disabled="noteReminderSaving" @click="noteReminderForm.enabled = !noteReminderForm.enabled"><span class="knob"></span></button>
              </div>
              <div class="pref-row">
                <div class="pref-main">
                  <div class="pref-name">提醒间隔</div>
                  <div class="pref-desc">从最近一次编辑开始计时；保存后自动退出全屏。</div>
                </div>
                <select v-model.number="noteReminderForm.delayMinutes" class="value-select" aria-label="小记全屏提醒间隔" :disabled="noteReminderSaving || !noteReminderForm.enabled">
                  <option :value="1">1 分钟</option>
                  <option :value="2">2 分钟</option>
                  <option :value="3">3 分钟</option>
                  <option :value="5">5 分钟</option>
                  <option :value="10">10 分钟</option>
                  <option :value="15">15 分钟</option>
                </select>
              </div>
              <div class="preference-actions">
                <span>{{ noteReminderStorageError || '本组独立保存；未保存的草稿保留在此浏览器。' }}</span>
                <button type="button" class="btn-primary" :disabled="noteReminderSaving || !noteReminderDirty" @click="saveNoteReminderPreferences">
                  {{ noteReminderSaving ? '保存中…' : '保存提醒设置' }}
                </button>
              </div>
            </template>
          </div>

          </details>
        </template>
        <KeepAlive><NoteDiscoveryPreference v-if="tab === 'notes'" :key="session.userId" ref="discoveryPanel" /></KeepAlive>

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
  width: 184px;
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
.snav-title span { display: block; margin-top: 5px; font-size: 11px; font-weight: 400; color: var(--wao-text-weak); }
.snav-divider { padding: 20px 10px 6px; color: var(--wao-text-faint); font-size: 11px; }
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
  min-height: 39px;
  padding: 8px 10px;
  gap: 9px;
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
  padding: 30px 32px 48px;
  max-width: 1000px;
}
.scontent.content-wide { max-width: 1420px; }
.note-ai-defaults-preferences :deep(.note-ai-settings-form) { height: 640px; max-height: calc(100dvh - 120px); min-height: 440px; }
@media(max-width:650px) { .note-ai-defaults-preferences :deep(.note-ai-settings-form) { min-height: 650px; max-height:none; } }
.notification-settings-stack { display: grid; gap: 14px; }
.s-h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
}
.s-intro { margin: 8px 0 24px; color: var(--wao-text-weak); font-size: 13px; line-height: 1.7; }
.section-label { margin: 24px 0 8px; font-size: 13px; color: var(--wao-text-3); }
.settings-shortcuts { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 22px; }
.settings-shortcut { display: flex; gap: 12px; align-items: center; padding: 18px; text-align: left; background: var(--wao-bg-card); color: var(--wao-text); border: 1px solid var(--wao-line); border-radius: 12px; cursor: pointer; }
.settings-shortcut > i { font-size: 20px; color: var(--wao-primary-text); }
.settings-shortcut small, .settings-entry small { display: block; margin-top: 5px; font-size: 12px; color: var(--wao-text-weak); line-height: 1.5; }
.settings-shortcut:hover, .settings-entry:hover { background: var(--wao-primary-soft); }
.settings-shortcut span { min-width: 0; font-size: 13px; }
.settings-entry-list { padding: 0 18px; }
.settings-entry { width: 100%; min-width: 0; display: flex; align-items: center; gap: 14px; border: 0; border-bottom: 1px solid var(--wao-line); padding: 18px 0; background: transparent; color: var(--wao-text); cursor: pointer; text-align: left; }
.settings-entry:last-child { border-bottom: 0; }
.settings-entry > span { flex: 1; min-width: 0; }
.settings-entry strong { font-size: 14px; font-weight: 600; }
.entry-icon { width: 36px; height: 36px; display: grid; place-items: center; flex: none; border-radius: 11px; background: var(--wao-primary-soft); color: var(--wao-primary-text); font-size: 18px; }
.settings-back { display: flex; align-items: center; gap: 6px; margin: 0 0 20px; padding: 0; border: 0; background: transparent; color: var(--wao-text-weak); cursor: pointer; font-size: 12px; }
.settings-disclosure { margin-top: 22px; }
.settings-disclosure > summary { padding: 10px 0; font-size: 13px; color: var(--wao-text-3); cursor: pointer; }
.settings-scope { margin: 0; color: var(--wao-text-faint); font-size: 12px; }
.companion-entry { margin-top: 16px; }
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
.identity-icon { width: 30px; height: 30px; display: grid; place-content: center; border-radius: 8px; background: var(--wao-bg-sub); color: var(--wao-primary-text); font-size: 17px; }
.bind-actions { display: flex; gap: 10px; margin-top: 12px; }
.credential-form { display: grid; grid-template-columns: 1fr 1fr 1fr auto; gap: 8px; margin-top: 12px; }
.member-account-form { padding: 13px; border-radius: 9px; background: var(--wao-bg-sub); }
.member-account-actions { display: flex; gap: 8px; }
.member-account-form .form-error { grid-column: 1 / -1; color: var(--wao-danger); font-size: 12px; }
.profile-form { grid-template-columns: 1fr 120px auto; margin: 14px 0; }
.note-ai-defaults-preferences { margin-top: 14px; }
.control-style-preferences { margin-bottom: 14px; }
.control-style-options { margin-top: 13px; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.control-style-options button { min-width: 0; padding: 11px; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card); color: var(--wao-text-3); cursor: pointer; display: flex; align-items: flex-start; gap: 8px; text-align: left; }
.control-style-options button.on { border-color: var(--wao-primary); background: var(--wao-primary-tint); color: var(--wao-primary-text); box-shadow: 0 0 0 2px var(--wao-primary-soft); }
.control-style-options button:disabled { opacity: .6; cursor: wait; }
.control-style-options button > i { margin-top: 1px; flex: 0 0 auto; }
.control-style-options span { min-width: 0; display: grid; gap: 4px; }
.control-style-options b { font-size: 12.5px; }
.control-style-options small { color: var(--wao-text-weak); font-size: 10.5px; line-height: 1.45; }
.control-style-save-error { padding-bottom: 0; }
.preference-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 0 2px; color: var(--wao-danger); font-size: 12.5px; }
.preference-loading { display: flex; align-items: center; gap: 7px; padding: 16px 0 2px; color: var(--wao-text-weak); font-size: 12.5px; }
.preference-loading i { animation: preference-spin 1s linear infinite; }
.preference-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-top: 14px; color: var(--wao-text-weak); font-size: 12px; }
@keyframes preference-spin { to { transform: rotate(360deg); } }
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
.site-form .outage-switch { grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; padding: 11px 12px; border-radius: 8px; background: var(--wao-bg-sub); }
.site-form .outage-switch input { width: 18px; height: 18px; padding: 0; accent-color: var(--wao-primary); }
.site-form > .hint, .site-form > .form-error { grid-column: 1 / -1; }
.site-form > .form-error { margin: 0; color: var(--wao-danger); font-size: 12px; }
.site-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 10px; }
.menu-loading { margin-top: 16px; padding: 28px 12px; border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-weak); text-align: center; font-size: 12.5px; }
.registry-load-error { margin-top: 16px; padding: 22px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 12.5px; }
.registry-layout { margin-top: 16px; display: grid; grid-template-columns: minmax(230px, 300px) minmax(0, 1fr); gap: 16px; align-items: start; }
.registry-list { display: flex; flex-direction: column; gap: 7px; }
.registry-row { display: flex; min-width: 0; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card); overflow: hidden; }
.registry-row.active { border-color: color-mix(in srgb, var(--wao-primary) 45%, transparent); box-shadow: 0 0 0 3px var(--wao-primary-soft); }
.registry-select { flex: 1; min-width: 0; padding: 10px; display: flex; align-items: center; gap: 9px; border: 0; background: transparent; cursor: pointer; text-align: left; }
.registry-select:hover, .registry-delete:hover { background: var(--wao-bg-hover-2); }
.registry-icon { width: 32px; height: 32px; flex: none; display: grid; place-items: center; border-radius: 8px; background: var(--wao-primary-soft); color: var(--wao-primary-text); font-size: 17px; }
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
.visibility-choice.on { border-color: color-mix(in srgb, var(--wao-primary) 35%, transparent); background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.choice-icon { width: 20px; display: inline-grid; place-items: center; }
.menu-preview { grid-column: 1 / -1; padding: 13px 14px; display: grid; grid-template-columns: 72px 1fr; gap: 8px 12px; border: 1px dashed var(--wao-line-strong); border-radius: 9px; background: var(--wao-bg-sub); font-size: 12px; }
.menu-preview .preview-label { width: auto; margin: 0; text-align: left; }
.menu-preview-group { color: var(--wao-text-faint); font-weight: 600; }
.menu-preview-item { grid-column: 2; display: inline-flex; align-items: center; gap: 7px; color: var(--wao-text); font-weight: 500; }
.menu-preview-hidden { grid-column: 2; color: var(--wao-text-faint); }
.connected.err { color: var(--wao-danger); }
.connected.err .c-dot { background: var(--wao-danger); }
.filing-preview { margin-top: 14px; padding: 12px 14px; display: flex; flex-wrap: wrap; justify-content: center; gap: 5px; border: 1px dashed var(--wao-line-strong); border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 11.5px; }
.filing-preview a { color: var(--wao-primary-text); text-decoration: none; }
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
  color: var(--wao-primary-text);
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
.pref-select {
  min-width: 112px;
  padding: 7px 10px;
  color: var(--wao-text);
  background: var(--wao-surface);
  border: 1px solid var(--wao-line);
  border-radius: 8px;
}
.pref-row.muted .pref-main { opacity: .58; }
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
.value-select {
  height: 30px;
  min-width: 104px;
  padding: 0 28px 0 11px;
  border: 1px solid var(--wao-line-strong);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font-size: 12px;
}
.value-select:focus { border-color: var(--wao-primary); outline: none; box-shadow: 0 0 0 3px var(--wao-primary-soft); }
.value-select:disabled { opacity: .6; cursor: wait; }
.row-saving { color: var(--wao-text-weak); font-size: 11.5px; }
.personal-preference-save-error { border-top: 1px solid var(--wao-divider-2); }
.language-boundary { padding-top: 12px; }

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
  .snav-title, .snav-divider, .snav-group, .snav-foot { display: none; }
  .snav-items { flex-direction: row; flex-wrap: wrap; gap: 4px; }
  .snav-item { min-height: 34px; padding: 6px 9px; white-space: nowrap; }
  .preference-actions { align-items: stretch; flex-direction: column; }
  .control-style-options { grid-template-columns: 1fr; }
  .preference-actions .btn-primary { justify-content: center; }
  .spane { overflow: visible; }
  .scontent { padding: 16px 12px 28px; }
  .card { padding: 14px; }
  .s-h1 { font-size: 22px; }
  .settings-shortcuts { gap: 8px; }
  .settings-shortcut { padding: 13px 10px; gap: 7px; }
  .pref-row { flex-wrap: wrap; }
  .pref-main { flex-basis: 55%; }
  .value-select { min-width: 94px; max-width: 100%; }
  .credential-form, .profile-form { grid-template-columns: 1fr; }
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

</style>
