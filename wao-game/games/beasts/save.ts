import {
  beastIds,
  species,
  regions,
  type BeastId,
  type RouteKind,
} from "./catalog";
import type { Beast, BeastMemory, Encounter } from "./model";
export type { BeastMemory } from "./model";
export const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));
export const newBeast = (id: BeastId): Beast => ({
  id,
  level: 1,
  xp: 0,
  path: 0,
  rank: 0,
  evolved: false,
  priority: "balanced",
});
export function freshBeastMemory(): BeastMemory {
  return {
    roster: ["sprig", "moss", "fin"].map((id) => newBeast(id as BeastId)),
    team: ["moss", "sprig", "fin"],
    food: 35,
    dew: 20,
    bait: 5,
    stones: 0,
    guardians: [false, false, false, false],
    traces: [0, 0, 0, 0],
    eggs: [],
    seen: ["sprig", "moss", "fin"],
    wins: 0,
    captures: 0,
    expeditions: 0,
    rewards: 0,
    goals: [false, false, false, false],
    expedition: null,
    muted: true,
  };
}
export function object(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === "object" && !Array.isArray(v);
}
export function fields(v: Record<string, unknown>, keys: string[]) {
  return (
    Object.keys(v).length === keys.length &&
    keys.every((k) => Object.prototype.hasOwnProperty.call(v, k))
  );
}
export function integer(v: unknown, min: number, max: number): v is number {
  return (
    Number.isSafeInteger(v) && (v as number) >= min && (v as number) <= max
  );
}
export function isBeastId(v: unknown): v is BeastId {
  return typeof v === "string" && beastIds.includes(v as BeastId);
}
function ids(v: unknown, max: number) {
  return (
    Array.isArray(v) &&
    v.length <= max &&
    v.every(isBeastId) &&
    new Set(v).size === v.length
  );
}
function beast(v: unknown): v is Beast {
  return (
    object(v) &&
    fields(v, ["id", "level", "xp", "path", "rank", "evolved", "priority"]) &&
    isBeastId(v.id) &&
    integer(v.level, 1, 8) &&
    integer(v.xp, 0, 99999) &&
    integer(v.path, 0, 1) &&
    integer(v.rank, 0, Math.min(3, Math.floor(v.level / 2))) &&
    typeof v.evolved === "boolean" &&
    (!v.evolved || (v.level >= 3 && !!species[v.id].evolution)) &&
    ["balanced", "skill", "reserve"].includes(v.priority as string)
  );
}
function encounter(v: unknown, region: number): v is Encounter {
  return (
    object(v) &&
    fields(v, [
      "region",
      "guardian",
      "wild",
      "terrain",
      "risk",
      "seed",
      "route",
    ]) &&
    v.region === region &&
    typeof v.guardian === "boolean" &&
    ids(v.wild, 3) &&
    Array.isArray(v.wild) &&
    (v.guardian
      ? v.wild.length === 0
      : v.wild.length >= 1 &&
        v.wild.every((id) => regions[region]!.wild.includes(id as never))) &&
    v.terrain === regions[region]!.terrain &&
    typeof v.risk === "boolean" &&
    integer(v.seed, 1, 2147483646) &&
    ["trail", "track", "guardian"].includes(v.route as string) &&
    v.guardian === (v.route === "guardian")
  );
}
export function isBeastMemory(v: unknown): v is BeastMemory {
  if (
    !object(v) ||
    !fields(v, [
      "roster",
      "team",
      "food",
      "dew",
      "bait",
      "stones",
      "guardians",
      "traces",
      "eggs",
      "seen",
      "wins",
      "captures",
      "expeditions",
      "rewards",
      "goals",
      "expedition",
      "muted",
    ])
  )
    return false;
  if (
    !Array.isArray(v.roster) ||
    v.roster.length < 3 ||
    v.roster.length > 12 ||
    !v.roster.every(beast) ||
    new Set(v.roster.map((b) => b.id)).size !== v.roster.length ||
    !ids(v.team, 3) ||
    (v.team as unknown[]).length !== 3 ||
    !(v.team as BeastId[]).every((id) =>
      (v.roster as Beast[]).some((b) => b.id === id),
    ) ||
    !ids(v.seen, 12) ||
    !(v.roster as Beast[]).every((b) => (v.seen as BeastId[]).includes(b.id))
  )
    return false;
  if (
    ![
      "food",
      "dew",
      "bait",
      "stones",
      "wins",
      "captures",
      "expeditions",
      "rewards",
    ].every((k) => integer(v[k], 0, 999999)) ||
    typeof v.muted !== "boolean"
  )
    return false;
  if (
    !Array.isArray(v.guardians) ||
    v.guardians.length !== 4 ||
    !v.guardians.every((x) => typeof x === "boolean") ||
    v.guardians.some(
      (x, i) => x && i > 0 && !(v.guardians as boolean[])[i - 1],
    ) ||
    !Array.isArray(v.goals) ||
    v.goals.length !== 4 ||
    !v.goals.every((x) => typeof x === "boolean") ||
    !Array.isArray(v.traces) ||
    v.traces.length !== 4 ||
    !v.traces.every((x) => integer(x, 0, 2))
  )
    return false;
  if (
    !Array.isArray(v.eggs) ||
    v.eggs.length > 12 ||
    !v.eggs.every(
      (e) =>
        object(e) &&
        fields(e, ["id", "winsAt"]) &&
        isBeastId(e.id) &&
        integer(e.winsAt, 0, v.wins as number),
    )
  )
    return false;
  if (v.expedition === null) return true;
  const e = v.expedition;
  return (
    object(e) &&
    fields(e, ["region", "step", "supplies", "seed", "pending"]) &&
    integer(e.region, 0, 3) &&
    (e.region === 0 || (v.guardians as boolean[])[e.region - 1] === true) &&
    integer(e.step, 0, 3) &&
    integer(e.supplies, 0, 12) &&
    integer(e.seed, 1, 2147483646) &&
    (e.pending === null || encounter(e.pending, e.region)) &&
    (!e.pending ||
      ((e.pending as Encounter).guardian ? e.step === 3 : e.step < 3))
  );
}
export function seeded(seed: number) {
  return ((Math.imul(seed, 48271) >>> 0) % 2147483646) + 1;
}
export function beginExpedition(
  input: BeastMemory,
  region: number,
): BeastMemory {
  const m = clone(input);
  if (!regions[region] || (region > 0 && !m.guardians[region - 1])) return m;
  m.expeditions = Math.min(999999, m.expeditions + 1);
  m.expedition = {
    region,
    step: 0,
    supplies: 6,
    seed: seeded(7919 + m.expeditions * 3571 + region * 137),
    pending: null,
  };
  return m;
}
export function chooseRoute(
  input: BeastMemory,
  route: RouteKind | "guardian",
): BeastMemory {
  const m = clone(input),
    e = m.expedition;
  if (!e || e.pending) return m;
  if (e.step === 3) {
    if (route !== "guardian") return m;
    e.pending = {
      region: e.region,
      guardian: true,
      wild: [],
      terrain: regions[e.region]!.terrain,
      risk: false,
      seed: e.seed,
      route,
    };
    return m;
  }
  if (
    route === "guardian" ||
    (route === "nest" && e.step !== 1) ||
    (route === "forage" && e.step === 1)
  )
    return m;
  const cost = route === "track" ? 2 : route === "forage" ? 0 : 1;
  if (e.supplies < cost) return m;
  e.supplies -= cost;
  e.seed = seeded(e.seed);
  const area = regions[e.region]!;
  if (route === "forage") {
    e.supplies = Math.min(12, e.supplies + 3);
    m.food = Math.min(999999, m.food + 9 + e.region * 2);
    m.bait = Math.min(999999, m.bait + 2);
    m.dew = Math.min(999999, m.dew + 3);
    e.step++;
    return m;
  }
  if (route === "nest") {
    if (m.eggs.length < 12)
      m.eggs.push({ id: area.wild[e.seed % 2]!, winsAt: m.wins });
    else m.dew = Math.min(999999, m.dew + 6);
    e.step++;
    return m;
  }
  if (route === "track")
    m.traces[e.region] = Math.min(2, m.traces[e.region]! + 1);
  const rare = route === "track" && m.traces[e.region] === 2;
  const id = rare ? area.wild[2] : area.wild[e.seed % 2];
  const wild: BeastId[] = [id!];
  if (e.region > 0 || route === "track")
    wild.push(area.wild[(e.seed + 1) % 2]!);
  // A species is a collection identity; avoid duplicate identities in a node.
  e.pending = {
    region: e.region,
    guardian: false,
    wild: [...new Set(wild)],
    terrain: area.terrain,
    risk: route === "track",
    seed: e.seed,
    route,
  };
  if (rare) m.traces[e.region] = 0;
  for (const seen of e.pending.wild)
    if (!m.seen.includes(seen)) m.seen.push(seen);
  return m;
}
export function collect(m: BeastMemory, id: BeastId) {
  if (!m.seen.includes(id)) m.seen.push(id);
  if (m.roster.some((b) => b.id === id)) {
    m.dew = Math.min(999999, m.dew + 12);
    m.food = Math.min(999999, m.food + 8);
  } else m.roster.push(newBeast(id));
}
export function train(input: BeastMemory, id: BeastId): BeastMemory {
  const m = clone(input),
    b = m.roster.find((x) => x.id === id);
  if (!b || b.level >= 8 || m.food < 8 + b.level * 3 || m.dew < 3 + b.level)
    return m;
  m.food -= 8 + b.level * 3;
  m.dew -= 3 + b.level;
  b.level++;
  return m;
}
export function grow(input: BeastMemory, id: BeastId): BeastMemory {
  const m = clone(input),
    b = m.roster.find((x) => x.id === id);
  if (
    !b ||
    b.rank >= 3 ||
    b.rank >= Math.floor(b.level / 2) ||
    m.dew < 8 + b.rank * 4
  )
    return m;
  m.dew -= 8 + b.rank * 4;
  b.rank++;
  return m;
}
export function evolve(input: BeastMemory, id: BeastId): BeastMemory {
  const m = clone(input),
    b = m.roster.find((x) => x.id === id);
  if (
    !b ||
    b.evolved ||
    !species[id].evolution ||
    b.level < 3 ||
    m.stones < 1 ||
    m.dew < 18
  )
    return m;
  m.stones--;
  m.dew -= 18;
  b.evolved = true;
  return m;
}
export function hatch(
  input: BeastMemory,
  index: number,
  instant: boolean,
): BeastMemory {
  const m = clone(input),
    egg = m.eggs[index];
  if (!egg) return m;
  if (m.wins - egg.winsAt < 2) {
    if (!instant || m.dew < 12) return m;
    m.dew -= 12;
  }
  collect(m, egg.id);
  m.eggs.splice(index, 1);
  return m;
}
export function claimGoal(input: BeastMemory, index: number): BeastMemory {
  const m = clone(input);
  const eligible = [
    m.captures >= 1,
    m.roster.length >= 6,
    m.roster.some((b) => b.evolved),
    m.guardians.every(Boolean),
  ];
  if (!eligible[index] || m.goals[index]) return m;
  m.goals[index] = true;
  m.food = Math.min(999999, m.food + 20);
  m.dew = Math.min(999999, m.dew + 15);
  m.stones = Math.min(999999, m.stones + 1);
  return m;
}
