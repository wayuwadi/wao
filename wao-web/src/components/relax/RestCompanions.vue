<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ expansion?: number; breathing?: boolean; reduced?: boolean; welcoming?: boolean; journey?: 'depart' | 'arrive'; progress?: number; paused?: boolean; reacting?: number }>()
const emit = defineEmits<{ greet: [index: number] }>()
const pals = ['blue', 'gold', 'green']
const names = ['小画家', '小书虫', '小青蛙']
const steps = computed(() => pals.map((_, i) => Math.max(0, Math.min(1, ((props.progress ?? 0) - i / 3) * 3))))
function visible(i: number) { return !props.journey || (props.journey === 'depart' ? steps.value[i]! < .98 : steps.value[i]! > 0) }
function pose(i: number) {
  if (!props.journey) return {}
  const p = steps.value[i]!, arriving = props.journey === 'arrive'
  if (props.reduced) return { opacity: visible(i) ? 1 : 0 }
  const x = (1-i)*97, travel = arriving ? 1-p : p
  return { opacity: arriving ? Math.min(1,p*4) : Math.min(1,(1-p)*5), transform: `translate(${x*travel}px,${-120*travel}px) scale(${arriving ? .12+.88*p : 1-.94*p}) rotate(${(i-1)*12*travel}deg)` }
}
</script>

<template>
  <div class="rest-companions" :class="{ breathing, welcoming, journey, paused }" :role="journey ? 'group' : 'img'" aria-label="蓝、黄、绿三个休息伙伴"
    :style="{ '--rest-expand': reduced ? 0 : (expansion ?? 0) }">
    <component :is="journey ? 'button' : 'div'" v-for="(color, i) in pals" :key="color" class="rest-pal" :class="[color, { walking: journey && steps[i]! > 0 && steps[i]! < 1 && !reduced, reacting: reacting === i }]" :style="pose(i)" :type="journey ? 'button' : undefined" :disabled="journey && (!visible(i) || paused)" :aria-hidden="journey && !visible(i) ? true : undefined" :aria-label="journey ? `和${names[i]}打个招呼` : undefined" @click="journey && emit('greet',i)">
      <div class="pal-figure">
      <div class="pal-body"><span v-if="color === 'blue'" class="hat"></span><span class="eyes" :class="{ glasses: color === 'gold' }"></span><span class="mouth"></span><span class="arm left"></span><span class="arm right"></span></div><span class="feet"></span>
      </div>
    </component>
  </div>
</template>

<style scoped>
.rest-companions { --rest-expand: 0; display:flex; gap:17px; align-items:flex-end; justify-content:center; width:286px; height:148px; margin-inline:auto; pointer-events:none; }
.rest-pal { width:80px; height:106px; position:relative; flex:none; transform-origin:50% 100%; }
.pal-figure { position:absolute;inset:0; }
.journey { pointer-events:auto; }.journey .rest-pal { padding:0;border:0;border-radius:20px;background:transparent;cursor:pointer;min-height:0; }.journey .rest-pal:focus-visible { outline:2px solid #b1a4e7;outline-offset:10px; }.journey .rest-pal:disabled { cursor:default; }
.walking .pal-figure { animation:pal-step .3s ease-in-out infinite; }.walking .feet { animation:pal-feet .3s linear infinite; }
.reacting .arm.right { animation:pal-hello .65s ease-in-out; }.paused .pal-figure,.paused .feet,.paused .arm { animation-play-state:paused; }
@keyframes pal-step { 50% { translate:0 -5px;rotate:3deg; } }
@keyframes pal-feet { 50% { transform:rotate(-12deg); } }
@keyframes pal-hello { 25%,75% { transform:rotate(-100deg); } 50% { transform:rotate(-60deg); } }
.pal-body { position:absolute; inset:0 0 10px; background:#55c9df; border-radius:43% 48% 35% 35%; box-shadow:inset -8px -8px 12px #0000000e; transform-origin:50% 100%; transform:scale(calc(1 + var(--rest-expand)*.075),calc(1 + var(--rest-expand)*.12)) translateY(calc(var(--rest-expand)*-4px)); }
.gold { height:137px; width:84px; }.gold .pal-body { background:#ebcf57; border-radius:58% 56% 33% 34%; }.green { height:100px; width:88px; }.green .pal-body { background:#b7d67b; border-radius:44% 48% 40% 37%; }
.hat { position:absolute; left:-4px; right:-4px; top:-5px; height:17px; border-radius:50%; background:#263632; transform:rotate(-6deg); }
.eyes { position:absolute; left:26%; right:26%; top:36%; height:10px; border-left:6px solid #27382e; border-right:6px solid #27382e; border-radius:5px; }.breathing .eyes { height:4px; border-radius:0 0 8px 8px; }
.glasses:before,.glasses:after { content:''; position:absolute; top:-9px; width:25px; height:28px; border:2px solid #465035; border-radius:50%; }.glasses:before { left:-14px; }.glasses:after { right:-14px; }
.mouth { position:absolute; left:43%; top:58%; width:14px; height:7px; border-bottom:2px solid #27382e; border-radius:50%; }
.arm { position:absolute; top:59%; width:22px; height:9px; border-radius:10px; background:inherit; }.arm.left { left:-13px; transform-origin:4px 5px; transform:rotate(calc(24deg - var(--rest-expand)*62deg)); }.arm.right { right:-13px; transform-origin:18px 5px; transform:rotate(calc(-24deg + var(--rest-expand)*62deg)); }
.feet { position:absolute; bottom:0; left:18px; right:18px; height:15px; border-left:13px solid #354239; border-right:13px solid #354239; border-radius:7px; }
.welcoming .rest-pal { animation:rest-wave .7s ease both; }.welcoming .gold { animation-delay:.12s; }.welcoming .green { animation-delay:.24s; }
@keyframes rest-wave { 45% { transform:translateY(-13px) rotate(-7deg); } }
@media(max-width:360px) { .rest-companions { transform:scale(.86); } }
@media(prefers-reduced-motion:reduce) { .welcoming .rest-pal,.pal-figure,.feet,.arm { animation:none !important; } .pal-body,.arm { transform:none !important; } }
</style>
