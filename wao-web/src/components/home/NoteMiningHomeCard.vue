<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { fetchNoteMining, type NoteMiningResult } from '@/api/noteMining'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const result = ref<NoteMiningResult>({ job: null, artifacts: [] })
const loading = ref(true)
const error = ref('')
let timer: ReturnType<typeof setInterval> | undefined
let version = 0

const focus = computed(() => result.value.artifacts.find(item => item.type === 'INSIGHT' && item.feedbackStatus !== 'NOT_ACCURATE')
  ?? result.value.artifacts.find(item => item.feedbackStatus !== 'NOT_ACCURATE') ?? null)
const visual = computed(() => result.value.visuals?.find(item => item.status === 'DONE' && item.feedbackStatus === 'SAVED')
  ?? result.value.visuals?.find(item => item.status === 'DONE' && item.feedbackStatus !== 'NOT_ACCURATE') ?? null)
const running = computed(() => ['QUEUED', 'RUNNING'].includes(result.value.job?.status ?? ''))
const title = computed(() => {
  if (loading.value) return '正在翻阅近期小记…'
  if (focus.value) return focus.value.content
  if (error.value) return '暂时没能读到挖掘结果'
  if (running.value) return 'AI 正在连接小记里的线索'
  return '让过去的小记，长出新的内容'
})

async function load() {
  if (!session.userId) { loading.value = false; result.value = { job: null, artifacts: [] }; return }
  const current = ++version
  try {
    const next = await fetchNoteMining()
    if (current === version) { result.value = next; error.value = '' }
  } catch (cause) {
    if (current === version) error.value = cause instanceof Error ? cause.message : '读取失败'
  } finally { if (current === version) loading.value = false }
}

watch(() => session.userId, load)
onMounted(() => {
  void load()
  timer = setInterval(() => { if (!document.hidden) void load() }, 60_000)
})
onUnmounted(() => { if (timer) clearInterval(timer); version++ })
</script>

<template>
  <section class="mining-home-card" aria-label="AI 挖掘小记">
    <div class="signal-scene" aria-hidden="true">
      <img v-if="visual?.imageUrl" class="visual-preview" :src="visual.imageUrl" alt="">
      <template v-else><span class="note note-a"></span><span class="note note-b"></span><span class="thread"></span>
        <i class="ri-sparkling-2-line"></i></template>
    </div>
    <div class="mining-copy">
      <div class="kicker"><span class="pulse"></span> AI 挖掘小记 <span>/</span> 内容正在生长</div>
      <strong>{{ title }}</strong>
      <p v-if="error">{{ error }}{{ focus ? ' · 仍显示上次结果' : '' }} · <button type="button" @click="load">重试</button></p>
      <p v-else-if="running">基于 {{ result.job?.sourceCount }} 篇小记分析中；可以继续做别的事。</p>
      <p v-else-if="visual">{{ visual.caption }} · {{ visual.sources.length }} 条来源可回看</p>
      <p v-else-if="focus">{{ focus.rationale }} · {{ focus.sources.length }} 条来源可回看</p>
      <p v-else>从近期小记中创造洞察、提醒草稿和文章种子；原文始终保留。</p>
    </div>
    <a class="open-mining" href="/notes">
      {{ focus || visual ? '查看内容' : running ? '查看进度' : '开始挖掘' }} <i class="ri-arrow-right-line"></i>
    </a>
  </section>
</template>

<style scoped>
.mining-home-card { min-width: 0; min-height: 150px; display: grid; grid-template-columns: 96px minmax(0, 1fr) auto; align-items: center; gap: 16px; padding: 16px 20px 16px 12px; color: #302b47; border: 1px solid #e2d9fa; border-radius: 18px; background: radial-gradient(circle at 10% 40%, #f0eaff 0, #faf8ff 42%, #fff 75%); position: relative; overflow: hidden; }
.mining-home-card:before { content: ''; position: absolute; inset: 0 auto 0 0; width: 4px; background: linear-gradient(#7958da, #c595e8); }
.signal-scene { position: relative; width: 90px; height: 94px; display: grid; place-items: center; }
.visual-preview { width: 90px; height: 94px; object-fit: cover; border-radius: 10px; }
.note { position: absolute; width: 43px; height: 52px; border: 1px solid #cbbbea; border-radius: 9px; background: #fff; box-shadow: 0 9px 18px #4d327226; }
.note-a { transform: translate(-16px, 7px) rotate(-12deg); }
.note-b { transform: translate(16px, -5px) rotate(11deg); background: #eee8fc; }
.thread { width: 58px; height: 34px; border: 1.5px dashed #9e80dc; border-left-color: transparent; border-bottom-color: transparent; border-radius: 50%; transform: rotate(10deg); }
.signal-scene i { position: absolute; display: grid; place-items: center; width: 35px; height: 35px; border-radius: 50%; color: #fff; background: #7858d6; box-shadow: 0 6px 16px #6c4dca42; }
.mining-copy { min-width: 0; }
.kicker { display: flex; align-items: center; gap: 7px; color: #735bb0; font-size: 11px; font-weight: 750; }
.kicker > span:not(.pulse) { color: #b6aacd; }
.pulse { width: 7px; height: 7px; border-radius: 50%; background: #9a70e8; box-shadow: 0 0 0 4px #eadffc; }
.mining-copy strong { display: -webkit-box; overflow: hidden; -webkit-line-clamp: 2; -webkit-box-orient: vertical; margin-top: 10px; font-size: 18px; line-height: 1.4; }
.mining-copy p { margin: 6px 0 0; color: #817891; font-size: 12px; line-height: 1.55; }
.mining-copy p button { border: 0; background: transparent; padding: 0; color: #6d50c4; font-weight: 700; cursor: pointer; }
.open-mining { display: flex; align-items: center; gap: 7px; border: 1px solid #d8c9f4; border-radius: 10px; padding: 10px 12px; background: #fff; color: #6848c0; text-decoration: none; white-space: nowrap; font-size: 12px; font-weight: 750; transition: background .18s, transform .18s; }
.open-mining:hover { background: #f6f1ff; transform: translateX(2px); }
@media (max-width: 760px) { .mining-home-card { grid-template-columns: 62px minmax(0, 1fr); gap: 10px; min-height: 0; padding: 14px; } .signal-scene { width: 60px; height: 70px; transform: scale(.72); } .mining-copy strong { font-size: 15px; margin-top: 7px; } .open-mining { grid-column: 2; justify-self: start; padding: 7px 10px; } }
@media (prefers-reduced-motion: reduce) { .open-mining { transition: none; } .open-mining:hover { transform: none; } }
</style>
