<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  fetchEnglishLearning,
  saveEnglishLearningProfile,
  saveEnglishLearningSession,
  type EnglishLearningOverview,
  type SaveEnglishLearningSession,
} from '@/api/englishLearning'
import { captureVocabulary } from '@/api/vocabulary'
import { ENGLISH_LESSONS, lessonById } from '@/data/englishLessons'
import { toast } from '@/composables/toast'

type SaveState = 'idle' | 'dirty' | 'saving' | 'saved' | 'error'
const PROFILE_DRAFT_KEY = 'wao-english-learning-profile-draft-v1'
const SESSION_DRAFT_PREFIX = 'wao-english-learning-session-draft-v1:'
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())

const loading = ref(true)
const loadError = ref('')
const overview = ref<EnglishLearningOverview | null>(null)
const selectedDate = ref(today)
const hydrated = ref(false)
const profileOpen = ref(false)
const profileBusy = ref(false)
const profileError = ref('')
const saveState = ref<SaveState>('idle')
const saveError = ref('')
const transcriptVisible = ref(false)
const speaking = ref(false)
const recordState = ref<'idle' | 'requesting' | 'recording' | 'ready' | 'error'>('idle')
const recordError = ref('')
const audioUrl = ref('')
const capturingTerm = ref('')
const capturedTerms = ref(new Set<string>())
let recorder: MediaRecorder | null = null
let recorderStream: MediaStream | null = null
let recorderChunks: Blob[] = []

const profileForm = reactive({
  goal: '能够在跨国协作中自然完成日常英文交流，不再因为害怕犯错而沉默。',
  dailyTargetMinutes: 25,
  startDate: today,
})
const sessionForm = reactive<SaveEnglishLearningSession>({
  lessonId: '', listeningDone: false, speakingDone: false, readingDone: false,
  writingDone: false, practiceMinutes: 0, confidence: null, writingText: '', reflection: '',
  nextFocus: '', completed: false,
})

const lesson = computed(() => {
  const stored = lessonById(sessionForm.lessonId)
  if (stored) return stored
  const next = (overview.value?.completedDays ?? 0) % ENGLISH_LESSONS.length
  return ENGLISH_LESSONS[next]!
})
const isToday = computed(() => selectedDate.value === today)
const completedSkills = computed(() => [
  sessionForm.listeningDone, sessionForm.speakingDone, sessionForm.readingDone,
  sessionForm.writingDone,
].filter(Boolean).length)
const canComplete = computed(() => completedSkills.value === 4 && sessionForm.confidence !== null)
const isCompleted = computed(() => Boolean(overview.value?.session?.completedAt))
const cycleNumber = computed(() => Math.floor((overview.value?.completedDays ?? 0) / ENGLISH_LESSONS.length) + 1)
const saveLabel = computed(() => ({
  idle: '已同步', dirty: '有未保存修改', saving: '正在保存…', saved: '已保存', error: '保存失败',
})[saveState.value])
const history = computed(() => overview.value?.recentSessions ?? [])
const profileProgress = computed(() => {
  const profile = overview.value?.profile
  if (!profile) return 0
  const elapsed = Math.max(1, Math.floor((Date.parse(`${today}T12:00:00+08:00`) - Date.parse(`${profile.startDate}T12:00:00+08:00`)) / 86_400_000) + 1)
  return Math.min(100, Math.round(((overview.value?.completedDays ?? 0) / elapsed) * 100))
})

watch(sessionForm, () => {
  if (!hydrated.value) return
  saveState.value = 'dirty'
  localStorage.setItem(sessionDraftKey(selectedDate.value), JSON.stringify(sessionForm))
}, { deep: true })

async function load(date = selectedDate.value) {
  loading.value = true
  loadError.value = ''
  hydrated.value = false
  try {
    const result = await fetchEnglishLearning(date)
    overview.value = result
    selectedDate.value = result.selectedDate
    hydrateSession(result)
    if (result.profile) {
      Object.assign(profileForm, {
        goal: result.profile.goal,
        dailyTargetMinutes: result.profile.dailyTargetMinutes,
        startDate: result.profile.startDate,
      })
    } else {
      restoreProfileDraft()
      profileOpen.value = true
    }
  } catch (error) {
    loadError.value = messageOf(error, '英语训练读取失败')
  } finally {
    loading.value = false
    await nextTick()
    hydrated.value = true
  }
}

function hydrateSession(result: EnglishLearningOverview) {
  const nextLesson = ENGLISH_LESSONS[result.completedDays % ENGLISH_LESSONS.length]!
  const stored = result.session
  const initial: SaveEnglishLearningSession = stored ? {
    lessonId: stored.lessonId,
    listeningDone: stored.listeningDone,
    speakingDone: stored.speakingDone,
    readingDone: stored.readingDone,
    writingDone: stored.writingDone,
    practiceMinutes: stored.practiceMinutes,
    confidence: stored.confidence,
    writingText: stored.writingText,
    reflection: stored.reflection,
    nextFocus: stored.nextFocus,
    completed: Boolean(stored.completedAt),
  } : {
    lessonId: nextLesson.id,
    listeningDone: false, speakingDone: false, readingDone: false, writingDone: false,
    practiceMinutes: result.profile?.dailyTargetMinutes ?? 25,
    confidence: null, writingText: '', reflection: '',
    nextFocus: result.recentSessions.find((item) => item.completedAt)?.nextFocus ?? '', completed: false,
  }
  const raw = localStorage.getItem(sessionDraftKey(result.selectedDate))
  if (raw) {
    try {
      const draft = JSON.parse(raw) as Partial<SaveEnglishLearningSession>
      if (lessonById(draft.lessonId ?? '')) Object.assign(initial, draft)
      saveState.value = 'dirty'
    } catch {
      localStorage.removeItem(sessionDraftKey(result.selectedDate))
    }
  } else saveState.value = 'idle'
  Object.assign(sessionForm, initial)
  transcriptVisible.value = false
  resetRecording()
  saveError.value = ''
}

function restoreProfileDraft() {
  const raw = localStorage.getItem(PROFILE_DRAFT_KEY)
  if (!raw) return
  try { Object.assign(profileForm, JSON.parse(raw)) } catch { localStorage.removeItem(PROFILE_DRAFT_KEY) }
}

function editProfile() {
  const profile = overview.value?.profile
  if (profile) Object.assign(profileForm, {
    goal: profile.goal, dailyTargetMinutes: profile.dailyTargetMinutes, startDate: profile.startDate,
  })
  profileError.value = ''
  profileOpen.value = true
}

function profileChanged() {
  localStorage.setItem(PROFILE_DRAFT_KEY, JSON.stringify(profileForm))
}

async function submitProfile() {
  if (!profileForm.goal.trim()) { profileError.value = '请写下真正想达到的英文交流目标'; return }
  profileBusy.value = true
  profileError.value = ''
  try {
    await saveEnglishLearningProfile({ ...profileForm, goal: profileForm.goal.trim() })
    localStorage.removeItem(PROFILE_DRAFT_KEY)
    profileOpen.value = false
    await load()
    toast('英语训练目标已保存，可以开始第一课了', 'ri-checkbox-circle-fill')
  } catch (error) {
    profileError.value = messageOf(error, '目标保存失败，本机草稿已保留')
  } finally {
    profileBusy.value = false
  }
}

async function saveProgress(complete: boolean) {
  if (!overview.value?.profile || saveState.value === 'saving') return
  if (complete && !canComplete.value) {
    saveError.value = '先完成听说读写四项，并选择今天的开口信心'
    return
  }
  saveState.value = 'saving'
  saveError.value = ''
  try {
    const saved = await saveEnglishLearningSession(selectedDate.value, {
      ...sessionForm,
      lessonId: lesson.value.id,
      practiceMinutes: Number(sessionForm.practiceMinutes) || 0,
      writingText: sessionForm.writingText.trim(),
      reflection: sessionForm.reflection.trim(),
      nextFocus: sessionForm.nextFocus.trim(),
      completed: complete,
    })
    localStorage.removeItem(sessionDraftKey(selectedDate.value))
    saveState.value = 'saved'
    await load(selectedDate.value)
    if (saved.completedAt) toast('今天的听说读写已经闭环，证据已记下', 'ri-fire-fill')
    else toast('训练进度已保存', 'ri-checkbox-circle-line')
  } catch (error) {
    saveError.value = messageOf(error, '保存失败，本机草稿已保留')
    saveState.value = 'error'
  }
}

async function selectHistory(date: string) {
  if (date === selectedDate.value || saveState.value === 'saving') return
  if (saveState.value === 'dirty' && !window.confirm('当前修改还没保存，切换日期后草稿仍会保留。继续吗？')) return
  selectedDate.value = date
  await load(date)
}

function speakLesson(rate: number) {
  if (!('speechSynthesis' in window) || typeof SpeechSynthesisUtterance === 'undefined') {
    toast('当前浏览器不支持英文朗读，请换用最新版 Chrome 或 Edge', 'ri-information-line')
    return
  }
  window.speechSynthesis.cancel()
  const utterance = new SpeechSynthesisUtterance(lesson.value.listenText)
  utterance.lang = 'en-US'
  utterance.rate = rate
  utterance.onstart = () => { speaking.value = true }
  utterance.onend = () => { speaking.value = false }
  utterance.onerror = () => { speaking.value = false; toast('浏览器朗读失败，请重试') }
  window.speechSynthesis.speak(utterance)
}

async function startRecording() {
  if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
    recordState.value = 'error'
    recordError.value = '当前浏览器不支持录音，可直接跟读后勾选完成'
    return
  }
  resetRecording()
  recordState.value = 'requesting'
  try {
    recorderStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    recorderChunks = []
    recorder = new MediaRecorder(recorderStream)
    recorder.ondataavailable = (event) => { if (event.data.size) recorderChunks.push(event.data) }
    recorder.onstop = () => {
      const blob = new Blob(recorderChunks, { type: recorder?.mimeType || 'audio/webm' })
      audioUrl.value = URL.createObjectURL(blob)
      recordState.value = 'ready'
      recorderStream?.getTracks().forEach((track) => track.stop())
      recorderStream = null
    }
    recorder.start()
    recordState.value = 'recording'
  } catch (error) {
    recordState.value = 'error'
    recordError.value = error instanceof DOMException && error.name === 'NotAllowedError'
      ? '没有获得麦克风权限；录音只在本机回放，不会上传'
      : '麦克风启动失败，请检查浏览器设置'
  }
}

function stopRecording() {
  if (recorder?.state === 'recording') recorder.stop()
}

function resetRecording() {
  if (recorder?.state === 'recording') {
    recorder.onstop = null
    recorder.stop()
  }
  recorderStream?.getTracks().forEach((track) => track.stop())
  recorderStream = null
  recorder = null
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  audioUrl.value = ''
  recordState.value = 'idle'
  recordError.value = ''
}

async function addPhrase(term: string) {
  if (capturingTerm.value) return
  capturingTerm.value = term
  try {
    const result = await captureVocabulary(term)
    capturedTerms.value = new Set([...capturedTerms.value, term])
    toast(result.created ? `已加入单词本：${term}` : `已记录再次遇见：${term}`, 'ri-book-marked-line')
  } catch (error) {
    toast(messageOf(error, '加入单词本失败，请重试'), 'ri-error-warning-line')
  } finally {
    capturingTerm.value = ''
  }
}

function sessionDraftKey(date: string) { return `${SESSION_DRAFT_PREFIX}${date}` }
function messageOf(error: unknown, fallback: string) { return error instanceof Error ? error.message : fallback }
function formatDate(date: string) {
  if (date === today) return '今天'
  return new Date(`${date}T12:00:00+08:00`).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}
function historyTitle(id: string) { return lessonById(id)?.title ?? '历史课程' }

onMounted(() => load())
onBeforeUnmount(() => {
  window.speechSynthesis?.cancel()
  resetRecording()
})
</script>

<template>
  <main class="english-page">
    <section v-if="loading" class="state-card"><i class="ri-loader-4-line spin"></i><p>正在读取你的英语训练记录…</p></section>
    <section v-else-if="loadError" class="state-card error-state">
      <i class="ri-wifi-off-line"></i><h2>暂时没有读到训练记录</h2><p>{{ loadError }}</p>
      <button @click="load()">重试</button>
    </section>
    <template v-else-if="overview?.profile">
      <header class="hero">
        <div>
          <span class="eyebrow">ENGLISH PRACTICE · 第 {{ cycleNumber }} 轮</span>
          <h1>{{ isToday ? '今天，开口就算进步' : `${formatDate(selectedDate)} 的训练记录` }}</h1>
          <p>{{ overview.profile.goal }}</p>
          <div class="hero-actions">
            <button class="profile-button" @click="editProfile"><i class="ri-settings-4-line"></i>调整目标</button>
            <RouterLink to="/vocabulary"><i class="ri-book-marked-line"></i>复习单词</RouterLink>
            <RouterLink to="/action-plans"><i class="ri-notification-3-line"></i>设置坚持提醒</RouterLink>
          </div>
        </div>
        <div class="hero-proof">
          <strong>{{ overview.currentStreak }}</strong><span>连续完成天数</span>
          <small>不是签到：听说读写全部完成才累计</small>
        </div>
      </header>

      <section class="stats-grid">
        <article><i class="ri-checkbox-circle-line"></i><div><strong>{{ overview.completedDays }}</strong><span>完成训练</span></div></article>
        <article><i class="ri-time-line"></i><div><strong>{{ overview.totalMinutes }}</strong><span>累计分钟</span></div></article>
        <article><i class="ri-calendar-check-line"></i><div><strong>{{ overview.profile.dailyTargetMinutes }}</strong><span>每日目标</span></div></article>
        <article><i class="ri-line-chart-line"></i><div><strong>{{ profileProgress }}%</strong><span>开始后的完成率</span></div></article>
      </section>

      <div class="workspace">
        <aside class="curriculum-card">
          <div class="aside-head"><span>14 课基础循环</span><small>完成后带着复盘再练一轮</small></div>
          <ol>
            <li v-for="(item, index) in ENGLISH_LESSONS" :key="item.id" :class="{ active: item.id === lesson.id }">
              <span>{{ index + 1 }}</span><div><b>{{ item.title }}</b><small>{{ item.stage }}</small></div>
            </li>
          </ol>
          <div class="truth-note"><i class="ri-shield-check-line"></i><p>课程是固定原创材料；不伪装自动翻译、AI 发音评分或能力测评。</p></div>
        </aside>

        <section class="lesson-column">
          <article class="lesson-head">
            <div><span>{{ lesson.stage }}</span><h2>{{ lesson.title }}</h2><p>{{ lesson.objective }}</p></div>
            <div class="skill-progress"><strong>{{ completedSkills }}/4</strong><small>今日技能</small></div>
          </article>

          <article class="practice-card listening-card">
            <div class="practice-title">
              <label><input v-model="sessionForm.listeningDone" type="checkbox"><span><i class="ri-headphone-line"></i>听 · 先听意思</span></label>
              <small>建议 5 分钟</small>
            </div>
            <p>先不看原文听一遍，再慢速听一遍，最后打开文本核对。</p>
            <div class="listen-actions">
              <button :class="{ playing: speaking }" @click="speakLesson(1)"><i class="ri-volume-up-line"></i>正常语速</button>
              <button @click="speakLesson(0.72)"><i class="ri-speed-mini-line"></i>慢速</button>
              <button class="ghost" @click="transcriptVisible = !transcriptVisible"><i class="ri-file-text-line"></i>{{ transcriptVisible ? '收起原文' : '核对原文' }}</button>
            </div>
            <div v-if="transcriptVisible" class="transcript">{{ lesson.transcript }}</div>
          </article>

          <article class="practice-card speaking-card">
            <div class="practice-title">
              <label><input v-model="sessionForm.speakingDone" type="checkbox"><span><i class="ri-mic-line"></i>说 · 跟读再脱稿</span></label>
              <small>建议 7 分钟</small>
            </div>
            <p>先跟读课程句子，再按提示说自己的真实情况。语法不完美也不要停。</p>
            <ul class="prompt-list"><li v-for="prompt in lesson.speakingPrompts" :key="prompt">{{ prompt }}</li></ul>
            <div class="record-box">
              <button v-if="recordState !== 'recording'" :disabled="recordState === 'requesting'" @click="startRecording">
                <i class="ri-record-circle-line"></i>{{ recordState === 'requesting' ? '正在申请麦克风…' : audioUrl ? '重新录一遍' : '录下这一遍' }}
              </button>
              <button v-else class="recording" @click="stopRecording"><i class="ri-stop-circle-line"></i>停止录音</button>
              <audio v-if="audioUrl" :src="audioUrl" controls></audio>
              <span v-if="recordState === 'recording'" class="record-hint">正在录音，勇敢说完再停</span>
              <span v-else-if="recordError" class="record-error">{{ recordError }}</span>
              <small v-else>录音只在当前浏览器本机回放，不上传、不评分。</small>
            </div>
          </article>

          <article class="practice-card reading-card">
            <div class="practice-title">
              <label><input v-model="sessionForm.readingDone" type="checkbox"><span><i class="ri-article-line"></i>读 · 抓住主旨</span></label>
              <small>建议 5 分钟</small>
            </div>
            <blockquote>{{ lesson.reading }}</blockquote>
            <div class="question"><i class="ri-question-line"></i><span>{{ lesson.readingQuestion }}</span></div>
          </article>

          <article class="practice-card phrases-card">
            <div class="practice-title plain"><span><i class="ri-chat-quote-line"></i>今天要带走的表达</span><small>来自本课固定内容</small></div>
            <div class="phrase-list">
              <div v-for="phrase in lesson.phrases" :key="phrase.term" class="phrase-row">
                <div><b>{{ phrase.term }}</b><span>{{ phrase.meaning }}</span><small>{{ phrase.example }}</small></div>
                <button :disabled="capturingTerm === phrase.term || capturedTerms.has(phrase.term)" @click="addPhrase(phrase.term)">
                  <i :class="capturedTerms.has(phrase.term) ? 'ri-check-line' : 'ri-book-marked-line'"></i>
                  {{ capturedTerms.has(phrase.term) ? '已加入' : capturingTerm === phrase.term ? '加入中…' : '加入单词本' }}
                </button>
              </div>
            </div>
          </article>

          <article class="practice-card writing-card">
            <div class="practice-title">
              <label><input v-model="sessionForm.writingDone" type="checkbox"><span><i class="ri-edit-2-line"></i>写 · 输出自己的内容</span></label>
              <small>建议 8 分钟</small>
            </div>
            <p class="writing-prompt">{{ lesson.writingPrompt }}</p>
            <textarea v-model="sessionForm.writingText" maxlength="4000" rows="7" placeholder="Write it here. 先把意思写出来，再慢慢改。"></textarea>
            <small class="counter">{{ sessionForm.writingText.length }} / 4000</small>
          </article>

          <article class="reflection-card">
            <div class="practice-title plain"><span><i class="ri-seedling-line"></i>留下证据，再定一个小调整</span></div>
            <div class="reflection-grid">
              <label><span>实际练习时长（分钟）</span><input v-model.number="sessionForm.practiceMinutes" type="number" min="0" max="240"></label>
              <fieldset><legend>今天开口时的信心</legend><button v-for="score in 5" :key="score" type="button" :class="{ selected: sessionForm.confidence === score }" @click="sessionForm.confidence = score">{{ score }}</button><small>1 胆怯 · 5 自然</small></fieldset>
            </div>
            <label class="wide-field"><span>今天真实发生了什么？</span><textarea v-model="sessionForm.reflection" maxlength="2000" rows="3" placeholder="例如：跟读了三遍；第二遍不看文本也能说完；会议里主动问了一个问题。"></textarea></label>
            <label class="wide-field"><span>下一次只改进什么？</span><input v-model="sessionForm.nextFocus" maxlength="500" placeholder="例如：回答时先说结论，不在脑中翻译完整句子。"></label>
            <div class="save-row">
              <span class="save-state" :class="saveState"><i :class="saveState === 'saving' ? 'ri-loader-4-line spin' : saveState === 'error' ? 'ri-error-warning-line' : 'ri-cloud-line'"></i>{{ saveLabel }}</span>
              <span v-if="saveError" class="save-error">{{ saveError }}</span>
              <button class="secondary" :disabled="saveState === 'saving'" @click="saveProgress(false)">{{ saveState === 'error' ? '重试保存进度' : '保存进度' }}</button>
              <button class="complete" :disabled="saveState === 'saving' || isCompleted" @click="saveProgress(true)"><i :class="isCompleted ? 'ri-checkbox-circle-fill' : 'ri-flag-2-line'"></i>{{ isCompleted ? '本日已完成' : '完成今日训练' }}</button>
            </div>
          </article>
        </section>

        <aside class="history-card">
          <div class="aside-head"><span>真实学习记录</span><button v-if="!isToday" @click="selectHistory(today)">回到今天</button></div>
          <div v-if="!history.length" class="empty-history"><i class="ri-footprint-line"></i><p>完成第一天后，这里会出现可回看的证据。</p></div>
          <button v-for="item in history" :key="item.id" class="history-row" :class="{ active: item.date === selectedDate }" @click="selectHistory(item.date)">
            <i :class="item.completedAt ? 'ri-checkbox-circle-fill' : 'ri-time-line'"></i>
            <span><b>{{ formatDate(item.date) }} · {{ historyTitle(item.lessonId) }}</b><small>{{ item.practiceMinutes }} 分钟 · 信心 {{ item.confidence ?? '未记' }}</small></span>
          </button>
          <div class="cycle-tip"><b>重复不是重头开始</b><p>第 14 课后课程会进入下一轮；保留历史，把自己的答案说得更长、更自然或不看稿。</p></div>
        </aside>
      </div>
    </template>

    <div v-if="profileOpen" class="modal-backdrop">
      <section class="profile-modal" role="dialog" aria-modal="true" aria-labelledby="english-profile-title">
        <button v-if="overview?.profile" class="modal-close" aria-label="关闭" @click="profileOpen = false"><i class="ri-close-line"></i></button>
        <span class="eyebrow">从这次开始，不再清零</span>
        <h2 id="english-profile-title">先约定一个能坚持的节奏</h2>
        <p>WAO 会保存每天的真实练习和复盘。课程不会替你制造“学会了”的假结果。</p>
        <label><span>我真正想达到的状态</span><textarea v-model="profileForm.goal" maxlength="500" rows="4" @input="profileChanged"></textarea></label>
        <div class="profile-fields">
          <label><span>每天投入</span><select v-model.number="profileForm.dailyTargetMinutes" @change="profileChanged"><option :value="15">15 分钟</option><option :value="25">25 分钟</option><option :value="40">40 分钟</option><option :value="60">60 分钟</option></select></label>
          <label><span>开始日期</span><input v-model="profileForm.startDate" type="date" :max="today" @change="profileChanged"></label>
        </div>
        <div class="plan-preview"><i class="ri-route-line"></i><div><b>每天一个小闭环</b><span>听 5 分钟 · 说 7 分钟 · 读 5 分钟 · 写 8 分钟 · 留下一条改进</span></div></div>
        <p v-if="profileError" class="profile-error">{{ profileError }}</p>
        <button class="profile-submit" :disabled="profileBusy" @click="submitProfile"><i :class="profileBusy ? 'ri-loader-4-line spin' : 'ri-arrow-right-line'"></i>{{ profileBusy ? '正在保存…' : overview?.profile ? '保存目标' : '开始第一课' }}</button>
      </section>
    </div>
  </main>
</template>

<style scoped>
.english-page { max-width: none; margin: 0 auto; padding: 28px 30px 72px; color: var(--wao-text); }
button, textarea, input, select { color: inherit; }
button { cursor: pointer; }
.state-card { min-height: 420px; display: grid; place-content: center; justify-items: center; gap: 12px; text-align: center; color: var(--wao-text-3); }
.state-card i { font-size: 32px; }.state-card h2,.state-card p { margin: 0; }.state-card button { border: 0; border-radius: 8px; padding: 10px 20px; background: var(--wao-primary); color: white; }
.error-state i { color: var(--wao-danger); }
.spin { display: inline-block; animation: spin 1s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
.hero { min-height: 214px; border-radius: 24px; padding: 34px 38px; display: flex; align-items: center; justify-content: space-between; gap: 30px; overflow: hidden; position: relative; color: white; background: linear-gradient(125deg,#1f2d5d 0%,#4358d6 58%,#6d62d9 100%); box-shadow: 0 18px 50px rgba(45,58,140,.18); }
.hero::after { content:''; position:absolute; width:330px; height:330px; border:60px solid rgba(255,255,255,.07); border-radius:50%; right:-90px; top:-150px; }
.eyebrow { display: inline-block; font-size: 11px; font-weight: 800; letter-spacing: .16em; text-transform: uppercase; }
.hero h1 { margin: 9px 0 9px; font-size: clamp(27px,3vw,42px); letter-spacing: -.04em; }.hero p { max-width: 760px; margin:0; line-height:1.75; color:rgba(255,255,255,.8); }
.hero-actions { display:flex; flex-wrap:wrap; gap:9px; margin-top:20px; position:relative; z-index:2; }.hero-actions a,.hero-actions button { display:inline-flex; align-items:center; gap:6px; border:1px solid rgba(255,255,255,.22); border-radius:999px; background:rgba(255,255,255,.1); color:white; padding:8px 13px; font-size:12px; text-decoration:none; }.hero-actions a:hover,.hero-actions button:hover { background:rgba(255,255,255,.18); }
.hero-proof { min-width:210px; min-height:145px; position:relative; z-index:1; border:1px solid rgba(255,255,255,.22); border-radius:20px; background:rgba(14,23,66,.24); backdrop-filter:blur(10px); display:flex; flex-direction:column; align-items:center; justify-content:center; }.hero-proof strong { font-size:52px; line-height:1; }.hero-proof span { font-weight:700; margin-top:8px; }.hero-proof small { font-size:10px; color:rgba(255,255,255,.65); margin-top:8px; }
.stats-grid { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin:16px 0; }.stats-grid article { border:1px solid var(--wao-line); background:var(--wao-bg-card); border-radius:14px; padding:17px 18px; display:flex; align-items:center; gap:13px; }.stats-grid i { width:40px;height:40px;border-radius:11px;background:var(--wao-primary-soft);color:var(--wao-primary-text);display:grid;place-items:center;font-size:20px; }.stats-grid div { display:flex;flex-direction:column; }.stats-grid strong { font-size:22px; }.stats-grid span { color:var(--wao-text-weak);font-size:11px;margin-top:2px; }
.workspace { display:grid; grid-template-columns:220px minmax(0,1fr) 250px; gap:16px; align-items:start; }.curriculum-card,.history-card,.lesson-head,.practice-card,.reflection-card { background:var(--wao-bg-card); border:1px solid var(--wao-line); border-radius:16px; }.curriculum-card,.history-card { position:sticky;top:108px;padding:16px;max-height:calc(100vh - 130px);overflow:auto; }.aside-head { display:flex; flex-direction:column; gap:3px; padding:2px 2px 13px; border-bottom:1px solid var(--wao-divider); }.aside-head>span { font-weight:800;font-size:13px; }.aside-head small { color:var(--wao-text-weak);font-size:10px; }.curriculum-card ol { list-style:none;padding:8px 0;margin:0; }.curriculum-card li { display:flex;gap:10px;align-items:center;padding:8px;border-radius:10px;color:var(--wao-text-3); }.curriculum-card li>span { width:24px;height:24px;border-radius:7px;background:var(--wao-bg-sub-2);display:grid;place-items:center;font-size:10px;font-weight:800;flex:none; }.curriculum-card li div { min-width:0;display:flex;flex-direction:column;gap:2px; }.curriculum-card li b { font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis; }.curriculum-card li small { font-size:9px;color:var(--wao-text-weak); }.curriculum-card li.active { background:var(--wao-primary-soft);color:var(--wao-primary-text); }.curriculum-card li.active>span { background:var(--wao-primary);color:white; }.truth-note { display:flex;gap:8px;background:var(--wao-bg-sub);padding:10px;border-radius:10px;color:var(--wao-text-weak); }.truth-note i { color:var(--wao-success); }.truth-note p { margin:0;font-size:9px;line-height:1.55; }
.lesson-column { display:flex;flex-direction:column;gap:13px;min-width:0; }.lesson-head { padding:24px 26px;display:flex;justify-content:space-between;align-items:center;gap:20px; }.lesson-head span { color:var(--wao-primary-text);font-size:11px;font-weight:800; }.lesson-head h2 { font-size:25px;margin:5px 0 7px; }.lesson-head p { color:var(--wao-text-3);margin:0;line-height:1.65;font-size:13px; }.skill-progress { width:70px;height:70px;border-radius:50%;background:var(--wao-primary-soft);display:flex;flex-direction:column;align-items:center;justify-content:center;flex:none; }.skill-progress strong { color:var(--wao-primary-text);font-size:19px; }.skill-progress small { color:var(--wao-text-weak);font-size:9px; }
.practice-card,.reflection-card { padding:21px 23px; }.practice-title { display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:13px; }.practice-title label,.practice-title>span { display:flex;align-items:center;gap:9px;font-size:15px;font-weight:800; }.practice-title label span { display:flex;align-items:center;gap:7px; }.practice-title input { width:18px;height:18px;accent-color:var(--wao-primary); }.practice-title i { color:var(--wao-primary-text);font-size:18px; }.practice-title>small { color:var(--wao-text-weak);font-size:10px;background:var(--wao-bg-sub);border-radius:999px;padding:5px 9px; }.practice-card>p { color:var(--wao-text-3);font-size:12px;line-height:1.7;margin:0 0 14px; }
.listen-actions { display:flex;gap:8px;flex-wrap:wrap; }.listen-actions button,.record-box>button { border:0;border-radius:9px;padding:9px 12px;background:var(--wao-primary);color:white;display:inline-flex;gap:6px;align-items:center;font-size:12px; }.listen-actions .ghost { color:var(--wao-text-3);background:var(--wao-bg-sub-2);border:1px solid var(--wao-line); }.listen-actions .playing { box-shadow:0 0 0 4px var(--wao-primary-soft); }.transcript { margin-top:13px;background:var(--wao-bg-sub);border-left:3px solid var(--wao-primary);padding:13px 15px;border-radius:4px 10px 10px 4px;font-family:Georgia,serif;line-height:1.8;color:var(--wao-text-2); }
.prompt-list { margin:0 0 14px;padding:0;display:grid;gap:7px;list-style:none; }.prompt-list li { background:var(--wao-bg-sub);border:1px solid var(--wao-divider);border-radius:9px;padding:9px 12px;font-size:12px;color:var(--wao-text-2); }.prompt-list li::before { content:'→';color:var(--wao-primary-text);font-weight:900;margin-right:8px; }.record-box { display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:12px;border:1px dashed var(--wao-line-strong);border-radius:11px; }.record-box .recording { background:var(--wao-danger); }.record-box audio { height:34px;max-width:100%; }.record-box small,.record-hint { color:var(--wao-text-weak);font-size:10px; }.record-hint { color:var(--wao-danger); }.record-error { color:var(--wao-danger);font-size:11px; }
.reading-card blockquote { margin:0;background:linear-gradient(135deg,var(--wao-primary-tint),var(--wao-bg-sub));border-radius:12px;padding:18px 20px;font:16px/1.8 Georgia,serif;color:var(--wao-text-2); }.question { display:flex;gap:8px;margin-top:12px;color:var(--wao-text-3);font-size:11px; }.question i { color:var(--wao-warning-strong); }
.phrase-list { display:grid;gap:8px; }.phrase-row { display:flex;align-items:center;justify-content:space-between;gap:15px;padding:12px 13px;background:var(--wao-bg-sub);border-radius:10px; }.phrase-row>div { display:flex;flex-direction:column;gap:3px;min-width:0; }.phrase-row b { color:var(--wao-primary-text);font-size:13px; }.phrase-row span { font-size:11px;color:var(--wao-text-2); }.phrase-row small { font:11px/1.5 Georgia,serif;color:var(--wao-text-weak); }.phrase-row button { flex:none;border:1px solid var(--wao-primary-line);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-primary-text);padding:7px 9px;font-size:10px; }.phrase-row button:disabled { color:var(--wao-success); }
.writing-prompt { background:var(--wao-warning-bg);color:var(--wao-warning-strong)!important;border-radius:10px;padding:11px 13px; }.writing-card textarea,.wide-field textarea,.wide-field input,.profile-modal textarea,.profile-modal input,.profile-modal select,.reflection-grid input { width:100%;border:1px solid var(--wao-line-control);background:var(--wao-bg-sub);border-radius:10px;padding:12px 13px;resize:vertical;line-height:1.65; }.counter { display:block;text-align:right;color:var(--wao-text-weak);font-size:9px;margin-top:5px; }
.reflection-card { border-color:var(--wao-primary-line); }.reflection-grid { display:grid;grid-template-columns:180px 1fr;gap:16px;margin-bottom:15px; }.reflection-grid label,.wide-field { display:flex;flex-direction:column;gap:7px;font-size:11px;font-weight:700; }.reflection-grid input { padding:10px; }.reflection-grid fieldset { margin:0;border:0;padding:0;display:flex;align-items:center;gap:6px;flex-wrap:wrap; }.reflection-grid legend { width:100%;font-size:11px;font-weight:700;margin-bottom:7px; }.reflection-grid fieldset button { width:34px;height:34px;border:1px solid var(--wao-line-control);border-radius:9px;background:var(--wao-bg-sub); }.reflection-grid fieldset button.selected { color:white;background:var(--wao-primary);border-color:var(--wao-primary); }.reflection-grid fieldset small { color:var(--wao-text-weak);font-size:9px;margin-left:4px; }.wide-field { margin-top:12px; }.save-row { display:flex;align-items:center;gap:10px;flex-wrap:wrap;border-top:1px solid var(--wao-divider);padding-top:16px;margin-top:17px; }.save-state { display:flex;align-items:center;gap:5px;color:var(--wao-text-weak);font-size:10px;margin-right:auto; }.save-state.error,.save-error { color:var(--wao-danger); }.save-error { width:100%;font-size:10px;order:-1; }.save-row button { border-radius:9px;padding:10px 14px;font-weight:700;font-size:12px; }.save-row .secondary { border:1px solid var(--wao-line-control);background:var(--wao-bg-card); }.save-row .complete { border:0;background:var(--wao-primary);color:white; }.save-row .complete i { margin-right:5px; }
.history-card .aside-head { flex-direction:row;align-items:center;justify-content:space-between; }.aside-head button { border:0;background:transparent;color:var(--wao-primary-text);font-size:10px; }.history-row { width:100%;border:0;background:transparent;display:flex;text-align:left;gap:8px;padding:10px 7px;border-bottom:1px solid var(--wao-divider);border-radius:8px; }.history-row:hover,.history-row.active { background:var(--wao-bg-hover); }.history-row>i { color:var(--wao-success);margin-top:1px; }.history-row>span { display:flex;flex-direction:column;gap:4px;min-width:0; }.history-row b { font-size:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis; }.history-row small { color:var(--wao-text-weak);font-size:9px; }.empty-history { padding:25px 8px;text-align:center;color:var(--wao-text-weak);font-size:10px; }.empty-history i { font-size:25px; }.cycle-tip { margin-top:14px;padding:12px;background:var(--wao-primary-soft);border-radius:10px; }.cycle-tip b { color:var(--wao-primary-text);font-size:11px; }.cycle-tip p { margin:5px 0 0;color:var(--wao-text-3);font-size:9px;line-height:1.55; }
.modal-backdrop { position:fixed;inset:0;z-index:1000;background:rgba(13,17,35,.58);backdrop-filter:blur(4px);display:grid;place-items:center;padding:18px; }.profile-modal { width:min(560px,100%);max-height:calc(100vh - 36px);overflow:auto;position:relative;background:var(--wao-bg-card);border:1px solid var(--wao-line);border-radius:20px;padding:30px;box-shadow:var(--wao-shadow-modal); }.modal-close { position:absolute;right:16px;top:16px;width:34px;height:34px;border:0;border-radius:50%;background:var(--wao-bg-sub-2); }.profile-modal .eyebrow { color:var(--wao-primary-text); }.profile-modal h2 { margin:8px 0;font-size:25px; }.profile-modal>p { color:var(--wao-text-3);font-size:12px;line-height:1.65; }.profile-modal label { display:flex;flex-direction:column;gap:7px;font-size:11px;font-weight:700;margin-top:15px; }.profile-fields { display:grid;grid-template-columns:1fr 1fr;gap:12px; }.profile-modal textarea { resize:vertical; }.plan-preview { display:flex;gap:11px;background:var(--wao-primary-soft);border-radius:12px;padding:13px;margin-top:17px; }.plan-preview i { color:var(--wao-primary-text);font-size:20px; }.plan-preview div { display:flex;flex-direction:column;gap:3px; }.plan-preview b { font-size:12px; }.plan-preview span { color:var(--wao-text-3);font-size:10px; }.profile-error { color:var(--wao-danger)!important;margin-bottom:0; }.profile-submit { width:100%;border:0;border-radius:10px;background:var(--wao-primary);color:white;padding:12px;margin-top:18px;font-weight:800; }.profile-submit i { margin-right:6px; }
@media (max-width:1180px) { .workspace { grid-template-columns:190px minmax(0,1fr); }.history-card { position:static;grid-column:1/-1;max-height:none; }.history-card .aside-head { margin-bottom:5px; }.history-row { width:calc(50% - 4px);display:inline-flex;vertical-align:top; }.cycle-tip { clear:both; } }
@media (max-width:820px) { .english-page { padding:18px 14px 65px; }.hero { padding:27px 23px;align-items:flex-start; }.hero-proof { min-width:150px; }.stats-grid { grid-template-columns:1fr 1fr; }.workspace { grid-template-columns:1fr; }.curriculum-card { position:static;max-height:none; }.curriculum-card ol { display:flex;overflow-x:auto;gap:5px;padding-bottom:8px; }.curriculum-card li { min-width:154px; }.truth-note { margin-top:4px; }.history-card { grid-column:auto; }.lesson-column { grid-row:1; }.curriculum-card { grid-row:2; }.history-card { grid-row:3; } }
@media (max-width:560px) { .english-page { padding:10px 9px 58px; }.hero { border-radius:17px;min-height:0;display:block;padding:23px 18px; }.hero h1 { font-size:26px; }.hero p { font-size:12px; }.hero-proof { margin-top:20px;min-height:95px;width:100%; }.hero-proof strong { font-size:37px; }.stats-grid { gap:7px;margin:9px 0; }.stats-grid article { padding:12px 10px; }.stats-grid i { width:34px;height:34px; }.stats-grid strong { font-size:18px; }.workspace { gap:9px; }.lesson-head,.practice-card,.reflection-card { border-radius:13px;padding:17px 14px; }.lesson-head h2 { font-size:21px; }.skill-progress { width:58px;height:58px; }.phrase-row { align-items:flex-start;flex-direction:column; }.phrase-row button { width:100%;min-height:42px; }.reflection-grid { grid-template-columns:1fr; }.save-row button { width:100%;min-height:44px; }.save-state { width:100%; }.history-row { width:100%; }.profile-modal { padding:25px 18px; }.profile-fields { grid-template-columns:1fr; }.listen-actions button,.record-box>button { min-height:42px; }.practice-title { align-items:flex-start; }.practice-title>small { flex:none; }.record-box audio { width:100%; }.hero-actions a,.hero-actions button { min-height:40px; } }
</style>
