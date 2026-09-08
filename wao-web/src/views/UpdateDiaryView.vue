<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchUpdateDiary, type UpdateDiaryPage, type UpdateKind } from '@/api/updateDiary'
import WaoPagination from '@/components/ui/WaoPagination.vue'
import { MODULES } from '@/config/nav'
import type { ModuleId } from '@/types'

const PAGE_SIZE = 12
const diary = ref<UpdateDiaryPage | null>(null)
const loading = ref(true)
const error = ref('')

const pageCount = computed(() =>
  Math.max(1, Math.ceil((diary.value?.totalDays ?? 0) / (diary.value?.size ?? PAGE_SIZE))),
)

const kindMeta: Record<UpdateKind, { label: string; icon: string }> = {
  FEATURE: { label: '新功能', icon: 'ri-sparkling-line' },
  FIX: { label: '问题修复', icon: 'ri-tools-line' },
  PERFORMANCE: { label: '体验优化', icon: 'ri-speed-up-line' },
}

const scopeLabels: Record<string, string> = {
  agent: '自媒体 Agent',
  'ai-run': 'AI 运行',
  auth: '登录与账号',
  decision: '决策管理',
  editor: '文档编辑',
  feishu: '飞书协作',
  http: '请求体验',
  pet: '首页宠物',
  polish: '润色',
  ui: '通用体验',
  web: '网页应用',
}

function scopeLabel(scope: string) {
  return MODULES[scope as ModuleId]?.label ?? scopeLabels[scope] ?? `WAO · ${scope}`
}

function dateLabel(value: string) {
  const [year, month, day] = value.split('-').map(Number)
  if (!year || !month || !day) return value
  return `${year} 年 ${month} 月 ${day} 日`
}

function weekdayLabel(value: string) {
  const date = new Date(`${value}T00:00:00`)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', { weekday: 'long' }).format(date)
}

async function load(page = diary.value?.page ?? 1) {
  loading.value = true
  error.value = ''
  try {
    diary.value = await fetchUpdateDiary(page, PAGE_SIZE)
    window.scrollTo?.({ top: 0, behavior: 'smooth' })
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '更新日记读取失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(() => load(1))
</script>

<template>
  <main class="update-diary-page">
    <header class="diary-hero">
      <div class="hero-copy">
        <span class="eyebrow"><i class="ri-history-line"></i> WAO UPDATE DIARY</span>
        <h1>更新日记</h1>
        <p>这里记录 WAO 已经完成并验收的功能变化。没有更新的日期不会占一行。</p>
      </div>
      <div class="hero-mark" aria-hidden="true">
        <i class="ri-sparkling-2-line"></i>
      </div>
    </header>

    <section v-if="loading && !diary" class="state-card" aria-live="polite">
      <i class="ri-loader-4-line spin"></i>
      <div><b>正在读取更新日记…</b><span>只整理已经确认交付的功能结果</span></div>
    </section>

    <section v-else-if="error && !diary" class="state-card error" role="alert">
      <i class="ri-error-warning-line"></i>
      <div>
        <b>暂时没能读到更新日记</b><span>{{ error }}</span>
      </div>
      <button type="button" @click="load(1)">重新读取</button>
    </section>

    <template v-else-if="diary">
      <div v-if="error" class="inline-error" role="alert">
        <span><i class="ri-error-warning-line"></i>{{ error }} 已保留上一次结果。</span>
        <button type="button" @click="load()">重试</button>
      </div>

      <section v-if="diary.days.length" class="timeline" aria-label="WAO 更新日记时间线">
        <article v-for="day in diary.days" :key="day.date" class="timeline-day">
          <div class="date-column">
            <div class="date-dot"></div>
            <time :datetime="day.date">{{ dateLabel(day.date) }}</time>
            <span>{{ weekdayLabel(day.date) }}</span>
          </div>
          <div class="updates-column">
            <div
              v-for="(entry, entryIndex) in day.entries"
              :key="`${day.date}:${entryIndex}`"
              class="update-card"
            >
              <div class="update-icon" :class="entry.kind.toLowerCase()">
                <i :class="kindMeta[entry.kind].icon"></i>
              </div>
              <div class="update-copy">
                <div class="update-meta">
                  <span>{{ scopeLabel(entry.scope) }}</span>
                  <em :class="entry.kind.toLowerCase()">{{ kindMeta[entry.kind].label }}</em>
                </div>
                <h2>{{ entry.title }}</h2>
              </div>
            </div>
          </div>
        </article>
      </section>

      <section v-else class="state-card empty">
        <i class="ri-inbox-2-line"></i>
        <div><b>还没有可展示的更新</b><span>首次成功交付后，更新会自动出现在这里。</span></div>
      </section>

      <WaoPagination
        v-if="diary.totalDays > diary.size"
        :page="diary.page"
        :page-count="pageCount"
        :total="diary.totalDays"
        :disabled="loading"
        aria-label="更新日记分页"
        @update:page="load"
      />
    </template>
  </main>
</template>

<style scoped>
.update-diary-page {
  width: min(1040px, 100%);
  margin: 0 auto;
  padding: 26px 32px 64px;
  box-sizing: border-box;
}
.diary-hero {
  position: relative;
  display: flex;
  justify-content: space-between;
  min-height: 174px;
  padding: 32px 38px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--wao-primary) 25%, var(--wao-line));
  border-radius: 24px;
  background:
    radial-gradient(
      circle at 88% 18%,
      color-mix(in srgb, var(--wao-primary) 20%, transparent),
      transparent 38%
    ),
    linear-gradient(
      135deg,
      var(--wao-bg-card),
      color-mix(in srgb, var(--wao-primary-soft) 72%, var(--wao-bg-card))
    );
  box-sizing: border-box;
}
.hero-copy {
  position: relative;
  z-index: 1;
  max-width: 650px;
}
.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--wao-primary-text);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.11em;
}
.diary-hero h1 {
  margin: 15px 0 9px;
  color: var(--wao-text);
  font-size: clamp(30px, 5vw, 46px);
  line-height: 1.05;
  letter-spacing: -0.04em;
}
.diary-hero p {
  max-width: 560px;
  margin: 0;
  color: var(--wao-text-2);
  font-size: 14px;
  line-height: 1.75;
}
.hero-mark {
  align-self: center;
  width: 96px;
  height: 96px;
  display: grid;
  place-items: center;
  border: 1px solid var(--wao-primary-line);
  border-radius: 28px;
  background: color-mix(in srgb, var(--wao-bg-card) 76%, transparent);
  color: var(--wao-primary-text);
  font-size: 46px;
  transform: rotate(7deg);
  box-shadow: 0 18px 44px color-mix(in srgb, var(--wao-primary) 17%, transparent);
}
.timeline {
  margin-top: 38px;
}
.timeline-day {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  gap: 30px;
  position: relative;
}
.timeline-day:not(:last-child) .date-column::after {
  content: '';
  position: absolute;
  top: 22px;
  bottom: -22px;
  left: 7px;
  width: 1px;
  background: var(--wao-line);
}
.date-column {
  position: relative;
  padding: 0 0 36px 28px;
}
.date-dot {
  position: absolute;
  top: 5px;
  left: 0;
  z-index: 1;
  width: 15px;
  height: 15px;
  border: 4px solid var(--wao-bg);
  border-radius: 50%;
  background: var(--wao-primary);
  box-shadow: 0 0 0 1px var(--wao-primary-line);
  box-sizing: border-box;
}
.date-column time {
  display: block;
  color: var(--wao-text);
  font-size: 14px;
  font-weight: 760;
}
.date-column span {
  display: block;
  margin-top: 4px;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.updates-column {
  display: grid;
  gap: 10px;
  padding-bottom: 30px;
}
.update-card {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  min-width: 0;
  padding: 18px 20px;
  border: 1px solid var(--wao-line);
  border-radius: 15px;
  background: var(--wao-bg-card);
  box-shadow: 0 8px 26px color-mix(in srgb, var(--wao-text) 5%, transparent);
}
.update-icon {
  flex: 0 0 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 18px;
}
.update-icon.fix {
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
}
.update-icon.performance {
  background: color-mix(in srgb, #22a06b 13%, var(--wao-bg-card));
  color: #168052;
}
.update-copy {
  min-width: 0;
  flex: 1;
}
.update-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.update-meta > span {
  color: var(--wao-text-weak);
  font-size: 11px;
  font-weight: 700;
}
.update-meta em {
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 9px;
  font-style: normal;
  font-weight: 800;
}
.update-meta em.fix {
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
}
.update-meta em.performance {
  background: color-mix(in srgb, #22a06b 12%, var(--wao-bg-card));
  color: #168052;
}
.update-card h2 {
  margin: 7px 0 0;
  overflow-wrap: anywhere;
  color: var(--wao-text);
  font-size: 15px;
  line-height: 1.55;
}
.state-card {
  min-height: 160px;
  margin-top: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  border: 1px dashed var(--wao-line);
  border-radius: 18px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}
.state-card > i {
  font-size: 25px;
  color: var(--wao-primary-text);
}
.state-card > div {
  display: grid;
  gap: 5px;
}
.state-card b {
  color: var(--wao-text);
  font-size: 14px;
}
.state-card span {
  color: var(--wao-text-weak);
  font-size: 11px;
}
.state-card button,
.inline-error button {
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  padding: 7px 12px;
  cursor: pointer;
}
.state-card.error > i,
.inline-error i {
  color: var(--wao-danger-strong);
}
.inline-error {
  margin-top: 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--wao-danger-line);
  border-radius: 10px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  font-size: 11px;
}
.inline-error span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 700px) {
  .update-diary-page {
    padding: 16px 14px 46px;
  }
  .diary-hero {
    min-height: 0;
    padding: 25px 22px;
    border-radius: 18px;
  }
  .hero-mark {
    position: absolute;
    right: -18px;
    bottom: -22px;
    width: 88px;
    height: 88px;
    opacity: 0.34;
  }
  .diary-hero p {
    padding-right: 24px;
    font-size: 13px;
  }
  .timeline {
    margin-top: 28px;
  }
  .timeline-day {
    grid-template-columns: 1fr;
    gap: 10px;
  }
  .timeline-day:not(:last-child) .date-column::after {
    bottom: -104px;
  }
  .date-column {
    padding: 0 0 0 28px;
  }
  .updates-column {
    padding: 0 0 26px 28px;
  }
  .update-card {
    padding: 15px;
  }
  .update-icon {
    flex-basis: 34px;
    height: 34px;
  }
  .state-card {
    padding: 24px 18px;
    flex-wrap: wrap;
  }
}
</style>
