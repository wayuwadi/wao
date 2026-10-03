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
  chapters,
  enemyNames,
  equipment,
  heroNames,
  skills,
  type GearId,
  type HeroClass,
} from "./catalog";
import {
  attackPressed,
  attackReleased,
  beginEncounter,
  castSkill,
  createEncounter,
  dodge,
  jump,
  pauseEncounter,
  stepEncounter,
  type Encounter,
} from "./engine";
import {
  cloneMemory,
  freshRiftMemory,
  level,
  pointBudget,
  rewardRoom,
  startChapter,
  type RiftMemory,
} from "./save";
import { RiftInput } from "./input";
import { RiftAudio } from "./audio";
import { renderRift } from "./render";
import { envelope, parentCommand, type ChildEvent } from "../../protocol/rift";

const embedded = window.parent !== window,
  instance = new URLSearchParams(location.search).get("instance") ?? "";
const memory = ref<RiftMemory>(freshRiftMemory()),
  initialized = ref(!embedded),
  view = ref<"camp" | "fight" | "result">("camp"),
  campTab = ref<"map" | "gear" | "skills">("map");
const arena = ref<HTMLElement>(),
  canvas = ref<HTMLCanvasElement>(),
  run = shallowRef<Encounter>(),
  frame = ref(0),
  dark = ref(false),
  feedback = ref(""),
  pauseReason = ref("歇一口气，准备好再出发。"),
  reward = ref<{ gear: GearId | null; leveled: boolean }>();
const audio = new RiftAudio();
const diagnostics =
  import.meta.env.DEV &&
  new URLSearchParams(location.search).get("qa") === "performance";
const frameCost = ref("尚未采样");
const costs: number[] = [];
let measuredFrames = 0,
  totalCost = 0,
  maxCost = 0;
const tapCharge = ref(false),
  chargeLocked = ref(false);
let input: RiftInput | undefined,
  observer: ResizeObserver | undefined,
  raf = 0,
  lastFrame = 0,
  accumulator = 0,
  sequence = 0,
  received = 0,
  lifecycle = 0,
  readyTimer: ReturnType<typeof setInterval> | undefined;
// The engine is deliberately non-reactive. Publish a fresh view each frame so
// Vue's computed-value equality cannot suppress HUD and control updates.
const state = computed(() => {
  void frame.value;
  return run.value ? { ...run.value } : undefined;
});
const hero = computed(() => memory.value.heroes[memory.value.selected]);
const availablePoints = computed(
  () => pointBudget(hero.value) - hero.value.ranks.reduce((a, b) => a + b, 0),
);
const unlockedTier = computed(() =>
  memory.value.chapterWins[1]! > 0
    ? 2
    : memory.value.chapterWins[0]! > 0
      ? 1
      : 0,
);
const weapon = computed(() =>
  equipment.find((e) => e.id === hero.value.weapon)!,
);
const charm = computed(() => equipment.find((e) => e.id === hero.value.charm));
const skillKeys = ["K", "L", "U"];
const threat = computed(() => {
  const s = state.value;
  if (!s) return "";
  if (s.incoming.length)
    return `增援 ${s.arrival.toFixed(1)} 秒后抵达 · 留意两侧裂隙`;
  const alive = s.enemies.filter((e) => e.hp > 0 && e.kind !== "decoy");
  const boss = alive.find((e) => ["quake", "prism", "mirror"].includes(e.kind));
  if (boss) {
    if (boss.kind === "prism")
      return s.enemies.some((e) => e.kind === "crystal" && e.hp > 0)
        ? `供能晶体 ${s.enemies.filter((e) => e.kind === "crystal" && e.hp > 0).length}/2 · 先断能`
        : `破盾窗口 ${Math.ceil(boss.shieldWindow)} 秒 · 集中输出`;
    if (boss.kind === "mirror") return "辨识脚下金环 · 分身会反击";
    if (boss.state === "windup")
      return boss.pattern % 2 === 0
        ? "震地蓄力 · 跳起来！"
        : "冲撞蓄力 · 离开冲撞线！";
    return boss.state === "recover"
      ? "首领收招 · 可以反击"
      : "留意首领下一次蓄力";
  }
  const next = alive.sort(
    (a, b) => Math.abs(a.x - s.player.x) - Math.abs(b.x - s.player.x),
  )[0];
  return next
    ? `${next.x < s.player.x ? "←" : "→"} ${next.elite ? "精英 " : ""}${enemyNames[next.kind]} · ${next.state === "windup" ? "正在蓄力" : `${Math.round(Math.abs(next.x - s.player.x) / 10)}步`}`
    : "这片裂隙已平息";
});
const bossHealth = computed(() =>
  state.value?.enemies.find((e) =>
    ["quake", "prism", "mirror"].includes(e.kind),
  ),
);
function send(event: ChildEvent) {
  if (embedded)
    window.parent.postMessage(
      envelope(event, instance, ++sequence),
      location.origin,
    );
}
function save() {
  send({ type: "checkpoint", payload: cloneMemory(memory.value) });
}
function publishPhase() {
  const phase = run.value?.phase;
  send({
    type: "state",
    payload: {
      phase:
        view.value === "camp"
          ? "ready"
          : phase === "lost"
            ? "crashed"
            : (phase ?? "ready"),
    },
  });
}
function draw() {
  if (canvas.value && run.value)
    renderRift(
      canvas.value,
      run.value,
      dark.value,
      matchMedia("(prefers-reduced-motion: reduce)").matches,
    );
  frame.value++;
}
function stop() {
  lifecycle++;
  cancelAnimationFrame(raf);
  raf = 0;
  lastFrame = 0;
  accumulator = 0;
  chargeLocked.value = false;
  input?.clear();
  audio.stop();
}
function pause(reason = "已暂停。按继续恢复战斗。") {
  stop();
  if (run.value) pauseEncounter(run.value);
  pauseReason.value = reason;
  draw();
  publishPhase();
}
function finish() {
  stop();
  const s = run.value;
  if (!s) return;
  if (s.phase === "won") {
    const oldLevel = level(hero.value.xp),
      result = rewardRoom(memory.value, s.checkpoint, s.time * 1000);
    memory.value = result.memory;
    reward.value = {
      gear: result.gear,
      leveled: level(hero.value.xp) > oldLevel,
    };
    save();
  }
  view.value = "result";
  publishPhase();
  draw();
  const ticket = lifecycle;
  void audio.unlock().then(() => {
    if (ticket === lifecycle) audio.play(s.phase === "won" ? "win" : "lose");
  });
}
function tick(now: number) {
  const s = run.value;
  if (!s || s.phase !== "running" || view.value !== "fight") {
    raf = 0;
    return;
  }
  const measuredStart = diagnostics ? performance.now() : 0;
  if (lastFrame) accumulator += Math.min(0.05, (now - lastFrame) / 1000);
  lastFrame = now;
  const hp = s.player.hp;
  while (accumulator >= 1 / 120) {
    stepEncounter(
      s,
      1 / 120,
      input?.controls ?? {
        left: false,
        right: false,
        down: false,
        attackHeld: false,
      },
    );
    accumulator -= 1 / 120;
  }
  if (s.player.hp < hp) audio.play("hit");
  draw();
  if (diagnostics) {
    const cost = performance.now() - measuredStart;
    measuredFrames++;
    totalCost += cost;
    maxCost = Math.max(maxCost, cost);
    costs.push(cost);
    if (costs.length > 600) costs.shift();
    if (measuredFrames % 30 === 0) {
      const sorted = [...costs].sort((a, b) => a - b);
      frameCost.value = `${measuredFrames}帧 · CPU均值 ${(totalCost / measuredFrames).toFixed(2)}ms · 最近600帧P95 ${(sorted[Math.floor(sorted.length * 0.95)] ?? 0).toFixed(2)}ms · 最大 ${maxCost.toFixed(2)}ms`;
    }
  }
  if (["won", "lost"].includes(s.phase)) {
    finish();
    return;
  }
  raf = requestAnimationFrame(tick);
}
async function resume() {
  if (!run.value || !initialized.value) return;
  stop();
  const ticket = lifecycle;
  view.value = "fight";
  beginEncounter(run.value);
  draw();
  await nextTick();
  if (ticket !== lifecycle) return;
  arena.value?.focus({ preventScroll: true });
  await audio.unlock();
  if (ticket !== lifecycle || run.value.phase !== "running") return;
  lastFrame = 0;
  raf = requestAnimationFrame(tick);
  publishPhase();
}
async function loadCheckpoint() {
  if (!memory.value.checkpoint) return;
  stop();
  feedback.value = "";
  reward.value = undefined;
  run.value = createEncounter(memory.value, memory.value.checkpoint);
  view.value = "fight";
  await nextTick();
  draw();
  await resume();
}
async function enter(chapter: number) {
  try {
    memory.value = startChapter(
      memory.value,
      chapter,
      crypto.getRandomValues(new Uint32Array(1))[0]!,
    );
    save();
    await loadCheckpoint();
  } catch (e) {
    feedback.value = e instanceof Error ? e.message : "无法进入关卡。";
  }
}
function camp() {
  stop();
  view.value = "camp";
  feedback.value = memory.value.checkpoint
    ? "检查点已保留。继续时从当前房间重新开始。"
    : "";
  publishPhase();
}
function selectHero(kind: HeroClass) {
  if (memory.value.selected === kind) return;
  memory.value.selected = kind;
  memory.value.checkpoint = null;
  save();
  feedback.value = "已更换职业，永久成长保留。当前远征回到营地。";
}
function equip(id: GearId) {
  const gear = equipment.find((e) => e.id === id)!;
  if (!memory.value.owned.includes(id)) return;
  if (gear.slot === "weapon" && gear.hero === memory.value.selected)
    hero.value.weapon = id;
  if (gear.slot === "charm") hero.value.charm = id;
  save();
  feedback.value = `已装备${gear.name}。下次进入房间生效。`;
}
function craft(id: GearId) {
  const gear = equipment.find((e) => e.id === id)!,
    cost = 25 + gear.tier * 25;
  if (
    memory.value.owned.includes(id) ||
    gear.tier > unlockedTier.value ||
    memory.value.materials < cost
  )
    return;
  memory.value.materials -= cost;
  memory.value.owned.push(id);
  save();
  feedback.value = `打造完成：${gear.name}。`;
}
function upgrade(id: number) {
  if (availablePoints.value <= 0 || hero.value.ranks[id]! >= 3) return;
  hero.value.ranks[id] = hero.value.ranks[id]! + 1;
  save();
}
function resetSkills() {
  hero.value.ranks = [1, 1, 1, 0, 0, 0];
  hero.value.slots = [0, 1, 2];
  save();
  feedback.value = "技能点已免费返还，可以重新搭配。";
}
function slotChange(slot: number, event: Event) {
  const id = Number((event.target as HTMLSelectElement).value),
    current = hero.value.slots[slot]!,
    other = hero.value.slots.indexOf(id);
  if (!Number.isInteger(id) || id < 0 || id > 5 || hero.value.ranks[id]! < 1)
    return;
  if (other >= 0) hero.value.slots[other] = current;
  hero.value.slots[slot] = id;
  save();
}
async function toggleSound() {
  memory.value.muted = !memory.value.muted;
  audio.muted = memory.value.muted;
  if (audio.muted) audio.stop();
  else await audio.unlock();
  save();
}
function touch(action: string, e: PointerEvent) {
  e.preventDefault();
  (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  if (
    action === "attack" &&
    memory.value.selected === "lancer" &&
    tapCharge.value
  ) {
    if (chargeLocked.value) {
      input?.touchUp(action);
      chargeLocked.value = false;
    } else {
      input?.touchDown(action);
      chargeLocked.value = true;
    }
  } else input?.touchDown(action);
}
function release(action: string, e: PointerEvent, cancelled = false) {
  e.preventDefault();
  if (cancelled) {
    chargeLocked.value = false;
    input?.touchUp(action, true);
    return;
  }
  if (
    action === "attack" &&
    tapCharge.value &&
    memory.value.selected === "lancer"
  )
    return;
  input?.touchUp(action);
}
function toggleChargeMode() {
  chargeLocked.value = false;
  input?.clear();
  tapCharge.value = !tapCharge.value;
}
function keyboardClick(action: string, e: MouseEvent) {
  if (e.detail === 0) {
    input?.touchDown(action);
    input?.touchUp(action);
  }
}
function focusArena(e: PointerEvent) {
  if (e.target === canvas.value) arena.value?.focus({ preventScroll: true });
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
    memory.value = cloneMemory(cmd.payload.memory);
    audio.muted = memory.value.muted;
    dark.value = cmd.payload.theme === "dark";
    initialized.value = true;
    view.value = "camp";
    clearInterval(readyTimer);
    publishPhase();
  } else if (cmd.type === "pause") pause(cmd.payload.reason);
  else if (cmd.type === "dispose") {
    pause();
    send({ type: "disposed", payload: null });
  } else if (cmd.type === "snapshot-request")
    send({ type: "snapshot-result", payload: cloneMemory(memory.value) });
  else if (cmd.type === "theme") {
    dark.value = cmd.payload.theme === "dark";
    draw();
  } else if (cmd.type === "audio") {
    memory.value.muted = cmd.payload.muted;
    audio.muted = cmd.payload.muted;
    if (audio.muted) audio.stop();
    save();
  } else if (cmd.type === "viewport") draw();
  // A parent resume command never bypasses the user's explicit continue button.
}
onMounted(() => {
  input = new RiftInput(arena.value!, {
    active: () => view.value === "fight" && run.value?.phase === "running",
    turn: (direction) => {
      if (run.value) run.value.player.facing = direction;
    },
    attack: () => {
      if (run.value) {
        attackPressed(run.value);
        if (memory.value.selected === "blade") audio.play("swing");
      }
    },
    releaseAttack: () => {
      if (run.value && attackReleased(run.value)) audio.play("shot");
    },
    jump: () => {
      if (run.value && jump(run.value)) audio.play("jump");
    },
    dodge: (direction) => {
      if (run.value && dodge(run.value, direction)) audio.play("dodge");
    },
    skill: (slot) => {
      if (run.value && castSkill(run.value, slot)) audio.play("skill");
    },
    pause: () => pause("已暂停：离开舞台、切换标签或按下暂停。"),
    restart: () => void loadCheckpoint(),
  });
  observer = new ResizeObserver(draw);
  if (canvas.value) observer.observe(canvas.value);
  window.addEventListener("message", receive);
  if (embedded) {
    const ready = () => send({ type: "ready", payload: { rulesVersion: 1 } });
    ready();
    readyTimer = setInterval(ready, 500);
  }
});
onBeforeUnmount(() => {
  stop();
  input?.dispose();
  observer?.disconnect();
  audio.dispose();
  clearInterval(readyTimer);
  window.removeEventListener("message", receive);
});
</script>

<template>
  <main class="rift" :class="{ dark, combat: view !== 'camp' }">
    <header class="game-header">
      <div>
        <b class="emblem">✦</b
        ><span
          ><strong>裂隙远征</strong
          ><small>{{
            view === "camp"
              ? "琥珀营地"
              : chapters[state!.checkpoint.chapter]!.rooms[
                  state!.checkpoint.room
                ]
          }}</small></span
        >
      </div>
      <nav>
        <button :aria-pressed="!memory.muted" @click="toggleSound">
          {{ memory.muted ? "音效关" : "音效开" }}</button
        ><button
          v-if="view === 'fight' && state?.phase === 'running'"
          @click="pause()"
        >
          暂停</button
        ><button v-if="view !== 'camp'" @click="camp">回营地</button>
      </nav>
    </header>
    <p v-if="!embedded" class="development-note">
      开发预览 · 仅本次游玩；正在接入正式游戏记忆
    </p>
    <section v-if="view === 'camp'" class="camp-panel">
      <div class="camp-intro">
        <span class="eyebrow">把勇气，装进口袋</span>
        <h1>下一站，越过裂隙。</h1>
        <p>近战读招，或蓄力拉开距离。每一趟远征，都有新搭配。</p>
      </div>
      <div class="hero-switch">
        <button
          v-for="kind in ['blade', 'lancer'] as const"
          :key="kind"
          :aria-pressed="memory.selected === kind"
          @click="selectHero(kind)"
        >
          <b>{{ kind === "blade" ? "⚔" : "✧" }} {{ heroNames[kind] }}</b
          ><span>{{
            kind === "blade" ? "三段连击 · 短窗弹反" : "蓄力射击 · 位移拉扯"
          }}</span
          ><small>Lv.{{ level(memory.heroes[kind].xp) }}</small>
        </button>
      </div>
      <div class="growth-bar">
        <span
          >{{ weapon.name
          }}<template v-if="charm"> · {{ charm.name }}</template></span
        ><span
          >锻造材料 <b>{{ memory.materials }}</b> · 可用技能点
          <b>{{ availablePoints }}</b></span
        >
      </div>
      <nav class="camp-tabs" aria-label="营地面板">
        <button :aria-pressed="campTab === 'map'" @click="campTab = 'map'">
          远征地图</button
        ><button :aria-pressed="campTab === 'gear'" @click="campTab = 'gear'">
          装备与锻造</button
        ><button
          :aria-pressed="campTab === 'skills'"
          @click="campTab = 'skills'"
        >
          技能搭配
        </button>
      </nav>
      <p v-if="feedback" class="feedback" role="status">{{ feedback }}</p>
      <div v-if="campTab === 'map'" class="map-panel">
        <button
          v-if="memory.checkpoint"
          class="checkpoint"
          :disabled="!initialized"
          @click="loadCheckpoint"
        >
          继续检查点 ↗
          <span
            >{{
              chapters[memory.checkpoint.chapter]!.rooms[memory.checkpoint.room]
            }}
            · 从房间开始恢复</span
          >
        </button>
        <div class="chapter-grid">
          <article
            v-for="(chapter, i) in chapters"
            :key="chapter.name"
            class="chapter-card"
            :class="`chapter-${i}`"
          >
            <span class="chapter-number">0{{ i + 1 }}</span>
            <h2>{{ chapter.name }}</h2>
            <p>{{ chapter.subtitle }}</p>
            <span class="room-list">{{ chapter.rooms.join(" → ") }}</span
            ><small
              >通关 {{ memory.chapterWins[i] }} 次<template
                v-if="memory.bestTimes[i]"
              >
                · 首领最快
                {{ (memory.bestTimes[i]! / 1000).toFixed(1) }} 秒</template
              ></small
            ><button
              class="primary"
              :disabled="!initialized || (i > 0 && !memory.chapterWins[i - 1])"
              @click="enter(i)"
            >
              {{
                i > 0 && !memory.chapterWins[i - 1]
                  ? "先通关上一章"
                  : "进入远征 ↗"
              }}
            </button>
          </article>
        </div>
        <p class="quiet">
          每章两段战斗与独立首领房。房间奖励即时记入检查点；失败可以重试，不丢已结算成长。
        </p>
      </div>
      <div v-else-if="campTab === 'gear'" class="equipment-grid">
        <article
          v-for="gear in equipment"
          :key="gear.id"
          :class="{
            equipped: hero.weapon === gear.id || hero.charm === gear.id,
          }"
        >
          <small
            >{{
              gear.slot === "weapon"
                ? gear.hero === "blade"
                  ? "剑刃武器"
                  : "星枪武器"
                : "通用护符"
            }}
            · 第{{ gear.tier + 1 }}阶</small
          >
          <h3>{{ gear.name }}</h3>
          <p>{{ gear.description }}</p>
          <button
            v-if="memory.owned.includes(gear.id)"
            :disabled="
              (gear.slot === 'weapon' && gear.hero !== memory.selected) ||
              hero.weapon === gear.id ||
              hero.charm === gear.id
            "
            @click="equip(gear.id)"
          >
            {{
              hero.weapon === gear.id || hero.charm === gear.id
                ? "已装备"
                : "装备"
            }}</button
          ><button
            v-else
            :disabled="
              gear.tier > unlockedTier || memory.materials < 25 + gear.tier * 25
            "
            @click="craft(gear.id)"
          >
            {{
              gear.tier > unlockedTier
                ? "通关前章解锁"
                : `打造 · ${25 + gear.tier * 25} 材料`
            }}
          </button>
        </article>
      </div>
      <div v-else class="skills-panel">
        <div class="skill-slots">
          <label v-for="(key, slot) in skillKeys" :key="key"
            >主动槽 {{ key
            }}<select
              :value="hero.slots[slot]"
              @change="slotChange(slot, $event)"
            >
              <option
                v-for="(skill, i) in skills[memory.selected]"
                :key="skill.name"
                :value="i"
                :disabled="!hero.ranks[i]"
              >
                {{ skill.name }}{{ !hero.ranks[i] ? " · 未学习" : "" }}
              </option>
            </select></label
          ><button @click="resetSkills">免费重配</button>
        </div>
        <div class="skill-grid">
          <article
            v-for="(skill, i) in skills[memory.selected]"
            :key="skill.name"
          >
            <small
              >阶级 {{ hero.ranks[i] }}/3 · 能量 {{ skill.cost }} · 冷却
              {{ skill.cooldown }}秒</small
            >
            <h3>{{ skill.name }}</h3>
            <p>{{ skill.description }}</p>
            <button
              :disabled="availablePoints < 1 || hero.ranks[i]! >= 3"
              @click="upgrade(i)"
            >
              {{ hero.ranks[i] ? "升阶 · 1 技能点" : "学习 · 1 技能点" }}
            </button>
          </article>
        </div>
      </div>
    </section>
    <section
      ref="arena"
      v-show="view !== 'camp'"
      class="arena"
      tabindex="0"
      aria-label="裂隙战场，方向移动，空格跳，J攻击，K L U技能，Shift闪避，P暂停"
      @pointerdown="focusArena"
    >
      <div v-if="state" class="combat-hud">
        <div class="hero-bars">
          <strong
            >{{ heroNames[memory.selected] }}
            <small>Lv.{{ level(hero.xp) }}</small></strong
          ><label
            >生命 {{ Math.ceil(state.player.hp) }}/{{ state.player.maxHp
            }}<meter min="0" :max="state.player.maxHp" :value="state.player.hp"
          /></label>
          <div>
            <span>体力 {{ Math.floor(state.player.stamina) }}</span
            ><span>能量 {{ Math.floor(state.player.energy) }}</span
            ><span v-if="memory.selected === 'lancer'">{{
              state.player.charge >= 0.62 / state.stats.attackSpeed
                ? "蓄力就绪"
                : `蓄力 ${Math.min(100, Math.floor((state.player.charge / (0.62 / state.stats.attackSpeed)) * 100))}%`
            }}</span>
          </div>
        </div>
        <div class="room-goal">
          <span>{{ threat }}</span
          ><template v-if="bossHealth"
            ><b
              >{{ enemyNames[bossHealth.kind] }}
              {{ Math.ceil(bossHealth.hp) }}/{{ bossHealth.maxHp }}</b
            ><meter
              min="0"
              :max="bossHealth.maxHp"
              :value="bossHealth.hp" /></template
          ><small v-else
            >遭遇 {{ state.wave }}/{{ state.totalWaves }} · 剩余敌人
            {{ state.enemies.filter((e) => e.hp > 0).length }} ·
            {{ state.time.toFixed(1) }} 秒</small
          >
        </div>
      </div>
      <canvas
        ref="canvas"
        :aria-label="`${chapters[state?.checkpoint.chapter ?? 0]!.name}中的原创远征小队与裂隙敌人`"
      />
      <div v-if="state?.phase === 'paused' && view === 'fight'" class="veil">
        <section class="pause-card">
          <span class="eyebrow">刀光，也歇一会儿</span>
          <h2>已暂停</h2>
          <p>{{ pauseReason }}</p>
          <button class="primary" @click="resume">继续战斗</button
          ><button @click="loadCheckpoint">重试当前房间</button
          ><button @click="camp">回营地</button
          ><small>重试从房间开始，已经结算的奖励保留。</small>
        </section>
      </div>
      <div v-if="view === 'result' && state" class="veil">
        <section class="result-card">
          <span class="eyebrow">{{
            state.phase === "won" ? "把收获，带回营地" : "下一次，读准前摇"
          }}</span>
          <h2>
            {{
              state.phase === "won"
                ? state.checkpoint.room === 2
                  ? "这一章，穿过去了！"
                  : "裂隙平息了。"
                : "远征暂时停在这里。"
            }}
          </h2>
          <p>
            {{ state.time.toFixed(1) }} 秒 · {{ state.comboHits }} 次命中 ·
            {{ state.parries }} 次弹反
          </p>
          <template v-if="state.phase === 'won'"
            ><p v-if="reward?.gear" class="loot">
              获得 {{ equipment.find((e) => e.id === reward!.gear)?.name
              }}<small>已有装备自动转成锻造材料</small>
            </p>
            <p v-if="reward?.leveled">升级了！营地可以分配新的技能点。</p>
            <button
              v-if="memory.checkpoint"
              class="primary"
              @click="loadCheckpoint"
            >
              前往下一房间 ↗</button
            ><button class="primary" @click="camp">
              {{ memory.checkpoint ? "回营地调整搭配" : "回营地领取新旅程" }}
            </button></template
          ><template v-else
            ><p>留意地面预警和蓄力。已结算成长不会丢失。</p>
            <button class="primary" @click="loadCheckpoint">从检查点再来</button
            ><button @click="camp">回营地调整搭配</button></template
          ><small>记忆恢复到安全检查点，不恢复半次挥刀。</small>
        </section>
      </div>
    </section>
    <footer v-if="view !== 'camp'" class="controls">
      <div class="move-controls">
        <button
          v-for="action in ['left', 'right', 'jump']"
          :key="action"
          :disabled="state?.phase !== 'running'"
          @pointerdown="touch(action, $event)"
          @pointerup="release(action, $event)"
          @pointercancel="release(action, $event, true)"
          @click="keyboardClick(action, $event)"
        >
          {{ action === "left" ? "←" : action === "right" ? "→" : "跳跃" }}
        </button>
      </div>
      <div class="action-controls">
        <button
          class="attack-button"
          :disabled="state?.phase !== 'running'"
          @pointerdown="touch('attack', $event)"
          @pointerup="release('attack', $event)"
          @pointercancel="release('attack', $event, true)"
          @click="keyboardClick('attack', $event)"
        >
          {{
            memory.selected === "blade"
              ? "攻击 J"
              : chargeLocked
                ? "发射 J"
                : "蓄力 J"
          }}</button
        ><button
          :disabled="state?.phase !== 'running'"
          @pointerdown="touch('dodge', $event)"
          @pointerup="release('dodge', $event)"
          @pointercancel="release('dodge', $event, true)"
          @click="keyboardClick('dodge', $event)"
        >
          闪避
        </button>
      </div>
      <div class="skill-controls">
        <button
          v-for="(id, slot) in hero.slots"
          :key="slot"
          :disabled="
            state?.phase !== 'running' ||
            state!.player.cooldowns[id]! > 0 ||
            state!.player.energy < skills[memory.selected][id]!.cost
          "
          @pointerdown="touch(`skill${slot}`, $event)"
          @pointerup="release(`skill${slot}`, $event)"
          @pointercancel="release(`skill${slot}`, $event, true)"
          @click="keyboardClick(`skill${slot}`, $event)"
        >
          <span
            >{{ skills[memory.selected][id]!.short }}
            <small>{{ skillKeys[slot] }}</small></span
          ><small>{{
            state!.player.cooldowns[id]! > 0
              ? `${state!.player.cooldowns[id]!.toFixed(1)}秒`
              : `${skills[memory.selected][id]!.cost}能量`
          }}</small>
        </button>
      </div>
      <button
        v-if="memory.selected === 'lancer'"
        class="charge-mode"
        :aria-pressed="tapCharge"
        @click="toggleChargeMode"
      >
        {{ tapCharge ? "轻触蓄力：再点发射" : "按住蓄力：松开发射" }}
      </button>
      <p>
        A/D 或 ←→ 移动 · 空格跳 · J{{
          memory.selected === "blade" ? "连击" : "长按蓄力后松开"
        }}
        · Shift 闪避 · P 暂停
      </p>
    </footer>
    <p v-if="diagnostics" class="qa-performance" role="status">
      开发验收采样（逻辑与Canvas CPU，不含GPU合成）：{{ frameCost }}
    </p>
  </main>
</template>

<style scoped>
:global(*) {
  box-sizing: border-box;
}
:global(body) {
  margin: 0;
  font-family: ui-sans-serif, system-ui, "Microsoft YaHei", sans-serif;
  background: #fff3dc;
  color: #684331;
}
button,
select {
  font: inherit;
}
button {
  min-height: 44px;
  border: 1px solid #dbb58e;
  background: #fffaf0;
  color: #714b36;
  border-radius: 12px;
  padding: 10px 15px;
  cursor: pointer;
  font-weight: 650;
}
button:focus-visible,
select:focus-visible {
  outline: 3px solid #417a88;
  outline-offset: 3px;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
button[aria-pressed="true"],
.primary {
  background: #d97835;
  color: #fffaf1;
  border-color: #d97835;
}
.rift {
  background: #fff4df;
  min-height: 580px;
}
.game-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-bottom: 1px solid #ead2af;
  gap: 12px;
}
.game-header > div {
  display: flex;
  align-items: center;
  gap: 12px;
}
.game-header strong {
  font-size: 20px;
  display: block;
}
.game-header small {
  display: block;
  font-size: 12px;
  margin-top: 4px;
  color: #977453;
}
.emblem {
  font-size: 30px;
  background: #efac52;
  color: #fff4cc;
  border-radius: 12px;
  width: 45px;
  height: 45px;
  text-align: center;
  line-height: 45px;
}
.game-header nav {
  display: flex;
  gap: 8px;
}
.camp-panel {
  padding: 26px 30px 20px;
  max-width: 1250px;
  margin: auto;
}
.camp-intro {
  text-align: center;
}
.eyebrow {
  font-size: 12px;
  letter-spacing: 2px;
  color: #ac6b3b;
  font-weight: 700;
}
h1 {
  font-size: clamp(25px, 4vw, 38px);
  margin: 8px 0 10px;
  letter-spacing: -1px;
}
h2,
h3,
p {
  margin: 8px 0;
}
p {
  line-height: 1.65;
}
.camp-intro p,
.quiet {
  font-size: 13px;
  color: #99734e;
}
.hero-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  max-width: 670px;
  margin: 25px auto 14px;
}
.hero-switch button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  text-align: left;
  padding: 18px;
}
.hero-switch b {
  font-size: 20px;
}
.hero-switch span {
  font-size: 12px;
}
.hero-switch small {
  font-weight: 800;
}
.growth-bar {
  display: flex;
  justify-content: center;
  gap: 25px;
  font-size: 12px;
  flex-wrap: wrap;
  padding: 6px 0 22px;
}
.camp-tabs {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-bottom: 22px;
}
.camp-tabs button {
  border-radius: 22px;
}
.chapter-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}
.chapter-card {
  position: relative;
  overflow: hidden;
  border: 1px solid #e4c59f;
  border-radius: 20px;
  padding: 26px 22px 22px;
  background: linear-gradient(135deg, #fffaf0, #f7d39f);
  display: flex;
  flex-direction: column;
  min-height: 230px;
}
.chapter-1 {
  background: linear-gradient(135deg, #fff9e9, #eed29a);
}
.chapter-2 {
  background: linear-gradient(135deg, #fff3e2, #f2bea0);
}
.chapter-number {
  font-size: 50px;
  font-weight: 900;
  opacity: 0.18;
  position: absolute;
  right: 20px;
  top: 4px;
}
.chapter-card h2 {
  font-size: 22px;
  margin-top: 16px;
}
.chapter-card p {
  font-size: 13px;
}
.room-list {
  font-size: 11px;
  color: #99744e;
  line-height: 1.8;
  min-height: 32px;
}
.chapter-card > small {
  font-size: 11px;
  margin: 12px 0;
}
.chapter-card button {
  margin-top: auto;
}
.checkpoint {
  display: flex;
  justify-content: space-between;
  width: 100%;
  margin-bottom: 18px;
  border-style: dashed;
  background: #fff9dc;
}
.checkpoint span {
  font-size: 12px;
  font-weight: 400;
}
.quiet {
  text-align: center;
  margin: 18px 0;
}
.equipment-grid,
.skill-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.equipment-grid article,
.skill-grid article {
  background: #fffaf0;
  border: 1px solid #e6ccab;
  border-radius: 15px;
  padding: 18px;
  display: flex;
  flex-direction: column;
}
.equipment-grid article.equipped {
  border: 2px solid #d78a44;
  padding: 17px;
}
.equipment-grid small,
.skill-grid small {
  font-size: 11px;
  color: #99744f;
}
.equipment-grid h3,
.skill-grid h3 {
  font-size: 17px;
}
.equipment-grid p,
.skill-grid p {
  font-size: 12px;
  min-height: 44px;
  color: #8c6648;
}
.equipment-grid button,
.skill-grid button {
  margin-top: auto;
  font-size: 12px;
}
.skill-slots {
  display: flex;
  align-items: end;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}
.skill-slots label {
  font-size: 12px;
  flex: 1;
  min-width: 130px;
}
.skill-slots select {
  display: block;
  min-height: 44px;
  width: 100%;
  margin-top: 6px;
  border-radius: 10px;
  border: 1px solid #dab78c;
  background: #fffaf0;
  color: #714b36;
  padding: 7px;
}
.feedback {
  padding: 10px 14px;
  border-radius: 10px;
  background: #f6e4bd;
  font-size: 13px;
}
.arena {
  position: relative;
  outline: none;
  min-height: 430px;
}
.arena:focus-visible {
  outline: 2px solid #ba985f;
  outline-offset: -2px;
}
canvas {
  display: block;
  width: 100%;
  height: 430px;
  touch-action: pan-y;
}
.combat-hud {
  position: absolute;
  top: 14px;
  left: 20px;
  right: 20px;
  display: flex;
  justify-content: space-between;
  gap: 16px;
  pointer-events: none;
  z-index: 1;
}
.hero-bars {
  min-width: 170px;
  font-size: 11px;
}
.hero-bars strong {
  display: block;
  font-size: 15px;
}
.hero-bars strong small {
  font-size: 10px;
  color: #97714a;
}
.hero-bars label {
  display: block;
  margin-top: 4px;
}
.hero-bars > div {
  display: flex;
  gap: 16px;
  font-size: 10px;
  margin-top: 3px;
}
meter {
  display: block;
  width: 100%;
  height: 10px;
  accent-color: #d8803a;
}
.room-goal {
  text-align: right;
  max-width: 420px;
  font-size: 13px;
  color: #925432;
}
.room-goal b,
.room-goal small {
  display: block;
  margin-top: 6px;
  font-size: 11px;
}
.room-goal meter {
  height: 8px;
}
.veil {
  position: absolute;
  inset: 0;
  background: #fff4dfb8;
  backdrop-filter: blur(3px);
  display: grid;
  place-items: center;
  padding: 16px;
  z-index: 3;
}
.pause-card,
.result-card {
  background: #fff9eb;
  border: 1px solid #e4bf91;
  box-shadow: 0 14px 45px #9f62221a;
  border-radius: 22px;
  padding: 26px;
  max-width: 500px;
  text-align: center;
}
.pause-card h2,
.result-card h2 {
  font-size: 26px;
  margin: 10px 0;
}
.pause-card p,
.result-card p {
  font-size: 13px;
  color: #88603e;
}
.pause-card button,
.result-card button {
  margin: 8px 5px;
}
.pause-card > small,
.result-card > small {
  display: block;
  font-size: 11px;
  color: #a27e59;
  margin-top: 10px;
}
.loot {
  background: #f9e6b9;
  border-radius: 10px;
  padding: 10px;
  font-weight: 800;
}
.loot small {
  display: block;
  font-weight: 400;
  font-size: 10px;
  margin-top: 4px;
}
.controls {
  display: grid;
  grid-template-columns: auto auto 1fr;
  gap: 12px;
  padding: 16px 24px 10px;
  border-top: 1px solid #ead0aa;
  align-items: center;
}
.controls > div {
  display: flex;
  gap: 8px;
}
.controls button {
  min-width: 56px;
  touch-action: none;
  user-select: none;
}
.controls .attack-button {
  background: #d9803c;
  border-color: #d9803c;
  color: #fff;
}
.skill-controls {
  justify-content: flex-end;
}
.skill-controls button {
  display: flex;
  flex-direction: column;
  gap: 3px;
  align-items: center;
}
.skill-controls small {
  font-size: 10px;
  font-weight: 400;
}
.controls p {
  grid-column: 1/-1;
  font-size: 11px;
  color: #a07b51;
  margin: 3px 0;
}
.development-note {
  background: #fff0a6;
  padding: 7px 20px;
  margin: 0;
  font-size: 11px;
}
.dark {
  background: #423441;
  color: #f5d9b5;
}
.dark .camp-intro p,
.dark .quiet,
.dark .game-header small {
  color: #c5a88e;
}
.dark .camp-intro h1 {
  color: #ffe1b2;
}
.dark .chapter-card,
.dark .equipment-grid article,
.dark .skill-grid article,
.dark .pause-card,
.dark .result-card {
  color: #664631;
}
.dark .combat-hud {
  color: #ffe4ba;
}
.dark .room-goal {
  color: #ffdb9b;
}
@media (max-width: 760px) {
  .game-header {
    padding: 12px 14px;
  }
  .game-header strong {
    font-size: 17px;
  }
  .game-header nav button {
    font-size: 12px;
    padding: 8px 11px;
  }
  .camp-panel {
    padding: 22px 15px 15px;
  }
  .hero-switch button {
    padding: 14px;
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 4px;
  }
  .hero-switch span {
    grid-column: 1/-1;
    grid-row: 2;
  }
  .hero-switch b {
    font-size: 18px;
  }
  .chapter-grid {
    grid-template-columns: 1fr;
  }
  .chapter-card {
    min-height: 175px;
    padding: 18px;
  }
  .chapter-card h2 {
    margin-top: 0;
  }
  .chapter-card .primary {
    align-self: flex-start;
    margin-top: 12px;
  }
  .chapter-card > small {
    margin: 4px 0;
  }
  .room-list {
    min-height: auto;
  }
  .equipment-grid,
  .skill-grid {
    grid-template-columns: 1fr 1fr;
  }
  .controls {
    grid-template-columns: 1fr auto;
    padding: 12px;
    gap: 9px;
  }
  .controls button {
    min-width: 48px;
    padding: 9px 12px;
    min-height: 48px;
  }
  .skill-controls {
    grid-column: 1/-1;
    justify-content: center;
  }
  .skill-controls button {
    min-width: 90px;
  }
  .combat-hud {
    left: 12px;
    right: 12px;
    gap: 10px;
  }
  .hero-bars {
    min-width: 125px;
  }
  .room-goal {
    font-size: 11px;
    max-width: 200px;
  }
  canvas {
    height: 395px;
  }
  .arena {
    min-height: 395px;
  }
  .result-card,
  .pause-card {
    padding: 18px;
    max-width: 360px;
  }
  .result-card h2 {
    font-size: 22px;
  }
  .checkpoint {
    display: block;
    text-align: left;
  }
  .checkpoint span {
    display: block;
    margin-top: 6px;
  }
  .growth-bar {
    gap: 7px;
  }
  .camp-tabs button {
    padding: 9px 12px;
    font-size: 12px;
  }
}
@media (max-width: 400px) {
  .equipment-grid,
  .skill-grid {
    grid-template-columns: 1fr;
  }
  .game-header .emblem {
    display: none;
  }
  .game-header nav {
    gap: 5px;
  }
  .game-header nav button {
    padding: 8px;
    min-height: 44px;
  }
  .controls > div {
    gap: 5px;
  }
  .controls button {
    min-width: 46px;
    padding: 9px 10px;
  }
  .skill-controls button {
    min-width: 90px;
  }
  .room-goal {
    max-width: 170px;
  }
  .hero-bars {
    min-width: 115px;
  }
  .camp-tabs {
    gap: 5px;
  }
}
.combat canvas {
  height: clamp(280px, calc(100dvh - 220px), 430px);
}
.combat .arena {
  min-height: 280px;
}
.charge-mode {
  grid-column: 1/-1;
  justify-self: start;
  font-size: 11px;
  min-height: 32px;
  padding: 6px 10px;
}
.combat:has(.charge-mode) canvas {
  height: clamp(280px, calc(100dvh - 260px), 430px);
}
@media (max-width: 760px) {
  .combat canvas {
    height: clamp(280px, calc(100dvh - 260px), 395px);
  }
  .combat:has(.charge-mode) canvas {
    height: clamp(280px, calc(100dvh - 300px), 395px);
  }
  .charge-mode {
    min-height: 44px;
  }
}
</style>
