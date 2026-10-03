export const beastIds = [
  "sprig",
  "moss",
  "moth",
  "fin",
  "otter",
  "crab",
  "fox",
  "cinder",
  "sparrow",
  "owl",
  "lynx",
  "bud",
] as const;
export type BeastId = (typeof beastIds)[number];
export type Role = "attack" | "guard" | "support" | "control";
export type Bond = "bloom" | "tide" | "ember" | "echo";
export type Terrain = "meadow" | "water" | "ridge" | "moon";
export const roleNames: Record<Role, string> = {
  attack: "攻击",
  guard: "守护",
  support: "支援",
  control: "控制",
};
export const bonds: Record<
  Bond,
  { name: string; color: string; hint: string }
> = {
  bloom: {
    name: "生机相伴",
    color: "#659b5b",
    hint: "两只生机兽：每4秒全队恢复生命，受伤越重越受益。",
  },
  tide: {
    name: "潮汐相守",
    color: "#4b9ea5",
    hint: "两只潮汐兽：每5秒净化灼烧和毒，并转化成护盾。",
  },
  ember: {
    name: "暖焰连心",
    color: "#ce8a4e",
    hint: "两只暖焰兽：技能伤害点燃；燃烧目标被击倒会引爆余烬。",
  },
  echo: {
    name: "回声合拍",
    color: "#9690be",
    hint: "两只回声兽：每第三次技能令集火目标沉默，延后蓄力。",
  },
};
export interface Species {
  id: BeastId;
  name: string;
  role: Role;
  bond: Bond;
  region: number;
  rare: boolean;
  hp: number;
  attack: number;
  armor: number;
  interval: number;
  cooldown: number;
  color: string;
  shape: "ears" | "shell" | "wings" | "fins" | "tail" | "horn";
  skill: string;
  hint: string;
  trait: string;
  branches: [string, string];
  branchHints: [string, string];
  evolution?: { name: string; hint: string; shape: Species["shape"] };
}
export const species: Record<BeastId, Species> = {
  sprig: {
    id: "sprig",
    name: "芽绒鹿",
    role: "attack",
    bond: "bloom",
    region: 0,
    rare: false,
    hp: 110,
    attack: 13,
    armor: 3,
    interval: 1.3,
    cooldown: 5,
    color: "#91bb75",
    shape: "horn",
    skill: "连生枝",
    hint: "连续两击，附加毒。",
    trait: "攻击受毒目标回复少量生命。",
    branches: ["繁枝", "回芽"],
    branchHints: ["技能多一次追击。", "受毒目标返还更多生命，技能冷却更短。"],
    evolution: {
      name: "冠叶灵鹿",
      hint: "连生枝穿透护盾，第二击溅射。",
      shape: "horn",
    },
  },
  moss: {
    id: "moss",
    name: "苔团熊",
    role: "guard",
    bond: "bloom",
    region: 0,
    rare: false,
    hp: 170,
    attack: 8,
    armor: 6,
    interval: 1.7,
    cooldown: 7,
    color: "#7fa984",
    shape: "ears",
    skill: "藤甲抱抱",
    hint: "全队护盾，并嘲讽敌人。",
    trait: "生命低于一半时护盾增加。",
    branches: ["厚苔", "护芽"],
    branchHints: ["护盾更厚，并反击攻击者。", "为最虚弱伙伴额外恢复生命。"],
    evolution: {
      name: "森冠巨绒",
      hint: "护盾附带全队减伤，藤甲延长嘲讽。",
      shape: "horn",
    },
  },
  moth: {
    id: "moth",
    name: "绒梦蛾",
    role: "control",
    bond: "echo",
    region: 0,
    rare: true,
    hp: 95,
    attack: 9,
    armor: 2,
    interval: 1.4,
    cooldown: 6,
    color: "#b2aacb",
    shape: "wings",
    skill: "梦粉轻落",
    hint: "沉默目标，打断其蓄力。",
    trait: "对正在蓄力的敌人技能更快就绪。",
    branches: ["深梦", "余响"],
    branchHints: ["更长沉默，顺带迟缓。", "梦粉影响两只敌人。"],
  },
  fin: {
    id: "fin",
    name: "露鳍团",
    role: "support",
    bond: "tide",
    region: 1,
    rare: false,
    hp: 105,
    attack: 6,
    armor: 3,
    interval: 1.7,
    cooldown: 5.5,
    color: "#80c7c0",
    shape: "fins",
    skill: "水珠摇篮",
    hint: "恢复最虚弱伙伴，并洗去毒和灼烧。",
    trait: "溢出的治疗会成为护盾。",
    branches: ["清露", "潮涌"],
    branchHints: ["治疗更强，连带净化沉默。", "额外治疗另一位伙伴。"],
    evolution: {
      name: "星潮鳐",
      hint: "每次治疗给予全队护盾。",
      shape: "fins",
    },
  },
  otter: {
    id: "otter",
    name: "波纹獭",
    role: "attack",
    bond: "tide",
    region: 1,
    rare: false,
    hp: 120,
    attack: 12,
    armor: 3,
    interval: 1.2,
    cooldown: 4.5,
    color: "#74b5b8",
    shape: "tail",
    skill: "卷浪扑",
    hint: "连续两击，削减目标护盾。",
    trait: "对有盾目标出手更快。",
    branches: ["碎浪", "回旋"],
    branchHints: ["额外破盾，技能附带易伤。", "第二次扑击转向另一个敌人。"],
    evolution: {
      name: "乘风浪獭",
      hint: "破盾后立即补一次扑击。",
      shape: "tail",
    },
  },
  crab: {
    id: "crab",
    name: "贝壳球",
    role: "guard",
    bond: "tide",
    region: 1,
    rare: true,
    hp: 155,
    attack: 9,
    armor: 7,
    interval: 1.9,
    cooldown: 6.5,
    color: "#73a7a0",
    shape: "shell",
    skill: "响贝护阵",
    hint: "自我护盾并反击，嘲讽敌人。",
    trait: "护盾存在时分担队友受到的伤害。",
    branches: ["镜贝", "潮障"],
    branchHints: ["反击更强并削弱攻击者。", "护盾分享全队，持续更久。"],
  },
  fox: {
    id: "fox",
    name: "暖尾狐",
    role: "attack",
    bond: "ember",
    region: 2,
    rare: false,
    hp: 105,
    attack: 15,
    armor: 2,
    interval: 1.3,
    cooldown: 6,
    color: "#d5a36d",
    shape: "tail",
    skill: "火绒散",
    hint: "灼烧全体敌人。",
    trait: "灼烧至少两只敌人时加速技能。",
    branches: ["炽尾", "余温"],
    branchHints: ["灼烧更久、伤害更高。", "火绒额外治疗最弱伙伴。"],
    evolution: { name: "流焰九绒", hint: "灼烧附带短时易伤。", shape: "tail" },
  },
  cinder: {
    id: "cinder",
    name: "炭甲崽",
    role: "guard",
    bond: "ember",
    region: 2,
    rare: false,
    hp: 155,
    attack: 10,
    armor: 6,
    interval: 1.8,
    cooldown: 7,
    color: "#a39886",
    shape: "shell",
    skill: "暖石壁",
    hint: "全队护盾，自身反击与嘲讽。",
    trait: "被命中时给攻击者附着灼烧。",
    branches: ["热甲", "暖巢"],
    branchHints: ["反击点燃目标，护盾更强。", "保护最弱伙伴并回复其生命。"],
  },
  sparrow: {
    id: "sparrow",
    name: "晨烬雀",
    role: "support",
    bond: "ember",
    region: 2,
    rare: true,
    hp: 100,
    attack: 8,
    armor: 3,
    interval: 1.5,
    cooldown: 6,
    color: "#d7bc76",
    shape: "wings",
    skill: "暖阳啁啾",
    hint: "小量群体治疗，使全队攻击加快。",
    trait: "队伍失去一位伙伴后治疗增强。",
    branches: ["长歌", "火羽"],
    branchHints: ["治疗与加速持续更久。", "歌声附带全体敌人灼烧。"],
  },
  owl: {
    id: "owl",
    name: "月茸鸮",
    role: "support",
    bond: "echo",
    region: 3,
    rare: false,
    hp: 110,
    attack: 7,
    armor: 3,
    interval: 1.6,
    cooldown: 5.5,
    color: "#acb4bc",
    shape: "wings",
    skill: "月下轻语",
    hint: "治疗最弱伙伴，并缩短队友技能冷却。",
    trait: "队伍有控制兽时轻语额外回复。",
    branches: ["月泉", "回音"],
    branchHints: ["治疗时消除沉默与毒。", "更多冷却缩减，帮助连续控制。"],
    evolution: {
      name: "银月绒王",
      hint: "轻语同时让全队获得减伤。",
      shape: "horn",
    },
  },
  lynx: {
    id: "lynx",
    name: "影铃狸",
    role: "control",
    bond: "echo",
    region: 3,
    rare: false,
    hp: 110,
    attack: 11,
    armor: 3,
    interval: 1.3,
    cooldown: 5,
    color: "#979bae",
    shape: "ears",
    skill: "铃影缚",
    hint: "眩晕敌人，取消其蓄力。",
    trait: "对正在蓄力的敌人附加易伤。",
    branches: ["缚影", "流铃"],
    branchHints: ["眩晕更久，技能伤害提高。", "技能同时迟缓所有敌人。"],
  },
  bud: {
    id: "bud",
    name: "月苔菇",
    role: "control",
    bond: "bloom",
    region: 3,
    rare: true,
    hp: 125,
    attack: 8,
    armor: 4,
    interval: 1.6,
    cooldown: 6,
    color: "#aac386",
    shape: "shell",
    skill: "孢子圆舞",
    hint: "全体敌人中毒和迟缓。",
    trait: "有敌人中毒时自身逐渐恢复。",
    branches: ["浓荫", "菌环"],
    branchHints: ["毒更久、伤害更高。", "孢子产生群体小护盾。"],
  },
};
export const regions = [
  {
    id: 0,
    name: "风铃草甸",
    subtitle: "草叶把路，轻轻摇给你",
    terrain: "meadow" as Terrain,
    guardian: "卷藤守卫",
    hint: "藤蔓会预告冲撞；切守护承受蓄力，控制也能打断。",
    color: "#a5c889",
    wild: ["sprig", "moss", "moth"] as BeastId[],
  },
  {
    id: 1,
    name: "镜水湿地",
    subtitle: "踩着涟漪，去找新朋友",
    terrain: "water" as Terrain,
    guardian: "镜湖甲主",
    hint: "周期生出镜盾；持续伤害与波纹獭的破盾可以打开缺口。",
    color: "#82bcb0",
    wild: ["fin", "otter", "crab"] as BeastId[],
  },
  {
    id: 2,
    name: "暖灰山脊",
    subtitle: "火山睡着了，余温还在",
    terrain: "ridge" as Terrain,
    guardian: "炉心长者",
    hint: "会召出治疗火灵；调整集火，先处理后方支援。",
    color: "#b6b78b",
    wild: ["fox", "cinder", "sparrow"] as BeastId[],
  },
  {
    id: 3,
    name: "月苔秘境",
    subtitle: "月光在茸毛上，留了一个吻",
    terrain: "moon" as Terrain,
    guardian: "长夜织梦者",
    hint: "安静的前奏之后是群体梦潮；用沉默、眩晕或号令打断。",
    color: "#a4b8ac",
    wild: ["owl", "lynx", "bud"] as BeastId[],
  },
] as const;
export const terrainHints: Record<Terrain, string> = {
  meadow: "草甸：恢复技能效果增加15%。",
  water: "湿地：灼烧持续更短，护盾获得额外20%。",
  ridge: "山脊：灼烧更猛烈；护甲不能抵挡持续伤害。",
  moon: "月苔：控制持续增加20%，技能冷却更短。",
};
export type RouteKind = "trail" | "track" | "forage" | "nest";
export interface Route {
  id: RouteKind;
  name: string;
  hint: string;
  cost: number;
  terrain: Terrain;
}
export function routeOptions(region: number, step: number): Route[] {
  const area = regions[region]!;
  return [
    {
      id: "trail",
      name:
        step % 2
          ? "沿兽径前进"
          : ["穿过摇铃草", "踏过浅水滩", "走过温石径", "穿过月苔林"][region]!,
      hint: "消耗1补给；遇见本区常见幻兽，胜利获得培养资源。",
      cost: 1,
      terrain: area.terrain,
    },
    {
      id: "track",
      name: "追踪发光足印",
      hint: "消耗2补给；追踪累计2次保证稀有遭遇。敌人更强，资源更多。",
      cost: 2,
      terrain: area.terrain,
    },
    step === 1
      ? {
          id: "nest",
          name: "探访温暖巢穴",
          hint: "消耗1补给；获得本区常见兽蛋，完成2次遭遇或用晶露即时孵化。",
          cost: 1,
          terrain: area.terrain,
        }
      : {
          id: "forage",
          name: "采集并休整",
          hint: "增加3补给、食物和诱饵；放弃本节点战斗奖励。",
          cost: 0,
          terrain: area.terrain,
        },
  ];
}
