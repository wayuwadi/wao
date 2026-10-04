<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { isNavigationFailure, NavigationFailureType, useRouter } from 'vue-router'
import { completeRestJourney } from '@/composables/restJourney'
import RestCompanions from './RestCompanions.vue'

const router = useRouter()
const phase = ref<'collect' | 'invite' | 'flip' | 'error'>('collect')
const snapshot = ref<HTMLElement>()
const canvas = ref<HTMLCanvasElement>()
const skipButton = ref<HTMLButtonElement>()
const error = ref('')
const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
const paused = ref(document.hidden)
let frame = 0, elapsed = 0, stamp = 0, disposed = false
const navigating = ref(false)
const animations: Animation[] = []
const app = document.getElementById('app')
const priorInert = app?.inert ?? false
const priorOverflow = document.body.style.overflow
const previousFocus = document.activeElement
const startingRoute = router.currentRoute.value.fullPath
const removeRouteListener = router.afterEach(to => {
  if (!navigating.value && to.fullPath !== startingRoute) completeRestJourney(false)
})
let source: HTMLElement | null = null

function capture() {
  source = document.querySelector<HTMLElement>('.shell') ?? app
  if (!source || !snapshot.value) return
  const clone = source.cloneNode(true) as HTMLElement
  const originals = source.querySelectorAll('canvas'), copies = clone.querySelectorAll('canvas')
  copies.forEach((copy, i) => {
    const original = originals[i]
    if (!original) return
    copy.width = original.width; copy.height = original.height
    try { copy.getContext('2d')?.drawImage(original, 0, 0) } catch { /* 静态回收可继续。 */ }
  })
  clone.removeAttribute('id')
  clone.querySelectorAll('[id]').forEach(el => el.removeAttribute('id'))
  clone.inert = true
  Object.assign(clone.style, { width: `${window.innerWidth}px`, height: `${window.innerHeight}px`, margin: '0', pointerEvents: 'none' })
  snapshot.value.append(clone)
  const bounds = clone.getBoundingClientRect(), cx = innerWidth * .52, cy = innerHeight * .42
  const pieces = clone.querySelectorAll<HTMLElement>('.side, .header, .tab-bar, .home-day, .home-rest-copy > *, .home-companion-space, .site-footer')
  if (!reduced) pieces.forEach((el, i) => {
    const b = el.getBoundingClientRect(), dx = cx - (b.left - bounds.left + b.width / 2), dy = cy - (b.top - bounds.top + b.height / 2)
    if (!el.animate) return
    animations.push(el.animate([
      { transform: 'translate(0,0) scale(1)', opacity: 1 },
      { transform: `translate(${dx*.3}px,${dy*.2-20}px) rotate(-10deg) scale(.9)`, opacity: 1, offset: .3 },
      { transform: `translate(${dx}px,${dy}px) rotate(${i%2?140:-140}deg) scale(0)`, opacity: 0 },
    ], { duration: 2800, delay: Math.min(i, 10)*95, fill: 'forwards', easing: 'cubic-bezier(.45,0,.25,1)' }))
  })
}
function draw(progress: number) {
  const c = canvas.value, ctx = c?.getContext('2d')
  if (!c || !ctx || reduced) return
  const w = innerWidth, h = innerHeight, d = Math.min(devicePixelRatio || 1, 1.5)
  if (c.width !== Math.round(w*d) || c.height !== Math.round(h*d)) { c.width=Math.round(w*d); c.height=Math.round(h*d) }
  ctx.setTransform(d,0,0,d,0,0); ctx.clearRect(0,0,w,h)
  ctx.fillStyle=`rgba(13,14,28,${.98*Math.min(1,progress*1.9)})`;ctx.fillRect(0,0,w,h)
  const cx=w*.52, cy=h*.42, r=35+Math.sin(progress*Math.PI*.65)*30
  ctx.save();ctx.translate(cx,cy);ctx.rotate(-.35+progress*2)
  for(let i=5;i>=0;i--){ctx.beginPath();ctx.ellipse(0,0,r+17+i*5,(r+17+i*5)*.4,0,0,Math.PI*2);ctx.strokeStyle=`rgba(190,177,247,${.09+(5-i)*.065})`;ctx.lineWidth=4;ctx.shadowColor='#c0a7ff';ctx.shadowBlur=20;ctx.stroke()}
  ctx.beginPath();ctx.arc(0,0,r,0,Math.PI*2);ctx.fillStyle='#080913';ctx.fill();ctx.restore()
  for(let i=0;i<65;i++){const t=(i*.618+progress*1.6)%1,rad=(1-t)*w*.66,a=i*2.4+progress*3+t*4;ctx.fillStyle=`rgba(210,199,255,${Math.sin(t*Math.PI)*.65})`;ctx.beginPath();ctx.arc(cx+Math.cos(a)*rad,cy+Math.sin(a)*rad*.65,1+i%2,0,Math.PI*2);ctx.fill()}
}
async function enter(guided=true) {
  if (navigating.value || disposed) return
  navigating.value=true;cancelAnimationFrame(frame)
  try {
    const failure = await router.push('/breathe')
    if ((isNavigationFailure(failure) && !isNavigationFailure(failure, NavigationFailureType.duplicated)) || router.currentRoute.value.path !== '/breathe') throw new Error('休息页面暂时未能打开。')
    if (disposed) return
    if (!guided || reduced) { completeRestJourney(guided, true); return }
    phase.value='flip';elapsed=0;stamp=performance.now();frame=requestAnimationFrame(tick)
  } catch {
    if (!disposed) { phase.value='error';error.value='休息页面暂时未能打开，请重试。';await nextTick();skipButton.value?.focus() }
  } finally { navigating.value=false }
}
function tick(now: number) {
  if (disposed || paused.value) return
  elapsed+=Math.min(100, Math.max(0,now-stamp));stamp=now
  if (phase.value==='collect') {
    draw(elapsed/4000)
    if(elapsed>=(reduced?300:4000)){phase.value='invite';elapsed=0}
  } else if(phase.value==='invite' && elapsed>=(reduced?300:1800)){void enter();return}
  else if(phase.value==='flip' && elapsed>=1200){completeRestJourney(true);return}
  frame=requestAnimationFrame(tick)
}
function pause() {
  if (document.hidden && !paused.value) { paused.value=true;cancelAnimationFrame(frame);animations.forEach(a=>a.pause()) }
}
function resume() { paused.value=false;animations.forEach(a=>a.play());stamp=performance.now();frame=requestAnimationFrame(tick) }
function cancel() { completeRestJourney(false); if(previousFocus instanceof HTMLElement)void nextTick(()=>previousFocus.focus()) }
function keys(event: KeyboardEvent) {
  if(event.key==='Escape'){event.preventDefault();void enter(false)}
  if(event.key==='Tab'){
    const buttons=[...document.querySelectorAll<HTMLButtonElement>('.rest-entry-transition button:not([disabled])')].filter(b=>b.getClientRects().length)
    const i=buttons.indexOf(document.activeElement as HTMLButtonElement)
    event.preventDefault();buttons[(i+(event.shiftKey?-1:1)+buttons.length)%buttons.length]?.focus()
  }
}
onMounted(async()=>{
  capture();if(paused.value)animations.forEach(a=>a.pause());if(app)app.inert=true;document.body.style.overflow='hidden'
  document.addEventListener('visibilitychange',pause);document.addEventListener('keydown',keys,true)
  await nextTick();skipButton.value?.focus();stamp=performance.now();if(!paused.value)frame=requestAnimationFrame(tick)
})
onBeforeUnmount(()=>{
  disposed=true;cancelAnimationFrame(frame);animations.forEach(a=>a.cancel());document.removeEventListener('visibilitychange',pause);document.removeEventListener('keydown',keys,true)
  removeRouteListener()
  if(app)app.inert=priorInert;document.body.style.overflow=priorOverflow
})
</script>

<template>
  <Teleport to="body">
    <section class="rest-entry-transition" :class="[phase, { reduced }]" role="dialog" aria-modal="true" aria-label="和伙伴一起进入休息空间">
      <div class="entry-turn" :class="{ flipped: phase==='flip' }" aria-hidden="true">
        <div class="entry-front"><div ref="snapshot" class="entry-snapshot"></div><canvas ref="canvas"></canvas></div>
        <div class="entry-back"><div class="entry-sun"></div><div class="entry-hill"></div></div>
      </div>
      <div v-if="phase!=='collect'" class="entry-crew"><p v-if="phase==='invite'" class="crew-speech">工作收好啦，一起去休息吧。</p><RestCompanions :welcoming="phase==='invite'" :reduced="reduced" /></div>
      <p v-if="phase==='collect'" class="entry-message" role="status">把忙碌，暂时交给宇宙。</p>
      <div v-if="phase==='error'" class="entry-error" role="alert"><p>{{ error }}</p><button @click="cancel">返回首页</button></div>
      <div v-if="paused" class="entry-paused"><p>已暂停，准备好后继续。</p><button @click="resume">继续旅程</button></div>
      <button ref="skipButton" class="entry-skip" :disabled="navigating" @click="enter(false)">{{ navigating ? '正在进入…' : phase==='error' ? '重试进入 ↗' : '跳过引导 ↗' }}</button>
    </section>
  </Teleport>
</template>

<style scoped>
.rest-entry-transition { position:fixed;inset:0;z-index:25000;isolation:isolate;overflow:hidden;background:#0d0e1c;perspective:1500px;font-family:Inter,'Microsoft YaHei',sans-serif; }
.entry-turn,.entry-front,.entry-back,.entry-snapshot,canvas { position:absolute;inset:0;width:100%;height:100%; }
.entry-turn { transform-style:preserve-3d;transition:transform 1.2s cubic-bezier(.4,0,.2,1); }.entry-turn.flipped { transform:rotateY(-180deg); }
.entry-front,.entry-back { backface-visibility:hidden; }.entry-back { transform:rotateY(180deg);background:#f6f6ed; }.entry-sun { position:absolute;right:13%;top:20%;width:115px;height:115px;border-radius:50%;background:#f7e6b6;opacity:.6; }.entry-hill { position:absolute;bottom:-15%;left:-10%;width:120%;height:40%;border-radius:50%;background:#e1e9d9; }
.entry-crew { position:absolute;left:50%;top:48%;transform:translateX(-50%);z-index:2;transition:top 1.2s ease; }.flip .entry-crew { top:38%; }
.crew-speech { position:absolute;bottom:165px;left:50%;transform:translateX(-50%);width:min(370px,calc(100vw - 36px));padding:17px;border-radius:24px;text-align:center;background:#fff;color:#273c3b;font-size:16px; }.crew-speech:after { content:'';position:absolute;left:50%;bottom:-8px;width:16px;height:16px;transform:rotate(45deg);background:#fff; }
.entry-message { position:absolute;bottom:16%;left:50%;transform:translateX(-50%);width:max-content;max-width:calc(100% - 36px);padding:12px 20px;border-radius:26px;background:#0d0e1ccc;text-align:center;color:#efedf9;font-size:21px;letter-spacing:2px; }
button { font:inherit;cursor:pointer;border:1px solid #dce3dc;border-radius:24px;background:#fff;color:#273c3b;padding:12px 19px;min-height:44px; }.entry-skip { position:absolute;right:26px;bottom:25px;font-size:12px;z-index:4; }
.entry-error,.entry-paused { position:absolute;left:50%;top:20%;transform:translateX(-50%);width:min(420px,calc(100% - 36px));padding:24px;background:#fff;color:#273c3b;border-radius:20px;text-align:center;z-index:3; }.entry-paused { top:10%; }
button:focus-visible { outline:2px solid #8b87d8;outline-offset:4px; }
.reduced .entry-turn,.reduced .entry-crew { transition:none; }.reduced .entry-snapshot { opacity:0; }
:global([data-theme='dark']) .entry-back { background:#202b2a; }:global([data-theme='dark']) .entry-hill { background:#334b42; }
@media(max-width:600px) { .entry-message { font-size:16px;bottom:17%; }.entry-crew { top:48%; }.crew-speech { font-size:14px; }.entry-skip { right:16px;bottom:20px; } }
</style>
