<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  createTodayTianrenReading,
  createTianrenShareLink,
  fetchTianrenHistory,
  fetchTodayTianrenReading,
  streamTianrenInterpretation,
  type TianrenReading,
} from '@/api/tianren'
import { fetchAssistantConversation, fetchAssistantStatus } from '@/api/assistant'
import { copyToClipboard } from '@/utils/clipboard'
import { renderMarkdown } from '@/utils/markdown'
import TianrenNotificationStatus from '@/components/workday/TianrenNotificationStatus.vue'

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

const localDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
const draftKey = `wao-tianren-focus-v1:${localDate}`
const focus = ref(loadDraft())
const draftState = ref<'empty' | 'saved' | 'error'>(focus.value ? 'saved' : 'empty')

const syncText = computed(() => ({
  loading: '正在读取今日记录',
  casting: '正在保存今日卦象',
  saved: '已保存到账号',
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
const canInterpret = computed(() => isViewingToday.value && assistantConfigured.value && !interpreting.value)
const season = computed(() => {
  const month = Number((reading.value?.date ?? localDate).slice(5, 7))
  if (month >= 3 && month <= 5) return { name: '春 · 生', icon: 'ri-seedling-line', note: '留意舒展与循序，给今天保留一点向外生长的空间。' }
  if (month >= 6 && month <= 8) return { name: '夏 · 长', icon: 'ri-sun-line', note: '留意充养与不过耗，在活跃里安排清楚的停顿。' }
  if (month >= 9 && month <= 11) return { name: '秋 · 收', icon: 'ri-leaf-line', note: '留意收敛与规律，把精力带回真正重要的事情。' }
  return { name: '冬 · 藏', icon: 'ri-moon-clear-line', note: '留意休息与保暖，让恢复成为计划的一部分。' }
})

watch(focus, (value) => {
  try {
    if (value) localStorage.setItem(draftKey, value)
    else localStorage.removeItem(draftKey)
    draftState.value = value ? 'saved' : 'empty'
  } catch {
    draftState.value = 'error'
  }
})

function loadDraft() {
  try {
    return localStorage.getItem(draftKey) ?? ''
  } catch {
    return ''
  }
}

function loadAssistant(): Promise<boolean> {
  if (assistantStatusRequest) return assistantStatusRequest
  assistantLoading.value = true
  assistantStatusRequest = (async () => {
    try {
      const status = await fetchAssistantStatus()
      assistantConfigured.value = status.configured
      assistantMessage.value = status.message
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
  if (reading.value?.id === item.id) return
  reading.value = item
  interpretationError.value = ''
  resetShareFeedback()
  await loadConversation(item)
}

async function showToday() {
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
  interpretation.value = ''
  interpretationError.value = ''
  conversationError.value = ''
  try {
    for await (const chunk of streamTianrenInterpretation(focus.value.trim())) {
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
    interpretationError.value = cause instanceof Error ? cause.message : 'AI 解读失败，请保留关注点后重试'
  } finally {
    interpreting.value = false
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
      <div>
        <div class="eyebrow"><i class="ri-compass-3-line"></i> 易经 × 黄帝内经 × 本地 AI</div>
        <h1>天人一卦</h1>
        <p>每天只起一卦，用变化的象征照见当下，再把四时、起居与情志化成一件今天做得到的小事。</p>
      </div>
      <div class="sync-pill" :class="syncState">
        <span class="sync-dot"></span>{{ syncText }}
      </div>
    </header>

    <div v-if="loading" class="state-card">
      <span class="spinner"></span>
      <strong>正在取回你的每日记录</strong>
      <small>卦象和 AI 会话都按当前成员隔离保存。</small>
    </div>

    <div v-else-if="pageError && !reading" class="state-card error-card">
      <i class="ri-cloud-off-line"></i>
      <strong>暂时没有读到每日记录</strong>
      <small>{{ pageError }}</small>
      <button type="button" @click="loadPage()">重新读取</button>
    </div>

    <template v-else>
      <div v-if="pageError" class="inline-error" role="alert">
        <i class="ri-error-warning-line"></i><span>{{ pageError }}</span>
        <button type="button" @click="loadPage(false)">重试同步</button>
      </div>

      <TianrenNotificationStatus v-if="reading" :reading-id="reading.id" :interpreting="interpreting" />
      <div class="content-grid">
        <main class="main-column">
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

          <template v-else>
            <section class="cast-card card">
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
            </section>

            <section v-if="isViewingToday" class="reflection-card card">
              <div class="reflection-head">
                <div class="season-icon"><i :class="season.icon"></i></div>
                <div><span>四时自照</span><strong>{{ season.name }}</strong></div>
                <p>{{ season.note }}</p>
              </div>

              <div class="ai-status" :class="{ offline: !assistantConfigured && !assistantLoading }">
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

              <button class="interpret-button" type="button" :disabled="!canInterpret" @click="interpretToday">
                <i :class="interpreting ? 'ri-loader-4-line spin' : 'ri-sparkling-2-line'"></i>
                {{ interpreting ? '本地 AI 正在结合卦象与四时生成反思' : interpretation ? '根据关注点重新解读' : '请 AI 解读今日一卦' }}
              </button>
              <small class="privacy-note"><i class="ri-shield-user-line"></i> 起卦后会自动生成首次解读；之后可填写关注点重新解读。内容保存在你的成员私有 AI 会话，主动分享后公开链接会展示当前最新一条 AI 自照。</small>
            </section>

            <section v-else class="reflection-card history-reflection card">
              <div class="reflection-head">
                <div class="season-icon"><i class="ri-history-line"></i></div>
                <div><span>历史回看</span><strong>{{ formatHistoryDate(reading.date) }}</strong></div>
                <p>这里恢复当日保存的卦象和最后一份 AI 解读，不会用今天的状态重新生成过去结果。</p>
              </div>
            </section>

            <section v-if="!isViewingToday || interpreting || interpretation || interpretationError || conversationError" class="answer-card card">
              <div class="answer-head">
                <span class="answer-avatar"><i class="ri-sparkling-2-fill"></i></span>
                <div>
                  <strong>天人一卦 · 联合解读</strong>
                  <small>卦象是服务端事实；经典观点与当日建议由 AI 分层标注</small>
                </div>
              </div>
              <div v-if="(interpreting || conversationLoading) && !interpretation" class="answer-loading">
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
              <div v-if="interpretationError || conversationError" class="answer-error" role="alert">
                <i class="ri-error-warning-line"></i>
                <span>{{ interpretationError || conversationError }}</span>
                <button v-if="interpretationError" type="button" :disabled="!canInterpret" @click="interpretToday">保留关注点并重试</button>
                <button v-else type="button" @click="loadConversation(reading)">重新读取历史解读</button>
              </div>
            </section>
          </template>
        </main>

        <aside class="side-column">
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

          <section class="boundary-card card">
            <span class="boundary-icon"><i class="ri-heart-pulse-line"></i></span>
            <div>
              <strong>文化反思，不是医疗建议</strong>
              <p>传统观点和当日建议均为 AI 辅助的文化学习与自我观察，不是诊断或治疗方案；身体持续不适、症状加重或情况紧急时，请及时咨询合格医务人员。</p>
            </div>
          </section>

          <section class="method-card card">
            <span>方法透明</span>
            <strong>三枚硬币 · 六次成卦</strong>
            <ol>
              <li>每枚硬币等概率记作 2 或 3。</li>
              <li>三枚相加得到 6、7、8、9。</li>
              <li>从初爻向上记录，6 与 9 为变爻。</li>
              <li>服务端保存当天结果，不因刷新重新抽取。</li>
            </ol>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tianren-page { min-height: 100%; padding: 28px clamp(16px, 3vw, 42px) 46px; color: var(--wao-text); background: radial-gradient(circle at 3% 0%, color-mix(in srgb, #b68745 13%, transparent), transparent 30%), radial-gradient(circle at 96% 12%, color-mix(in srgb, #3b887b 11%, transparent), transparent 26%), var(--wao-bg-page); }
.hero { max-width: 1180px; margin: 0 auto 20px; display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; }.eyebrow { margin-bottom: 7px; color: #8e6534; font-size: 12px; font-weight: 650; letter-spacing: .04em; }.eyebrow i { margin-right: 6px; }.hero h1 { margin: 0; font-family: 'STKaiti', 'KaiTi', serif; font-size: clamp(32px, 4vw, 45px); font-weight: 700; letter-spacing: .05em; }.hero p { max-width: 720px; margin: 9px 0 0; color: var(--wao-text-3); font-size: 13px; line-height: 1.75; }
.sync-pill { height: 30px; padding: 0 11px; display: inline-flex; align-items: center; gap: 7px; border: 1px solid var(--wao-success-line); border-radius: 999px; background: var(--wao-success-bg); color: var(--wao-success-strong); font-size: 11px; white-space: nowrap; }.sync-pill.loading,.sync-pill.casting { border-color: var(--wao-primary-line); background: var(--wao-primary-soft); color: var(--wao-primary-text); }.sync-pill.error { border-color: var(--wao-danger-line); background: var(--wao-danger-bg); color: var(--wao-danger-strong); }.sync-dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }.sync-pill.loading .sync-dot,.sync-pill.casting .sync-dot { animation: pulse 1s ease infinite; }
.card { border: 1px solid var(--wao-line); border-radius: 16px; background: color-mix(in srgb, var(--wao-bg-card) 96%, #f2e5cf); box-shadow: var(--wao-shadow-card-hover); }.content-grid { max-width: 1180px; margin: 0 auto; display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(280px, .72fr); gap: 16px; align-items: start; }.main-column,.side-column { min-width: 0; display: grid; gap: 16px; }
.state-card { min-height: 420px; max-width: 1180px; margin: 0 auto; display: grid; place-items: center; align-content: center; gap: 10px; border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); color: var(--wao-text-3); }.state-card strong { color: var(--wao-text); font-size: 15px; }.state-card small { color: var(--wao-text-weak); }.state-card > i { font-size: 38px; color: var(--wao-danger); }.state-card button,.cast-button { border: 0; border-radius: 10px; background: #3d756b; color: #fff; cursor: pointer; }.state-card button { margin-top: 8px; padding: 9px 15px; }.spinner { width: 28px; height: 28px; border: 3px solid color-mix(in srgb, #3d756b 20%, transparent); border-top-color: #3d756b; border-radius: 50%; animation: spin .8s linear infinite; }
.inline-error { max-width: 1180px; margin: 0 auto 12px; padding: 10px 12px; display: flex; align-items: center; gap: 8px; border: 1px solid var(--wao-danger-line); border-radius: 9px; background: var(--wao-danger-bg); color: var(--wao-danger-strong); font-size: 12px; }.inline-error span { flex: 1; }.inline-error button { border: 0; background: transparent; color: inherit; font-weight: 650; cursor: pointer; }
.empty-cast { min-height: 510px; padding: 44px 24px; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }.empty-orbit { position: relative; width: 112px; height: 112px; margin-bottom: 20px; border: 1px solid color-mix(in srgb, #ae7d40 36%, var(--wao-line)); border-radius: 50%; display: flex; align-items: center; justify-content: space-around; color: #9a6d37; font-size: 31px; }.empty-orbit::before,.empty-orbit::after { content: ''; position: absolute; inset: 12px; border: 1px dashed color-mix(in srgb, #3d756b 34%, transparent); border-radius: 50%; transform: rotate(30deg); }.empty-orbit::after { inset: -7px; transform: rotate(-20deg); }.empty-orbit i { position: absolute; z-index: 1; color: #3d756b; font-size: 20px; }.today-label,.section-kicker { color: #8e6534; font-size: 11px; font-weight: 650; letter-spacing: .04em; }.empty-cast h2 { margin: 8px 0 7px; font-family: 'STKaiti', 'KaiTi', serif; font-size: 26px; }.empty-cast p { max-width: 500px; margin: 0; color: var(--wao-text-3); font-size: 12.5px; line-height: 1.75; }.empty-cast > small { margin-top: 12px; color: var(--wao-text-weak); font-size: 10px; }.cast-button { min-width: 180px; height: 42px; margin-top: 23px; font-weight: 650; }.cast-button i { margin-right: 7px; }.cast-button:disabled { opacity: .55; cursor: wait; }
.cast-card { padding: clamp(17px, 2.5vw, 24px); }.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }.card-head h2 { margin: 5px 0 4px; font-family: 'STKaiti', 'KaiTi', serif; font-size: clamp(23px, 3vw, 31px); letter-spacing: .03em; }.card-head p { margin: 0; color: var(--wao-text-weak); font-size: 11px; }.card-actions { display: flex; align-items: center; justify-content: flex-end; gap: 7px; flex-wrap: wrap; }.once-pill { padding: 6px 9px; border-radius: 999px; background: color-mix(in srgb, #3d756b 10%, transparent); color: #3d756b; font-size: 10px; white-space: nowrap; }.once-pill i { margin-right: 4px; }.share-button { min-height: 30px; padding: 0 10px; border: 1px solid color-mix(in srgb, #3d756b 35%, var(--wao-line)); border-radius: 999px; background: var(--wao-bg-card); color: #3d756b; cursor: pointer; font-size: 10px; font-weight: 650; white-space: nowrap; }.share-button i { margin-right: 4px; }.share-button:hover { background: color-mix(in srgb, #3d756b 7%, var(--wao-bg-card)); }.share-feedback { margin-top: 13px; padding: 9px 11px; display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 7px; border: 1px solid var(--wao-success-line); border-radius: 9px; background: var(--wao-success-bg); color: var(--wao-success-strong); font-size: 10.5px; }.share-feedback.manual { border-color: var(--wao-primary-line); background: var(--wao-primary-soft); color: var(--wao-primary-text); }.share-feedback.error { border-color: var(--wao-danger-line); background: var(--wao-danger-bg); color: var(--wao-danger-strong); }.share-feedback input { grid-column: 1 / -1; width: 100%; min-width: 0; padding: 7px 9px; border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-2); font-size: 11px; }.cast-layout { margin-top: 22px; display: grid; grid-template-columns: minmax(260px, .9fr) minmax(250px, 1fr); gap: clamp(18px, 3vw, 34px); align-items: center; }
.line-stack { padding: 13px 15px; display: flex; flex-direction: column; gap: 10px; border-radius: 13px; background: color-mix(in srgb, #3d756b 5%, var(--wao-bg-sub)); }.yao-row { min-height: 25px; display: grid; grid-template-columns: 23px minmax(110px, 1fr) 66px 24px; align-items: center; gap: 9px; }.line-position { color: var(--wao-text-weak); font-size: 10px; text-align: center; }.yao-shape { height: 11px; display: flex; gap: 15%; }.yao-shape i { display: block; flex: 1; border-radius: 2px; background: #283f3b; }.yao-shape:not(.yin) i { width: 100%; }.yao-row.changing .yao-shape i { background: #b36b42; box-shadow: 0 0 0 3px color-mix(in srgb, #b36b42 10%, transparent); }.line-value { color: var(--wao-text-weak); font-size: 9.5px; }.change-mark { width: 21px; height: 21px; border-radius: 50%; display: grid; place-items: center; background: color-mix(in srgb, #b36b42 12%, transparent); color: #a85d36; font-size: 9px; font-weight: 700; }
.hexagram-flow { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 12px; }.hex-card { min-width: 0; padding: 17px 10px; border: 1px solid color-mix(in srgb, #3d756b 24%, var(--wao-line)); border-radius: 13px; display: grid; justify-items: center; gap: 3px; background: color-mix(in srgb, #3d756b 4%, var(--wao-bg-card)); text-align: center; }.hex-card.primary { border-color: color-mix(in srgb, #ae7d40 35%, var(--wao-line)); background: color-mix(in srgb, #ae7d40 5%, var(--wao-bg-card)); }.hex-card > span { color: var(--wao-text-weak); font-size: 9px; }.hex-card > strong { color: #3d756b; font-size: 47px; line-height: 1.05; }.hex-card.primary > strong { color: #8e6534; }.hex-card > b { font-family: 'STKaiti', 'KaiTi', serif; font-size: 18px; }.hex-card > small { color: var(--wao-text-weak); font-size: 9px; }.hex-card.muted { opacity: .58; }.flow-arrow { display: grid; justify-items: center; gap: 3px; color: #a65f38; }.flow-arrow i { font-size: 20px; }.flow-arrow span { font-size: 8.5px; white-space: nowrap; }.flow-arrow.still { color: var(--wao-text-weak); }
.reflection-card { padding: clamp(17px, 2.5vw, 23px); }.reflection-head { display: grid; grid-template-columns: 42px auto 1fr; align-items: center; gap: 11px; }.season-icon { width: 42px; height: 42px; border-radius: 12px; display: grid; place-items: center; background: color-mix(in srgb, #3d756b 11%, transparent); color: #3d756b; font-size: 21px; }.reflection-head span,.reflection-head strong { display: block; }.reflection-head span { color: var(--wao-text-weak); font-size: 9.5px; }.reflection-head strong { margin-top: 2px; font-size: 14px; }.reflection-head p { margin: 0 0 0 8px; color: var(--wao-text-3); font-size: 11px; line-height: 1.6; }
.ai-status { margin-top: 16px; padding: 8px 10px; border-radius: 9px; display: flex; align-items: center; gap: 7px; background: var(--wao-success-bg); color: var(--wao-success-strong); font-size: 10.5px; }.ai-status.offline { background: var(--wao-warning-bg); color: var(--wao-warning-strong); }.status-dot { width: 6px; height: 6px; border-radius: 50%; background: #d99b37; }.status-dot.online { background: #39a371; }.ai-status span:nth-child(2) { flex: 1; }.ai-status button { border: 0; background: transparent; color: inherit; font-weight: 650; cursor: pointer; }
.focus-field { margin-top: 13px; display: block; }.focus-field > span:first-child { color: var(--wao-text-2); font-size: 11px; font-weight: 650; }.focus-field > span:first-child small { margin-left: 5px; color: var(--wao-text-weak); font-size: 9px; font-weight: 400; }.focus-field textarea { width: 100%; min-height: 86px; margin-top: 7px; padding: 11px 12px; resize: vertical; border: 1px solid var(--wao-line-control); border-radius: 11px; outline: 0; background: var(--wao-bg-sub); color: var(--wao-text); font: 12px/1.6 inherit; box-sizing: border-box; }.focus-field textarea:focus { border-color: color-mix(in srgb, #3d756b 70%, var(--wao-line)); box-shadow: 0 0 0 3px color-mix(in srgb, #3d756b 9%, transparent); }.focus-field textarea:disabled { opacity: .65; }.focus-foot { margin-top: 5px; display: flex; justify-content: space-between; gap: 10px; color: var(--wao-text-weak); font-size: 9px; }.focus-foot em { font-style: normal; }.focus-foot em.error { color: var(--wao-danger-strong); }.focus-foot i { margin-right: 3px; }
.interpret-button { width: 100%; min-height: 40px; margin-top: 11px; padding: 8px 13px; border: 0; border-radius: 10px; background: linear-gradient(135deg, #3d756b, #59776e); color: #fff; cursor: pointer; font-weight: 650; }.interpret-button i { margin-right: 6px; }.interpret-button:disabled { opacity: .42; cursor: not-allowed; }.privacy-note { margin-top: 9px; display: block; color: var(--wao-text-weak); font-size: 9px; line-height: 1.5; }.privacy-note i { margin-right: 4px; color: #3d756b; }
.answer-card { padding: clamp(17px, 2.5vw, 23px); }.answer-head { display: flex; align-items: center; gap: 10px; }.answer-avatar { width: 34px; height: 34px; border-radius: 10px; display: grid; place-items: center; background: linear-gradient(135deg, #3d756b, #99713e); color: white; }.answer-head strong,.answer-head small { display: block; }.answer-head strong { font-size: 13px; }.answer-head small { margin-top: 2px; color: var(--wao-text-weak); font-size: 9px; }.answer-loading { min-height: 110px; display: flex; align-items: center; justify-content: center; gap: 10px; color: var(--wao-text-weak); font-size: 11px; }.typing { display: inline-flex; gap: 3px; }.typing i { width: 5px; height: 5px; border-radius: 50%; background: #3d756b; animation: typing 1s infinite; }.typing i:nth-child(2) { animation-delay: .15s; }.typing i:nth-child(3) { animation-delay: .3s; }.markdown { margin-top: 17px; color: var(--wao-text-2); font-size: 12.5px; line-height: 1.75; }.markdown :deep(h2),.markdown :deep(h3) { margin: 18px 0 6px; color: var(--wao-text); font-size: 14px; }.markdown :deep(p) { margin: 0 0 9px; }.markdown :deep(ul),.markdown :deep(ol) { margin: 0 0 9px; padding-left: 20px; }.markdown :deep(blockquote) { margin: 10px 0; padding: 8px 12px; border-left: 3px solid #3d756b; background: var(--wao-bg-sub); }.answer-error { margin-top: 14px; padding: 9px 10px; border-radius: 8px; display: flex; align-items: center; gap: 7px; background: var(--wao-danger-bg); color: var(--wao-danger-strong); font-size: 10.5px; }.answer-error span { flex: 1; }.answer-error button { border: 0; background: transparent; color: inherit; cursor: pointer; font-weight: 650; }
.history-card { overflow: hidden; }.history-head { padding: 16px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--wao-divider); }.history-head span,.history-head strong { display: block; }.history-head span { color: var(--wao-text-weak); font-size: 9px; }.history-head strong { margin-top: 2px; font-size: 13px; }.history-head > i { color: #8e6534; font-size: 20px; }.history-list { padding: 7px; }.history-row { padding: 9px; display: flex; align-items: center; gap: 9px; border-radius: 9px; }.history-row.today { background: color-mix(in srgb, #3d756b 7%, transparent); }.history-symbol { width: 31px; height: 31px; flex: none; display: grid; place-items: center; border-radius: 8px; background: var(--wao-bg-sub); color: #8e6534; font-size: 20px; }.history-main { min-width: 0; flex: 1; }.history-main strong,.history-main span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.history-main strong { font-size: 11px; }.history-main span { margin-top: 2px; color: var(--wao-text-weak); font-size: 9px; }.ai-mark { color: #3d756b; font-size: 13px; }.history-empty { min-height: 180px; padding: 22px; display: grid; place-items: center; align-content: center; gap: 7px; color: var(--wao-text-weak); text-align: center; }.history-empty i { color: #3d756b; font-size: 28px; }.history-empty strong { color: var(--wao-text-3); font-size: 11px; }.history-empty span { font-size: 9.5px; }
.today-button { min-height: 30px; padding: 0 10px; border: 1px solid color-mix(in srgb, #3d756b 35%, var(--wao-line)); border-radius: 999px; background: var(--wao-bg-card); color: #3d756b; cursor: pointer; font-size: 10px; font-weight: 650; white-space: nowrap; }.today-button i { margin-right: 4px; }.today-button:hover { background: color-mix(in srgb, #3d756b 7%, var(--wao-bg-card)); }
.interpretation-legend { margin-top: 16px; display: flex; flex-wrap: wrap; gap: 6px; }.interpretation-legend span { padding: 5px 8px; border-radius: 999px; background: var(--wao-bg-sub); color: var(--wao-text-3); font-size: 9px; }.interpretation-legend i { margin-right: 3px; color: #3d756b; }.interpretation-legend + .markdown { margin-top: 13px; }.history-answer-empty { min-height: 130px; display: grid; justify-items: center; align-content: center; gap: 7px; color: var(--wao-text-weak); text-align: center; }.history-answer-empty i { color: #3d756b; font-size: 27px; }.history-answer-empty strong { color: var(--wao-text-3); font-size: 11px; }.history-answer-empty span { max-width: 380px; font-size: 9.5px; }
.history-list .history-row { width: 100%; border: 0; background: transparent; color: inherit; cursor: pointer; text-align: left; }.history-list .history-row:hover,.history-list .history-row:focus-visible { background: var(--wao-bg-sub); outline: 0; }.history-list .history-row.today { background: color-mix(in srgb, #3d756b 7%, transparent); }.history-list .history-row.selected { box-shadow: inset 0 0 0 1px color-mix(in srgb, #3d756b 30%, transparent); }.history-arrow { color: var(--wao-text-weak); font-size: 13px; }
.boundary-card { padding: 15px; display: flex; align-items: flex-start; gap: 10px; }.boundary-icon { width: 34px; height: 34px; flex: none; border-radius: 9px; display: grid; place-items: center; background: var(--wao-warning-bg); color: var(--wao-warning-strong); }.boundary-card strong { font-size: 11px; }.boundary-card p { margin: 5px 0 0; color: var(--wao-text-weak); font-size: 9.5px; line-height: 1.65; }.method-card { padding: 16px; }.method-card > span { color: var(--wao-text-weak); font-size: 9px; }.method-card > strong { margin-top: 2px; display: block; font-size: 12px; }.method-card ol { margin: 11px 0 0; padding-left: 18px; color: var(--wao-text-weak); font-size: 9.5px; line-height: 1.75; }
.spin { animation: spin .9s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } } @keyframes pulse { 50% { opacity: .35; transform: scale(.7); } } @keyframes typing { 50% { opacity: .25; transform: translateY(-2px); } }
@media (max-width: 940px) { .content-grid { grid-template-columns: 1fr; }.side-column { grid-template-columns: repeat(2, minmax(0, 1fr)); }.history-card { grid-row: span 2; }.cast-layout { grid-template-columns: 1fr 1fr; } }
@media (max-width: 650px) { .tianren-page { padding: 18px 12px 34px; }.hero { display: grid; gap: 12px; }.sync-pill { justify-self: start; }.hero h1 { font-size: 34px; }.content-grid { gap: 12px; }.main-column,.side-column { gap: 12px; }.side-column { grid-template-columns: 1fr; }.history-card { grid-row: auto; }.cast-card,.reflection-card,.answer-card { border-radius: 13px; }.card-head { display: grid; }.card-actions { justify-content: flex-start; }.cast-layout { grid-template-columns: 1fr; }.hexagram-flow { gap: 8px; }.reflection-head { grid-template-columns: 42px 1fr; }.reflection-head p { grid-column: 1 / -1; margin: 0; }.yao-row { grid-template-columns: 20px minmax(100px, 1fr) 62px 21px; gap: 6px; }.answer-error { align-items: flex-start; flex-wrap: wrap; }.answer-error span { min-width: calc(100% - 25px); } }
@media (prefers-reduced-motion: reduce) { .spinner,.spin,.sync-dot,.typing i { animation: none !important; } }
</style>
