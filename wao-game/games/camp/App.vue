<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref } from "vue";
import MapBoard from "./MapBoard.vue";
import {
  bosses,
  enemies,
  maps,
  towerCatalog,
  towerKinds,
  waveRoster,
  type TowerKind,
  type Point,
} from "./catalog";
import {
  advance,
  castBlizzard,
  castRally,
  settle,
  startBattle,
  stats,
} from "./engine";
import {
  beginRun,
  build,
  clone,
  freshCampMemory,
  invested,
  priority,
  sell,
  upgrade,
  upgradeCost,
} from "./save";
import type { Battle, CampMemory, CampRun, Priority } from "./model";
import { CampAudio } from "./audio";
import { envelope, parentCommand, type ChildEvent } from "../../protocol/camp";
const embedded = window.parent !== window,
  instance = new URLSearchParams(location.search).get("instance") ?? "";
const memory = ref(freshCampMemory()),
  initialized = ref(!embedded),
  dark = ref(false),
  menu = ref(true),
  mapChoice = ref(0),
  mode = ref<CampRun["mode"]>("campaign"),
  battle = ref<Battle | null>(null),
  paused = ref(false),
  pauseReason = ref(""),
  selected = ref(0),
  aiming = ref(false),
  confirmNew = ref(false),
  message = ref(""),
  arena = ref<HTMLElement>();
const audio = new CampAudio();
let seq = 0,
  received = 0,
  frame = 0,
  last = 0,
  lifecycle = 0,
  nextSound = 0,
  readyTimer: ReturnType<typeof setInterval> | undefined;
const run = computed(() => battle.value?.run ?? memory.value.run),
  active = computed(() => battle.value?.phase === "running"),
  map = computed(() => maps[run.value?.map ?? mapChoice.value]!),
  tower = computed(() =>
    run.value?.towers.find((t) => t.pad === selected.value),
  );
const nextEnemies = computed(() =>
  run.value
    ? waveRoster(run.value.map, run.value.wave, run.value.mode === "endless")
    : [],
);
const threats = computed(() =>
  [...new Set(nextEnemies.value)].map((k) => ({
    id: k,
    name: Object.hasOwn(bosses, k)
      ? bosses[k as keyof typeof bosses].name
      : enemies[k as keyof typeof enemies].name,
    count: nextEnemies.value.filter((x) => x === k).length,
  })),
);
const ended = computed(() => battle.value && battle.value.phase !== "running");
const waveBoss = computed(() =>
  nextEnemies.value.find((k) => Object.hasOwn(bosses, k)),
);
function send(e: ChildEvent) {
  if (embedded)
    window.parent.postMessage(envelope(e, instance, ++seq), location.origin);
}
function save() {
  send({ type: "checkpoint", payload: clone(memory.value) });
}
function publish() {
  send({
    type: "state",
    payload: {
      phase: !battle.value
        ? "ready"
        : active.value
          ? paused.value
            ? "paused"
            : "running"
          : battle.value.phase === "lost"
            ? "crashed"
            : "won",
    },
  });
}
function stop() {
  lifecycle++;
  cancelAnimationFrame(frame);
  frame = 0;
  last = 0;
  audio.stop();
}
function pause(reason = "已暂停，可查看防线、投资与选择技能目标。") {
  stop();
  if (active.value) paused.value = true;
  pauseReason.value = reason;
  publish();
}
function finish() {
  const b = battle.value;
  if (!b || b.phase === "running") return;
  cancelAnimationFrame(frame);
  frame = 0;
  last = 0;
  lifecycle++;
  aiming.value = false;
  paused.value = false;
  memory.value = settle(memory.value, b);
  audio.play(b.phase === "won" ? "win" : "lose");
  save();
  publish();
}
function loop(now: number) {
  if (!active.value || paused.value) return;
  const elapsed = last ? Math.min((now - last) / 1000, 0.08) : 0;
  last = now;
  for (let i = 0; i < memory.value.speed; i++) advance(battle.value!, elapsed);
  if (!active.value) {
    finish();
    return;
  }
  if (
    now >= nextSound &&
    battle.value!.effects.some((e) => e.kind === "shot" || e.kind === "blast")
  ) {
    audio.play("hit");
    nextSound = now + 280;
  }
  frame = requestAnimationFrame(loop);
}
async function resume() {
  if (!active.value) return;
  stop();
  paused.value = false;
  const ticket = lifecycle;
  await nextTick();
  arena.value?.focus({ preventScroll: true });
  await audio.unlock();
  if (ticket !== lifecycle) return;
  frame = requestAnimationFrame(loop);
  publish();
}
function start(force = false) {
  if (memory.value.run && !force) {
    confirmNew.value = true;
    return;
  }
  memory.value = beginRun(memory.value, mapChoice.value, mode.value);
  if (!memory.value.run) return;
  stop();
  battle.value = null;
  menu.value = false;
  selected.value = 0;
  message.value = "先点击建造点，再选择塔。波间自由调整，准备好再发波。";
  confirmNew.value = false;
  save();
  publish();
}
async function wave() {
  if (!memory.value.run || active.value) return;
  save();
  battle.value = startBattle(memory.value.run);
  message.value = "";
  aiming.value = false;
  await resume();
}
function edit(action: () => boolean, success: string) {
  if (!run.value || ended.value) return;
  if (action()) {
    message.value = success;
    audio.play("choose");
    if (!active.value) save();
    // 建造与升级会替换当前按钮；先收回焦点，避免它被移除后落到 body。
    arena.value?.focus({ preventScroll: true });
  } else message.value = "条件不足，资金和布局未改变。";
}
function add(kind: TowerKind) {
  edit(
    () => build(run.value!, selected.value, kind),
    `${selected.value + 1}号点已建造${towerCatalog[kind].name}。`,
  );
}
function improve(branch: 0 | 1) {
  edit(
    () => upgrade(run.value!, selected.value, branch),
    "升级完成。分支能力立即生效。",
  );
}
function remove() {
  edit(() => sell(run.value!, selected.value), "防御塔已出售，返还70%投入。");
}
function choosePad(pad: number) {
  selected.value = pad;
  message.value = "";
}
function setPriority(e: Event) {
  edit(
    () =>
      priority(
        run.value!,
        selected.value,
        (e.target as HTMLSelectElement).value as Priority,
      ),
    "目标优先级已改变。",
  );
}
function blizzard() {
  if (!active.value || battle.value!.blizzard) return;
  aiming.value = !aiming.value;
  if (aiming.value)
    pause("点选沙盘区域或下方建造点投下寒潮；施放后仍暂停，准备好再继续。");
}
function aim(point: Point) {
  if (!aiming.value || !battle.value) return;
  if (castBlizzard(battle.value, point)) {
    aiming.value = false;
    message.value = "寒潮已释放。";
    pauseReason.value = "寒潮已施放，准备好后继续守卫。";
    audio.play("choose");
  }
}
function rally() {
  if (battle.value && castRally(battle.value)) {
    audio.play("heal");
    arena.value?.focus({ preventScroll: true });
  }
}
function continueWave() {
  stop();
  battle.value = null;
  paused.value = false;
  aiming.value = false;
  publish();
}
function back() {
  pause();
  menu.value = true;
  aiming.value = false;
  message.value = active.value
    ? "本波开始前的布局和资金已保留；返回会从本波重新开始。"
    : "";
  battle.value = null;
  paused.value = false;
  publish();
}
function restoreRun() {
  if (memory.value.run) {
    menu.value = false;
    battle.value = null;
    selected.value = memory.value.run.towers[0]?.pad ?? 0;
    message.value = "已恢复波开始前的布局。点击开始本波继续。";
  }
}
function retry() {
  const b = battle.value;
  if (!b) return;
  mapChoice.value = b.run.map;
  mode.value = b.run.mode;
  start(true);
}
function speed() {
  memory.value.speed = memory.value.speed === 1 ? 2 : 1;
  save();
}
async function toggleAudio() {
  memory.value.muted = !memory.value.muted;
  audio.muted = memory.value.muted;
  save();
  if (audio.muted) audio.stop();
  else {
    await audio.unlock();
    audio.play("choose");
  }
}
function keys(e: KeyboardEvent) {
  const target = e.target as HTMLElement;
  if (
    target.closest("button,input,select,textarea,[contenteditable=true]") ||
    !arena.value?.contains(target) ||
    e.altKey ||
    e.ctrlKey ||
    e.metaKey ||
    e.repeat
  )
    return;
  if (e.code === "Escape") {
    e.preventDefault();
    if (aiming.value) aiming.value = false;
    else pause();
  }
  if (e.code === "Space" && active.value) {
    e.preventDefault();
    if (paused.value) void resume();
    else pause();
  }
  if (e.code === "Digit1" && active.value) {
    e.preventDefault();
    blizzard();
  }
  if (e.code === "Digit2" && active.value) {
    e.preventDefault();
    rally();
  }
}
function blur() {
  pause("焦点已离开游戏，防线已暂停。");
}
function hidden() {
  if (document.hidden) pause("页面已进入后台，防线已暂停。");
}
async function focusOut(e: FocusEvent) {
  if (e.relatedTarget && arena.value?.contains(e.relatedTarget as Node)) return;
  await nextTick();
  if (arena.value?.contains(document.activeElement)) return;
  pause("输入焦点已离开战场，防线已暂停。");
}
function receive(e: MessageEvent) {
  if (
    e.origin !== location.origin ||
    e.source !== window.parent ||
    !parentCommand(e.data, instance) ||
    e.data.seq <= received
  )
    return;
  received = e.data.seq;
  const command = e.data;
  if (command.type === "init") {
    stop();
    memory.value = clone(command.payload.memory);
    audio.muted = memory.value.muted;
    dark.value = command.payload.theme === "dark";
    initialized.value = true;
    menu.value = !memory.value.run;
    battle.value = null;
    clearInterval(readyTimer);
    publish();
  } else if (command.type === "pause") pause(command.payload.reason);
  else if (command.type === "resume") void resume();
  else if (command.type === "theme")
    dark.value = command.payload.theme === "dark";
  else if (command.type === "audio") {
    memory.value.muted = command.payload.muted;
    audio.muted = command.payload.muted;
    if (audio.muted) audio.stop();
    save();
  } else if (command.type === "snapshot-request")
    send({ type: "snapshot-result", payload: clone(memory.value) });
  else if (command.type === "dispose") {
    stop();
    audio.dispose();
    clearInterval(readyTimer);
    send({ type: "disposed", payload: null });
  }
}
onMounted(() => {
  window.addEventListener("message", receive);
  window.addEventListener("blur", blur);
  document.addEventListener("visibilitychange", hidden);
  if (embedded) {
    send({ type: "ready", payload: { rulesVersion: 1 } });
    readyTimer = setInterval(() => {
      if (!initialized.value)
        send({ type: "ready", payload: { rulesVersion: 1 } });
    }, 700);
  }
});
onBeforeUnmount(() => {
  stop();
  audio.dispose();
  clearInterval(readyTimer);
  window.removeEventListener("message", receive);
  window.removeEventListener("blur", blur);
  document.removeEventListener("visibilitychange", hidden);
});
</script>
<template>
  <main
    ref="arena"
    class="camp-game"
    :class="{ dark }"
    tabindex="0"
    @keydown="keys"
    @focusout="focusOut"
  >
    <div v-if="!initialized" class="loading">营火正在点亮…</div>
    <template v-else>
      <header>
        <div>
          <span class="eyebrow">WAO · KEEP A LITTLE LIGHT</span>
          <h1>最后一座营地 <small>为彼此，留一盏灯。</small></h1>
        </div>
        <div class="header-actions">
          <button @click="toggleAudio">
            音效{{ memory.muted ? "关" : "开" }}</button
          ><button @click="speed">{{ memory.speed }}倍速度</button
          ><button v-if="!menu" @click="back">保存回营</button>
        </div>
      </header>
      <section v-if="menu" class="camp-menu">
        <div class="intro">
          <div>
            <span class="eyebrow">CYAN SANDBOX</span>
            <h2>风会经过，灯会留下。</h2>
            <p>三条来路，五种塔。一座营地，由你守护。</p>
            <button v-if="memory.run" class="primary" @click="restoreRun">
              继续{{ maps[memory.run.map]!.name }} · 第{{ memory.run.wave }}波
            </button>
          </div>
          <svg viewBox="0 0 300 180" aria-hidden="true">
            <ellipse cx="155" cy="144" rx="112" ry="27" fill="#49858430" />
            <path d="M30 120 130 65 270 119 165 165Z" fill="#75aaa5" />
            <path d="M30 110 130 55 270 109 165 155Z" fill="#c6e0d0" />
            <path
              d="M115 113 162 35 211 113Z"
              fill="#fae6b3"
              stroke="#698d83"
              stroke-width="3"
            />
            <path d="M149 114V79H174V122Z" fill="#52787a" />
            <path
              d="M161 35V6L198 18 161 27"
              fill="#eaaa70"
              stroke="#52787a"
              stroke-width="3"
            />
            <circle cx="88" cy="116" r="10" fill="#f2be72" />
            <path
              d="M68 123 103 110M72 111 105 124"
              stroke="#856c58"
              stroke-width="5"
            />
          </svg>
        </div>
        <div class="maps">
          <button
            v-for="m in maps"
            :key="m.id"
            :disabled="m.id > 0 && !memory.wins[m.id - 1]"
            :class="{ chosen: mapChoice === m.id }"
            @click="
              mapChoice = m.id;
              mode = 'campaign';
            "
          >
            <span>0{{ m.id + 1 }} · {{ m.shape }}</span
            ><strong>{{ m.name }}</strong
            ><small
              >{{
                m.id > 0 && !memory.wins[m.id - 1]
                  ? "通关上一张地图解锁"
                  : memory.wins[m.id]
                    ? `通关${memory.wins[m.id]}次 · 最佳${memory.bestLives[m.id]}生命`
                    : "守住12波，解锁下一站"
              }}<br />无尽纪录 {{ memory.bestEndless[m.id] }} 波</small
            >
          </button>
        </div>
        <div class="depart">
          <div>
            <h3>{{ maps[mapChoice]!.name }}</h3>
            <p>{{ bosses[maps[mapChoice]!.boss].tip }}</p>
            <div class="mode-buttons">
              <button
                :class="{ chosen: mode === 'campaign' }"
                @click="mode = 'campaign'"
              >
                十二波守卫</button
              ><button
                :disabled="!memory.wins[mapChoice]"
                :class="{ chosen: mode === 'endless' }"
                @click="mode = 'endless'"
              >
                无尽挑战{{ memory.wins[mapChoice] ? "" : " · 通关解锁" }}
              </button>
            </div>
          </div>
          <button class="primary" @click="start()">布置防线 ↗</button>
        </div>
        <div v-if="confirmNew" class="notice" role="alert">
          <p>新挑战会替换当前局。已解锁地图与永久纪录保留。</p>
          <button class="primary" @click="start(true)">
            结束旧局，开始新挑战</button
          ><button @click="confirmNew = false">保留当前局</button>
        </div>
      </section>
      <template v-else-if="run">
        <div class="hud">
          <b>{{ map.name }}</b
          ><span
            >{{ run.mode === "endless" ? "无尽" : "守卫" }} · 第 {{ run.wave
            }}{{ run.mode === "campaign" ? " / 12" : "" }} 波</span
          ><span
            >营地 <strong>{{ run.lives }} / 20</strong></span
          ><span
            >补给 <strong>{{ run.gold }}</strong></span
          ><span class="spent">投入 {{ run.spent }}</span
          ><button v-if="active" @click="paused ? resume() : pause()">
            {{ paused ? "继续" : "暂停" }}</button
          ><button v-else-if="!ended" class="primary" @click="wave">
            开始本波
          </button>
        </div>
        <div v-if="paused && active" class="pause-banner" role="status">
          <span>{{ pauseReason }}</span
          ><button class="primary" @click="resume">继续守卫</button>
        </div>
        <div class="defense-layout">
          <section class="board-section">
            <MapBoard
              :run="run"
              :battle="battle"
              :selected="selected"
              :aiming="aiming"
              @select="choosePad"
              @aim="aim"
            />
            <div class="skills">
              <button
                :disabled="!active || battle?.blizzard"
                :class="{ chosen: aiming }"
                @click="blizzard"
              >
                ❄
                {{
                  aiming
                    ? "取消瞄准"
                    : battle?.blizzard
                      ? "寒潮已用"
                      : "寒潮 · 选区域"
                }}
                <small>破盾 / 冻结 · 每波1次</small></button
              ><button :disabled="!active || battle?.rally" @click="rally">
                ✦ {{ battle?.rally ? "鼓舞已用" : "营地鼓舞" }}
                <small>修补3生命 / 加速 / 解干扰</small>
              </button>
            </div>
            <div v-if="aiming" class="aim-hint">
              点击沙盘或任一编号点施放寒潮。已暂停，可以从容选择。
            </div>
            <div class="pad-picker" aria-label="选择建造点">
              <button
                v-for="(_, i) in map.pads"
                :key="i"
                :class="{ chosen: selected === i }"
                @click="aiming ? aim(map.pads[i]!) : choosePad(i)"
              >
                {{ i + 1
                }}<small>{{
                  run.towers.find((t) => t.pad === i)
                    ? towerCatalog[run.towers.find((t) => t.pad === i)!.kind]
                        .short
                    : "空"
                }}</small>
              </button>
            </div>
          </section>
          <aside class="build-panel">
            <span class="eyebrow">BUILD A LITTLE SAFETY</span>
            <h2>{{ selected + 1 }}号建造点</h2>
            <template v-if="tower"
              ><h3>
                {{ towerCatalog[tower.kind].name }} · {{ tower.level }}级
              </h3>
              <p>
                {{ towerCatalog[tower.kind].description }} · 射程{{
                  Math.round(stats(tower).range)
                }}
              </p>
              <p class="branch" v-if="tower.branch !== null">
                {{ towerCatalog[tower.kind].branches[tower.branch]
                }}<small>{{
                  towerCatalog[tower.kind].details[tower.branch]
                }}</small>
              </p>
              <label
                >优先目标<select :value="tower.priority" @change="setPriority">
                  <option value="first">最靠近营地</option>
                  <option value="strong">最强敌人</option>
                  <option value="support">治疗与护盾支援</option>
                </select></label
              >
              <div class="upgrade-options" v-if="tower.level === 1">
                <button
                  v-for="branch in [0, 1] as const"
                  :key="branch"
                  :disabled="!!ended || run.gold < upgradeCost(tower)"
                  @click="improve(branch)"
                >
                  <b>{{ towerCatalog[tower.kind].branches[branch] }}</b
                  ><small>{{ towerCatalog[tower.kind].details[branch] }}</small
                  ><span>升级 · {{ upgradeCost(tower) }}补给</span>
                </button>
              </div>
              <button
                v-else-if="tower.level === 2"
                class="primary full"
                :disabled="!!ended || run.gold < upgradeCost(tower)"
                @click="improve(tower.branch!)"
              >
                强化至3级 · {{ upgradeCost(tower) }}补给
              </button>
              <p v-else>已达本分支最高等级。</p>
              <button class="sell full" :disabled="!!ended" @click="remove">
                出售 · 返还{{ Math.floor(invested(tower) * 0.7) }}补给
              </button>
            </template>
            <div v-else class="tower-shop">
              <button
                v-for="kind in towerKinds"
                :key="kind"
                :disabled="!!ended || run.gold < towerCatalog[kind].cost"
                @click="add(kind)"
              >
                <span
                  class="tower-icon"
                  :style="{ background: towerCatalog[kind].color }"
                  >{{ towerCatalog[kind].short }}</span
                ><span
                  ><b>{{ towerCatalog[kind].name }}</b
                  ><small>{{ towerCatalog[kind].description }}</small></span
                ><strong>{{ towerCatalog[kind].cost }}</strong>
              </button>
            </div>
            <p class="save-note">
              {{
                active
                  ? "本波进行中。离开会恢复波前布局与资金，波内投入和收益一起回退。"
                  : "波间布局即时保存；准备好，再开始下一波。"
              }}
            </p>
          </aside>
        </div>
        <div v-if="ended" class="result" role="status">
          <div>
            <span class="eyebrow">{{
              battle!.phase === "lost"
                ? "CAMP LOST"
                : memory.run
                  ? "WAVE CLEAR"
                  : "CAMP SAFE"
            }}</span>
            <h2>
              {{
                battle!.phase === "lost"
                  ? "灯暂时暗了，再试一次。"
                  : memory.run
                    ? "这一波，守住了。"
                    : run.mode === "endless"
                      ? "无尽守卫极限，营火仍亮。"
                      : "十二波风雨，营火仍亮。"
              }}
            </h2>
            <p>
              击退 {{ battle!.kills }} · 漏过 {{ battle!.leaked }} · 本波
              {{ battle!.time.toFixed(1) }} 秒 · 累计投入
              {{ battle!.run.spent }}
            </p>
          </div>
          <button
            v-if="battle!.phase === 'won' && memory.run"
            class="primary"
            @click="continueWave"
          >
            布置下一波</button
          ><button
            v-else-if="battle!.phase === 'lost'"
            class="primary"
            @click="retry"
          >
            重新守卫这张图</button
          ><button @click="back">返回营地</button>
        </div>
        <div class="wave-preview">
          <b>{{ active || ended ? "本波情报" : "下一波情报" }}</b
          ><span v-for="t in threats" :key="t.id"
            >{{ t.name }} ×{{ t.count }}</span
          >
          <p v-if="waveBoss">
            {{ bosses[waveBoss as keyof typeof bosses].tip }}
          </p>
        </div>
        <details class="guide">
          <summary>敌人图鉴与操作提示</summary>
          <div class="enemy-guide">
            <p v-for="e in enemies" :key="e.name">
              <b>{{ e.name }}</b> {{ e.tip }}
            </p>
          </div>
          <p>
            点建造点→建造/升级/出售。炮塔只打地面；单点强塔与支援组合、交叉范围火力都可行。Space暂停，1寒潮，2鼓舞；快捷键仅在沙盘聚焦时生效。按住页面滚动不影响游戏输入。
          </p>
        </details>
        <details v-if="battle" class="battle-log">
          <summary>战况 · {{ battle.messages.at(-1) }}</summary>
          <p v-for="(text, i) in battle.messages" :key="i">{{ text }}</p>
        </details>
      </template>
      <p v-if="message" class="feedback" role="status">{{ message }}</p>
      <footer>
        无需登录 · 游戏记忆保留波间进度与永久纪录 · 波内离开恢复波开始前
      </footer>
    </template>
  </main>
</template>
