import type { BeastId, Terrain, RouteKind } from "./catalog";
export type Priority = "balanced" | "skill" | "reserve";
export type Stance = "assault" | "guard" | "tame";
export interface Beast {
  id: BeastId;
  level: number;
  xp: number;
  path: 0 | 1;
  rank: number;
  evolved: boolean;
  priority: Priority;
}
export interface Egg {
  id: BeastId;
  winsAt: number;
}
export interface Encounter {
  region: number;
  guardian: boolean;
  wild: BeastId[];
  terrain: Terrain;
  risk: boolean;
  seed: number;
  route: RouteKind | "guardian";
}
export interface Expedition {
  region: number;
  step: number;
  supplies: number;
  seed: number;
  pending: Encounter | null;
}
export interface BeastMemory {
  roster: Beast[];
  team: BeastId[];
  food: number;
  dew: number;
  bait: number;
  stones: number;
  guardians: boolean[];
  traces: number[];
  eggs: Egg[];
  seen: BeastId[];
  wins: number;
  captures: number;
  expeditions: number;
  rewards: number;
  goals: boolean[];
  expedition: Expedition | null;
  muted: boolean;
}
export interface Fighter {
  id: string;
  kind: BeastId | "guardian" | "healer";
  side: "party" | "wild";
  name: string;
  hp: number;
  maxHp: number;
  attack: number;
  armor: number;
  shield: number;
  attackWait: number;
  skillWait: number;
  interval: number;
  cooldown: number;
  path: 0 | 1;
  rank: number;
  evolved: boolean;
  priority: Priority;
  burn: number;
  poison: number;
  slow: number;
  silence: number;
  stun: number;
  haste: number;
  guard: number;
  taunt: number;
  counter: number;
  weak: number;
  cast: number;
  casts: number;
  calm: number;
}
export interface Battle {
  encounter: Encounter;
  actors: Fighter[];
  seconds: number;
  tick: number;
  phase: "running" | "won" | "lost" | "captured";
  stance: Stance;
  target: string;
  charge: number;
  orderCooldown: number;
  captureCooldown: number;
  skillCount: number;
  log: string[];
  effects: {
    from: string;
    to: string;
    kind: "hit" | "heal" | "shield" | "control";
    life: number;
  }[];
  captured: BeastId | null;
  spentBait: number;
  settled: boolean;
}
