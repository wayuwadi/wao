import { describe, expect, it } from "vitest";
import {
  createRun,
  distance,
  MAX_SPEED,
  METRE,
  minimumGap,
  nextObstacle,
  pauseRun,
  playerBox,
  queueJump,
  shapes,
  STEP,
  step,
  type ObstacleKind,
} from "./engine";
import { finishMemory, freshMemory, isDinoMemory } from "./save";

const idle = { jumpHeld: false, duck: false };
function jumpPeak(held: boolean) {
  const s = createRun("practice");
  s.phase = "running";
  s.obstacles = [];
  s.nextAt = Infinity;
  queueJump(s);
  let peak = 0;
  for (let i = 0; i < 110; i++) {
    step(s, { jumpHeld: held, duck: false });
    peak = Math.max(peak, s.player.y);
  }
  return { s, peak };
}
describe("跑酷手感与边界", () => {
  it("轻点与长按形成两条真实轨迹；按住不自动连跳", () => {
    const short = jumpPeak(false),
      full = jumpPeak(true);
    expect(short.peak).toBeGreaterThan(60);
    expect(short.peak).toBeLessThan(90);
    expect(full.peak).toBeGreaterThan(115);
    expect(full.peak - short.peak).toBeGreaterThan(35);
    expect(full.s.jumps).toBe(1);
    expect(full.s.landings).toBe(1);
    expect(full.s.player.y).toBe(0);
  });
  it("下蹲缩小高度且横向变长；空中下键加快落地", () => {
    const full = jumpPeak(true).s;
    expect(playerBox({ ...full.player, duck: true }).h).toBeLessThan(
      playerBox(full.player).h,
    );
    expect(playerBox({ ...full.player, duck: true }).w).toBeGreaterThan(
      playerBox(full.player).w,
    );
    const s = createRun("practice");
    s.obstacles = [];
    s.nextAt = Infinity;
    s.phase = "running";
    queueJump(s);
    for (let i = 0; i < 20; i++) step(s, { jumpHeld: true, duck: false });
    const before = s.player.y;
    for (let i = 0; i < 36; i++) step(s, { jumpHeld: false, duck: true });
    expect(s.player.y).toBeLessThan(before);
    expect(s.player.y).toBe(0);
  });
  it("暂停清缓冲，时间和位置保持，不补跑；拒绝大步长", () => {
    const s = createRun("endless");
    s.phase = "running";
    queueJump(s);
    pauseRun(s);
    expect(s.player.buffer).toBe(0);
    step(s, idle);
    expect(s.travel).toBe(0);
    s.phase = "running";
    expect(() => step(s, idle, 0.3)).toThrow();
    step(s, idle);
    expect(s.jumps).toBe(0);
  });
  it("碰撞停止运行并提供具体失败原因", () => {
    const s = createRun("practice");
    s.phase = "running";
    for (let i = 0; i < 600; i++) step(s, idle);
    expect(s.phase).toBe("crashed");
    expect(s.collision).toBe("small");
    const travel = s.travel;
    step(s, idle);
    expect(s.travel).toBe(travel);
  });
  it("所有障碍在最高速度仍有连续的可通过输入窗口", () => {
    for (const kind of Object.keys(shapes) as ObstacleKind[]) {
      for (const speed of [320, 460, MAX_SPEED]) {
        let safeWindows = 0;
        for (let lead = 0.1; lead <= 0.4; lead += 0.01) {
          const s = createRun("endless");
          s.phase = "running";
          s.travel = (speed - 320) * 120;
          s.obstacles = [
            { id: 0, kind, at: s.travel + 142 + speed * lead, passed: false },
          ];
          s.nextAt = Infinity;
          const bird = kind.startsWith("bird") && kind !== "bird-low";
          if (!bird) queueJump(s);
          for (let i = 0; i < 140; i++)
            step(s, { jumpHeld: !bird, duck: kind === "bird-mid" });
          if (s.phase === "running" && s.cleared === 1) safeWindows++;
        }
        expect(safeWindows, `${kind} @ ${speed}`).toBeGreaterThanOrEqual(8);
      }
    }
  });
});

describe("自动模拟：生成器与完整挑战（不代替浏览器手操）", () => {
  function simulate(
    seed: number,
    mode: "sprint" | "practice" | "endless",
    maxSpeed = false,
  ) {
    const s = createRun(mode, seed, 600);
    s.phase = "running";
    if (maxSpeed) {
      s.travel = 40_000;
      s.obstacles = [];
      s.nextAt = s.travel + 1000;
      s.nextId = 2;
    }
    for (let i = 0; i < 15_000 && s.phase === "running"; i++) {
      const o = nextObstacle(s),
        duck = o?.kind === "bird-mid" && o.at - s.travel < 450;
      if (
        o &&
        (!o.kind.startsWith("bird") || o.kind === "bird-low") &&
        s.player.y === 0
      ) {
        const seconds = (o.at - s.travel - 142) / s.speed;
        if (seconds <= 0.24 && seconds > 0) queueJump(s);
      }
      step(s, { jumpHeld: true, duck });
      expect(s.speed).toBeLessThanOrEqual(MAX_SPEED);
      for (let j = 1; j < s.obstacles.length; j++) {
        const a = s.obstacles[j - 1]!,
          b = s.obstacles[j]!;
        expect(b.at - a.at - shapes[a.kind].w).toBeGreaterThan(390);
      }
      if (maxSpeed && s.cleared >= 45) break;
    }
    return s;
  }
  it("100 个种子真实物理通关1000米；结果与同种子一致", () => {
    for (let seed = 1; seed <= 100; seed++) {
      const s = simulate(seed, "sprint");
      expect(s.phase, `seed ${seed}: ${s.collision}`).toBe("won");
      expect(distance(s)).toBeGreaterThanOrEqual(1000);
      expect(s.travel).toBeLessThan(1000 * METRE + MAX_SPEED * STEP);
    }
    expect(simulate(37, "sprint")).toEqual(simulate(37, "sprint"));
  }, 15000);
  it("最高速度100种子连续障碍可通过；窄屏不改变物理", () => {
    for (let seed = 1; seed <= 100; seed++) {
      const s = simulate(seed, "endless", true);
      expect(s.phase, `max seed ${seed}: ${s.collision}`).toBe("running");
      expect(s.cleared).toBeGreaterThanOrEqual(45);
    }
    expect(minimumGap(MAX_SPEED)).toBeGreaterThan(MAX_SPEED);
  }, 15000);
  it("练习有六种障碍与独立成功结算", () => {
    const s = simulate(1, "practice");
    expect(s.phase).toBe("won");
    expect(s.cleared).toBe(6);
    expect(s.ducked).toBe(1);
    const m = finishMemory(freshMemory(), s);
    expect(m.practiceComplete).toBe(true);
    expect(m.best).toBe(0);
    expect(m.runs).toBe(0);
  });
  it("正式挑战记录可验证，拒绝伪造字段/范围/非有限值", () => {
    const m = finishMemory(freshMemory(), simulate(7, "sprint"));
    expect(isDinoMemory(m)).toBe(true);
    expect(m.sprintWins).toBe(1);
    expect(m.best).toBe(1000);
    for (const bad of [
      { ...m, best: Infinity },
      { ...m, best: -1 },
      { ...m, html: "<script>" },
      { ...m, sprintWins: 8 },
      { ...m, bestSprintMs: 1 },
    ])
      expect(isDinoMemory(bad)).toBe(false);
  });
});
