<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchAiRunners, fetchAiRuns } from '@/api/ai'
import AiRunProgressTimeline from '@/components/ai/AiRunProgressTimeline.vue'
import type { AiRun, AiRunStatus, AiRunner } from '@/types'

const router = useRouter()
const runs = ref<AiRun[]>([])
const runners = ref<AiRunner[]>([])
const loading = ref(true)
const selectedRun = ref<AiRun | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

const activeRuns = computed(() => runs.value.filter((run) => ['queued', 'claimed', 'running'].includes(run.status)))
const recentRuns = computed(() => runs.value.filter((run) => !['queued', 'claimed', 'running'].includes(run.status)).slice(0, 8))
const onlineRunners = computed(() => runners.value.filter((runner) => runner.online))
const totalSlots = computed(() => onlineRunners.value.reduce((sum, runner) => sum + runner.maxConcurrency, 0))
const usedSlots = computed(() => onlineRunners.value.reduce((sum, runner) => sum + runner.activeRuns, 0))

const statusMeta: Record<AiRunStatus, { label: string; icon: string; color: string; bg: string }> = {
  queued: { label: '等待电脑领取', icon: 'ri-time-line', color: '#8a5b0d', bg: '#fff7df' },
  claimed: { label: '准备工作区', icon: 'ri-git-branch-line', color: '#3650b4', bg: '#eef1ff' },
  running: { label: 'Codex 运行中', icon: 'ri-loader-4-line', color: '#3650b4', bg: '#eef1ff' },
  succeeded: { label: '等待验收', icon: 'ri-checkbox-circle-line', color: '#237a4b', bg: '#eaf8f0' },
  failed: { label: '运行失败', icon: 'ri-error-warning-line', color: '#b33a3a', bg: '#fff0f0' },
  cancelled: { label: '已取消', icon: 'ri-stop-circle-line', color: '#747989', bg: '#f1f2f5' },
}

async function refresh() {
  try {
    const [nextRuns, nextRunners] = await Promise.all([fetchAiRuns(false), fetchAiRunners()])
    runs.value = nextRuns
    runners.value = nextRunners
    if (selectedRun.value) selectedRun.value = nextRuns.find((run) => run.id === selectedRun.value?.id) ?? selectedRun.value
  } catch {
    // 首页轮询失败时保留上一帧，避免网络抖动让运行面板闪空。
  } finally {
    loading.value = false
  }
}

function openRun(run: AiRun) {
  router.push({ path: `/projects/${run.projectId}`, query: { task: run.taskId } })
}

function inspectRun(run: AiRun) {
  selectedRun.value = run
}

function resultPreview(run: AiRun) {
  if (!run.resultSummary) return run.status === 'failed' ? '执行节点没有返回错误详情' : ''
  const meaningfulLine = run.resultSummary
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => line && !line.startsWith('工作目录：') && !line.startsWith('本机日志：'))
  return meaningfulLine || run.resultSummary.trim()
}

function timeText(value: string | null) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function progressAge(value: string | null) {
  if (!value) return '等待反馈'
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000))
  if (seconds < 10) return '刚刚更新'
  if (seconds < 60) return `${seconds} 秒前更新`
  return `${Math.floor(seconds / 60)} 分钟前更新`
}

onMounted(() => {
  void refresh()
  timer = setInterval(refresh, 5_000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="home">
    <section class="hero">
      <div>
        <div class="eyebrow">AI CONTROL CENTER</div>
        <h1>现在有 {{ activeRuns.length }} 个任务在跑</h1>
        <p>WAO 会把任务分配给在线电脑，每个任务使用独立 Codex 会话和 Git worktree。</p>
      </div>
      <div class="capacity">
        <div class="capacity-num">{{ usedSlots }}<span>/ {{ totalSlots }}</span></div>
        <div>执行槽位占用</div>
        <div class="capacity-bar"><i :style="{ width: totalSlots ? `${Math.min(100, usedSlots / totalSlots * 100)}%` : '0%' }"></i></div>
      </div>
    </section>

    <section class="runner-strip">
      <div class="section-title"><i class="ri-computer-line"></i>执行电脑</div>
      <div v-if="!runners.length && !loading" class="strip-empty">还没有电脑接入，请到项目设置生成配对码。</div>
      <div v-for="runner in runners" :key="runner.id" class="runner-chip" :class="{ offline: !runner.online }">
        <span class="online-dot"></span>
        <b>{{ runner.name }}</b>
        <em>{{ runner.online ? `${runner.activeRuns}/${runner.maxConcurrency} 运行中` : '离线' }}</em>
      </div>
    </section>

    <section class="run-section">
      <div class="section-head">
        <div>
          <h2>正在运行</h2>
          <p>点击卡片查看 AI 的实时反馈和执行档案。</p>
        </div>
        <span class="live"><i></i>每 5 秒刷新</span>
      </div>
      <div v-if="loading" class="empty"><i class="ri-loader-4-line spin"></i>正在读取 AI 运行状态…</div>
      <div v-else-if="!activeRuns.length" class="empty ready">
        <i class="ri-robot-2-line"></i>
        <b>当前没有任务在运行</b>
        <span>打开项目任务，点击 “AI run run” 即可开始。</span>
      </div>
      <div v-else class="run-grid">
        <button v-for="run in activeRuns" :key="run.id" class="run-card" @click="inspectRun(run)">
          <div class="run-top">
            <span class="status" :style="{ color: statusMeta[run.status].color, background: statusMeta[run.status].bg }">
              <i :class="[statusMeta[run.status].icon, { spin: run.status === 'running' }]"></i>
              {{ statusMeta[run.status].label }}
            </span>
            <span class="run-time">{{ timeText(run.startedAt || run.createdAt) }}</span>
          </div>
          <h3>{{ run.taskTitle }}</h3>
          <div class="run-project"><i class="ri-stack-line"></i>{{ run.projectName }}</div>
          <div class="live-progress">
            <span class="live-pulse"></span>
            <div><b>{{ run.latestProgress || '正在等待第一条 AI 反馈…' }}</b><small>{{ progressAge(run.progressUpdatedAt) }}</small></div>
          </div>
          <div class="run-foot">
            <span><i class="ri-computer-line"></i>{{ run.runnerName || '等待自动分配' }}</span>
            <i class="ri-arrow-right-line"></i>
          </div>
        </button>
      </div>
    </section>

    <section v-if="recentRuns.length" class="recent-section">
      <div class="section-head">
        <div><h2>最近完成</h2><p>完成后任务会自动进入“待验证”。</p></div>
      </div>
      <div class="recent-list">
        <button v-for="run in recentRuns" :key="run.id" @click="inspectRun(run)">
          <span class="recent-icon" :style="{ color: statusMeta[run.status].color, background: statusMeta[run.status].bg }">
            <i :class="statusMeta[run.status].icon"></i>
          </span>
          <span class="recent-main">
            <b>{{ run.taskTitle }}</b>
            <em>{{ run.projectName }} · {{ run.runnerName || '未分配电脑' }}</em>
            <small v-if="run.status === 'failed'">{{ resultPreview(run) }}</small>
          </span>
          <span class="recent-status">{{ statusMeta[run.status].label }}</span>
          <span class="run-time">{{ timeText(run.finishedAt) }}</span>
          <i class="ri-arrow-right-s-line"></i>
        </button>
      </div>
    </section>

    <div v-if="selectedRun" class="run-detail-mask" @click.self="selectedRun = null">
      <section class="run-detail" role="dialog" aria-modal="true" aria-labelledby="run-detail-title">
        <header>
          <span class="recent-icon" :style="{ color: statusMeta[selectedRun.status].color, background: statusMeta[selectedRun.status].bg }">
            <i :class="statusMeta[selectedRun.status].icon"></i>
          </span>
          <div>
            <h2 id="run-detail-title">{{ statusMeta[selectedRun.status].label }}</h2>
            <p>{{ selectedRun.taskTitle }}</p>
          </div>
          <button class="detail-close" aria-label="关闭运行详情" @click="selectedRun = null"><i class="ri-close-line"></i></button>
        </header>
        <dl>
          <div><dt>执行电脑</dt><dd>{{ selectedRun.runnerName || '未分配电脑' }}</dd></div>
          <div><dt>退出码</dt><dd>{{ selectedRun.exitCode ?? '—' }}</dd></div>
          <div><dt>{{ selectedRun.finishedAt ? '结束时间' : '最近反馈' }}</dt><dd>{{ timeText(selectedRun.finishedAt || selectedRun.progressUpdatedAt) || '等待中' }}</dd></div>
        </dl>
        <div class="result-title">AI 执行记录</div>
        <AiRunProgressTimeline :run="selectedRun" />
        <template v-if="selectedRun.resultSummary">
          <div class="result-title result-gap">{{ selectedRun.status === 'failed' ? '失败原因' : '最终结果' }}</div>
          <pre>{{ selectedRun.resultSummary }}</pre>
        </template>
        <footer>
          <button class="secondary" @click="selectedRun = null">关闭</button>
          <button class="primary" @click="openRun(selectedRun)">打开对应任务</button>
        </footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.home {
  min-height: 100%;
  padding: 24px 28px 60px;
  background: var(--wao-bg-page);
}
.hero {
  min-height: 170px;
  padding: 28px 30px;
  border-radius: 18px;
  background:
    radial-gradient(circle at 85% 20%, rgba(136, 153, 255, .35), transparent 35%),
    linear-gradient(135deg, #202b69, #4358d6);
  color: #fff;
  display: flex;
  align-items: center;
  gap: 24px;
  box-shadow: 0 14px 35px rgba(43, 59, 141, .2);
}
.hero > div:first-child { flex: 1; }
.eyebrow { font-size: 10px; letter-spacing: 2px; opacity: .65; }
.hero h1 { margin: 8px 0 6px; font-size: 28px; }
.hero p { margin: 0; font-size: 13px; opacity: .72; }
.capacity {
  width: 185px;
  padding: 18px;
  border: 1px solid #ffffff2b;
  border-radius: 14px;
  background: #ffffff12;
  font-size: 11px;
  color: #ffffffa8;
}
.capacity-num { color: #fff; font-size: 30px; font-weight: 750; }
.capacity-num span { font-size: 14px; opacity: .6; }
.capacity-bar { height: 5px; margin-top: 11px; overflow: hidden; border-radius: 9px; background: #ffffff24; }
.capacity-bar i { display: block; height: 100%; border-radius: inherit; background: #fff; transition: width .3s; }
.runner-strip {
  min-height: 58px;
  margin-top: 16px;
  padding: 11px 14px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: #fff;
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.section-title { margin-right: 4px; font-size: 12px; font-weight: 650; color: var(--wao-text-2); }
.runner-chip {
  padding: 6px 9px;
  border-radius: 8px;
  background: #f3faf6;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
}
.runner-chip b { font-weight: 600; }
.runner-chip em { color: #458064; font-style: normal; }
.runner-chip.offline { background: #f4f5f7; opacity: .7; }
.runner-chip.offline em { color: var(--wao-text-weak); }
.online-dot { width: 7px; height: 7px; border-radius: 50%; background: #31a56a; box-shadow: 0 0 0 3px #dff5e9; }
.offline .online-dot { background: #9ca1ad; box-shadow: none; }
.strip-empty { color: var(--wao-text-weak); font-size: 11.5px; }
.run-section, .recent-section { margin-top: 26px; }
.section-head { display: flex; align-items: flex-end; margin-bottom: 12px; }
.section-head > div { flex: 1; }
.section-head h2 { margin: 0; font-size: 17px; }
.section-head p { margin: 4px 0 0; color: var(--wao-text-weak); font-size: 11.5px; }
.live { color: var(--wao-text-weak); font-size: 10.5px; }
.live i { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; background: #35a86e; }
.run-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(270px, 1fr)); gap: 12px; }
.run-card {
  padding: 16px;
  border: 1px solid var(--wao-line);
  border-radius: 13px;
  background: #fff;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: .16s;
}
.run-card:hover { transform: translateY(-2px); border-color: #bec7ef; box-shadow: 0 8px 22px #27366c14; }
.run-top, .run-foot { display: flex; align-items: center; gap: 8px; }
.status { padding: 4px 7px; border-radius: 6px; font-size: 10.5px; font-weight: 650; }
.run-time { margin-left: auto; color: var(--wao-text-weak); font-size: 10.5px; }
.run-card h3 { min-height: 40px; margin: 14px 0 5px; font-size: 14px; line-height: 1.45; }
.run-project { color: var(--wao-text-weak); font-size: 11.5px; }
.run-project i { margin-right: 5px; }
.live-progress { margin-top: 13px; padding: 10px; border-radius: 9px; background: #f7f8fd; display: flex; gap: 9px; align-items: flex-start; }
.live-progress > div { min-width: 0; }
.live-progress b, .live-progress small { display: block; }
.live-progress b { overflow: hidden; color: #41485c; font-size: 11px; font-weight: 550; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.live-progress small { margin-top: 3px; color: #969bac; font-size: 9.5px; }
.live-pulse { width: 8px; height: 8px; margin-top: 4px; flex: 0 0 auto; border-radius: 50%; background: #36a86f; box-shadow: 0 0 0 4px #dff5e9; animation: livePulse 1.5s infinite; }
.run-foot { margin-top: 15px; padding-top: 11px; border-top: 1px solid #f0f1f5; color: var(--wao-text-3); font-size: 11px; }
.run-foot span { flex: 1; }
.run-foot span i { margin-right: 5px; }
.empty {
  min-height: 150px;
  border: 1px dashed #d6d9e5;
  border-radius: 13px;
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  font-size: 12px;
}
.empty.ready { flex-direction: column; gap: 5px; }
.empty.ready > i { margin-bottom: 4px; color: #7d8ddd; font-size: 26px; }
.empty.ready b { color: var(--wao-text-2); font-size: 13px; }
.recent-list { overflow: hidden; border: 1px solid var(--wao-line); border-radius: 12px; background: #fff; }
.recent-list button {
  width: 100%;
  min-height: 58px;
  padding: 9px 14px;
  border: 0;
  border-bottom: 1px solid #f0f1f4;
  background: #fff;
  color: inherit;
  text-align: left;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 11px;
}
.recent-list button:last-child { border-bottom: 0; }
.recent-list button:hover { background: #fafbff; }
.recent-icon { width: 30px; height: 30px; border-radius: 8px; display: grid; place-items: center; }
.recent-main { flex: 1; min-width: 0; }
.recent-main b, .recent-main em { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recent-main b { font-size: 12.5px; }
.recent-main em { margin-top: 2px; color: var(--wao-text-weak); font-size: 10.5px; font-style: normal; }
.recent-main small { display: block; margin-top: 4px; overflow: hidden; color: #b33a3a; font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.recent-status { color: var(--wao-text-3); font-size: 11px; }
.run-detail-mask {
  position: fixed;
  inset: 0;
  z-index: 1200;
  padding: 24px;
  background: rgba(20, 25, 45, .34);
  display: grid;
  place-items: center;
}
.run-detail {
  width: min(680px, 100%);
  max-height: min(720px, calc(100vh - 48px));
  overflow: auto;
  padding: 22px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 24px 70px rgba(26, 31, 61, .22);
}
.run-detail header { display: flex; align-items: center; gap: 12px; }
.run-detail header > div { flex: 1; min-width: 0; }
.run-detail h2 { margin: 0; font-size: 17px; }
.run-detail header p { margin: 3px 0 0; overflow: hidden; color: var(--wao-text-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.detail-close { width: 32px; height: 32px; border: 0; border-radius: 8px; background: #f4f5f8; cursor: pointer; }
.run-detail dl { margin: 18px 0; display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.run-detail dl > div { padding: 10px 12px; border-radius: 9px; background: #f6f7fa; }
.run-detail dt { color: var(--wao-text-weak); font-size: 10px; }
.run-detail dd { margin: 4px 0 0; font-size: 11.5px; font-weight: 600; }
.result-title { margin-bottom: 7px; font-size: 12px; font-weight: 650; }
.result-title.result-gap { margin-top: 16px; }
.run-detail pre { max-height: 360px; margin: 0; overflow: auto; padding: 14px; border: 1px solid #ead3d3; border-radius: 10px; background: #fff8f8; color: #753030; font: 11.5px/1.65 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; word-break: break-word; }
.run-detail footer { margin-top: 18px; display: flex; justify-content: flex-end; gap: 9px; }
.run-detail footer button { padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 12px; }
.run-detail footer .secondary { border: 1px solid var(--wao-line); background: #fff; }
.run-detail footer .primary { border: 1px solid #4358d6; background: #4358d6; color: #fff; }
@keyframes livePulse { 50% { opacity: .45; box-shadow: 0 0 0 7px #dff5e955; } }
.spin { display: inline-block; animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) {
  .home { padding: 16px; }
  .hero { padding: 22px; flex-direction: column; align-items: stretch; }
  .capacity { width: auto; }
  .run-detail dl { grid-template-columns: 1fr; }
}
</style>
