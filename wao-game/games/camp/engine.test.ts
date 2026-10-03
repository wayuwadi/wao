import { describe, it, expect } from "vitest";
import { maps, towerKinds, waveRoster, type TowerKind } from "./catalog";
import {
  advance,
  castBlizzard,
  castRally,
  pathLength,
  pointAt,
  settle,
  spawn,
  startBattle,
  stats,
  supportedStats,
  target,
} from "./engine";
import {
  beginRun,
  build,
  clone,
  freshCampMemory,
  isCampMemory,
  priority,
  sell,
  upgrade,
  invested,
} from "./save";
import type { CampRun } from "./model";

function unlocked() {
  const m = freshCampMemory();
  m.wins = [1, 1, 1];
  return m;
}
const padOrders = [
  [2, 4, 5, 7, 0, 6, 8, 3, 10, 11, 1, 9],
  [5, 6, 2, 4, 3, 7, 9, 8, 0, 1, 11, 10],
  [4, 3, 6, 7, 2, 8, 10, 5, 0, 1, 9, 11],
];
const compositions: TowerKind[][] = [
  ["bolt", "frost", "cannon", "bolt", "beam", "support", "cannon", "bolt"],
  ["beam", "bolt", "support", "beam", "frost", "bolt", "beam", "support"],
  ["cannon", "beam", "bolt", "support", "cannon", "beam", "frost", "bolt"],
];
export function invest(r: CampRun, style: number) {
  const order = padOrders[r.map]!,
    kinds = compositions[style]!;
  // Use only actually earned gold. Three distinct plans share the same budget.
  for (let count = 0; count < 30; count++) {
    const n = r.towers.length;
    if (n < 3) {
      if (!build(r, order[n]!, kinds[n]!)) break;
      continue;
    }
    const low = r.towers.find((t) => t.level < 2);
    if (low) {
      if (
        !upgrade(
          r,
          low.pad,
          low.kind === "cannon"
            ? 1
            : low.kind === "bolt"
              ? 0
              : low.kind === "support"
                ? 0
                : 1,
        )
      )
        break;
      continue;
    }
    if (n < 6) {
      if (!build(r, order[n]!, kinds[n]!)) break;
      continue;
    }
    const mid = r.towers.find((t) => t.level < 3);
    if (mid) {
      if (!upgrade(r, mid.pad, mid.branch!)) break;
      continue;
    }
    if (n < 8) {
      if (!build(r, order[n]!, kinds[n]!)) break;
      continue;
    }
    break;
  }
}
function finish(r: CampRun, skills = true) {
  const b = startBattle(r);
  for (let i = 0; i < 6000 && b.phase === "running"; i++) {
    advance(b, 0.1);
    if (skills && b.foes.some((f) => f.boss && f.distance > 300)) {
      if (!b.rally) castRally(b);
      const boss = b.foes.find((f) => f.boss);
      if (boss && !b.blizzard && boss.shield > 0) castBlizzard(b, boss);
    }
    if (skills && !b.rally && b.run.lives < 14) castRally(b);
  }
  return b;
}
describe("营地存档与布局", () => {
  it("锁图与无尽门禁、初始合法、三图几何独立", () => {
    const m = freshCampMemory();
    expect(isCampMemory(m)).toBe(true);
    expect(beginRun(m, 1, "campaign").run).toBeNull();
    expect(beginRun(m, 0, "endless").run).toBeNull();
    const r = beginRun(m, 0, "campaign");
    expect(isCampMemory(r)).toBe(true);
    expect(new Set(maps.map((m) => JSON.stringify(m.paths))).size).toBe(3);
    for (const m of maps)
      for (const p of m.paths) {
        expect(pathLength(p)).toBeGreaterThan(1000);
        expect(pointAt(p, 0)).toEqual(p[0]);
        expect(pointAt(p, 1e9)).toEqual(p.at(-1));
      }
  });
  it("建造禁止占点、升级分支与预算、出售70%和优先级", () => {
    const r = beginRun(freshCampMemory(), 0, "campaign").run!;
    expect(build(r, 2, "cannon")).toBe(true);
    expect(build(r, 2, "bolt")).toBe(false);
    expect(build(r, 99, "beam")).toBe(false);
    expect(upgrade(r, 2, 1)).toBe(true);
    expect(r.towers[0]?.branch).toBe(1);
    expect(upgrade(r, 2, 0)).toBe(false);
    const before = r.gold,
      refund = Math.floor(invested(r.towers[0]!) * 0.7);
    expect(priority(r, 2, "support")).toBe(true);
    expect(sell(r, 2)).toBe(true);
    expect(r.gold).toBe(before + refund);
    expect(sell(r, 2)).toBe(false);
  });
  it("拒绝未知字段、非法地图分支重复点位与超范围数值", () => {
    const m = beginRun(unlocked(), 2, "campaign");
    build(m.run!, 4, "frost");
    expect(isCampMemory(m)).toBe(true);
    for (const mutate of [
      (n: any) => (n.run.gold = -1),
      (n: any) => n.run.towers.push(n.run.towers[0]),
      (n: any) => (n.run.towers[0].branch = 0),
      (n: any) => (n.run.towers[0].kind = "<script>"),
      (n: any) => (n.run.wave = 13),
      (n: any) => (n.run.hp = 10),
      (n: any) => (n.wins[0] = 0),
      (n: any) => (n.speed = 9),
    ]) {
      const n = clone(m);
      mutate(n);
      expect(isCampMemory(n)).toBe(false);
    }
  });
  it("出售指定塔只返还它的投资，保留其他塔的分支和目标策略", () => {
    const r = beginRun(freshCampMemory(), 0, "campaign").run!;
    r.gold = 1000;
    build(r, 2, "bolt");
    build(r, 4, "frost");
    build(r, 5, "cannon");
    upgrade(r, 4, 1);
    priority(r, 5, "support");
    const remaining = clone(r.towers.filter((t) => t.pad !== 2)),
      gold = r.gold;
    expect(sell(r, 2)).toBe(true);
    expect(r.towers).toEqual(remaining);
    expect(r.gold).toBe(gold + 38);
    expect(sell(r, 2)).toBe(false);
    expect(r.towers).toEqual(remaining);
  });
  it("波间推进和最终解锁、失败与无尽纪录、重复结算不重复奖励", () => {
    const m = beginRun(freshCampMemory(), 0, "campaign"),
      b = startBattle(m.run!);
    b.phase = "won";
    b.run.gold += 20;
    const n = settle(m, b);
    expect(n.run?.wave).toBe(2);
    expect(settle(n, b)).toEqual(n);
    const final = startBattle({ ...m.run!, wave: 12 });
    final.phase = "won";
    const win = settle(m, final);
    expect(win.wins[0]).toBe(1);
    expect(beginRun(win, 1, "campaign").run).not.toBeNull();
    expect(beginRun(win, 0, "endless").run).not.toBeNull();
    const endless = startBattle({
      ...beginRun(win, 0, "endless").run!,
      wave: 9,
    });
    endless.phase = "lost";
    expect(settle(win, endless).bestEndless[0]).toBe(8);
  });
});
describe("敌人塔与技能", () => {
  it("十二波包含全部六敌与对应Boss，无尽循环提高压力", () => {
    const seen = new Set();
    for (const m of maps) {
      for (let w = 1; w <= 12; w++)
        waveRoster(m.id, w).forEach((k) => seen.add(k));
      expect(waveRoster(m.id, 12)).toContain(m.boss);
    }
    expect(seen.size).toBe(9);
    expect(waveRoster(0, 36, true).length).toBeGreaterThan(
      waveRoster(0, 12).length,
    );
  });
  it("射程、空地、优先级、破甲和两升级分支有实际差异", () => {
    const r = beginRun(unlocked(), 0, "campaign").run!;
    r.gold = 5000;
    build(r, 2, "cannon");
    const b = startBattle(r),
      pos = maps[0]!.pads[2]!;
    const f = spawn(b, "flyer");
    Object.assign(f, pos);
    expect(target(b, r.towers[0]!, 200)).toBeUndefined();
    const a = spawn(b, "armor");
    Object.assign(a, pos);
    expect(target(b, r.towers[0]!, 200)).toBe(a);
    const h = spawn(b, "healer");
    Object.assign(h, pos);
    priority(r, 2, "support");
    expect(target(b, r.towers[0]!, 200)).toBe(h);
    for (const kind of towerKinds) {
      const base = {
        pad: 2,
        kind,
        level: 1 as const,
        branch: null,
        priority: "first" as const,
      };
      const aa = { ...base, level: 3 as const, branch: 0 as const },
        bb = { ...base, level: 3 as const, branch: 1 as const };
      expect(stats(aa).range).toBeGreaterThanOrEqual(stats(base).range);
      if (kind === "bolt" || kind === "cannon" || kind === "beam")
        expect(stats(aa)).not.toEqual(stats(bb));
    }
  });
  it("支援范围改变伤害攻速，远离时不生效", () => {
    const r = beginRun(unlocked(), 0, "campaign").run!;
    r.gold = 9999;
    build(r, 2, "bolt");
    build(r, 4, "support");
    upgrade(r, 4, 0);
    expect(supportedStats(r, r.towers[0]!).damage).toBeGreaterThan(
      stats(r.towers[0]!).damage,
    );
    r.towers[1]!.branch = 1;
    expect(supportedStats(r, r.towers[0]!).interval).toBeLessThan(
      stats(r.towers[0]!).interval,
    );
    r.towers[1]!.pad = 9;
    expect(supportedStats(r, r.towers[0]!).damage).toBe(
      stats(r.towers[0]!).damage,
    );
  });
  it("霜塔冻结与易伤分支实际改变目标状态；破甲炮不伤飞行目标", () => {
    for (const branch of [0, 1] as const) {
      const r = beginRun(unlocked(), 0, "campaign").run!;
      r.gold = 1000;
      build(r, 2, "frost");
      upgrade(r, 2, branch);
      const b = startBattle(r);
      b.queue = [];
      const f = spawn(b, "armor");
      f.freeze = 10;
      f.hp = f.maxHp = 1000;
      Object.assign(f, maps[0]!.pads[2]);
      for (let i = 0; i < 18; i++) advance(b, 0.1);
      expect(b.turrets[2]!.shots).toBeGreaterThanOrEqual(3);
      expect(f.slow).toBe(branch === 0 ? 0.4 : 0.62);
      expect(f.weak > 0).toBe(branch === 1);
      expect(f.hp).toBeLessThan(1000);
      f.freeze = 0.2;
      b.turrets[2]!.cooldown = 0;
      b.turrets[2]!.shots = 2;
      advance(b, 0.1);
      expect(f.freeze).toBeCloseTo(branch === 0 ? 0.7 : 0.1);
    }
    const r = beginRun(unlocked(), 0, "campaign").run!;
    r.gold = 1000;
    build(r, 2, "cannon");
    upgrade(r, 2, 1);
    const b = startBattle(r);
    b.queue = [];
    const ground = spawn(b, "armor"),
      air = spawn(b, "flyer");
    for (const f of [ground, air]) {
      f.freeze = 10;
      Object.assign(f, maps[0]!.pads[2]);
    }
    advance(b, 0.1);
    expect(ground.shred).toBe(10);
    expect(ground.hp).toBeLessThan(ground.maxHp);
    expect(air.hp).toBe(air.maxHp);
    expect(air.shred).toBe(0);
  });
  it("贯穿击中直线远端但不打偏轴，分叉改为打邻近侧翼", () => {
    for (const branch of [0, 1] as const) {
      const r = beginRun(unlocked(), 0, "campaign").run!;
      r.gold = 1000;
      build(r, 2, "beam");
      upgrade(r, 2, branch);
      const b = startBattle(r);
      b.queue = [];
      const pos = maps[0]!.pads[2]!,
        front = spawn(b, "armor"),
        inline = spawn(b, "armor"),
        side = spawn(b, "armor");
      front.distance = 500;
      inline.distance = 400;
      side.distance = 300;
      Object.assign(front, { x: pos.x + 60, y: pos.y });
      Object.assign(inline, { x: pos.x + 130, y: pos.y });
      Object.assign(side, { x: pos.x + 70, y: pos.y + 70 });
      for (const f of [front, inline, side]) f.freeze = 10;
      advance(b, 0.1);
      expect(front.hp).toBeLessThan(front.maxHp);
      expect(inline.hp).toBeLessThan(inline.maxHp);
      expect(side.hp < side.maxHp).toBe(branch === 1);
    }
  });
  it("寒潮范围与每波次数、鼓舞修补/加速/解除干扰", () => {
    const r = beginRun(unlocked(), 0, "campaign").run!,
      b = startBattle(r);
    b.run.lives = 12;
    const f = spawn(b, "bastion");
    f.x = 200;
    f.y = 200;
    f.shield = 200;
    f.charge = 2;
    expect(castBlizzard(b, { x: 200, y: 200 })).toBe(true);
    expect(f.shield).toBe(0);
    expect(f.charge).toBe(0);
    expect(f.freeze).toBe(1);
    expect(castBlizzard(b, f)).toBe(false);
    b.turrets[2] = { cooldown: 0, shots: 0, disabled: 4, repair: 0 };
    expect(castRally(b)).toBe(true);
    expect(b.run.lives).toBe(15);
    expect(b.turrets[2]!.disabled).toBe(0);
    expect(b.boost).toBe(7);
    expect(castRally(b)).toBe(false);
  });
  it("三Boss机制不是增加生命，治疗与护盾敌人支援有效", () => {
    for (const kind of ["bastion", "drummer", "tempest"] as const) {
      const r = beginRun(unlocked(), 0, "campaign").run!;
      build(r, 2, "frost");
      const b = startBattle(r);
      b.queue = [];
      const f = spawn(b, kind);
      f.distance = 360;
      Object.assign(f, pointAt(maps[0]!.paths[0], 360));
      f.skill = 0.01;
      for (let i = 0; i < 29; i++) advance(b, 0.1);
      if (kind === "bastion") expect(f.shield).toBeGreaterThan(0);
      if (kind === "drummer")
        expect(b.foes.some((x) => x.kind === "hound")).toBe(true);
      if (kind === "tempest") expect(b.turrets[2]?.disabled).toBeGreaterThan(0);
    }
    const b = startBattle(beginRun(unlocked(), 0, "campaign").run!);
    b.queue = [];
    const a = spawn(b, "walker"),
      h = spawn(b, "healer"),
      w = spawn(b, "ward");
    a.hp = 10;
    w.skill = 0.01;
    advance(b, 0.1);
    expect(a.hp).toBeGreaterThan(10);
    expect(a.shield).toBeGreaterThan(0);
    expect(h.hp).toBe(h.maxHp);
  });
  it("无防御会失败；结束不再移动、奖励或漏怪", () => {
    const r = beginRun(freshCampMemory(), 0, "campaign").run!;
    r.wave = 10;
    r.lives = 1;
    const b = finish(r, false);
    expect(b.phase).toBe("lost");
    const frozen = clone(b);
    advance(b, 1);
    expect(b).toEqual(frozen);
  });
});
describe("三地图多布局完整模拟（不替代浏览器）", () => {
  for (let map = 0; map < 3; map++)
    for (let style = 0; style < 3; style++)
      it(`地图${map + 1}布局${style + 1}仅用真实奖励完成12波`, () => {
        let m = beginRun(unlocked(), map, "campaign");
        const waves = [];
        for (let w = 1; w <= 12; w++) {
          invest(m.run!, style);
          const b = finish(m.run!);
          waves.push({
            wave: w,
            result: b.phase,
            lives: b.run.lives,
            spent: b.run.spent,
            gold: b.run.gold,
          });
          expect(b.phase, JSON.stringify(waves)).toBe("won");
          m = settle(m, b);
          expect(isCampMemory(m)).toBe(true);
        }
        expect(m.run).toBeNull();
        expect(m.wins[map]).toBe(2);
      });
});
