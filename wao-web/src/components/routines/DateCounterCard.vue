<script setup lang="ts">
import { computed } from 'vue'
import { routineColors, type RoutineItem, type RoutineSummary } from '@/api/personalRoutines'
import WaoCard from '@/components/ui/WaoCard.vue'
const props=defineProps<{item:RoutineItem;summary?:RoutineSummary}>(),emit=defineEmits<{edit:[]}>()
const caption=computed(()=>props.summary?.days===0?'就是今天':props.item.direction==='up'?(Number(props.summary?.days)>=0?'已经':'还有'):(Number(props.summary?.days)>=0?'还有':'已经'))
</script>
<template><WaoCard class="date-card" :class="item.style" :style="{'--date-color':routineColors[item.color]}">
 <div class="card-actions"><slot name="actions" /></div><button class="card-title" @click="emit('edit')"><span aria-hidden="true">{{item.emoji||'⏳'}}</span><strong>{{item.name}}</strong></button>
 <p class="number"><span>{{summary?.days==null?'—':Math.abs(summary.days)}}</span><small>天</small></p>
 <p class="date-caption">{{caption}}<template v-if="summary?.days!==0"> · {{item.direction==='up'?item.date:summary?.occurrence||'暂无下次日期'}}</template></p>
 <p v-if="item.calendar==='lunar'" class="secondary">农历 · {{summary?.lunarLabel}}</p><p v-if="item.showAge&&summary?.age!=null" class="secondary">{{summary.age}} 周岁</p><p v-if="item.note" class="note">{{item.note}}</p>
</WaoCard></template>
<style scoped>
.date-card {position:relative;padding:var(--wao-space-3);text-align:center;min-width:0;overflow-wrap:anywhere;}.date-card.soft {background:color-mix(in srgb,var(--date-color) 7%,var(--wao-bg-card));}.date-card.outline {border-color:var(--date-color);}.card-actions {display:flex;justify-content:flex-end;gap:var(--wao-space-1);min-height:32px;}.card-title {display:flex;align-items:center;justify-content:center;gap:var(--wao-space-1);width:100%;font:inherit;font-size:18px;color:var(--wao-text);background:none;border:0;cursor:pointer;min-height:44px;}.card-title span {font-size:26px;}.number{margin:var(--wao-space-2) 0;color:var(--date-color);font-size:60px;font-weight:500;font-variant-numeric:tabular-nums;line-height:1.2;}.number small {font-size:14px;margin-left:var(--wao-space-1);}.date-caption,.secondary{font-size:13px;color:var(--wao-text-3);}.note{font-size:14px;text-align:left;white-space:pre-wrap;max-height:6em;overflow:auto;margin-bottom:0;line-height:1.7;}
</style>
