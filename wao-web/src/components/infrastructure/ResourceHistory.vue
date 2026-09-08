<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { InfraPoint } from '@/api/infrastructure'
echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])
const props = defineProps<{ points: InfraPoint[]; metric: 'usage' | 'capacity' }>()
const element = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
let observer: ResizeObserver | undefined
function draw() {
  if (!chart) return
  const usage = props.metric === 'usage'
  const series = usage
    ? [{ name: 'CPU 使用率', color: '#7363d9', values: props.points.map(p => [p.ts * 1000, p.cpu]) },
       { name: '内存使用率', color: '#289d91', values: props.points.map(p => [p.ts * 1000, p.memoryTotal ? (1 - p.memoryAvailable / p.memoryTotal) * 100 : null]) }]
    : [{ name: '根磁盘可用', color: '#5682d8', values: props.points.map(p => [p.ts * 1000, p.diskAvailable == null ? null : p.diskAvailable / 1073741824]) },
       { name: '交换分区已用', color: '#d39245', values: props.points.map(p => [p.ts * 1000, p.swapUsed / 1073741824]) }]
  chart.setOption({ animation: false, tooltip: { trigger: 'axis', valueFormatter: (value: number | null) => value == null ? '未知' : `${Number(value).toFixed(1)} ${usage ? '%' : 'GiB'}` },
    legend: { bottom: 0 }, grid: { left: 52, right: 18, top: 15, bottom: 60 },
    xAxis: { type: 'time' }, yAxis: { type: 'value', min: 0, max: usage ? 100 : undefined, axisLabel: { formatter: usage ? '{value}%' : '{value} GiB' } },
    series: series.map(s => ({ name: s.name, type: 'line', data: s.values, connectNulls: false, showSymbol: props.points.length < 3, symbolSize: 6, itemStyle: { color: s.color }, lineStyle: { width: 2 }, areaStyle: { opacity: 0.04 } })) }, true)
}
onMounted(() => { chart = echarts.init(element.value!); draw(); observer = new ResizeObserver(() => chart?.resize()); observer.observe(element.value!) })
watch(() => [props.points, props.metric], draw, { flush: 'post' })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<template><div ref="element" class="history-chart" role="img" :aria-label="metric === 'usage' ? 'CPU 和内存使用率历史趋势，百分比' : '根磁盘可用容量与交换分区占用历史趋势，GiB'"></div></template>
<style scoped>.history-chart { width: 100%; min-width: 0; height: 240px; }</style>
