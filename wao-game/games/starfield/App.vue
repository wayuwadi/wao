<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import Character from "./Character.vue";
import {
  chapters,
  counterElement,
  elementColors,
  elementNames,
  gear,
  heroIds,
  heroes,
  petIds,
  pets,
  quests,
  type HeroId,
  type PetId,
  type Skill,
} from "./catalog";
import {
  act,
  actorName,
  currentActor,
  enemyAct,
  heroStats,
  intentText,
  legalTargets,
  living,
  startBattle,
  usePet,
} from "./engine";
import {
  branchBudget,
  clone,
  freshStarfieldMemory,
  level,
  unlocked,
} from "./save";
import {
  statusKeys,
  type Actor,
  type StarfieldMemory,
  type Status,
} from "./model";
import { StarAudio } from "./audio";
import {
  envelope,
  parentCommand,
  type ChildEvent,
} from "../../protocol/starfield";

const embedded = window.parent !== window,
  instance = new URLSearchParams(location.search).get("instance") ?? "";
const memory = ref<StarfieldMemory>(freshStarfieldMemory()),
  initialized = ref(!embedded),
  dark = ref(false),
  paused = ref(false),
  pauseReason = ref("准备好，再继续这一轮。");
const arena = ref<HTMLElement>(),
  tab = ref<"map" | "party" | "gear">("map"),
  chapter = ref(0),
  questId = ref(0),
  heroId = ref<HeroId>("sol"),
  partySlot = ref(0);
const pending = ref<Skill | "pet" | null>(null),
  targetId = ref(""),
  feedback = ref(""),
  retreatAsk = ref(false),
  flashes = ref<string[]>([]);
const audio = new StarAudio();
let timer: ReturnType<typeof setTimeout> | undefined,
  flashTimer: ReturnType<typeof setTimeout> | undefined,
  readyTimer: ReturnType<typeof setInterval> | undefined;
let lifecycle = 0,
  sequence = 0,
  received = 0;
const battle = computed(() => memory.value.battle),
  active = computed(
    () => !!battle.value && ["player", "enemy"].includes(battle.value.phase),
  );
const result = computed(() => battle.value && !active.value),
  actor = computed(() => (battle.value ? currentActor(battle.value) : null));
const quest = computed(() => quests[questId.value]!),
  campaignCount = computed(() => memory.value.completed.filter(Boolean).length);
const selectedHero = computed(() => heroes[heroId.value]),
  progress = computed(() => memory.value.heroes[heroId.value]);
const points = computed(
  () =>
    branchBudget(progress.value) -
    progress.value.branches.reduce((a, b) => a + b, 0),
);
const currentSkills = computed(() =>
  actor.value?.side === "party"
    ? heroes[actor.value.kind as HeroId].skills
    : [],
);
const possible = computed(() => {
  if (!battle.value || !actor.value || !pending.value) return [];
  return pending.value === "pet"
    ? living(battle.value, memory.value.pet === "bird" ? "foe" : "party")
    : legalTargets(battle.value, actor.value, pending.value);
});
const playerTurn = computed(
  () => active.value && !paused.value && battle.value?.phase === "player",
);
const tier = computed(() =>
  memory.value.completed[5] ? 2 : memory.value.completed[2] ? 1 : 0,
);
const statusNames: Record<Status, string> = {
  burn: "灼烧",
  slow: "迟缓",
  sealed: "禁疗",
  stun: "眩晕",
  weak: "易伤",
  guard: "防御",
  taunt: "嘲讽",
  haste: "加速",
  ward: "澄明",
  regen: "持续回复",
  counter: "反击",
};
const mapPoints = [
  { x: 24, y: 58 },
  { x: 75, y: 53 },
  { x: 51, y: 19 },
];
function send(event: ChildEvent) {
  if (embedded)
    window.parent.postMessage(
      envelope(event, instance, ++sequence),
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
          : battle.value.phase === "won"
            ? "won"
            : battle.value.phase === "lost"
              ? "crashed"
              : "running",
    },
  });
}
function save() {
  send({ type: "checkpoint", payload: clone(memory.value) });
}
function stop() {
  lifecycle++;
  clearTimeout(timer);
  clearTimeout(flashTimer);
  timer = undefined;
  flashes.value = [];
  audio.stop();
}
function pause(reason = "已暂停，伙伴们会等你。") {
  stop();
  pending.value = null;
  targetId.value = "";
  if (active.value) paused.value = true;
  pauseReason.value = reason;
  publish();
}
function schedule() {
  clearTimeout(timer);
  if (!active.value || paused.value || battle.value?.phase !== "enemy") return;
  const ticket = lifecycle;
  timer = setTimeout(() => {
    if (ticket !== lifecycle || paused.value) return;
    apply(() => enemyAct(memory.value));
  }, 720);
}
function apply(change: () => StarfieldMemory) {
  try {
    const before = battle.value?.actors ?? [];
    memory.value = change();
    pending.value = null;
    targetId.value = "";
    feedback.value = "";
    flashes.value =
      battle.value?.actors
        .filter(
          (a) => a.hp < (before.find((old) => old.id === a.id)?.hp ?? a.hp),
        )
        .map((a) => a.id) ?? [];
    clearTimeout(flashTimer);
    flashTimer = setTimeout(() => {
      flashes.value = [];
    }, 350);
    audio.play(
      result.value
        ? battle.value!.phase === "won"
          ? "win"
          : "lose"
        : flashes.value.length
          ? "hit"
          : "heal",
    );
    save();
    publish();
    schedule();
  } catch (e) {
    feedback.value = e instanceof Error ? e.message : "行动未提交，请重试。";
  }
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
  schedule();
}
function selectQuest(id: number) {
  questId.value = id;
  feedback.value = "";
  audio.play("choose");
}
function accept() {
  if (!unlocked(memory.value, questId.value)) return;
  memory.value.accepted[questId.value] = true;
  save();
  feedback.value = `已接取「${quest.value.name}」。选择探索路线后出发。`;
}
async function embark(choice: "aid" | "bold") {
  try {
    stop();
    memory.value = startBattle(
      memory.value,
      questId.value,
      choice,
      crypto.getRandomValues(new Uint32Array(1))[0]!,
    );
    save();
    paused.value = true;
    pending.value = null;
    feedback.value = "";
    await resume();
  } catch (e) {
    feedback.value = e instanceof Error ? e.message : "无法出发。";
  }
}
function camp() {
  stop();
  if (battle.value) {
    chapter.value = quests[battle.value.quest]!.chapter;
    questId.value = battle.value.quest;
  }
  memory.value.battle = null;
  paused.value = false;
  pending.value = null;
  retreatAsk.value = false;
  save();
  publish();
}
function askRetreat() {
  pause();
  retreatAsk.value = true;
}
async function retry() {
  const b = battle.value;
  if (!b) return;
  const { quest: oldQuest, choice } = b;
  stop();
  memory.value.battle = null;
  questId.value = oldQuest;
  await embark(choice);
}
async function selectSkill(skill: Skill) {
  if (!playerTurn.value || actor.value!.mp < skill.cost) return;
  arena.value?.focus({ preventScroll: true });
  pending.value = skill;
  targetId.value = possible.value[0]?.id ?? "";
  feedback.value = "";
  await nextTick();
  arena.value?.focus({ preventScroll: true });
}
async function selectPet() {
  if (!playerTurn.value) return;
  arena.value?.focus({ preventScroll: true });
  pending.value = "pet";
  targetId.value = possible.value[0]?.id ?? "";
  await nextTick();
  arena.value?.focus({ preventScroll: true });
}
function confirm() {
  if (!playerTurn.value || !pending.value) return;
  arena.value?.focus({ preventScroll: true });
  const selection = pending.value;
  apply(() =>
    selection === "pet"
      ? usePet(memory.value, targetId.value)
      : act(memory.value, selection.id, targetId.value),
  );
}
function cancelSelection() {
  arena.value?.focus({ preventScroll: true });
  pending.value = null;
  targetId.value = "";
}
function defend() {
  arena.value?.focus({ preventScroll: true });
  if (playerTurn.value)
    apply(() => act(memory.value, "defend", actor.value!.id));
}
function target(a: Actor) {
  if (possible.value.some((p) => p.id === a.id)) {
    targetId.value = a.id;
    arena.value?.focus({ preventScroll: true });
  }
}
function keyboard(e: KeyboardEvent) {
  if (
    (e.target as HTMLElement).closest(
      "input,textarea,select,button,[contenteditable=true]",
    )
  )
    return;
  if (e.code === "Escape") {
    e.preventDefault();
    if (pending.value) pending.value = null;
    else pause();
    return;
  }
  if (!playerTurn.value || !pending.value) return;
  if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.code)) {
    e.preventDefault();
    const options = possible.value,
      index = options.findIndex((a) => a.id === targetId.value),
      delta = ["ArrowLeft", "ArrowUp"].includes(e.code) ? -1 : 1;
    targetId.value =
      options[(index + delta + options.length) % options.length]?.id ?? "";
  } else if (e.code === "Enter") {
    e.preventDefault();
    confirm();
  }
}
function configureHero(id: HeroId) {
  heroId.value = id;
}
function setParty(id: HeroId) {
  const old = memory.value.party[partySlot.value]!,
    other = memory.value.party.indexOf(id);
  if (other >= 0) memory.value.party[other] = old;
  memory.value.party[partySlot.value] = id;
  heroId.value = id;
  save();
}
function grow(branch: number) {
  if (points.value <= 0 || progress.value.branches[branch]! >= 3) return;
  progress.value.branches[branch]++;
  save();
}
function resetGrowth() {
  progress.value.branches = [0, 0];
  save();
  feedback.value = "成长点已返还，可以换一条路线再试。";
}
function selectPetCamp(id: PetId) {
  if (campaignCount.value < pets[id].unlock) return;
  memory.value.pet = id;
  save();
}
function equip(id: string) {
  const item = gear.find((g) => g.id === id);
  if (!item || !memory.value.owned.includes(id)) return;
  progress.value[item.slot] = id;
  save();
}
function craft(id: string) {
  const item = gear.find((g) => g.id === id);
  if (
    !item ||
    item.tier > tier.value ||
    memory.value.owned.includes(id) ||
    memory.value.sand < item.price
  )
    return;
  memory.value.sand -= item.price;
  memory.value.owned.push(id);
  save();
  feedback.value = `已制作${item.name}，可以给伙伴装备。`;
}
async function toggleSound() {
  memory.value.muted = !memory.value.muted;
  audio.muted = memory.value.muted;
  if (audio.muted) audio.stop();
  else await audio.unlock();
  save();
}
function blur() {
  pause("已暂停：离开舞台或输入焦点。");
}
function visibility() {
  if (document.hidden) pause("已暂停：页面进入后台。");
}
async function focusOut(e: FocusEvent) {
  // Removing a selected command can briefly report a null destination.
  // Check the settled DOM focus; window blur still pauses immediately.
  if (!e.relatedTarget) await nextTick();
  const destination = e.relatedTarget ?? document.activeElement;
  if (active.value && !arena.value?.contains(destination as Node))
    pause("已暂停：输入焦点离开战场。");
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
  const cmd = e.data;
  if (cmd.type === "init") {
    stop();
    memory.value = clone(cmd.payload.memory);
    const nextQuest = memory.value.completed.findIndex(
      (done, id) => !done && unlocked(memory.value, id),
    );
    questId.value =
      memory.value.battle?.quest ?? (nextQuest < 0 ? 8 : nextQuest);
    chapter.value = quests[questId.value]!.chapter;
    audio.muted = memory.value.muted;
    dark.value = cmd.payload.theme === "dark";
    initialized.value = true;
    paused.value = active.value;
    pending.value = null;
    clearInterval(readyTimer);
    publish();
  } else if (cmd.type === "pause") pause(cmd.payload.reason);
  else if (cmd.type === "dispose") {
    stop();
    clearInterval(readyTimer);
    audio.dispose();
    paused.value = true;
    send({ type: "disposed", payload: null });
  } else if (cmd.type === "snapshot-request")
    send({ type: "snapshot-result", payload: clone(memory.value) });
  else if (cmd.type === "theme") dark.value = cmd.payload.theme === "dark";
  else if (cmd.type === "audio") {
    memory.value.muted = cmd.payload.muted;
    audio.muted = cmd.payload.muted;
    if (audio.muted) audio.stop();
    save();
  }
}
onMounted(() => {
  window.addEventListener("message", receive);
  window.addEventListener("blur", blur);
  document.addEventListener("visibilitychange", visibility);
  if (embedded) {
    const ready = () => send({ type: "ready", payload: { rulesVersion: 1 } });
    ready();
    readyTimer = setInterval(ready, 500);
  }
});
onBeforeUnmount(() => {
  stop();
  audio.dispose();
  clearInterval(readyTimer);
  window.removeEventListener("message", receive);
  window.removeEventListener("blur", blur);
  document.removeEventListener("visibilitychange", visibility);
});
</script>

<template>
  <main class="starfield" :class="{ dark, paused }">
    <header class="game-header">
      <div class="brand">
        <span>✺</span>
        <div>
          <strong>星野奇旅</strong
          ><small>{{
            battle ? quests[battle.quest]!.name : "把星光，带在身边。"
          }}</small>
        </div>
      </div>
      <nav>
        <button :aria-pressed="!memory.muted" @click="toggleSound">
          {{ memory.muted ? "音效关" : "音效开" }}</button
        ><button v-if="active && !paused" @click="pause()">暂停</button
        ><button v-if="battle" @click="active ? askRetreat() : camp()">
          回营地
        </button>
      </nav>
    </header>
    <p v-if="!initialized" class="loading">正在读取这一页星路…</p>
    <template v-else-if="!battle">
      <div class="camp-top">
        <div>
          <span class="eyebrow">星图，正在一点点亮起</span>
          <h1>
            {{
              campaignCount === 9
                ? "你们把星光送回了家。"
                : "下一站，和伙伴一起。"
            }}
          </h1>
          <p>三个人，三种办法。带上不同属性，给彼此留一次出手的机会。</p>
        </div>
        <div class="journey-count">
          <b>{{ campaignCount }}<small>/9</small></b
          ><span>已完成任务</span>
        </div>
      </div>
      <nav class="tabs" aria-label="营地面板">
        <button :aria-pressed="tab === 'map'" @click="tab = 'map'">
          探索星图</button
        ><button :aria-pressed="tab === 'party'" @click="tab = 'party'">
          队伍与成长</button
        ><button :aria-pressed="tab === 'gear'" @click="tab = 'gear'">
          装备工坊</button
        ><span>✧ 星砂 {{ memory.sand }}</span>
      </nav>
      <section v-if="tab === 'map'" class="map-layout">
        <div class="map-column">
          <nav class="chapters" aria-label="章节区域">
            <button
              v-for="(c, i) in chapters"
              :key="c.name"
              :disabled="i > 0 && !memory.completed[i * 3 - 1]"
              :aria-pressed="chapter === i"
              @click="
                chapter = i;
                questId = i * 3;
              "
            >
              0{{ i + 1 }} {{ c.name }}
            </button>
          </nav>
          <div
            class="island-map"
            :style="{ '--land': chapters[chapter]!.color }"
          >
            <div class="map-caption">
              <small>{{ chapters[chapter]!.subtitle }}</small>
              <h2>{{ chapters[chapter]!.name }}</h2>
            </div>
            <svg viewBox="0 0 700 400" aria-hidden="true" class="map-sky">
              <circle cx="580" cy="70" r="35" fill="#fff3be" />
              <path
                d="M45 340Q160 280 300 334T655 330M55 95Q89 60 139 82T190 81"
                fill="none"
                stroke="#fffaf0"
                stroke-width="18"
                stroke-linecap="round"
              />
              <path
                d="M168 255 357 108 525 235"
                fill="none"
                stroke="#b89945"
                stroke-width="3"
                stroke-dasharray="7 8"
              />
              <path
                d="M140 260 165 312 194 264M325 135 356 181 392 132M498 257 526 312 552 250"
                fill="#d0b775"
                opacity=".35"
              /></svg
            ><button
              v-for="(point, i) in mapPoints"
              :key="i"
              class="island-node"
              :class="{
                selected: questId === chapter * 3 + i,
                done: memory.completed[chapter * 3 + i],
                locked: !unlocked(memory, chapter * 3 + i),
              }"
              :style="{ left: point.x + '%', top: point.y + '%' }"
              :aria-label="
                quests[chapter * 3 + i]!.name +
                (memory.completed[chapter * 3 + i]
                  ? ' · 已完成'
                  : unlocked(memory, chapter * 3 + i)
                    ? ' · 可探索'
                    : ' · 先完成本章两条支线')
              "
              @click="selectQuest(chapter * 3 + i)"
            >
              <svg viewBox="0 0 150 100" aria-hidden="true">
                <path
                  d="M5 39 75 5 145 39 111 78 75 97 35 78Z"
                  fill="#b58b45"
                />
                <path d="M5 39 75 5 145 39 75 76Z" fill="var(--land)" />
                <path d="M20 38 75 13 130 38 75 63Z" fill="#ffe89a" />
                <path
                  d="M74 10 75 64M31 32 86 60"
                  stroke="#fff4c2"
                  stroke-width="3"
                />
                <text
                  x="75"
                  y="45"
                  text-anchor="middle"
                  font-size="28"
                  fill="#866532"
                >
                  {{
                    memory.completed[chapter * 3 + i]
                      ? "✓"
                      : i === 2
                        ? "✺"
                        : "✦"
                  }}
                </text></svg
              ><span>{{ quests[chapter * 3 + i]!.name }}</span>
            </button>
          </div>
        </div>
        <aside class="quest-card">
          <span class="eyebrow"
            >{{ quest.boss ? "守关任务" : "探索委托" }} · {{ quest.npc }}</span
          >
          <h2>{{ quest.name }}</h2>
          <p>{{ quest.story }}</p>
          <div class="quest-objective">{{ quest.objective }}</div>
          <div class="foe-preview">
            <span v-for="id in quest.foes" :key="id">{{
              id === "sundial"
                ? "日晷守卫"
                : id === "librarian"
                  ? "封页司书"
                  : id === "orrery"
                    ? "星轨仪"
                    : {
                        scout: "疾行斥候",
                        bulwark: "护阵盾卫",
                        medic: "织露医师",
                        stinger: "灼羽蜂",
                        chanter: "吟咒者",
                        drainer: "汲能灵",
                      }[id]
            }}</span>
          </div>
          <p class="reward-line">
            首次 {{ quest.reward }} 星砂 · {{ quest.xp }} 经验 ·
            {{ gear.find((g) => g.id === quest.drop)?.name }}
          </p>
          <p v-if="!unlocked(memory, quest.id)" class="locked-note">
            先完成本章两条支线，再敲响守关的风铃。
          </p>
          <button
            v-else-if="!memory.accepted[quest.id]"
            class="primary"
            @click="accept"
          >
            接取任务</button
          ><template v-else
            ><p class="route-label">
              {{
                memory.completed[quest.id]
                  ? "再次挑战，换一种搭配"
                  : "任务已接取"
              }}
              · 选择探索路线
            </p>
            <button
              v-for="choice in quest.choices"
              :key="choice.id"
              class="route-choice"
              @click="embark(choice.id)"
            >
              <strong>{{ choice.name }} ↗</strong
              ><small>{{ choice.detail }}</small>
            </button></template
          ><small v-if="memory.records[quest.id]!.wins"
            >通关 {{ memory.records[quest.id]!.wins }} 次 · 最快
            {{ memory.records[quest.id]!.bestRounds }} 轮</small
          >
        </aside>
      </section>
      <section v-else-if="tab === 'party'" class="party-panel">
        <div class="formation">
          <button
            v-for="(id, slot) in memory.party"
            :key="slot"
            :aria-pressed="partySlot === slot"
            @click="
              partySlot = slot;
              configureHero(id);
            "
          >
            <small>位置 {{ slot + 1 }}</small
            ><Character :kind="id" :element="heroes[id].element" /><strong>{{
              heroes[id].name
            }}</strong
            ><span>{{ heroes[id].role }}</span>
          </button>
        </div>
        <div class="roster">
          <button
            v-for="id in heroIds"
            :key="id"
            :aria-pressed="heroId === id"
            @click="configureHero(id)"
          >
            {{ heroes[id].name }}
            <small
              >{{ elementNames[heroes[id].element] }} · Lv.{{
                level(memory.heroes[id].xp)
              }}</small
            >
          </button>
        </div>
        <article class="hero-detail">
          <div>
            <span class="eyebrow"
              >{{ selectedHero.role }} ·
              {{ elementNames[selectedHero.element] }}</span
            >
            <h2>
              {{ selectedHero.name }} <small>Lv.{{ level(progress.xp) }}</small>
            </h2>
            <p>
              生命 {{ heroStats(memory, heroId).maxHp }} · 资源
              {{ heroStats(memory, heroId).maxMp }} · 速度
              {{ heroStats(memory, heroId).speed }}
            </p>
            <button class="primary" @click="setParty(heroId)">
              加入位置 {{ partySlot + 1
              }}{{ memory.party.includes(heroId) ? "（交换位置）" : "" }}
            </button>
          </div>
          <div class="growth">
            <span>可用成长点 {{ points }}</span
            ><button @click="resetGrowth">免费重配</button>
            <article v-for="(branch, i) in selectedHero.branches" :key="branch">
              <h3>
                {{ branch }} <small>{{ progress.branches[i] }}/3</small>
              </h3>
              <p>{{ selectedHero.branchHints[i] }}</p>
              <button
                :disabled="points <= 0 || progress.branches[i]! >= 3"
                @click="grow(i)"
              >
                成长 · 1 点
              </button>
            </article>
          </div>
        </article>
        <div class="skill-guide">
          <article v-for="skill in selectedHero.skills" :key="skill.id">
            <strong>{{ skill.name }}</strong
            ><small
              >{{ elementNames[skill.element] }} · {{ skill.cost }}资源</small
            >
            <p>{{ skill.hint }}</p>
          </article>
        </div>
        <h2 class="pet-title">今天，由谁协战？</h2>
        <div class="pet-list">
          <button
            v-for="id in petIds"
            :key="id"
            :disabled="campaignCount < pets[id].unlock"
            :aria-pressed="memory.pet === id"
            @click="selectPetCamp(id)"
          >
            <span>{{ id === "bird" ? "✦" : id === "droplet" ? "❋" : "◇" }}</span
            ><strong>{{ pets[id].name }}</strong
            ><small>{{ pets[id].hint }}</small
            ><em>{{
              campaignCount < pets[id].unlock
                ? `完成${pets[id].unlock}个任务解锁`
                : `消耗${pets[id].cost}协战能量 · 每轮一次`
            }}</em>
          </button>
        </div>
      </section>
      <section v-else class="gear-panel">
        <div class="gear-hero">
          <label
            >给谁装备<select v-model="heroId">
              <option v-for="id in heroIds" :key="id" :value="id">
                {{ heroes[id].name }}
              </option>
            </select></label
          >
          <p>
            武器：{{ gear.find((g) => g.id === progress.weapon)?.name }} ·
            饰品：{{
              gear.find((g) => g.id === progress.charm)?.name ?? "还没有"
            }}
          </p>
          <small
            >同一件装备可由不同伙伴共享搭配；每人各一个武器和饰品槽。</small
          >
        </div>
        <div class="gear-grid">
          <article
            v-for="item in gear"
            :key="item.id"
            :class="{ owned: memory.owned.includes(item.id) }"
          >
            <small
              >{{ item.slot === "weapon" ? "武器" : "饰品" }} ·
              {{
                item.tier === 0 ? "晨穗" : item.tier === 1 ? "书湾" : "天台"
              }}</small
            >
            <h3>{{ item.name }}</h3>
            <p>{{ item.hint }}</p>
            <button
              v-if="memory.owned.includes(item.id)"
              :disabled="progress[item.slot] === item.id"
              @click="equip(item.id)"
            >
              {{ progress[item.slot] === item.id ? "已装备" : "装备" }}</button
            ><button
              v-else
              :disabled="item.tier > tier || memory.sand < item.price"
              @click="craft(item.id)"
            >
              {{
                item.tier > tier ? "先通关上一章" : `制作 · ${item.price}星砂`
              }}
            </button>
          </article>
        </div>
      </section>
    </template>
    <section
      v-else
      ref="arena"
      class="arena"
      tabindex="0"
      aria-label="回合战场，选技能后方向键选目标，回车确认，退出键暂停"
      @keydown="keyboard"
      @focusout="focusOut"
    >
      <div class="battle-top">
        <span
          >第 <b>{{ battle.round }}</b> 轮</span
        >
        <div class="turn-order" aria-label="本轮行动顺序">
          <span
            v-for="id in battle.queue.slice(battle.cursor)"
            :key="id"
            :class="{
              foe: battle.actors.find((a) => a.id === id)!.side === 'foe',
              current: id === actor?.id,
              dead: battle.actors.find((a) => a.id === id)!.hp === 0,
            }"
            >{{ actorName(battle.actors.find((a) => a.id === id)!) }}</span
          >
        </div>
        <span
          >协战 <b>{{ battle.petCharge }}</b
          >/10</span
        >
      </div>
      <div class="battle-layout">
        <div class="battlefield">
          <div class="field-stars" aria-hidden="true">✧<span>✦</span>✧</div>
          <div
            v-for="side in ['foe', 'party'] as const"
            :key="side"
            class="combat-row"
            :class="side"
          >
            <button
              v-for="unit in battle.actors.filter((a) => a.side === side)"
              :key="unit.id"
              class="unit"
              :class="{
                current: actor?.id === unit.id && !result,
                selected: targetId === unit.id,
                targetable: possible.some((a) => a.id === unit.id),
                fainted: unit.hp === 0,
              }"
              :disabled="!playerTurn || !possible.some((a) => a.id === unit.id)"
              :aria-label="`${actorName(unit)}，${elementNames[unit.element]}属性，生命${unit.hp}/${unit.maxHp}${unit.shield ? `，护盾${unit.shield}` : ''}`"
              :aria-pressed="targetId === unit.id"
              @click="target(unit)"
            >
              <span v-if="side === 'foe' && unit.hp > 0" class="intent">{{
                intentText(battle, unit)
              }}</span
              ><Character
                :kind="unit.kind"
                :element="unit.element"
                :active="actor?.id === unit.id && active && !paused"
                :hurt="flashes.includes(unit.id)"
                :fainted="unit.hp === 0"
              /><strong
                >{{ actorName(unit) }}
                <i :style="{ background: elementColors[unit.element] }">{{
                  elementNames[unit.element]
                }}</i></strong
              ><meter
                :value="unit.hp"
                :max="unit.maxHp"
                :aria-label="actorName(unit) + '生命'"
              /><small
                >{{ unit.hp }}/{{ unit.maxHp }}
                <span v-if="unit.shield">◇{{ unit.shield }}</span></small
              ><small v-if="side === 'party'"
                >资源 {{ unit.mp }}/{{ unit.maxMp }}</small
              >
              <div class="statuses">
                <span
                  v-for="key in statusKeys.filter((k) => unit.status[k])"
                  :key="key"
                  >{{ statusNames[key] }} {{ unit.status[key] }}</span
                >
              </div>
            </button>
          </div>
          <p
            v-if="battle.actors.some((a) => a.kind === 'orrery' && a.hp > 0)"
            class="boss-hint"
          >
            星轨仪当前属性可被「{{
              elementNames[
                counterElement(
                  battle.actors.find((a) => a.kind === "orrery")!.element,
                )
              ]
            }}」克制。两次克制打断星陨，晨灯鸟也可以帮忙。
          </p>
        </div>
        <aside class="commands">
          <template v-if="active"
            ><div class="active-actor">
              <span class="eyebrow">{{
                battle.phase === "player" ? "轮到伙伴了" : "观察敌人的行动"
              }}</span>
              <h2>{{ actor ? actorName(actor) : "" }}</h2>
            </div>
            <template v-if="pending"
              ><div class="selection">
                <strong>{{
                  pending === "pet" ? pets[memory.pet].name : pending.name
                }}</strong>
                <p>
                  {{ pending === "pet" ? pets[memory.pet].hint : pending.hint }}
                </p>
                <small>{{
                  possible.find((a) => a.id === targetId)
                    ? "目标：" +
                      actorName(possible.find((a) => a.id === targetId)!)
                    : "请选择目标"
                }}</small
                ><button
                  class="primary"
                  :disabled="!playerTurn || !targetId"
                  @click="confirm"
                >
                  确认{{ pending === "pet" ? "协战" : "行动" }}</button
                ><button @click="cancelSelection">取消选择</button>
              </div></template
            ><template v-else
              ><div class="skill-buttons">
                <button
                  v-for="skill in currentSkills"
                  :key="skill.id"
                  :disabled="!playerTurn || actor!.mp < skill.cost"
                  @click="selectSkill(skill)"
                >
                  <strong>{{ skill.name }}</strong
                  ><small
                    >{{ elementNames[skill.element] }} ·
                    {{ skill.cost }}资源</small
                  ></button
                ><button class="defend" :disabled="!playerTurn" @click="defend">
                  <strong>防御 · 蓄能</strong><small>减伤并回复资源</small>
                </button>
              </div>
              <button
                class="pet-command"
                :disabled="
                  !playerTurn ||
                  battle.petUsedRound === battle.round ||
                  battle.petCharge < pets[memory.pet].cost
                "
                @click="selectPet"
              >
                {{ pets[memory.pet].name }}协战 ·
                {{ pets[memory.pet].cost }}能量
              </button></template
            >
            <p class="input-tip">
              选技能 → 选目标 → 确认。防御可以恢复资源。
            </p></template
          >
          <div class="battle-log" role="log" aria-label="战斗记录">
            <p
              v-for="(line, i) in battle.log.slice(-5)"
              :key="battle.actions + '-' + i"
            >
              {{ line }}
            </p>
          </div>
        </aside>
      </div>
      <div v-if="paused && active" class="overlay">
        <div class="pause-card">
          <span class="eyebrow">星光，也在等你</span>
          <h2>{{ retreatAsk ? "先回营地调整吗？" : "已暂停" }}</h2>
          <p>
            {{
              retreatAsk
                ? "当前战斗会结束，已经结算的任务、装备和成长保留。"
                : pauseReason
            }}
          </p>
          <div>
            <button class="primary" @click="resume">继续这一轮</button
            ><button v-if="retreatAsk" @click="camp">结束本战并回营地</button
            ><button v-else @click="askRetreat">回营地调整</button>
          </div>
          <small>回合快照已经保存；刷新后从这次行动之后继续。</small>
        </div>
      </div>
      <div v-if="result" class="overlay result">
        <div class="result-card">
          <span class="eyebrow">{{
            battle.phase === "won" ? "把这一束光，带回营地" : "还可以换一种办法"
          }}</span>
          <h2>
            {{
              battle.phase === "won"
                ? campaignCount === 9 && battle.quest === 8
                  ? "星轨，重新亮起来了！"
                  : "这条星路，通了！"
                : "这次，先歇一歇。"
            }}
          </h2>
          <p>{{ battle.round }}轮 · {{ quests[battle.quest]!.name }}</p>
          <p v-if="memory.lastResult?.won">
            获得 {{ memory.lastResult.sand }} 星砂 ·
            {{ gear.find((g) => g.id === memory.lastResult?.drop)?.name
            }}<small>重复获得的装备会转为额外星砂。成长已提交保存。</small>
          </p>
          <div>
            <button class="primary" @click="camp">回营地继续旅程</button
            ><button @click="retry">再挑战一次</button>
          </div>
        </div>
      </div>
    </section>
    <p v-if="feedback" class="feedback" role="status">{{ feedback }}</p>
    <footer v-if="!embedded" class="standalone">
      开发预览 · 仅本次内存。正式页面提供自动保存及导出导入。
    </footer>
  </main>
</template>

<style scoped>
* {
  box-sizing: border-box;
}
button,
select {
  font: inherit;
}
button {
  cursor: pointer;
  touch-action: manipulation;
  min-height: 44px;
  border: 1px solid #d5c7a5;
  border-radius: 12px;
  background: #fffaf0;
  color: #4f452f;
  padding: 10px 15px;
  transition:
    background 0.15s,
    transform 0.15s;
}
button:hover:not(:disabled) {
  background: #fff0b6;
  transform: translateY(-1px);
}
button:focus-visible {
  outline: 3px solid #887024;
  outline-offset: 2px;
}
button:disabled {
  cursor: default;
  opacity: 0.5;
}
button[aria-pressed="true"] {
  background: #f9dc79;
  border-color: #a78935;
}
button.primary {
  background: #dcae36;
  border-color: #c0982a;
  color: #332810;
  font-weight: 750;
}
.starfield {
  --paper: #fff9e9;
  --ink: #50462d;
  min-height: 100vh;
  background: var(--paper);
  color: var(--ink);
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
  padding: 20px 28px 30px;
}
.dark {
  --paper: #272a29;
  --ink: #f4e8bc;
}
.game-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #a98d4636;
  padding: 0 0 15px;
  margin-bottom: 20px;
  gap: 12px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand > span {
  width: 45px;
  height: 45px;
  display: grid;
  place-items: center;
  background: #f5d269;
  font-size: 36px;
  border-radius: 14px;
  color: #97701d;
}
.brand strong {
  font-size: 21px;
  letter-spacing: 2px;
  display: block;
}
.brand small {
  font-size: 11px;
  display: block;
  margin-top: 4px;
  opacity: 0.7;
}
.game-header nav {
  display: flex;
  gap: 8px;
}
.game-header button {
  min-height: 40px;
  padding: 8px 12px;
  font-size: 12px;
}
.camp-top {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: center;
  margin: 14px 0 22px;
}
.eyebrow {
  font-size: 11px;
  letter-spacing: 2px;
  color: #977829;
}
.camp-top h1 {
  font-size: clamp(23px, 3vw, 34px);
  font-weight: 700;
  margin: 8px 0;
}
.camp-top p {
  font-size: 13px;
  line-height: 1.7;
  margin: 0;
  opacity: 0.7;
}
.journey-count {
  display: grid;
  text-align: center;
  border: 1px solid #e9d595;
  border-radius: 50%;
  width: 100px;
  height: 100px;
  align-content: center;
  flex-shrink: 0;
}
.journey-count b {
  font-size: 30px;
}
.journey-count small {
  font-size: 14px;
  color: #a28e5b;
}
.journey-count > span {
  font-size: 10px;
  color: #a28e5b;
}
.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
  align-items: center;
}
.tabs > span {
  margin-left: auto;
  font-size: 12px;
}
.tabs button {
  border-radius: 22px;
  font-size: 13px;
}
.map-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(270px, 1fr);
  gap: 24px;
}
.chapters {
  display: flex;
  gap: 7px;
}
.chapters button {
  font-size: 12px;
  flex: 1;
  padding: 9px 5px;
}
.island-map {
  position: relative;
  min-height: 390px;
  overflow: hidden;
  border-radius: 22px;
  background: linear-gradient(#faf0c866, #f6e5ad55);
  margin-top: 12px;
  border: 1px solid #e7d6a655;
}
.map-caption {
  position: absolute;
  left: 18px;
  top: 17px;
}
.map-caption small {
  font-size: 11px;
  color: #a78b40;
}
.map-caption h2 {
  font-size: 21px;
  letter-spacing: 3px;
  margin: 6px 0;
}
.map-sky {
  position: absolute;
  width: 100%;
  height: 100%;
  inset: 0;
}
.island-node {
  position: absolute;
  transform: translate(-50%, 0);
  padding: 0;
  background: transparent !important;
  border: 0;
  width: 128px;
  display: grid;
  gap: 0;
  color: #6b5525;
}
.island-node:hover:not(:disabled) {
  transform: translate(-50%, -4px);
}
.island-node svg {
  width: 128px;
  height: 85px;
  filter: drop-shadow(0 7px 0 #b1924824);
}
.island-node span {
  display: block;
  font-size: 12px;
  font-weight: 700;
  padding: 7px;
  border-radius: 20px;
  background: #fff9e9;
  border: 1px solid #e4cb86;
}
.island-node.selected span {
  background: #f3cd60;
  box-shadow: 0 0 0 3px #e8c75b44;
}
.island-node.locked {
  opacity: 0.5;
}
.island-node.done span::before {
  content: "✓ ";
  color: #54845e;
}
.quest-card {
  border: 1px solid #dfcc92;
  border-radius: 20px;
  padding: 21px;
  background: #fffdf5;
  box-shadow: 0 9px 30px #95762809;
  display: flex;
  flex-direction: column;
  gap: 10px;
  color: #50462d;
}
.quest-card h2 {
  margin: 0;
  font-size: 25px;
}
.quest-card p {
  margin: 0;
  font-size: 13px;
  line-height: 1.75;
}
.quest-objective {
  padding: 10px;
  background: #f8edc8;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.6;
}
.foe-preview {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}
.foe-preview span {
  background: #e7e4d4;
  border-radius: 10px;
  padding: 4px 7px;
  font-size: 10px;
}
.quest-card .reward-line {
  color: #917327;
  font-size: 11px;
}
.route-choice {
  text-align: left;
  display: grid;
  gap: 5px;
  padding: 11px 13px;
}
.route-choice strong {
  font-size: 13px;
}
.route-choice small {
  font-size: 11px;
  line-height: 1.5;
  opacity: 0.75;
}
.quest-card > small {
  font-size: 11px;
  opacity: 0.7;
}
.route-label {
  font-size: 11px !important;
  color: #8b7234;
}
.locked-note {
  color: #99846a;
}
.formation {
  display: flex;
  gap: 13px;
  max-width: 760px;
  margin: 0 auto 20px;
}
.formation > button {
  flex: 1;
  display: grid;
  justify-items: center;
  min-width: 0;
}
.formation .character {
  height: 115px;
  max-width: 140px;
}
.formation strong {
  font-size: 16px;
}
.formation span,
.formation small {
  font-size: 11px;
  color: #927e4f;
}
.roster {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.roster button {
  display: grid;
  gap: 4px;
  flex: 1;
  min-width: 100px;
  font-size: 14px;
}
.roster small {
  font-size: 11px;
  color: #9a8245;
}
.hero-detail {
  display: grid;
  grid-template-columns: 1fr 2fr;
  gap: 20px;
  background: #fffdf588;
  border: 1px solid #e4d4a6;
  border-radius: 17px;
  padding: 20px;
}
.hero-detail h2 {
  margin: 8px 0;
}
.hero-detail h2 small {
  font-size: 15px;
  font-weight: 400;
}
.hero-detail p {
  font-size: 12px;
  line-height: 1.7;
}
.growth {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.growth > span {
  font-size: 12px;
  align-self: center;
}
.growth > button {
  justify-self: end;
  font-size: 11px;
  min-height: 32px;
  padding: 6px 10px;
}
.growth article {
  border: 1px solid #ddcea5;
  border-radius: 12px;
  padding: 12px;
}
.growth h3 {
  font-size: 14px;
  margin: 0;
}
.growth h3 small {
  float: right;
  color: #a48535;
}
.growth article p {
  min-height: 42px;
  font-size: 11px;
  line-height: 1.6;
}
.growth article button {
  font-size: 12px;
  padding: 6px 12px;
}
.skill-guide {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin: 14px 0;
}
.skill-guide article {
  padding: 12px;
  background: #f5e9bf66;
  border-radius: 12px;
}
.skill-guide strong {
  font-size: 13px;
}
.skill-guide small {
  display: block;
  font-size: 10px;
  margin-top: 4px;
  color: #a18a4e;
}
.skill-guide p {
  font-size: 11px;
  line-height: 1.6;
  margin: 8px 0 0;
  opacity: 0.8;
}
.pet-title {
  font-size: 18px;
  margin-top: 26px;
}
.pet-list {
  display: flex;
  gap: 12px;
}
.pet-list button {
  flex: 1;
  display: grid;
  gap: 7px;
  text-align: left;
}
.pet-list span {
  font-size: 28px;
  color: #b69136;
}
.pet-list small {
  font-size: 11px;
  line-height: 1.6;
}
.pet-list em {
  font-size: 10px;
  font-style: normal;
  color: #9b854b;
}
.gear-hero {
  padding: 16px;
  border-radius: 16px;
  background: #f5e9bf88;
  margin-bottom: 15px;
}
.gear-hero label {
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.gear-hero select {
  min-height: 40px;
  padding: 6px 15px;
  border: 1px solid #d9c698;
  border-radius: 9px;
  background: #fffdf5;
  color: #514733;
}
.gear-hero p {
  font-size: 13px;
}
.gear-hero small {
  font-size: 11px;
  color: #9d8645;
}
.gear-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.gear-grid article {
  border: 1px solid #e0d0a4;
  background: #fffdf588;
  border-radius: 15px;
  padding: 16px;
  display: flex;
  flex-direction: column;
}
.gear-grid article.owned {
  border-color: #c4b067;
}
.gear-grid h3 {
  font-size: 16px;
  margin: 8px 0;
}
.gear-grid small {
  font-size: 10px;
  color: #9c8450;
}
.gear-grid p {
  font-size: 12px;
  line-height: 1.6;
  flex: 1;
}
.gear-grid button {
  font-size: 12px;
  width: 100%;
}
.arena {
  position: relative;
  outline: none;
}
.battle-top {
  display: flex;
  align-items: center;
  gap: 15px;
  font-size: 12px;
  margin-bottom: 15px;
}
.battle-top > span {
  white-space: nowrap;
}
.turn-order {
  display: flex;
  gap: 5px;
  flex: 1;
  overflow: auto;
}
.turn-order span {
  background: #e3ecd9;
  white-space: nowrap;
  border-radius: 9px;
  padding: 7px 10px;
  font-size: 11px;
  border: 1px solid transparent;
}
.turn-order .foe {
  background: #f3ddbd;
}
.turn-order .current {
  border-color: #ab7b15;
  box-shadow: inset 0 0 0 1px #cda13d;
}
.turn-order .dead {
  opacity: 0.4;
  text-decoration: line-through;
}
.battle-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 255px;
  gap: 19px;
}
.battlefield {
  position: relative;
  background: radial-gradient(ellipse at 60% 45%, #fffdf1, #f8eac2);
  border: 1px solid #e4d3a3;
  border-radius: 23px;
  padding: 22px 14px 10px;
  min-height: 470px;
  overflow: hidden;
}
.field-stars {
  position: absolute;
  inset: 20px 35px;
  color: #dbbb60;
  font-size: 30px;
  display: flex;
  justify-content: space-between;
  pointer-events: none;
}
.field-stars span {
  margin-top: 110px;
  font-size: 48px;
  opacity: 0.15;
}
.combat-row {
  position: relative;
  display: flex;
  justify-content: center;
  gap: 10px;
}
.combat-row.foe {
  margin-bottom: 16px;
}
.unit {
  border: 2px solid transparent;
  background: #fffaf066 !important;
  min-width: 0;
  max-width: 170px;
  flex: 1;
  padding: 8px 7px;
  display: grid;
  justify-items: center;
  align-content: end;
  gap: 3px;
  color: #58462a;
  position: relative;
}
.unit:disabled {
  opacity: 1;
  cursor: default;
}
.unit.current {
  border-color: #dac371;
  background: #fff8d8 !important;
}
.unit.fainted {
  opacity: 0.4;
}
.unit.targetable {
  border-color: #93a876;
  cursor: pointer;
}
.unit.selected {
  border-color: #9a7419;
  box-shadow: 0 0 0 3px #e5c57570;
  background: #fff0be !important;
}
.unit .character {
  height: 96px;
  width: 104px;
}
.unit strong {
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 5px;
}
.unit i {
  font-size: 9px;
  color: #fff;
  border-radius: 10px;
  font-style: normal;
  min-width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
}
.unit meter {
  height: 10px;
  width: 95%;
  max-width: 100px;
  appearance: none;
}
.unit meter::-webkit-meter-bar {
  background: #dfd9c7;
  border: 0;
  border-radius: 7px;
}
.unit meter::-webkit-meter-optimum-value {
  background: #85a56b;
  border-radius: 7px;
}
.foe meter::-webkit-meter-optimum-value {
  background: #d89a5e;
}
.unit small {
  font-size: 10px;
}
.unit small span {
  color: #7795a9;
}
.unit .intent {
  font-size: 9px;
  line-height: 1.5;
  min-height: 28px;
  color: #985e38;
  text-align: center;
}
.statuses {
  display: flex;
  gap: 3px;
  flex-wrap: wrap;
  justify-content: center;
  min-height: 15px;
}
.statuses span {
  font-size: 8px;
  padding: 2px 4px;
  background: #ede3c9;
  border-radius: 4px;
}
.boss-hint {
  font-size: 11px;
  line-height: 1.5;
  margin: 8px 2px;
  background: #f4df97;
  padding: 9px;
  border-radius: 9px;
  color: #725821;
}
.commands {
  border: 1px solid #e2d0a0;
  border-radius: 18px;
  background: #fffdf4;
  color: #50462d;
  padding: 17px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.active-actor h2 {
  font-size: 24px;
  margin: 7px 0 0;
}
.active-actor .eyebrow {
  font-size: 10px;
}
.skill-buttons {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 7px;
}
.skill-buttons button {
  padding: 10px 7px;
  min-height: 58px;
  display: grid;
  gap: 6px;
  text-align: left;
}
.skill-buttons strong {
  font-size: 12px;
}
.skill-buttons small {
  font-size: 10px;
  color: #927b44;
}
.skill-buttons .defend {
  grid-column: span 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 44px;
}
.pet-command {
  font-size: 12px;
  background: #ede6f2;
}
.input-tip {
  font-size: 10px;
  line-height: 1.5;
  color: #9e8b61;
  margin: 0;
}
.selection {
  display: grid;
  gap: 11px;
}
.selection strong {
  font-size: 18px;
}
.selection p {
  font-size: 12px;
  line-height: 1.7;
  margin: 0;
}
.selection small {
  font-size: 12px;
  color: #977622;
}
.battle-log {
  margin-top: auto;
  border-top: 1px solid #e9dec1;
  padding-top: 6px;
}
.battle-log p {
  font-size: 10px;
  line-height: 1.6;
  margin: 3px 0;
  color: #8e7b50;
}
.battle-log p:last-child {
  color: #5e4b23;
}
.overlay {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 22px;
  background: #fff4d4b3;
  backdrop-filter: blur(5px);
  z-index: 3;
  border-radius: 20px;
}
.pause-card,
.result-card {
  max-width: 490px;
  width: 100%;
  padding: 35px 28px;
  background: #fffaf0;
  border: 1px solid #cfbb83;
  border-radius: 23px;
  box-shadow: 0 15px 50px #80601b15;
  text-align: center;
  color: #59482b;
}
.overlay h2 {
  font-size: 27px;
  margin: 15px 0;
}
.overlay p {
  font-size: 13px;
  line-height: 1.8;
}
.overlay p small {
  display: block;
  font-size: 10px;
  margin: 9px;
}
.overlay div > div {
  display: flex;
  gap: 9px;
  flex-wrap: wrap;
  justify-content: center;
  margin: 22px 0 15px;
}
.overlay small {
  display: block;
  font-size: 10px;
  line-height: 1.6;
  color: #a18a56;
}
.feedback {
  font-size: 12px;
  line-height: 1.8;
  border-radius: 10px;
  padding: 10px 14px;
  background: #f4e5b4;
}
.standalone {
  font-size: 10px;
  color: #a18b5b;
  padding-top: 20px;
  text-align: center;
}
.dark .hero-detail,
.dark .gear-grid article {
  background: #36372e;
}
.dark .camp-top,
.dark .pet-title {
  color: #f4e7ba;
}
.dark .formation,
.dark .roster,
.dark .gear-hero,
.dark .growth article,
.dark .skill-guide {
  color: #57492e;
}
@media (max-width: 900px) {
  .starfield {
    padding: 17px;
  }
  .map-layout {
    grid-template-columns: 1.3fr 1fr;
    gap: 15px;
  }
  .island-map {
    min-height: 410px;
  }
  .island-node {
    width: 105px;
  }
  .island-node svg {
    width: 105px;
  }
  .island-node span {
    font-size: 10px;
  }
  .gear-grid {
    grid-template-columns: repeat(3, 1fr);
  }
  .battle-layout {
    grid-template-columns: minmax(0, 1fr) 220px;
    gap: 12px;
  }
  .commands {
    padding: 12px;
  }
  .unit .character {
    width: 80px;
    height: 80px;
  }
  .unit .intent {
    font-size: 8px;
  }
  .unit {
    padding: 6px 3px;
  }
  .battlefield {
    padding: 15px 5px;
  }
  .combat-row {
    gap: 3px;
  }
  .hero-detail {
    grid-template-columns: 1fr;
  }
  .map-caption h2 {
    font-size: 18px;
  }
}
@media (max-width: 620px) {
  .starfield {
    padding: 14px 12px 24px;
  }
  .game-header {
    gap: 7px;
    margin-bottom: 14px;
  }
  .brand {
    gap: 8px;
  }
  .brand > span {
    width: 36px;
    height: 36px;
    font-size: 28px;
  }
  .brand strong {
    font-size: 17px;
  }
  .brand small {
    font-size: 9px;
  }
  .game-header nav {
    gap: 5px;
  }
  .game-header button {
    font-size: 10px;
    min-height: 38px;
    padding: 7px;
  }
  .camp-top {
    gap: 8px;
    margin-bottom: 17px;
  }
  .camp-top h1 {
    font-size: 22px;
  }
  .camp-top p {
    font-size: 11px;
  }
  .eyebrow {
    font-size: 9px;
    letter-spacing: 1px;
  }
  .journey-count {
    width: 65px;
    height: 65px;
  }
  .journey-count b {
    font-size: 23px;
  }
  .journey-count > span {
    font-size: 8px;
  }
  .tabs {
    gap: 5px;
    flex-wrap: wrap;
    margin-bottom: 12px;
  }
  .tabs button {
    font-size: 11px;
    min-height: 42px;
    padding: 8px 11px;
  }
  .tabs > span {
    width: 100%;
    text-align: right;
    font-size: 11px;
  }
  .map-layout {
    grid-template-columns: 1fr;
  }
  .chapters button {
    font-size: 10px;
  }
  .island-map {
    min-height: 315px;
  }
  .map-caption {
    top: 12px;
    left: 13px;
  }
  .map-caption small {
    font-size: 9px;
  }
  .map-caption h2 {
    font-size: 17px;
  }
  .island-node {
    width: 100px;
  }
  .island-node svg {
    width: 100px;
    height: 67px;
  }
  .island-node span {
    padding: 6px 3px;
    font-size: 10px;
  }
  .quest-card {
    padding: 17px;
    gap: 9px;
  }
  .quest-card h2 {
    font-size: 23px;
  }
  .formation {
    gap: 6px;
    margin-bottom: 12px;
  }
  .formation > button {
    padding: 8px 3px;
  }
  .formation .character {
    height: 86px;
  }
  .formation strong {
    font-size: 13px;
  }
  .formation span {
    font-size: 9px;
  }
  .formation small {
    font-size: 10px;
  }
  .roster {
    gap: 6px;
  }
  .roster button {
    min-width: 29%;
    font-size: 12px;
    padding: 8px;
  }
  .hero-detail {
    padding: 13px;
    gap: 14px;
  }
  .growth {
    gap: 8px;
  }
  .growth article {
    padding: 10px;
  }
  .growth article p {
    font-size: 10px;
    min-height: 50px;
  }
  .skill-guide {
    grid-template-columns: 1fr 1fr;
  }
  .pet-list {
    flex-direction: column;
    gap: 8px;
  }
  .pet-list button {
    grid-template-columns: 35px 1fr;
    gap: 4px 9px;
  }
  .pet-list button > span {
    grid-row: span 3;
    align-self: center;
  }
  .pet-list em {
    font-size: 9px;
  }
  .gear-grid {
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  .gear-grid article {
    padding: 12px;
  }
  .gear-grid h3 {
    font-size: 14px;
  }
  .gear-grid p {
    font-size: 11px;
  }
  .gear-hero small {
    font-size: 10px;
    line-height: 1.6;
    display: block;
  }
  .battle-layout {
    grid-template-columns: 1fr;
  }
  .battle-top {
    gap: 8px;
    font-size: 10px;
    flex-wrap: wrap;
    margin-bottom: 10px;
  }
  .turn-order {
    order: 3;
    flex-basis: 100%;
    padding-bottom: 3px;
  }
  .turn-order span {
    font-size: 9px;
    padding: 6px 8px;
  }
  .battlefield {
    min-height: 0;
    padding: 12px 4px 8px;
    border-radius: 16px;
  }
  .combat-row.foe {
    margin-bottom: 11px;
  }
  .unit {
    padding: 6px 2px;
    border-radius: 10px;
    max-width: 110px;
  }
  .unit .character {
    height: 66px;
    width: 73px;
  }
  .unit strong {
    font-size: 10px;
    gap: 2px;
  }
  .unit i {
    font-size: 8px;
    min-width: 15px;
    height: 15px;
  }
  .unit small {
    font-size: 9px;
  }
  .unit .intent {
    font-size: 8px;
    min-height: 35px;
    line-height: 1.4;
  }
  .statuses span {
    font-size: 7px;
  }
  .unit meter {
    height: 8px;
  }
  .commands {
    padding: 12px;
    gap: 9px;
  }
  .active-actor {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .active-actor h2 {
    font-size: 18px;
    margin: 0;
  }
  .skill-buttons {
    grid-template-columns: 1fr 1fr;
    gap: 7px;
  }
  .skill-buttons button {
    min-height: 48px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .skill-buttons strong {
    font-size: 11px;
  }
  .skill-buttons small {
    font-size: 9px;
  }
  .skill-buttons .defend {
    min-height: 44px;
  }
  .battle-log {
    max-height: 90px;
    overflow: auto;
  }
  .selection {
    gap: 8px;
  }
  .selection p {
    font-size: 11px;
  }
  .selection strong {
    font-size: 17px;
  }
  .overlay {
    padding: 12px;
    position: fixed;
    inset: 0;
    border-radius: 0;
  }
  .pause-card,
  .result-card {
    padding: 25px 18px;
  }
  .overlay h2 {
    font-size: 24px;
  }
  .overlay p {
    font-size: 12px;
  }
  .overlay small {
    font-size: 9px;
  }
  .boss-hint {
    font-size: 10px;
  }
  .field-stars {
    font-size: 20px;
    inset: 16px;
  }
  .pet-title {
    font-size: 17px;
  }
}
</style>
