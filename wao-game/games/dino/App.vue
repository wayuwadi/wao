<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
} from "vue";
import {
  createRun,
  distance,
  nextObstacle,
  pauseRun,
  queueJump,
  shapes,
  step,
  PLAYER_X,
  METRE,
  type Mode,
} from "./engine";
import { DinoLoop } from "./loop";
import { DinoInput } from "./input";
import { DinoAudio } from "./audio";
import { renderDino } from "./render";
import { finishMemory, freshMemory } from "./save";
import { envelope, parentCommand, type ChildEvent } from "../../protocol/dino";

const arena = ref<HTMLElement>(),
  canvas = ref<HTMLCanvasElement>();
const run = shallowRef(createRun("practice", Date.now()));
const mode = ref<Mode>("practice"),
  phase = ref(run.value.phase),
  metres = ref(0),
  cleared = ref(0),
  hint = ref(""),
  ahead = ref("");
const best = ref(freshMemory()),
  ducking = ref(false),
  initialized = ref(window.parent === window);
const pauseReason = ref("歇一口气，准备好再继续。"),
  theme = ref("light");
const instanceId = new URLSearchParams(location.search).get("instance") ?? "";
const embedded = window.parent !== window;
let sequence = 0,
  received = 0,
  input: DinoInput | undefined,
  loop: DinoLoop | undefined,
  observer: ResizeObserver | undefined;
let reducedMotion = false,
  savedRun = false;
const audio = new DinoAudio();
const resultTitle = computed(() =>
  phase.value === "won"
    ? mode.value === "practice"
      ? "动作都学会了！"
      : "一千米，跑到了！"
    : "再跑一次，就会更远。",
);
const labels = {
  practice: "六步练习",
  sprint: "1000 米挑战",
  endless: "无尽荒野",
};
function emit(event: ChildEvent) {
  if (embedded)
    window.parent.postMessage(
      envelope(event, instanceId, ++sequence),
      location.origin,
    );
}
function snapshot() {
  emit({ type: "checkpoint", payload: { ...best.value } });
}
function draw() {
  if (canvas.value) renderDino(canvas.value, run.value, reducedMotion);
  metres.value = distance(run.value);
  cleared.value = run.value.cleared;
  ducking.value = !!input?.state.duck;
  const next = nextObstacle(run.value);
  ahead.value = next
    ? `前方 ${shapes[next.kind].label} · ${Math.max(0, Math.ceil((next.at - run.value.travel - PLAYER_X) / METRE))} 米`
    : "前方道路畅通";
  hint.value = next
    ? `${shapes[next.kind].label} · ${shapes[next.kind].tip}`
    : "最后一步，跑过终点";
}
function setPhase() {
  phase.value = run.value.phase;
  emit({ type: "state", payload: { phase: phase.value } });
}
function update() {
  const s = run.value,
    jumps = s.jumps,
    landings = s.landings,
    passed = s.cleared;
  step(s, input?.state ?? { jumpHeld: false, duck: false });
  if (s.jumps > jumps) audio.play("jump");
  if (s.landings > landings) audio.play("land");
  if (s.cleared > passed) audio.play("pass");
  if (s.phase === "crashed" || s.phase === "won") {
    if (!savedRun) {
      best.value = finishMemory(best.value, s);
      snapshot();
      savedRun = true;
    }
    input?.clear();
    setPhase();
    audio.play(s.phase === "won" ? "win" : "hit");
    return false;
  }
  return s.phase === "running";
}
function pause(reason = "歇一口气，准备好再继续。") {
  if (run.value.phase !== "running") return;
  pauseReason.value = reason;
  pauseRun(run.value);
  input?.clear();
  loop?.stop();
  audio.stop();
  setPhase();
  draw();
}
async function focus() {
  await nextTick();
  arena.value?.focus({ preventScroll: true });
}
function start() {
  if (!initialized.value) return;
  loop?.stop();
  input?.clear();
  savedRun = false;
  run.value = createRun(
    mode.value,
    crypto.getRandomValues(new Uint32Array(1))[0],
    Math.max(600, canvas.value?.clientWidth ?? 1000),
  );
  run.value.phase = "running";
  setPhase();
  draw();
  void audio.unlock();
  loop?.start();
  void focus();
}
function resume() {
  if (run.value.phase !== "paused") return;
  input?.clear();
  run.value.phase = "running";
  setPhase();
  void audio.unlock();
  loop?.start();
  void focus();
}
function menu() {
  loop?.stop();
  input?.clear();
  audio.stop();
  run.value = createRun(mode.value, Date.now(), run.value.width);
  setPhase();
  draw();
}
function jumpDown(e: PointerEvent) {
  if (phase.value !== "running") return;
  e.preventDefault();
  (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  void focus();
  void audio.unlock();
  input?.pressJump();
}
function release() {
  input?.releaseJump();
}
function duck() {
  ducking.value = input?.toggleDuck() ?? false;
  void focus();
  draw();
}
function toggleAudio() {
  audio.muted = !audio.muted;
  best.value = { ...best.value, muted: audio.muted };
  if (audio.muted) audio.stop();
  else void audio.unlock();
  snapshot();
  void focus();
}
function receive(e: MessageEvent) {
  if (
    e.origin !== location.origin ||
    e.source !== window.parent ||
    !parentCommand(e.data, instanceId) ||
    e.data.seq <= received
  )
    return;
  received = e.data.seq;
  const event = e.data;
  if (event.type === "init" && !initialized.value) {
    best.value = { ...event.payload.memory };
    audio.muted = best.value.muted;
    theme.value = event.payload.theme;
    initialized.value = true;
    mode.value = best.value.practiceComplete ? "endless" : "practice";
    menu();
  } else if (event.type === "pause") pause(event.payload.reason);
  else if (event.type === "resume") resume();
  else if (event.type === "theme") {
    theme.value = event.payload.theme;
    draw();
  } else if (event.type === "viewport") {
    run.value.width = Math.max(600, event.payload.width);
    draw();
  } else if (event.type === "audio") {
    audio.muted = event.payload.muted;
    best.value = { ...best.value, muted: audio.muted };
    if (audio.muted) audio.stop();
  } else if (event.type === "snapshot-request")
    emit({ type: "snapshot-result", payload: { ...best.value } });
  else if (event.type === "dispose") {
    pause();
    input?.dispose();
    loop?.stop();
    audio.dispose();
    emit({ type: "disposed", payload: null });
  }
}
onMounted(() => {
  reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  loop = new DinoLoop(update, draw);
  input = new DinoInput(
    arena.value!,
    () => run.value.phase === "running",
    () => queueJump(run.value),
    () => pause("已暂停：离开游戏或失去焦点。"),
    () => {
      if (phase.value !== "running") start();
    },
  );
  observer = new ResizeObserver(() => {
    run.value.width = Math.max(600, canvas.value?.clientWidth ?? 1000);
    draw();
  });
  observer.observe(arena.value!);
  window.addEventListener("message", receive);
  draw();
  emit({ type: "ready", payload: { rulesVersion: 1 } });
});
onBeforeUnmount(() => {
  input?.dispose();
  loop?.stop();
  audio.dispose();
  observer?.disconnect();
  window.removeEventListener("message", receive);
});
</script>

<template>
  <main class="dino-game" :data-theme="theme">
    <header class="toolbar">
      <div class="identity">
        <span class="symbol" aria-hidden="true">↗</span>
        <div>
          <strong>恐龙荒野</strong><small>{{ labels[mode] }}</small>
        </div>
      </div>
      <div class="tools">
        <button :aria-pressed="!best.muted" @click="toggleAudio">
          音效{{ best.muted ? "关" : "开" }}
        </button>
        <button v-if="phase === 'running'" @click="pause()">暂停</button>
        <button v-else-if="phase === 'paused'" @click="resume">继续</button>
        <button v-if="phase !== 'ready'" @click="menu">返回</button>
      </div>
    </header>
    <section
      ref="arena"
      class="arena"
      tabindex="0"
      aria-label="恐龙跑道，空格或上键跳跃，下键下蹲，P暂停"
    >
      <canvas
        ref="canvas"
        aria-label="珊瑚恐龙跑过仙人掌与飞鸟"
        @pointerdown="jumpDown"
        @pointerup="release"
        @pointercancel="release"
        @lostpointercapture="release"
      />
      <div class="scorebar" aria-live="off">
        <span>{{
          mode === "practice" ? `练习 ${cleared} / 6` : `${metres} 米`
        }}</span
        ><span>最远 {{ best.best }} 米</span>
      </div>
      <div v-if="phase === 'running'" class="lesson" aria-live="off">
        {{ mode === "practice" ? hint : ahead }}
      </div>
      <div v-if="phase === 'ready'" class="curtain">
        <div class="welcome">
          <p class="eyebrow">一跃，去荒野</p>
          <h1>把风留在身后。</h1>
          <p>轻点跳，按住跳得更高。<br />遇到中飞鸟，低下头就好。</p>
          <label class="mode-label"
            >选择旅程<select v-model="mode">
              <option value="practice">六步练习 · 学会跳与蹲</option>
              <option value="endless">无尽荒野 · 跑得更远</option>
              <option value="sprint">1000 米挑战 · 穿过昼夜</option>
            </select></label
          >
          <button class="primary" :disabled="!initialized" @click="start">
            {{ initialized ? "出发 ↗" : "读取游戏记忆…" }}
          </button>
          <small>练习不计纪录 · 正式短局只保存成绩，不保存中途位置</small>
        </div>
      </div>
      <div v-else-if="phase === 'paused'" class="curtain paused">
        <div class="panel">
          <p class="eyebrow">风也歇一会儿</p>
          <h2>已暂停</h2>
          <p>{{ pauseReason }}</p>
          <div class="panel-actions">
            <button class="primary" @click="resume">继续跑</button
            ><button @click="start">重新开始</button
            ><button @click="menu">返回选择</button>
          </div>
          <small>重开或返回会结束这一局；已结算纪录保留。</small>
        </div>
      </div>
      <div
        v-else-if="phase === 'crashed' || phase === 'won'"
        class="curtain result"
      >
        <div class="panel">
          <p class="eyebrow">
            {{
              phase === "won"
                ? "旅程完成"
                : "碰到了 " +
                  (run.collision ? shapes[run.collision].label : "障碍")
            }}
          </p>
          <h2>{{ resultTitle }}</h2>
          <p v-if="phase === 'crashed' && run.collision">
            {{ shapes[run.collision].tip }}。准备好了就再来。
          </p>
          <div class="result-stat">
            <strong>{{ metres }}<small>米</small></strong
            ><span>{{ cleared }} 个障碍 · {{ run.elapsed.toFixed(1) }} 秒</span>
          </div>
          <div class="panel-actions">
            <button class="primary" @click="start">再跑一次 ↗</button
            ><button @click="menu">换个旅程</button>
          </div>
          <small>{{
            mode === "practice"
              ? "练习完成情况已交给游戏记忆"
              : "纪录已交给游戏记忆 · R 快速重开"
          }}</small>
        </div>
      </div>
    </section>
    <footer class="controls">
      <p>
        <kbd>空格 / ↑</kbd> 跳跃 <kbd>↓</kbd> 下蹲
        <span>长按跳高 · 松开短跳</span>
      </p>
      <div class="touch">
        <button
          :aria-pressed="ducking"
          :disabled="phase !== 'running'"
          @pointerdown.prevent="duck"
          @click="$event.detail === 0 && duck()"
        >
          {{ ducking ? "起身 ↑" : "下蹲 ↓" }}</button
        ><button
          class="jump"
          :disabled="phase !== 'running'"
          @pointerdown="jumpDown"
          @pointerup="release"
          @pointercancel="release"
          @lostpointercapture="release"
        >
          跳跃 ↗
        </button>
      </div>
    </footer>
    <p v-if="!embedded" class="standalone-note">
      独立开发预览 · 本页纪录仅在本次保留，正式页面由游戏记忆保存。
    </p>
  </main>
</template>

<style>
* {
  box-sizing: border-box;
}
body {
  margin: 0;
  font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif;
  color: #593d42;
  background: #fff6ed;
}
button,
select {
  font: inherit;
}
button {
  cursor: pointer;
  border: 1px solid #cf9f93;
  border-radius: 13px;
  padding: 10px 16px;
  background: #fff7ed;
  color: #673e43;
  min-height: 44px;
  font-weight: 650;
}
button:hover {
  background: #ffe3d0;
}
button:disabled {
  opacity: 0.45;
  cursor: default;
}
button:focus-visible,
select:focus-visible {
  outline: 3px solid #ba353f;
  outline-offset: 3px;
}
.dino-game {
  max-width: 1400px;
  margin: auto;
}
.toolbar {
  padding: 14px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.identity {
  display: flex;
  gap: 10px;
  align-items: center;
}
.identity strong {
  display: block;
  font-size: 19px;
  letter-spacing: 1px;
}
.identity small {
  display: block;
  font-size: 11px;
  margin-top: 3px;
  color: #926e67;
}
.symbol {
  background: #f96c60;
  border-radius: 12px;
  width: 37px;
  height: 37px;
  display: grid;
  place-items: center;
  color: #fff4d5;
  font-size: 27px;
}
.tools {
  display: flex;
  gap: 6px;
}
.tools button {
  padding: 8px 12px;
  font-size: 12px;
}
.arena {
  position: relative;
  outline: none;
  isolation: isolate;
  background: #ffdbc7;
}
.arena:focus-visible {
  box-shadow: inset 0 0 0 3px #d8444b;
}
.arena canvas {
  display: block;
  width: 100%;
  height: 380px;
  touch-action: none;
}
.scorebar {
  position: absolute;
  left: 24px;
  right: 24px;
  top: 18px;
  display: flex;
  justify-content: space-between;
  pointer-events: none;
  font-weight: 750;
  font-size: 14px;
  color: #643742;
  text-shadow: 0 1px #fff0d9;
  background: #fff4daaa;
  padding: 7px 12px;
  border-radius: 9px;
}
.lesson {
  position: absolute;
  top: 62px;
  left: 50%;
  transform: translateX(-50%);
  white-space: nowrap;
  background: #fff9e5e8;
  padding: 8px 14px;
  border-radius: 9px;
  font-size: 13px;
  font-weight: 600;
}
.curtain {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: #fff1df25;
}
.welcome {
  margin-left: auto;
  width: 58%;
  padding-left: 10px;
}
.eyebrow {
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 3px;
  color: #b75550;
  margin: 0 0 9px;
}
h1 {
  font-size: clamp(25px, 3.4vw, 40px);
  margin: 0 0 12px;
  letter-spacing: -1px;
}
p {
  line-height: 1.65;
}
.welcome > p:not(.eyebrow) {
  font-size: 13px;
  margin: 0 0 16px;
}
.mode-label {
  display: block;
  font-size: 11px;
  font-weight: 700;
  margin-bottom: 12px;
}
.mode-label select {
  display: block;
  width: min(310px, 100%);
  background: #fffaf0;
  border: 1px solid #d4a297;
  padding: 11px;
  border-radius: 10px;
  color: #65383e;
  margin-top: 5px;
}
.primary {
  background: #e85551;
  border-color: #e85551;
  color: #fff8e7;
  min-width: 120px;
}
.primary:hover {
  background: #cd4145;
  color: #fff;
}
small {
  line-height: 1.6;
}
.welcome > small {
  display: block;
  margin-top: 10px;
  font-size: 10px;
  color: #805b58;
}
.paused {
  background: #43253644;
  backdrop-filter: blur(3px);
}
.panel {
  padding: 24px 28px;
  background: #fff6eafa;
  border: 1px solid #e7b0a0;
  border-radius: 22px;
  box-shadow: 0 12px 40px #672b361c;
  text-align: center;
  max-width: 550px;
}
.panel h2 {
  font-size: 25px;
  margin: 7px 0;
}
.panel p:not(.eyebrow) {
  font-size: 13px;
  margin: 8px 0 17px;
}
.panel small {
  font-size: 10px;
  color: #805b58;
  display: block;
  margin-top: 13px;
}
.panel-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
  flex-wrap: wrap;
}
.result .panel {
  margin-left: 15%;
  padding: 20px 28px;
}
.result-stat {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 16px;
  margin: 13px;
}
.result-stat strong {
  font-size: 40px;
  letter-spacing: -2px;
}
.result-stat strong small {
  display: inline;
  margin-left: 4px;
  font-size: 12px;
  letter-spacing: 0;
}
.result-stat span {
  font-size: 11px;
  color: #8e6862;
}
.controls {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 20px;
  gap: 12px;
}
.controls p {
  font-size: 11px;
  margin: 0;
  color: #846562;
}
.controls p span {
  display: block;
  margin-top: 5px;
}
kbd {
  background: #f4e1d5;
  border-radius: 4px;
  padding: 3px 5px;
  font-size: 10px;
}
.touch {
  display: flex;
  gap: 10px;
}
.touch button {
  min-width: 100px;
  min-height: 50px;
  touch-action: none;
  user-select: none;
}
.touch .jump {
  background: #f56559;
  border-color: #f56559;
  color: #fff5da;
}
.touch [aria-pressed="true"] {
  background: #663a45;
  color: #fff3d7;
}
.standalone-note {
  font-size: 11px;
  text-align: center;
  color: #977771;
}
.dino-game[data-theme="dark"] {
  background: #382b35;
  color: #ffe5d0;
}
.dino-game[data-theme="dark"] .controls p,
.dino-game[data-theme="dark"] .identity small {
  color: #efbca9;
}
@media (max-width: 600px) {
  .toolbar {
    padding: 10px 12px;
  }
  .identity strong {
    font-size: 16px;
  }
  .symbol {
    display: none;
  }
  .tools button {
    padding: 8px 10px;
  }
  .arena canvas {
    height: 340px;
  }
  .scorebar {
    left: 10px;
    right: 10px;
    top: 10px;
    font-size: 12px;
  }
  .curtain {
    padding: 12px;
  }
  .welcome {
    width: 75%;
    padding-left: 20px;
  }
  .welcome > p:not(.eyebrow) {
    font-size: 12px;
    margin-bottom: 10px;
  }
  .welcome > small {
    font-size: 9px;
    max-width: 210px;
  }
  .welcome h1 {
    font-size: 26px;
  }
  .mode-label select {
    font-size: 11px;
    padding: 9px;
  }
  .welcome .eyebrow {
    margin-top: 18px;
  }
  .panel {
    padding: 18px 16px;
    width: 100%;
  }
  .panel h2 {
    font-size: 21px;
  }
  .result .panel {
    margin-left: 0;
    padding: 18px;
  }
  .result-stat {
    margin: 10px;
  }
  .result-stat strong {
    font-size: 34px;
  }
  .controls {
    display: block;
    padding: 10px 12px;
  }
  .controls p {
    text-align: center;
    font-size: 10px;
    margin-bottom: 10px;
  }
  .controls p span {
    display: inline;
    margin-left: 8px;
  }
  .touch button {
    width: 50%;
    min-height: 56px;
  }
  .touch {
    gap: 12px;
  }
  .lesson {
    top: 52px;
    font-size: 11px;
    padding: 7px 10px;
  }
  .panel-actions button {
    padding: 8px 13px;
    font-size: 12px;
  }
}
</style>
