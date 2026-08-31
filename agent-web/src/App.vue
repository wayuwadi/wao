<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { getSession } from '@/api/agent'
import { addManualTopic, getStudio, initializeProfile, markSuggestion, recommendTopics, refreshHotspots, saveProfile } from '@/api/studio'
import { ApiError } from '@/api/http'
import MaterialLibrary from '@/components/MaterialLibrary.vue'
import PublishCenter from '@/components/PublishCenter.vue'
import VideoWorkflow from '@/components/VideoWorkflow.vue'
import type { AgentSession, Studio, TopicSuggestion } from '@/types'

type AuthState = 'loading' | 'unauthorized' | 'ready' | 'error'
type StudioState = 'idle' | 'loading' | 'ready' | 'error'

const session = ref<AgentSession | null>(null)
const studio = ref<Studio | null>(null)
const studioBusy = ref('')
const studioMessage = ref('')
const studioState = ref<StudioState>('idle')
const studioError = ref('')
const profileForm = reactive({
  niche: '', audience: '', tone: '', avoid: '', contentTopics: '', lengthStructure: '',
  updateFrequency: '', primaryPlatforms: '', imageStyle: '',
})
const recommendForm = reactive({ platform: 'DOUYIN', count: 5 })
const manualTopicForm = reactive({ topic: '', platform: 'WECHAT_MP' })
const authState = ref<AuthState>('loading')
const message = ref('正在读取 WAO 登录会话…')
const activeSection = ref('内容工作台')
const creationMode = ref<'topics' | 'video'>('topics')
const publishTopic = ref('')
const profileDirty = ref(false)
const profileNeeds = ref('')
const contentDirty = ref(false)

const NAV_ITEMS = [
  { id: '内容工作台', icon: 'ri-layout-column-line', badge: '' },
  { id: '话题与热点', icon: 'ri-fire-line', badge: '' },
  { id: '账号管理', icon: 'ri-user-settings-line', badge: '' },
  { id: '素材库', icon: 'ri-gallery-line', badge: '' },
]

function formatHeat(heat?: number) {
  if (!heat) return ''
  if (heat >= 100_000_000) return `${(heat / 100_000_000).toFixed(1)} 亿`
  if (heat >= 10_000) return `${(heat / 10_000).toFixed(1)} 万`
  return String(heat)
}

async function refreshStudio(force = false) {
  if (profileDirty.value && !force && !window.confirm('账号定位还有未保存修改，仍要重新读取吗？')) return
  studioState.value = 'loading'
  studioError.value = ''
  try {
    studio.value = await getStudio()
    const p = studio.value.profile
    profileForm.niche    = p.niche    || ''
    profileForm.audience = p.audience || ''
    profileForm.tone     = p.tone     || ''
    profileForm.avoid    = p.avoid    || ''
    profileForm.contentTopics = p.contentTopics || ''
    profileForm.lengthStructure = p.lengthStructure || ''
    profileForm.updateFrequency = p.updateFrequency || ''
    profileForm.primaryPlatforms = p.primaryPlatforms || ''
    profileForm.imageStyle = p.imageStyle || ''
    profileDirty.value = false
    studioState.value = 'ready'
  } catch (error) {
    studioState.value = 'error'
    studioError.value = error instanceof Error ? error.message : '内容工作台加载失败'
    throw error
  }
}

async function retryStudio(force = false) {
  studioMessage.value = ''
  try {
    await refreshStudio(force)
  } catch {
    studioMessage.value = studioError.value
  }
}

async function runStudio(kind: string, action: () => Promise<unknown>) {
  studioBusy.value  = kind
  studioMessage.value = ''
  try {
    await action()
    await refreshStudio()
  } catch (error) {
    studioMessage.value = error instanceof Error ? error.message : '操作失败'
  } finally {
    studioBusy.value = ''
  }
}

function doRefreshHotspots() {
  return runStudio('hotspots', async () => {
    const statuses = await refreshHotspots()
    const failed = statuses.filter((item) => item.error)
    const total  = statuses.reduce((sum, item) => sum + item.fetched, 0)
    studioMessage.value = failed.length
      ? `采集到 ${total} 条；${failed.length} 个来源失败：${failed.map((f) => f.label).join('、')}`
      : `采集到 ${total} 条热点`
  })
}

function doSaveProfile() {
  return runStudio('profile', async () => {
    await saveProfile({ ...profileForm })
    profileDirty.value = false
    studioMessage.value = '账号定位已保存'
  })
}

function doInitializeProfile() {
  if (!profileNeeds.value.trim()) return
  return runStudio('profile-initialize', async () => {
    await initializeProfile(profileNeeds.value)
    profileNeeds.value = ''
    studioMessage.value = '已按需求初始化账号定位，请检查并按需修改'
  })
}

function markProfileDirty() {
  profileDirty.value = true
  studioMessage.value = '账号定位有未保存修改'
}

function switchSection(next: string) {
  if (next === activeSection.value) return
  const leavingProfile = activeSection.value === '账号管理' && profileDirty.value
  const leavingContent = activeSection.value === '内容工作台' && contentDirty.value
  if ((leavingProfile || leavingContent) && !window.confirm('当前内容还有未保存修改，仍要离开吗？')) return
  if (leavingProfile) {
    profileDirty.value = false
    void retryStudio(true)
  }
  contentDirty.value = false
  activeSection.value = next
}

function doRecommend() {
  return runStudio('recommend', async () => {
    const list = await recommendTopics(recommendForm.platform, recommendForm.count)
    studioMessage.value = list.length
      ? `挑出 ${list.length} 条选题`
      : '当前热榜里没有适合这个账号的选题，可以先补全账号定位或稍后再试'
  })
}

function addTopic() {
  if (!manualTopicForm.topic.trim()) return
  return runStudio('manual-topic', async () => {
    await addManualTopic(manualTopicForm.topic, manualTopicForm.platform)
    manualTopicForm.topic = ''
    studioMessage.value = '灵感已加入话题池'
  })
}

function useSuggestion(suggestion: TopicSuggestion) {
  return runStudio('use-' + suggestion.id, async () => {
    await markSuggestion(suggestion.id, 'USED')
    publishTopic.value = suggestion.topic
    switchSection('内容工作台')
  })
}

function dismissSuggestion(suggestion: TopicSuggestion) {
  return runStudio('dismiss-' + suggestion.id, () => markSuggestion(suggestion.id, 'DISMISSED'))
}

function loginWao() {
  const target = '/login?redirect=%2Fmedia-agent'
  if (window.top) window.top.location.href = target
  else window.location.href = target
}

function reloadPage() {
  window.location.reload()
}

function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (!profileDirty.value) return
  event.preventDefault()
}

onMounted(async () => {
  window.addEventListener('beforeunload', warnBeforeUnload)
  try {
    session.value = await getSession()
    authState.value = 'ready'
    message.value   = ''
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      authState.value = 'unauthorized'
      message.value   = '当前 WAO 登录已失效，请重新登录后进入 Agent。'
    } else {
      authState.value = 'error'
      message.value   = error instanceof Error ? error.message : 'Agent 服务暂时不可用'
    }
    return
  }
  await retryStudio()
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', warnBeforeUnload))
</script>

<template>
  <!-- 认证页 -->
  <div v-if="authState !== 'ready'" class="auth-screen">
    <div class="auth-card">
      <div class="logo"><i class="ri-sparkling-2-fill"></i></div>
      <span class="eyebrow">WAO AGENT</span>
      <h1>自媒体创作助手</h1>
      <p>{{ message }}</p>
      <div v-if="authState === 'loading'" class="loader"><span></span></div>
      <div v-else class="auth-actions">
        <button v-if="authState === 'unauthorized'" class="btn-primary" @click="loginWao">
          <i class="ri-login-box-line"></i>登录 WAO
        </button>
        <button v-else class="btn-primary" @click="reloadPage">
          <i class="ri-refresh-line"></i>重新加载
        </button>
      </div>
      <small>账号与会话均由 WAO 统一管理。</small>
    </div>
  </div>

  <!-- 主 Shell -->
  <div v-else-if="session" class="app-shell">

    <!-- 左侧导航（WAO 风格浅色侧边栏） -->
    <aside class="sidebar">
      <div class="brand-row">
        <div class="brand-mark">A</div>
        <span class="brand-name">WAO Agent</span>
      </div>

      <nav class="sidebar-menu">
        <div class="menu-group-label">创作者工作台</div>
        <div class="menu-items">
          <button
            v-for="item in NAV_ITEMS"
            :key="item.id"
            class="menu-item"
            :class="{ active: activeSection === item.id }"
            @click="switchSection(item.id)"
          >
            <i :class="item.icon"></i>
            <span class="menu-item-label">{{ item.id }}</span>
            <em v-if="item.badge">{{ item.badge }}</em>
          </button>
        </div>
      </nav>

      <div class="sidebar-user">
        <button class="user-btn">
          <span class="user-avatar">{{ session.avatarText }}</span>
          <div class="user-meta">
            <span class="user-name">{{ session.displayName }}</span>
            <span class="user-role">{{ session.workspaceName }}</span>
          </div>
        </button>
      </div>
    </aside>

    <!-- 右侧内容 -->
    <div class="main-content">
      <header class="main-header">
        <span class="page-title">{{ activeSection }}</span>
        <div class="header-spacer"></div>
        <button v-if="activeSection === '账号管理' || activeSection === '话题与热点'" class="btn-ghost" style="height:30px;font-size:12px;" :disabled="studioState === 'loading'" @click="retryStudio()">
          <i class="ri-refresh-line"></i>{{ studioState === 'loading' ? '加载中…' : '刷新' }}
        </button>
      </header>

      <div class="main-body">

        <section
          v-if="(activeSection === '账号管理' || activeSection === '话题与热点') && !studio"
          class="panel studio-load-state"
          :class="{ error: studioState === 'error' }"
        >
          <span v-if="studioState === 'loading'" class="loader"><span></span></span>
          <i v-else class="ri-error-warning-line"></i>
          <div>
            <b>{{ studioState === 'loading' ? '正在加载内容工作台…' : '内容工作台暂时无法加载' }}</b>
            <span v-if="studioState === 'error'">{{ studioError }}</span>
          </div>
          <button v-if="studioState === 'error'" class="btn-primary" @click="retryStudio()">重试</button>
        </section>

        <!-- ===== 账号管理 ===== -->
        <template v-else-if="activeSection === '账号管理' && studio">
          <p v-if="studioMessage" class="message-bar">{{ studioMessage }}</p>

          <div class="account-layout">
            <section class="panel account-profile">
              <div class="panel-head"><div><h2>账号定位</h2><p>决定热点推荐和成稿语气，保存后按当前工作区持久化</p></div></div>
              <div class="profile-initialize">
                <label class="field-mini">我的实际需求<textarea v-model="profileNeeds" maxlength="2000" rows="3" placeholder="例如：我想面向忙碌的上班族，分享自己真实使用过的 AI 工作流；每周更新两篇公众号文章。"></textarea></label>
                <button class="btn-ghost" :disabled="studioBusy === 'profile-initialize' || !profileNeeds.trim()" @click="doInitializeProfile"><i class="ri-sparkling-line"></i>{{ studioBusy === 'profile-initialize' ? '初始化中…' : '用已配置模型初始化' }}</button>
                <small>只依据你填写的需求生成可编辑建议；未配置模型时会明确提示，不填演示数据。</small>
              </div>
              <label class="field-mini">领域<input v-model="profileForm.niche" maxlength="200" placeholder="例如：程序员 AI 副业" @input="markProfileDirty" /></label>
              <label class="field-mini">受众<input v-model="profileForm.audience" maxlength="200" placeholder="例如：想用 AI 提效的开发者" @input="markProfileDirty" /></label>
              <label class="field-mini">语气<input v-model="profileForm.tone" maxlength="200" placeholder="例如：轻松吐槽，像和朋友聊天" @input="markProfileDirty" /></label>
              <label class="field-mini">常用主题<input v-model="profileForm.contentTopics" maxlength="500" placeholder="例如：AI 工具实测、工作流复盘、效率方法" @input="markProfileDirty" /></label>
              <label class="field-mini">篇幅与结构<input v-model="profileForm.lengthStructure" maxlength="500" placeholder="例如：1500 字，先结论后案例，结尾给行动清单" @input="markProfileDirty" /></label>
              <label class="field-mini">更新频率<input v-model="profileForm.updateFrequency" maxlength="200" placeholder="例如：每周二、四各一篇" @input="markProfileDirty" /></label>
              <label class="field-mini">主要平台<input v-model="profileForm.primaryPlatforms" maxlength="200" placeholder="例如：公众号、小红书" @input="markProfileDirty" /></label>
              <label class="field-mini">封面与配图<input v-model="profileForm.imageStyle" maxlength="500" placeholder="例如：温暖插画，少文字，16:9 封面" @input="markProfileDirty" /></label>
              <label class="field-mini">避免<input v-model="profileForm.avoid" maxlength="500" placeholder="例如：绝对化用词、政治话题" @input="markProfileDirty" /></label>
              <button class="btn-primary profile-save" :disabled="studioBusy === 'profile' || !profileDirty" @click="doSaveProfile">
                <i class="ri-save-line"></i>{{ studioBusy === 'profile' ? '保存中…' : '保存账号定位' }}
              </button>
            </section>
            <section class="account-platforms">
              <div class="section-heading compact"><div><h2>平台账号</h2><p>仅展示后端声明的真实 OAuth 与发布能力</p></div></div>
              <PublishCenter mode="accounts" />
            </section>
          </div>
        </template>

        <!-- ===== 话题与热点 ===== -->
        <template v-else-if="activeSection === '话题与热点' && studio">
          <div class="creation-tabs" role="tablist" aria-label="内容创作方式">
            <button :class="{ active: creationMode === 'topics' }" role="tab" :aria-selected="creationMode === 'topics'" @click="creationMode = 'topics'">
              <i class="ri-fire-line"></i>热点选题
            </button>
            <button :class="{ active: creationMode === 'video' }" role="tab" :aria-selected="creationMode === 'video'" @click="creationMode = 'video'">
              <i class="ri-movie-2-line"></i>视频创作
            </button>
          </div>

          <VideoWorkflow v-if="creationMode === 'video'" />

          <template v-else>
            <p v-if="studioMessage" class="message-bar">{{ studioMessage }}</p>

            <button v-if="!studio.profile.configured" class="capability-note actionable" @click="switchSection('账号管理')">
              <i class="ri-error-warning-line"></i>
              <span>还没填账号定位。不填也能推荐，但补全「领域」后推荐质量差别很大。</span>
              <b>去设置</b>
            </button>

            <section class="panel manual-topic-card">
              <div class="panel-head"><div><h2>随手记灵感</h2><p>保存到当前工作区话题池，之后可直接采用为内容草稿</p></div></div>
              <div class="manual-topic-form">
                <input v-model="manualTopicForm.topic" maxlength="200" placeholder="写下想法、选题或系列内容" @keyup.enter="addTopic" />
                <select v-model="manualTopicForm.platform"><option value="WECHAT_MP">公众号</option><option value="XIAOHONGSHU">小红书</option><option value="DOUYIN">抖音</option><option value="WECHAT_CHANNELS">视频号</option></select>
                <button class="btn-primary" :disabled="studioBusy === 'manual-topic' || !manualTopicForm.topic.trim()" @click="addTopic">{{ studioBusy === 'manual-topic' ? '保存中…' : '加入话题池' }}</button>
              </div>
            </section>

            <div class="studio-grid">
            <!-- 热点列 -->
            <div class="panel">
              <div class="panel-head">
                <div><h2>实时热点</h2><p>每 30 分钟自动采集一次</p></div>
                <button class="btn-ghost" style="height:30px;font-size:12px;"
                        :disabled="studioBusy === 'hotspots'" @click="doRefreshHotspots">
                  <i class="ri-refresh-line"></i>{{ studioBusy === 'hotspots' ? '采集中…' : '刷新热点' }}
                </button>
              </div>
              <div class="source-chips">
                <span v-for="s in studio.sources" :key="s.code"
                      class="source-chip"
                      :class="s.error ? 'bad' : s.available ? 'ok' : 'off'"
                      :title="s.error || s.note">
                  {{ s.label }}<em v-if="s.error">失败</em><em v-else-if="!s.available">未接入</em>
                </span>
              </div>
              <div v-if="!studio.hotspots.length" class="empty">
                <i class="ri-fire-line"></i><b>还没有热点</b><span>点右上角「刷新热点」开始采集。</span>
              </div>
              <ul v-else class="hotspot-list">
                <li v-for="item in studio.hotspots" :key="item.id" class="hotspot-item">
                  <span class="hot-rank">{{ item.rank }}</span>
                  <div class="hotspot-meta">
                    <a v-if="item.url" :href="item.url" target="_blank" rel="noopener">{{ item.title }}</a>
                    <b v-else>{{ item.title }}</b>
                    <small>{{ item.sourceLabel }}<template v-if="item.heat"> · {{ formatHeat(item.heat) }} 热度</template></small>
                  </div>
                </li>
              </ul>
            </div>

            <!-- 推荐 -->
            <div class="panel">
              <div class="panel-head"><div><h2>选题推荐</h2><p>结合账号定位，从热榜里挑当前账号能做的</p></div></div>
              <div class="recommend-bar">
                <select v-model="recommendForm.platform" style="flex:1;">
                  <option value="DOUYIN">抖音</option>
                  <option value="XIAOHONGSHU">小红书</option>
                  <option value="WECHAT_MP">公众号</option>
                </select>
                <select v-model.number="recommendForm.count">
                  <option :value="3">3 条</option>
                  <option :value="5">5 条</option>
                  <option :value="8">8 条</option>
                </select>
                <button class="btn-primary" :disabled="studioBusy === 'recommend'" @click="doRecommend">
                  <i class="ri-sparkling-line"></i>{{ studioBusy === 'recommend' ? '思考中…' : '推荐选题' }}
                </button>
              </div>
            </div>
            </div>

          <!-- 选题建议 -->
            <div v-if="studio.suggestions.length" class="section-heading">
            <div><h2>选题建议</h2><p>共 {{ studio.suggestions.length }} 条</p></div>
          </div>
            <div v-if="!studio.suggestions.length" class="panel" style="margin-top:14px;">
            <div class="empty">
              <i class="ri-lightbulb-line"></i>
              <b>还没有选题建议</b>
              <span>先刷新热点，再点「推荐选题」。</span>
            </div>
          </div>
            <div v-else class="suggestion-grid">
            <div v-for="item in studio.suggestions" :key="item.id"
                 class="suggestion-card" :class="{ used: item.status === 'USED' }">
              <div class="suggestion-head">
                <span class="fit-score"
                      :class="item.fitScore >= 70 ? 'high' : item.fitScore >= 40 ? 'mid' : 'low'">
                  {{ item.fitScore }}
                </span>
                <div>
                  <h3>{{ item.topic }}</h3>
                  <small>{{ item.platformLabel }}</small>
                </div>
              </div>
              <p v-if="item.angle"><b>切入</b>{{ item.angle }}</p>
              <p v-if="item.reason"><b>理由</b>{{ item.reason }}</p>
              <a v-if="item.hotspotUrl" class="suggestion-origin" :href="item.hotspotUrl" target="_blank" rel="noopener">
                <i class="ri-fire-line"></i>{{ item.hotspotTitle }}
              </a>
              <div v-else-if="item.hotspotTitle" class="suggestion-origin">
                <i class="ri-fire-line"></i>{{ item.hotspotTitle }}
              </div>
              <div class="suggestion-actions">
                <span v-if="item.status === 'USED'" class="used-badge">已采用</span>
                <template v-else>
                  <button class="btn-text" :disabled="!!studioBusy" @click="dismissSuggestion(item)">忽略</button>
                  <button class="btn-primary" style="min-height:28px;font-size:12px;"
                          :disabled="!!studioBusy" @click="useSuggestion(item)">
                    <i class="ri-check-line"></i>采用
                  </button>
                </template>
              </div>
            </div>
            </div>
          </template>
        </template>

        <!-- ===== 内容工作台：创作、管理和发布统一在同一条状态流 ===== -->
        <PublishCenter v-else-if="activeSection === '内容工作台'" mode="workbench" :initial-topic="publishTopic" @topic-consumed="publishTopic = ''" @dirty-change="contentDirty = $event" />

        <!-- ===== 素材库 ===== -->
        <MaterialLibrary v-else-if="activeSection === '素材库'" />

      </div>
    </div>
  </div>
</template>
