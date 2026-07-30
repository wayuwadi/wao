<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  fetchNumberMergeGame,
  moveNumberMergeGame,
  restartNumberMergeGame,
  undoNumberMergeGame,
  type MoveDirection,
  type NumberMergeGame,
} from '@/api/games'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const game = ref<NumberMergeGame | null>(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const syncState = ref<'loading' | 'saving' | 'saved' | 'error'>('loading')
const moveQueue: MoveDirection[] = []
let draining = false
let pointerX = 0
let pointerY = 0

const number = new Intl.NumberFormat('zh-CN')
const currentHighest = computed(() => Math.max(...(game.value?.board ?? [0])))
const syncText = computed(() => ({
  loading: '正在读取云存档',
  saving: '正在保存',
  saved: '已保存到账号',
  error: '同步失败',
})[syncState.value])

async function loadGame(showLoading = true) {
  if (showLoading) loading.value = true
  error.value = ''
  syncState.value = 'loading'
  try {
    game.value = await fetchNumberMergeGame()
    syncState.value = 'saved'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '游戏存档读取失败'
    syncState.value = 'error'
  } finally {
    loading.value = false
  }
}

function applySnapshot(snapshot: NumberMergeGame) {
  const reachedTarget = game.value && !game.value.won && snapshot.won
  game.value = snapshot
  syncState.value = 'saved'
  if (reachedTarget) ui.celebrate()
}

function enqueueMove(direction: MoveDirection) {
  if (!game.value || loading.value || game.value.gameOver || moveQueue.length >= 8) return
  moveQueue.push(direction)
  void drainMoves()
}

async function drainMoves() {
  if (draining) return
  draining = true
  busy.value = true
  error.value = ''
  try {
    while (moveQueue.length && game.value && !game.value.gameOver) {
      const direction = moveQueue.shift()
      if (!direction) break
      syncState.value = 'saving'
      applySnapshot(await moveNumberMergeGame(direction, game.value.revision))
    }
  } catch (cause) {
    moveQueue.splice(0)
    error.value = cause instanceof Error ? cause.message : '这一步没有保存成功'
    syncState.value = 'error'
    // 冲突或网络重试后必须重新取服务端快照，不能用旧棋盘继续覆盖。
    try {
      game.value = await fetchNumberMergeGame()
    } catch {
      /* 保留原错误，交给玩家显式重试。 */
    }
  } finally {
    draining = false
    busy.value = false
    if (!error.value) syncState.value = 'saved'
  }
}

async function undo() {
  if (!game.value?.canUndo || busy.value) return
  busy.value = true
  syncState.value = 'saving'
  error.value = ''
  try {
    applySnapshot(await undoNumberMergeGame(game.value.revision))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '撤销失败'
    syncState.value = 'error'
  } finally {
    busy.value = false
  }
}

async function restart() {
  if (!game.value || busy.value) return
  if (game.value.moves > 0 && !window.confirm('重新开一局吗？当前成绩会进入历史战绩。')) return
  moveQueue.splice(0)
  busy.value = true
  syncState.value = 'saving'
  error.value = ''
  try {
    applySnapshot(await restartNumberMergeGame(game.value.revision))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '新游戏创建失败'
    syncState.value = 'error'
  } finally {
    busy.value = false
  }
}

function onKeydown(event: KeyboardEvent) {
  const target = event.target
  if (target instanceof HTMLElement && target.matches('input, textarea, select, [contenteditable="true"]')) return
  const direction = ({
    ArrowUp: 'UP', w: 'UP', W: 'UP',
    ArrowDown: 'DOWN', s: 'DOWN', S: 'DOWN',
    ArrowLeft: 'LEFT', a: 'LEFT', A: 'LEFT',
    ArrowRight: 'RIGHT', d: 'RIGHT', D: 'RIGHT',
  } as Record<string, MoveDirection>)[event.key]
  if (!direction) return
  event.preventDefault()
  enqueueMove(direction)
}

function pointerDown(event: PointerEvent) {
  pointerX = event.clientX
  pointerY = event.clientY
}

function pointerUp(event: PointerEvent) {
  const dx = event.clientX - pointerX
  const dy = event.clientY - pointerY
  if (Math.max(Math.abs(dx), Math.abs(dy)) < 28) return
  enqueueMove(Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'RIGHT' : 'LEFT') : (dy > 0 ? 'DOWN' : 'UP'))
}

function tileClass(value: number) {
  if (!value) return 'tile-empty'
  return `tile-${Math.min(Math.round(Math.log2(value)), 11)}`
}

function outcomeLabel(outcome: string) {
  return outcome === 'WON' ? '达成 2048' : outcome === 'GAME_OVER' ? '棋盘已满' : '主动重开'
}

function shortTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
  })
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  void loadGame()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="game-page">
    <header class="game-hero">
      <div>
        <div class="eyebrow"><i class="ri-gamepad-line"></i> 游戏厅 · 第 1 个挑战</div>
        <h1>数字熔炉</h1>
        <p>把相同数字推到一起，持续合成到 2048。每一步都写进你的账号存档，换设备也能接着玩。</p>
      </div>
      <div class="sync-pill" :class="syncState">
        <span class="sync-dot"></span>{{ syncText }}
      </div>
    </header>

    <div v-if="loading" class="loading-card">
      <span class="spinner"></span>
      <strong>正在恢复上次棋盘</strong>
      <small>第一次进入会自动开一局。</small>
    </div>

    <div v-else-if="!game" class="loading-card error-card">
      <i class="ri-cloud-off-line"></i>
      <strong>暂时没拿到游戏存档</strong>
      <small>{{ error }}</small>
      <button type="button" @click="loadGame()">重新读取</button>
    </div>

    <template v-else>
      <div class="stats-strip">
        <div><span>本局分数</span><strong>{{ number.format(game.score) }}</strong></div>
        <div><span>历史最高</span><strong>{{ number.format(game.stats.bestScore) }}</strong></div>
        <div><span>最高数字</span><strong>{{ number.format(game.stats.highestTile) }}</strong></div>
        <div><span>累计步数</span><strong>{{ number.format(game.stats.totalMoves) }}</strong></div>
        <div><span>挑战局数</span><strong>{{ number.format(game.stats.gamesPlayed) }}</strong></div>
      </div>

      <div v-if="error" class="inline-error" role="alert">
        <i class="ri-error-warning-line"></i><span>{{ error }}</span>
        <button type="button" @click="loadGame(false)">同步最新存档</button>
      </div>

      <div class="game-layout">
        <main class="play-card">
          <div class="play-toolbar">
            <div>
              <strong>当前棋盘</strong>
              <span>第 {{ game.moves }} 步 · 当前最大 {{ currentHighest }}</span>
            </div>
            <div class="toolbar-actions">
              <button type="button" :disabled="!game.canUndo || busy" @click="undo">
                <i class="ri-arrow-go-back-line"></i> 撤销一步
              </button>
              <button type="button" class="primary" :disabled="busy" @click="restart">
                <i class="ri-refresh-line"></i> 新游戏
              </button>
            </div>
          </div>

          <div
            class="number-board"
            :class="{ busy }"
            aria-label="数字熔炉棋盘"
            @pointerdown="pointerDown"
            @pointerup="pointerUp"
          >
            <div
              v-for="(value, index) in game.board"
              :key="`${game.revision}-${index}-${value}`"
              class="number-tile"
              :class="tileClass(value)"
            >
              <span v-if="value">{{ value }}</span>
            </div>

            <div v-if="game.gameOver" class="board-overlay">
              <i class="ri-flag-2-fill"></i>
              <strong>这一炉到头了</strong>
              <span>本局 {{ number.format(game.score) }} 分，最高合成 {{ currentHighest }}</span>
              <button type="button" @click="restart">记录成绩，再来一局</button>
            </div>
          </div>

          <div v-if="game.won && !game.gameOver" class="target-banner">
            <i class="ri-trophy-fill"></i>
            <span><strong>已经炼出 2048！</strong> 你可以继续往 4096、8192 挑战。</span>
          </div>

          <div class="direction-pad" aria-label="移动方向">
            <button type="button" aria-label="向上" @click="enqueueMove('UP')"><i class="ri-arrow-up-line"></i></button>
            <button type="button" aria-label="向左" @click="enqueueMove('LEFT')"><i class="ri-arrow-left-line"></i></button>
            <button type="button" aria-label="向下" @click="enqueueMove('DOWN')"><i class="ri-arrow-down-line"></i></button>
            <button type="button" aria-label="向右" @click="enqueueMove('RIGHT')"><i class="ri-arrow-right-line"></i></button>
          </div>

          <div class="how-to-play">
            <i class="ri-keyboard-line"></i>
            <span>电脑用方向键或 WASD；手机直接在棋盘上滑动。相同数字碰到时会合并，每次有效移动后出现一个新数字。</span>
          </div>
        </main>

        <aside class="record-card">
          <div class="record-head">
            <div><span>长期记录</span><strong>最近战绩</strong></div>
            <span class="win-count"><i class="ri-trophy-line"></i>{{ game.stats.wins }} 次达成</span>
          </div>

          <div v-if="game.recentRuns.length" class="run-list">
            <div v-for="run in game.recentRuns" :key="run.id" class="run-row">
              <div class="run-result" :class="run.outcome.toLowerCase()">
                <i :class="run.outcome === 'WON' ? 'ri-trophy-fill' : run.outcome === 'GAME_OVER' ? 'ri-flag-2-fill' : 'ri-refresh-line'"></i>
              </div>
              <div class="run-main">
                <strong>{{ number.format(run.score) }} 分</strong>
                <span>{{ outcomeLabel(run.outcome) }} · {{ run.moves }} 步</span>
              </div>
              <div class="run-side">
                <strong>{{ run.highestTile }}</strong>
                <span>{{ shortTime(run.endedAt) }}</span>
              </div>
            </div>
          </div>
          <div v-else class="empty-records">
            <i class="ri-seedling-line"></i>
            <strong>第一局正在生长</strong>
            <span>主动重开或棋盘结束后，成绩会留在这里。</span>
          </div>

          <div class="next-game">
            <span class="next-icon"><i class="ri-lock-2-line"></i></span>
            <div><strong>下一台游戏机</strong><span>以后可以放每日猜词、数独或记忆挑战。</span></div>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.game-page { min-height: 100%; padding: 28px clamp(18px, 3vw, 42px) 44px; color: var(--wao-text); background: radial-gradient(circle at 8% 0%, rgba(91, 111, 232, .11), transparent 29%), var(--wao-bg-page); }
.game-hero { max-width: 1180px; margin: 0 auto 20px; display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; }
.eyebrow { margin-bottom: 7px; display: flex; align-items: center; gap: 7px; color: var(--wao-primary-text); font-size: 12px; font-weight: 650; letter-spacing: .03em; }
.game-hero h1 { margin: 0; font-size: clamp(28px, 4vw, 42px); line-height: 1.12; letter-spacing: -.04em; }
.game-hero p { max-width: 680px; margin: 10px 0 0; color: var(--wao-text-3); font-size: 13px; line-height: 1.7; }
.sync-pill { height: 30px; padding: 0 11px; display: inline-flex; align-items: center; gap: 7px; border: 1px solid var(--wao-success-line); border-radius: 999px; background: var(--wao-success-bg); color: var(--wao-success-strong); font-size: 11px; white-space: nowrap; }
.sync-pill.saving, .sync-pill.loading { border-color: var(--wao-primary-line); background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.sync-pill.error { border-color: var(--wao-danger-line); background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
.sync-dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.sync-pill.saving .sync-dot, .sync-pill.loading .sync-dot { animation: pulse 1s ease infinite; }
.loading-card { min-height: 420px; max-width: 1180px; margin: 0 auto; display: grid; place-items: center; align-content: center; gap: 10px; border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); color: var(--wao-text-3); }
.loading-card strong { color: var(--wao-text); font-size: 15px; }.loading-card small { color: var(--wao-text-weak); }
.loading-card > i { font-size: 38px; color: var(--wao-danger); }.loading-card button { margin-top: 8px; padding: 9px 15px; border: 0; border-radius: 8px; background: var(--wao-primary); color: white; cursor: pointer; }
.spinner { width: 28px; height: 28px; border: 3px solid var(--wao-primary-line); border-top-color: var(--wao-primary); border-radius: 50%; animation: spin .8s linear infinite; }
.stats-strip { max-width: 1180px; margin: 0 auto 14px; display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; }
.stats-strip > div { min-width: 0; padding: 13px 15px; display: grid; gap: 4px; border: 1px solid var(--wao-line); border-radius: 11px; background: var(--wao-bg-card); }
.stats-strip span { color: var(--wao-text-weak); font-size: 10.5px; }.stats-strip strong { overflow: hidden; font-size: 18px; text-overflow: ellipsis; }
.inline-error { max-width: 1180px; margin: 0 auto 12px; padding: 10px 12px; display: flex; align-items: center; gap: 8px; border: 1px solid var(--wao-danger-line); border-radius: 9px; background: var(--wao-danger-bg); color: var(--wao-danger-strong); font-size: 12px; }
.inline-error span { flex: 1; }.inline-error button { border: 0; background: transparent; color: inherit; font-weight: 600; cursor: pointer; }
.game-layout { max-width: 1180px; margin: 0 auto; display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(280px, .8fr); gap: 16px; align-items: start; }
.play-card, .record-card { border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-card-hover); }
.play-card { padding: clamp(14px, 2.5vw, 22px); }
.play-toolbar { margin-bottom: 15px; display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.play-toolbar > div:first-child { display: grid; gap: 3px; }.play-toolbar strong { font-size: 14px; }.play-toolbar span { color: var(--wao-text-weak); font-size: 10.5px; }
.toolbar-actions { display: flex; gap: 7px; }.toolbar-actions button { height: 34px; padding: 0 11px; display: inline-flex; align-items: center; gap: 5px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-3); cursor: pointer; }
.toolbar-actions button.primary { border-color: var(--wao-primary); background: var(--wao-primary); color: #fff; }.toolbar-actions button:disabled { opacity: .45; cursor: not-allowed; }
.number-board { position: relative; width: min(100%, 560px); aspect-ratio: 1; margin: 0 auto; padding: clamp(8px, 1.8vw, 13px); display: grid; grid-template-columns: repeat(4, 1fr); gap: clamp(7px, 1.5vw, 11px); border-radius: 18px; background: #2c3040; box-shadow: inset 0 0 0 1px rgba(255,255,255,.04), 0 18px 38px rgba(24, 27, 39, .13); touch-action: none; user-select: none; }
.number-board.busy { cursor: progress; }.number-tile { display: grid; place-items: center; border-radius: clamp(8px, 1.5vw, 13px); color: #fff; font-size: clamp(21px, 5vw, 42px); font-weight: 800; letter-spacing: -.04em; box-shadow: inset 0 1px rgba(255,255,255,.18); animation: tile-in .16s ease-out; }
.number-tile span { transform: translateY(-1px); }.tile-empty { background: rgba(255,255,255,.07); box-shadow: none; animation: none; }
.tile-1 { background: #eef0f5; color: #3e4353; }.tile-2 { background: #dfe5f6; color: #394870; }.tile-3 { background: #9caeea; }.tile-4 { background: #6f83df; }.tile-5 { background: #5268d3; }.tile-6 { background: #775fc8; }.tile-7 { background: #9b55bd; }.tile-8 { background: #c05291; }.tile-9 { background: #d45e6a; }.tile-10 { background: #db7a43; }.tile-11 { background: linear-gradient(135deg, #e2a43b, #cd5f70 48%, #725ad4); font-size: clamp(17px, 4vw, 34px); }
.board-overlay { position: absolute; inset: 0; z-index: 3; padding: 20px; display: grid; place-items: center; align-content: center; gap: 9px; border-radius: inherit; background: rgba(27, 30, 42, .88); color: #fff; text-align: center; backdrop-filter: blur(5px); }.board-overlay i { font-size: 40px; color: #f3bb52; }.board-overlay strong { font-size: 22px; }.board-overlay span { color: #c9cbd5; font-size: 12px; }.board-overlay button { margin-top: 7px; padding: 10px 15px; border: 0; border-radius: 9px; background: #fff; color: #2c3040; font-weight: 650; cursor: pointer; }
.target-banner { width: min(100%, 560px); margin: 12px auto 0; padding: 11px 13px; display: flex; align-items: center; gap: 10px; border: 1px solid #f0d49c; border-radius: 10px; background: #fff7e4; color: #8b5c0c; font-size: 12px; }.target-banner i { font-size: 20px; color: #d59a2e; }
.direction-pad { width: 156px; margin: 14px auto 0; display: grid; grid-template-columns: repeat(3, 46px); grid-template-areas: '. up .' 'left down right'; gap: 6px; }.direction-pad button { width: 46px; height: 38px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-sub); color: var(--wao-text-3); cursor: pointer; }.direction-pad button:nth-child(1) { grid-area: up; }.direction-pad button:nth-child(2) { grid-area: left; }.direction-pad button:nth-child(3) { grid-area: down; }.direction-pad button:nth-child(4) { grid-area: right; }.direction-pad button:hover { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.how-to-play { width: min(100%, 560px); margin: 14px auto 0; display: flex; align-items: flex-start; gap: 8px; color: var(--wao-text-weak); font-size: 11px; line-height: 1.6; }.how-to-play i { margin-top: 1px; color: var(--wao-primary-text); font-size: 15px; }
.record-card { overflow: hidden; }.record-head { padding: 17px 17px 14px; display: flex; align-items: center; justify-content: space-between; gap: 10px; border-bottom: 1px solid var(--wao-divider); }.record-head > div { display: grid; gap: 2px; }.record-head span { color: var(--wao-text-weak); font-size: 10px; }.record-head strong { font-size: 14px; }.win-count { padding: 5px 8px; display: inline-flex; align-items: center; gap: 4px; border-radius: 999px; background: var(--wao-warning-bg); color: var(--wao-warning-strong) !important; }
.run-list { padding: 7px; }.run-row { padding: 9px; display: flex; align-items: center; gap: 9px; border-radius: 9px; }.run-row:hover { background: var(--wao-bg-hover); }.run-result { width: 31px; height: 31px; flex: none; display: grid; place-items: center; border-radius: 8px; background: var(--wao-bg-sub); color: var(--wao-text-weak); }.run-result.won { background: var(--wao-warning-bg); color: var(--wao-warning-strong); }.run-result.game_over { background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
.run-main { min-width: 0; flex: 1; display: grid; gap: 2px; }.run-main strong, .run-side strong { font-size: 12px; }.run-main span, .run-side span { color: var(--wao-text-weak); font-size: 9.5px; }.run-side { flex: none; display: grid; justify-items: end; gap: 2px; }
.empty-records { min-height: 190px; padding: 22px; display: grid; place-items: center; align-content: center; gap: 7px; color: var(--wao-text-weak); text-align: center; }.empty-records i { font-size: 28px; color: var(--wao-success); }.empty-records strong { color: var(--wao-text-3); font-size: 12px; }.empty-records span { font-size: 10px; }
.next-game { margin: 7px; padding: 12px; display: flex; align-items: center; gap: 9px; border: 1px dashed var(--wao-line-strong); border-radius: 10px; background: var(--wao-bg-sub); }.next-icon { width: 34px; height: 34px; flex: none; display: grid; place-items: center; border-radius: 9px; background: var(--wao-bg-hover-2); color: var(--wao-text-faint); }.next-game > div { display: grid; gap: 2px; }.next-game strong { color: var(--wao-text-3); font-size: 11px; }.next-game span { color: var(--wao-text-weak); font-size: 9.5px; }
@keyframes spin { to { transform: rotate(360deg); } } @keyframes pulse { 50% { opacity: .3; transform: scale(.75); } } @keyframes tile-in { from { opacity: .55; transform: scale(.88); } to { opacity: 1; transform: scale(1); } }
@media (max-width: 900px) { .game-layout { grid-template-columns: 1fr; }.record-card { max-width: none; }.stats-strip { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 620px) { .game-page { padding: 18px 12px 34px; }.game-hero { display: grid; gap: 12px; }.sync-pill { justify-self: start; }.game-hero h1 { font-size: 30px; }.stats-strip { grid-template-columns: repeat(2, 1fr); }.stats-strip > div:first-child { grid-column: 1 / -1; }.play-card { padding: 11px; border-radius: 13px; }.play-toolbar { align-items: flex-start; }.toolbar-actions { flex-direction: column-reverse; }.toolbar-actions button { height: 31px; }.number-board { gap: 7px; padding: 8px; border-radius: 14px; }.number-tile { border-radius: 8px; }.how-to-play { padding: 0 3px; }.game-layout { gap: 12px; } }
@media (prefers-reduced-motion: reduce) { .number-tile, .spinner, .sync-dot { animation: none !important; } }
</style>
