import {
  bosses,
  enemies,
  maps,
  towerCatalog,
  waveRoster,
  type Point,
  type EnemyKind,
  type BossKind,
} from "./catalog";
import { clone } from "./save";
import type { Battle, CampRun, CampMemory, Foe, Tower, Effect } from "./model";
export const distance = (a: Point, b: Point) =>
  Math.hypot(a.x - b.x, a.y - b.y);
export function pathLength(points: readonly Point[]) {
  return points
    .slice(1)
    .reduce((sum, p, i) => sum + distance(p, points[i]!), 0);
}
export function pointAt(points: readonly Point[], progress: number): Point {
  let remaining = Math.max(0, progress);
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1]!,
      b = points[i]!,
      len = distance(a, b);
    if (remaining <= len)
      return {
        x: a.x + ((b.x - a.x) * remaining) / len,
        y: a.y + ((b.y - a.y) * remaining) / len,
      };
    remaining -= len;
  }
  return { ...points.at(-1)! };
}
function log(b: Battle, message: string) {
  b.messages.push(message);
  if (b.messages.length > 10) b.messages.shift();
}
function effect(
  b: Battle,
  kind: Effect["kind"],
  from: Point,
  to: Point,
  radius = 0,
) {
  b.effects.push({
    kind,
    from: { ...from },
    to: { ...to },
    life: kind === "shot" ? 0.16 : 0.42,
    radius,
  });
  if (b.effects.length > 90) b.effects.shift();
}
export function stats(t: Tower) {
  const c = towerCatalog[t.kind],
    power = 1 + (t.level - 1) * 0.62;
  let range = c.range + (t.level - 1) * 8,
    damage = c.damage * power,
    interval = c.interval;
  if (t.level > 1) {
    if (t.kind === "bolt" && t.branch === 0)
      interval *= t.level === 3 ? 0.5 : 0.65;
    if (t.kind === "bolt" && t.branch === 1) {
      range += 32;
      damage *= 1.18;
    }
    if (t.kind === "beam" && t.branch === 0) range += t.level === 3 ? 70 : 45;
    if (t.kind === "cannon" && t.branch === 1) damage *= 1.25;
  }
  return { range, damage, interval };
}
export function supportedStats(run: CampRun, t: Tower, boost = 0) {
  const s = stats(t),
    pos = maps[run.map]!.pads[t.pad]!;
  let damage = 1,
    rate = 1,
    range = 1;
  for (const helper of run.towers) {
    if (
      helper.kind !== "support" ||
      helper.pad === t.pad ||
      distance(pos, maps[run.map]!.pads[helper.pad]!) > stats(helper).range
    )
      continue;
    damage = Math.max(
      damage,
      helper.branch === 0 ? 1.2 + helper.level * 0.08 : 1.12,
    );
    range = Math.max(range, helper.branch === 0 ? 1.14 : 1);
    rate = Math.max(
      rate,
      helper.branch === 1 ? 1.3 + helper.level * 0.1 : 1.08,
    );
  }
  if (boost > 0) rate *= 1.6;
  return {
    range: s.range * range,
    damage: s.damage * damage,
    interval: s.interval / rate,
  };
}
export function startBattle(run: CampRun): Battle {
  return {
    run: clone(run),
    phase: "running",
    time: 0,
    queue: waveRoster(run.map, run.wave, run.mode === "endless"),
    nextSpawn: 0.25,
    spawned: 0,
    nextId: 1,
    foes: [],
    turrets: {},
    effects: [],
    blizzard: false,
    rally: false,
    boost: 0,
    messages: [`第${run.wave}波 · 守住营地。`],
    kills: 0,
    leaked: 0,
    settled: false,
  };
}
export function spawn(
  b: Battle,
  kind: EnemyKind | BossKind,
  path?: number,
  progress = 0,
) {
  const boss = Object.hasOwn(bosses, kind),
    c = boss ? bosses[kind as BossKind] : enemies[kind as EnemyKind];
  const route = path ?? b.spawned % maps[b.run.map]!.paths.length;
  const cycle =
    b.run.mode === "endless" ? Math.floor((b.run.wave - 1) / 12) : 0;
  const factor = boss
    ? 1 + cycle * 0.48
    : (1 + ((b.run.wave - 1) % 12) * 0.12 + b.run.map * 0.08) *
      (1 + cycle * 0.45);
  const hp = c.hp * factor,
    pos = pointAt(maps[b.run.map]!.paths[route]!, progress);
  const f: Foe = {
    id: b.nextId++,
    kind,
    boss,
    path: route,
    distance: progress,
    ...pos,
    hp,
    maxHp: hp,
    shield: kind === "ward" ? 28 : 0,
    armor: c.armor,
    speed: c.speed * (1 + Math.min(cycle, 0.8) * 0.06),
    flying: c.flying,
    slow: 1,
    slowTime: 0,
    freeze: 0,
    weak: 0,
    shred: 0,
    skill: boss ? 5 : 2.6,
    charge: 0,
    haste: 0,
    dead: false,
  };
  b.foes.push(f);
  return f;
}
function hurt(b: Battle, f: Foe, amount: number, ignore = 0) {
  if (f.dead || f.hp <= 0) return;
  let hit = Math.max(
    0.5,
    amount - Math.max(0, f.armor - f.shred) * (1 - ignore),
  );
  if (f.weak > 0) hit *= 1.25;
  const absorbed = Math.min(f.shield, hit);
  f.shield -= absorbed;
  hit -= absorbed;
  f.hp = Math.max(0, f.hp - hit);
  if (f.hp <= 0) {
    f.dead = true;
    b.kills++;
    b.run.gold += f.boss ? 160 : enemies[f.kind as EnemyKind].bounty;
    if (f.boss) log(b, `${bosses[f.kind as BossKind].name}已被击退！`);
  }
}
const supportEnemy = (f: Foe) => f.kind === "ward" || f.kind === "healer";
export function target(b: Battle, t: Tower, range: number): Foe | undefined {
  const pos = maps[b.run.map]!.pads[t.pad]!;
  const inRange = b.foes.filter(
    (f) =>
      !f.dead &&
      f.hp > 0 &&
      (t.kind !== "cannon" || !f.flying) &&
      distance(pos, f) <= range,
  );
  return inRange.sort((a, c) => {
    if (t.priority === "support" && supportEnemy(a) !== supportEnemy(c))
      return supportEnemy(a) ? -1 : 1;
    if (t.priority === "strong" && a.maxHp !== c.maxHp)
      return c.maxHp - a.maxHp;
    return (
      pathLength(maps[b.run.map]!.paths[a.path]!) -
      a.distance -
      (pathLength(maps[b.run.map]!.paths[c.path]!) - c.distance)
    );
  })[0];
}
function lineDistance(p: Point, a: Point, z: Point) {
  const dx = z.x - a.x,
    dy = z.y - a.y,
    len = dx * dx + dy * dy;
  const q = len ? ((p.x - a.x) * dx + (p.y - a.y) * dy) / len : 0;
  return q < 0 || q > 1
    ? Infinity
    : Math.hypot(p.x - a.x - q * dx, p.y - a.y - q * dy);
}
function fire(b: Battle, t: Tower, f: Foe, s: ReturnType<typeof stats>) {
  const pos = maps[b.run.map]!.pads[t.pad]!,
    rt = b.turrets[t.pad]!;
  rt.shots++;
  const power = s.damage;
  if (t.kind === "cannon") {
    const radius =
      t.branch === 0 && t.level > 1 ? (t.level === 3 ? 115 : 100) : 58;
    effect(b, "blast", pos, f, radius);
    for (const hit of b.foes)
      if (!hit.dead && !hit.flying && distance(hit, f) <= radius) {
        if (t.branch === 1)
          hit.shred = Math.max(hit.shred, t.level === 3 ? 18 : 10);
        hurt(b, hit, power, t.branch === 1 ? 0.4 : 0);
      }
  } else if (t.kind === "frost") {
    effect(b, "frost", pos, f, 26);
    f.slow = t.branch === 0 ? (t.level === 3 ? 0.28 : 0.4) : 0.62;
    f.slowTime = 2.2;
    if (t.branch === 0 && rt.shots % 3 === 0)
      f.freeze = Math.max(f.freeze, f.boss ? 0.22 : 0.7);
    if (t.branch === 1) f.weak = 3;
    hurt(b, f, power, 0.65);
  } else if (t.kind === "beam") {
    if (t.branch === 1) {
      const targets = [
        f,
        ...b.foes
          .filter((a) => a !== f && !a.dead && distance(a, f) < 125)
          .slice(0, t.level === 3 ? 3 : 2),
      ];
      for (let i = 0; i < targets.length; i++) {
        effect(b, "beam", i ? targets[i - 1]! : pos, targets[i]!);
        hurt(b, targets[i]!, power * (i ? 0.7 : 1), 0.55);
      }
    } else {
      const dx = f.x - pos.x,
        dy = f.y - pos.y,
        len = Math.hypot(dx, dy) || 1,
        to = {
          x: pos.x + (dx / len) * s.range,
          y: pos.y + (dy / len) * s.range,
        };
      effect(b, "beam", pos, to);
      for (const hit of b.foes)
        if (!hit.dead && lineDistance(hit, pos, to) < 22)
          hurt(b, hit, power, t.branch === 0 ? 0.85 : 0.45);
    }
  } else {
    effect(b, "shot", pos, f);
    hurt(
      b,
      f,
      power * (t.branch === 1 && (f.flying || f.kind === "hound") ? 2 : 1),
    );
  }
}
function enemySkill(b: Battle, f: Foe, dt: number) {
  if (f.kind === "healer") {
    for (const other of b.foes)
      if (!other.dead && distance(f, other) < 110)
        other.hp = Math.min(other.maxHp, other.hp + other.maxHp * 0.035 * dt);
  }
  f.skill -= dt;
  if (f.boss) {
    if (f.charge > 0) {
      f.charge -= dt;
      if (f.charge > 0) return;
      if (f.kind === "bastion") {
        f.shield += Math.min(380, f.maxHp * 0.18);
        log(b, "堡垒结壳 · 寒潮能消去护盾。");
        effect(b, "frost", f, f, 68);
      }
      if (f.kind === "drummer") {
        for (const other of b.foes)
          if (!other.dead && distance(f, other) < 260) other.haste = 5;
        spawn(b, "hound", f.path, Math.max(0, f.distance - 30));
        spawn(b, "hound", f.path, Math.max(0, f.distance - 70));
        log(b, "鼓手召来疾奔支援，邻近敌人加速！");
      }
      if (f.kind === "tempest") {
        const near = b.run.towers
          .filter((t) => distance(maps[b.run.map]!.pads[t.pad]!, f) < 280)
          .sort(
            (a, c) =>
              distance(maps[b.run.map]!.pads[a.pad]!, f) -
              distance(maps[b.run.map]!.pads[c.pad]!, f),
          )
          .slice(0, 2);
        for (const t of near) {
          b.turrets[t.pad] ??= {
            cooldown: 0,
            shots: 0,
            disabled: 0,
            repair: 0,
          };
          b.turrets[t.pad]!.disabled = 4;
          effect(b, "disable", f, maps[b.run.map]!.pads[t.pad]!, 40);
        }
        log(b, "风眼干扰邻近塔 · 鼓舞可立即修复。");
      }
      f.skill = 7;
    } else if (f.skill <= 0) {
      f.charge = 2.5;
      log(b, `${bosses[f.kind as BossKind].name}正在蓄势！`);
    }
  } else if (f.kind === "ward" && f.skill <= 0) {
    for (const other of b.foes)
      if (!other.dead && distance(f, other) < 105)
        other.shield = Math.min(other.maxHp * 0.45, other.shield + 22);
    f.skill = 4;
    effect(b, "frost", f, f, 105);
  }
}
export function castBlizzard(b: Battle, point: Point): boolean {
  if (
    b.phase !== "running" ||
    b.blizzard ||
    !Number.isFinite(point.x) ||
    !Number.isFinite(point.y) ||
    point.x < 0 ||
    point.x > 900 ||
    point.y < 0 ||
    point.y > 560
  )
    return false;
  b.blizzard = true;
  effect(b, "frost", point, point, 150);
  let hits = 0;
  for (const f of b.foes)
    if (!f.dead && distance(f, point) < 150) {
      f.shield = 0;
      f.freeze = f.boss ? 1 : 2.4;
      f.charge = 0;
      f.skill = Math.max(f.skill, 5);
      hurt(b, f, 70 + b.run.wave * 3, 1);
      hits++;
    }
  log(b, `寒潮已落下 · 命中${hits}个目标，本波不可再次使用。`);
  return true;
}
export function castRally(b: Battle): boolean {
  if (b.phase !== "running" || b.rally) return false;
  b.rally = true;
  b.boost = 7;
  b.run.lives = Math.min(20, b.run.lives + 3);
  for (const t of Object.values(b.turrets)) t.disabled = 0;
  effect(b, "heal", maps[b.run.map]!.base, maps[b.run.map]!.base, 100);
  log(b, "营地鼓舞 · 修补3生命，全塔加速7秒并解除干扰。");
  return true;
}
export function advance(b: Battle, elapsed: number) {
  if (b.phase !== "running" || !Number.isFinite(elapsed) || elapsed <= 0)
    return;
  const dt = Math.min(0.1, elapsed);
  b.time += dt;
  b.boost = Math.max(0, b.boost - dt);
  for (const e of b.effects) e.life -= dt;
  b.effects = b.effects.filter((e) => e.life > 0);
  b.nextSpawn -= dt;
  if (b.queue.length && b.nextSpawn <= 0) {
    spawn(b, b.queue.shift()!);
    b.spawned++;
    b.nextSpawn += 0.9;
  }
  // Snapshot: enemies summoned during this step start moving on the next step.
  // eslint-disable-next-line unicorn/no-useless-spread
  for (const f of [...b.foes]) {
    if (f.dead) continue;
    f.freeze = Math.max(0, f.freeze - dt);
    f.weak = Math.max(0, f.weak - dt);
    f.haste = Math.max(0, f.haste - dt);
    f.slowTime = Math.max(0, f.slowTime - dt);
    if (f.slowTime <= 0) f.slow = 1;
    if (f.freeze <= 0) {
      enemySkill(b, f, dt);
      f.distance += f.speed * f.slow * (f.haste > 0 ? 1.5 : 1) * dt;
      Object.assign(f, pointAt(maps[b.run.map]!.paths[f.path]!, f.distance));
    }
    if (f.distance >= pathLength(maps[b.run.map]!.paths[f.path]!) - 62) {
      f.dead = true;
      b.leaked++;
      b.run.lives = Math.max(
        0,
        b.run.lives - (f.boss ? 8 : enemies[f.kind as EnemyKind].leak),
      );
      effect(b, "leak", f, maps[b.run.map]!.base, 70);
      log(
        b,
        `${f.boss ? bosses[f.kind as BossKind].name : enemies[f.kind as EnemyKind].name}越过防线！`,
      );
    }
  }
  if (b.run.lives <= 0) {
    b.phase = "lost";
    log(b, "营地失守。波内收益没有写入，可重新挑战。");
    return;
  }
  for (const t of b.run.towers) {
    const rt = (b.turrets[t.pad] ??= {
      cooldown: 0.08,
      shots: 0,
      disabled: 0,
      repair: 0,
    });
    rt.disabled = Math.max(0, rt.disabled - dt);
    if (rt.disabled > 0) continue;
    if (t.kind === "support") {
      if (t.branch === 1) {
        rt.repair += dt;
        if (rt.repair >= 16) {
          rt.repair -= 16;
          b.run.lives = Math.min(20, b.run.lives + 1);
          effect(
            b,
            "heal",
            maps[b.run.map]!.pads[t.pad]!,
            maps[b.run.map]!.base,
            40,
          );
        }
      }
      continue;
    }
    rt.cooldown -= dt;
    if (rt.cooldown > 0) continue;
    // Disabled support towers do not provide an aura.
    const supportedRun = {
      ...b.run,
      towers: b.run.towers.filter(
        (a) => a.kind !== "support" || !b.turrets[a.pad]?.disabled,
      ),
    };
    const s = supportedStats(supportedRun, t, b.boost),
      f = target(b, t, s.range);
    if (f) {
      fire(b, t, f, s);
      rt.cooldown = s.interval;
    } else rt.cooldown = 0;
  }
  b.foes = b.foes.filter((f) => !f.dead);
  if (!b.queue.length && !b.foes.length) {
    b.phase = "won";
    b.run.gold += 25 + Math.floor(b.run.wave * 2);
    log(b, `第${b.run.wave}波守住了 · 获得波间补给。`);
  }
}
export function settle(m: CampMemory, b: Battle): CampMemory {
  if (b.phase === "running" || b.settled) return clone(m);
  b.settled = true;
  const n = clone(m),
    r = b.run;
  if (r.mode === "endless")
    n.bestEndless[r.map] = Math.max(
      n.bestEndless[r.map]!,
      b.phase === "won" ? r.wave : r.wave - 1,
    );
  if (b.phase === "lost") {
    n.run = null;
    return n;
  }
  if (r.mode === "campaign" && r.wave >= 12) {
    n.wins[r.map] = Math.min(100000, n.wins[r.map]! + 1);
    n.bestLives[r.map] = Math.max(n.bestLives[r.map]!, r.lives);
    n.run = null;
  } else if (r.wave >= 999) n.run = null;
  else n.run = { ...clone(r), wave: r.wave + 1 };
  return n;
}
