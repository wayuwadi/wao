export const elements = ["ember", "grove", "storm", "tide"] as const;
export type Element = (typeof elements)[number];
export const elementNames: Record<Element, string> = {
  ember: "炎",
  grove: "森",
  storm: "雷",
  tide: "潮",
};
export const elementColors: Record<Element, string> = {
  ember: "#dc7655",
  grove: "#5a9976",
  storm: "#a27cbe",
  tide: "#589db7",
};
export const heroIds = ["sol", "dew", "fern", "bolt", "opal", "root"] as const;
export type HeroId = (typeof heroIds)[number];
export const petIds = ["bird", "droplet", "horn"] as const;
export type PetId = (typeof petIds)[number];
export const foeIds = [
  "scout",
  "bulwark",
  "medic",
  "stinger",
  "chanter",
  "drainer",
  "sundial",
  "librarian",
  "orrery",
] as const;
export type FoeId = (typeof foeIds)[number];
export type Target = "enemy" | "ally" | "self" | "all-enemies" | "all-allies";
export interface Skill {
  id: string;
  name: string;
  cost: number;
  element: Element;
  target: Target;
  hint: string;
}
export interface Hero {
  id: HeroId;
  name: string;
  role: string;
  element: Element;
  hp: number;
  mp: number;
  attack: number;
  defense: number;
  speed: number;
  shape: string;
  branches: [string, string];
  branchHints: [string, string];
  skills: [Skill, Skill, Skill, Skill];
}
export const heroes: Record<HeroId, Hero> = {
  sol: {
    id: "sol",
    name: "炽芽",
    role: "爆发 · 灼烧",
    element: "ember",
    hp: 112,
    mp: 34,
    attack: 22,
    defense: 3,
    speed: 12,
    shape: "sun",
    branches: ["余烬扩散", "破晓回流"],
    branchHints: [
      "火种溅射并延长灼烧；烈日命中燃烧目标返还资源。",
      "回旋刃恢复资源；破阵能打穿更多护盾并为自身护体。",
    ],
    skills: [
      {
        id: "spark",
        name: "火种",
        cost: 0,
        element: "ember",
        target: "enemy",
        hint: "基础炎伤。余烬分支会扩散到相邻敌人。",
      },
      {
        id: "sunburst",
        name: "烈日绽放",
        cost: 12,
        element: "ember",
        target: "all-enemies",
        hint: "全体炎伤并灼烧。对方若已灼烧，余烬分支返还资源。",
      },
      {
        id: "breakline",
        name: "破阵光",
        cost: 9,
        element: "storm",
        target: "enemy",
        hint: "雷击穿盾并施加易伤，适合先手破防。",
      },
      {
        id: "returnblade",
        name: "回旋刃",
        cost: 5,
        element: "grove",
        target: "enemy",
        hint: "两段森伤；破晓分支恢复资源并获得护盾。",
      },
    ],
  },
  dew: {
    id: "dew",
    name: "露弥",
    role: "治疗 · 净化",
    element: "tide",
    hp: 101,
    mp: 46,
    attack: 14,
    defense: 3,
    speed: 10,
    shape: "drop",
    branches: ["澄澈潮汐", "绵长露珠"],
    branchHints: [
      "疗愈先解除禁疗；清泉额外净化队友并补充资源。",
      "治疗溢出转护盾；潮雨附带持续回复与更多恢复。",
    ],
    skills: [
      {
        id: "ripple",
        name: "小浪花",
        cost: 0,
        element: "tide",
        target: "enemy",
        hint: "潮伤，恢复自身少量资源。",
      },
      {
        id: "mend",
        name: "露珠疗愈",
        cost: 8,
        element: "tide",
        target: "ally",
        hint: "单体治疗。禁疗时无效；澄澈分支会先解除禁疗。",
      },
      {
        id: "cleanse",
        name: "清泉",
        cost: 10,
        element: "tide",
        target: "ally",
        hint: "解除灼烧、迟缓、禁疗与眩晕，再给予少量治疗。",
      },
      {
        id: "rain",
        name: "潮雨",
        cost: 16,
        element: "tide",
        target: "all-allies",
        hint: "全队治疗。绵长分支延长恢复并把溢出治疗转护盾。",
      },
    ],
  },
  fern: {
    id: "fern",
    name: "叶铃",
    role: "控制 · 改变顺序",
    element: "grove",
    hp: 107,
    mp: 40,
    attack: 16,
    defense: 4,
    speed: 15,
    shape: "leaf",
    branches: ["缠枝延展", "迅风回响"],
    branchHints: [
      "缠枝附带易伤；森笼打断后溅射伤害。",
      "风铃为全队加速；延迟目标后回复资源并护盾自己。",
    ],
    skills: [
      {
        id: "leafshot",
        name: "叶笛",
        cost: 0,
        element: "grove",
        target: "enemy",
        hint: "森伤；命中迟缓目标恢复资源。",
      },
      {
        id: "bind",
        name: "缠枝",
        cost: 8,
        element: "grove",
        target: "enemy",
        hint: "森伤并迟缓两轮，改变下一轮速度顺序。",
      },
      {
        id: "interrupt",
        name: "森笼",
        cost: 12,
        element: "grove",
        target: "enemy",
        hint: "普通敌人眩晕，前两章首领取消蓄力；星轨仪只会迟缓，需要属性破绽。",
      },
      {
        id: "delay",
        name: "风铃换位",
        cost: 9,
        element: "storm",
        target: "enemy",
        hint: "把本轮尚未行动的目标移至队尾。迅风分支给队伍加速。",
      },
    ],
  },
  bolt: {
    id: "bolt",
    name: "闪尾",
    role: "高速 · 追击",
    element: "storm",
    hp: 96,
    mp: 33,
    attack: 21,
    defense: 2,
    speed: 19,
    shape: "fox",
    branches: ["电光连锁", "游击储能"],
    branchHints: [
      "追击易伤目标附加打击；连锁雷击扩散。",
      "换步为自身护盾；吸能刀向伙伴转移资源。",
    ],
    skills: [
      {
        id: "zap",
        name: "电光点",
        cost: 0,
        element: "storm",
        target: "enemy",
        hint: "快速雷伤，对易伤目标更有效。",
      },
      {
        id: "chain",
        name: "连锁闪电",
        cost: 12,
        element: "storm",
        target: "all-enemies",
        hint: "全体雷伤，电光分支对易伤目标追加连锁。",
      },
      {
        id: "sidestep",
        name: "换步追击",
        cost: 7,
        element: "tide",
        target: "enemy",
        hint: "潮伤并给自身加速；游击分支提供护盾。",
      },
      {
        id: "drainblade",
        name: "吸能刀",
        cost: 4,
        element: "grove",
        target: "enemy",
        hint: "森伤并恢复自身资源，游击分支同时补给资源最少的伙伴。",
      },
    ],
  },
  opal: {
    id: "opal",
    name: "晶芽",
    role: "支援 · 资源分配",
    element: "storm",
    hp: 106,
    mp: 44,
    attack: 14,
    defense: 4,
    speed: 13,
    shape: "gem",
    branches: ["星光共振", "棱镜护航"],
    branchHints: [
      "星光给予全体加速；资源转移额外补给第二人。",
      "镜盾附带反击；协奏将队伍护盾延长并解除禁疗。",
    ],
    skills: [
      {
        id: "glint",
        name: "棱光",
        cost: 0,
        element: "storm",
        target: "enemy",
        hint: "雷伤，额外积累一点协战能量。",
      },
      {
        id: "barrier",
        name: "镜盾",
        cost: 9,
        element: "storm",
        target: "ally",
        hint: "给予单体护盾；棱镜分支让被攻击时反击。",
      },
      {
        id: "transfer",
        name: "星光接力",
        cost: 10,
        element: "ember",
        target: "ally",
        hint: "转移资源并加速目标，不能给自己。",
      },
      {
        id: "chorus",
        name: "光之协奏",
        cost: 16,
        element: "storm",
        target: "all-allies",
        hint: "全队护盾，星光分支同时加速，棱镜分支解除禁疗。",
      },
    ],
  },
  root: {
    id: "root",
    name: "岩芽",
    role: "守护 · 反击",
    element: "grove",
    hp: 144,
    mp: 32,
    attack: 18,
    defense: 7,
    speed: 7,
    shape: "stone",
    branches: ["磐根反击", "生息守望"],
    branchHints: [
      "守护时反击攻击者；震踏能打断蓄力。",
      "堡垒分摊为全队护盾；扎根治疗带净化并为伙伴补给。",
    ],
    skills: [
      {
        id: "thump",
        name: "石子敲敲",
        cost: 0,
        element: "grove",
        target: "enemy",
        hint: "森伤并获得少量护盾。",
      },
      {
        id: "taunt",
        name: "看向这里",
        cost: 7,
        element: "grove",
        target: "self",
        hint: "嘲讽吸引单体攻击并减伤；磐根分支反击。",
      },
      {
        id: "stomp",
        name: "震踏",
        cost: 11,
        element: "ember",
        target: "all-enemies",
        hint: "全体炎伤与迟缓；磐根分支打断正在蓄力的目标。",
      },
      {
        id: "bastion",
        name: "扎根堡垒",
        cost: 12,
        element: "grove",
        target: "ally",
        hint: "治疗与护盾，生息分支净化目标并护盾全队。",
      },
    ],
  },
};

export const pets: Record<
  PetId,
  { name: string; hint: string; cost: number; unlock: number }
> = {
  bird: {
    name: "晨灯鸟",
    hint: "选择一个敌人，自动使用克制属性打击；为首领积累破绽。",
    cost: 4,
    unlock: 0,
  },
  droplet: {
    name: "露团",
    hint: "治疗生命比例最低的伙伴，先解除负面状态；补充全队资源。",
    cost: 4,
    unlock: 1,
  },
  horn: {
    name: "磐角",
    hint: "全队护盾；本轮下一次敌方群攻伤害降低。",
    cost: 5,
    unlock: 3,
  },
};
export interface Gear {
  id: string;
  name: string;
  slot: "weapon" | "charm";
  tier: number;
  price: number;
  hint: string;
  attack?: number;
  defense?: number;
  hp?: number;
  mp?: number;
  speed?: number;
  effect:
    | "none"
    | "pierce"
    | "echo"
    | "heal"
    | "battery"
    | "burn"
    | "thorns"
    | "clean"
    | "pet"
    | "swift"
    | "guard";
}
export const gear: Gear[] = [
  {
    id: "branch-wand",
    name: "芽枝手杖",
    slot: "weapon",
    tier: 0,
    price: 0,
    attack: 0,
    effect: "none",
    hint: "可靠的初始装备，没有额外负担。",
  },
  {
    id: "sun-sabre",
    name: "日轮短刃",
    slot: "weapon",
    tier: 0,
    price: 30,
    attack: 5,
    speed: -2,
    effect: "burn",
    hint: "基础攻击附加灼烧，出手稍慢。",
  },
  {
    id: "tide-bell",
    name: "潮声铃",
    slot: "weapon",
    tier: 0,
    price: 30,
    attack: -2,
    mp: 10,
    effect: "heal",
    hint: "治疗量增加，直接伤害降低。",
  },
  {
    id: "needle",
    name: "贯星针",
    slot: "weapon",
    tier: 1,
    price: 45,
    attack: 3,
    defense: -2,
    effect: "pierce",
    hint: "部分伤害穿过护盾，自身防御降低。",
  },
  {
    id: "echo-staff",
    name: "回响杖",
    slot: "weapon",
    tier: 1,
    price: 45,
    mp: 8,
    speed: -1,
    effect: "echo",
    hint: "克制命中返还资源，略慢。",
  },
  {
    id: "thorn-hammer",
    name: "棘光锤",
    slot: "weapon",
    tier: 2,
    price: 60,
    attack: 7,
    speed: -4,
    effect: "thorns",
    hint: "高攻击并反击，行动顺序更晚。",
  },
  {
    id: "pocket-star",
    name: "口袋星",
    slot: "charm",
    tier: 0,
    price: 20,
    hp: 12,
    effect: "pet",
    hint: "首轮更早获得协战能量。",
  },
  {
    id: "wind-ribbon",
    name: "风铃缎带",
    slot: "charm",
    tier: 0,
    price: 25,
    speed: 4,
    hp: -12,
    effect: "swift",
    hint: "明显提前行动，生命降低。",
  },
  {
    id: "dew-bottle",
    name: "露水瓶",
    slot: "charm",
    tier: 1,
    price: 35,
    mp: 12,
    effect: "battery",
    hint: "防御时额外回复资源。",
  },
  {
    id: "shell",
    name: "暖光壳",
    slot: "charm",
    tier: 1,
    price: 35,
    hp: 20,
    speed: -2,
    defense: 2,
    effect: "guard",
    hint: "更耐打，防御时获得额外护盾。",
  },
  {
    id: "clear-stone",
    name: "澄明石",
    slot: "charm",
    tier: 2,
    price: 50,
    mp: -6,
    effect: "clean",
    hint: "防御时解除负面状态，资源上限稍低。",
  },
  {
    id: "mirror-pin",
    name: "棱镜别针",
    slot: "charm",
    tier: 2,
    price: 50,
    defense: -1,
    effect: "thorns",
    hint: "受到直接攻击会反击，自身防御稍低。",
  },
];
export interface Foe {
  name: string;
  element: Element;
  hp: number;
  attack: number;
  defense: number;
  speed: number;
  hint: string;
}
export const foes: Record<FoeId, Foe> = {
  scout: {
    name: "疾行斥候",
    element: "storm",
    hp: 52,
    attack: 15,
    defense: 1,
    speed: 17,
    hint: "优先攻击生命较低目标，时而准备突袭。",
  },
  bulwark: {
    name: "护阵盾卫",
    element: "grove",
    hp: 72,
    attack: 15,
    defense: 6,
    speed: 7,
    hint: "先张盾再重击，护盾内正面硬攻效率低。",
  },
  medic: {
    name: "织露医师",
    element: "tide",
    hp: 58,
    attack: 12,
    defense: 2,
    speed: 11,
    hint: "优先治疗受伤伙伴，否则向我方施加禁疗。",
  },
  stinger: {
    name: "灼羽蜂",
    element: "ember",
    hp: 48,
    attack: 13,
    defense: 1,
    speed: 18,
    hint: "灼烧持续伤害，净化和先手击杀都有效。",
  },
  chanter: {
    name: "吟咒者",
    element: "grove",
    hp: 65,
    attack: 19,
    defense: 2,
    speed: 8,
    hint: "蓄力群攻，可以被打断或防御减伤。",
  },
  drainer: {
    name: "汲能灵",
    element: "storm",
    hp: 59,
    attack: 13,
    defense: 3,
    speed: 14,
    hint: "吸取资源，使高费用技能暂时不可用。",
  },
  sundial: {
    name: "日晷守卫",
    element: "ember",
    hp: 245,
    attack: 24,
    defense: 5,
    speed: 9,
    hint: "反击姿态与日轮蓄力交替；控制打断蓄力，反击轮可治疗蓄能。",
  },
  librarian: {
    name: "封页司书",
    element: "tide",
    hp: 320,
    attack: 26,
    defense: 4,
    speed: 12,
    hint: "封页让全队禁疗，再放墨潮；净化与支援目标优先级很重要。",
  },
  orrery: {
    name: "星轨仪",
    element: "storm",
    hp: 440,
    attack: 32,
    defense: 6,
    speed: 10,
    hint: "属性每轮变换。两次克制命中形成破绽，可取消本轮星陨。",
  },
};
export interface RouteChoice {
  id: "aid" | "bold";
  name: string;
  detail: string;
  bonus: "shield" | "mana" | "clean";
  extraReward: number;
}
export interface Quest {
  id: number;
  chapter: number;
  name: string;
  npc: string;
  story: string;
  objective: string;
  foes: FoeId[];
  boss: boolean;
  reward: number;
  xp: number;
  drop: string;
  choices: [RouteChoice, RouteChoice];
}
const safe = (
  name: string,
  detail: string,
  bonus: RouteChoice["bonus"],
): RouteChoice => ({ id: "aid", name, detail, bonus, extraReward: 0 });
const bold = (name: string, detail: string): RouteChoice => ({
  id: "bold",
  name,
  detail,
  bonus: "mana",
  extraReward: 12,
});
export const quests: Quest[] = [
  {
    id: 0,
    chapter: 0,
    name: "风铃渡桥",
    npc: "渡桥员阿穗",
    story: "桥上的铃声被赶路的小影子打乱了。先选一条修桥路线吧。",
    objective: "击退斥候与灼羽蜂，找回桥钉。",
    foes: ["scout", "stinger"],
    boss: false,
    reward: 22,
    xp: 28,
    drop: "pocket-star",
    choices: [
      safe("帮阿穗加固桥面", "开场全队护盾，适合熟悉四属性。", "shield"),
      bold("沿风铃绳抄近路", "敌人更强，奖励多12星砂；开场补充资源。"),
    ],
  },
  {
    id: 1,
    chapter: 0,
    name: "信使的行囊",
    npc: "晨灯鸟小邮",
    story: "小邮追着飘走的信件，落进了护阵守卫的影子里。",
    objective: "击破盾卫与斥候，找回信袋。",
    foes: ["bulwark", "scout"],
    boss: false,
    reward: 24,
    xp: 32,
    drop: "sun-sabre",
    choices: [
      safe("先吹响求援哨", "开场为全队补足资源，留出破盾技能。", "mana"),
      bold("轻装绕过外哨", "敌人更强，奖励多12星砂；出发资源充足。"),
    ],
  },
  {
    id: 2,
    chapter: 0,
    name: "醒来的日晷",
    npc: "阿穗与小邮",
    story: "两条支线带回了铃铛，日晷却把响声当成了警报。",
    objective: "读懂反击姿态，打断日轮蓄力。",
    foes: ["sundial", "bulwark"],
    boss: true,
    reward: 40,
    xp: 60,
    drop: "tide-bell",
    choices: [
      safe("按顺序敲响铃铛", "开场护盾，可承受第一次攻击。", "shield"),
      bold("直接登上日晷台", "首领更强，多12星砂；开场补充资源。"),
    ],
  },
  {
    id: 3,
    chapter: 1,
    name: "墨潮航道",
    npc: "摆渡员露舟",
    story: "航道上的墨潮把每一盏路灯都染成同一种颜色。",
    objective: "处理汲能灵与吟咒者，恢复灯标。",
    foes: ["drainer", "chanter", "scout"],
    boss: false,
    reward: 32,
    xp: 38,
    drop: "dew-bottle",
    choices: [
      safe("把路灯一盏盏擦亮", "开场净化并抵御第一轮负面状态。", "clean"),
      bold("穿过近处墨雾", "敌人更强，多12星砂；补充开场资源。"),
    ],
  },
  {
    id: 4,
    chapter: 1,
    name: "档案员的求救",
    npc: "档案员卷卷",
    story: "卷卷被一群互相疗伤的小影子堵在书箱之间。",
    objective: "优先处理治疗支援，带卷卷离开。",
    foes: ["medic", "bulwark", "stinger"],
    boss: false,
    reward: 34,
    xp: 40,
    drop: "echo-staff",
    choices: [
      safe("撑起书箱当掩体", "开场全队护盾。", "shield"),
      bold("从索引滑梯下去", "敌人更强，多12星砂；补充开场资源。"),
    ],
  },
  {
    id: 5,
    chapter: 1,
    name: "封页司书",
    npc: "露舟与卷卷",
    story: "司书误以为所有声音都会撕坏藏书，要把伙伴也封进书页。",
    objective: "净化禁疗，处理支援，顶住墨潮。",
    foes: ["librarian", "medic"],
    boss: true,
    reward: 48,
    xp: 70,
    drop: "needle",
    choices: [
      safe("抄下净墨咒", "第一轮抵御负面状态，获得喘息空间。", "clean"),
      bold("跃进翻动的书页", "首领更强，多12星砂；补充开场资源。"),
    ],
  },
  {
    id: 6,
    chapter: 2,
    name: "迷路的星轨",
    npc: "观星员闪闪",
    story: "天台的轨道绕了一个结。跟着不同颜色的光，找回正确方向。",
    objective: "应对高速集火与资源吸取。",
    foes: ["scout", "drainer", "stinger"],
    boss: false,
    reward: 40,
    xp: 46,
    drop: "clear-stone",
    choices: [
      safe("沿观测刻度前进", "开场护盾，给慢速队员留出行动机会。", "shield"),
      bold("追逐飞快的流星", "敌人更强，多12星砂；补充开场资源。"),
    ],
  },
  {
    id: 7,
    chapter: 2,
    name: "四象灯塔",
    npc: "修灯员岩朵",
    story: "四盏灯需要四种颜色。让队伍带上不同属性，再去灯塔顶端。",
    objective: "打断吟咒，并阻止治疗支援拖长战斗。",
    foes: ["chanter", "medic", "bulwark"],
    boss: false,
    reward: 42,
    xp: 48,
    drop: "thorn-hammer",
    choices: [
      safe("修好避雷台", "第一轮负面状态抗性，护住行动顺序。", "clean"),
      bold("借闪电点灯", "敌人更强，多12星砂；补充开场资源。"),
    ],
  },
  {
    id: 8,
    chapter: 2,
    name: "星轨重新亮起",
    npc: "所有旅伴",
    story: "星轨仪把四季同时转到了眼前。找准克制属性，让它慢下来。",
    objective: "跟随属性变化，两次克制打出破绽，阻止星陨。",
    foes: ["orrery", "chanter"],
    boss: true,
    reward: 60,
    xp: 90,
    drop: "mirror-pin",
    choices: [
      safe(
        "合上伙伴的观测图",
        "开场护盾；晨灯鸟可弥补队伍缺少的属性。",
        "shield",
      ),
      bold("迎向完整星陨", "首领更强，多12星砂；补充开场资源。"),
    ],
  },
];
export const chapters = [
  { name: "晨穗浮岛", subtitle: "风铃把路，系在云上", color: "#e7ba4e" },
  { name: "潮墨书湾", subtitle: "每一页，都藏着一片海", color: "#75aaa9" },
  { name: "雷芽天台", subtitle: "把迷路的星光，送回轨道", color: "#ae95c7" },
];
export function advantage(attack: Element, target: Element): number {
  const a = elements.indexOf(attack),
    b = elements.indexOf(target);
  return (a + 1) % 4 === b ? 1.4 : (b + 1) % 4 === a ? 0.7 : 1;
}
export function counterElement(target: Element): Element {
  return elements[(elements.indexOf(target) + 3) % 4]!;
}
