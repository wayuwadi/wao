import { equipment, type GearId, type HeroClass } from "./catalog";

export interface HeroGrowth {
  xp: number;
  ranks: number[];
  slots: number[];
  weapon: GearId;
  charm: GearId | null;
}
export interface Checkpoint {
  chapter: number;
  room: number;
  attempt: number;
  seed: number;
}
export interface RiftMemory {
  selected: HeroClass;
  heroes: Record<HeroClass, HeroGrowth>;
  owned: GearId[];
  materials: number;
  chapterWins: number[];
  bestTimes: number[];
  checkpoint: Checkpoint | null;
  nextAttempt: number;
  lastReward: string;
  muted: boolean;
}
export function freshRiftMemory(): RiftMemory {
  const hero = (weapon: GearId): HeroGrowth => ({
    xp: 0,
    ranks: [1, 1, 1, 0, 0, 0],
    slots: [0, 1, 2],
    weapon,
    charm: null,
  });
  return {
    selected: "blade",
    heroes: { blade: hero("blade-balanced"), lancer: hero("lancer-scout") },
    owned: ["blade-balanced", "lancer-scout"],
    materials: 0,
    chapterWins: [0, 0, 0],
    bestTimes: [0, 0, 0],
    checkpoint: null,
    nextAttempt: 1,
    lastReward: "",
    muted: true,
  };
}
export function level(xp: number) {
  return [0, 45, 110, 200, 330, 500, 720, 1000].filter((n) => xp >= n).length;
}
export function pointBudget(hero: HeroGrowth) {
  return 3 + (level(hero.xp) - 1) * 2;
}
function record(x: unknown): x is Record<string, unknown> {
  return !!x && typeof x === "object" && !Array.isArray(x);
}
function fields(x: Record<string, unknown>, keys: string[]) {
  return (
    Object.keys(x).length === keys.length &&
    keys.every((k) => Object.prototype.hasOwnProperty.call(x, k))
  );
}
function integer(x: unknown, min: number, max: number): x is number {
  return (
    Number.isSafeInteger(x) && (x as number) >= min && (x as number) <= max
  );
}
export function isRiftMemory(x: unknown): x is RiftMemory {
  if (
    !record(x) ||
    !fields(x, [
      "selected",
      "heroes",
      "owned",
      "materials",
      "chapterWins",
      "bestTimes",
      "checkpoint",
      "nextAttempt",
      "lastReward",
      "muted",
    ])
  )
    return false;
  if (
    !["blade", "lancer"].includes(x.selected as string) ||
    typeof x.muted !== "boolean" ||
    !integer(x.materials, 0, 999999) ||
    !integer(x.nextAttempt, 1, 1000000000) ||
    typeof x.lastReward !== "string" ||
    !/^(?:\d{1,10}:[0-2]:[0-2])?$/.test(x.lastReward)
  )
    return false;
  if (
    !Array.isArray(x.owned) ||
    x.owned.length < 2 ||
    x.owned.length > equipment.length ||
    new Set(x.owned).size !== x.owned.length ||
    x.owned.some((id) => !equipment.some((e) => e.id === id))
  )
    return false;
  if (!x.owned.includes("blade-balanced") || !x.owned.includes("lancer-scout"))
    return false;
  if (
    !Array.isArray(x.chapterWins) ||
    x.chapterWins.length !== 3 ||
    !x.chapterWins.every((n) => integer(n, 0, 1000000))
  )
    return false;
  if (
    !Array.isArray(x.bestTimes) ||
    x.bestTimes.length !== 3 ||
    !x.bestTimes.every((n) => integer(n, 0, 86400000))
  )
    return false;
  for (let i = 0; i < 3; i++) {
    if ((x.chapterWins[i] === 0) !== (x.bestTimes[i] === 0)) return false;
    if (i > 0 && x.chapterWins[i]! > 0 && x.chapterWins[i - 1] === 0)
      return false;
  }
  if (
    x.lastReward &&
    !integer(Number(x.lastReward.split(":")[0]), 1, x.nextAttempt - 1)
  )
    return false;
  if (!record(x.heroes) || !fields(x.heroes, ["blade", "lancer"])) return false;
  for (const kind of ["blade", "lancer"] as const) {
    const hero = x.heroes[kind];
    if (
      !record(hero) ||
      !fields(hero, ["xp", "ranks", "slots", "weapon", "charm"]) ||
      !integer(hero.xp, 0, 1000000)
    )
      return false;
    if (
      !Array.isArray(hero.ranks) ||
      hero.ranks.length !== 6 ||
      !hero.ranks.every((n) => integer(n, 0, 3)) ||
      hero.ranks.reduce((a, b) => a + b, 0) > 3 + (level(hero.xp) - 1) * 2
    )
      return false;
    if (
      !Array.isArray(hero.slots) ||
      hero.slots.length !== 3 ||
      new Set(hero.slots).size !== 3 ||
      !hero.slots.every(
        (n) => integer(n, 0, 5) && (hero.ranks as number[])[n]! > 0,
      )
    )
      return false;
    const weapon = equipment.find((e) => e.id === hero.weapon);
    if (
      !weapon ||
      weapon.slot !== "weapon" ||
      weapon.hero !== kind ||
      !x.owned.includes(hero.weapon)
    )
      return false;
    if (
      hero.charm !== null &&
      (!x.owned.includes(hero.charm) ||
        !equipment.some((e) => e.id === hero.charm && e.slot === "charm"))
    )
      return false;
  }
  if (x.checkpoint !== null) {
    const c = x.checkpoint;
    if (
      !record(c) ||
      !fields(c, ["chapter", "room", "attempt", "seed"]) ||
      !integer(c.chapter, 0, 2) ||
      !integer(c.room, 0, 2) ||
      !integer(c.attempt, 1, x.nextAttempt - 1) ||
      !integer(c.seed, 0, 4294967295)
    )
      return false;
    if (c.chapter > 0 && x.chapterWins[c.chapter - 1] === 0) return false;
  }
  return true;
}
export function cloneMemory(value: RiftMemory): RiftMemory {
  return JSON.parse(JSON.stringify(value)) as RiftMemory;
}
export function startChapter(
  memory: RiftMemory,
  chapter: number,
  seed: number,
): RiftMemory {
  if (
    !Number.isInteger(chapter) ||
    chapter < 0 ||
    chapter > 2 ||
    (chapter > 0 && memory.chapterWins[chapter - 1] === 0)
  )
    throw new Error("请先通关前一章。");
  const next = cloneMemory(memory);
  next.checkpoint = {
    chapter,
    room: 0,
    seed: seed >>> 0,
    attempt: next.nextAttempt++,
  };
  return next;
}
export function rewardRoom(
  memory: RiftMemory,
  checkpoint: Checkpoint,
  milliseconds: number,
): { memory: RiftMemory; gear: GearId | null; duplicate: boolean } {
  const id = `${checkpoint.attempt}:${checkpoint.chapter}:${checkpoint.room}`;
  if (memory.lastReward === id) return { memory, gear: null, duplicate: true };
  if (
    !memory.checkpoint ||
    !(["chapter", "room", "attempt", "seed"] as const).every(
      (k) => memory.checkpoint![k] === checkpoint[k],
    )
  )
    throw new Error("房间检查点已变化，拒绝重复奖励。");
  const next = cloneMemory(memory),
    hero = next.heroes[next.selected];
  hero.xp = Math.min(1000000, hero.xp + (checkpoint.room === 2 ? 75 : 28));
  next.materials = Math.min(
    999999,
    next.materials + (checkpoint.room === 2 ? 35 : 12),
  );
  const eligible = equipment.filter(
    (e) =>
      e.tier <= checkpoint.chapter &&
      (e.hero === "all" || e.hero === next.selected),
  );
  const index =
    ((checkpoint.seed >>> 0) + checkpoint.room * 7 + checkpoint.attempt * 3) %
    eligible.length;
  const gear = eligible[index]!.id;
  if (next.owned.includes(gear))
    next.materials = Math.min(999999, next.materials + 10);
  else next.owned.push(gear);
  next.lastReward = id;
  if (checkpoint.room === 2) {
    next.chapterWins[checkpoint.chapter] = Math.min(
      1000000,
      next.chapterWins[checkpoint.chapter]! + 1,
    );
    const ms = Math.max(1, Math.min(86400000, Math.round(milliseconds)));
    next.bestTimes[checkpoint.chapter] = next.bestTimes[checkpoint.chapter]
      ? Math.min(next.bestTimes[checkpoint.chapter]!, ms)
      : ms;
    next.checkpoint = null;
  } else next.checkpoint = { ...checkpoint, room: checkpoint.room + 1 };
  return { memory: next, gear, duplicate: false };
}
