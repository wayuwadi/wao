<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  fetchMemoryMatchGame,
  flipMemoryMatch,
  restartMemoryMatch,
  type MemoryMatchGame,
} from '@/api/games'

const icons = [
  'ri-home-5-line', 'ri-stack-line', 'ri-calendar-2-line', 'ri-checkbox-multiple-line',
  'ri-book-2-line', 'ri-key-2-line', 'ri-flashlight-line', 'ri-hard-drive-2-line',
]

const game = ref<MemoryMatchGame | null>(null)
const elapsed = ref(0)
const loading = ref(true)
const saving = ref(false)
const locked = ref(false)
const newRecord = ref(false)
const error = ref('')
let startedAt: number | null = null
let coverTimer: ReturnType<typeof setTimeout> | null = null
let clockTimer: ReturnType<typeof setInterval> | null = null

const pairs = computed(() => (game.value?.matched.length ?? 0) / 2)
const timeText = computed(() => `${String(Math.floor(elapsed.value / 60)).padStart(2, '0')}:${String(elapsed.value % 60).padStart(2, '0')}`)
const syncText = computed(() => loading.value ? '正在读取账号牌局' : saving.value ? '正在保存牌局' : error.value ? '同步失败' : '已同步到账号')

function isOpen(index: number) {
  return game.value?.revealed.includes(index) ?? false
}

function isMatched(index: number) {
  return game.value?.matched.includes(index) ?? false
}

function iconClass(index: number) {
  const icon = game.value?.cards[index]
  return icon === null || icon === undefined ? '' : icons[icon]
}

function startClock() {
  if (startedAt !== null) return
  startedAt = Date.now()
  clockTimer = setInterval(() => {
    elapsed.value = Math.floor((Date.now() - (startedAt ?? Date.now())) / 1000)
  }, 1000)
}

function stopTimers() {
  if (coverTimer) clearTimeout(coverTimer)
  if (clockTimer) clearInterval(clockTimer)
  coverTimer = null
  clockTimer = null
}

function resetLocalClock() {
  if (clockTimer) clearInterval(clockTimer)
  clockTimer = null
  startedAt = null
  elapsed.value = 0
}

async function loadGame() {
  loading.value = true
  error.value = ''
  try {
    game.value = await fetchMemoryMatchGame()
    if (game.value.finished && clockTimer) clearInterval(clockTimer)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '账号牌局读取失败'
  } finally {
    loading.value = false
  }
}

async function reveal(index: number) {
  if (!game.value || locked.value || saving.value || game.value.finished || isOpen(index)) return
  startClock()
  saving.value = true
  error.value = ''
  const previousBest = game.value.bestSteps
  const previousPending = game.value.revealed.length - game.value.matched.length
  try {
    const snapshot = await flipMemoryMatch(index, game.value.revision)
    game.value = snapshot
    if (snapshot.finished) {
      if (clockTimer) clearInterval(clockTimer)
      clockTimer = null
      newRecord.value = previousBest === null || (snapshot.bestSteps ?? Number.MAX_SAFE_INTEGER) < previousBest
    } else if (previousPending === 1 && snapshot.revealed.length > snapshot.matched.length) {
      locked.value = true
      coverTimer = setTimeout(() => {
        if (game.value) {
          const matched = new Set(game.value.matched)
          game.value = {
            ...game.value,
            cards: game.value.cards.map((icon, cardIndex) => matched.has(cardIndex) ? icon : null),
            revealed: [...game.value.matched],
          }
        }
        locked.value = false
        coverTimer = null
      }, 800)
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '牌局保存失败'
    await loadLatestAfterFailure()
  } finally {
    saving.value = false
  }
}

async function loadLatestAfterFailure() {
  try {
    game.value = await fetchMemoryMatchGame()
  } catch {
    /* 保留首次失败原因，由玩家显式重试。 */
  }
}

async function newGame() {
  if (!game.value || saving.value) return
  stopTimers()
  saving.value = true
  error.value = ''
  try {
    game.value = await restartMemoryMatch(game.value.revision)
    resetLocalClock()
    locked.value = false
    newRecord.value = false
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '重新洗牌失败'
    await loadLatestAfterFailure()
  } finally {
    saving.value = false
  }
}

onMounted(loadGame)
onUnmounted(stopTimers)
</script>

<template>
  <section class="memory-shell">
    <header class="memory-hero">
      <div>
        <div class="eyebrow"><i class="ri-brain-line"></i> 游戏厅 · 第 2 个挑战</div>
        <h1>记忆翻牌</h1>
        <p>找出八对 WAO 模块图标。每翻两张记为一步，当前牌局和最佳成绩都会跟随你的账号。</p>
      </div>
      <div class="sync-pill" :class="{ loading, saving, error: !!error }"><span class="sync-dot"></span>{{ syncText }}</div>
    </header>

    <div v-if="loading" class="memory-loading"><span class="spinner"></span>正在恢复账号牌局</div>
    <div v-else-if="!game" class="memory-loading error-card">
      <i class="ri-cloud-off-line"></i><strong>暂时没拿到账号牌局</strong><span>{{ error }}</span>
      <button type="button" @click="loadGame">重新读取</button>
    </div>
    <div v-else class="memory-panel">
      <div class="memory-toolbar">
        <div><span>已配对</span><strong>{{ pairs }} / 8</strong></div>
        <div><span>步数</span><strong>{{ game.steps }}</strong></div>
        <div><span>本次用时</span><strong>{{ timeText }}</strong></div>
        <div><span>账号最佳</span><strong>{{ game.bestSteps === null ? '—' : `${game.bestSteps} 步` }}</strong></div>
        <button type="button" :disabled="saving" @click="newGame"><i class="ri-refresh-line"></i> 重新洗牌</button>
      </div>

      <div v-if="error" class="memory-error" role="alert">
        <i class="ri-error-warning-line"></i><span>{{ error }}</span><button type="button" @click="loadGame">重试同步</button>
      </div>
      <div class="memory-board" :class="{ locked }" aria-label="记忆翻牌棋盘">
        <button v-for="(_icon, index) in game.cards" :key="index" class="memory-card"
          :class="{ open: isOpen(index), matched: isMatched(index) }" type="button"
          :aria-label="isOpen(index) ? `已翻开 ${iconClass(index)}` : '未翻开的卡片'"
          :disabled="locked || saving || isMatched(index)" @click="reveal(index)">
          <span class="card-inner">
            <span class="card-face card-back"><i class="ri-question-line"></i></span>
            <span class="card-face card-front"><i :class="iconClass(index)"></i></span>
          </span>
        </button>
      </div>

      <div v-if="game.finished" class="memory-result" role="status">
        <span><i class="ri-sparkling-2-fill"></i></span>
        <div><strong>八个模块全部归位</strong><p>用了 {{ game.steps }} 步、{{ timeText }}，{{ newRecord ? '刷新了账号最佳记录。' : '牌局和成绩均已保存。' }}</p></div>
        <button type="button" :disabled="saving" @click="newGame">再玩一次</button>
      </div>
      <p class="memory-tip"><i class="ri-lightbulb-line"></i> 配对不同会在 0.8 秒后盖回去；刷新或换设备都能从服务端恢复当前牌局。</p>
    </div>
  </section>
</template>

<style scoped>
.memory-shell { max-width: 1180px; margin: 0 auto; }.memory-hero { margin-bottom: 20px; display: flex; justify-content: space-between; gap: 24px; }.memory-hero h1 { margin: 0; font-size: clamp(28px,4vw,42px); letter-spacing: -.04em; }.memory-hero p { max-width: 680px; margin: 10px 0 0; color: var(--wao-text-3); font-size: 13px; line-height: 1.7; }.eyebrow { margin-bottom: 7px; display: flex; gap: 7px; color: var(--wao-primary-text); font-size: 12px; font-weight: 650; }.sync-pill { height: 30px; padding: 0 11px; display: inline-flex; align-items: center; gap: 7px; border: 1px solid var(--wao-success-line); border-radius: 999px; background: var(--wao-success-bg); color: var(--wao-success-strong); font-size: 11px; white-space: nowrap; }.sync-pill.loading,.sync-pill.saving { border-color: var(--wao-primary-line); background: var(--wao-primary-soft); color: var(--wao-primary-text); }.sync-pill.error { border-color: var(--wao-danger-line); background: var(--wao-danger-bg); color: var(--wao-danger-strong); }.sync-dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }.memory-loading { min-height: 360px; display: grid; place-items: center; align-content: center; gap: 10px; border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); color: var(--wao-text-3); }.memory-loading button { padding: 9px 15px; border: 0; border-radius: 8px; background: var(--wao-primary); color: white; cursor: pointer; }.memory-panel { width: min(760px,100%); margin: 0 auto; padding: clamp(14px,3vw,22px); background: var(--wao-bg-card); border: 1px solid var(--wao-line); border-radius: 17px; box-shadow: var(--wao-shadow-card-hover-2); }.memory-toolbar { display: grid; grid-template-columns: repeat(4,1fr) auto; gap: 8px; margin-bottom: 14px; }.memory-toolbar>div { padding: 9px 11px; display: grid; gap: 3px; background: var(--wao-bg-sub); border: 1px solid var(--wao-divider); border-radius: 9px; }.memory-toolbar span { color: var(--wao-text-weak); font-size: 10px; }.memory-toolbar strong { font-size: 14px; }.memory-toolbar>button,.memory-result button { padding: 0 12px; border: 0; border-radius: 9px; color: var(--wao-primary-text); background: var(--wao-primary-soft); cursor: pointer; font-weight: 650; }.memory-toolbar>button:disabled,.memory-result button:disabled{cursor:wait;opacity:.65}.memory-error { margin-bottom: 12px; padding: 9px 11px; display: flex; gap: 8px; color: var(--wao-danger-strong); background: var(--wao-danger-bg); border: 1px solid var(--wao-danger-line); border-radius: 9px; font-size: 11px; }.memory-error span { flex: 1; }.memory-error button { border: 0; background: transparent; color: inherit; cursor: pointer; }.memory-board { display: grid; grid-template-columns: repeat(4,1fr); gap: clamp(8px,2vw,12px); perspective: 900px; }.memory-card { aspect-ratio: 1; padding: 0; background: transparent; border: 0; cursor: pointer; perspective: 700px; }.memory-card:disabled { opacity: 1; cursor: default; }.card-inner { position: relative; width: 100%; height: 100%; display: block; transform-style: preserve-3d; transition: transform .42s ease; }.memory-card.open .card-inner { transform: rotateY(180deg); }.card-face { position: absolute; inset: 0; display: grid; place-items: center; border: 1px solid var(--wao-line-control); border-radius: 13px; backface-visibility: hidden; }.card-back { color: var(--wao-primary-text); background: var(--wao-primary-soft); font-size: clamp(20px,4vw,30px); }.card-front { color: var(--wao-primary-text); background: var(--wao-bg-sub); border-color: var(--wao-primary-line); font-size: clamp(24px,5vw,38px); transform: rotateY(180deg); }.memory-card.matched .card-front { color: var(--wao-success-strong); background: var(--wao-success-bg); border-color: var(--wao-success-line); }.memory-result { margin-top: 13px; padding: 12px; display: flex; align-items: center; gap: 10px; color: var(--wao-success-strong); background: var(--wao-success-bg); border: 1px solid var(--wao-success-line); border-radius: 11px; }.memory-result>span { width: 37px; height: 37px; display: grid; place-items: center; font-size: 19px; }.memory-result div { flex: 1; }.memory-result strong { font-size: 12px; }.memory-result p { margin: 3px 0 0; font-size: 10px; }.memory-result button { min-height: 34px; }.memory-tip { margin: 14px 0 0; display: flex; justify-content: center; gap: 6px; color: var(--wao-text-weak); font-size: 10px; text-align: center; }
@media(max-width:650px){.memory-hero{display:grid;gap:12px}.sync-pill{justify-self:start}.memory-panel{padding:11px}.memory-toolbar{grid-template-columns:repeat(2,1fr)}.memory-toolbar>button{grid-column:1/-1;min-height:34px}.memory-board{gap:8px}.card-face{border-radius:9px}.memory-result{align-items:flex-start;flex-wrap:wrap}.memory-result button{width:100%}}
@media(prefers-reduced-motion:reduce){.card-inner{transition:none}}
</style>
