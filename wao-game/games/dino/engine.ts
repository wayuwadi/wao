/** 原创跑酷物理；不依赖 DOM、账号、帧率或渲染器。所有尺寸为逻辑像素。 */
export const STEP = 1 / 120;
export const PLAYER_X = 96;
export const GRAVITY = 2100;
export const JUMP_SPEED = 720;
export const SHORT_JUMP_SPEED = 390;
export const MAX_AIR_TIME = (2 * JUMP_SPEED) / GRAVITY;
export const MAX_SPEED = 600;
export const METRE = 16;

export type Mode = "practice" | "sprint" | "endless";
export type Phase = "ready" | "running" | "paused" | "crashed" | "won";
export type ObstacleKind =
  "small" | "tall" | "cluster" | "bird-low" | "bird-mid" | "bird-high";
export type Controls = { jumpHeld: boolean; duck: boolean };
export type Obstacle = {
  id: number;
  kind: ObstacleKind;
  at: number;
  passed: boolean;
};
export type Box = { x: number; y: number; w: number; h: number };
export type Player = {
  y: number;
  vy: number;
  age: number;
  duck: boolean;
  buffer: number;
};
export type Run = {
  mode: Mode;
  phase: Phase;
  seed: number;
  width: number;
  travel: number;
  elapsed: number;
  speed: number;
  player: Player;
  obstacles: Obstacle[];
  nextAt: number;
  nextId: number;
  cleared: number;
  jumps: number;
  landings: number;
  ducked: number;
  collision: ObstacleKind | null;
};

export const shapes: Record<
  ObstacleKind,
  { w: number; h: number; bottom: number; label: string; tip: string }
> = {
  small: { w: 25, h: 31, bottom: 0, label: "小仙人掌", tip: "轻点跳跃" },
  tall: { w: 31, h: 59, bottom: 0, label: "高仙人掌", tip: "按住跳高一点" },
  cluster: {
    w: 61,
    h: 43,
    bottom: 0,
    label: "仙人掌群",
    tip: "稍晚起跳，跨过整组",
  },
  "bird-low": { w: 43, h: 21, bottom: 13, label: "低飞鸟", tip: "跳过去" },
  "bird-mid": {
    w: 45,
    h: 23,
    bottom: 34,
    label: "中飞鸟",
    tip: "下蹲让它飞过去",
  },
  "bird-high": {
    w: 43,
    h: 23,
    bottom: 85,
    label: "高飞鸟",
    tip: "站着通过，别急着跳",
  },
};
export const lesson: ObstacleKind[] = [
  "small",
  "bird-mid",
  "bird-high",
  "tall",
  "cluster",
  "bird-low",
];
const kinds = Object.keys(shapes) as ObstacleKind[];

function random(s: Run) {
  s.seed = (Math.imul(s.seed, 1664525) + 1013904223) >>> 0;
  return s.seed / 4294967296;
}

/** 先落地、恢复姿势，再为下一障碍留反应窗口。取未来更高速度避免提速吃掉间距。 */
export function minimumGap(speed: number) {
  return (
    Math.ceil(Math.min(MAX_SPEED, speed + 24) * (MAX_AIR_TIME + 0.55)) + 32
  );
}

export function createRun(mode: Mode, seed = 1, width = 1000): Run {
  const speed = mode === "practice" ? 260 : 320;
  const s: Run = {
    mode,
    phase: "ready",
    seed: seed >>> 0,
    width: Math.max(600, width),
    travel: 0,
    elapsed: 0,
    speed,
    player: { y: 0, vy: 0, age: 0, duck: false, buffer: 0 },
    obstacles: [],
    nextAt: PLAYER_X + speed * 3,
    nextId: 0,
    cleared: 0,
    jumps: 0,
    landings: 0,
    ducked: 0,
    collision: null,
  };
  fill(s);
  return s;
}

function fill(s: Run) {
  while (s.nextAt < s.travel + s.width + 900) {
    if (s.mode === "practice" && s.nextId >= lesson.length) break;
    // 起步先给低障碍；后续才混入需要改变动作的飞鸟与组合。
    const kind =
      s.mode === "practice"
        ? lesson[s.nextId]!
        : s.nextId < 2
          ? "small"
          : kinds[Math.floor(random(s) * kinds.length)]!;
    s.obstacles.push({ id: s.nextId++, kind, at: s.nextAt, passed: false });
    const gap =
      s.mode === "practice"
        ? s.speed * 2.7
        : minimumGap(s.speed) + random(s) * s.speed * 0.5;
    s.nextAt += shapes[kind].w + gap;
  }
}

export function playerBox(p: Player): Box {
  return p.duck && p.y === 0
    ? { x: PLAYER_X + 3, y: 3, w: 60, h: 21 }
    : { x: PLAYER_X + 13, y: p.y + 4, w: 33, h: 46 };
}

export function obstacleBox(o: Obstacle, travel: number): Box {
  const spec = shapes[o.kind];
  return {
    x: o.at - travel + 3,
    y: spec.bottom + 2,
    w: spec.w - 6,
    h: spec.h - 4,
  };
}

export function intersects(a: Box, b: Box) {
  return (
    a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y
  );
}

export function queueJump(s: Run) {
  if (s.phase === "running") s.player.buffer = 0.09;
}

export function clearInput(s: Run) {
  s.player.buffer = 0;
  s.player.duck = false;
}

export function pauseRun(s: Run) {
  if (s.phase === "running") s.phase = "paused";
  clearInput(s);
}

/** 固定小步推进；调用方负责累加时钟，暂停期间绝不补跑。 */
export function step(s: Run, input: Controls, dt = STEP) {
  if (s.phase !== "running" || !Number.isFinite(dt) || dt <= 0) return;
  if (dt > 1 / 60) throw new RangeError("Use fixed physics steps");
  const p = s.player;
  p.buffer = Math.max(0, p.buffer - dt);
  p.duck = input.duck && p.y === 0;
  if (p.buffer > 0 && p.y === 0 && !input.duck) {
    p.vy = JUMP_SPEED;
    p.age = 0;
    p.buffer = 0;
    s.jumps++;
  }
  const airborne = p.y > 0 || p.vy > 0;
  if (airborne) {
    p.age += dt;
    // 保留最短起跳阶段，轻点也有足够高度；按住才得到完整弧线。
    if (!input.jumpHeld && p.age > 0.065 && p.vy > SHORT_JUMP_SPEED)
      p.vy = SHORT_JUMP_SPEED;
    p.vy -= GRAVITY * (input.duck ? 2.8 : 1) * dt;
    p.y = Math.max(0, p.y + p.vy * dt);
    if (p.y === 0) {
      p.vy = 0;
      s.landings++;
    }
  }
  s.elapsed += dt;
  s.speed =
    s.mode === "practice" ? 260 : Math.min(MAX_SPEED, 320 + s.travel / 120);
  s.travel += s.speed * dt;
  const hit = playerBox(p);
  for (const o of s.obstacles) {
    if (intersects(hit, obstacleBox(o, s.travel))) {
      s.phase = "crashed";
      s.collision = o.kind;
      clearInput(s);
      return;
    }
    if (!o.passed && o.at + shapes[o.kind].w - s.travel < PLAYER_X) {
      o.passed = true;
      s.cleared++;
      if (o.kind === "bird-mid" && input.duck) s.ducked++;
    }
  }
  if (
    (s.mode === "sprint" && s.travel >= 1000 * METRE) ||
    (s.mode === "practice" && s.cleared === lesson.length)
  ) {
    s.phase = "won";
    clearInput(s);
    return;
  }
  s.obstacles = s.obstacles.filter(
    (o) => o.at + shapes[o.kind].w > s.travel - 100,
  );
  fill(s);
}

export function nextObstacle(s: Run) {
  return s.obstacles.find((o) => !o.passed) ?? null;
}

export function distance(s: Run) {
  return Math.floor(s.travel / METRE);
}
