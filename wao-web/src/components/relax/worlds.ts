import star from '@/assets/relax/star.svg'
import book from '@/assets/relax/book.svg'
import robot from '@/assets/relax/robot.svg'
import bolt from '@/assets/relax/bolt.svg'
import folder from '@/assets/relax/folder.svg'

export const buddies = [
  { name: '灵感星星', image: star, color: '#ffdc70', action: '眨眨眼', skill: '照亮路标' },
  { name: '记录小本', image: book, color: '#ad94ee', action: '挥挥手', skill: '搭起纸桥' },
  { name: 'AI 伙伴', image: robot, color: '#65dfcf', action: '转个圈', skill: '推动箱子' },
  { name: '行动闪电', image: bolt, color: '#ff91bf', action: '跳一下', skill: '向前冲刺' },
  { name: '资料方块', image: folder, color: '#b6d87c', action: '歪歪头', skill: '压下机关' },
] as const
export const worlds = [
  {
    id: 'theater',
    name: '伙伴小剧场',
    title: '世界很忙。你先放个空。',
    subtitle: '不用立刻变好，先和我们待一会儿。',
    game: '动作传话筒',
    genre: '观察 · 记忆',
    color: '#d3f586',
  },
  {
    id: 'club',
    name: '休息俱乐部',
    title: '今天，允许自己慢半拍。',
    subtitle: '灵感会等你。世界也会。',
    game: '今天怎么躺',
    genre: '物理 · 摆放',
    color: '#ffa0c8',
  },
  {
    id: 'orbit',
    name: '环游小队',
    title: '呼——松一口气。',
    subtitle: '不用飞很远，回到自己就好。',
    game: '把伙伴送回家',
    genre: '轨道 · 解谜',
    color: '#a899ff',
  },
  {
    id: 'arcade',
    name: '像素游乐场',
    title: '暂停键，也是前进键。',
    subtitle: '今天不用打怪。和小伙伴一起存个档。',
    game: '五人摸鱼冒险',
    genre: '平台 · 闯关',
    color: '#b2ff5b',
  },
  {
    id: 'journal',
    name: '涂鸦手账',
    title: '这一页，就用来发呆。',
    subtitle: '写不出来也没关系，空白也是生活的一部分。',
    game: '今天贴点什么',
    genre: '涂鸦 · 创作',
    color: '#537cf6',
  },
  {
    id: 'gallery',
    name: '极简美术馆',
    title: '无所事事，也是一件作品。',
    subtitle: '请随意停留，不必带走答案。',
    game: '影子有点不对劲',
    genre: '光影 · 拼图',
    color: '#ed8354',
  },
] as const
export type WorldId = (typeof worlds)[number]['id']
export function isWorld(value: unknown): value is WorldId {
  return worlds.some((w) => w.id === value)
}
export const worldById = (id: WorldId) => worlds.find((w) => w.id === id)!
