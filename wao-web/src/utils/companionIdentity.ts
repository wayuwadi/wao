/** 首页四位伙伴是所有陪伴场景的唯一角色来源。 */
export const COMPANION_IDS = ['painter', 'bookworm', 'happy', 'frog'] as const
export type CompanionId = (typeof COMPANION_IDS)[number]
export const COMPANIONS = {
  painter: {
    name: '小绘', role: '表达', image: '/companions/painter.png',
    meaning: '留下灵感，表达自己。想法不必完整，也值得被记录。',
    voice: '先记下来，慢慢再说。',
  },
  bookworm: {
    name: '小知', role: '理解', image: '/companions/bookworm.png',
    meaning: '保持好奇，让杂乱的事情慢慢变清楚。',
    voice: '我们一起理一理。',
  },
  happy: {
    name: '小悦', role: '喜悦', image: '/companions/happy.png',
    meaning: '看见每一点进展，小小的完成也值得开心。',
    voice: '这一步，也值得开心。',
  },
  frog: {
    name: '小悠', role: '松弛', image: '/companions/frog.png',
    meaning: '按自己的节奏生活，休息也有它的价值。',
    voice: '歇一会儿，没关系。',
  },
} satisfies Record<CompanionId, { name: string; role: string; image: string; meaning: string; voice: string }>
const selections = new Map<string, CompanionId>()
export function randomCompanion(): CompanionId {
  return COMPANION_IDS[Math.floor(Math.random() * COMPANION_IDS.length)]!
}
/** 同一成员、同一场景保持身份；版本卡片和大图共用版本场景。 */
export function selectCompanion(context: string, member = ''): CompanionId {
  const key = `wao-companion-identity:${encodeURIComponent(member)}:${context}`
  const cached = selections.get(key)
  if (cached) return cached
  let selected: CompanionId | undefined
  try {
    const stored = sessionStorage.getItem(key)
    if (COMPANION_IDS.includes(stored as CompanionId)) selected = stored as CompanionId
  } catch {
    /* 存储受限时当前运行仍保持同一位。 */
  }
  selected ??= randomCompanion()
  selections.set(key, selected)
  if (selections.size > 64) selections.delete(selections.keys().next().value!)
  try {
    sessionStorage.setItem(key, selected)
  } catch {
    /* 装饰身份不影响业务。 */
  }
  return selected
}
