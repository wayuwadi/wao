<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getSession } from '@/api/agent'
import { getStudio, markSuggestion, recommendTopics, refreshHotspots, saveProfile } from '@/api/studio'
import { ApiError } from '@/api/http'
import PublishCenter from '@/components/PublishCenter.vue'
import type { AgentSession, Studio, TopicSuggestion } from '@/types'

type AuthState = 'loading' | 'unauthorized' | 'ready' | 'error'

const session = ref<AgentSession | null>(null)
const studio = ref<Studio | null>(null)
const studioBusy = ref('')
const studioMessage = ref('')
const profileForm = reactive({ niche: '', audience: '', tone: '', avoid: '' })
const recommendForm = reactive({ platform: 'DOUYIN', count: 5 })
const authState = ref<AuthState>('loading')
const message = ref('正在读取 WAO 登录会话…')
const activeSection = ref('选题中心')
const publishTopic = ref('')

const NAV_ITEMS = [
  { id: '选题中心', icon: 'ri-fire-line',           badge: '' },
  { id: '发布中心', icon: 'ri-send-plane-line',     badge: '' },
]

function formatHeat(heat?: number) {
  if (!heat) return ''
  if (heat >= 100_000_000) return `${(heat / 100_000_000).toFixed(1)} 亿`
  if (heat >= 10_000) return `${(heat / 10_000).toFixed(1)} 万`
  return String(heat)
}

async function refreshStudio() {
  studio.value = await getStudio()
  const p = studio.value.profile
  profileForm.niche    = p.niche    || ''
  profileForm.audience = p.audience || ''
  profileForm.tone     = p.tone     || ''
  profileForm.avoid    = p.avoid    || ''
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
    studioMessage.value = '账号定位已保存'
  })
}

function doRecommend() {
  return runStudio('recommend', async () => {
    const list = await recommendTopics(recommendForm.platform, recommendForm.count)
    studioMessage.value = list.length
      ? `挑出 ${list.length} 条选题`
      : '当前热榜里没有适合这个账号的选题，可以先补全账号定位或稍后再试'
  })
}

function useSuggestion(suggestion: TopicSuggestion) {
  return runStudio('use-' + suggestion.id, async () => {
    await markSuggestion(suggestion.id, 'USED')
    publishTopic.value = suggestion.topic
    activeSection.value = '发布中心'
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

onMounted(async () => {
  try {
    session.value = await getSession()
    await refreshStudio()
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
  }
})
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
        <div class="menu-group-label">创作</div>
        <div class="menu-items">
          <button
            v-for="item in NAV_ITEMS"
            :key="item.id"
            class="menu-item"
            :class="{ active: activeSection === item.id }"
            @click="activeSection = item.id"
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
        <button class="btn-ghost" style="height:30px;font-size:12px;" @click="refreshStudio">
          <i class="ri-refresh-line"></i>刷新
        </button>
      </header>

      <div class="main-body">

        <!-- ===== 选题中心 ===== -->
        <template v-if="activeSection === '选题中心' && studio">
          <p v-if="studioMessage" class="message-bar">{{ studioMessage }}</p>

          <div v-if="!studio.profile.configured" class="capability-note">
            <i class="ri-error-warning-line"></i>
            <span>还没填账号定位。不填也能推荐，但填上「领域」后推荐质量差别很大。</span>
          </div>

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

            <!-- 账号定位 + 推荐 -->
            <div class="panel">
              <div class="panel-head"><div><h2>账号定位</h2><p>决定推荐质量</p></div></div>
              <label class="field-mini">领域<input v-model="profileForm.niche"    maxlength="200" placeholder="例如：程序员 AI 副业" /></label>
              <label class="field-mini">受众<input v-model="profileForm.audience" maxlength="200" placeholder="例如：想用 AI 提效的开发者" /></label>
              <label class="field-mini">语气<input v-model="profileForm.tone"     maxlength="200" placeholder="例如：轻松吐槽，像和朋友聊天" /></label>
              <label class="field-mini">避免<input v-model="profileForm.avoid"    maxlength="500" placeholder="例如：绝对化用词、政治话题" /></label>
              <button class="btn-ghost full" style="margin-bottom:16px;"
                      :disabled="studioBusy === 'profile'" @click="doSaveProfile">
                <i class="ri-save-line"></i>{{ studioBusy === 'profile' ? '保存中…' : '保存定位' }}
              </button>

              <div class="panel-head" style="margin-top:16px;padding-top:14px;border-top:1px solid var(--wao-divider);">
                <div><h2>选题推荐</h2><p>从热榜里挑你能做的</p></div>
              </div>
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

        <!-- ===== 发布中心 ===== -->
        <PublishCenter v-else-if="activeSection === '发布中心'" :initial-topic="publishTopic" />

      </div>
    </div>
  </div>
</template>
