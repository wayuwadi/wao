<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AttendanceDay, AttendanceWeek } from '@/api/attendance'

const props = defineProps<{ days: AttendanceDay[]; weeks: AttendanceWeek[] }>()
const mode = ref<'day' | 'week'>('day')
const selected = ref('')
const series = computed(() => mode.value === 'day'
  ? [...props.days].sort((a, b) => a.date.localeCompare(b.date)).map(day => ({
    key: day.date, label: day.date.slice(5),
    value: day.dayType === 'LEAVE' || !day.clockInAt || !day.clockOutAt ? null : day.workHours,
    note: day.dayType === 'LEAVE' ? '请假 · 不计日均' : day.workHours === null ? '无完整工时' : day.manualOverride ? '手工模拟' : 'Moka',
  }))
  : [...props.weeks].sort((a, b) => a.from.localeCompare(b.from)).map(week => ({
    key: week.from, label: `${week.from.slice(5)}—${week.to.slice(5)}`,
    value: week.validDays ? week.averageHours : null, note: `${week.validDays} 天有效出勤`,
  })))
const valid = computed(() => series.value.filter(item => item.value !== null))
const ceiling = computed(() => Math.max(12, Math.ceil(Math.max(0, ...valid.value.map(item => item.value!)) / 2) * 2))
const y = (hours: number) => 150 - hours / ceiling.value * 126
const points = computed(() => series.value.map((item, index) => ({ ...item,
  x: 30 + index / Math.max(1, series.value.length - 1) * 260,
  y: item.value === null ? null : y(item.value),
})))
const paths = computed(() => {
  const segments: string[] = []
  let segment = ''
  for (const point of points.value) {
    if (point.y === null) { if (segment) segments.push(segment); segment = ''; continue }
    segment += `${segment ? ' L' : 'M'}${point.x},${point.y}`
  }
  if (segment) segments.push(segment)
  return segments
})
const active = computed(() => series.value.find(item => item.key === selected.value) || valid.value[valid.value.length - 1])
const extrema = computed(() => {
  const values = valid.value.map(item => item.value!)
  return values.length ? { min: Math.min(...values), max: Math.max(...values) } : null
})
watch([mode, () => props.days, () => props.weeks], () => { selected.value = '' })
</script>

<template>
  <section class="attendance-trend" aria-label="工时趋势分析">
    <div class="trend-heading"><b>工时趋势</b><div class="trend-modes" role="group" aria-label="趋势粒度"><button type="button" :aria-pressed="mode === 'day'" @click="mode = 'day'">每日</button><button type="button" :aria-pressed="mode === 'week'" @click="mode = 'week'">周均</button></div></div>
    <template v-if="valid.length">
      <div class="trend-reading" aria-live="polite"><span>{{ active?.label }}</span><strong class="wao-tabular">{{ active?.value?.toFixed(2) ?? '—' }} <small>h</small></strong><span>{{ active?.note }}</span></div>
      <svg viewBox="0 0 310 177" class="trend-chart" role="group" :aria-label="mode === 'day' ? '每日有效工时折线图，空档表示无完整工时' : '各周平均工时折线图'">
        <g v-for="tick in [0, ceiling / 2, ceiling]" :key="tick" class="trend-grid"><line x1="30" x2="290" :y1="y(tick)" :y2="y(tick)"/><text x="23" :y="y(tick) + 3" text-anchor="end">{{ tick }}h</text></g>
        <line class="trend-target" x1="30" x2="290" :y1="y(10)" :y2="y(10)"/><text class="target-label" x="290" :y="y(10) - 5" text-anchor="end">目标 10h</text>
        <path v-for="(path, index) in paths" :key="index" :d="path" class="trend-line"/>
        <template v-for="point in points" :key="point.key"><g v-if="point.y !== null" role="button" tabindex="0" :aria-label="`${point.label}，${point.value?.toFixed(2)} 小时，${point.note}`" :aria-pressed="active?.key === point.key" class="trend-point" @mouseenter="selected = point.key" @focus="selected = point.key" @click="selected = point.key" @keydown.enter.prevent="selected = point.key" @keydown.space.prevent="selected = point.key"><circle :cx="point.x" :cy="point.y" r="9" class="point-hit"/><circle :cx="point.x" :cy="point.y" :r="active?.key === point.key ? 4 : 2.5"/><title>{{ point.label }} · {{ point.value?.toFixed(2) }} h</title></g></template>
        <text class="axis-label" x="30" y="171">{{ series[0]?.label.split('—')[0] }}</text><text class="axis-label" x="290" y="171" text-anchor="end">{{ series[series.length - 1]?.label.split('—').pop() }}</text>
      </svg>
      <div v-if="extrema" class="trend-range"><span>最高 <b>{{ extrema.max.toFixed(2) }} h</b></span><span>最低 <b>{{ extrema.min.toFixed(2) }} h</b></span></div>
      <p class="trend-note">请假、缺卡不计入；空档不按 0 小时计算</p>
    </template>
    <p v-else class="trend-empty">暂无完整工时记录，完成同步后显示趋势。</p>
  </section>
</template>

<style scoped>
.attendance-trend{grid-column:1/-1;min-width:0;padding-top:var(--wao-space-1);border-top:1px solid var(--wao-line)}
.trend-heading,.trend-reading,.trend-range{display:flex;align-items:center;justify-content:space-between;gap:var(--wao-space-1)}
.trend-heading b{font-size:var(--wao-font-help);color:var(--wao-text)}
.trend-modes{display:flex;gap:2px;padding:2px;border:1px solid var(--wao-line);border-radius:var(--wao-radius-control)}
.trend-modes button{border:0;border-radius:var(--wao-radius-checkbox);background:transparent;color:var(--wao-text-3);padding:3px 8px;font-size:11px;cursor:pointer}
.trend-modes button[aria-pressed=true]{background:var(--wao-primary-soft);color:var(--wao-primary-text)}
.trend-reading{margin-top:var(--wao-space-1);min-height:26px;font-size:11px;color:var(--wao-text-3)}
.trend-reading strong{font-size:var(--wao-font-card-title);color:var(--wao-text);margin-right:auto}.trend-reading small{font-size:11px;font-weight:400}
.trend-chart{display:block;width:100%;height:auto;overflow:visible;margin-top:var(--wao-space-1)}
.trend-grid line{stroke:var(--wao-line)}.trend-grid text,.axis-label{fill:var(--wao-text-3);font-size:10px}
.trend-target{stroke:var(--wao-warning-strong);stroke-dasharray:4 4}.target-label{fill:var(--wao-warning-strong);font-size:10px}
.trend-line{fill:none;stroke:var(--wao-primary);stroke-width:2;stroke-linejoin:round}
.trend-point{fill:var(--wao-primary);cursor:pointer;outline:none}.trend-point .point-hit{fill:transparent}
.trend-point:focus-visible circle:last-of-type{stroke:var(--wao-text);stroke-width:2}
.trend-range{font-size:11px;color:var(--wao-text-3);margin-top:var(--wao-space-1)}.trend-range b{color:var(--wao-text-2);font-weight:500}
.trend-note,.trend-empty{font-size:11px;line-height:1.6;color:var(--wao-text-3);margin:var(--wao-space-1) 0 0}.trend-empty{padding:var(--wao-space-2) 0}
</style>
