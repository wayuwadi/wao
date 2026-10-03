export type HeroClass = "blade" | "lancer";
export type EnemyKind =
  | "chaser"
  | "sniper"
  | "shield"
  | "leaper"
  | "bomber"
  | "healer"
  | "quake"
  | "prism"
  | "mirror"
  | "decoy"
  | "crystal";
export type GearId = (typeof equipment)[number]["id"];
export const heroNames = { blade: "剑刃", lancer: "星枪" } as const;
export const chapters = [
  {
    name: "琥珀峡谷",
    subtitle: "听见鼓点，就跳起来",
    boss: "岩鼓守卫",
    color: "#f2a04b",
    rooms: ["巡风小径", "岩门哨站", "回声擂台"],
  },
  {
    name: "棱镜工坊",
    subtitle: "先断供能，再攻核心",
    boss: "棱镜机核",
    color: "#e8ae52",
    rooms: ["齿轮长廊", "熔光车间", "双晶核心"],
  },
  {
    name: "映光回廊",
    subtitle: "脚下金环，指向真身",
    boss: "映光游侠",
    color: "#f08b57",
    rooms: ["落星平台", "织光庭院", "三重幻镜"],
  },
] as const;

export const equipment = [
  {
    id: "blade-balanced",
    name: "晨光短刃",
    slot: "weapon",
    hero: "blade",
    tier: 0,
    power: 0,
    speed: 1,
    reach: 0,
    description: "均衡连击，无额外代价。",
  },
  {
    id: "blade-heavy",
    name: "岩鸣重刃",
    slot: "weapon",
    hero: "blade",
    tier: 1,
    power: 7,
    speed: 0.76,
    reach: 18,
    description: "伤害与范围提高，挥刀更慢。",
  },
  {
    id: "blade-swift",
    name: "流风双刃",
    slot: "weapon",
    hero: "blade",
    tier: 1,
    power: -3,
    speed: 1.42,
    reach: -8,
    description: "连击迅速，单次伤害与距离降低。",
  },
  {
    id: "lancer-scout",
    name: "拾星轻枪",
    slot: "weapon",
    hero: "lancer",
    tier: 0,
    power: 0,
    speed: 1,
    reach: 0,
    description: "蓄力与移动均衡。",
  },
  {
    id: "lancer-charge",
    name: "曜核长枪",
    slot: "weapon",
    hero: "lancer",
    tier: 1,
    power: 6,
    speed: 0.78,
    reach: 0,
    description: "满蓄力额外贯穿，准备较慢。",
  },
  {
    id: "lancer-spread",
    name: "萤火散枪",
    slot: "weapon",
    hero: "lancer",
    tier: 2,
    power: -4,
    speed: 1.15,
    reach: 0,
    description: "三束扩散，近距离命中更多。",
  },
  {
    id: "charm-shell",
    name: "暖岩护符",
    slot: "charm",
    hero: "all",
    tier: 0,
    power: 0,
    speed: 1,
    reach: 0,
    description: "生命+28，受伤减20%，移动略慢。",
  },
  {
    id: "charm-feather",
    name: "风羽护符",
    slot: "charm",
    hero: "all",
    tier: 0,
    power: -1,
    speed: 1,
    reach: 0,
    description: "闪避耗力减少，移速提高，伤害略降。",
  },
  {
    id: "charm-spring",
    name: "清泉护符",
    slot: "charm",
    hero: "all",
    tier: 1,
    power: -2,
    speed: 1,
    reach: 0,
    description: "能量恢复更快，普攻伤害略降。",
  },
  {
    id: "charm-ember",
    name: "余烬护符",
    slot: "charm",
    hero: "all",
    tier: 1,
    power: 0,
    speed: 1,
    reach: 0,
    description: "命中持续灼烧，最大生命-12。",
  },
  {
    id: "charm-glass",
    name: "晴晶护符",
    slot: "charm",
    hero: "all",
    tier: 2,
    power: 6,
    speed: 1,
    reach: 0,
    description: "伤害+6，受到的伤害增加25%。",
  },
  {
    id: "charm-clock",
    name: "回响护符",
    slot: "charm",
    hero: "all",
    tier: 2,
    power: -2,
    speed: 1,
    reach: 0,
    description: "技能冷却缩短25%，普攻伤害略降。",
  },
] as const;
export const skills = {
  blade: [
    {
      name: "见招拆招",
      short: "弹反",
      cost: 14,
      cooldown: 4,
      description: "0.28秒弹反窗口。成功化解伤害，反击并震慑敌人。",
    },
    {
      name: "升龙斩",
      short: "升龙",
      cost: 22,
      cooldown: 5,
      description: "跃起攻击近处高低目标，挑飞普通敌人。",
    },
    {
      name: "回旋风",
      short: "旋风",
      cost: 30,
      cooldown: 7,
      description: "环身多段攻击，适合被包围时破局。",
    },
    {
      name: "穿风刺",
      short: "突刺",
      cost: 20,
      cooldown: 4.5,
      description: "向前冲刺并刺击，穿过敌人转移站位。",
    },
    {
      name: "震刃波",
      short: "震刃",
      cost: 26,
      cooldown: 6,
      description: "沿地面发出贯穿冲击，射程长但不能打高处。",
    },
    {
      name: "不屈战意",
      short: "战意",
      cost: 35,
      cooldown: 12,
      description: "短暂减伤并恢复生命，输出期间需保留能量。",
    },
  ],
  lancer: [
    {
      name: "贯星射线",
      short: "贯穿",
      cost: 24,
      cooldown: 5,
      description: "宽直线贯穿弹，能绕过普通盾卫的正面减伤。",
    },
    {
      name: "星火地雷",
      short: "地雷",
      cost: 22,
      cooldown: 5,
      description: "留下延时爆炸，引诱追击者靠近。",
    },
    {
      name: "闪移射击",
      short: "闪移",
      cost: 20,
      cooldown: 4.5,
      description: "向移动方向闪移并射击，带短暂无敌。",
    },
    {
      name: "流星弹幕",
      short: "弹幕",
      cost: 34,
      cooldown: 8,
      description: "扇形多发弹丸，贴近目标可集中命中。",
    },
    {
      name: "牵引涡流",
      short: "涡流",
      cost: 30,
      cooldown: 7,
      description: "在前方聚拢并迟缓普通敌人，创造蓄力空间。",
    },
    {
      name: "星核过载",
      short: "过载",
      cost: 32,
      cooldown: 12,
      description: "数秒内更快蓄力并提高枪弹伤害。",
    },
  ],
} as const;

export const enemyNames: Record<EnemyKind, string> = {
  chaser: "追击者",
  sniper: "弧光射手",
  shield: "盾卫",
  leaper: "跃袭者",
  bomber: "爆弹手",
  healer: "织光医师",
  quake: "岩鼓守卫",
  prism: "棱镜机核",
  mirror: "映光游侠",
  decoy: "映光分身",
  crystal: "供能晶体",
};
export const roomEnemies: {
  kind: EnemyKind;
  x: number;
  elite?: boolean;
}[][][] = [
  [
    [
      { kind: "chaser", x: 490 },
      { kind: "sniper", x: 1000 },
      { kind: "chaser", x: 1260 },
    ],
    [
      { kind: "chaser", x: 550, elite: true },
      { kind: "sniper", x: 1100 },
      { kind: "leaper", x: 1360 },
    ],
    [{ kind: "quake", x: 1040 }],
  ],
  [
    [
      { kind: "shield", x: 550 },
      { kind: "leaper", x: 900 },
      { kind: "sniper", x: 1330 },
    ],
    [
      { kind: "sniper", x: 750, elite: true },
      { kind: "shield", x: 1140 },
      { kind: "bomber", x: 1350 },
    ],
    [
      { kind: "prism", x: 920 },
      { kind: "crystal", x: 400 },
      { kind: "crystal", x: 1360 },
    ],
  ],
  [
    [
      { kind: "bomber", x: 620 },
      { kind: "leaper", x: 920 },
      { kind: "healer", x: 1260 },
    ],
    [
      { kind: "healer", x: 1200, elite: true },
      { kind: "shield", x: 640 },
      { kind: "bomber", x: 980 },
    ],
    [
      { kind: "mirror", x: 760 },
      { kind: "decoy", x: 1080 },
      { kind: "decoy", x: 1380 },
    ],
  ],
];

/** Second encounters change the attack direction and target priorities. */
export const reinforcements: {
  kind: EnemyKind;
  offset: number;
  elite?: boolean;
}[][][] = [
  [
    [],
    [
      { kind: "chaser", offset: -280 },
      { kind: "sniper", offset: 400 },
    ],
  ],
  [
    [
      { kind: "shield", offset: 280 },
      { kind: "healer", offset: 520 },
      { kind: "bomber", offset: -350 },
    ],
    [
      { kind: "leaper", offset: -320 },
      { kind: "bomber", offset: 340 },
      { kind: "sniper", offset: -550, elite: true },
    ],
  ],
  [
    [
      { kind: "shield", offset: 300 },
      { kind: "healer", offset: 510, elite: true },
      { kind: "leaper", offset: -330 },
    ],
    [
      { kind: "chaser", offset: -300, elite: true },
      { kind: "bomber", offset: 370 },
      { kind: "sniper", offset: -530 },
    ],
  ],
];
