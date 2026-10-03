<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  createTodayTianrenReading,
  createTianrenShareLink,
  fetchTianrenHistory,
  fetchTodayTianrenReading,
  fetchTianrenAiStatus,
  streamTianrenInterpretation,
  type TianrenReading,
} from '@/api/tianren'
import { fetchAssistantConversation } from '@/api/assistant'
import { copyToClipboard } from '@/utils/clipboard'
import { renderMarkdown } from '@/utils/markdown'
import TianrenNotificationStatus from '@/components/workday/TianrenNotificationStatus.vue'

const activeTab = ref<'reading' | 'structure' | 'history'>('reading')

type SyncState = 'loading' | 'casting' | 'saved' | 'error'

const reading = ref<TianrenReading | null>(null)
const todayReading = ref<TianrenReading | null>(null)
const history = ref<TianrenReading[]>([])
const loading = ref(true)
const casting = ref(false)
const pageError = ref('')
const syncState = ref<SyncState>('loading')
const sharing = ref(false)
const shareFeedback = ref('')
const shareUrl = ref('')
const shareError = ref(false)

const assistantLoading = ref(true)
const assistantConfigured = ref(false)
const assistantMessage = ref('正在连接本地 Codex')
const interpreting = ref(false)
const interpretation = ref('')
const interpretationError = ref('')
const conversationError = ref('')
const conversationLoading = ref(false)
let assistantStatusRequest: Promise<boolean> | null = null
const backgroundRunning = ref(false)
let statusTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
onBeforeUnmount(() => { disposed = true; if (statusTimer) clearTimeout(statusTimer) })

const localDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
const draftKey = `wao-tianren-focus-v1:${localDate}`
const focus = ref(loadDraft())
const draftState = ref<'empty' | 'saved' | 'error'>(focus.value ? 'saved' : 'empty')

const syncText = computed(() => ({
  loading: '正在读取今日记录',
  casting: '正在保存今日卦象',
  saved: todayReading.value ? '已保存到账号' : '今日尚未起卦',
  error: '同步失败',
})[syncState.value])

const displayLines = computed(() => (reading.value?.lines ?? [])
  .map((value, index) => ({
    value,
    position: index + 1,
    positionLabel: ['初', '二', '三', '四', '五', '上'][index],
    yang: value % 2 === 1,
    changing: value === 6 || value === 9,
    label: ({ 6: '老阴', 7: '少阳', 8: '少阴', 9: '老阳' } as Record<number, string>)[value],
  }))
  .reverse())

const interpretationHtml = computed(() => renderMarkdown(interpretation.value))
const isViewingToday = computed(() => reading.value?.date === localDate)
const canInterpret = computed(() => isViewingToday.value && assistantConfigured.value && !interpreting.value && !backgroundRunning.value)
const season = computed(() => {
  const month = Number((reading.value?.date ?? localDate).slice(5, 7))
  if (month >= 3 && month <= 5) return { name: '春 · 生', icon: 'ri-seedling-line', note: '留意舒展与循序，给今天保留一点向外生长的空间。' }
  if (month >= 6 && month <= 8) return { name: '夏 · 长', icon: 'ri-sun-line', note: '留意充养与不过耗，在活跃里安排清楚的停顿。' }
  if (month >= 9 && month <= 11) return { name: '秋 · 收', icon: 'ri-leaf-line', note: '留意收敛与规律，把精力带回真正重要的事情。' }
  return { name: '冬 · 藏', icon: 'ri-moon-clear-line', note: '留意休息与保暖，让恢复成为计划的一部分。' }
})

function saveDraft(value = focus.value) {
  try {
    if (value) localStorage.setItem(draftKey, value)
    else localStorage.removeItem(draftKey)
    draftState.value = value ? 'saved' : 'empty'
  } catch {
    draftState.value = 'error'
  }
}
watch(focus, value => saveDraft(value))

function loadDraft() {
  try {
    return localStorage.getItem(draftKey) ?? ''
  } catch {
    return ''
  }
}

function loadAssistant(): Promise<boolean> {
  if (assistantStatusRequest) return assistantStatusRequest
  if (statusTimer) clearTimeout(statusTimer)
  assistantLoading.value = true
  assistantStatusRequest = (async () => {
    try {
      const status = await fetchTianrenAiStatus()
      if (disposed) return false
      const wasRunning = backgroundRunning.value
      backgroundRunning.value = ['secure_queued', 'secure_running', 'queued', 'running', 'provider_queued', 'provider_running'].includes(status.runStatus || '')
      assistantConfigured.value = status.configured
      assistantMessage.value = status.message
      if (backgroundRunning.value) statusTimer = setTimeout(() => void loadAssistant(), 5000)
      else if (wasRunning && !interpreting.value && isViewingToday.value) await loadPage(false)
      return status.configured
    } catch (cause) {
      assistantConfigured.value = false
      assistantMessage.value = cause instanceof Error ? cause.message : '无法读取 AI 状态'
      return false
    } finally {
      assistantLoading.value = false
      assistantStatusRequest = null
    }
  })()
  return assistantStatusRequest
}

async function loadConversation(current: TianrenReading | null) {
  const requestedId = current?.id
  interpretation.value = ''
  conversationError.value = ''
  conversationLoading.value = false
  if (!current?.conversationId) return
  conversationLoading.value = true
  try {
    const detail = await fetchAssistantConversation(current.conversationId)
    if (reading.value?.id !== requestedId) return
    interpretation.value = [...detail.messages].reverse()
      .find((message) => message.role === 'assistant')?.content ?? ''
  } catch (cause) {
    if (reading.value?.id !== requestedId) return
    conversationError.value = cause instanceof Error ? cause.message : '历史解读读取失败'
  } finally {
    if (reading.value?.id === requestedId) conversationLoading.value = false
  }
}

async function selectReading(item: TianrenReading) {
  if (interpreting.value || casting.value) return
  activeTab.value = 'reading'
  if (reading.value?.id === item.id) return
  reading.value = item
  interpretationError.value = ''
  resetShareFeedback()
  await loadConversation(item)
}

async function showToday() {
  activeTab.value = 'reading'
  if (todayReading.value) {
    await selectReading(todayReading.value)
    return
  }
  reading.value = null
  interpretation.value = ''
  interpretationError.value = ''
  conversationError.value = ''
  conversationLoading.value = false
  resetShareFeedback()
}

function resetShareFeedback() {
  shareFeedback.value = ''
  shareUrl.value = ''
  shareError.value = false
}

async function loadPage(showLoading = true) {
  if (showLoading) loading.value = true
  pageError.value = ''
  syncState.value = 'loading'
  try {
    const [today, recent] = await Promise.all([
      fetchTodayTianrenReading(),
      fetchTianrenHistory(14),
    ])
    todayReading.value = today
    reading.value = today
    history.value = recent
    syncState.value = 'saved'
    await loadConversation(today)
  } catch (cause) {
    pageError.value = cause instanceof Error ? cause.message : '每日卦象读取失败'
    syncState.value = 'error'
  } finally {
    loading.value = false
  }
}

async function castToday() {
  if (casting.value) return
  casting.value = true
  pageError.value = ''
  syncState.value = 'casting'
  try {
    todayReading.value = await createTodayTianrenReading()
    reading.value = todayReading.value
    history.value = await fetchTianrenHistory(14)
    syncState.value = 'saved'
    const canStartInterpretation = assistantLoading.value
      ? await loadAssistant()
      : assistantConfigured.value
    if (canStartInterpretation) await interpretToday()
  } catch (cause) {
    pageError.value = cause instanceof Error ? cause.message : '今天的一卦没有保存成功'
    syncState.value = 'error'
  } finally {
    casting.value = false
  }
}

async function shareReading() {
  if (!reading.value || sharing.value) return
  sharing.value = true
  shareFeedback.value = ''
  shareUrl.value = ''
  shareError.value = false
  try {
    const url = await createTianrenShareLink(reading.value.id)
    if (await copyToClipboard(url)) {
      shareFeedback.value = '公网结果链接已复制，任何拿到链接的人都能查看这份卦象和当前最新 AI 自照。'
    } else {
      shareUrl.value = url
      shareFeedback.value = '浏览器没有允许自动复制，请手动复制下面的链接。'
    }
  } catch (cause) {
    shareError.value = true
    shareFeedback.value = cause instanceof Error ? cause.message : '分享链接生成失败，请重试'
  } finally {
    sharing.value = false
  }
}

async function interpretToday() {
  if (!canInterpret.value) return
  interpreting.value = true
  const previous = interpretation.value
  interpretationError.value = ''
  conversationError.value = ''
  let received = false
  try {
    for await (const chunk of streamTianrenInterpretation(focus.value.trim())) {
      if (!received) { interpretation.value = ''; received = true }
      interpretation.value += chunk
    }
    const latest = await fetchTodayTianrenReading()
    if (latest) {
      todayReading.value = latest
      reading.value = latest
    }
    history.value = await fetchTianrenHistory(14)
    focus.value = ''
  } catch (cause) {
    interpretation.value = previous
    interpretationError.value = cause instanceof Error ? cause.message : 'AI 解读失败，请保留关注点后重试'
  } finally {
    interpreting.value = false
    void loadAssistant()
  }
}

function formatHistoryDate(value: string) {
  return new Date(`${value}T12:00:00`).toLocaleDateString('zh-CN', {
    month: 'long', day: 'numeric', weekday: 'short',
  })
}

onMounted(() => {
  void Promise.all([loadPage(), loadAssistant()])
})
</script>

<template>
  <div class="tianren-page">
    <header class="hero">
      <div class="page-title"><span class="page-icon"><i class="ri-windy-line" aria-hidden="true"></i></span><div><h1>天人一卦</h1><p>观照当下，留下一点行动。</p></div></div>
      <div class="hero-actions">
        <span class="sync-pill" :class="syncState" role="status">{{ syncText }}</span>
        <TianrenNotificationStatus v-if="reading" :reading-id="reading.id" :interpreting="interpreting" />
        <RouterLink v-else class="notification-settings" to="/settings?tab=notifications#tianren-notifications"><i class="ri-settings-3-line"></i>配置通知群</RouterLink>
      </div>
    </header>
    <div v-if="loading" class="state-card" role="status"><span class="spinner"></span><strong>正在取回你的每日记录</strong></div>
    <div v-else-if="pageError && !reading" class="state-card error-card"><strong>暂时没有读到每日记录</strong><p>{{ pageError }}</p><button type="button" @click="loadPage()">重新读取</button></div>
    <main v-else class="page-content">
      <div v-if="pageError" class="inline-error" role="alert"><span>{{ pageError }}</span><button type="button" :disabled="interpreting || casting" @click="loadPage(false)">重试同步</button></div>
          <section v-if="!reading" class="empty-cast card">
            <div class="empty-orbit">
              <span>☰</span><span>☷</span>
              <i class="ri-sparkling-2-fill"></i>
            </div>
            <span class="today-label">{{ formatHistoryDate(localDate) }}</span>
            <h2>今天还没有起卦</h2>
            <p>服务端会模拟六次三枚硬币投掷，按初爻到上爻保存 6、7、8、9；今天再次打开仍是同一卦。</p>
            <button class="cast-button" type="button" :disabled="casting" @click="castToday">
              <i :class="casting ? 'ri-loader-4-line spin' : 'ri-copper-coin-line'"></i>
              {{ casting ? '正在起卦并保存' : '起出今日一卦' }}
            </button>
            <small>随机卦象用于文化反思，不预测吉凶，也不替代现实判断。</small>
          </section>


      <section v-if="reading" class="cast-card card">
              <div class="card-head">
                <div>
                  <span class="section-kicker">{{ isViewingToday ? '今日卦象' : '历史卦象' }} · {{ formatHistoryDate(reading.date) }}</span>
                  <h2>{{ reading.primary.symbol }} 第 {{ reading.primary.number }} 卦 · {{ reading.primary.name }}</h2>
                  <p>上{{ reading.primary.upper.name }}（{{ reading.primary.upper.nature }}）下{{ reading.primary.lower.name }}（{{ reading.primary.lower.nature }}）</p>
                </div>
                <div class="card-actions">
                  <button v-if="!isViewingToday" class="today-button" type="button" @click="showToday">
                    <i class="ri-sun-line"></i> 回到今日
                  </button>
                  <span class="once-pill"><i class="ri-lock-line"></i> {{ isViewingToday ? '今日唯一' : '历史存档' }}</span>
                  <button class="share-button" type="button" :disabled="sharing" @click="shareReading">
                    <i :class="sharing ? 'ri-loader-4-line spin' : 'ri-share-forward-line'"></i>
                    {{ sharing ? '正在生成链接' : '复制分享链接' }}
                  </button>
                </div>
              </div>

              <div v-if="shareFeedback" class="share-feedback" :role="shareError ? 'alert' : 'status'" :class="{ manual: shareUrl, error: shareError }">
                <i :class="shareError ? 'ri-error-warning-line' : shareUrl ? 'ri-information-line' : 'ri-checkbox-circle-line'"></i>
                <span>{{ shareFeedback }}</span>
                <input v-if="shareUrl" :value="shareUrl" readonly aria-label="天人一卦分享链接" @focus="($event.target as HTMLInputElement).select()" />
              </div>


      </section>
      <nav class="content-tabs" aria-label="卦象内容">
        <button v-for="tab in ([{ id: 'reading', label: '今日解读' }, { id: 'structure', label: '卦象结构' }, { id: 'history', label: '往日记录' }] as const)" :key="tab.id" type="button" :aria-pressed="activeTab === tab.id" :aria-controls="`tianren-${tab.id}`" @click="activeTab = tab.id">{{ tab.id === 'reading' && reading && !isViewingToday ? '当日解读' : tab.label }}</button>
      </nav>
      <section v-show="activeTab === 'reading'" id="tianren-reading" aria-label="当日解读">
        <div v-if="reading" class="reading-layout">
          <div class="reading-main">
            <section class="answer-card card">
              <div class="answer-head">
                <span class="answer-avatar"><i class="ri-sparkling-2-fill"></i></span>
                <div>
                  <strong>天人一卦 · 联合解读</strong>
                  <small>卦象是服务端事实；经典观点与当日建议由 AI 分层标注</small>
                </div>
              </div>
              <div v-if="(interpreting || backgroundRunning || conversationLoading) && !interpretation" class="answer-loading">
                <span class="typing"><i></i><i></i><i></i></span>
                <span>{{ conversationLoading ? '正在取回这一天保存的联合解读……' : '正在梳理卦意、生活观察与今天的调养提示……' }}</span>
              </div>
              <div v-if="interpretation" class="interpretation-legend" aria-label="解读内容标注说明">
                <span><i class="ri-lock-line"></i> 卦象事实</span>
                <span><i class="ri-book-open-line"></i> 传统观点 · AI 概括</span>
                <span><i class="ri-sparkling-line"></i> 当日建议 · AI 延伸</span>
              </div>
              <div v-if="interpretation" class="markdown" v-html="interpretationHtml"></div>
              <div v-else-if="!isViewingToday && !conversationLoading && !conversationError" class="history-answer-empty">
                <i class="ri-chat-history-line"></i>
                <strong>这一天没有保存 AI 解读</strong>
                <span>完整卦象仍可回看；历史结果不会用今天的接口补做或伪造。</span>
              </div>
<p v-if="isViewingToday && !interpretation && !interpreting && !backgroundRunning && !conversationLoading && !conversationError" class="empty-answer">今日还没有保存 AI 解读。可以填写关注点，再发起解读。</p>
              <div v-if="interpretationError || conversationError" class="answer-error" role="alert">
                <i class="ri-error-warning-line"></i>
                <span>{{ interpretationError || conversationError }}</span>
                <button v-if="interpretationError" type="button" :disabled="!canInterpret" @click="interpretToday">保留关注点并重试</button>
                <button v-else type="button" @click="loadConversation(reading)">重新读取历史解读</button>
              </div>
            </section>
              <div class="reflection-head">
                <div class="season-icon"><i :class="season.icon"></i></div>
                <div><span>四时自照</span><strong>{{ season.name }}</strong></div>
                <p>{{ season.note }}</p>
              </div>


          </div>
            <section v-if="isViewingToday" class="reflection-card card">
<h3>留给今天的关注点</h3><p class="muted">写下一句话，结合今天的卦象继续解读。</p>              <div class="ai-status" :class="{ offline: !assistantConfigured && !assistantLoading }">
                <span class="status-dot" :class="{ online: assistantConfigured }"></span>
                <span>{{ assistantLoading ? '正在连接本地 Codex' : assistantMessage }}</span>
                <button v-if="!assistantConfigured && !assistantLoading" type="button" @click="loadAssistant">重新连接</button>
              </div>

              <label class="focus-field">
                <span>今天想照见什么？<small>可不填</small></span>
                <textarea
                  v-model="focus"
                  maxlength="500"
                  :disabled="interpreting"
                  placeholder="例如：最近总在赶进度，怎样安排今天的节奏？"
                  @keydown.ctrl.enter.prevent="interpretToday"
                  @keydown.meta.enter.prevent="interpretToday"
                ></textarea>
                <span class="focus-foot">
                  <em :class="draftState">
                    <i :class="draftState === 'error' ? 'ri-error-warning-line' : 'ri-draft-line'"></i>
                    {{ draftState === 'saved' ? '关注点草稿已保存在本机' : draftState === 'error' ? '草稿暂时无法保存' : '输入后自动保存草稿' }}
                  </em>
                  <small>{{ focus.length }}/500</small>
                </span>
              </label>
              <button v-if="draftState === 'error'" type="button" @click="saveDraft()">重试保存本机草稿</button>

              <button class="interpret-button" type="button" :disabled="!canInterpret" @click="interpretToday">
                <i :class="interpreting ? 'ri-loader-4-line spin' : 'ri-sparkling-2-line'"></i>
                {{ interpreting ? '本地 AI 正在结合卦象与四时生成反思' : interpretation ? '根据关注点重新解读' : '请 AI 解读今日一卦' }}
              </button>
              <small class="privacy-note"><i class="ri-shield-user-line"></i> 起卦后会自动生成首次解读；之后可填写关注点重新解读。内容保存在你的成员私有 AI 会话，主动分享后公开链接会展示当前最新一条 AI 自照。</small>
            </section>


        </div>
        <p v-else class="empty-answer">起卦后，可以在这里查看今日解读。</p>
      </section>
      <section v-show="activeTab === 'structure'" id="tianren-structure" aria-label="卦象结构">
        <template v-if="reading"><h3>六爻 · 自下而上起卦</h3>
              <div class="cast-layout">
                <div class="line-stack" aria-label="今日六爻，从上爻到初爻展示">
                  <div v-for="line in displayLines" :key="line.position" class="yao-row" :class="{ changing: line.changing }">
                    <span class="line-position">{{ line.positionLabel }}</span>
                    <span class="yao-shape" :class="{ yin: !line.yang }">
                      <i></i><i v-if="!line.yang"></i>
                    </span>
                    <span class="line-value">{{ line.value }} · {{ line.label }}</span>
                    <span v-if="line.changing" class="change-mark">变</span>
                  </div>
                </div>

                <div class="hexagram-flow">
                  <div class="hex-card primary">
                    <span>本卦</span>
                    <strong>{{ reading.primary.symbol }}</strong>
                    <b>{{ reading.primary.name }}</b>
                    <small>第 {{ reading.primary.number }} 卦</small>
                  </div>
                  <div class="flow-arrow" :class="{ still: !reading.relating }">
                    <i :class="reading.relating ? 'ri-arrow-right-line' : 'ri-subtract-line'"></i>
                    <span>{{ reading.changingLines.length ? `${reading.changingLines.length} 个变爻` : '无变爻' }}</span>
                  </div>
                  <div class="hex-card" :class="{ muted: !reading.relating }">
                    <span>之卦</span>
                    <strong>{{ reading.relating?.symbol ?? '—' }}</strong>
                    <b>{{ reading.relating?.name ?? '守其本卦' }}</b>
                    <small>{{ reading.relating ? `第 ${reading.relating.number} 卦` : '今天不另生之卦' }}</small>
                  </div>
                </div>
              </div>
</template>
        <p v-else class="empty-answer">尚无今日卦象。</p>
          <details class="method-card"><summary>三枚硬币 · 六次成卦 · 查看方法</summary>
            <span>方法透明</span>
            <strong>三枚硬币 · 六次成卦</strong>
            <ol>
              <li>每枚硬币等概率记作 2 或 3。</li>
              <li>三枚相加得到 6、7、8、9。</li>
              <li>从初爻向上记录，6 与 9 为变爻。</li>
              <li>服务端保存当天结果，不因刷新重新抽取。</li>
            </ol>
          </details>

      </section>
      <section v-show="activeTab === 'history'" id="tianren-history" aria-label="往日记录">
          <section class="history-card card">
            <div class="history-head">
              <div><span>长期记录</span><strong>最近十四卦</strong></div>
              <i class="ri-history-line"></i>
            </div>
            <div v-if="history.length" class="history-list">
              <button
                v-for="item in history"
                :key="item.id"
                class="history-row"
                :class="{ today: item.date === localDate, selected: item.id === reading?.id }"
                :disabled="interpreting || casting"
                type="button"
                :aria-pressed="item.id === reading?.id"
                @click="selectReading(item)"
              >
                <span class="history-symbol">{{ item.primary.symbol }}</span>
                <div class="history-main">
                  <strong>{{ item.primary.name }}<template v-if="item.relating"> → {{ item.relating.name }}</template></strong>
                  <span>{{ formatHistoryDate(item.date) }} · {{ item.changingLines.length ? `${item.changingLines.length} 个变爻` : '无变爻' }}</span>
                </div>
                <span v-if="item.conversationId" class="ai-mark" title="已有 AI 解读"><i class="ri-sparkling-line"></i></span>
                <i class="ri-arrow-right-s-line history-arrow"></i>
              </button>
            </div>
            <div v-else class="history-empty">
              <i class="ri-plant-line"></i>
              <strong>第一卦还在今天</strong>
              <span>起卦后会按账号保存，换设备也能在这里看到。</span>
            </div>
          </section>


      </section>
      <footer class="boundary-note">文化反思，不是医疗建议。用作文化阅读与自我观照，不作为医疗或决策依据。</footer>
    </main>
  </div>
</template>

<style scoped>
.tianren-page { width: 100%; box-sizing: border-box; min-height: 100%; padding: 24px 28px 40px; color: var(--wao-text); background: var(--wao-bg-page); container-type: inline-size; }
.hero, .page-content { max-width: var(--wao-content-max); margin: 0 auto; }
.hero { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 16px; margin-bottom: 24px; }
.page-title { display: flex; align-items: center; gap: 12px; }.page-icon { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 10px; background: var(--wao-primary-soft); color: var(--wao-primary); font-size: 22px; }
h1 { margin: 0; font-size: 23px; font-weight: 650; }h2 { font-size: 22px; margin: 8px 0; }h3 { font-size: 15px; margin: 0 0 12px; }.hero p, .muted { margin: 4px 0 0; color: var(--wao-text-3); font-size: 12px; }
.hero-actions, .card-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }.sync-pill, .once-pill { padding: 5px 9px; border-radius: 20px; background: var(--wao-bg-sub); color: var(--wao-text-3); font-size: 11px; }.sync-pill.error { color: var(--wao-danger-strong); }.notification-settings { color: var(--wao-text-3); font-size: 12px; }
button { cursor: pointer; font: inherit; font-size: 12px; border: 1px solid var(--wao-line); border-radius: 7px; padding: 7px 10px; background: var(--wao-bg-card); color: var(--wao-text); }button:disabled { opacity: .5; cursor: default; }button:focus-visible, a:focus-visible, summary:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; }
.card { min-width: 0; }.cast-card { padding: 20px 24px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); }.card-head { display: flex; align-items: center; justify-content: space-between; gap: 20px; flex-wrap: wrap; }.section-kicker { color: var(--wao-text-3); font-size: 12px; }.card-head p { font-size: 12px; margin: 0; color: var(--wao-text-3); }.card-head h2 { color: var(--wao-primary-text); }.share-feedback { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; font-size: 12px; color: var(--wao-text-3); }.share-feedback input { width: 100%; padding: 8px; border: 1px solid var(--wao-line); border-radius: 6px; background: var(--wao-bg-sub); color: var(--wao-text); }
.content-tabs { display: flex; gap: 22px; margin: 20px 0 24px; border-bottom: 1px solid var(--wao-line); }.content-tabs button { border: 0; border-bottom: 2px solid transparent; border-radius: 0; padding: 12px 0; background: transparent; color: var(--wao-text-3); }.content-tabs button[aria-pressed="true"] { color: var(--wao-primary-text); border-bottom-color: var(--wao-primary); }
.reading-layout { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 28px; align-items: start; }.reading-main { min-width: 0; }.answer-head { display: flex; align-items: center; gap: 8px; color: var(--wao-primary-text); font-size: 13px; }.answer-head small { display: block; color: var(--wao-text-3); margin-top: 4px; font-size: 11px; }.answer-avatar { font-size: 20px; }
.reflection-card { padding: 18px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-sub); }.reflection-head { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; border-top: 1px solid var(--wao-line); margin-top: 24px; padding-top: 18px; font-size: 12px; }.reflection-head span { display: block; color: var(--wao-text-3); font-size: 11px; }.reflection-head p { color: var(--wao-text-3); margin: 0; }.season-icon { font-size: 22px; color: var(--wao-primary-text); }
.ai-status { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: 14px 0; font-size: 12px; color: var(--wao-text-3); overflow-wrap: anywhere; }.ai-status.offline { color: var(--wao-warning-strong); }.ai-status button { font-size: 11px; padding: 4px 6px; }.status-dot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; flex: none; }
.focus-field { display: grid; gap: 8px; font-size: 12px; }.focus-field > span > small { margin-left: 8px; color: var(--wao-text-3); }.focus-field textarea { resize: vertical; width: 100%; box-sizing: border-box; min-height: 130px; padding: 12px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text); font: inherit; line-height: 1.6; }.focus-field textarea:focus { outline-color: var(--wao-primary); }.focus-foot { display: flex; justify-content: space-between; gap: 8px; color: var(--wao-text-3); font-size: 11px; }.focus-foot em { font-style: normal; }.focus-foot .error { color: var(--wao-danger-strong); }.interpret-button, .cast-button { background: var(--wao-primary); color: white; border-color: transparent; margin-top: 14px; line-height: 1.6; }.interpret-button { width: 100%; }.privacy-note { display: block; margin-top: 12px; color: var(--wao-text-3); line-height: 1.7; font-size: 11px; }
.interpretation-legend { display: flex; flex-wrap: wrap; gap: 12px; margin: 18px 0; font-size: 11px; color: var(--wao-text-3); }.markdown { font-size: 14px; line-height: 1.85; overflow-wrap: anywhere; }.markdown :deep(h1), .markdown :deep(h2), .markdown :deep(h3) { font-size: 18px; margin: 20px 0 10px; }.markdown :deep(p) { margin: 0 0 12px; }.markdown :deep(pre) { white-space: pre-wrap; }.markdown :deep(img) { max-width: 100%; }.markdown :deep(table) { display: block; max-width: 100%; overflow-x: auto; }.markdown :deep(blockquote) { border-left: 3px solid var(--wao-primary); margin: 12px 0; padding-left: 14px; color: var(--wao-text-3); }
.empty-answer, .history-answer-empty, .answer-loading { padding: 24px 0; color: var(--wao-text-3); font-size: 13px; line-height: 1.8; }.history-answer-empty { display: grid; gap: 6px; }.answer-error, .inline-error { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding: 12px; margin: 12px 0; background: var(--wao-danger-bg); color: var(--wao-danger-strong); border-radius: 8px; font-size: 12px; }
.cast-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 32px; align-items: center; }.line-stack { padding: 24px; border-radius: 12px; background: var(--wao-bg-sub); display: grid; gap: 18px; }.yao-row { display: grid; grid-template-columns: 20px minmax(60px, 1fr) 68px 20px; align-items: center; gap: 10px; font-size: 12px; }.line-position, .line-value { color: var(--wao-text-3); }.yao-shape { display: flex; gap: 16%; }.yao-shape i { flex: 1; height: 8px; border-radius: 1px; background: var(--wao-primary); }.change-mark { color: var(--wao-warning-strong); }.hexagram-flow { display: flex; align-items: center; justify-content: center; gap: 16px; }.hex-card { flex: 1; display: grid; justify-items: center; gap: 6px; padding: 20px 8px; border: 1px solid var(--wao-line); border-radius: 10px; }.hex-card strong { font-size: 48px; line-height: 1.2; color: var(--wao-primary); }.hex-card span, .hex-card small, .flow-arrow { font-size: 11px; color: var(--wao-text-3); }.flow-arrow { display: grid; justify-items: center; gap: 5px; }.method-card { margin-top: 24px; font-size: 12px; color: var(--wao-text-3); line-height: 1.8; }.method-card summary { cursor: pointer; color: var(--wao-primary-text); }.method-card > span, .method-card > strong { display: none; }
.history-head { display: flex; align-items: center; justify-content: space-between; padding-bottom: 16px; }.history-head span { display: none; }.history-head strong { font-size: 14px; }.history-list { display: grid; gap: 8px; }.history-row { width: 100%; display: flex; text-align: left; align-items: center; gap: 12px; padding: 12px; }.history-row.selected { border-color: var(--wao-primary); background: var(--wao-primary-soft); }.history-symbol { font-size: 30px; color: var(--wao-primary); }.history-main { flex: 1; min-width: 0; }.history-main strong, .history-main span { display: block; }.history-main span { font-size: 12px; color: var(--wao-text-3); margin-top: 4px; }.ai-mark { color: var(--wao-primary-text); }.history-empty { display: grid; justify-items: center; gap: 10px; padding: 32px; font-size: 13px; color: var(--wao-text-3); }
.boundary-note { margin-top: 28px; color: var(--wao-text-3); font-size: 11px; line-height: 1.7; }.state-card, .empty-cast { display: grid; justify-items: center; align-content: center; gap: 12px; padding: 36px 20px; text-align: center; }.empty-cast { border: 1px solid var(--wao-line); border-radius: 12px; }.empty-cast p { max-width: 540px; color: var(--wao-text-3); font-size: 13px; line-height: 1.8; }.empty-cast small { color: var(--wao-text-3); font-size: 11px; }.empty-orbit { font-size: 30px; color: var(--wao-primary); }.empty-orbit i { display: none; }.today-label { color: var(--wao-text-3); font-size: 12px; }
.spin, .spinner { animation: spin .9s linear infinite; }.spinner { width: 20px; height: 20px; border: 2px solid var(--wao-line); border-top-color: var(--wao-primary); border-radius: 50%; }@keyframes spin { to { transform: rotate(360deg); } }
@container (max-width: 780px) { .reading-layout, .cast-layout { grid-template-columns: 1fr; }.hero-actions { width: 100%; justify-content: flex-start; }.reflection-card { margin-top: 6px; } }
@media (max-width: 650px) { .tianren-page { padding: 18px 14px 32px; }.cast-card { padding: 16px; }.card-actions { gap: 6px; }.focus-field textarea { font-size: 16px; }.line-stack { padding: 18px 12px; }.yao-row { gap: 6px; grid-template-columns: 18px minmax(50px, 1fr) 62px 18px; }.hexagram-flow { gap: 8px; } }
@media (prefers-reduced-motion: reduce) { .spin, .spinner { animation: none; } }
</style>
