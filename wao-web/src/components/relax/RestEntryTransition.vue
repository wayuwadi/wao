<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { isNavigationFailure, NavigationFailureType, useRouter } from 'vue-router'
import { completeRestJourney } from '@/composables/restJourney'
import RestCompanions from './RestCompanions.vue'
import { COMPANIONS } from '@/utils/companionIdentity'

const companionNames = [COMPANIONS.painter.name, COMPANIONS.bookworm.name, COMPANIONS.frog.name]

const router = useRouter()
const phase = ref<'collect' | 'invite' | 'depart' | 'flip' | 'arrive' | 'error'>('collect')
const snapshot = ref<HTMLElement>()
const canvas = ref<HTMLCanvasElement>()
const skipButton = ref<HTMLButtonElement>()
const error = ref('')
const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
const paused = ref(document.hidden)
let frame = 0, stamp = 0, disposed = false
const elapsed = ref(0)
const reacting = ref(-1)
let reactionUntil = 0
const passageDuration = 7500
const arrivalDuration = reduced ? 4500 : passageDuration
const progress = computed(() => Math.min(1, elapsed.value / (phase.value === 'arrive' ? arrivalDuration : passageDuration)))
const speaker = computed(() => Math.min(2, Math.floor(progress.value * 3)))
const departures = ['我先去探探路，你慢慢来。', '把待办留在这里，带上轻松的自己。', '跟上啦，我们去有风的地方。']
const arrivals = ['哇，休息一下好舒服。看看这片暖暖的阳光。', '这里的空气好清新。松松肩膀，慢慢感受一下。', '风轻轻吹过来啦。不用赶路，我们一起歇一会儿。']
const replies = ['收到你的招呼啦，我在前面等你。', '好呀，今天也给自己一点空白。', '嘿嘿，走吧，舒服的风在等我们。']
const arrivalReplies = ['你也来啦，一起晒晒太阳吧。', '不着急，慢慢呼吸就好。', '这阵风也在和你打招呼呢。']
const speech = computed(() => reacting.value >= 0 ? (phase.value === 'arrive' ? arrivalReplies : replies)[reacting.value] : phase.value === 'depart' ? departures[speaker.value] : phase.value === 'arrive' ? arrivals[speaker.value] : '工作收好啦，一起去休息吧。')
const turn = computed(() => reduced || phase.value === 'arrive' ? undefined : `rotateY(${-180 * (phase.value === 'flip' ? Math.min(1, elapsed.value / 1200) : 0)}deg)`)
function greet(index: number) { if (paused.value) return; reacting.value = index; reactionUntil = elapsed.value + 850 }
function changePhase(next: typeof phase.value) { phase.value = next; elapsed.value = 0; reacting.value = -1 }
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
  const pieces = clone.querySelectorAll<HTMLElement>('.side, .header, .tab-bar, .home-day, .home-rest-copy > *, .home-companion-space, .site-footer, .mobile-main-nav, .fab')
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
    if (!guided) { completeRestJourney(false, true); return }
    changePhase(reduced ? 'arrive' : 'flip');stamp=performance.now();frame=requestAnimationFrame(tick)
  } catch {
    if (!disposed) { phase.value='error';error.value='休息页面暂时未能打开，请重试。';await nextTick();skipButton.value?.focus() }
  } finally { navigating.value=false;await nextTick();if(!disposed)skipButton.value?.focus() }
}
function tick(now: number) {
  if (disposed || paused.value) return
  elapsed.value+=Math.min(100, Math.max(0,now-stamp));stamp=now
  if (reacting.value >= 0 && elapsed.value >= reactionUntil) reacting.value = -1
  if (phase.value==='collect') {
    draw(elapsed.value/4000)
    if(elapsed.value>=(reduced?300:4000)) changePhase('invite')
  } else if(phase.value==='invite' && elapsed.value>=(reduced?300:1800)) {
    if(reduced){void enter();return}
    changePhase('depart')
  } else if(phase.value==='depart') {
    draw(1 + progress.value*.5)
    if(elapsed.value>=passageDuration){void enter();return}
  } else if(phase.value==='flip' && elapsed.value>=1200) changePhase('arrive')
  else if(phase.value==='arrive' && elapsed.value>=arrivalDuration){completeRestJourney(true);return}
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
      <div class="entry-turn" :style="{ transform:turn }" aria-hidden="true">
        <div class="entry-front"><div ref="snapshot" class="entry-snapshot"></div><canvas ref="canvas"></canvas></div>
        <div class="entry-back"><div class="entry-sun"></div><div class="entry-hill"></div><div class="entry-water"></div><div class="entry-breeze"><span></span><span></span><span></span></div></div>
      </div>
      <div v-if="phase==='invite' || phase==='depart' || phase==='arrive'" class="entry-crew">
        <div v-if="phase==='arrive'" class="arrival-door" aria-hidden="true"></div>
        <p class="crew-speech" role="status"><small v-if="phase!=='invite'">{{ companionNames[reacting>=0 ? reacting : speaker] }}</small>{{ speech }}</p>
        <RestCompanions :welcoming="phase==='invite'" :reduced="reduced" :journey="phase==='depart' || phase==='arrive' ? phase : undefined" :progress="progress" :paused="paused" :reacting="reacting" @greet="greet" />
      </div>
      <p v-if="phase==='depart' || phase==='arrive'" class="entry-interaction">{{ phase==='depart' ? '轻轻点一下伙伴，和它打个招呼' : '先感受阳光、空气和风，再一起呼吸' }}</p>
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
.entry-turn { transform-style:preserve-3d; }
.entry-front,.entry-back { backface-visibility:hidden; }.entry-back { transform:rotateY(180deg);background:radial-gradient(ellipse at 51% 48%,#fbfaf0 0,#f6f6ed 58%,#eef0e4 100%); }.entry-sun { position:absolute;right:13%;top:20%;width:clamp(90px,12vw,132px);aspect-ratio:1;border-radius:50%;background:radial-gradient(circle at 38% 35%,#fff9dd 0,#f7e6b6 53%,#f0ddb0 73%,transparent 74%);opacity:.8;box-shadow:0 0 50px #f7e6b655; }.entry-hill { position:absolute;bottom:-15%;left:-10%;width:120%;height:40%;border-radius:50%;background:linear-gradient(165deg,#e7eddc,#dbe6d6); }
.entry-crew { position:absolute;left:50%;top:48%;transform:translateX(-50%);z-index:2; }.arrive .entry-crew { top:43%; }
.entry-water { position:absolute;left:18%;right:18%;top:55%;height:19%;border-radius:50%;background:radial-gradient(ellipse,#e2eee7,#dcebe6 70%,transparent); }
.entry-breeze { position:absolute;inset:0; }.entry-breeze span { position:absolute;left:14%;top:30%;width:100px;height:20px;border-top:1px solid #8fab9d;border-radius:50%;opacity:.25; }.entry-breeze span:nth-child(2) { left:72%;top:47%;width:75px; }.entry-breeze span:nth-child(3) { left:23%;top:69%;width:60px; }
.arrival-door { position:absolute;left:50%;top:-120px;width:94px;height:128px;border:1px solid #acb9d055;border-radius:50%;transform:translateX(-50%);background:radial-gradient(ellipse,#d8e2d9,#e8efe9 60%,transparent 70%);box-shadow:0 0 30px #cddfd366; }
.entry-interaction { position:absolute;top:76%;left:18px;right:18px;text-align:center;font-size:13px;color:#c5c1d7; }.arrive .entry-interaction { color:#526960; }
.crew-speech small { display:block;margin-bottom:7px;font-size:11px;color:#74867c; }.depart .crew-speech,.arrive .crew-speech { bottom:clamp(250px,31vh,330px);min-height:68px;line-height:1.6; }
.crew-speech { position:absolute;bottom:clamp(250px,31vh,330px);left:50%;transform:translateX(-50%);width:min(370px,calc(100vw - 36px));padding:15px 20px;border:1px solid #ffffffb8;border-radius:22px;text-align:center;background:linear-gradient(145deg,#fffefaed,#fffdf5f5);box-shadow:0 12px 36px #080a1b30,0 2px 8px #ffffff30 inset;backdrop-filter:blur(12px);color:#273c3b;font-size:15px;line-height:1.65; }.crew-speech:after { content:'';position:absolute;left:50%;bottom:-8px;width:16px;height:16px;transform:rotate(45deg);background:#fffdf5;border-right:1px solid #ffffffb8;border-bottom:1px solid #ffffffb8; }
.entry-message { position:absolute;bottom:16%;left:50%;transform:translateX(-50%);width:max-content;max-width:calc(100% - 36px);padding:12px 20px;border-radius:26px;background:#0d0e1ccc;text-align:center;color:#efedf9;font-size:21px;letter-spacing:2px; }
button { font:inherit;cursor:pointer;border:1px solid #dce3dc;border-radius:24px;background:#fff;color:#273c3b;padding:12px 19px;min-height:44px; }.entry-skip { position:absolute;right:26px;bottom:25px;font-size:12px;z-index:4; }
.entry-error,.entry-paused { position:absolute;left:50%;top:20%;transform:translateX(-50%);width:min(420px,calc(100% - 36px));padding:24px;background:#fff;color:#273c3b;border-radius:20px;text-align:center;z-index:3; }.entry-paused { top:10%; }
button:focus-visible { outline:2px solid #8b87d8;outline-offset:4px; }
.reduced .entry-turn,.reduced .entry-crew { transition:none; }.reduced .entry-snapshot { opacity:0; }
:global([data-theme='dark']) .entry-back { background:#202b2a; }:global([data-theme='dark']) .entry-hill { background:#334b42; }
:global([data-theme='dark']) .entry-water { background:#293e36; }:global([data-theme='dark']) .arrive .entry-interaction { color:#b9cbbf; }
.reduced .entry-back { transform:none; }.reduced.arrive .entry-front { visibility:hidden; }.reduced .arrival-door { display:none; }
.arrive .entry-back { transform:none; }.arrive .entry-front { visibility:hidden; }
@media(max-width:600px) { .entry-message { font-size:16px;bottom:17%; }.entry-crew { top:51%; }.crew-speech { bottom:clamp(225px,30vh,285px);font-size:14px;padding:13px 16px; }.arrive .entry-crew { top:46%; }.entry-skip { right:16px;bottom:20px; } }
</style>
