import {
  freshBeastMemory,
  isBeastMemory,
  type BeastMemory,
} from "../games/beasts/save";
import {
  freshCampMemory,
  isCampMemory,
  type CampMemory,
} from "../games/camp/save";
import { freshMemory, isDinoMemory, type DinoMemory } from "../games/dino/save";
import {
  freshStarfieldMemory,
  isStarfieldMemory,
  type StarfieldMemory,
} from "../games/starfield/save";
import {
  freshRiftMemory,
  isRiftMemory,
  type RiftMemory,
} from "../games/rift/save";

export interface GameMemories {
  dino: DinoMemory;
  rift: RiftMemory;
  starfield: StarfieldMemory;
  beasts: BeastMemory;
  camp: CampMemory;
}
export type GameId = keyof GameMemories;
export const games = {
  camp: {
    name: "最后一座营地",
    letter: "青色营地",
    tagline: "为彼此，留一盏灯。",
    restore: "保存波间布局与纪录 · 波内离开恢复本波开始前",
    fresh: freshCampMemory,
    validate: isCampMemory,
  },
  beasts: {
    name: "幻兽领地",
    letter: "绿色领地",
    tagline: "把心事，养成小兽。",
    restore: "保存收集与成长 · 遭遇从本节点开战前恢复",
    fresh: freshBeastMemory,
    validate: isBeastMemory,
  },
  dino: {
    name: "恐龙荒野",
    letter: "荒野来信",
    tagline: "跳过烦恼，跑进晚风。",
    restore: "保存纪录与解锁 · 不恢复未结束的一局",
    fresh: freshMemory,
    validate: isDinoMemory,
  },
  rift: {
    name: "裂隙远征",
    letter: "琥珀远征",
    tagline: "把勇气，装进口袋。",
    restore: "保存角色与成长 · 从房间检查点恢复",
    fresh: freshRiftMemory,
    validate: isRiftMemory,
  },
  starfield: {
    name: "星野奇旅",
    letter: "金色星野",
    tagline: "把星光，带在身边。",
    restore: "保存队伍与成长 · 恢复上一条已确认行动",
    fresh: freshStarfieldMemory,
    validate: isStarfieldMemory,
  },
} as const;
export function isGameId(id: unknown): id is GameId {
  return (
    id === "dino" ||
    id === "rift" ||
    id === "starfield" ||
    id === "beasts" ||
    id === "camp"
  );
}
export function clonePayload<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
