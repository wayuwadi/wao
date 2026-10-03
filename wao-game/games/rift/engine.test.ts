import { describe, expect, it } from "vitest";
import {
  attackPressed,
  attackReleased,
  beginEncounter,
  castSkill,
  createEncounter,
  createEnemy,
  dodge,
  hurtPlayer,
  jump,
  pauseEncounter,
  stepEncounter,
  type Encounter,
  type RiftControls,
} from "./engine";
import {
  cloneMemory,
  freshRiftMemory,
  isRiftMemory,
  rewardRoom,
  startChapter,
} from "./save";
const idle: RiftControls = {
  left: false,
  right: false,
  down: false,
  attackHeld: false,
};
function encounter(hero: "blade" | "lancer" = "blade", chapter = 0, room = 0) {
  const memory = freshRiftMemory();
  memory.selected = hero;
  const s = createEncounter(memory, { chapter, room, seed: 42, attempt: 1 });
  beginEncounter(s);
  return s;
}
function advance(s: Encounter, seconds: number, controls = idle) {
  for (let n = 0; n < Math.round(seconds * 120); n++)
    stepEncounter(s, 1 / 120, controls);
}

describe("实际空间战斗", () => {
  it("突刺改变站位、震刃只击中低处、战意恢复与减伤且受资源限制", () => {
    const s = encounter();
    s.ranks = [1, 1, 1, 1, 1, 1];
    s.slots = [3, 4, 5];
    s.enemies = [
      createEnemy("shield", 310, 2, 10),
      createEnemy("shield", 540, 2, 11),
    ];
    s.enemies.forEach((e) => {
      e.stun = 5;
    });
    const oldX = s.player.x;
    expect(castSkill(s, 0)).toBe(true);
    expect(s.player.x).toBeGreaterThan(oldX + 100);
    expect(s.enemies[0]!.hp).toBeLessThan(s.enemies[0]!.maxHp);
    expect(castSkill(s, 0)).toBe(false);
    s.enemies[0]!.x = s.player.x + 160;
    s.enemies[0]!.z = 100;
    const airHp = s.enemies[0]!.hp;
    expect(castSkill(s, 1)).toBe(true);
    advance(s, 0.5);
    expect(s.enemies[0]!.hp).toBe(airHp);
    expect(s.enemies[1]!.hp).toBeLessThan(s.enemies[1]!.maxHp);
    s.player.hp = 40;
    s.player.energy = 0;
    expect(castSkill(s, 2)).toBe(false);
    expect(s.player.hp).toBe(40);
    s.player.energy = 100;
    expect(castSkill(s, 2)).toBe(true);
    expect(s.player.hp).toBeGreaterThan(40);
    const before = s.player.hp;
    s.player.invulnerable = 0;
    hurtPlayer(s, 20);
    expect(before - s.player.hp).toBeLessThan(20);
  });
  it("地雷延时命中、闪移提供位移保护、弹幕散射与单线枪弹不同", () => {
    const s = encounter("lancer");
    s.ranks = [1, 1, 1, 1, 1, 1];
    s.slots = [1, 2, 3];
    s.enemies = [createEnemy("shield", s.player.x + 70, 2, 10)];
    const e = s.enemies[0]!;
    e.stun = 5;
    castSkill(s, 0);
    advance(s, 0.7);
    expect(e.hp).toBe(e.maxHp);
    advance(s, 0.25);
    expect(e.hp).toBeLessThan(e.maxHp);
    const x = s.player.x;
    castSkill(s, 1);
    expect(s.player.x).toBeGreaterThan(x + 150);
    expect(hurtPlayer(s, 20)).toBe(false);
    s.player.energy = 100;
    s.projectiles = [];
    expect(castSkill(s, 2)).toBe(true);
    expect(new Set(s.projectiles.map((p) => p.vz)).size).toBe(5);
    expect(s.projectiles.every((p) => p.vx > 0)).toBe(true);
  });
  it("涡流拉拢普通敌人但不移动首领；过载缩短蓄力而非免费全屏伤害", () => {
    const s = encounter("lancer");
    s.ranks = [1, 1, 1, 1, 1, 1];
    s.slots = [4, 5, 0];
    s.enemies = [
      createEnemy("shield", 450, 2, 10),
      createEnemy("quake", 480, 0, 11),
    ];
    castSkill(s, 0);
    expect(s.enemies[0]!.x).toBeLessThan(450);
    expect(s.enemies[0]!.stun).toBeGreaterThan(0);
    expect(s.enemies[1]!.x).toBe(480);
    const normal = encounter("lancer");
    castSkill(s, 1);
    advance(s, 0.3, { ...idle, attackHeld: true });
    advance(normal, 0.3, { ...idle, attackHeld: true });
    expect(s.player.charge).toBeGreaterThan(normal.player.charge);
    expect(s.player.energy).toBeLessThan(normal.player.energy);
  });
  it("贴身射击仍命中，枪口不能越过重叠敌人的碰撞盒", () => {
    const s = encounter("lancer");
    s.enemies = [s.enemies[0]!];
    const e = s.enemies[0]!;
    e.x = s.player.x;
    e.stun = 2;
    attackReleased(s);
    advance(s, 0.03);
    expect(e.hp).toBeLessThan(e.maxHp);
  });
  it("精英支援提供短时护盾，必须改变优先击杀顺序或使用贯穿", () => {
    const s = encounter();
    const healer = createEnemy("healer", 400, 1, 8, true),
      ally = createEnemy("chaser", 330, 1, 9);
    s.enemies = [healer, ally];
    healer.state = "windup";
    healer.age = 0.94;
    ally.stun = 4;
    advance(s, 0.03);
    expect(ally.ward).toBeGreaterThan(3);
    s.player.x = ally.x - 55;
    attackPressed(s);
    advance(s, 0.12);
    expect(ally.maxHp - ally.hp).toBeLessThan(s.stats.power);
  });
  it("冲撞沿连续轨迹运动，前摇结束不能瞬移到锁定点", () => {
    const s = encounter("blade", 0, 2),
      boss = s.enemies[0]!;
    boss.state = "windup";
    boss.pattern = 1;
    boss.age = 0.86;
    boss.target = s.player.x;
    const before = boss.x;
    advance(s, 0.02);
    expect(boss.x).toBeGreaterThan(s.player.x + 200);
    expect(boss.x).toBeLessThanOrEqual(before);
    advance(s, 0.18);
    expect(boss.x).toBeLessThan(before - 100);
  });
  it("普攻有方向距离和前摇，三段连击有不同冲击", () => {
    const s = encounter();
    const enemy = s.enemies[0]!;
    enemy.x = 600;
    attackPressed(s);
    advance(s, 0.12);
    expect(enemy.hp).toBe(enemy.maxHp);
    advance(s, 0.3);
    enemy.x = s.player.x + 62;
    const before = enemy.hp;
    attackPressed(s);
    advance(s, 0.02);
    expect(enemy.hp).toBe(before);
    advance(s, 0.09);
    expect(enemy.hp).toBeLessThan(before);
    expect(s.player.combo).toBe(2);
    advance(s, 0.3);
    enemy.x = s.player.x + 65;
    attackPressed(s);
    advance(s, 0.12);
    expect(s.player.combo).toBe(3);
    expect(enemy.x).toBeGreaterThan(s.player.x + 95);
  });
  it("星枪蓄力和短射不同，弹丸必须飞到敌人处才造成伤害", () => {
    const short = encounter("lancer"),
      charged = encounter("lancer");
    for (const s of [short, charged]) {
      s.enemies = [s.enemies[0]!];
      s.enemies[0]!.x = 470;
      s.enemies[0]!.stun = 3;
    }
    advance(short, 0.1, { ...idle, attackHeld: true });
    attackReleased(short);
    advance(charged, 0.7, { ...idle, attackHeld: true });
    attackReleased(charged);
    expect(short.enemies[0]!.hp).toBe(short.enemies[0]!.maxHp);
    expect(charged.projectiles[0]!.damage).toBeGreaterThan(
      short.projectiles[0]!.damage * 2,
    );
    advance(short, 0.7);
    advance(charged, 0.7);
    expect(charged.enemies[0]!.hp).toBeLessThan(short.enemies[0]!.hp);
  });
  it("闪避消耗有限体力，弹反窗口能反击而不是永久无敌", () => {
    const s = encounter(),
      enemy = s.enemies[0]!;
    expect(dodge(s)).toBe(true);
    const hp = s.player.hp;
    hurtPlayer(s, 30, enemy);
    expect(s.player.hp).toBe(hp);
    expect(dodge(s)).toBe(false);
    advance(s, 0.3);
    s.player.stamina = 0;
    expect(dodge(s)).toBe(false);
    expect(castSkill(s, 0)).toBe(true);
    hurtPlayer(s, 20, enemy);
    expect(s.parries).toBe(1);
    expect(enemy.hp).toBeLessThan(enemy.maxHp);
    expect(s.player.hp).toBe(hp);
    advance(s, 0.5);
    hurtPlayer(s, 20, enemy);
    expect(s.player.hp).toBeLessThan(hp);
  });
  it("暂停不推进任何战斗时间，恢复前不会命中或持续蓄力", () => {
    const s = encounter("lancer");
    advance(s, 0.2, { ...idle, attackHeld: true });
    pauseEncounter(s);
    const before = JSON.stringify(s);
    advance(s, 10, { ...idle, right: true, attackHeld: true });
    expect(JSON.stringify(s)).toBe(before);
    expect(s.player.charge).toBe(0);
    beginEncounter(s);
    advance(s, 0.1, { ...idle, right: true });
    expect(s.time).toBeLessThan(0.4);
  });
});
describe("三个机制首领", () => {
  it("贯穿弹不能穿过供能主核或分身，必须重新选择站位", () => {
    const prism = encounter("lancer", 1, 2);
    prism.player.x = 700;
    castSkill(prism, 0);
    advance(prism, 1);
    expect(
      prism.enemies.find((e) => e.kind === "crystal" && e.x > 1000)!.hp,
    ).toBe(30);
    const mirror = encounter("lancer", 2, 2);
    const real = mirror.enemies[0]!,
      fake = mirror.enemies[1]!;
    fake.x = 400;
    real.x = 600;
    real.stun = 3;
    castSkill(mirror, 0);
    advance(mirror, 1);
    expect(real.hp).toBe(real.maxHp);
    expect(mirror.mechanism.decoyHits).toBe(1);
  });
  it("震地对地面造成伤害，真正跳起越过震波", () => {
    const ground = encounter("blade", 0, 2),
      air = encounter("blade", 0, 2);
    for (const s of [ground, air]) {
      s.enemies[0]!.state = "windup";
      s.enemies[0]!.age = 0.75;
    }
    jump(air);
    advance(ground, 0.3);
    advance(air, 0.3);
    expect(ground.player.hp).toBeLessThan(ground.player.maxHp);
    expect(air.player.hp).toBe(air.player.maxHp);
    expect(air.mechanism.quakesAvoided).toBe(1);
  });
  it("供能晶体存在时主核不掉血，击破后有有限输出窗口", () => {
    const s = encounter("lancer", 1, 2),
      boss = s.enemies[0]!;
    s.player.x = boss.x - 110;
    castSkill(s, 0);
    advance(s, 0.3);
    expect(boss.hp).toBe(boss.maxHp);
    for (const e of s.enemies) if (e.kind === "crystal") e.hp = 0;
    advance(s, 0.1);
    expect(s.mechanism.shieldsBroken).toBe(1);
    expect(boss.shieldWindow).toBeGreaterThan(6);
    s.player.cooldowns[0] = 0;
    castSkill(s, 0);
    advance(s, 0.3);
    expect(boss.hp).toBeLessThan(boss.maxHp);
    s.player.invulnerable = 20;
    advance(s, 7);
    expect(
      s.enemies.filter((e) => e.kind === "crystal" && e.hp > 0),
    ).toHaveLength(2);
  });
  it("错打分身产生反击，打真身才推进首领生命", () => {
    const mirror = encounter("blade", 2, 2);
    const real = mirror.enemies[0]!,
      fake = mirror.enemies[1]!;
    mirror.player.x = fake.x - 65;
    attackPressed(mirror);
    advance(mirror, 0.12);
    expect(mirror.mechanism.decoyHits).toBe(1);
    expect(real.hp).toBe(real.maxHp);
    expect(mirror.projectiles.some((p) => p.team === "enemy")).toBe(true);
    advance(mirror, 0.3);
    mirror.player.x = real.x - 65;
    attackPressed(mirror);
    advance(mirror, 0.12);
    expect(real.hp).toBeLessThan(real.maxHp);
    expect(mirror.mechanism.trueHits).toBe(1);
  });
});
describe("成长和安全检查点", () => {
  it("增援有可见预警，暂停不缩短倒计时，两次遭遇全清才结算", () => {
    const s = encounter("blade", 1, 0);
    for (const e of s.enemies) e.hp = 0;
    advance(s, 0.01);
    expect(s.phase).toBe("running");
    expect(s.incoming).toHaveLength(3);
    expect(s.incoming.some((e) => e.kind === "healer")).toBe(true);
    pauseEncounter(s);
    const remaining = s.arrival;
    advance(s, 5);
    expect(s.arrival).toBe(remaining);
    beginEncounter(s);
    advance(s, 1.6);
    expect(s.wave).toBe(2);
    expect(s.enemies).toHaveLength(3);
    for (const e of s.enemies) e.hp = 0;
    advance(s, 0.02);
    expect(s.phase).toBe("won");
  });
  it("首发记忆严格限制装备引用、技能点、章节和数值", () => {
    const m = freshRiftMemory();
    expect(isRiftMemory(m)).toBe(true);
    const bad = cloneMemory(m);
    bad.heroes.blade.ranks = [3, 3, 3, 3, 3, 3];
    expect(isRiftMemory(bad)).toBe(false);
    const wrong = cloneMemory(m);
    wrong.heroes.blade.weapon = "lancer-scout";
    expect(isRiftMemory(wrong)).toBe(false);
    expect(isRiftMemory({ ...m, materials: Infinity })).toBe(false);
    expect(isRiftMemory({ ...m, script: "<script>bad</script>" })).toBe(false);
    expect(() => startChapter(m, 1, 42)).toThrow("先通关");
  });
  it("结算原子推进检查点，同一房间奖励不重复领取", () => {
    const m = startChapter(freshRiftMemory(), 0, 42),
      cp = m.checkpoint!;
    const first = rewardRoom(m, cp, 12000);
    expect(first.memory.checkpoint?.room).toBe(1);
    expect(first.memory.heroes.blade.xp).toBe(28);
    const again = rewardRoom(first.memory, cp, 12000);
    expect(again.duplicate).toBe(true);
    expect(again.memory).toBe(first.memory);
    expect(isRiftMemory(first.memory)).toBe(true);
    expect(m.heroes.blade.xp).toBe(0);
  });
});
