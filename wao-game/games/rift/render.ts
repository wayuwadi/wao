import { enemyNames } from "./catalog";
import { ROOM_WIDTH, type Encounter, type Enemy } from "./engine";
const clamp = (n: number, lo: number, hi: number) =>
  Math.max(lo, Math.min(hi, n));
function pill(
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
function diamond(
  c: CanvasRenderingContext2D,
  x: number,
  y: number,
  r: number,
  color: string,
) {
  c.fillStyle = color;
  c.beginPath();
  c.moveTo(x, y - r);
  c.lineTo(x + r * 0.65, y);
  c.lineTo(x, y + r);
  c.lineTo(x - r * 0.65, y);
  c.closePath();
  c.fill();
}
function shadow(
  c: CanvasRenderingContext2D,
  x: number,
  ground: number,
  width: number,
  alpha = 0.16,
) {
  c.fillStyle = `rgba(111,61,40,${alpha})`;
  c.beginPath();
  c.ellipse(x, ground + 3, width, 6, 0, 0, Math.PI * 2);
  c.fill();
}
function background(
  c: CanvasRenderingContext2D,
  w: number,
  h: number,
  camera: number,
  chapter: number,
  time: number,
  dark: boolean,
) {
  const sky = c.createLinearGradient(0, 0, 0, h);
  sky.addColorStop(0, dark ? "#383242" : "#fff3d8");
  sky.addColorStop(1, dark ? "#775046" : "#ffd1a0");
  c.fillStyle = sky;
  c.fillRect(0, 0, w, h);
  c.fillStyle = dark ? "#e9c596" : "#ffbd68";
  c.beginPath();
  c.arc(w * 0.8 - camera * 0.03, 72, 34, 0, Math.PI * 2);
  c.fill();
  for (let layer = 0; layer < 2; layer++) {
    const shift = camera * (0.08 + layer * 0.09),
      scale = 270 - layer * 50;
    c.fillStyle = dark
      ? ["#655058", "#906451"][layer]!
      : ["#eac39f", "#e8a76e"][layer]!;
    for (let x = -scale; x < w + scale; x += scale) {
      const px = x - (shift % scale);
      if (chapter === 0) {
        c.beginPath();
        c.moveTo(px, h - 70);
        c.lineTo(px + scale * 0.4, h - 150 - layer * 45);
        c.lineTo(px + scale, h - 70);
        c.fill();
      } else if (chapter === 1) {
        c.fillRect(px + 30, h - 210 + layer * 30, 100, 145);
        c.fillRect(px + 60, h - 248 + layer * 30, 24, 60);
        for (let i = 0; i < 3; i++)
          pill(
            c,
            px + 65 + Math.sin(time + i) * 8,
            h - 270 - i * 17 + layer * 30,
            28,
            14,
            7,
            dark ? "#87727a" : "#f9dfbf",
          );
      } else {
        diamond(
          c,
          px + 60,
          h - 170 - layer * 25,
          55,
          dark ? "#a17b73" : "#efbe91",
        );
        c.fillRect(px + 110, h - 240, 13, 180);
      }
    }
  }
  const ground = h - 65;
  pill(c, 0, ground, w, 65, 0, dark ? "#80533f" : "#efb477");
  c.fillStyle = dark ? "#edbc87" : "#ffe4b8";
  c.fillRect(0, ground, w, 4);
  c.strokeStyle = dark ? "#b47753" : "#db935b";
  c.lineWidth = 2;
  for (let x = -camera % 95; x < w; x += 95) {
    c.beginPath();
    c.moveTo(x, ground + 22);
    c.lineTo(x + 28, ground + 22);
    c.stroke();
    if (chapter === 1) {
      c.beginPath();
      c.moveTo(x + 55, ground + 5);
      c.lineTo(x + 40, ground + 64);
      c.stroke();
    }
  }
}
function hero(
  c: CanvasRenderingContext2D,
  s: Encounter,
  x: number,
  ground: number,
) {
  const p = s.player,
    y = ground - p.z,
    run = Math.sin(s.time * 14) * 5;
  shadow(c, x, ground, 20);
  c.save();
  c.translate(x, y);
  c.scale(p.facing, 1);
  if (p.invulnerable > 0 && Math.floor(s.time * 20) % 2 === 0)
    c.globalAlpha = 0.55;
  if (p.dodge > 0) c.rotate(-0.22);
  // Chunky original companion silhouette: oversized head, scarf, small boots.
  c.fillStyle = s.heroClass === "blade" ? "#da6a35" : "#5d7792";
  c.beginPath();
  c.moveTo(-9, -38);
  c.lineTo(-31, -21 - run);
  c.lineTo(-15, -18);
  c.closePath();
  c.fill();
  pill(c, -13, -37, 26, 27, 7, s.heroClass === "blade" ? "#f39a42" : "#758fa3");
  pill(c, -13, -14, 10, 14 + (p.z === 0 ? run : 0), 3, "#634745");
  pill(c, 4, -14, 10, 14 - (p.z === 0 ? run : 0), 3, "#634745");
  pill(c, -17, -65, 36, 32, 10, "#ffe4b4");
  pill(c, -18, -67, 34, 12, 4, "#714b41");
  pill(c, -15, -58, 10, 10, 2, "#714b41");
  c.fillStyle = "#593f3a";
  c.fillRect(7, -53, 5, 6);
  c.fillStyle = "#ee926d";
  c.fillRect(10, -44, 6, 3);
  pill(c, -17, -36, 35, 7, 2, "#fff0c2");
  if (s.heroClass === "blade") {
    c.save();
    c.translate(15, -27);
    c.rotate(p.action ? -0.9 + p.action.age * 7 : -0.35);
    pill(c, -3, -7, 8, 30, 2, "#81573f");
    pill(c, -6, -38, 12, 34, 2, "#ffeebc");
    c.fillStyle = "#fffbed";
    c.fillRect(2, -37, 3, 30);
    c.restore();
  } else {
    pill(c, 11, -35, 39, 12, 3, "#635761");
    pill(c, 43, -38, 12, 18, 3, "#eeac59");
    diamond(c, 28, -29, 6, "#ccf2ee");
    if (p.charge > 0.12) {
      c.strokeStyle =
        p.charge > 0.62 / s.stats.attackSpeed ? "#fffac3" : "#efbd67";
      c.lineWidth = 3;
      c.beginPath();
      c.arc(52, -29, 8 + p.charge * 7, 0, Math.PI * 2);
      c.stroke();
    }
  }
  if (p.parry > 0 || p.guard > 0.1) {
    c.strokeStyle = "#fff7be";
    c.lineWidth = 4;
    c.beginPath();
    c.arc(8, -30, 38, -0.9, 0.9);
    c.stroke();
  }
  c.restore();
}
function enemy(
  c: CanvasRenderingContext2D,
  e: Enemy,
  x: number,
  ground: number,
  time: number,
) {
  const boss = ["quake", "prism", "mirror", "decoy"].includes(e.kind),
    y = ground - e.z;
  shadow(c, x, ground, boss ? 32 : 19);
  if (e.ward > 0) {
    c.strokeStyle = "#b6e3c9";
    c.lineWidth = 3;
    c.beginPath();
    c.ellipse(x, y - 35, 33, 47, 0, 0, Math.PI * 2);
    c.stroke();
  }
  if (e.kind === "mirror") {
    c.strokeStyle = "#fff3a6";
    c.lineWidth = 5;
    c.beginPath();
    c.ellipse(x, ground, 36, 10, 0, 0, Math.PI * 2);
    c.stroke();
  }
  if (e.kind === "crystal") {
    diamond(
      c,
      x,
      y - 34 + Math.sin(time * 2) * 4,
      31,
      e.flash > 0 ? "#fff8da" : "#84c8d1",
    );
    diamond(c, x - 3, y - 37, 17, "#d9f6eb");
    return;
  }
  c.save();
  c.translate(x, y);
  c.scale(e.facing, 1);
  const body =
    e.flash > 0
      ? "#fff5d0"
      : e.kind === "healer"
        ? "#739b7d"
        : e.kind === "prism"
          ? "#b2afb0"
          : e.kind === "shield" || e.kind === "quake"
            ? "#9b7964"
            : "#b56d50";
  if (e.kind === "prism") {
    diamond(c, 0, -47, 54, body);
    diamond(c, 0, -47, 25, "#ffd476");
    c.fillStyle = "#75585a";
    c.fillRect(-9, -51, 18, 8);
    if (e.shieldWindow === 0) {
      c.strokeStyle = "#a5e4e3";
      c.lineWidth = 4;
      c.beginPath();
      c.arc(0, -47, 59, time, time + Math.PI * 1.7);
      c.stroke();
    }
  } else {
    const scale = boss ? 1.38 : 1;
    c.scale(scale, scale);
    pill(c, -17, -36, 34, 29, 7, body);
    pill(c, -16, -11, 12, 12, 3, "#73523e");
    pill(c, 5, -11, 12, 12, 3, "#73523e");
    pill(c, -20, -62, 40, 31, 7, body);
    pill(c, -22, -64, 44, 10, 3, "#765649");
    c.fillStyle = "#ffefb5";
    c.fillRect(4, -52, 9, 7);
    if (e.kind === "shield") {
      pill(c, 16, -46, 13, 43, 3, "#647a84");
      pill(c, 19, -39, 7, 20, 2, "#c9d6bb");
    } else if (e.kind === "sniper") {
      pill(c, 12, -35, 39, 10, 3, "#775d5a");
      c.fillStyle = "#efc585";
      c.fillRect(45, -38, 6, 16);
    } else if (e.kind === "bomber") {
      c.fillStyle = "#635658";
      c.beginPath();
      c.arc(22, -30, 13, 0, Math.PI * 2);
      c.fill();
      c.fillStyle = "#ffd391";
      c.fillRect(20, -48, 4, 8);
    } else if (e.kind === "healer") {
      pill(c, 23, -53, 5, 50, 1, "#bca178");
      diamond(c, 25, -57, 12, "#c1ebac");
    } else if (e.kind === "quake") {
      pill(c, 20, -33, 24, 28, 5, "#6e6259");
      pill(c, 18, -38, 30, 10, 2, "#dec39a");
    } else {
      c.fillStyle = "#eed4a0";
      c.beginPath();
      c.moveTo(17, -25);
      c.lineTo(42, -47);
      c.lineTo(34, -12);
      c.closePath();
      c.fill();
    }
    if (e.elite) {
      c.fillStyle = "#fff0a0";
      c.beginPath();
      c.moveTo(-15, -68);
      c.lineTo(-17, -80);
      c.lineTo(-5, -74);
      c.lineTo(2, -85);
      c.lineTo(8, -74);
      c.lineTo(19, -80);
      c.lineTo(16, -68);
      c.closePath();
      c.fill();
    }
  }
  c.restore();
  const bw = boss ? 80 : 50,
    by = y - (boss ? 106 : e.elite ? 92 : 78);
  pill(c, x - bw / 2, by, bw, 5, 2, "#a47860");
  pill(
    c,
    x - bw / 2,
    by,
    (bw * e.hp) / e.maxHp,
    5,
    2,
    e.kind === "healer" ? "#bddea3" : "#fff0b5",
  );
  c.textAlign = "center";
  c.font = "11px system-ui";
  c.fillStyle = "#714b3e";
  c.fillText(
    (e.elite ? "精英 " : "") +
      (e.kind === "mirror" || e.kind === "decoy"
        ? "映光残影"
        : enemyNames[e.kind]),
    x,
    by - 6,
  );
  if (e.stun > 0.2) {
    c.fillStyle = "#fff1a4";
    c.font = "17px system-ui";
    c.fillText("✦", x, by - 23);
  }
}
export function renderRift(
  canvas: HTMLCanvasElement,
  s: Encounter,
  dark = false,
  reduced = false,
) {
  const c = canvas.getContext("2d");
  if (!c) return;
  const w = Math.max(280, canvas.clientWidth),
    h = Math.max(280, canvas.clientHeight),
    dpr = Math.min(2, window.devicePixelRatio || 1);
  if (
    canvas.width !== Math.round(w * dpr) ||
    canvas.height !== Math.round(h * dpr)
  ) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }
  c.setTransform(dpr, 0, 0, dpr, 0, 0);
  c.imageSmoothingEnabled = false;
  const camera = clamp(s.player.x - w * 0.4, 0, Math.max(0, ROOM_WIDTH - w)),
    ground = h - 65;
  background(c, w, h, camera, s.checkpoint.chapter, reduced ? 0 : s.time, dark);
  for (const entry of s.incoming) {
    const x = clamp(entry.x - camera, 22, w - 22);
    c.strokeStyle = "#b36b7e";
    c.lineWidth = 3;
    c.setLineDash([7, 5]);
    c.strokeRect(x - 17, ground - 85, 34, 85);
    c.setLineDash([]);
    c.fillStyle = dark ? "#ffe4c2" : "#9e4b60";
    c.font = "bold 12px system-ui";
    c.textAlign = "center";
    c.fillText("增援", x, ground - 94);
  }
  for (const e of s.enemies) {
    if (e.hp <= 0 || e.state !== "windup") continue;
    c.fillStyle = "rgba(220,91,55,.18)";
    c.strokeStyle = "#c95433";
    c.lineWidth = 2;
    const x = e.x - camera;
    if (e.kind === "quake" && e.pattern % 2 === 0) {
      c.fillRect(0, ground - 7, w, 11);
      c.setLineDash([8, 5]);
      c.strokeRect(1, ground - 10, w - 2, 14);
      c.setLineDash([]);
    } else if (
      ["leaper", "bomber", "mirror", "decoy"].includes(e.kind) ||
      (e.elite && e.kind === "chaser")
    ) {
      c.beginPath();
      c.ellipse(e.target - camera, ground, 85, 12, 0, 0, Math.PI * 2);
      c.fill();
      c.stroke();
    } else if (
      e.kind === "sniper" ||
      e.kind === "prism" ||
      e.kind === "healer"
    ) {
      c.setLineDash([7, 6]);
      c.beginPath();
      c.moveTo(x, ground - 35);
      c.lineTo(s.player.x - camera, ground - s.player.z - 28);
      c.stroke();
      c.setLineDash([]);
    } else {
      const target = e.kind === "quake" ? e.target - camera : x + e.facing * 95;
      c.fillRect(Math.min(x, target), ground - 68, Math.abs(target - x), 68);
    }
    if (x > -50 && x < w + 50) {
      c.fillStyle = "#af492f";
      c.textAlign = "center";
      c.font = "bold 20px system-ui";
      c.fillText("!", x, ground - e.z - 110);
    }
  }
  for (const hzd of s.hazards) {
    c.fillStyle =
      hzd.team === "hero" ? "rgba(233,177,53,.2)" : "rgba(230,92,63,.22)";
    c.strokeStyle = hzd.team === "hero" ? "#cd9b35" : "#cc573e";
    c.lineWidth = 2;
    c.beginPath();
    c.ellipse(
      hzd.x - camera,
      ground,
      hzd.radius * Math.min(1, 0.4 + hzd.age / hzd.delay),
      14,
      0,
      0,
      Math.PI * 2,
    );
    c.fill();
    c.stroke();
  }
  for (const e of s.enemies)
    if (e.hp > 0 && e.x - camera > -110 && e.x - camera < w + 110)
      enemy(c, e, e.x - camera, ground, reduced ? 0 : s.time);
  hero(c, s, s.player.x - camera, ground);
  for (const p of s.projectiles) {
    c.fillStyle = p.team === "hero" ? "#fff4b2" : "#d86143";
    c.strokeStyle = p.team === "hero" ? "#e4a940" : "#ffdb95";
    c.lineWidth = 2;
    c.beginPath();
    c.ellipse(
      p.x - camera,
      ground - p.z,
      p.radius * 1.8,
      p.radius,
      Math.atan2(-p.vz, p.vx),
      0,
      Math.PI * 2,
    );
    c.fill();
    c.stroke();
  }
  for (const e of s.effects) {
    const ratio = e.age / e.life,
      x = e.x - camera,
      y = ground - e.z;
    c.save();
    c.globalAlpha = 1 - ratio;
    c.strokeStyle = e.color;
    c.fillStyle = e.color;
    if (e.kind === "slash") {
      c.lineWidth = 9 * (1 - ratio);
      c.beginPath();
      c.arc(
        x,
        y,
        47 + ratio * 15,
        e.text === "环" ? 0 : e.text === "1" ? -1.1 : 2,
        e.text === "环" ? Math.PI * 2 : e.text === "1" ? 1.1 : 4.3,
      );
      c.stroke();
    } else if (e.kind === "burst" || e.kind === "parry") {
      c.lineWidth = 3;
      c.beginPath();
      c.arc(x, y, 16 + ratio * 45, 0, Math.PI * 2);
      c.stroke();
      for (let n = 0; n < 6; n++)
        c.fillRect(
          x + Math.cos(n) * ratio * 48,
          y + Math.sin(n) * ratio * 38,
          5,
          5,
        );
    } else if (e.kind === "dash") {
      c.lineWidth = 6;
      c.beginPath();
      c.moveTo(x - 60, y - 25);
      c.lineTo(x + 35, y - 25);
      c.stroke();
    }
    if (e.text && !["slash"].includes(e.kind)) {
      c.font = "bold 16px system-ui";
      c.textAlign = "center";
      c.strokeStyle = "#81523b";
      c.lineWidth = 3;
      c.strokeText(e.text, x, y - ratio * 40);
      c.fillText(e.text, x, y - ratio * 40);
    }
    c.restore();
  }
  const outside = s.enemies.filter(
    (e) =>
      e.hp > 0 &&
      !["crystal", "decoy"].includes(e.kind) &&
      (e.x < camera || e.x > camera + w),
  );
  if (outside.length) {
    const next = outside.reduce((a, b) =>
      Math.abs(a.x - s.player.x) < Math.abs(b.x - s.player.x) ? a : b,
    );
    const right = next.x > camera + w;
    c.font = "bold 13px system-ui";
    c.textAlign = right ? "right" : "left";
    c.fillStyle = dark ? "#ffe2b9" : "#8a573d";
    c.fillText(right ? "敌人 →" : "← 敌人", right ? w - 12 : 12, h * 0.48);
  }
}
