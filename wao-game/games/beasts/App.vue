<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import Creature from "./Creature.vue";
import {
  beastIds,
  bonds,
  regions,
  roleNames,
  routeOptions,
  species,
  terrainHints,
  type BeastId,
  type RouteKind,
} from "./catalog";
import {
  activeBonds,
  advance,
  capture,
  captureChance,
  order,
  setPriority,
  setStance,
  settle,
  startBattle,
  stats,
} from "./engine";
import {
  beginExpedition,
  chooseRoute,
  claimGoal,
  clone,
  evolve,
  freshBeastMemory,
  grow,
  hatch,
  train,
} from "./save";
import type { Battle, BeastMemory, Fighter, Priority, Stance } from "./model";
import { BeastAudio } from "./audio";
import {
  envelope,
  parentCommand,
  type ChildEvent,
} from "../../protocol/beasts";

const embedded = window.parent !== window,
  instance = new URLSearchParams(location.search).get("instance") ?? "";
const memory = ref(freshBeastMemory()),
  initialized = ref(!embedded),
  dark = ref(false),
  battle = ref<Battle | null>(null),
  paused = ref(false),
  pauseReason = ref("伙伴们会在这里等你。");
const arena = ref<HTMLElement>(),
  tab = ref<"route" | "roster" | "journal">("route"),
  region = ref(0),
  selected = ref<BeastId>("sprig"),
  slot = ref(0),
  feedback = ref(""),
  retreatAsk = ref(false);
const audio = new BeastAudio();
let seq = 0,
  received = 0,
  frame = 0,
  last = 0,
  nextSound = 0,
  lifecycle = 0,
  readyTimer: ReturnType<typeof setInterval> | undefined;
const active = computed(() => battle.value?.phase === "running"),
  expedition = computed(() => memory.value.expedition),
  area = computed(() => regions[expedition.value?.region ?? region.value]!);
const owned = computed(() =>
    memory.value.roster.find((b) => b.id === selected.value),
  ),
  info = computed(() => species[selected.value]),
  eligible = computed(() => [
    memory.value.captures >= 1,
    memory.value.roster.length >= 6,
    memory.value.roster.some((b) => b.evolved),
    memory.value.guardians.every(Boolean),
  ]);
const team = computed(() =>
    memory.value.team.map((id) =>
      memory.value.roster.find((p) => p.id === id)!,
    ),
  ),
  party = computed(
    () => battle.value?.actors.filter((a) => a.side === "party") ?? [],
  ),
  foes = computed(() => {
    const actors = battle.value?.actors ?? [],
      lastHelper = actors.filter((a) => a.kind === "healer").at(-1)?.id;
    return actors.filter(
      (a) =>
        a.side === "wild" &&
        (a.kind !== "healer" || a.hp > 0 || a.id === lastHelper),
    );
  });
const phaseNames = {
  running: "遭遇进行中",
  won: "又向前，走了一小步",
  lost: "休息一下，再试一次",
  captured: "有新朋友，愿意跟你回家",
};
const priorities: { id: Priority; name: string }[] = [
  { id: "balanced", name: "均衡" },
  { id: "skill", name: "就绪即放" },
  { id: "reserve", name: "保留应对" },
];
const statuses = [
  "burn",
  "poison",
  "slow",
  "silence",
  "stun",
  "haste",
  "guard",
  "taunt",
  "counter",
  "weak",
] as const;
const statusNames = {
  burn: "灼烧",
  poison: "毒",
  slow: "迟缓",
  silence: "沉默",
  stun: "眩晕",
  haste: "加速",
  guard: "减伤",
  taunt: "嘲讽",
  counter: "反击",
  weak: "易伤",
};
const goalNames = [
  "第一次牵起爪爪",
  "六位营地伙伴",
  "让成长发出光",
  "四境安宁",
];
function send(event: ChildEvent) {
  if (embedded)
    window.parent.postMessage(
      envelope(event, instance, ++seq),
      location.origin,
    );
}
function publish() {
  send({
    type: "state",
    payload: {
      phase: !battle.value
        ? "ready"
        : paused.value && active.value
          ? "paused"
          : battle.value.phase === "lost"
            ? "crashed"
            : active.value
              ? "running"
              : "won",
    },
  });
}
function save() {
  send({ type: "checkpoint", payload: clone(memory.value) });
}
function apply(change: (m: BeastMemory) => BeastMemory) {
  memory.value = change(memory.value);
  save();
  audio.play("choose");
}
function stop() {
  lifecycle++;
  cancelAnimationFrame(frame);
  frame = 0;
  last = 0;
  audio.stop();
}
function pause(reason = "已暂停，可以从容选择目标、姿态和技能优先级。") {
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
  memory.value = settle(memory.value, b);
  paused.value = false;
  audio.play(b.phase === "lost" ? "lose" : "win");
  save();
  publish();
}
function loop(now: number) {
  if (!active.value || paused.value) return;
  const b = battle.value!;
  advance(b, last ? Math.min((now - last) / 1000, 0.1) : 0);
  last = now;
  if (b.phase !== "running") {
    finish();
    return;
  }
  if (now >= nextSound && b.effects.some((e) => e.kind === "hit")) {
    audio.play("hit");
    nextSound = now + 240;
  }
  frame = requestAnimationFrame(loop);
}
function point(id: string, narrow: boolean) {
  const friends = party.value.some((a) => a.id === id),
    list = friends ? party.value : foes.value,
    index = Math.max(
      0,
      list.findIndex((a) => a.id === id),
    );
  return narrow
    ? {
        x: 50 + (index - (list.length - 1) / 2) * (friends ? 31 : 37),
        y: friends ? 70 : 20,
      }
    : {
        x:
          (friends ? 25 : 75) +
          (index - (list.length - 1) / 2) * (friends ? 14 : 18),
        y: 38,
      };
}
async function resume() {
  if (!active.value) return;
  stop();
  paused.value = false;
  retreatAsk.value = false;
  const ticket = lifecycle;
  await nextTick();
  arena.value?.focus({ preventScroll: true });
  await audio.unlock();
  if (ticket !== lifecycle) return;
  publish();
  frame = requestAnimationFrame(loop);
}
async function encounter() {
  stop();
  battle.value = startBattle(memory.value);
  paused.value = true;
  feedback.value = "";
  await resume();
}
function startRegion() {
  if (expedition.value) return;
  apply((m) => beginExpedition(m, region.value));
  feedback.value = "每段选一条路线。稀有足印保留在该区域，回营也不会丢。";
}
async function route(id: RouteKind | "guardian") {
  apply((m) => chooseRoute(m, id));
  if (expedition.value?.pending) await encounter();
  else
    feedback.value =
      id === "nest"
        ? "带回一枚温暖的蛋。兽舍中可以查看孵化条件。"
        : "采集完成，补给与培养资源已装好。";
}
function camp() {
  stop();
  battle.value = null;
  paused.value = false;
  retreatAsk.value = false;
  publish();
}
function abandon() {
  camp();
  memory.value.expedition = null;
  save();
  feedback.value = "本次探索已结束，已结算的收获全部保留。";
}
function askRetreat() {
  pause();
  retreatAsk.value = true;
}
function stance(s: Stance) {
  if (battle.value) {
    setStance(battle.value, s);
    arena.value?.focus({ preventScroll: true });
  }
}
function command(kind: "rally" | "hush" | "soothe") {
  if (battle.value) {
    order(battle.value, kind);
    arena.value?.focus({ preventScroll: true });
  }
}
function catchBeast() {
  const b = battle.value;
  if (!b) return;
  const before = b.spentBait;
  capture(b, memory.value);
  if (b.spentBait > before) {
    save();
  }
  if (b.phase !== "running") finish();
  else arena.value?.focus({ preventScroll: true });
}
function focusEnemy(id: string) {
  if (battle.value && active.value) {
    battle.value.target = id;
    arena.value?.focus({ preventScroll: true });
  }
}
function priority(a: Fighter, event: Event) {
  const value = (event.target as HTMLSelectElement).value as Priority;
  if (battle.value) setPriority(battle.value, a.id, value);
  const p = memory.value.roster.find((p) => p.id === a.kind);
  if (p) {
    p.priority = value;
    save();
  }
}
function name(id: BeastId) {
  const p = memory.value.roster.find((p) => p.id === id);
  return p?.evolved ? species[id].evolution!.name : species[id].name;
}
function selectBeast(id: BeastId) {
  selected.value = id;
  feedback.value = "";
}
function assign() {
  if (!owned.value) return;
  const next = clone(memory.value),
    current = next.team.indexOf(selected.value),
    old = next.team[slot.value]!;
  if (current >= 0) next.team[current] = old;
  next.team[slot.value] = selected.value;
  memory.value = next;
  save();
  feedback.value = `${name(selected.value)}已放到第${slot.value + 1}位。`;
}
function path(value: 0 | 1) {
  if (owned.value) {
    owned.value.path = value;
    save();
    feedback.value = "成长方向已重配，等级和投入保留。";
  }
}
function growAction(kind: "train" | "grow" | "evolve") {
  const actions = { train, grow, evolve };
  const before = JSON.stringify(memory.value);
  apply((m) => actions[kind](m, selected.value));
  feedback.value =
    JSON.stringify(memory.value) === before
      ? "条件尚未满足，所需资源没有扣除。"
      : kind === "evolve"
        ? "成长发出了光！形态与能力都改变了。"
        : "培养完成，下次出战即可生效。";
}
function hatchEgg(index: number, instant: boolean) {
  const before = memory.value.eggs.length;
  apply((m) => hatch(m, index, instant));
  feedback.value =
    memory.value.eggs.length < before
      ? "蛋壳轻轻打开了。已有伙伴会转为12晶露与8食物。"
      : "再完成遭遇，或投入12晶露即时孵化。";
}
function toggleAudio() {
  memory.value.muted = !memory.value.muted;
  audio.muted = memory.value.muted;
  if (audio.muted) audio.stop();
  else void audio.unlock().then(() => audio.play("choose"));
  save();
}
function keyboard(e: KeyboardEvent) {
  if (
    (e.target as HTMLElement).closest(
      "input,textarea,select,button,[contenteditable=true]",
    )
  )
    return;
  if (e.code === "Space" && active.value) {
    e.preventDefault();
    if (paused.value) void resume();
    else pause();
  } else if (e.code === "Escape" && active.value) {
    e.preventDefault();
    pause();
  } else if (active.value && ["Digit1", "Digit2", "Digit3"].includes(e.code)) {
    e.preventDefault();
    stance(
      ({ Digit1: "assault", Digit2: "guard", Digit3: "tame" } as const)[
        e.code as "Digit1"
      ],
    );
  }
}
function receive(event: MessageEvent) {
  if (
    event.source !== window.parent ||
    event.origin !== location.origin ||
    !parentCommand(event.data, instance) ||
    event.data.seq <= received
  )
    return;
  received = event.data.seq;
  const e = event.data;
  if (e.type === "init") {
    stop();
    memory.value = clone(e.payload.memory);
    initialized.value = true;
    dark.value = e.payload.theme === "dark";
    audio.muted = memory.value.muted;
    clearInterval(readyTimer);
    battle.value = startBattle(memory.value);
    paused.value = !!battle.value;
    region.value =
      memory.value.expedition?.region ??
      Math.min(memory.value.guardians.filter(Boolean).length, 3);
    publish();
  } else if (e.type === "pause") pause(e.payload.reason);
  else if (e.type === "resume") void resume();
  else if (e.type === "snapshot-request")
    send({ type: "snapshot-result", payload: clone(memory.value) });
  else if (e.type === "theme") dark.value = e.payload.theme === "dark";
  else if (e.type === "audio") {
    memory.value.muted = e.payload.muted;
    audio.muted = e.payload.muted;
    if (audio.muted) audio.stop();
    save();
  } else if (e.type === "dispose") {
    stop();
    clearInterval(readyTimer);
    send({ type: "disposed", payload: null });
  }
}
function blur() {
  pause("焦点已离开游戏，已暂停。");
}
function visibility() {
  if (document.hidden) pause("页面进入后台，已暂停。");
}
async function focusout(e: FocusEvent) {
  if (!e.relatedTarget) await nextTick();
  const destination = e.relatedTarget ?? document.activeElement;
  if (active.value && !arena.value?.contains(destination as Node))
    pause("输入焦点已离开战场，已暂停。");
}

onMounted(() => {
  window.addEventListener("message", receive);
  window.addEventListener("blur", blur);
  document.addEventListener("visibilitychange", visibility);
  if (embedded) {
    send({ type: "ready", payload: { rulesVersion: 1 } });
    readyTimer = setInterval(
      () => send({ type: "ready", payload: { rulesVersion: 1 } }),
      600,
    );
  }
});
onBeforeUnmount(() => {
  stop();
  clearInterval(readyTimer);
  audio.dispose();
  window.removeEventListener("message", receive);
  window.removeEventListener("blur", blur);
  document.removeEventListener("visibilitychange", visibility);
});
</script>

<template>
  <main
    class="beast-game"
    :class="{ dark, paused }"
    ref="arena"
    tabindex="0"
    @keydown="keyboard"
    @focusout="focusout"
  >
    <div v-if="!initialized" class="loading">正在打开营地记忆…</div>
    <template v-else>
      <header class="game-header">
        <div>
          <span class="eyebrow">WAO · LITTLE WILD FRIENDS</span>
          <h1>幻兽领地 <small>把心事，养成小兽。</small></h1>
        </div>
        <div class="header-actions">
          <button @click="toggleAudio">
            音效{{ memory.muted ? "关" : "开" }}</button
          ><button v-if="active" @click="paused ? resume() : pause()">
            {{ paused ? "继续" : "暂停" }}
          </button>
        </div>
      </header>
      <div class="resources">
        <span
          >食物 <b>{{ memory.food }}</b></span
        ><span
          >晶露 <b>{{ memory.dew }}</b></span
        ><span
          >诱饵 <b>{{ memory.bait }}</b></span
        ><span
          >进化石 <b>{{ memory.stones }}</b></span
        ><span class="record"
          >四境守卫 {{ memory.guardians.filter(Boolean).length }} / 4</span
        >
      </div>

      <template v-if="battle">
        <section class="battle-heading">
          <div>
            <span class="eyebrow"
              >{{ battle.encounter.guardian ? "守卫挑战" : "野外遭遇" }} ·
              {{ Math.floor(battle.seconds) }} 秒</span
            >
            <h2>{{ regions[battle.encounter.region]!.name }}</h2>
            <p>{{ terrainHints[battle.encounter.terrain] }}</p>
          </div>
          <button @click="askRetreat">返回路线</button>
        </section>
        <div v-if="paused && active" class="pause-banner" role="status">
          <div>
            <b>风也停下来等你了</b>
            <p>{{ pauseReason }}</p>
          </div>
          <button class="primary" @click="resume">继续遭遇</button>
        </div>
        <section
          class="battlefield"
          :class="battle.encounter.terrain"
          aria-label="阵容战场"
        >
          <div class="scenery"><i /><i /><i /><i /><i /></div>
          <svg
            v-for="narrow in [false, true]"
            :key="String(narrow)"
            class="effect-lines"
            :class="{ narrow }"
            viewBox="0 0 100 100"
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            <g
              v-for="(e, i) in battle.effects"
              :key="i"
              :opacity="Math.min(1, e.life * 4)"
            >
              <line
                :x1="point(e.from, narrow).x"
                :y1="point(e.from, narrow).y"
                :x2="point(e.to, narrow).x"
                :y2="point(e.to, narrow).y"
                :stroke="
                  e.kind === 'heal'
                    ? '#82b966'
                    : e.kind === 'shield'
                      ? '#83b8b6'
                      : e.kind === 'control'
                        ? '#b7a7c6'
                        : '#e6bd6c'
                "
                :stroke-dasharray="e.kind === 'hit' ? undefined : '2 1'"
                stroke-width=".55"
              />
              <circle
                :cx="point(e.to, narrow).x"
                :cy="point(e.to, narrow).y"
                r="2"
                fill="none"
                stroke="#e2c989"
                stroke-width=".4"
              />
            </g>
          </svg>
          <div class="battle-side party">
            <div
              v-for="a in party"
              :key="a.id"
              class="fighter"
              :class="{
                out: a.hp <= 0,
                hit: battle.effects.some(
                  (e) => e.to === a.id && e.kind === 'hit',
                ),
              }"
            >
              <div class="portrait">
                <Creature
                  :kind="a.kind"
                  :evolved="a.evolved"
                  :asleep="a.hp <= 0"
                  :hurt="
                    battle.effects.some(
                      (e) => e.to === a.id && e.kind === 'hit',
                    )
                  "
                /><span
                  v-if="
                    battle.effects.some(
                      (e) => e.to === a.id && e.kind === 'heal',
                    )
                  "
                  class="heal-pop"
                  >＋</span
                >
              </div>
              <b>{{ a.name }}</b>
              <div class="hp">
                <i
                  :style="{ width: Math.max(0, (a.hp / a.maxHp) * 100) + '%' }"
                />
              </div>
              <small
                >生命 {{ Math.ceil(a.hp) }}/{{ a.maxHp }}
                <em v-if="a.shield">盾 {{ Math.ceil(a.shield) }}</em></small
              >
              <div class="status-list">
                <span v-for="s in statuses.filter((s) => a[s] > 0)" :key="s">{{
                  statusNames[s]
                }}</span>
              </div>
              <label class="priority"
                >技能<select
                  :aria-label="a.name + '技能优先级'"
                  :value="a.priority"
                  :disabled="!active"
                  @change="priority(a, $event)"
                >
                  <option v-for="p in priorities" :key="p.id" :value="p.id">
                    {{ p.name }}
                  </option>
                </select></label
              ><small class="cooldown">{{
                a.skillWait > 0
                  ? `${a.skillWait.toFixed(1)}秒就绪`
                  : "等待释放时机"
              }}</small>
            </div>
          </div>
          <span class="versus">✦</span>
          <div class="battle-side wild">
            <button
              v-for="a in foes"
              :key="a.id"
              class="fighter enemy"
              :class="{ target: battle.target === a.id, out: a.hp <= 0 }"
              :disabled="a.hp <= 0 || !active"
              @click="focusEnemy(a.id)"
              :aria-label="'集火' + a.name"
            >
              <div class="portrait">
                <Creature
                  :kind="a.kind"
                  :region="battle.encounter.region"
                  :hurt="
                    battle.effects.some(
                      (e) => e.to === a.id && e.kind === 'hit',
                    )
                  "
                  :casting="a.cast > 0"
                  :asleep="a.hp <= 0"
                />
              </div>
              <b>{{ a.name }}</b>
              <div class="hp">
                <i
                  :style="{ width: Math.max(0, (a.hp / a.maxHp) * 100) + '%' }"
                />
              </div>
              <small
                >生命 {{ Math.ceil(a.hp) }}/{{ a.maxHp }}
                <em v-if="a.shield">盾 {{ Math.ceil(a.shield) }}</em></small
              >
              <div class="status-list">
                <span v-for="s in statuses.filter((s) => a[s] > 0)" :key="s">{{
                  statusNames[s]
                }}</span>
              </div>
              <strong v-if="a.cast > 0" class="casting-label"
                >蓄力 {{ a.cast.toFixed(1) }}秒</strong
              ><small v-else
                >{{ battle.target === a.id ? "正在集火" : "点击集火"
                }}{{ a.calm > 0 ? " · 已安抚" : "" }}</small
              >
            </button>
          </div>
        </section>
        <div class="battle-info">
          <span
            v-for="bond in activeBonds(memory)"
            :key="bond"
            :title="bonds[bond].hint"
            >{{ bonds[bond].name }}</span
          >
          <p>
            {{
              battle.encounter.guardian
                ? regions[battle.encounter.region]!.hint
                : "先留手、再安抚。捕获一只会结束本节点，其余野兽安然离开。"
            }}
          </p>
        </div>
        <div v-if="active" class="tactics">
          <div class="stance-group">
            <button
              v-for="s in [
                ['assault', '进攻', '1'],
                ['guard', '守护', '2'],
                ['tame', '留手', '3'],
              ] as const"
              :key="s[0]"
              :class="{ chosen: battle.stance === s[0] }"
              @click="stance(s[0])"
            >
              {{ s[1] }} <kbd>{{ s[2] }}</kbd>
            </button>
          </div>
          <div class="orders">
            <span
              >号令 <b>{{ Math.floor(battle.charge) }}</b
              >/100
              <small v-if="battle.orderCooldown > 0"
                >· {{ battle.orderCooldown.toFixed(1) }}秒后可用</small
              ></span
            ><button
              :disabled="battle.charge < 40 || battle.orderCooldown > 0"
              @click="command('rally')"
            >
              集结 · 40</button
            ><button
              :disabled="battle.charge < 40 || battle.orderCooldown > 0"
              @click="command('hush')"
            >
              静心打断 · 40</button
            ><button
              v-if="!battle.encounter.guardian"
              :disabled="battle.charge < 20 || battle.orderCooldown > 0"
              @click="command('soothe')"
            >
              安抚 · 20</button
            ><button
              v-if="!battle.encounter.guardian"
              class="catch"
              :disabled="memory.bait < 1 || battle.captureCooldown > 0"
              @click="catchBeast"
            >
              捕获 {{ captureChance(battle) }}% · 1诱饵
              <small v-if="battle.captureCooldown > 0"
                >{{ battle.captureCooldown.toFixed(1) }}秒后重试</small
              >
            </button>
          </div>
          <small
            >守护：承伤 −60%，输出 −32%。留手：输出
            −70%，敌人至少保留1生命。Space 暂停。</small
          >
        </div>
        <section v-else class="result" role="status">
          <span class="eyebrow">{{
            battle.phase === "lost"
              ? "本节点未完成"
              : battle.phase === "captured"
                ? "相遇记忆 +1"
                : "探索完成"
          }}</span>
          <h2>{{ phaseNames[battle.phase] }}</h2>
          <p v-if="battle.phase === 'lost'">
            调整队伍、培养或技能策略后再来。路线和已结算资源保留；本节点从开战前重试。
          </p>
          <p v-else>
            {{
              battle.phase === "captured"
                ? species[battle.captured!].name +
                  " 已加入营地（重复伙伴转为资源）。"
                : "收获已装进口袋。"
            }}
            食物 +{{
              12 + battle.encounter.region * 4 + (battle.encounter.risk ? 6 : 0)
            }}
            · 晶露 +{{
              8 + battle.encounter.region * 3 + (battle.encounter.risk ? 6 : 0)
            }}{{
              battle.encounter.guardian
                ? battle.encounter.region < 3
                  ? " · 获得进化石，下一片领地已解锁。"
                  : " · 获得进化石，四境已安宁。"
                : ""
            }}
          </p>
          <button
            v-if="battle.phase === 'lost'"
            class="primary"
            @click="encounter"
          >
            重试本节点</button
          ><button class="primary" @click="camp">
            {{
              battle.encounter.guardian && battle.phase !== "lost"
                ? "回到营地"
                : "继续探索"
            }}
          </button>
        </section>
        <details class="battle-log">
          <summary>遭遇记录 · {{ battle.log.at(-1) }}</summary>
          <p v-for="(line, i) in battle.log" :key="i">{{ line }}</p>
        </details>
        <div
          v-if="retreatAsk"
          class="retreat-box"
          role="dialog"
          aria-label="返回路线确认"
        >
          <h3>暂时回到路线？</h3>
          <p>
            已获得资源保留。未结束遭遇下次从本节点重新开战；已经使用的诱饵不会返还。
          </p>
          <button @click="retreatAsk = false">取消</button
          ><button class="primary" @click="camp">返回路线</button>
        </div>
      </template>

      <template v-else>
        <nav class="tabs" aria-label="营地页面">
          <button @click="tab = 'route'" :class="{ chosen: tab === 'route' }">
            领地探索</button
          ><button
            @click="tab = 'roster'"
            :class="{ chosen: tab === 'roster' }"
          >
            兽舍与培养 <span>{{ memory.roster.length }}</span></button
          ><button
            @click="tab = 'journal'"
            :class="{ chosen: tab === 'journal' }"
          >
            图鉴与目标
          </button>
        </nav>
        <section class="camp-layout" v-if="tab === 'route'">
          <div class="route-main">
            <div class="map-hero" :style="{ '--region-color': area.color }">
              <div class="hero-copy">
                <span class="eyebrow">{{
                  expedition ? "EXPLORING · 在路上" : "YOUR LITTLE TERRITORY"
                }}</span>
                <h2>{{ area.name }}</h2>
                <p>{{ area.subtitle }}</p>
                <span class="stamp">{{
                  memory.guardians[area.id] ? "守卫已安宁" : "未知的小小冒险"
                }}</span>
              </div>
              <svg class="map-art" viewBox="0 0 500 260" aria-hidden="true">
                <ellipse
                  cx="278"
                  cy="188"
                  rx="194"
                  ry="54"
                  fill="#657e4b"
                  opacity=".18"
                />
                <path
                  d="m59 161 86-58 85-39 101 31 125 68-68 52-135 22-125-31Z"
                  fill="#6b9563"
                />
                <path
                  d="m59 151 86-58 85-39 101 31 125 68-68 52-135 22-125-31Z"
                  fill="#b9d69b"
                />
                <path
                  d="m157 186 33-40 60 9 30-54 66 46 55 8"
                  fill="none"
                  stroke="#f8e9bd"
                  stroke-width="13"
                  stroke-linecap="round"
                />
                <path d="m297 174 53-38 69 41-63 29Z" fill="#83b9ae" />
                <g fill="#72985a" stroke="#597d47" stroke-width="3">
                  <path d="m90 146 26-57 25 61Z" />
                  <path d="m138 104 21-49 21 49Z" />
                  <path d="m348 126 25-67 27 68Z" />
                  <path d="m389 151 20-51 18 55Z" />
                </g>
                <path d="m204 115 38-35 39 35-6 33-65-3Z" fill="#e1c596" />
                <path d="m197 117 45-44 47 44Z" fill="#f4eed4" />
                <path d="m230 142 0-23q12-13 22 0v24" fill="#778760" />
                <g fill="#f0e4a6">
                  <circle cx="174" cy="165" r="6" />
                  <circle cx="280" cy="108" r="6" />
                  <circle cx="341" cy="146" r="6" />
                </g>
                <g fill="#f7f4d7">
                  <circle cx="120" cy="170" r="3" />
                  <circle cx="128" cy="176" r="4" />
                  <circle cx="312" cy="202" r="4" />
                  <circle cx="321" cy="198" r="3" />
                </g>
              </svg>
              <div class="map-pet">
                <Creature :kind="memory.team[0]!" :evolved="team[0]!.evolved" />
              </div>
            </div>
            <div v-if="!expedition" class="region-picker">
              <button
                v-for="r in regions"
                :key="r.id"
                :disabled="r.id > 0 && !memory.guardians[r.id - 1]"
                :class="{ chosen: region === r.id }"
                @click="region = r.id"
              >
                <small>0{{ r.id + 1 }}</small
                >{{ r.name
                }}<span>{{
                  memory.guardians[r.id]
                    ? "✓"
                    : r.id > 0 && !memory.guardians[r.id - 1]
                      ? "未解锁"
                      : "可探索"
                }}</span>
              </button>
            </div>
            <div class="route-meta">
              <p>{{ terrainHints[area.terrain] }}</p>
              <p>
                稀有足印 <b>{{ memory.traces[area.id] }} / 2</b> ·
                追踪满两次保证相遇
              </p>
            </div>
            <div v-if="!expedition" class="depart">
              <div>
                <h3>带上伙伴，沿小径出发</h3>
                <p>三段路线，一位守卫。资源、巢穴、追踪，由你决定。</p>
              </div>
              <button class="primary" @click="startRegion">出发探索</button>
            </div>
            <template v-else
              ><div class="expedition-progress">
                <span
                  v-for="i in 4"
                  :key="i"
                  :class="{
                    done: expedition.step >= i,
                    current: expedition.step === i - 1,
                  }"
                  >{{ i === 4 ? "守卫" : `路段 ${i}` }}</span
                ><b>补给 {{ expedition.supplies }}</b>
              </div>
              <div v-if="expedition.pending" class="depart">
                <div>
                  <h3>
                    {{
                      expedition.pending.guardian
                        ? area.guardian
                        : "本节点遭遇"
                    }}等待继续
                  </h3>
                  <p>保存的是开战前节点，重新进入会恢复双方生命。</p>
                </div>
                <button class="primary" @click="encounter">进入遭遇</button>
              </div>
              <div v-else-if="expedition.step === 3" class="depart">
                <div>
                  <h3>{{ area.guardian }}</h3>
                  <p>{{ area.hint }}</p>
                </div>
                <button class="primary" @click="route('guardian')">
                  挑战守卫
                </button>
              </div>
              <div v-else class="route-cards">
                <button
                  v-for="r in routeOptions(area.id, expedition.step)"
                  :key="r.id"
                  :disabled="expedition.supplies < r.cost"
                  @click="route(r.id)"
                >
                  <span class="route-icon">{{
                    r.id === "track"
                      ? "✧"
                      : r.id === "nest"
                        ? "◒"
                        : r.id === "forage"
                          ? "❧"
                          : "⌁"
                  }}</span>
                  <h3>{{ r.name }}</h3>
                  <p>{{ r.hint }}</p>
                  <small
                    >{{ r.cost ? `消耗 ${r.cost} 补给` : "获得补给" }} →</small
                  >
                </button>
              </div>
              <button class="quiet" @click="abandon">
                结束本次探索 · 保留收获
              </button>
            </template>
          </div>
          <aside class="team-panel">
            <span class="eyebrow">一起去，才有意思</span>
            <h3>今天的三位伙伴</h3>
            <button
              v-for="(p, i) in team"
              :key="p.id"
              class="team-row"
              @click="
                selected = p.id;
                slot = i;
                tab = 'roster';
              "
            >
              <Creature :kind="p.id" :evolved="p.evolved" />
              <div>
                <b>{{ name(p.id) }}</b
                ><small
                  >Lv.{{ p.level }} · {{ roleNames[species[p.id].role] }} · 第{{
                    i + 1
                  }}位</small
                >
              </div>
              <span>↗</span>
            </button>
            <div class="bond" v-for="bond in activeBonds(memory)" :key="bond">
              <b>{{ bonds[bond].name }}</b>
              <p>{{ bonds[bond].hint }}</p>
            </div>
            <p v-if="!activeBonds(memory).length" class="muted-text">
              同系两兽会触发协同。也可以混搭三种角色，灵活处理机制。
            </p>
            <button class="wide" @click="tab = 'roster'">调整队伍与培养</button>
            <p class="save-note">
              随关键进度保存。遭遇中离开，下次从本节点开战前恢复。
            </p>
          </aside>
        </section>

        <section v-else-if="tab === 'roster'" class="roster-layout">
          <div class="beast-shelf">
            <button
              v-for="p in memory.roster"
              :key="p.id"
              :class="{ chosen: selected === p.id }"
              @click="selectBeast(p.id)"
            >
              <Creature :kind="p.id" :evolved="p.evolved" /><b>{{
                name(p.id)
              }}</b
              ><small
                >Lv.{{ p.level }} · {{ roleNames[species[p.id].role] }}</small
              ><span v-if="memory.team.includes(p.id)" class="team-badge"
                >出战{{ memory.team.indexOf(p.id) + 1 }}</span
              >
            </button>
          </div>
          <div v-if="owned" class="beast-detail">
            <div class="detail-heading">
              <Creature :kind="selected" :evolved="owned.evolved" />
              <div>
                <span class="eyebrow"
                  >{{ roleNames[info.role] }} ·
                  {{ bonds[info.bond].name.slice(0, 2)
                  }}{{ info.rare ? " · 稀有" : "" }}</span
                >
                <h2>{{ name(selected) }}</h2>
                <p>
                  Lv.{{ owned.level }} · 成长 {{ owned.rank }}/3 · 经验
                  {{ owned.xp }}
                </p>
              </div>
            </div>
            <div class="stat-line">
              <span>生命 {{ stats(owned).hp }}</span
              ><span>攻击 {{ stats(owned).attack }}</span
              ><span>护甲 {{ stats(owned).armor }}</span>
            </div>
            <h3>{{ info.skill }}</h3>
            <p>{{ info.hint }} {{ info.trait }}</p>
            <div class="branch-options">
              <button
                v-for="(branch, i) in info.branches"
                :key="branch"
                :class="{ chosen: owned.path === i }"
                @click="path(i as 0 | 1)"
              >
                <b>{{ branch }}</b
                ><small>{{ info.branchHints[i] }}</small>
              </button>
            </div>
            <small>方向免费重配。成长1阶开始体现分支；每两级可培养1阶。</small>
            <div class="growth-actions">
              <button
                :disabled="
                  owned.level >= 8 ||
                  memory.food < 8 + owned.level * 3 ||
                  memory.dew < 3 + owned.level
                "
                @click="growAction('train')"
              >
                {{
                  owned.level >= 8
                    ? "等级已满"
                    : `喂养升级 · ${8 + owned.level * 3}食物 ${3 + owned.level}晶露`
                }}</button
              ><button
                :disabled="
                  owned.rank >= 3 ||
                  owned.rank >= Math.floor(owned.level / 2) ||
                  memory.dew < 8 + owned.rank * 4
                "
                @click="growAction('grow')"
              >
                培养成长 · {{ 8 + owned.rank * 4 }}晶露</button
              ><button
                v-if="info.evolution"
                :disabled="
                  owned.evolved ||
                  owned.level < 3 ||
                  memory.stones < 1 ||
                  memory.dew < 18
                "
                @click="growAction('evolve')"
              >
                {{
                  owned.evolved
                    ? "已进化：" + info.evolution.name
                    : "进化 · Lv.3 / 1石 / 18晶露"
                }}
              </button>
            </div>
            <p v-if="info.evolution" class="evolution-note">
              {{ info.evolution.name }}：{{ info.evolution.hint }}
            </p>
            <div class="assignment">
              <label
                >放入位置<select v-model="slot" aria-label="出战位置">
                  <option :value="0">1 · 前排</option>
                  <option :value="1">2 · 中排</option>
                  <option :value="2">3 · 后排</option>
                </select></label
              ><button class="primary" @click="assign">安排出战</button>
            </div>
            <small
              >默认先攻击前排；嘲讽可以转移目标。已有出战兽会交换位置。</small
            >
          </div>
          <section class="egg-panel">
            <div>
              <span class="eyebrow">不催促，也不用等待现实时间</span>
              <h3>温暖的孵化角 · {{ memory.eggs.length }}枚蛋</h3>
            </div>
            <p v-if="!memory.eggs.length">
              探索第二段的巢穴，可以带回本区常见幻兽蛋。
            </p>
            <div v-for="(egg, i) in memory.eggs" :key="i" class="egg-row">
              <span class="egg-icon">◒</span>
              <div>
                <b>{{ species[egg.id].name }}的蛋</b
                ><small
                  >已完成 {{ Math.min(2, memory.wins - egg.winsAt) }} /
                  2次遭遇</small
                >
              </div>
              <button
                :disabled="memory.wins - egg.winsAt < 2"
                @click="hatchEgg(i, false)"
              >
                孵化</button
              ><button
                v-if="memory.wins - egg.winsAt < 2"
                :disabled="memory.dew < 12"
                @click="hatchEgg(i, true)"
              >
                12晶露即时孵化
              </button>
            </div>
          </section>
        </section>

        <section v-else class="journal">
          <div class="journal-title">
            <div>
              <span class="eyebrow">每一种相遇，都有形状</span>
              <h2>幻兽图鉴 · {{ memory.roster.length }} / 12</h2>
            </div>
            <span>捕获 {{ memory.captures }} · 遭遇胜利 {{ memory.wins }}</span>
          </div>
          <div class="collection">
            <button
              v-for="id in beastIds"
              :key="id"
              :class="{ unknown: !memory.seen.includes(id) }"
              @click="
                selected = id;
                if (memory.roster.some((p) => p.id === id)) tab = 'roster';
              "
            >
              <Creature
                :kind="id"
                :evolved="memory.roster.find((p) => p.id === id)?.evolved"
              /><b>{{ species[id].name }}</b
              ><small
                >{{ regions[species[id].region]!.name }} ·
                {{ species[id].rare ? "追踪稀有" : "常见" }}</small
              ><span>{{
                memory.roster.some((p) => p.id === id)
                  ? "已收集"
                  : memory.seen.includes(id)
                    ? "已相遇"
                    : "待发现"
              }}</span>
            </button>
          </div>
          <div class="goals">
            <article v-for="(title, i) in goalNames" :key="title">
              <span class="goal-number">0{{ i + 1 }}</span>
              <h3>{{ title }}</h3>
              <p>
                {{
                  [
                    "成功捕获一只幻兽",
                    "收集六种幻兽",
                    "完成一次形态进化",
                    "战胜四个区域守卫",
                  ][i]
                }}
              </p>
              <small>20食物 · 15晶露 · 1进化石</small
              ><button
                :disabled="!eligible[i] || memory.goals[i]"
                @click="apply((m) => claimGoal(m, i))"
              >
                {{
                  memory.goals[i]
                    ? "已领取"
                    : eligible[i]
                      ? "领取奖励"
                      : "继续探索"
                }}
              </button>
            </article>
          </div>
          <p v-if="memory.guardians.every(Boolean)" class="chapter-complete">
            四境已安宁。还可以收集全部伙伴、尝试新阵容和进化，重访每片领地。
          </p>
        </section>
        <p v-if="feedback" class="feedback" role="status">{{ feedback }}</p>
      </template>
      <footer>
        无需登录 · 本机 / 本次模式、导出与导入在页面「游戏记忆」中管理。{{
          !embedded ? "当前为独立开发预览，刷新不保留。" : ""
        }}
      </footer>
    </template>
  </main>
</template>

<style src="./style.css"></style>
