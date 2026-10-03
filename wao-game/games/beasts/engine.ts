import { species, regions, type BeastId, type Bond } from "./catalog";
import type {
  Battle,
  Beast,
  BeastMemory,
  Fighter,
  Priority,
  Stance,
} from "./model";
import { clone, collect } from "./save";

export function stats(beast: Beast) {
  const s = species[beast.id],
    growth = 1 + (beast.level - 1) * 0.14 + (beast.evolved ? 0.22 : 0);
  return {
    hp: Math.round(s.hp * growth),
    attack: Math.round(s.attack * growth),
    armor: s.armor + Math.floor(beast.level / 2),
    interval: s.interval,
    cooldown: s.cooldown,
  };
}
export function activeBonds(memory: BeastMemory): Bond[] {
  const tags = memory.team.map((id) => species[id].bond);
  return (["bloom", "tide", "ember", "echo"] as Bond[]).filter(
    (tag) => tags.filter((t) => t === tag).length >= 2,
  );
}
export const living = (b: Battle, side: Fighter["side"]) =>
  b.actors.filter((a) => a.side === side && a.hp > 0);
const weakest = (actors: Fighter[]) =>
  [...actors].sort((a, b) => a.hp / a.maxHp - b.hp / b.maxHp)[0];
function message(b: Battle, text: string) {
  b.log.push(text);
  if (b.log.length > 10) b.log.shift();
}
function empty(
  id: string,
  kind: Fighter["kind"],
  name: string,
  side: Fighter["side"],
  hp: number,
  attack: number,
  armor: number,
): Fighter {
  return {
    id,
    kind,
    name,
    side,
    hp,
    maxHp: hp,
    attack,
    armor,
    shield: 0,
    attackWait: 1,
    skillWait: 2,
    interval: 1.5,
    cooldown: 6,
    path: 0,
    rank: 0,
    evolved: false,
    priority: "balanced",
    burn: 0,
    poison: 0,
    slow: 0,
    silence: 0,
    stun: 0,
    haste: 0,
    guard: 0,
    taunt: 0,
    counter: 0,
    weak: 0,
    cast: 0,
    casts: 0,
    calm: 0,
  };
}
export function startBattle(memory: BeastMemory): Battle | null {
  const encounter = memory.expedition?.pending;
  if (!encounter) return null;
  const actors = memory.team.map((id, i) => {
    const p = memory.roster.find((x) => x.id === id)!,
      s = stats(p),
      a = empty(
        "p" + i,
        id,
        p.evolved ? species[id].evolution!.name : species[id].name,
        "party",
        s.hp,
        s.attack,
        s.armor,
      );
    Object.assign(a, {
      interval: s.interval,
      cooldown: s.cooldown,
      path: p.path,
      rank: p.rank,
      evolved: p.evolved,
      priority: p.priority,
      skillWait: 1 + i * 0.6,
    });
    return a;
  });
  const region = encounter.region;
  if (encounter.guardian) {
    actors.push(
      empty(
        "g",
        "guardian",
        regions[region]!.guardian,
        "wild",
        290 + region * 180,
        20 + region * 8,
        4 + region * 2,
      ),
    );
    actors.at(-1)!.skillWait = 4;
    actors.at(-1)!.cooldown = 8;
    if (region === 2)
      actors.push(empty("helper", "healer", "火灵灯", "wild", 95, 7, 2));
  } else
    for (const [i, id] of encounter.wild.entries()) {
      const s = species[id],
        scale = (0.7 + region * 0.17) * (encounter.risk ? 1.18 : 1),
        a = empty(
          "w" + i,
          id,
          s.name,
          "wild",
          Math.round(s.hp * scale),
          Math.round(s.attack * (0.65 + region * 0.15)),
          s.armor,
        );
      a.interval = s.interval * 1.12;
      a.cooldown = s.cooldown + 1;
      a.skillWait = 3 + i;
      actors.push(a);
    }
  return {
    encounter: clone(encounter),
    actors,
    seconds: 0,
    tick: 0,
    phase: "running",
    stance: "assault",
    target: actors.find((a) => a.side === "wild")!.id,
    charge: 35,
    orderCooldown: 0,
    captureCooldown: 0,
    skillCount: 0,
    log: [
      regions[region]!.name +
        " · " +
        (encounter.guardian ? "守卫挑战" : "野外遭遇"),
    ],
    effects: [],
    captured: null,
    spentBait: 0,
    settled: false,
  };
}
function effect(
  b: Battle,
  from: Fighter,
  to: Fighter,
  kind: Battle["effects"][number]["kind"],
) {
  b.effects.push({ from: from.id, to: to.id, kind, life: 0.35 });
}
function heal(b: Battle, from: Fighter, to: Fighter, value: number) {
  if (to.hp <= 0) return;
  value *= b.encounter.terrain === "meadow" ? 1.15 : 1;
  const amount = Math.min(to.maxHp - to.hp, Math.round(value));
  to.hp += amount;
  if (from.kind === "fin" && value > amount)
    to.shield = Math.min(
      to.maxHp,
      to.shield + Math.round((value - amount) * 0.5),
    );
  effect(b, from, to, "heal");
}
function shield(b: Battle, from: Fighter, to: Fighter, value: number) {
  to.shield = Math.min(
    to.maxHp,
    to.shield + Math.round(value * (b.encounter.terrain === "water" ? 1.2 : 1)),
  );
  effect(b, from, to, "shield");
}
function status(
  b: Battle,
  target: Fighter,
  name: "burn" | "poison" | "slow" | "silence" | "stun" | "weak",
  seconds: number,
) {
  if (target.hp <= 0) return;
  const duration =
    seconds *
    (b.encounter.terrain === "moon" &&
    ["stun", "silence", "slow"].includes(name)
      ? 1.2
      : 1);
  target[name] = Math.max(target[name], duration);
  if (name === "silence" || name === "stun") {
    if (target.cast > 0) message(b, target.name + "的蓄力被打断。");
    target.cast = 0;
    target.skillWait = Math.max(target.skillWait, 3);
  }
}
function damage(
  b: Battle,
  from: Fighter,
  to: Fighter,
  value: number,
  options: { dot?: boolean; pierce?: boolean; counter?: boolean } = {},
) {
  if (to.hp <= 0) return;
  let amount = Math.max(1, value - (options.dot ? 0 : to.armor));
  if (to.weak > 0) amount *= 1.25;
  if (from.side === "party" && !options.dot) {
    if (b.stance === "guard") amount *= 0.68;
    if (b.stance === "tame") amount *= 0.3;
  }
  if (to.side === "party" && b.stance === "guard") amount *= 0.4;
  if (to.guard > 0) amount *= 0.65;
  if (options.dot && b.encounter.terrain === "ridge") amount *= 1.3;
  amount = Math.round(amount);
  const absorbed =
    options.pierce || options.dot ? 0 : Math.min(to.shield, amount);
  to.shield -= absorbed;
  amount -= absorbed;
  if (to.side === "party") {
    const crab = living(b, "party").find(
      (a) => a.kind === "crab" && a.shield > 0 && a.id !== to.id,
    );
    if (crab) {
      const shared = Math.min(crab.shield, Math.round(amount * 0.3));
      crab.shield -= shared;
      amount -= shared;
    }
  }
  const minimum =
    to.side === "wild" &&
    b.stance === "tame" &&
    to.kind !== "guardian" &&
    to.kind !== "healer"
      ? 1
      : 0;
  to.hp = Math.max(minimum, to.hp - amount);
  effect(b, from, to, options.dot ? "control" : "hit");
  b.charge = Math.min(100, b.charge + 2);
  if (to.hp <= 0) {
    message(b, to.name + "暂时退场。");
    const tags = living(b, from.side)
      .filter((a) => a.kind !== "guardian" && a.kind !== "healer")
      .map((a) => species[a.kind as BeastId].bond);
    if (
      to.burn > 0 &&
      tags.filter((t) => t === "ember").length >= 2 &&
      !options.dot
    )
      for (const other of living(b, to.side))
        damage(b, from, other, 8, { dot: true });
  }
  if (
    to.hp > 0 &&
    to.kind === "cinder" &&
    from.side !== to.side &&
    !options.dot
  )
    status(b, from, "burn", 3);
  if (to.hp > 0 && to.counter > 0 && !options.counter && !options.dot)
    damage(
      b,
      to,
      from,
      to.attack *
        (to.kind === "crab" && to.path === 0 ? 0.7 + to.rank * 0.2 : 0.7),
      { counter: true },
    );
  if (
    from.kind === "cinder" &&
    from.path === 0 &&
    from.rank > 0 &&
    options.counter
  )
    status(b, to, "burn", 3 + from.rank);
  if (from.kind === "sprig" && to.poison > 0 && !options.dot)
    heal(b, from, from, from.path === 1 ? 4 + from.rank * 2 : 3);
}
function targetFor(b: Battle, a: Fighter) {
  const enemy = living(b, a.side === "party" ? "wild" : "party");
  if (a.side === "party")
    return enemy.find((x) => x.id === b.target) ?? enemy[0];
  return enemy.find((x) => x.taunt > 0) ?? enemy[0];
}
function skill(b: Battle, a: Fighter) {
  const target = targetFor(b, a);
  if (!target) return;
  const friends = living(b, a.side),
    opponents = living(b, a.side === "party" ? "wild" : "party"),
    weak = weakest(friends)!;
  const r = a.rank,
    power = 1 + r * 0.14,
    boost = a.path === 0 ? 1 + r * 0.15 : 1;
  if (a.kind === "guardian") {
    a.cast = 2.4;
    a.casts++;
    message(b, a.name + "开始蓄力，留意守护或打断。");
    return;
  }
  if (a.kind === "healer") {
    const boss = friends.find((x) => x.kind === "guardian");
    if (boss) heal(b, a, boss, 45);
    message(b, "火灵灯为长者恢复生命。");
    return;
  }
  message(b, a.name + " · " + species[a.kind].skill);
  effect(b, a, target, "control");
  switch (a.kind) {
    case "sprig":
      for (let i = 0; i < 2 + (a.path === 0 && r > 0 ? 1 : 0); i++)
        damage(b, a, target, a.attack * 0.8 * power, { pierce: a.evolved });
      status(b, target, "poison", 5 + r);
      if (a.evolved) {
        const other = opponents.find((x) => x.id !== target.id);
        if (other) damage(b, a, other, a.attack * 0.6);
      }
      break;
    case "moss":
      for (const f of friends)
        shield(b, a, f, 15 * boost * (a.hp < a.maxHp * 0.5 ? 1.5 : 1));
      a.taunt = 4 + (a.evolved ? 2 : 0);
      if (a.path === 0 && r > 0) a.counter = 3 + r;
      else if (a.path === 1) heal(b, a, weak, 12 + r * 6);
      if (a.evolved) for (const f of friends) f.guard = 3;
      break;
    case "moth":
      for (const f of a.path === 1 && r > 0
        ? opponents.slice(0, 2)
        : [target]) {
        damage(b, a, f, a.attack * 1.1);
        status(b, f, "silence", 2 + r * (a.path === 0 ? 0.6 : 0.2));
        if (a.path === 0 && r > 0) status(b, f, "slow", 3 + r);
      }
      break;
    case "fin":
      heal(b, a, weak, (26 + r * 5) * boost);
      weak.burn = 0;
      weak.poison = 0;
      if (a.path === 0 && r > 0) weak.silence = 0;
      if (a.path === 1 && r > 0) {
        const next = weakest(friends.filter((f) => f.id !== weak.id));
        if (next) heal(b, a, next, 16 + r * 3);
      }
      if (a.evolved) for (const f of friends) shield(b, a, f, 8);
      break;
    case "otter": {
      const had = target.shield > 0;
      target.shield = Math.max(
        0,
        target.shield - (a.path === 0 ? 25 + r * 12 : 18),
      );
      damage(b, a, target, a.attack * power);
      damage(
        b,
        a,
        a.path === 1 && r > 0
          ? (opponents.find((x) => x.id !== target.id) ?? target)
          : target,
        a.attack * 0.8,
      );
      if (a.path === 0 && r > 0) status(b, target, "weak", 4);
      if (a.evolved && had && target.shield === 0)
        damage(b, a, target, a.attack);
      break;
    }
    case "crab":
      shield(b, a, a, 30 * boost);
      a.counter = 5 + r * 0.5;
      a.taunt = 5 + (a.path === 1 ? r : 0);
      if (a.path === 1 && r > 0)
        for (const f of friends) shield(b, a, f, 10 + r * 4);
      if (a.path === 0 && r > 0) status(b, target, "weak", 4);
      break;
    case "fox":
      for (const f of opponents) {
        damage(b, a, f, a.attack * 0.7 * boost);
        status(b, f, "burn", 5 + (a.path === 0 ? r : 0));
        if (a.evolved) status(b, f, "weak", 3);
      }
      if (a.path === 1 && r > 0) heal(b, a, weak, 10 + r * 5);
      break;
    case "cinder":
      for (const f of friends)
        shield(b, a, f, (12 + r * 4) * (a.path === 0 ? 1 + r * 0.1 : 1));
      a.counter = 4;
      a.taunt = 5;
      if (a.path === 1) heal(b, a, weak, 10 + r * 5);
      else if (r > 0) status(b, target, "burn", 4 + r);
      break;
    case "sparrow":
      for (const f of friends) {
        heal(b, a, f, 12 * boost * (friends.length < 3 ? 1.5 : 1));
        f.haste = 4 + (a.path === 0 ? r * 1.5 : r);
      }
      if (a.path === 1 && r > 0)
        for (const f of opponents) status(b, f, "burn", 3 + r);
      break;
    case "owl":
      heal(
        b,
        a,
        weak,
        22 +
          (friends.some(
            (f) =>
              f.kind !== "healer" &&
              f.kind !== "guardian" &&
              species[f.kind].role === "control",
          )
            ? 8
            : 0),
      );
      for (const f of friends)
        if (f !== a)
          f.skillWait = Math.max(
            0.2,
            f.skillWait - (a.path === 1 ? 1.5 + r * 0.6 : 1),
          );
      if (a.path === 0 && r > 0) {
        weak.silence = 0;
        weak.poison = 0;
      }
      if (a.evolved) for (const f of friends) f.guard = 3;
      break;
    case "lynx": {
      const charging = target.cast > 0;
      damage(b, a, target, a.attack * boost);
      status(b, target, "stun", 1.5 + (a.path === 0 ? r * 0.4 : 0));
      if (charging) status(b, target, "weak", 4);
      if (a.path === 1 && r > 0)
        for (const f of opponents) status(b, f, "slow", 3 + r);
      break;
    }
    case "bud":
      for (const f of opponents) {
        if (a.path === 0 && r > 0) damage(b, a, f, r * 3, { dot: true });
        status(b, f, "poison", 5 + (a.path === 0 ? r : 0));
        status(b, f, "slow", 4);
      }
      if (a.path === 1 && r > 0)
        for (const f of friends) shield(b, a, f, 6 + r * 3);
      break;
  }
  if (a.side === "party") {
    b.skillCount++;
    const tags = friends
      .filter((f) => f.kind !== "guardian" && f.kind !== "healer")
      .map((f) => species[f.kind as BeastId].bond);
    if (
      tags.filter((t) => t === "ember").length >= 2 &&
      species[a.kind].role !== "support"
    )
      status(b, target, "burn", 4);
    if (
      tags.filter((t) => t === "echo").length >= 2 &&
      b.skillCount % 3 === 0
    ) {
      status(b, target, "silence", 2);
      message(b, "回声合拍，敌人的节奏被打乱。");
    }
  }
}
function guardianCast(b: Battle, a: Fighter) {
  const team = living(b, "party");
  if (!team.length) return;
  const region = b.encounter.region;
  if (region === 0) {
    damage(b, a, team[0]!, 65);
    status(b, team[0]!, "slow", 3);
    message(b, "卷藤冲撞落下，守护姿态可大幅减伤。");
  }
  if (region === 1) {
    shield(b, a, a, 65);
    for (const f of team) damage(b, a, f, 24);
    message(b, "镜湖重新结盾，持续伤害能绕开镜盾。");
  }
  if (region === 2) {
    for (const f of team) {
      damage(b, a, f, 24);
      status(b, f, "burn", 4);
    }
    if (
      !living(b, "wild").some((x) => x.kind === "healer") &&
      a.casts % 2 === 0
    )
      b.actors.push(
        empty("helper" + a.casts, "healer", "火灵灯", "wild", 95, 7, 2),
      );
    message(b, "炉心散出热浪。");
  }
  if (region === 3) {
    for (const f of team) {
      damage(b, a, f, 70);
      status(b, f, "silence", 3);
    }
    message(b, "梦潮席卷队伍，下一次可以提前打断。");
  }
}
function check(b: Battle) {
  const targets = living(b, "wild");
  if (!targets.some((a) => a.id === b.target) && targets[0])
    b.target = targets[0].id;
  if (!living(b, "party").length) b.phase = "lost";
  else if (!living(b, "wild").length) b.phase = "won";
  if (b.seconds >= 150) b.phase = "lost";
}
export function advance(b: Battle, dt: number) {
  if (b.phase !== "running") return;
  dt = Math.max(0, Math.min(dt, 0.1));
  b.seconds += dt;
  b.tick++;
  b.orderCooldown = Math.max(0, b.orderCooldown - dt);
  b.captureCooldown = Math.max(0, b.captureCooldown - dt);
  b.charge = Math.min(100, b.charge + dt * 1.8);
  b.effects = b.effects.filter((e) => (e.life -= dt) > 0);
  const second = Math.floor(b.seconds) !== Math.floor(b.seconds - dt);
  if (second) {
    const team = living(b, "party"),
      tags = team
        .filter((a) => a.kind !== "guardian" && a.kind !== "healer")
        .map((a) => species[a.kind as BeastId].bond);
    if (
      Math.floor(b.seconds) % 4 === 0 &&
      tags.filter((t) => t === "bloom").length >= 2
    ) {
      for (const a of team) heal(b, a, a, 5 + (a.maxHp - a.hp) * 0.08);
      message(b, "生机相伴，草叶为伙伴恢复生命。");
    }
    if (
      Math.floor(b.seconds) % 5 === 0 &&
      tags.filter((t) => t === "tide").length >= 2
    ) {
      for (const a of team) {
        const cleansed = a.burn > 0 || a.poison > 0;
        a.burn = 0;
        a.poison = 0;
        shield(b, a, a, cleansed ? 16 : 6);
      }
      message(b, "潮汐相守，净化并形成护盾。");
    }
  }
  for (const a of b.actors) {
    if (a.hp <= 0) continue;
    if (second) {
      if (a.burn > 0) damage(b, a, a, 4, { dot: true });
      if (a.poison > 0) damage(b, a, a, 4, { dot: true });
      if (
        a.kind === "bud" &&
        living(b, a.side === "party" ? "wild" : "party").some(
          (x) => x.poison > 0,
        )
      )
        heal(b, a, a, 4);
      if (a.side === "wild") a.calm = Math.max(0, a.calm - 0.02);
    }
    for (const key of [
      "burn",
      "poison",
      "slow",
      "silence",
      "stun",
      "haste",
      "guard",
      "taunt",
      "counter",
      "weak",
    ] as const)
      a[key] = Math.max(
        0,
        a[key] -
          dt * (key === "burn" && b.encounter.terrain === "water" ? 1.4 : 1),
      );
    if (a.hp <= 0 || a.stun > 0) continue;
    if (a.cast > 0) {
      a.cast -= dt;
      if (a.cast <= 0 && a.silence <= 0) guardianCast(b, a);
      continue;
    }
    const speed = (a.slow > 0 ? 0.65 : 1) * (a.haste > 0 ? 1.3 : 1);
    a.attackWait -= dt * speed;
    let cdSpeed = b.encounter.terrain === "moon" ? 1.12 : 1;
    const target = targetFor(b, a);
    if (!target) continue;
    if (a.kind === "sprig" && a.path === 1) cdSpeed += a.rank * 0.1;
    if (a.kind === "moth" && target.cast > 0) cdSpeed += 0.5;
    if (
      a.kind === "fox" &&
      living(b, a.side === "party" ? "wild" : "party").filter((x) => x.burn > 0)
        .length >= 2
    )
      cdSpeed += 0.3;
    a.skillWait -= dt * cdSpeed;
    const info =
      a.kind === "guardian" || a.kind === "healer" ? null : species[a.kind];
    const tacticalReady =
      a.priority === "skill" ||
      a.side === "wild" ||
      !info ||
      info.role === "attack" ||
      (info.role === "support" &&
        living(b, a.side).some(
          (x) => x.hp < x.maxHp * (a.priority === "reserve" ? 0.55 : 0.85),
        )) ||
      (info.role === "guard" &&
        (a.priority !== "reserve" || target.cast > 0)) ||
      (info.role === "control" &&
        (a.priority !== "reserve" || target.cast > 0));
    if (
      a.skillWait <= 0 &&
      a.silence <= 0 &&
      tacticalReady &&
      (a.side !== "party" ||
        b.stance !== "tame" ||
        info?.role === "support" ||
        info?.role === "guard")
    ) {
      skill(b, a);
      a.skillWait = a.cooldown;
    }
    if (a.attackWait <= 0 && a.cast <= 0) {
      damage(b, a, target, a.attack);
      a.attackWait =
        a.interval * (a.kind === "otter" && target.shield > 0 ? 0.7 : 1);
    }
    check(b);
    if (b.phase !== "running") break;
  }
  check(b);
}
export function setStance(b: Battle, stance: Stance) {
  if (b.phase !== "running") return;
  b.stance = stance;
  message(
    b,
    stance === "guard"
      ? "全队守护，等待危险过去。"
      : stance === "tame"
        ? "全队留手，野兽生命不会降到零，仍需留意伙伴生命。"
        : "全队进攻。",
  );
}
export function setPriority(b: Battle, id: string, priority: Priority) {
  const a = b.actors.find((x) => x.id === id && x.side === "party");
  if (a) a.priority = priority;
}
export function order(b: Battle, kind: "rally" | "hush" | "soothe") {
  if (
    b.phase !== "running" ||
    (kind === "soothe" && b.encounter.guardian) ||
    b.orderCooldown > 0 ||
    b.charge < (kind === "soothe" ? 20 : 40)
  )
    return false;
  b.charge -= kind === "soothe" ? 20 : 40;
  b.orderCooldown = kind === "soothe" ? 2 : 5;
  if (kind === "rally") {
    for (const a of living(b, "party")) {
      heal(b, a, a, 20);
      shield(b, a, a, 12);
    }
    message(b, "集结号令：全队恢复并获得护盾。");
  }
  if (kind === "hush") {
    const a = living(b, "wild").find((x) => x.id === b.target);
    if (a) {
      status(b, a, "silence", 2.2);
      status(b, a, "stun", 0.4);
    }
    message(b, "静心号令打断目标。");
  }
  if (kind === "soothe") {
    const a = living(b, "wild").find((x) => x.id === b.target);
    if (a && a.kind !== "guardian" && a.kind !== "healer") {
      a.calm = Math.min(3, a.calm + 1);
      a.attackWait += 0.8;
      message(b, a.name + "放松了一点，捕获把握提升。");
    }
  }
  return true;
}
export function captureChance(b: Battle) {
  const target = living(b, "wild").find((a) => a.id === b.target);
  if (
    !target ||
    target.kind === "guardian" ||
    target.kind === "healer" ||
    b.encounter.guardian
  )
    return 0;
  const missing = 1 - target.hp / target.maxHp;
  return Math.min(100, Math.round(15 + missing * 65 + target.calm * 18));
}
export function capture(b: Battle, memory: BeastMemory) {
  if (
    b.phase !== "running" ||
    b.encounter.guardian ||
    b.captureCooldown > 0 ||
    memory.bait <= 0
  )
    return false;
  const target = living(b, "wild").find((a) => a.id === b.target);
  if (!target || target.kind === "guardian" || target.kind === "healer")
    return false;
  b.spentBait++;
  memory.bait--;
  b.captureCooldown = 6;
  const chance = captureChance(b),
    roll = (b.encounter.seed + b.spentBait * 37) % 100;
  if (roll < chance) {
    b.captured = target.kind;
    b.phase = "captured";
    message(b, target.name + "愿意跟你回家了。");
    return true;
  }
  target.calm = Math.min(3, target.calm + 0.5);
  message(b, "诱饵被悄悄吃掉了。继续安抚或等待受伤时机。");
  return false;
}
export function settle(input: BeastMemory, b: Battle): BeastMemory {
  const m = clone(input),
    e = m.expedition;
  if (
    b.phase === "running" ||
    b.settled ||
    !e?.pending ||
    e.pending.seed !== b.encounter.seed ||
    e.pending.guardian !== b.encounter.guardian
  )
    return m;
  b.settled = true;

  if (b.phase === "lost") {
    return m;
  }
  m.wins = Math.min(999999, m.wins + 1);
  m.rewards = Math.min(999999, m.rewards + 1);
  const bonus = b.encounter.risk ? 6 : 0;
  m.food = Math.min(999999, m.food + 12 + b.encounter.region * 4 + bonus);
  m.dew = Math.min(999999, m.dew + 8 + b.encounter.region * 3 + bonus);
  m.bait = Math.min(999999, m.bait + (b.encounter.guardian ? 2 : 1));
  for (const p of m.roster) {
    p.xp = Math.min(99999, p.xp + (m.team.includes(p.id) ? 18 : 6));
    if (p.level < 8 && p.xp >= p.level * 35) {
      p.xp -= p.level * 35;
      p.level++;
    }
  }
  if (b.captured) {
    collect(m, b.captured);
    m.captures = Math.min(999999, m.captures + 1);
  }
  if (b.encounter.guardian) {
    const first = !m.guardians[e.region];
    m.guardians[e.region] = true;
    m.stones = Math.min(999999, m.stones + (first ? 2 : 1));
    m.expedition = null;
  } else {
    e.pending = null;
    e.step++;
  }
  return m;
}
