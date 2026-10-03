import { describe, expect, it } from "vitest";
import {
  advantage,
  gear,
  heroIds,
  heroes,
  quests,
  type HeroId,
} from "./catalog";
import {
  act,
  currentActor,
  enemyAct,
  heroStats,
  legalTargets,
  living,
  startBattle,
  usePet,
} from "./engine";
import {
  clone,
  freshStarfieldMemory,
  isStarfieldMemory,
  level,
  settle,
} from "./save";
import type { Actor, StarfieldMemory } from "./model";

function fixture(
  quest = 0,
  party: StarfieldMemory["party"] = ["sol", "dew", "fern"],
) {
  const m = freshStarfieldMemory();
  m.party = party;
  for (let i = 0; i < quest; i++) {
    m.completed[i] = true;
    m.accepted[i] = true;
    m.records[i] = { wins: 1, bestRounds: 3 };
  }
  m.accepted[quest] = true;
  return startBattle(m, quest, "aid", 12);
}
function actorTurn(m: StarfieldMemory, id: HeroId): Actor {
  const b = m.battle!,
    source = b.actors.find((a) => a.kind === id)!;
  b.queue = [source.id, ...b.queue.filter((x) => x !== source.id)];
  b.cursor = 0;
  b.phase = "player";
  return source;
}

describe("四属性与真正影响结果的队伍行动", () => {
  it("四属性闭环、装备注重取舍，队伍只能三名且保持全快照校验", () => {
    expect(advantage("ember", "grove")).toBe(1.4);
    expect(advantage("grove", "ember")).toBe(0.7);
    expect(advantage("ember", "tide")).toBe(0.7);
    const m = fixture();
    expect(isStarfieldMemory(m)).toBe(true);
    const normal = heroStats(m, "sol");
    m.heroes.sol.weapon = "sun-sabre";
    m.owned.push("sun-sabre");
    const heavy = heroStats(m, "sol");
    expect(heavy.attack).toBeGreaterThan(normal.attack);
    expect(heavy.speed).toBeLessThan(normal.speed);
    expect(gear).toHaveLength(12);
    expect(
      heroIds.every(
        (id) =>
          heroes[id].skills.length === 4 && heroes[id].branches.length === 2,
      ),
    ).toBe(true);
  });
  it("选择非法目标或资源不足不能消耗原存档；延迟改变当轮队列", () => {
    const m = fixture(),
      source = actorTurn(m, "fern"),
      b = m.battle!,
      enemy = living(b, "foe")[0]!;
    const original = clone(m);
    expect(() => act(m, "bind", source.id)).toThrow("合法目标");
    expect(m).toEqual(original);
    source.mp = 0;
    expect(() => act(m, "bind", enemy.id)).toThrow("资源不足");
    source.mp = 30;
    const result = act(m, "delay", enemy.id);
    expect(result.battle!.queue.at(-1)).toBe(enemy.id);
    expect(m.battle!.queue.at(-1)).not.toBe(enemy.id);
  });
  it("禁疗挡住治疗；净化/成长分支解除禁疗并恢复生命，溢出转盾", () => {
    const m = fixture();
    actorTurn(m, "dew");
    const friend = m.battle!.actors.find((a) => a.kind === "sol")!;
    friend.hp = 20;
    friend.status.sealed = 3;
    const denied = act(m, "mend", friend.id);
    expect(denied.battle!.actors.find((a) => a.id === friend.id)!.hp).toBe(20);
    const clean = act(m, "cleanse", friend.id);
    expect(
      clean.battle!.actors.find((a) => a.id === friend.id)!.status.sealed,
    ).toBe(0);
    expect(
      clean.battle!.actors.find((a) => a.id === friend.id)!.hp,
    ).toBeGreaterThan(20);
    const healer = currentActor(m.battle!);
    healer.branches = [1, 1];
    friend.hp = friend.maxHp - 1;
    const branched = act(m, "mend", friend.id).battle!.actors.find(
      (a) => a.id === friend.id,
    )!;
    expect(branched.hp).toBe(friend.maxHp);
    expect(branched.shield).toBeGreaterThan(friend.shield);
  });
  it("普通眩晕取消意图，首领蓄力打断；首领非蓄力时只能迟缓", () => {
    let m = fixture();
    actorTurn(m, "fern");
    const enemy = living(m.battle!, "foe")[0]!;
    m = act(m, "interrupt", enemy.id);
    expect(m.battle!.actors.find((a) => a.id === enemy.id)!.status.stun).toBe(
      1,
    );
    m = enemyAct(m);
    expect(m.battle!.log.some((x) => x.includes("行动取消"))).toBe(true);
    const boss = fixture(2);
    actorTurn(boss, "fern");
    const target = living(boss.battle!, "foe")[0]!;
    target.intent.move = "sunfall";
    expect(
      act(boss, "interrupt", target.id).battle!.actors.find(
        (a) => a.id === target.id,
      )!.intent.move,
    ).toBe("wait");
    target.intent.move = "counter";
    const slowed = act(boss, "interrupt", target.id).battle!.actors.find(
      (a) => a.id === target.id,
    )!;
    expect(slowed.status.slow).toBeGreaterThan(0);
    expect(slowed.status.stun).toBe(0);
  });
  it("晨灯鸟补足克制破绽；协战不消耗当前角色行动且一轮只能一次", () => {
    const m = fixture(8);
    actorTurn(m, "sol");
    const b = m.battle!,
      boss = living(b, "foe")[0]!;
    boss.element = "tide";
    boss.breakCount = 1;
    b.petCharge = 10;
    const n = usePet(m, boss.id);
    expect(n.battle!.actors.find((a) => a.id === boss.id)!.intent.move).toBe(
      "wait",
    );
    expect(n.battle!.cursor).toBe(b.cursor);
    expect(n.battle!.petCharge).toBe(6);
    expect(() => usePet(n, boss.id)).toThrow("已经协战");
    const controlled = fixture(8);
    actorTurn(controlled, "fern");
    const resisted = act(controlled, "interrupt", "e0").battle!.actors.find(
      (a) => a.id === "e0",
    )!;
    expect(resisted.intent.move).toBe("starfall");
    expect(resisted.status.slow).toBeGreaterThan(0);
  });
  it("露团先解除禁疗再治疗；磐角保护实际降低群攻伤害", () => {
    const m = fixture(3);
    actorTurn(m, "sol");
    m.pet = "droplet";
    m.battle!.pet = "droplet";
    m.battle!.petCharge = 10;
    const hero = living(m.battle!, "party")[0]!;
    hero.hp = 5;
    hero.status.sealed = 3;
    const healed = usePet(m, "p0");
    expect(healed.battle!.actors[0]!.hp).toBeGreaterThan(5);
    expect(healed.battle!.actors[0]!.status.sealed).toBe(0);
    m.pet = "horn";
    m.battle!.pet = "horn";
    const shielded = usePet(m, "p0");
    expect(shielded.battle!.petWard).toBe(true);
    expect(shielded.battle!.actors[0]!.shield).toBeGreaterThan(hero.shield);
  });
  it("新轮次重新按迟缓/加速排序，死亡队员不获得行动", () => {
    let m = fixture();
    const b = m.battle!;
    const fern = b.actors.find((a) => a.kind === "fern")!,
      scout = living(b, "foe")[0]!;
    fern.status.slow = 3;
    fern.hp = 1;
    fern.status.burn = 3;
    const last = b.queue.at(-1)!;
    b.cursor = b.queue.length - 1;
    b.phase = "enemy";
    b.actors.find((a) => a.id === last)!.intent.move = "wait";
    m = enemyAct(m);
    expect(m.battle!.round).toBe(2);
    expect(m.battle!.queue.indexOf(fern.id)).toBeGreaterThan(
      m.battle!.queue.indexOf(scout.id),
    );
    for (let i = 0; i < 10 && m.battle!.round === 2; i++)
      m =
        m.battle!.phase === "enemy"
          ? enemyAct(m)
          : act(m, "defend", currentActor(m.battle!).id);
    expect(m.battle!.actors.find((a) => a.id === fern.id)!.hp).toBe(0);
    expect(isStarfieldMemory(m)).toBe(true);
  });
  it("奖励幂等，刷新不能重复获得掉落与经验；坏队列/未知状态/越界引用拒绝", () => {
    const m = fixture();
    for (const e of living(m.battle!, "foe")) e.hp = 0;
    m.battle!.phase = "won";
    const won = settle(m);
    expect(won.sand).toBe(57);
    expect(won.completed[0]).toBe(true);
    expect(settle(won)).toEqual(won);
    expect(isStarfieldMemory(won)).toBe(true);
    const bad = clone(won);
    bad.battle!.queue[0] = "p9";
    expect(isStarfieldMemory(bad)).toBe(false);
    const duplicate = clone(won);
    duplicate.party[1] = duplicate.party[0];
    expect(isStarfieldMemory(duplicate)).toBe(false);
    const deep = clone(won);
    Object.assign(deep.battle!.actors[0]!.status, { script: "<script>" });
    expect(isStarfieldMemory(deep)).toBe(false);
  });
});

/** Explicit automated balance policy; never reported as manual browser acceptance. */
function play(memory: StarfieldMemory): StarfieldMemory {
  let m = memory;
  for (let step = 0; step < 400; step++) {
    const b = m.battle!;
    expect(isStarfieldMemory(m), `round ${b.round}: ${b.log.join(" / ")}`).toBe(
      true,
    );
    if (["won", "lost"].includes(b.phase)) return m;
    if (b.phase === "enemy") {
      m = enemyAct(m);
      continue;
    }
    const hero = currentActor(b),
      enemies = living(b, "foe"),
      friends = living(b, "party");
    if (b.petCharge >= 4 && b.petUsedRound !== b.round) {
      m = usePet(
        m,
        enemies.find((a) => a.kind === "orrery")?.id ?? enemies[0]!.id,
      );
      continue;
    }
    const hurt = [...friends].sort(
      (a, c) => a.hp / a.maxHp - c.hp / c.maxHp,
    )[0]!;
    const skills = heroes[hero.kind as HeroId].skills.filter(
      (s) => s.cost <= hero.mp,
    );
    if (hero.kind === "dew" && hurt.hp < hurt.maxHp * 0.65) {
      const skill = skills.find(
        (s) => s.id === (hurt.status.sealed ? "cleanse" : "mend"),
      );
      if (skill) {
        m = act(m, skill.id, hurt.id);
        continue;
      }
    }
    const charged = enemies.find(
      (a) =>
        a.kind !== "orrery" &&
        ["sunfall", "ink", "starfall", "chant"].includes(a.intent.move) &&
        b.queue.indexOf(a.id) > b.cursor,
    );
    if (hero.kind === "fern" && charged && hero.mp >= 12) {
      m = act(m, "interrupt", charged.id);
      continue;
    }
    const target =
      enemies.find(
        (a) =>
          a.kind === "orrery" &&
          a.breakCount < 2 &&
          b.queue.indexOf(a.id) > b.cursor,
      ) ??
      enemies.find((a) => a.kind === "medic") ??
      enemies.find((a) => a.kind === "chanter") ??
      enemies[0]!;
    const attacks = skills.filter(
      (s) => s.target === "enemy" || s.target === "all-enemies",
    );
    attacks.sort(
      (a, c) =>
        advantage(c.element, target.element) * (c.cost > 0 ? 1.25 : 1) -
        advantage(a.element, target.element) * (a.cost > 0 ? 1.25 : 1),
    );
    const skill = attacks[0];
    m = skill
      ? act(
          m,
          skill.id,
          legalTargets(b, hero, skill).find((a) => a.id === target.id)!.id,
        )
      : act(m, "defend", hero.id);
  }
  throw new Error("Automated battle did not terminate");
}
it("自动策略模拟九任务成长闭环与所有行动快照可恢复（不是浏览器手操）", () => {
  let m = freshStarfieldMemory();
  for (const q of quests) {
    m.battle = null;
    m.accepted[q.id] = true;
    if (q.id === 8) {
      // Spend earned resources to let both damage and healing act before the final boss.
      if (!m.owned.includes("wind-ribbon")) {
        m.sand -= 25;
        m.owned.push("wind-ribbon");
      }
      for (const id of m.party) m.heroes[id].charm = "wind-ribbon";
    }
    for (const id of heroIds) {
      const h = m.heroes[id];
      h.branches = [
        Math.min(3, level(h.xp) - 1),
        Math.min(3, Math.max(0, level(h.xp) - 4)),
      ];
    }
    m = play(startBattle(m, q.id, "aid", q.id + 1));
    expect(m.battle!.phase, `quest ${q.id}: ${m.battle!.log.join(" / ")}`).toBe(
      "won",
    );
    expect(isStarfieldMemory(m), `save ${q.id}`).toBe(true);
  }
  expect(m.completed.every(Boolean)).toBe(true);
  expect(m.owned.length).toBeGreaterThan(6);
  expect(level(m.heroes.sol.xp)).toBeGreaterThan(3);
});

it("六角色全部技能在两种分支配置下消耗一次行动并产生合法可恢复状态", () => {
  for (const id of heroIds)
    for (const branches of [
      [0, 0],
      [3, 3],
    ] as const) {
      for (const skill of heroes[id].skills) {
        const others = heroIds.filter((x) => x !== id).slice(0, 2);
        const m = fixture(3, [id, others[0]!, others[1]!]);
        for (const h of heroIds) m.heroes[h].xp = 1120;
        const source = actorTurn(m, id);
        m.heroes[id].branches = [...branches];
        source.branches = [...branches];
        source.mp = source.maxMp;
        const friend = living(m.battle!, "party")[1]!;
        friend.hp = Math.floor(friend.maxHp / 3);
        friend.status.sealed = 2;
        const target = legalTargets(m.battle!, source, skill)[0]!;
        const result = act(m, skill.id, target.id);
        expect(result.battle!.actions, skill.id).toBe(m.battle!.actions + 1);
        expect(
          result.battle!.actors.find((a) => a.id === source.id)!.mp,
          skill.id,
        ).toBeLessThanOrEqual(source.maxMp);
        expect(isStarfieldMemory(result), `${id}/${skill.id}/${branches}`).toBe(
          true,
        );
        expect(result.battle!.log.join(" ")).toContain(skill.name);
      }
    }
});

it("护卫嘲讽接住下一次单体攻击，游击与支援队可通过首章", () => {
  let m = fixture(0, ["root", "bolt", "opal"]);
  const root = actorTurn(m, "root");
  m = act(m, "taunt", root.id);
  const b = m.battle!,
    enemy = living(b, "foe")[0]!;
  b.queue = [enemy.id, ...b.queue.filter((x) => x !== enemy.id)];
  b.cursor = 0;
  b.phase = "enemy";
  const hp = living(b, "party")
    .filter((x) => x.kind !== "root")
    .map((a) => a.hp);
  m = enemyAct(m);
  expect(
    living(m.battle!, "party")
      .filter((x) => x.kind !== "root")
      .map((a) => a.hp),
  ).toEqual(hp);
  expect(m.battle!.log.join(" ")).toContain("岩芽");
  m = freshStarfieldMemory();
  m.party = ["bolt", "opal", "root"];
  for (const q of quests.slice(0, 3)) {
    m.battle = null;
    m.accepted[q.id] = true;
    m = play(startBattle(m, q.id, "aid", 13));
    expect(m.battle!.phase, `alternate party quest ${q.id}`).toBe("won");
  }
});
