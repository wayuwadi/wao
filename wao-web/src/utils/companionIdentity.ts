/** 首页四位伙伴是所有陪伴场景的唯一角色来源。 */
export const COMPANION_IDS = ['painter', 'bookworm', 'happy', 'frog'] as const
export type CompanionId = (typeof COMPANION_IDS)[number]
export const COMPANIONS = {
  painter: { name: '小画家', image: '/companions/painter.png' },
  bookworm: { name: '小书虫', image: '/companions/bookworm.png' },
  happy: { name: '小开心', image: '/companions/happy.png' },
  frog: { name: '小青蛙', image: '/companions/frog.png' },
} satisfies Record<CompanionId, { name: string; image: string }>
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
