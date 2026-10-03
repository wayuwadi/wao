import {
  elements,
  foeIds,
  heroIds,
  petIds,
  type Element,
  type FoeId,
  type HeroId,
  type PetId,
} from "./catalog";

export const statusKeys = [
  "burn",
  "slow",
  "sealed",
  "stun",
  "weak",
  "guard",
  "taunt",
  "haste",
  "ward",
  "regen",
  "counter",
] as const;
export type Status = (typeof statusKeys)[number];
export type Statuses = Record<Status, number>;
export const enemyMoves = [
  "strike",
  "ambush",
  "shield",
  "smash",
  "heal",
  "seal",
  "scorch",
  "chant",
  "drain",
  "counter",
  "sunfall",
  "ink",
  "starfall",
  "wait",
] as const;
export type EnemyMove = (typeof enemyMoves)[number];
export interface Actor {
  id: string;
  side: "party" | "foe";
  kind: HeroId | FoeId;
  element: Element;
  hp: number;
  maxHp: number;
  mp: number;
  maxMp: number;
  attack: number;
  defense: number;
  speed: number;
  shield: number;
  status: Statuses;
  branches: [number, number];
  effects: string[];
  intent: { move: EnemyMove; target: string };
  breakCount: number;
}
export interface Battle {
  quest: number;
  choice: "aid" | "bold";
  attempt: number;
  seed: number;
  round: number;
  actions: number;
  actors: Actor[];
  queue: string[];
  cursor: number;
  phase: "player" | "enemy" | "won" | "lost";
  pet: PetId;
  petCharge: number;
  petUsedRound: number;
  petWard: boolean;
  log: string[];
}
export interface HeroProgress {
  xp: number;
  branches: [number, number];
  weapon: string;
  charm: string | null;
}
export interface StarfieldMemory {
  party: [HeroId, HeroId, HeroId];
  heroes: Record<HeroId, HeroProgress>;
  pet: PetId;
  sand: number;
  owned: string[];
  completed: boolean[];
  accepted: boolean[];
  records: { wins: number; bestRounds: number }[];
  nextAttempt: number;
  rewardedAttempt: number;
  battle: Battle | null;
  lastResult: {
    quest: number;
    won: boolean;
    rounds: number;
    sand: number;
    drop: string | null;
  } | null;
  muted: boolean;
}
export function emptyStatus(): Statuses {
  return Object.fromEntries(statusKeys.map((key) => [key, 0])) as Statuses;
}
export const object = (x: unknown): x is Record<string, unknown> =>
  !!x && typeof x === "object" && !Array.isArray(x);
export const integer = (x: unknown, lo: number, hi: number): x is number =>
  typeof x === "number" && Number.isInteger(x) && x >= lo && x <= hi;
export function keys(x: Record<string, unknown>, expected: string[]): boolean {
  return (
    Object.keys(x).length === expected.length &&
    expected.every((k) => Object.prototype.hasOwnProperty.call(x, k))
  );
}
export function isBattle(x: unknown): x is Battle {
  if (
    !object(x) ||
    !keys(x, [
      "quest",
      "choice",
      "attempt",
      "seed",
      "round",
      "actions",
      "actors",
      "queue",
      "cursor",
      "phase",
      "pet",
      "petCharge",
      "petUsedRound",
      "petWard",
      "log",
    ])
  )
    return false;
  if (
    !integer(x.quest, 0, 8) ||
    !["aid", "bold"].includes(String(x.choice)) ||
    !integer(x.attempt, 1, 10000000) ||
    !integer(x.seed, 0, 0xffffffff) ||
    !integer(x.round, 1, 100) ||
    !integer(x.actions, 0, 2000) ||
    !["player", "enemy", "won", "lost"].includes(String(x.phase)) ||
    !petIds.includes(x.pet as PetId) ||
    !integer(x.petCharge, 0, 10) ||
    !integer(x.petUsedRound, 0, x.round) ||
    typeof x.petWard !== "boolean"
  )
    return false;
  if (!Array.isArray(x.actors) || x.actors.length < 4 || x.actors.length > 8)
    return false;
  const ids = new Set<string>();
  let partyCount = 0;
  for (const a of x.actors) {
    if (
      !object(a) ||
      !keys(a, [
        "id",
        "side",
        "kind",
        "element",
        "hp",
        "maxHp",
        "mp",
        "maxMp",
        "attack",
        "defense",
        "speed",
        "shield",
        "status",
        "branches",
        "effects",
        "intent",
        "breakCount",
      ])
    )
      return false;
    if (
      typeof a.id !== "string" ||
      !/^[pe][0-4]$/.test(a.id) ||
      ids.has(a.id) ||
      !["party", "foe"].includes(String(a.side))
    )
      return false;
    ids.add(a.id);
    if (a.side === "party") {
      if (!heroIds.includes(a.kind as HeroId) || !a.id.startsWith("p"))
        return false;
      partyCount++;
    } else if (!foeIds.includes(a.kind as FoeId) || !a.id.startsWith("e"))
      return false;
    if (
      !elements.includes(a.element as Element) ||
      !integer(a.maxHp, 20, 2000) ||
      !integer(a.hp, 0, a.maxHp) ||
      !integer(a.maxMp, 0, 100) ||
      !integer(a.mp, 0, a.maxMp) ||
      !integer(a.attack, 1, 100) ||
      !integer(a.defense, 0, 40) ||
      !integer(a.speed, 1, 60) ||
      !integer(a.shield, 0, 300) ||
      !integer(a.breakCount, 0, 2)
    )
      return false;
    if (
      !object(a.status) ||
      !keys(a.status, [...statusKeys]) ||
      !statusKeys.every((k) =>
        integer((a.status as Record<string, unknown>)[k], 0, 4),
      )
    )
      return false;
    if (
      !Array.isArray(a.branches) ||
      a.branches.length !== 2 ||
      !a.branches.every((v) => integer(v, 0, 3))
    )
      return false;
    if (
      !Array.isArray(a.effects) ||
      a.effects.length > 2 ||
      !a.effects.every((v) =>
        [
          "none",
          "pierce",
          "echo",
          "heal",
          "battery",
          "burn",
          "thorns",
          "clean",
          "pet",
          "swift",
          "guard",
        ].includes(String(v)),
      )
    )
      return false;
    if (
      !object(a.intent) ||
      !keys(a.intent, ["move", "target"]) ||
      !enemyMoves.includes(a.intent.move as EnemyMove) ||
      typeof a.intent.target !== "string" ||
      !/^[pe][0-4]$/.test(a.intent.target)
    )
      return false;
  }
  if (
    partyCount !== 3 ||
    new Set(x.actors.filter((a) => a.side === "party").map((a) => a.kind))
      .size !== 3
  )
    return false;
  if (
    !Array.isArray(x.queue) ||
    x.queue.length !== x.actors.length ||
    new Set(x.queue).size !== x.queue.length ||
    !x.queue.every((id) => typeof id === "string" && ids.has(id)) ||
    !integer(x.cursor, 0, x.queue.length - 1)
  )
    return false;
  if (!x.actors.every((a) => ids.has(a.intent.target))) return false;
  if (x.phase === "player" || x.phase === "enemy") {
    const current = x.actors.find(
      (a) => a.id === (x.queue as string[])[x.cursor as number],
    );
    if (
      !current ||
      current.hp <= 0 ||
      current.side !== (x.phase === "player" ? "party" : "foe")
    )
      return false;
  }
  if (x.phase === "won" && x.actors.some((a) => a.side === "foe" && a.hp > 0))
    return false;
  if (
    x.phase === "lost" &&
    x.round < 100 &&
    x.actors.some((a) => a.side === "party" && a.hp > 0)
  )
    return false;
  return (
    Array.isArray(x.log) &&
    x.log.length <= 12 &&
    x.log.every((s) => typeof s === "string" && s.length <= 180)
  );
}
