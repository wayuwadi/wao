import {
  equipment,
  roomEnemies,
  reinforcements,
  skills,
  type EnemyKind,
  type HeroClass,
} from "./catalog";
import { level, type Checkpoint, type RiftMemory } from "./save";

export const ROOM_WIDTH = 1640;
const GRAVITY = 1800;
export interface RiftControls {
  left: boolean;
  right: boolean;
  down: boolean;
  attackHeld: boolean;
}
export interface Fighter {
  x: number;
  z: number;
  vz: number;
  facing: number;
  hp: number;
  maxHp: number;
  stamina: number;
  energy: number;
  invulnerable: number;
  dodge: number;
  parry: number;
  guard: number;
  overcharge: number;
  charge: number;
  combo: number;
  comboTime: number;
  attackBuffer: number;
  cooldowns: number[];
  action: Action | null;
}
export interface Action {
  combo: number;
  age: number;
  impact: number;
  duration: number;
  hit: boolean;
  facing: number;
}
export interface Enemy {
  id: number;
  kind: EnemyKind;
  elite: boolean;
  x: number;
  z: number;
  hp: number;
  maxHp: number;
  facing: number;
  age: number;
  state: "move" | "windup" | "recover";
  target: number;
  pattern: number;
  stun: number;
  burn: number;
  burnTick: number;
  flash: number;
  shieldWindow: number;
  fakeCooldown: number;
  ward: number;
  motion?: {
    from: number;
    to: number;
    age: number;
    duration: number;
    damage: number;
    hit: boolean;
    leap: boolean;
  };
}
export interface Projectile {
  id: number;
  team: "hero" | "enemy";
  x: number;
  z: number;
  vx: number;
  vz: number;
  radius: number;
  damage: number;
  life: number;
  pierce: number;
  hits: number[];
  armorPiercing: boolean;
  lob: boolean;
}
export interface Hazard {
  x: number;
  radius: number;
  age: number;
  delay: number;
  damage: number;
  team: "hero" | "enemy";
  ground: boolean;
  source: number;
}
export interface Effect {
  kind: "hit" | "slash" | "heal" | "parry" | "dash" | "text" | "burst";
  x: number;
  z: number;
  age: number;
  life: number;
  text: string;
  color: string;
}
export interface Stats {
  power: number;
  attackSpeed: number;
  reach: number;
  moveSpeed: number;
  reduction: number;
  energyRate: number;
  dodgeCost: number;
  cooldownRate: number;
  ember: boolean;
  scatter: boolean;
  longGun: boolean;
}
export interface Encounter {
  phase: "ready" | "running" | "paused" | "won" | "lost";
  heroClass: HeroClass;
  checkpoint: Checkpoint;
  time: number;
  player: Fighter;
  enemies: Enemy[];
  projectiles: Projectile[];
  hazards: Hazard[];
  effects: Effect[];
  stats: Stats;
  ranks: number[];
  slots: number[];
  nextId: number;
  seed: number;
  hint: string;
  comboHits: number;
  parries: number;
  mechanism: {
    quakesAvoided: number;
    shieldsBroken: number;
    trueHits: number;
    decoyHits: number;
  };
  wave: number;
  totalWaves: number;
  arrival: number;
  incoming: Enemy[];
}
const clamp = (n: number, lo: number, hi: number) =>
  Math.max(lo, Math.min(hi, n));
const sign = (n: number) => (n < 0 ? -1 : 1);
function random(s: Encounter) {
  s.seed = (Math.imul(s.seed, 1664525) + 1013904223) >>> 0;
  return s.seed / 4294967296;
}
function effect(
  s: Encounter,
  kind: Effect["kind"],
  x: number,
  z: number,
  text = "",
  color = "#fff0a6",
  life = 0.5,
) {
  s.effects.push({ kind, x, z, age: 0, life, text, color });
  if (s.effects.length > 80) s.effects.shift();
}
export function createEnemy(
  kind: EnemyKind,
  x: number,
  chapter: number,
  id: number,
  elite = false,
): Enemy {
  const base: Record<EnemyKind, number> = {
    chaser: 44,
    sniper: 38,
    shield: 62,
    leaper: 46,
    bomber: 45,
    healer: 48,
    quake: 270,
    prism: 320,
    mirror: 340,
    decoy: 999,
    crystal: 30,
  };
  const hp = Math.round(
    base[kind] *
      (elite ? 1.65 : 1) *
      (["quake", "prism", "mirror", "decoy", "crystal"].includes(kind)
        ? 1
        : 1 + chapter * 0.65),
  );
  return {
    id,
    kind,
    elite,
    x,
    z: 0,
    hp,
    maxHp: hp,
    facing: -1,
    age: 0,
    state: "move",
    target: 0,
    pattern: 0,
    stun: 0,
    burn: 0,
    burnTick: 0,
    flash: 0,
    shieldWindow: 0,
    fakeCooldown: 0,
    ward: 0,
  };
}
export function createEncounter(
  memory: RiftMemory,
  checkpoint: Checkpoint,
): Encounter {
  const growth = memory.heroes[memory.selected],
    weapon = equipment.find((e) => e.id === growth.weapon)!,
    charm = equipment.find((e) => e.id === growth.charm);
  const shell = charm?.id === "charm-shell",
    feather = charm?.id === "charm-feather";
  const maxHp =
    110 +
    (level(growth.xp) - 1) * 8 +
    (shell ? 28 : 0) -
    (charm?.id === "charm-ember" ? 12 : 0);
  const enemies = roomEnemies[checkpoint.chapter]![checkpoint.room]!.map(
    (e, i) => createEnemy(e.kind, e.x, checkpoint.chapter, i + 1, e.elite),
  );
  return {
    phase: "ready",
    heroClass: memory.selected,
    checkpoint: { ...checkpoint },
    time: 0,
    player: {
      x: 160,
      z: 0,
      vz: 0,
      facing: 1,
      hp: maxHp,
      maxHp,
      stamina: 100,
      energy: 80,
      invulnerable: 0,
      dodge: 0,
      parry: 0,
      guard: 0,
      overcharge: 0,
      charge: 0,
      combo: 0,
      comboTime: 0,
      attackBuffer: 0,
      cooldowns: [0, 0, 0, 0, 0, 0],
      action: null,
    },
    enemies,
    projectiles: [],
    hazards: [],
    effects: [],
    ranks: [...growth.ranks],
    slots: [...growth.slots],
    nextId: 20,
    seed: checkpoint.seed,
    stats: {
      power:
        14 + (level(growth.xp) - 1) * 1.5 + weapon.power + (charm?.power ?? 0),
      attackSpeed: weapon.speed,
      reach: weapon.reach,
      moveSpeed: shell ? 218 : feather ? 278 : 248,
      reduction: shell ? 0.8 : charm?.id === "charm-glass" ? 1.25 : 1,
      energyRate: charm?.id === "charm-spring" ? 10 : 5,
      dodgeCost: feather ? 22 : 32,
      cooldownRate: charm?.id === "charm-clock" ? 0.75 : 1,
      ember: charm?.id === "charm-ember",
      scatter: weapon.id === "lancer-spread",
      longGun: weapon.id === "lancer-charge",
    },
    hint:
      checkpoint.room === 2
        ? [
            "震地前跳起，冲撞时闪开。",
            "击破两侧晶体，趁破盾窗口输出。",
            "脚下金环是真身，击中分身会被反击。",
          ][checkpoint.chapter]!
        : "读准前摇，移动和闪避比站桩更有效。",
    comboHits: 0,
    parries: 0,
    mechanism: {
      quakesAvoided: 0,
      shieldsBroken: 0,
      trueHits: 0,
      decoyHits: 0,
    },
    wave: 1,
    totalWaves: reinforcements[checkpoint.chapter]?.[checkpoint.room]?.length
      ? 2
      : 1,
    arrival: 0,
    incoming: [],
  };
}
export function beginEncounter(s: Encounter) {
  if (s.phase === "ready" || s.phase === "paused") s.phase = "running";
}
export function pauseEncounter(s: Encounter) {
  if (s.phase === "running") {
    s.phase = "paused";
    s.player.attackBuffer = 0;
    s.player.charge = 0;
  }
}
export function jump(s: Encounter) {
  if (s.phase !== "running" || s.player.z > 0.5) return false;
  s.player.vz = 650;
  effect(s, "burst", s.player.x, 0, "", "#ffe2a4", 0.25);
  return true;
}
export function dodge(s: Encounter, direction = 0) {
  const p = s.player;
  if (s.phase !== "running" || p.dodge > 0 || p.stamina < s.stats.dodgeCost)
    return false;
  p.stamina -= s.stats.dodgeCost;
  p.dodge = 0.28;
  p.invulnerable = 0.28;
  p.action = null;
  p.attackBuffer = 0;
  p.charge = 0;
  if (direction) p.facing = sign(direction);
  effect(s, "dash", p.x, p.z, "", "#ffe8c3");
  return true;
}
export function attackPressed(s: Encounter) {
  if (s.phase === "running" && s.heroClass === "blade")
    s.player.attackBuffer = 0.2;
}
function projectile(
  s: Encounter,
  team: Projectile["team"],
  x: number,
  z: number,
  vx: number,
  vz: number,
  damage: number,
  pierce = 0,
  armorPiercing = false,
  radius = 7,
  lob = false,
) {
  s.projectiles.push({
    id: s.nextId++,
    team,
    x,
    z,
    vx,
    vz,
    damage,
    pierce,
    armorPiercing,
    radius,
    lob,
    life: 4,
    hits: [],
  });
}
export function attackReleased(s: Encounter) {
  if (s.phase !== "running" || s.heroClass !== "lancer" || s.player.dodge > 0)
    return false;
  const p = s.player;
  if (p.action) {
    p.charge = 0;
    return false;
  }
  const charged = p.charge >= 0.62 / s.stats.attackSpeed,
    factor = charged ? 2.6 : 1,
    count = s.stats.scatter ? 3 : 1;
  for (let i = 0; i < count; i++)
    projectile(
      s,
      "hero",
      p.x + p.facing * 12,
      p.z + 30,
      p.facing * (charged ? 650 : 530),
      (i - (count - 1) / 2) * 70,
      s.stats.power * factor * (p.overcharge > 0 ? 1.35 : 1),
      charged ? (s.stats.longGun ? 3 : 1) : 0,
      charged,
      charged ? 12 : 6,
    );
  p.action = {
    combo: 0,
    age: 0,
    impact: 0,
    duration: 0.24 / s.stats.attackSpeed,
    hit: true,
    facing: p.facing,
  };
  p.charge = 0;
  effect(
    s,
    "burst",
    p.x + p.facing * 25,
    p.z + 30,
    charged ? "蓄力" : "",
    "#fff4af",
    0.2,
  );
  return true;
}
function damageEnemy(
  s: Encounter,
  e: Enemy,
  amount: number,
  fromX: number,
  piercing = false,
  knock = 0,
  applyEmber = true,
) {
  if (e.hp <= 0) return;
  if (e.kind === "decoy") {
    if (e.fakeCooldown <= 0) {
      e.fakeCooldown = 0.8;
      s.mechanism.decoyHits++;
      effect(s, "text", e.x, 75, "分身反击", "#ff7c6a");
      projectile(s, "enemy", e.x, 32, sign(s.player.x - e.x) * 280, 0, 10);
    }
    return;
  }
  if (
    e.kind === "prism" &&
    s.enemies.some((n) => n.kind === "crystal" && n.hp > 0)
  ) {
    effect(s, "text", e.x, 80, "先击破晶体", "#b7e8ef");
    return;
  }
  const guarded =
    e.kind === "shield" &&
    e.state === "move" &&
    sign(fromX - e.x) === e.facing &&
    !piercing;
  const warded = e.ward > 0 && !piercing;
  const damage = Math.max(
    1,
    Math.round(amount * (guarded ? 0.18 : 1) * (warded ? 0.5 : 1)),
  );
  e.hp = Math.max(0, e.hp - damage);
  e.flash = 0.13;
  s.comboHits++;
  s.player.energy = Math.min(100, s.player.energy + 2.5);
  if (e.kind === "mirror") s.mechanism.trueHits++;
  if (applyEmber && s.stats.ember && e.kind !== "crystal") e.burn = 3;
  if (!["quake", "prism", "mirror", "crystal"].includes(e.kind)) {
    e.x = clamp(e.x + sign(e.x - fromX) * knock, 45, ROOM_WIDTH - 45);
    if (!guarded) e.stun = Math.max(e.stun, 0.16);
  }
  effect(
    s,
    "hit",
    e.x,
    e.z + 44,
    guarded ? `格挡 ${damage}` : warded ? `护盾 ${damage}` : String(damage),
    guarded || warded ? "#b7d2d5" : "#fff0a6",
  );
  if (e.hp === 0)
    effect(
      s,
      "burst",
      e.x,
      e.z + 25,
      e.kind === "crystal" ? "断能" : "",
      "#ffd18b",
      0.7,
    );
}
function strike(
  s: Encounter,
  range: number,
  amount: number,
  allDirections = false,
  piercing = false,
  knock = 18,
) {
  const p = s.player;
  for (const e of s.enemies)
    if (
      Math.abs(e.x - p.x) < range &&
      (allDirections || sign(e.x - p.x) === p.facing) &&
      Math.abs(e.z - p.z) < 78
    )
      damageEnemy(s, e, amount, p.x, piercing, knock);
  effect(
    s,
    "slash",
    p.x + (allDirections ? 0 : (p.facing * range) / 2),
    p.z + 28,
    allDirections ? "环" : String(p.facing),
    "#ffeeb9",
    0.25,
  );
}
export function hurtPlayer(s: Encounter, amount: number, source?: Enemy) {
  const p = s.player;
  if (s.phase !== "running" || p.invulnerable > 0 || p.hp <= 0) return false;
  if (p.parry > 0) {
    p.parry = 0;
    p.invulnerable = 0.4;
    p.energy = Math.min(100, p.energy + 18);
    s.parries++;
    effect(s, "parry", p.x, p.z + 30, "弹反！", "#fffbb9", 0.65);
    if (source) {
      source.stun = 1.1;
      damageEnemy(
        s,
        source,
        s.stats.power * (2 + 0.25 * s.ranks[0]!),
        p.x,
        true,
        45,
      );
    }
    return false;
  }
  const damage = Math.round(
    amount * s.stats.reduction * (p.guard > 0 ? 0.55 : 1),
  );
  p.hp = Math.max(0, p.hp - damage);
  p.invulnerable = 0.55;
  p.charge = 0;
  p.action = null;
  p.attackBuffer = 0;
  effect(s, "hit", p.x, p.z + 48, `−${damage}`, "#fa6a58");
  if (p.hp === 0) {
    s.phase = "lost";
    s.hint = "回到检查点，再读一次敌人的前摇。";
  }
  return true;
}
export function castSkill(s: Encounter, slot: number) {
  const p = s.player,
    id = s.slots[slot];
  if (
    s.phase !== "running" ||
    id === undefined ||
    p.dodge > 0 ||
    p.cooldowns[id]! > 0 ||
    s.ranks[id]! <= 0
  )
    return false;
  const definition = skills[s.heroClass][id]!,
    rank = s.ranks[id]!,
    power = s.stats.power * (1 + 0.2 * (rank - 1));
  if (p.energy < definition.cost) {
    s.hint = "能量不足：普攻命中可以恢复能量。";
    return false;
  }
  p.energy -= definition.cost;
  p.cooldowns[id] = definition.cooldown * s.stats.cooldownRate;
  p.action = null;
  if (s.heroClass === "blade") {
    if (id === 0) {
      p.parry = 0.28 + 0.04 * (rank - 1);
      effect(s, "parry", p.x, p.z + 25, "", "#ffed9a", p.parry);
    }
    if (id === 1) {
      strike(s, 120, power * 2, false, true, 24);
      p.vz = 500;
      for (const e of s.enemies)
        if (
          e.hp > 0 &&
          Math.abs(e.x - p.x) < 110 &&
          !["quake", "prism", "mirror", "decoy", "crystal"].includes(e.kind)
        ) {
          e.z = 60;
          e.stun = 0.65;
        }
    }
    if (id === 2) {
      strike(s, 140, power * 1.8, true, false, 40);
      s.hazards.push({
        x: p.x,
        radius: 145,
        age: 0,
        delay: 0.3,
        damage: power * 1.6,
        team: "hero",
        ground: false,
        source: 0,
      });
    }
    if (id === 3) {
      p.x = clamp(p.x + p.facing * 135, 40, ROOM_WIDTH - 40);
      p.invulnerable = 0.18;
      strike(s, 150, power * 2.2, true, true, 35);
      effect(s, "dash", p.x, p.z);
    }
    if (id === 4)
      projectile(
        s,
        "hero",
        p.x,
        22,
        p.facing * 600,
        0,
        power * 2.2,
        6,
        true,
        20,
      );
    if (id === 5) {
      p.guard = 4 + rank;
      p.hp = Math.min(p.maxHp, p.hp + 14 + rank * 5);
      effect(s, "heal", p.x, p.z + 60, "战意", "#c6edaa");
    }
  } else {
    if (id === 0)
      projectile(
        s,
        "hero",
        p.x,
        p.z + 30,
        p.facing * 850,
        0,
        power * 3,
        8,
        true,
        18,
      );
    if (id === 1) {
      s.hazards.push({
        x: p.x + p.facing * 45,
        radius: 130,
        age: 0,
        delay: 0.9,
        damage: power * 4,
        team: "hero",
        ground: true,
        source: 0,
      });
      effect(s, "text", p.x, 55, "诱敌入圈");
    }
    if (id === 2) {
      const old = p.x;
      p.x = clamp(p.x + p.facing * 180, 40, ROOM_WIDTH - 40);
      p.invulnerable = 0.28;
      effect(s, "dash", old, p.z);
      projectile(
        s,
        "hero",
        p.x,
        p.z + 30,
        p.facing * 620,
        0,
        power * 2,
        2,
        true,
        12,
      );
    }
    if (id === 3)
      for (let i = -2; i <= 2; i++)
        projectile(
          s,
          "hero",
          p.x,
          p.z + 30,
          p.facing * 500,
          i * 70,
          power * 1.45,
          1,
          false,
          8,
        );
    if (id === 4) {
      const center = p.x + p.facing * 180;
      for (const e of s.enemies)
        if (e.hp > 0 && Math.abs(e.x - center) < 210) {
          if (
            !["quake", "prism", "mirror", "decoy", "crystal"].includes(e.kind)
          ) {
            e.x += (center - e.x) * 0.65;
            e.stun = 1.1;
          }
          damageEnemy(s, e, power * 1.5, p.x, true);
        }
      effect(s, "burst", center, 30, "聚拢", "#bce5e7", 0.8);
    }
    if (id === 5) {
      p.overcharge = 4 + rank;
      effect(s, "text", p.x, 70, "星核过载", "#fff4aa");
    }
  }
  return true;
}
function telegraph(e: Enemy, target: number) {
  e.state = "windup";
  e.age = 0;
  e.target = target;
  e.facing = sign(target - e.x);
}
function recover(e: Enemy) {
  e.state = "recover";
  e.age = 0;
  e.pattern++;
}
function fireAtPlayer(s: Encounter, e: Enemy, count = 1) {
  const dx = s.player.x - e.x,
    dz = s.player.z + 28 - (e.z + 35),
    length = Math.max(1, Math.hypot(dx, dz));
  for (let i = 0; i < count; i++)
    projectile(
      s,
      "enemy",
      e.x,
      e.z + 35,
      (dx / length) * 265,
      (dz / length) * 265 + (i - (count - 1) / 2) * 75,
      e.elite ? 17 : 13,
    );
}
function boss(s: Encounter, e: Enemy, dt: number) {
  if (e.kind === "prism") {
    const crystals = s.enemies.filter((n) => n.kind === "crystal" && n.hp > 0);
    if (!crystals.length && e.shieldWindow === 0) {
      e.shieldWindow = 7;
      s.mechanism.shieldsBroken++;
      s.hint = "护盾已破！七秒输出窗口。";
      effect(s, "burst", e.x, 60, "破盾", "#aff2ec", 0.9);
    }
    if (e.shieldWindow > 0) {
      e.shieldWindow = Math.max(0.00001, e.shieldWindow - dt);
      if (e.shieldWindow <= 0.00001) {
        e.shieldWindow = 0;
        s.enemies = s.enemies.filter((n) => n.kind !== "crystal");
        s.enemies.push(
          createEnemy("crystal", 400, 1, s.nextId++),
          createEnemy("crystal", 1360, 1, s.nextId++),
        );
        s.hint = "供能重启，击破两侧晶体。";
      }
    }
    if (e.state === "move" && e.age > 0.85) telegraph(e, s.player.x);
    if (e.state === "windup" && e.age > 0.8) {
      fireAtPlayer(s, e, 3);
      recover(e);
    }
    if (e.state === "recover" && e.age > 0.6) {
      e.state = "move";
      e.age = 0;
    }
    return;
  }
  if (e.kind === "mirror") {
    if (e.state === "move" && e.age > 1) {
      telegraph(e, s.player.x);
      for (const decoy of s.enemies.filter((n) => n.kind === "decoy")) {
        decoy.state = "windup";
        decoy.age = 0;
        decoy.target = s.player.x;
      }
    }
    if (e.state === "windup" && e.age > 0.9) {
      fireAtPlayer(s, e, 3);
      s.hazards.push({
        x: e.target,
        radius: 90,
        age: 0,
        delay: 0.55,
        damage: 23,
        team: "enemy",
        ground: true,
        source: e.id,
      });
      recover(e);
    }
    if (e.state === "recover" && e.age > 0.65) {
      const copies = s.enemies.filter((n) => n.kind === "decoy"),
        other = copies[Math.floor(random(s) * copies.length)];
      if (other) {
        const old = e.x;
        e.x = other.x;
        other.x = old;
      }
      for (const n of copies) {
        n.state = "move";
        n.age = 0;
      }
      e.state = "move";
      e.age = 0;
      effect(s, "burst", e.x, 40, "", "#ffe7a0", 0.3);
    }
    return;
  }
  if (e.state === "move" && e.age > 0.85) telegraph(e, s.player.x);
  if (e.state === "windup" && e.age > (e.pattern % 2 === 0 ? 1 : 0.85)) {
    if (e.pattern % 2 === 0) {
      effect(s, "burst", e.x, 0, "震地", "#f89b58", 0.75);
      if (s.player.z < 40) hurtPlayer(s, 29, e);
      else {
        s.mechanism.quakesAvoided++;
        effect(s, "text", s.player.x, s.player.z + 60, "跃过震波");
      }
    } else {
      e.motion = {
        from: e.x,
        to: clamp(e.target, 100, ROOM_WIDTH - 100),
        age: 0,
        duration: 0.36,
        damage: 25,
        hit: false,
        leap: false,
      };
      effect(s, "dash", e.x, 20, "", "#ffb477", 0.5);
    }
    recover(e);
  }
  if (e.state === "recover" && e.age > 0.9) {
    e.state = "move";
    e.age = 0;
  }
}
function enemyStep(s: Encounter, e: Enemy, dt: number) {
  if (e.hp <= 0) return;
  e.flash = Math.max(0, e.flash - dt);
  e.fakeCooldown = Math.max(0, e.fakeCooldown - dt);
  e.ward = Math.max(0, e.ward - dt);
  if (e.motion) {
    const m = e.motion;
    m.age = Math.min(m.duration, m.age + dt);
    const progress = m.age / m.duration;
    e.x = m.from + (m.to - m.from) * progress;
    e.z = m.leap ? Math.sin(progress * Math.PI) * 90 : 0;
    if (
      !m.hit &&
      Math.abs(s.player.x - e.x) < 62 &&
      Math.abs(s.player.z - e.z) < 60
    ) {
      m.hit = hurtPlayer(s, m.damage, e);
    }
    if (progress === 1) {
      e.motion = undefined;
      e.z = 0;
    }
    return;
  }
  if (e.burn > 0) {
    e.burn -= dt;
    e.burnTick += dt;
    if (e.burnTick > 0.75) {
      e.burnTick = 0;
      damageEnemy(s, e, 3, s.player.x, true, 0, false);
    }
  }
  if (e.stun > 0) {
    e.stun -= dt;
    e.z = Math.max(0, e.z - 110 * dt);
    return;
  }
  if (e.kind === "crystal" || e.kind === "decoy") return;
  e.age += dt;
  if (["quake", "prism", "mirror"].includes(e.kind)) {
    boss(s, e, dt);
    return;
  }
  const dx = s.player.x - e.x,
    distance = Math.abs(dx);
  if (e.state === "recover") {
    if (e.age > (e.elite ? 0.65 : 0.85)) {
      e.state = "move";
      e.age = 0;
    }
    return;
  }
  if (e.state === "windup") {
    const duration =
      e.kind === "sniper"
        ? 1
        : e.kind === "healer"
          ? 0.95
          : e.kind === "leaper" || e.elite
            ? 0.8
            : 0.65;
    if (e.age < duration) return;
    if (e.kind === "sniper") fireAtPlayer(s, e, e.elite ? 3 : 1);
    else if (e.kind === "bomber") {
      s.hazards.push({
        x: e.target,
        radius: 90,
        age: 0,
        delay: 0.9,
        damage: 21,
        team: "enemy",
        ground: true,
        source: e.id,
      });
      projectile(
        s,
        "enemy",
        e.x,
        45,
        (e.target - e.x) / 0.9,
        330,
        0,
        0,
        false,
        9,
        true,
      );
    } else if (e.kind === "healer") {
      const allies = s.enemies.filter(
        (n) =>
          n.id !== e.id &&
          n.hp > 0 &&
          (e.elite || n.hp < n.maxHp) &&
          Math.abs(n.x - e.x) < 420,
      );
      if (allies.length)
        for (const n of allies) {
          n.hp = Math.min(n.maxHp, n.hp + (e.elite ? 22 : 14));
          if (e.elite) {
            n.stun = 0;
            n.ward = 3.5;
          }
          effect(s, "heal", n.x, 65, e.elite ? "护盾" : "+", "#a9e4b4");
        }
      else fireAtPlayer(s, e);
    } else if (e.kind === "leaper" || (e.kind === "chaser" && e.elite)) {
      e.motion = {
        from: e.x,
        to: clamp(e.target, 45, ROOM_WIDTH - 45),
        age: 0,
        duration: 0.32,
        damage: e.elite ? 24 : 19,
        hit: false,
        leap: e.kind === "leaper",
      };
      effect(s, "dash", e.x, 20, "", "#f6ba73");
    } else if (
      distance < (e.kind === "shield" ? 105 : 88) &&
      sign(dx) === e.facing &&
      s.player.z < 68
    )
      hurtPlayer(s, e.kind === "shield" ? 20 : 16, e);
    recover(e);
    return;
  }
  e.facing = sign(dx);
  const ranged = ["sniper", "healer", "bomber"].includes(e.kind);
  const reach = ranged ? 650 : e.kind === "leaper" || e.elite ? 280 : 70;
  if (distance < reach && e.age > 0.5) {
    telegraph(e, s.player.x);
    return;
  }
  if (distance > (ranged ? 330 : 62))
    e.x = clamp(
      e.x + e.facing * (e.kind === "shield" ? 65 : ranged ? 65 : 106) * dt,
      45,
      ROOM_WIDTH - 45,
    );
}
export function stepEncounter(
  s: Encounter,
  dt: number,
  controls: RiftControls,
) {
  if (s.phase !== "running" || !Number.isFinite(dt) || dt <= 0) return;
  dt = Math.min(dt, 1 / 30);
  s.time += dt;
  const p = s.player;
  for (const key of [
    "invulnerable",
    "dodge",
    "parry",
    "guard",
    "overcharge",
    "comboTime",
    "attackBuffer",
  ] as const)
    p[key] = Math.max(0, p[key] - dt);
  p.cooldowns = p.cooldowns.map((n) => Math.max(0, n - dt));
  p.stamina = Math.min(100, p.stamina + (p.dodge > 0 ? 0 : 25) * dt);
  p.energy = Math.min(100, p.energy + s.stats.energyRate * dt);
  const move = Number(controls.right) - Number(controls.left);
  if (p.dodge > 0) p.x += p.facing * 670 * dt;
  else {
    if (move) p.facing = move;
    p.x +=
      move *
      s.stats.moveSpeed *
      (p.action ? 0.45 : p.charge > 0.1 ? 0.65 : 1) *
      dt;
  }
  p.x = clamp(p.x, 35, ROOM_WIDTH - 35);
  if (p.z > 0 || p.vz > 0) {
    p.vz -= GRAVITY * dt * (controls.down && p.vz < 0 ? 2 : 1);
    p.z += p.vz * dt;
    if (p.z <= 0) {
      p.z = 0;
      p.vz = 0;
    }
  }
  if (controls.down && p.z === 0 && !move) p.guard = Math.max(p.guard, 0.05);
  if (
    s.heroClass === "lancer" &&
    controls.attackHeld &&
    p.dodge <= 0 &&
    !p.action
  )
    p.charge = Math.min(1.5, p.charge + dt * (p.overcharge > 0 ? 1.75 : 1));
  if (p.action) {
    const action = p.action;
    action.age += dt;
    if (!action.hit && action.age >= action.impact) {
      const facing = p.facing;
      p.facing = action.facing;
      strike(
        s,
        [0, 82, 96, 122][action.combo]! + s.stats.reach,
        s.stats.power * [0, 1, 1.12, 1.6][action.combo]!,
        false,
        false,
        action.combo === 3 ? 58 : 12,
      );
      p.facing = facing;
      action.hit = true;
    }
    if (action.age >= action.duration) p.action = null;
  }
  if (
    s.heroClass === "blade" &&
    p.attackBuffer > 0 &&
    !p.action &&
    p.dodge <= 0
  ) {
    p.combo = p.comboTime > 0 ? (p.combo % 3) + 1 : 1;
    p.comboTime = 0.95;
    p.attackBuffer = 0;
    p.action = {
      combo: p.combo,
      age: 0,
      impact: 0.07 / s.stats.attackSpeed,
      duration: (p.combo === 3 ? 0.4 : 0.27) / s.stats.attackSpeed,
      hit: false,
      facing: p.facing,
    };
  }
  for (const e of s.enemies) enemyStep(s, e, dt);
  for (const shot of s.projectiles) {
    shot.life -= dt;
    shot.x += shot.vx * dt;
    shot.z += shot.vz * dt;
    if (shot.lob) {
      shot.vz -= 800 * dt;
      if (shot.z < 0) shot.life = 0;
      continue;
    }
    if (shot.team === "hero") {
      for (const e of s.enemies)
        if (
          e.hp > 0 &&
          !shot.hits.includes(e.id) &&
          Math.abs(shot.x - e.x) < 23 + shot.radius &&
          Math.abs(shot.z - (e.z + 28)) < 30 + shot.radius
        ) {
          damageEnemy(
            s,
            e,
            shot.damage,
            shot.x - shot.vx * 0.1,
            shot.armorPiercing,
            10,
          );
          shot.hits.push(e.id);
          // Illusions and the powered core absorb even piercing shots: the
          // player must change position, rather than shoot through the mechanic.
          const absorbs =
            e.kind === "decoy" ||
            (e.kind === "prism" &&
              s.enemies.some((n) => n.kind === "crystal" && n.hp > 0));
          if (absorbs || shot.pierce-- <= 0) {
            shot.life = 0;
            break;
          }
        }
    } else if (
      Math.abs(shot.x - p.x) < 17 + shot.radius &&
      Math.abs(shot.z - (p.z + 27)) < 27 + shot.radius
    ) {
      hurtPlayer(s, shot.damage);
      shot.life = 0;
    }
  }
  s.projectiles = s.projectiles.filter(
    (n) =>
      n.life > 0 &&
      n.x > -100 &&
      n.x < ROOM_WIDTH + 100 &&
      n.z > -60 &&
      n.z < 650,
  );
  for (const h of s.hazards) {
    h.age += dt;
    if (h.age < h.delay) continue;
    if (h.team === "enemy") {
      if (Math.abs(p.x - h.x) < h.radius && (!h.ground || p.z < 42))
        hurtPlayer(
          s,
          h.damage,
          s.enemies.find((e) => e.id === h.source),
        );
    } else
      for (const e of s.enemies)
        if (Math.abs(e.x - h.x) < h.radius && (!h.ground || e.z < 65))
          damageEnemy(s, e, h.damage, h.x, true, 25);
    effect(
      s,
      "burst",
      h.x,
      18,
      "",
      h.team === "hero" ? "#ffe6a3" : "#f78765",
      0.5,
    );
    h.age = 999;
  }
  s.hazards = s.hazards.filter((h) => h.age < 999);
  for (const e of s.effects) e.age += dt;
  s.effects = s.effects.filter((e) => e.age < e.life);
  if (
    s.phase === "running" &&
    !s.enemies.some((e) => e.hp > 0 && !["decoy", "crystal"].includes(e.kind))
  ) {
    if (s.wave < s.totalWaves) {
      if (!s.incoming.length) {
        s.arrival = 1.5;
        s.incoming = reinforcements[s.checkpoint.chapter]![
          s.checkpoint.room
        ]!.map((n) =>
          createEnemy(
            n.kind,
            clamp(p.x + n.offset, 60, ROOM_WIDTH - 60),
            s.checkpoint.chapter,
            s.nextId++,
            n.elite,
          ),
        );
        s.projectiles = [];
        s.hazards = [];
        s.hint = "两侧裂隙将打开，留意增援预警。";
      } else {
        s.arrival = Math.max(0, s.arrival - dt);
        if (s.arrival === 0) {
          s.enemies = s.incoming;
          s.incoming = [];
          s.wave++;
          for (const e of s.enemies) {
            e.age = -0.5;
            effect(s, "burst", e.x, 30, "增援", "#bfe0d4", 0.8);
          }
        }
      }
    } else {
      s.phase = "won";
      p.charge = 0;
      s.hint = "裂隙平息了。带上战利品，继续向前。";
    }
  }
}
