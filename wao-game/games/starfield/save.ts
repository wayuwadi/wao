import {
  gear,
  heroIds,
  petIds,
  pets,
  quests,
  type HeroId,
  type PetId,
} from "./catalog";
import {
  integer,
  isBattle,
  keys,
  object,
  type HeroProgress,
  type StarfieldMemory,
} from "./model";
export type { StarfieldMemory } from "./model";
const xpThresholds = [0, 50, 130, 240, 390, 580, 820, 1120];
export function level(xp: number): number {
  return xpThresholds.filter((n) => xp >= n).length;
}
export function branchBudget(hero: HeroProgress): number {
  return Math.min(6, level(hero.xp) - 1);
}
export function freshStarfieldMemory(): StarfieldMemory {
  return {
    party: ["sol", "dew", "fern"],
    heroes: Object.fromEntries(
      heroIds.map((id) => [
        id,
        { xp: 0, branches: [0, 0], weapon: "branch-wand", charm: null },
      ]),
    ) as StarfieldMemory["heroes"],
    pet: "bird",
    sand: 35,
    owned: ["branch-wand"],
    completed: Array(9).fill(false),
    accepted: Array(9).fill(false),
    records: Array.from({ length: 9 }, () => ({ wins: 0, bestRounds: 0 })),
    nextAttempt: 1,
    rewardedAttempt: 0,
    battle: null,
    lastResult: null,
    muted: true,
  };
}
export function clone<T>(x: T): T {
  return JSON.parse(JSON.stringify(x)) as T;
}
export function unlocked(memory: StarfieldMemory, quest: number): boolean {
  const q = quests[quest];
  if (!q) return false;
  if (q.chapter > 0 && !memory.completed[q.chapter * 3 - 1]) return false;
  return (
    !q.boss || (memory.completed[quest - 2]! && memory.completed[quest - 1]!)
  );
}
export function isStarfieldMemory(x: unknown): x is StarfieldMemory {
  if (
    !object(x) ||
    !keys(x, [
      "party",
      "heroes",
      "pet",
      "sand",
      "owned",
      "completed",
      "accepted",
      "records",
      "nextAttempt",
      "rewardedAttempt",
      "battle",
      "lastResult",
      "muted",
    ])
  )
    return false;
  if (
    !Array.isArray(x.party) ||
    x.party.length !== 3 ||
    new Set(x.party).size !== 3 ||
    !x.party.every((id) => heroIds.includes(id as HeroId))
  )
    return false;
  if (
    !integer(x.sand, 0, 9999999) ||
    !integer(x.nextAttempt, 1, 10000000) ||
    !integer(x.rewardedAttempt, 0, x.nextAttempt - 1) ||
    typeof x.muted !== "boolean" ||
    !petIds.includes(x.pet as PetId)
  )
    return false;
  if (
    !Array.isArray(x.owned) ||
    x.owned.length < 1 ||
    x.owned.length > 12 ||
    new Set(x.owned).size !== x.owned.length ||
    !x.owned.includes("branch-wand") ||
    !x.owned.every((id) => gear.some((g) => g.id === id))
  )
    return false;
  if (!object(x.heroes) || !keys(x.heroes, [...heroIds])) return false;
  for (const id of heroIds) {
    const h = x.heroes[id];
    if (
      !object(h) ||
      !keys(h, ["xp", "branches", "weapon", "charm"]) ||
      !integer(h.xp, 0, 9999999) ||
      !Array.isArray(h.branches) ||
      h.branches.length !== 2 ||
      !h.branches.every((v) => integer(v, 0, 3)) ||
      h.branches.reduce((a, b) => a + b, 0) > Math.min(6, level(h.xp) - 1)
    )
      return false;
    if (
      !x.owned.includes(h.weapon) ||
      !gear.some((g) => g.id === h.weapon && g.slot === "weapon") ||
      (h.charm !== null &&
        (!x.owned.includes(h.charm) ||
          !gear.some((g) => g.id === h.charm && g.slot === "charm")))
    )
      return false;
  }
  if (
    !Array.isArray(x.completed) ||
    x.completed.length !== 9 ||
    !x.completed.every((v) => typeof v === "boolean") ||
    !Array.isArray(x.accepted) ||
    x.accepted.length !== 9 ||
    !x.accepted.every((v) => typeof v === "boolean")
  )
    return false;
  const memory = x as unknown as StarfieldMemory;
  if (
    memory.completed.some(
      (done, id) => done && (!memory.accepted[id] || !unlocked(memory, id)),
    ) ||
    memory.accepted.some((accepted, id) => accepted && !unlocked(memory, id))
  )
    return false;
  if (memory.completed.filter(Boolean).length < pets[memory.pet].unlock)
    return false;
  if (
    !Array.isArray(x.records) ||
    x.records.length !== 9 ||
    !x.records.every(
      (r, i) =>
        object(r) &&
        keys(r, ["wins", "bestRounds"]) &&
        integer(r.wins, 0, 10000000) &&
        integer(r.bestRounds, 0, 100) &&
        (memory.completed[i]
          ? r.wins > 0 && r.bestRounds > 0
          : r.wins === 0 && r.bestRounds === 0),
    )
  )
    return false;
  if (x.battle !== null) {
    if (
      !isBattle(x.battle) ||
      !unlocked(memory, x.battle.quest) ||
      !memory.accepted[x.battle.quest] ||
      x.battle.attempt >= x.nextAttempt ||
      x.battle.pet !== x.pet
    )
      return false;
    if (
      !x.battle.actors
        .filter((a) => a.side === "party")
        .every((a) => memory.party.includes(a.kind as HeroId))
    )
      return false;
    const scenario = quests[x.battle.quest]!;
    if (
      x.battle.actors
        .filter((a) => a.side === "foe")
        .map((a) => a.kind)
        .join(",") !== scenario.foes.join(",")
    )
      return false;
  }
  const result = x.lastResult;
  if (
    result !== null &&
    (!object(result) ||
      !keys(result, ["quest", "won", "rounds", "sand", "drop"]) ||
      !integer(result.quest, 0, 8) ||
      typeof result.won !== "boolean" ||
      !integer(result.rounds, 1, 100) ||
      !integer(result.sand, 0, 100) ||
      (result.drop !== null && !gear.some((g) => g.id === result.drop)))
  )
    return false;
  return true;
}
export function settle(memory: StarfieldMemory): StarfieldMemory {
  const m = clone(memory),
    b = m.battle;
  if (
    !b ||
    !["won", "lost"].includes(b.phase) ||
    m.rewardedAttempt >= b.attempt
  )
    return m;
  m.rewardedAttempt = b.attempt;
  const q = quests[b.quest]!,
    won = b.phase === "won";
  let reward = 0,
    drop: string | null = null;
  if (won) {
    const first = !m.completed[q.id];
    reward =
      (first ? q.reward : Math.ceil(q.reward * 0.6)) +
      (b.choice === "bold" ? 12 : 0);
    drop = q.drop;
    if (!m.owned.includes(drop)) m.owned.push(drop);
    else reward += 8;
    m.sand = Math.min(9999999, m.sand + reward);
    m.completed[q.id] = true;
    const record = m.records[q.id]!;
    record.wins = Math.min(10000000, record.wins + 1);
    record.bestRounds = record.bestRounds
      ? Math.min(record.bestRounds, b.round)
      : b.round;
    for (const id of heroIds)
      m.heroes[id].xp = Math.min(
        9999999,
        m.heroes[id].xp +
          Math.round(
            q.xp * (m.party.includes(id) ? 1 : 0.5) * (first ? 1 : 0.5),
          ),
      );
  }
  m.lastResult = { quest: q.id, won, rounds: b.round, sand: reward, drop };
  return m;
}
