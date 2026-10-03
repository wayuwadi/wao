import { distance, PLAYER_X, shapes, type Run } from "./engine";

export const HEIGHT = 380;
const GROUND = 290;
function rounded(
  c: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number,
  color: string,
) {
  c.fillStyle = color;
  c.beginPath();
  c.roundRect(x, y, w, h, r);
  c.fill();
}
function ellipse(
  c: CanvasRenderingContext2D,
  x: number,
  y: number,
  rx: number,
  ry: number,
  color: string,
) {
  c.fillStyle = color;
  c.beginPath();
  c.ellipse(x, y, rx, ry, 0, 0, Math.PI * 2);
  c.fill();
}
function mountain(
  c: CanvasRenderingContext2D,
  x: number,
  y: number,
  size: number,
  color: string,
) {
  c.fillStyle = color;
  c.beginPath();
  c.moveTo(x - size, y);
  c.lineTo(x - size * 0.45, y - size * 0.5);
  c.lineTo(x - size * 0.1, y - size * 0.52);
  c.lineTo(x + size * 0.35, y - size * 0.94);
  c.lineTo(x + size * 0.7, y - size * 0.62);
  c.lineTo(x + size, y);
  c.fill();
}

/** 全部角色、地形与彩蛋为程序原创绘制，不使用第三方角色或贴图。 */
export function renderDino(
  canvas: HTMLCanvasElement,
  s: Run,
  reducedMotion = false,
) {
  const c = canvas.getContext("2d");
  if (!c) return;
  const width = s.width,
    ratio = Math.min(window.devicePixelRatio || 1, 2);
  const cssWidth = canvas.clientWidth || width,
    cssHeight = canvas.clientHeight || HEIGHT;
  if (
    canvas.width !== Math.round(cssWidth * ratio) ||
    canvas.height !== Math.round(cssHeight * ratio)
  ) {
    canvas.width = Math.round(cssWidth * ratio);
    canvas.height = Math.round(cssHeight * ratio);
  }
  c.setTransform(canvas.width / width, 0, 0, canvas.height / HEIGHT, 0, 0);
  const night = Math.floor(distance(s) / 240) % 2 === 1;
  const sky = c.createLinearGradient(0, 0, 0, HEIGHT);
  sky.addColorStop(0, night ? "#34253f" : "#fff1df");
  sky.addColorStop(1, night ? "#96575f" : "#ffc6b0");
  c.fillStyle = sky;
  c.fillRect(0, 0, width, HEIGHT);
  if (night) {
    for (let i = 0; i < 24; i++)
      ellipse(
        c,
        (i * 139 + 28) % width,
        22 + ((i * 43) % 140),
        i % 3 === 0 ? 2 : 1,
        1.5,
        "#fff2d5",
      );
  }
  ellipse(c, width * 0.78, 88, 37, 37, night ? "#ffedd0" : "#ffab79");
  if (night) ellipse(c, width * 0.78 + 14, 78, 32, 32, "#493047");
  for (let layer = 0; layer < 2; layer++) {
    const speed = reducedMotion ? 0 : layer === 0 ? 0.09 : 0.19;
    const period = layer === 0 ? 380 : 290;
    for (let i = -1; i < Math.ceil(width / period) + 2; i++) {
      const x = i * period - ((s.travel * speed) % period);
      mountain(
        c,
        x,
        layer === 0 ? 271 : 288,
        layer === 0 ? 210 : 150,
        layer === 0
          ? night
            ? "#654153"
            : "#eda191"
          : night
            ? "#88505c"
            : "#df887b",
      );
    }
  }
  // 三种无碰撞彩蛋：纸飞机、举旗沙獭、夜空流星。
  const egg = Math.floor(distance(s) / 90) % 3;
  const eggX = width - ((s.travel * 0.28) % (width + 160));
  if (egg === 0) {
    c.fillStyle = "#fff9e9";
    c.beginPath();
    c.moveTo(eggX, 87);
    c.lineTo(eggX + 32, 78);
    c.lineTo(eggX + 18, 101);
    c.lineTo(eggX + 13, 88);
    c.fill();
  } else if (egg === 1) {
    ellipse(c, eggX, 253, 13, 19, "#ae6156");
    ellipse(c, eggX - 7, 235, 5, 5, "#ae6156");
    ellipse(c, eggX + 7, 235, 5, 5, "#ae6156");
    ellipse(c, eggX + 5, 249, 2, 2, "#fff6de");
    c.fillStyle = "#fbd489";
    c.fillRect(eggX + 18, 232, 2, 35);
    c.fillRect(eggX + 20, 232, 19, 12);
  } else if (night) {
    c.strokeStyle = "#fff2d0";
    c.lineWidth = 2;
    c.beginPath();
    c.moveTo(eggX, 55);
    c.lineTo(eggX + 42, 37);
    c.stroke();
  }
  c.fillStyle = night ? "#3d2a38" : "#a7564f";
  c.fillRect(0, GROUND, width, 2);
  c.fillStyle = night ? "#49313e" : "#edb398";
  c.fillRect(0, GROUND + 2, width, HEIGHT - GROUND);
  c.strokeStyle = night ? "#915f6a" : "#b37563";
  c.lineWidth = 2;
  for (let i = 0; i < width / 60 + 2; i++) {
    const x = i * 61 - (s.travel % 61);
    c.beginPath();
    c.moveTo(x, 308 + (i % 3) * 11);
    c.lineTo(x + 8 + (i % 4) * 3, 308 + (i % 3) * 11);
    c.stroke();
  }
  for (const o of s.obstacles) {
    const spec = shapes[o.kind],
      x = o.at - s.travel,
      y = GROUND - spec.bottom - spec.h;
    if (x > width + 70 || x < -90) continue;
    if (o.kind.startsWith("bird")) {
      const wing = Math.sin(s.elapsed * 15 + o.id) * 11;
      ellipse(c, x + 22, y + 12, 19, 9, night ? "#ffc18c" : "#754857");
      c.fillStyle = night ? "#ffdaac" : "#985c69";
      c.beginPath();
      c.moveTo(x + 13, y + 12);
      c.lineTo(x + 27, y - 5 - wing);
      c.lineTo(x + 32, y + 13);
      c.fill();
      c.fillStyle = "#ef8757";
      c.beginPath();
      c.moveTo(x + 3, y + 9);
      c.lineTo(x - 6, y + 13);
      c.lineTo(x + 5, y + 17);
      c.fill();
      ellipse(c, x + 11, y + 9, 3, 3, "#fff9e5");
      ellipse(c, x + 10, y + 9, 1.5, 1.5, "#3c2937");
    } else {
      const count = o.kind === "cluster" ? 3 : 1;
      for (let i = 0; i < count; i++) {
        const cx = x + 5 + i * 19,
          top = y + (i % 2) * 9,
          height = spec.h - (i % 2) * 9;
        rounded(
          c,
          cx,
          top,
          count === 1 ? spec.w - 10 : 14,
          height,
          6,
          "#50796a",
        );
        rounded(c, cx - 6, top + height * 0.38, 9, height * 0.33, 4, "#50796a");
        rounded(
          c,
          cx + (count === 1 ? spec.w - 14 : 10),
          top + height * 0.24,
          9,
          height * 0.38,
          4,
          "#50796a",
        );
        c.strokeStyle = "#a7c392";
        c.lineWidth = 1.5;
        c.beginPath();
        c.moveTo(cx + 5, top + 7);
        c.lineTo(cx + 5, top + height - 3);
        c.stroke();
        if (o.kind === "tall") ellipse(c, cx + 8, top, 5, 3, "#fa796d");
      }
    }
  }
  const p = s.player,
    x = PLAYER_X,
    y = GROUND - p.y;
  ellipse(
    c,
    x + 34,
    GROUND + 4,
    Math.max(12, 28 - p.y * 0.08),
    4,
    night ? "#34243288" : "#a9625366",
  );
  c.save();
  c.translate(x, y);
  const running = s.phase === "running" && p.y === 0;
  const gait = running ? Math.sin(s.elapsed * 26) : 0;
  if (s.phase === "crashed") c.rotate(-0.14);
  const red = "#f3655c",
    shadow = "#c4424c",
    cream = "#ffe3b2";
  if (p.duck) {
    rounded(c, 4, -25, 53, 21, 10, red);
    rounded(c, 40, -29, 26, 18, 7, red);
    rounded(c, 10, -9, 33, 5, 3, cream);
    rounded(c, 15, -7, 9, 7, 2, shadow);
    rounded(c, 43, -7, 10, 7, 2, shadow);
    ellipse(c, 56, -23, 3, 3, "#382b38");
  } else {
    c.fillStyle = shadow;
    c.beginPath();
    c.moveTo(20, -23);
    c.lineTo(-2, -35);
    c.lineTo(6, -13);
    c.lineTo(25, -10);
    c.fill();
    rounded(c, 13, -41, 32, 34, 13, red);
    rounded(c, 25, -60, 33, 29, 9, red);
    rounded(c, 37, -43, 23, 13, 5, red);
    rounded(c, 23, -25, 17, 15, 7, cream);
    rounded(c, 15, -12, 10, 12 + gait * 4, 3, shadow);
    rounded(c, 33, -12, 10, 12 - gait * 4, 3, red);
    rounded(c, 39, -31, 15, 6, 3, shadow);
    for (let i = 0; i < 3; i++) {
      c.fillStyle = "#ffd18c";
      c.beginPath();
      c.moveTo(14, -36 + i * 8);
      c.lineTo(7, -34 + i * 8);
      c.lineTo(13, -30 + i * 8);
      c.fill();
    }
    ellipse(c, 46, -50, 5, 6, "#fff7dc");
    if (s.phase === "crashed") {
      c.strokeStyle = "#392536";
      c.lineWidth = 2;
      c.beginPath();
      c.moveTo(43, -53);
      c.lineTo(49, -47);
      c.moveTo(49, -53);
      c.lineTo(43, -47);
      c.stroke();
    } else ellipse(c, 48, -50, 2.5, 3.5, "#382b38");
    ellipse(c, 53, -39, 3.5, 2, "#fba38b");
  }
  c.restore();
  if (running && !reducedMotion) {
    for (let i = 0; i < 4; i++)
      ellipse(
        c,
        x - ((s.elapsed * 100 + i * 11) % 43),
        GROUND - 3 - i * 2,
        2 + (i % 2),
        2,
        "#fff0c799",
      );
  }
  if (s.phase === "crashed") {
    c.strokeStyle = "#fff0d5";
    c.lineWidth = 3;
    for (let i = 0; i < 7; i++) {
      const a = (i * Math.PI * 2) / 7;
      c.beginPath();
      c.moveTo(x + 38 + Math.cos(a) * 42, y - 30 + Math.sin(a) * 42);
      c.lineTo(x + 38 + Math.cos(a) * 51, y - 30 + Math.sin(a) * 51);
      c.stroke();
    }
  }
  if (s.mode === "sprint" && distance(s) > 970) {
    const flag = 1000 * 16 + PLAYER_X - s.travel;
    c.fillStyle = "#fff3d4";
    c.fillRect(flag, GROUND - 115, 4, 115);
    for (let row = 0; row < 4; row++)
      for (let col = 0; col < 5; col++) {
        c.fillStyle = (row + col) % 2 ? "#5e3948" : "#fff3d4";
        c.fillRect(flag + 4 + col * 9, GROUND - 115 + row * 9, 9, 9);
      }
  }
}
