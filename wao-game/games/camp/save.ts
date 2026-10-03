import { maps, towerCatalog, towerKinds, type TowerKind } from "./catalog";
import type { CampMemory, CampRun, Tower, Priority } from "./model";
export type { CampMemory, CampRun, Tower } from "./model";
export const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v));
export const freshCampMemory = (): CampMemory => ({
  muted: true,
  speed: 1,
  wins: [0, 0, 0],
  bestLives: [0, 0, 0],
  bestEndless: [0, 0, 0],
  run: null,
});
const obj = (x: unknown): x is Record<string, unknown> =>
  !!x && typeof x === "object" && !Array.isArray(x);
const fields = (x: Record<string, unknown>, keys: string[]) =>
  Object.keys(x).length === keys.length &&
  keys.every((k) => Object.prototype.hasOwnProperty.call(x, k));
const int = (v: unknown, min: number, max: number): v is number =>
  Number.isSafeInteger(v) && (v as number) >= min && (v as number) <= max;
export function isCampMemory(v: unknown): v is CampMemory {
  if (
    !obj(v) ||
    !fields(v, ["muted", "speed", "wins", "bestLives", "bestEndless", "run"]) ||
    typeof v.muted !== "boolean" ||
    ![1, 2].includes(v.speed as number)
  )
    return false;
  for (const [key, max] of [
    ["wins", 100000],
    ["bestLives", 20],
    ["bestEndless", 999],
  ] as const)
    if (
      !Array.isArray(v[key]) ||
      v[key].length !== 3 ||
      !v[key].every((x) => int(x, 0, max))
    )
      return false;
  const m = v as unknown as CampMemory;
  if (
    m.wins.some((w, i) => i > 0 && w > 0 && !m.wins[i - 1]) ||
    m.bestLives.some((n, i) => n > 0 && !m.wins[i]) ||
    m.bestEndless.some((n, i) => n > 0 && !m.wins[i])
  )
    return false;
  if (v.run === null) return true;
  const r = v.run;
  if (
    !obj(r) ||
    !fields(r, ["map", "mode", "wave", "gold", "lives", "towers", "spent"]) ||
    !int(r.map, 0, 2) ||
    !["campaign", "endless"].includes(r.mode as string) ||
    !int(r.wave, 1, r.mode === "campaign" ? 12 : 999) ||
    !int(r.gold, 0, 1000000) ||
    !int(r.spent, 0, 1000000) ||
    !int(r.lives, 1, 20) ||
    !Array.isArray(r.towers) ||
    r.towers.length > maps[r.map]!.pads.length
  )
    return false;
  if (
    (r.map > 0 && !m.wins[r.map - 1]) ||
    (r.mode === "endless" && !m.wins[r.map])
  )
    return false;
  const pads = new Set();
  return r.towers.every((t) => {
    if (
      !obj(t) ||
      !fields(t, ["pad", "kind", "level", "branch", "priority"]) ||
      !int(t.pad, 0, maps[r.map as number]!.pads.length - 1) ||
      !towerKinds.includes(t.kind as TowerKind) ||
      !int(t.level, 1, 3) ||
      !["first", "strong", "support"].includes(t.priority as string) ||
      !(
        (t.level === 1 && t.branch === null) ||
        (t.level > 1 && (t.branch === 0 || t.branch === 1))
      ) ||
      pads.has(t.pad)
    )
      return false;
    pads.add(t.pad);
    return true;
  });
}
export function beginRun(
  m: CampMemory,
  map: number,
  mode: CampRun["mode"],
): CampMemory {
  const n = clone(m);
  if (
    !int(map, 0, 2) ||
    (map > 0 && !m.wins[map - 1]) ||
    (mode === "endless" && !m.wins[map])
  )
    return n;
  n.run = { map, mode, wave: 1, gold: 220, lives: 20, towers: [], spent: 0 };
  return n;
}
export function upgradeCost(t: Tower) {
  return Math.round(towerCatalog[t.kind].cost * (t.level === 1 ? 0.85 : 1.15));
}
export function invested(t: Tower) {
  const base = towerCatalog[t.kind].cost;
  return (
    base +
    (t.level > 1 ? Math.round(base * 0.85) : 0) +
    (t.level > 2 ? Math.round(base * 1.15) : 0)
  );
}
export function build(r: CampRun, pad: number, kind: TowerKind): boolean {
  if (
    !towerKinds.includes(kind) ||
    !int(pad, 0, maps[r.map]!.pads.length - 1) ||
    r.towers.some((t) => t.pad === pad) ||
    r.gold < towerCatalog[kind].cost
  )
    return false;
  r.gold -= towerCatalog[kind].cost;
  r.spent += towerCatalog[kind].cost;
  r.towers.push({ pad, kind, level: 1, branch: null, priority: "first" });
  return true;
}
export function upgrade(r: CampRun, pad: number, branch: 0 | 1): boolean {
  const t = r.towers.find((t) => t.pad === pad);
  if (!t || t.level >= 3 || ![0, 1].includes(branch) || r.gold < upgradeCost(t))
    return false;
  const cost = upgradeCost(t);
  r.gold -= cost;
  r.spent += cost;
  if (t.level === 1) t.branch = branch;
  t.level = (t.level + 1) as 2 | 3;
  return true;
}
export function sell(r: CampRun, pad: number): boolean {
  const t = r.towers.find((t) => t.pad === pad);
  if (!t) return false;
  r.gold += Math.floor(invested(t) * 0.7);
  r.towers = r.towers.filter((candidate) => candidate.pad !== pad);
  return true;
}
export function priority(r: CampRun, pad: number, value: Priority): boolean {
  const t = r.towers.find((t) => t.pad === pad);
  if (!t || !["first", "strong", "support"].includes(value)) return false;
  t.priority = value;
  return true;
}
