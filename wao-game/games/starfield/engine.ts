import {
  advantage,
  counterElement,
  elementNames,
  elements,
  foes,
  gear,
  heroes,
  pets,
  quests,
  type Element,
  type HeroId,
  type Skill,
} from "./catalog";
import {
  emptyStatus,
  statusKeys,
  type Actor,
  type Battle,
  type EnemyMove,
  type StarfieldMemory,
  type Status,
} from "./model";
import { clone, level, settle, unlocked } from "./save";

export function actorName(a: Actor): string {
  return a.side === "party"
    ? heroes[a.kind as HeroId].name
    : foes[a.kind as keyof typeof foes].name;
}
export function currentActor(b: Battle): Actor {
  return b.actors.find((a) => a.id === b.queue[b.cursor])!;
}
export function living(b: Battle, side: Actor["side"]): Actor[] {
  return b.actors.filter((a) => a.side === side && a.hp > 0);
}
export function heroStats(memory: StarfieldMemory, id: HeroId) {
  const h = heroes[id],
    p = memory.heroes[id],
    lv = level(p.xp),
    items = gear.filter((g) => g.id === p.weapon || g.id === p.charm);
  const stat = (
    key: "attack" | "defense" | "speed" | "hp" | "mp",
    base: number,
    growth = 0,
  ) =>
    Math.max(
      key === "defense" ? 0 : 1,
      base + (lv - 1) * growth + items.reduce((v, g) => v + (g[key] ?? 0), 0),
    );
  return {
    maxHp: stat("hp", h.hp, 9),
    maxMp: stat("mp", h.mp, 2),
    attack: stat("attack", h.attack, 2),
    defense: stat("defense", h.defense, 1),
    speed: stat("speed", h.speed),
    effects: items.map((g) => g.effect),
  };
}
function log(b: Battle, text: string) {
  b.log.push(text);
  if (b.log.length > 12) b.log.shift();
}
function amount(a: Actor, key: "attack" | "speed") {
  return key === "speed"
    ? a.speed * (a.status.slow ? 0.55 : 1) * (a.status.haste ? 1.45 : 1)
    : a.attack;
}
function shield(a: Actor, value: number) {
  a.shield = Math.min(300, a.shield + Math.round(value));
}
function mana(a: Actor, value: number) {
  a.mp = Math.max(0, Math.min(a.maxMp, a.mp + Math.round(value)));
}
function status(b: Battle, a: Actor, key: Status, turns: number) {
  if (a.hp <= 0) return;
  if (
    a.status.ward > 0 &&
    ["burn", "slow", "sealed", "stun", "weak"].includes(key)
  ) {
    log(b, `${actorName(a)}的澄明护佑挡住了负面状态。`);
    return;
  }
  a.status[key] = Math.max(a.status[key], Math.min(4, turns));
}
function cleanse(a: Actor) {
  for (const key of ["burn", "slow", "sealed", "stun", "weak"] as const)
    a.status[key] = 0;
}
function heal(
  b: Battle,
  source: Actor,
  target: Actor,
  value: number,
  overflow = false,
) {
  if (target.hp <= 0) return;
  if (target.status.sealed) {
    log(b, `${actorName(target)}处于禁疗，治疗没有生效。`);
    return;
  }
  value = Math.round(value * (source.effects.includes("heal") ? 1.3 : 1));
  const actual = Math.min(target.maxHp - target.hp, value);
  target.hp += actual;
  if (overflow && value > actual) shield(target, (value - actual) * 0.65);
  log(
    b,
    `${actorName(target)}恢复${actual}生命${overflow && value > actual ? "，余量化为护盾" : ""}。`,
  );
}
function rawDamage(b: Battle, target: Actor, value: number, pierce = 0) {
  value = Math.max(1, Math.round(value));
  const piercing = Math.floor(value * pierce),
    blocked = Math.min(target.shield, value - piercing);
  target.shield -= blocked;
  const actual = Math.min(target.hp, value - blocked);
  target.hp -= actual;
  if (target.hp === 0) {
    target.shield = 0;
    log(b, `${actorName(target)}暂时退场。`);
  }
  return { actual, blocked };
}
function damage(
  b: Battle,
  source: Actor,
  target: Actor,
  power: number,
  element: Element,
  pierce = 0,
  credit = true,
) {
  if (source.hp <= 0 || target.hp <= 0) return;
  const ratio = advantage(element, target.element);
  let value = Math.max(2, source.attack * power - target.defense * 0.6) * ratio;
  if (target.status.weak) value *= 1.25;
  if (target.status.guard) value *= 0.45;
  if (target.kind === "orrery" && target.breakCount < 2) value *= 0.7;
  const result = rawDamage(
    b,
    target,
    value,
    Math.max(pierce, source.effects.includes("pierce") ? 0.4 : 0),
  );
  log(
    b,
    `${actorName(source)}→${actorName(target)} ${result.actual}${result.blocked ? `（盾挡${result.blocked}）` : ""}${ratio > 1 ? " · 克制" : ratio < 1 ? " · 抵抗" : ""}`,
  );
  if (credit && source.side === "party") {
    b.petCharge = Math.min(10, b.petCharge + (ratio > 1 ? 2 : 1));
    if (ratio > 1 && source.effects.includes("echo")) mana(source, 3);
    if (
      target.kind === "orrery" &&
      ratio > 1 &&
      target.hp > 0 &&
      target.breakCount < 2
    ) {
      target.breakCount++;
      if (target.breakCount === 2) {
        target.intent.move = "wait";
        status(b, target, "weak", 2);
        log(b, "两次克制形成破绽，星陨被打断！");
      }
    }
  }
  if (
    target.hp > 0 &&
    source.hp > 0 &&
    (target.status.counter || target.effects.includes("thorns"))
  ) {
    const reflected = rawDamage(
      b,
      source,
      target.attack * (target.status.counter ? 0.45 : 0.25),
    );
    log(b, `${actorName(target)}反击${reflected.actual}。`);
  }
}
function interrupt(b: Battle, target: Actor) {
  if (target.side !== "foe" || target.hp <= 0) return;
  if (target.kind === "orrery") {
    status(b, target, "slow", 2);
    log(b, "星轨仪抵抗直接打断，已被迟缓；需要两次克制形成破绽。");
    return;
  }
  if (["sundial", "librarian", "orrery"].includes(target.kind)) {
    if (["sunfall", "ink", "starfall"].includes(target.intent.move)) {
      target.intent.move = "wait";
      log(b, `${actorName(target)}的蓄力被打断。`);
    } else {
      status(b, target, "slow", 2);
      log(b, "首领抵抗眩晕，但速度降低。");
    }
  } else status(b, target, "stun", 1);
}
function targetForEnemy(b: Battle, enemy: Actor) {
  const party = living(b, "party");
  return (
    party.find((a) => a.status.taunt) ??
    [...party].sort(
      (a, c) => a.hp / a.maxHp - c.hp / c.maxHp || a.id.localeCompare(c.id),
    )[0] ??
    enemy
  );
}
function prepareIntents(b: Battle) {
  for (const e of living(b, "foe")) {
    let move: EnemyMove = "strike";
    const target = targetForEnemy(b, e);
    switch (e.kind) {
      case "scout":
        move = b.round % 2 === 0 ? "ambush" : "strike";
        break;
      case "bulwark":
        move = b.round % 2 === 1 ? "shield" : "smash";
        break;
      case "medic":
        move = living(b, "foe").some((a) => a.hp < a.maxHp * 0.7)
          ? "heal"
          : "seal";
        break;
      case "stinger":
        move = "scorch";
        break;
      case "chanter":
        move = "chant";
        break;
      case "drainer":
        move = "drain";
        break;
      case "sundial":
        move = b.round % 2 === 1 ? "counter" : "sunfall";
        e.status.counter = move === "counter" ? 1 : 0;
        break;
      case "librarian":
        move = b.round % 3 === 1 ? "seal" : b.round % 3 === 2 ? "ink" : "heal";
        break;
      case "orrery":
        e.element = elements[(b.round + 1) % 4]!;
        e.breakCount = 0;
        move = "starfall";
        break;
    }
    e.intent = { move, target: target.id };
  }
}
function outcome(b: Battle): boolean {
  if (!living(b, "foe").length) {
    b.phase = "won";
    log(b, "星路亮起来了，这次挑战成功！");
    return true;
  }
  if (!living(b, "party").length) {
    b.phase = "lost";
    log(b, "先回营地歇一歇。已经获得的成长保留。");
    return true;
  }
  return false;
}
function newRound(b: Battle) {
  if (b.round >= 100) {
    b.cursor = 0;
    b.phase = "lost";
    log(b, "星光暂时耗尽，回营地调整策略后再来。");
    return;
  }
  b.round++;
  b.petWard = false;
  for (const a of b.actors) {
    for (const key of statusKeys) if (a.status[key] > 0) a.status[key]--;
  }
  prepareIntents(b);
  b.queue = [...b.actors]
    .sort(
      (a, c) =>
        amount(c, "speed") - amount(a, "speed") || a.id.localeCompare(c.id),
    )
    .map((a) => a.id);
  b.cursor = 0;
  log(b, `第${b.round}轮 · 行动顺序已更新。`);
}
/** Select the next live actor and apply start-of-turn effects exactly once. */
function seek(b: Battle) {
  for (let safety = 0; safety < 100; safety++) {
    if (outcome(b)) return;
    if (b.cursor >= b.queue.length) {
      newRound(b);
      if (b.phase === "lost") return;
    }
    const a = currentActor(b);
    if (a.hp <= 0) {
      b.cursor++;
      continue;
    }
    if (a.status.burn) {
      rawDamage(b, a, Math.max(4, Math.round(a.maxHp * 0.06)), 1);
      log(b, `${actorName(a)}受到灼烧。`);
    }
    if (a.hp > 0 && a.status.regen) heal(b, a, a, Math.round(a.maxHp * 0.08));
    if (outcome(b)) return;
    if (a.hp <= 0) {
      b.cursor++;
      continue;
    }
    if (a.status.stun) {
      a.status.stun = 0;
      log(b, `${actorName(a)}被控制，本次行动取消。`);
      b.cursor++;
      continue;
    }
    b.phase = a.side === "party" ? "player" : "enemy";
    return;
  }
  throw new Error("行动队列异常，请导出记忆后重试。");
}
function endAction(b: Battle) {
  b.actions++;
  if (outcome(b)) return;
  b.cursor++;
  seek(b);
}
export function startBattle(
  memory: StarfieldMemory,
  questId: number,
  choice: "aid" | "bold",
  seed: number,
): StarfieldMemory {
  if (memory.battle && ["player", "enemy"].includes(memory.battle.phase))
    throw new Error("请先返回营地结束当前遭遇。");
  if (!unlocked(memory, questId) || !memory.accepted[questId])
    throw new Error("先在星图接取已经开放的任务。");
  const m = clone(memory),
    q = quests[questId]!,
    route = q.choices.find((c) => c.id === choice)!;
  const actors: Actor[] = m.party.map((id, i) => {
    const stats = heroStats(m, id);
    return {
      id: `p${i}`,
      side: "party",
      kind: id,
      element: heroes[id].element,
      ...stats,
      hp: stats.maxHp,
      mp: Math.round(stats.maxMp * 0.7),
      shield: 0,
      status: emptyStatus(),
      branches: [...m.heroes[id].branches] as [number, number],
      intent: { move: "wait", target: "p0" },
      breakCount: 0,
    };
  });
  const scale = 1 + q.chapter * 0.18 + (choice === "bold" ? 0.18 : 0);
  q.foes.forEach((id, i) => {
    const f = foes[id],
      hp = Math.round(
        f.hp * (q.boss && i === 0 ? 1 + (choice === "bold" ? 0.12 : 0) : scale),
      );
    actors.push({
      id: `e${i}`,
      side: "foe",
      kind: id,
      element: f.element,
      hp,
      maxHp: hp,
      mp: 0,
      maxMp: 0,
      attack: Math.round(f.attack * scale),
      defense: f.defense + q.chapter,
      speed: f.speed + q.chapter,
      shield: 0,
      status: emptyStatus(),
      branches: [0, 0],
      effects: [],
      intent: { move: "strike", target: "p0" },
      breakCount: 0,
    });
  });
  for (const a of actors.filter((a) => a.side === "party")) {
    if (route.bonus === "shield" && choice === "aid")
      shield(a, 18 + q.chapter * 5);
    if (route.bonus === "mana") a.mp = a.maxMp;
    if (route.bonus === "clean" && choice === "aid") a.status.ward = 1;
  }
  m.battle = {
    quest: questId,
    choice,
    attempt: m.nextAttempt++,
    seed: seed >>> 0,
    round: 1,
    actions: 0,
    actors,
    queue: [],
    cursor: 0,
    phase: "player",
    pet: m.pet,
    petCharge: actors.filter((a) => a.effects.includes("pet")).length,
    petUsedRound: 0,
    petWard: false,
    log: [`${q.name} · ${route.name}`],
  };
  const b = m.battle;
  prepareIntents(b);
  b.queue = [...actors]
    .sort(
      (a, c) =>
        amount(c, "speed") - amount(a, "speed") || a.id.localeCompare(c.id),
    )
    .map((a) => a.id);
  seek(b);
  m.lastResult = null;
  return m;
}
export function legalTargets(b: Battle, source: Actor, skill: Skill): Actor[] {
  if (skill.target === "self") return [source];
  const side =
    skill.target === "enemy" || skill.target === "all-enemies"
      ? "foe"
      : "party";
  return living(b, side).filter(
    (a) => skill.id !== "transfer" || a.id !== source.id,
  );
}
export function act(
  memory: StarfieldMemory,
  skillId: string,
  targetId: string,
): StarfieldMemory {
  const m = clone(memory),
    b = m.battle;
  if (!b || b.phase !== "player") throw new Error("现在不是伙伴的行动。");
  const source = currentActor(b),
    definition = heroes[source.kind as HeroId];
  if (skillId === "defend") {
    status(b, source, "guard", 2);
    mana(source, 8 + (source.effects.includes("battery") ? 6 : 0));
    if (source.effects.includes("guard")) shield(source, 12);
    if (source.effects.includes("clean")) cleanse(source);
    log(b, `${definition.name}防御并恢复资源。`);
    endAction(b);
    return settle(m);
  }
  const skill = definition.skills.find((s) => s.id === skillId);
  if (!skill || source.mp < skill.cost)
    throw new Error("资源不足或技能不可用。");
  const legal = legalTargets(b, source, skill),
    target = legal.find((a) => a.id === targetId);
  if (!target) throw new Error("请选择合法目标。");
  const targets = skill.target.startsWith("all-") ? legal : [target];
  source.mp -= skill.cost;
  const [aRank, bRank] = source.branches;
  log(b, `${definition.name}使用${skill.name}。`);
  const hit = (t: Actor, p = 1, element = skill.element, pierce = 0) =>
    damage(b, source, t, p, element, pierce);
  switch (skill.id) {
    case "spark":
      hit(target);
      if (aRank) {
        status(b, target, "burn", 2 + aRank);
        const next = living(b, "foe").find((a) => a.id !== target.id);
        if (next) hit(next, 0.25 + 0.1 * aRank);
      }
      break;
    case "sunburst":
      for (const t of targets) {
        const burning = t.status.burn;
        hit(t, 0.9 + 0.08 * aRank);
        status(b, t, "burn", 2 + (aRank ? 1 : 0));
        if (burning && aRank) mana(source, 2 + aRank);
      }
      break;
    case "breakline":
      hit(target, 1.2, "storm", 0.5 + 0.12 * bRank);
      status(b, target, "weak", 2);
      if (bRank) shield(source, 5 * bRank);
      break;
    case "returnblade":
      hit(target, 0.7);
      hit(target, 0.7);
      if (bRank) {
        mana(source, 2 + 2 * bRank);
        shield(source, 4 * bRank);
      }
      break;
    case "ripple":
      hit(target, 0.8);
      mana(source, 3);
      break;
    case "mend":
      if (aRank) target.status.sealed = 0;
      heal(b, source, target, 30 + source.attack * 0.7 + bRank * 4, bRank > 0);
      break;
    case "cleanse":
      cleanse(target);
      heal(b, source, target, 16 + source.attack * 0.4);
      if (aRank) {
        mana(target, 2 * aRank);
        const friend = living(b, "party").find(
          (a) =>
            a.id !== target.id &&
            statusKeys.some(
              (k) =>
                ["burn", "slow", "sealed", "stun"].includes(k) && a.status[k],
            ),
        );
        if (friend) cleanse(friend);
      }
      break;
    case "rain":
      for (const t of targets) {
        heal(b, source, t, 20 + source.attack * 0.55 + bRank * 3, bRank > 0);
        if (bRank) status(b, t, "regen", 2);
      }
      break;
    case "leafshot":
      hit(target);
      if (target.status.slow) mana(source, 4);
      break;
    case "bind":
      hit(target, 0.9);
      status(b, target, "slow", 3);
      if (aRank) status(b, target, "weak", 1 + aRank);
      break;
    case "interrupt":
      hit(target, 0.7);
      interrupt(b, target);
      if (aRank) {
        const next = living(b, "foe").find((a) => a.id !== target.id);
        if (next) hit(next, 0.25 * aRank);
      }
      break;
    case "delay": {
      hit(target, 0.8);
      const at = b.queue.indexOf(target.id);
      if (at > b.cursor) {
        b.queue.splice(at, 1);
        b.queue.push(target.id);
        log(b, `${actorName(target)}被移至本轮队尾。`);
      }
      if (bRank) {
        for (const friend of living(b, "party")) status(b, friend, "haste", 2);
        mana(source, 2 * bRank);
        shield(source, 4 * bRank);
      }
      break;
    }
    case "zap":
      hit(target, target.status.weak ? 1.4 + 0.1 * aRank : 1);
      break;
    case "chain":
      for (const t of targets) {
        hit(t, 0.9);
        if (t.status.weak && aRank) hit(t, 0.2 * aRank);
      }
      break;
    case "sidestep":
      hit(target, 1.1);
      status(b, source, "haste", 2);
      if (bRank) shield(source, 8 + 5 * bRank);
      break;
    case "drainblade":
      hit(target, 0.9);
      mana(source, 7);
      if (bRank) {
        const friend = living(b, "party")
          .filter((a) => a.id !== source.id)
          .sort((a, c) => a.mp / a.maxMp - c.mp / c.maxMp)[0];
        if (friend) mana(friend, 3 * bRank);
      }
      break;
    case "glint":
      hit(target, 0.85);
      b.petCharge = Math.min(10, b.petCharge + 1);
      break;
    case "barrier":
      shield(target, 22 + source.attack * 0.6 + 5 * bRank);
      if (bRank) status(b, target, "counter", 2);
      break;
    case "transfer":
      mana(target, 16 + 2 * aRank);
      status(b, target, "haste", 2);
      if (aRank) {
        const friend = living(b, "party").filter(
          (a) => a.id !== target.id && a.id !== source.id,
        )[0];
        if (friend) mana(friend, 3 * aRank);
      }
      break;
    case "chorus":
      for (const friend of targets) {
        shield(friend, 15 + source.attack * 0.5 + 4 * bRank);
        if (aRank) status(b, friend, "haste", 2);
        if (bRank) friend.status.sealed = 0;
      }
      break;
    case "thump":
      hit(target);
      shield(source, 6);
      break;
    case "taunt":
      status(b, source, "taunt", 2);
      status(b, source, "guard", 2);
      shield(source, 12);
      if (aRank) status(b, source, "counter", 2);
      break;
    case "stomp":
      for (const t of targets) {
        hit(t, 0.7 + 0.06 * aRank);
        status(b, t, "slow", 2);
        if (
          aRank &&
          ["chant", "sunfall", "ink", "starfall"].includes(t.intent.move)
        )
          interrupt(b, t);
      }
      break;
    case "bastion":
      if (bRank) cleanse(target);
      heal(b, source, target, 22 + source.attack * 0.5);
      shield(target, 18 + 3 * bRank);
      if (bRank)
        for (const friend of living(b, "party"))
          if (friend.id !== target.id) shield(friend, 5 * bRank);
      break;
  }
  if (
    source.effects.includes("burn") &&
    skill.cost === 0 &&
    target.side === "foe"
  )
    status(b, target, "burn", 2);
  endAction(b);
  return settle(m);
}
export function usePet(
  memory: StarfieldMemory,
  targetId: string,
): StarfieldMemory {
  const m = clone(memory),
    b = m.battle;
  if (
    !b ||
    b.phase !== "player" ||
    b.petUsedRound === b.round ||
    b.petCharge < pets[b.pet].cost
  )
    throw new Error("协战能量不足，或本轮已经协战。");
  const source = currentActor(b);
  if (b.pet === "bird") {
    const target = living(b, "foe").find((a) => a.id === targetId);
    if (!target) throw new Error("请选择协战目标。");
    damage(b, source, target, 1.2, counterElement(target.element), 0.25, false);
    if (target.kind === "orrery" && target.hp > 0 && target.breakCount < 2) {
      target.breakCount++;
      if (target.breakCount === 2) {
        target.intent.move = "wait";
        status(b, target, "weak", 2);
        log(b, "晨灯鸟补齐克制，星陨被打断！");
      }
    }
  } else if (b.pet === "droplet") {
    const target = [...living(b, "party")].sort(
      (a, c) => a.hp / a.maxHp - c.hp / c.maxHp,
    )[0]!;
    cleanse(target);
    heal(b, source, target, 38);
    for (const friend of living(b, "party")) mana(friend, 4);
  } else {
    for (const friend of living(b, "party")) shield(friend, 20);
    b.petWard = true;
  }
  b.petCharge -= pets[b.pet].cost;
  b.petUsedRound = b.round;
  b.actions++;
  log(b, `${pets[b.pet].name}完成协战，不消耗伙伴行动。`);
  outcome(b);
  return settle(m);
}
export function enemyAct(memory: StarfieldMemory): StarfieldMemory {
  const m = clone(memory),
    b = m.battle;
  if (!b || b.phase !== "enemy") return m;
  const source = currentActor(b),
    move = source.intent.move;
  const target =
    living(b, "party").find((a) => a.status.taunt) ??
    living(b, "party").find((a) => a.id === source.intent.target) ??
    targetForEnemy(b, source);
  const all = living(b, "party");
  const hit = (t: Actor, p = 1, element = source.element) =>
    damage(b, source, t, p, element, 0, false);
  log(b, `${actorName(source)}：${intentNames[move]}。`);
  switch (move) {
    case "strike":
      hit(target);
      break;
    case "ambush":
      hit(target, 1.25);
      status(b, target, "weak", 2);
      break;
    case "shield":
      shield(source, 26);
      status(b, source, "counter", 2);
      break;
    case "smash":
      hit(target, 1.6);
      break;
    case "heal": {
      const friend = [...living(b, "foe")].sort(
        (a, c) => a.hp / a.maxHp - c.hp / c.maxHp,
      )[0]!;
      heal(b, source, friend, source.kind === "librarian" ? 40 : 26);
      break;
    }
    case "seal":
      if (source.kind === "librarian") {
        for (const friend of all) status(b, friend, "sealed", 3);
      } else {
        hit(target, 0.5);
        status(b, target, "sealed", 3);
      }
      break;
    case "scorch":
      hit(target, 0.85);
      status(b, target, "burn", 3);
      break;
    case "chant":
    case "sunfall":
    case "ink":
    case "starfall": {
      const power = move === "starfall" ? 1.6 : move === "sunfall" ? 1.45 : 1.1;
      for (const friend of all) {
        hit(friend, power * (b.petWard ? 0.6 : 1));
        if (move === "ink") status(b, friend, "slow", 2);
      }
      b.petWard = false;
      break;
    }
    case "drain":
      hit(target, 0.8);
      mana(target, -10);
      break;
    case "counter":
      shield(source, 12);
      log(b, "日晷摆出反击姿态。可以治疗、蓄能或用护盾应对。");
      break;
    case "wait":
      log(b, "攻击已被打断，敌人正在重新站稳。");
      break;
  }
  endAction(b);
  return settle(m);
}
export const intentNames: Record<EnemyMove, string> = {
  strike: "单体攻击",
  ambush: "突袭 + 易伤",
  shield: "张盾 + 反击",
  smash: "蓄力重击",
  heal: "治疗受伤同伴",
  seal: "施加禁疗",
  scorch: "灼羽 + 持续伤害",
  chant: "蓄力群攻 · 可打断",
  drain: "吸取资源",
  counter: "反击姿态",
  sunfall: "日轮群攻 · 可打断",
  ink: "墨潮群攻 + 迟缓",
  starfall: "星陨 · 两次克制可打断",
  wait: "蓄力已中断",
};
export function intentText(b: Battle, a: Actor): string {
  const target = b.actors.find((t) => t.id === a.intent.target);
  return `${intentNames[a.intent.move]}${["strike", "ambush", "smash", "scorch", "drain"].includes(a.intent.move) && target ? ` → ${actorName(target)}` : ""}${a.kind === "orrery" ? ` · ${elementNames[a.element]}属性 · 破绽${a.breakCount}/2` : ""}`;
}
