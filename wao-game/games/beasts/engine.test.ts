import { describe, it, expect } from "vitest";
import { beastIds, regions, species, type BeastId } from "./catalog";
import {
  activeBonds,
  advance,
  capture,
  captureChance,
  living,
  order,
  setStance,
  settle,
  startBattle,
} from "./engine";
import {
  beginExpedition,
  chooseRoute,
  claimGoal,
  clone,
  collect,
  evolve,
  freshBeastMemory,
  grow,
  hatch,
  isBeastMemory,
  train,
} from "./save";
import type { Battle } from "./model";
function encounter(
  region = 0,
  guardian = false,
  ids: BeastId[] = ["moss", "sprig", "fin"],
  level = 1,
) {
  let m = freshBeastMemory();
  m.guardians = m.guardians.map((_, i) => i < region);
  for (const id of ids) collect(m, id);
  m.team = ids;
  m.roster.forEach((p) => {
    p.level = level;
    p.rank = Math.min(3, Math.floor(level / 2));
  });
  m = beginExpedition(m, region);
  if (guardian) m.expedition!.step = 3;
  m = chooseRoute(m, guardian ? "guardian" : "trail");
  return { m, b: startBattle(m)! };
}
function run(b: Battle, seconds: number, commands = false) {
  for (let i = 0; i < seconds * 10 && b.phase === "running"; i++) {
    if (commands) {
      const charging = living(b, "wild").find((a) => a.cast > 0);
      if (charging) {
        b.target = charging.id;
        order(b, "hush");
        setStance(b, charging.cast > 0 ? "guard" : "assault");
      } else setStanceSilent(b);
      const helper = living(b, "wild").find((a) => a.kind === "healer");
      if (helper) b.target = helper.id;
      if (living(b, "party").some((a) => a.hp < a.maxHp * 0.45))
        order(b, "rally");
    }
    advance(b, 0.1);
  }
  return b;
}
function setStanceSilent(b: Battle) {
  b.stance = "assault";
}
describe("幻兽领地路线与记忆", () => {
  it("初始合法，锁区不可进入，分叉节点补给与保底可以恢复", () => {
    let m = freshBeastMemory();
    expect(isBeastMemory(m)).toBe(true);
    expect(beginExpedition(m, 1)).toEqual(m);
    m = beginExpedition(m, 0);
    m = chooseRoute(m, "track");
    const b = startBattle(m)!;
    expect(m.traces[0]).toBe(1);
    b.phase = "won";
    m = settle(m, b);
    m = chooseRoute(m, "nest");
    expect(m.eggs).toHaveLength(1);
    expect(m.expedition!.step).toBe(2);
    m = chooseRoute(m, "track");
    expect(m.expedition!.pending!.wild).toContain("moth");
    expect(m.traces[0]).toBe(0);
    expect(m.expedition!.supplies).toBe(1);
    expect(startBattle(clone(m))).toEqual(startBattle(m));
    expect(isBeastMemory(m)).toBe(true);
  });
  it("十二种均可通过正常区域路线遇见，稀有不依赖抽取", () => {
    const reached = new Set<BeastId>();
    for (let region = 0; region < 4; region++) {
      let m = freshBeastMemory();
      m.guardians = m.guardians.map(() => true);
      for (let trip = 0; trip < 10; trip++) {
        m = beginExpedition(m, region);
        for (let step = 0; step < 3; step++) {
          m = chooseRoute(m, "track");
          m.expedition!.pending!.wild.forEach((id) => reached.add(id));
          const b = startBattle(m)!;
          b.phase = "won";
          m = settle(m, b);
        }
      }
    }
    expect([...reached].sort()).toEqual([...beastIds].sort());
  });
  it("巢穴有条件孵化和资源即时孵化，重复转资源", () => {
    let m = freshBeastMemory();
    m.eggs = [{ id: "otter", winsAt: 0 }];
    expect(hatch(m, 0, false)).toEqual(m);
    m = hatch(m, 0, true);
    expect(m.dew).toBe(8);
    expect(m.roster.some((p) => p.id === "otter")).toBe(true);
    m.eggs = [{ id: "otter", winsAt: 0 }];
    m.wins = 2;
    m = hatch(m, 0, false);
    expect(m.eggs).toHaveLength(0);
    expect(m.dew).toBe(20);
    expect(m.roster.filter((p) => p.id === "otter")).toHaveLength(1);
  });
  it("每兽两方向、六种进化，成长资源与等级上限实际约束", () => {
    expect(beastIds.filter((id) => species[id].evolution)).toHaveLength(6);
    for (const id of beastIds) {
      let m = freshBeastMemory();
      collect(m, id);
      m.food = m.dew = 500;
      m.stones = 10;
      const p = m.roster.find((p) => p.id === id)!;
      expect(grow(m, id)).toEqual(m);
      m = train(m, id);
      m = grow(m, id);
      expect(m.roster.find((p) => p.id === id)!.rank).toBe(1);
      m = train(m, id);
      m = evolve(m, id);
      expect(m.roster.find((p) => p.id === id)!.evolved).toBe(
        !!species[id].evolution,
      );
      expect(species[id].branches).toHaveLength(2);
      for (let i = 0; i < 15; i++) m = train(m, id);
      expect(m.roster.find((p) => p.id === id)!.level).toBe(8);
      expect(isBeastMemory(m)).toBe(true);
      expect(p.level).toBe(1);
    }
  });
  it("拒绝未知字段、越界成长、无效引用、非法区域和伪造进化", () => {
    const base = freshBeastMemory();
    for (const mutate of [
      (m: any) => (m.html = "<script>"),
      (m: any) => (m.food = Infinity),
      (m: any) => (m.team = ["sprig", "sprig", "moss"]),
      (m: any) => (m.roster[0].rank = 3),
      (m: any) => (m.roster[0].evolved = true),
      (m: any) => (m.guardians = [false, true, false, false]),
      (m: any) => (m.eggs = [{ id: "fox", winsAt: 5 }]),
      (m: any) => (m.roster[0].priority = "eval"),
    ]) {
      const m = clone(base);
      mutate(m);
      expect(isBeastMemory(m)).toBe(false);
    }
    const { m } = encounter();
    m.expedition!.pending!.wild = ["fox"];
    expect(isBeastMemory(m)).toBe(false);
  });
  it("奖励只能结算一次，失败保持节点，四目标领取一次", () => {
    const { m, b } = encounter();
    b.phase = "won";
    const win = settle(m, b);
    expect(win.wins).toBe(1);
    expect(win.expedition!.step).toBe(1);
    expect(settle(win, b)).toEqual(win);
    const lost = encounter();
    lost.b.phase = "lost";
    expect(settle(lost.m, lost.b).expedition?.pending).not.toBeNull();
    win.captures = 1;
    const reward = claimGoal(win, 0);
    expect(reward.food).toBe(win.food + 20);
    expect(claimGoal(reward, 0)).toEqual(reward);
  });
});
describe("实时阵容与捕获", () => {
  it("留手保护持续伤害目标，安抚提升捕获并立即消耗诱饵", () => {
    const { m, b } = encounter();
    setStance(b, "tame");
    const target = living(b, "wild")[0]!;
    target.hp = 2;
    target.poison = 20;
    target.burn = 20;
    run(b, 3);
    expect(target.hp).toBe(1);
    const before = captureChance(b);
    b.charge = 100;
    expect(order(b, "soothe")).toBe(true);
    b.orderCooldown = 0;
    order(b, "soothe");
    expect(captureChance(b)).toBeGreaterThan(before);
    expect(captureChance(b)).toBe(100);
    const bait = m.bait;
    expect(capture(b, m)).toBe(true);
    expect(m.bait).toBe(bait - 1);
    const after = settle(m, b);
    expect(after.captures).toBe(1);
    expect(after.bait).toBe(bait);
    expect(capture(b, m)).toBe(false);
  });
  it("失败捕获消耗与冷却有效；守卫不能安抚或捕获", () => {
    const { m, b } = encounter();
    b.encounter.seed = 60;
    expect(capture(b, m)).toBe(false);
    expect(m.bait).toBe(4);
    expect(capture(b, m)).toBe(false);
    expect(m.bait).toBe(4);
    const g = encounter(0, true);
    expect(order(g.b, "soothe")).toBe(false);
    expect(capture(g.b, g.m)).toBe(false);
  });
  it("所有十二兽技能两分支都会产生生命/盾/状态/节奏变化", () => {
    for (const id of beastIds)
      for (const path of [0, 1] as const) {
        const ids = [id, ...beastIds.filter((x) => x !== id).slice(0, 2)];
        const { b } = encounter(3, true, ids, 4);
        const a = b.actors[0]!;
        a.path = path;
        a.rank = 2;
        a.skillWait = 0;
        a.priority = "skill";
        for (const f of b.actors) {
          f.hp = f.maxHp * 0.7;
          f.attackWait = 100;
        }
        const before = clone(b.actors);
        advance(b, 0.1);
        expect(
          b.log.some((line) => line.includes(species[id].skill)),
          id + path,
        ).toBe(true);
        expect(
          b.actors.some(
            (f, i) =>
              f.hp !== before[i]!.hp ||
              f.shield > 0 ||
              f.poison > 0 ||
              f.burn > 0 ||
              f.silence > 0 ||
              f.stun > 0 ||
              f.haste > 0 ||
              f.skillWait < before[i]!.skillWait,
          ),
          id + path,
        ).toBe(true);
      }
  });
  it("四种协同真正触发生命、净化护盾、燃烧、沉默效果", () => {
    for (const [ids, bond] of [
      [["sprig", "moss", "fin"], "bloom"],
      [["fin", "otter", "moss"], "tide"],
      [["fox", "cinder", "fin"], "ember"],
      [["moth", "owl", "moss"], "echo"],
    ] as const) {
      const { m, b } = encounter(3, true, [...ids], 4);
      expect(activeBonds(m)).toContain(bond);
      for (const a of b.actors) {
        a.attackWait = 100;
        a.skillWait = 100;
      }
      if (bond === "bloom") {
        b.actors[0]!.hp -= 60;
        const hp = b.actors[0]!.hp;
        run(b, 4.2);
        expect(b.actors[0]!.hp).toBeGreaterThan(hp);
      }
      if (bond === "tide") {
        b.actors[0]!.burn = 20;
        run(b, 5.2);
        expect(b.actors[0]!.burn).toBe(0);
        expect(b.actors[0]!.shield).toBeGreaterThan(0);
      }
      if (bond === "ember") {
        b.actors[0]!.skillWait = 0;
        advance(b, 0.1);
        expect(living(b, "wild")[0]!.burn).toBeGreaterThan(0);
      }
      if (bond === "echo") {
        b.skillCount = 2;
        b.actors[0]!.skillWait = 0;
        advance(b, 0.1);
        expect(living(b, "wild")[0]!.silence).toBeGreaterThan(0);
      }
    }
  });
  it("四守卫有不同蓄力效果，号令可以打断且不能越过冷却", () => {
    for (let i = 0; i < 4; i++) {
      const { b } = encounter(i, true, ["moss", "sprig", "fin"], 4);
      b.actors.forEach((a) => {
        a.attackWait = 100;
        a.skillWait = 100;
      });
      const g = living(b, "wild")[0]!;
      g.skillWait = 0;
      advance(b, 0.1);
      expect(g.cast).toBeGreaterThan(0);
      run(b, 2.6);
      if (i === 0) expect(b.actors[0]!.hp).toBeLessThan(b.actors[0]!.maxHp);
      if (i === 1) expect(g.shield).toBeGreaterThan(0);
      if (i === 2)
        expect(living(b, "wild").some((a) => a.kind === "healer")).toBe(true);
      if (i === 3)
        expect(b.actors.every((a) => a.side === "wild" || a.silence > 0)).toBe(
          true,
        );
      g.cast = 2;
      b.charge = 100;
      expect(order(b, "hush")).toBe(true);
      expect(g.cast).toBe(0);
      expect(order(b, "hush")).toBe(false);
    }
  });
  it("合理等级下两组不同阵容能够完成四守卫；无培养脆弱阵容有失败反馈", () => {
    for (let region = 0; region < 4; region++)
      for (const ids of [
        ["moss", "sprig", "fin"],
        ["crab", "otter", "owl"],
      ] as BeastId[][]) {
        const { b } = encounter(region, true, ids, 3 + region);
        run(b, 149, true);
        expect(
          b.phase,
          `${regions[region]!.name} ${ids.join(",")} hp:${b.actors.map((a) => a.hp)}`,
        ).toBe("won");
      }
    const { b } = encounter(3, true, ["sprig", "fox", "moth"], 1);
    run(b, 149);
    expect(b.phase).toBe("lost");
  });
  it("时间步长限幅，结束之后不再运行或发奖励", () => {
    const { b } = encounter();
    advance(b, 100);
    expect(b.seconds).toBe(0.1);
    b.phase = "won";
    const before = clone(b);
    advance(b, 0.1);
    expect(b).toEqual(before);
  });
});
