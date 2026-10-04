export type WorldKind = 'watch' | 'catch' | 'miss' | 'wind' | 'orbit' | 'visit' | 'tickle' | 'imitate' | 'wave' | 'reverse' | 'yawn' | 'peek' | 'pass'
export type WorldTier = 'common' | 'variation' | 'rare'
export interface WorldStory { kind: WorldKind; source: number; target?: number; direction?: number; tier: WorldTier }
export interface WorldPoint { x: number; y: number }
export interface CompanionAnchor extends WorldPoint { size: number; available: boolean }

/** 当前停留内记住近期选择；稀有变化有冷却，组合动作的识别始终确定。 */
export function createWorldChoices(random = Math.random) {
  let previous = -1, rareAt = -Infinity
  return {
    partner(exclude = -1) {
      let pool = [0, 1, 2, 3].filter(i => i !== exclude && i !== previous)
      if (!pool.length) pool = [0, 1, 2, 3].filter(i => i !== exclude)
      previous = pool[Math.min(pool.length - 1, Math.floor(random() * pool.length))]!
      return previous
    },
    tier(now: number): WorldTier {
      const n = random()
      if (n >= 0.95 && now - rareAt >= 45000) { rareAt = now; return 'rare' }
      return n >= 0.7 ? 'variation' : 'common'
    },
  }
}
export type WorldChoices = ReturnType<typeof createWorldChoices>

export function createWorldCombos() {
  let clicks: { index: number; at: number }[] = [], beats: number[] = []
  return {
    pop(index: number, now: number) {
      clicks = [...clicks.filter(c => now - c.at <= 18000), { index, at: now }].slice(-8)
      const last = clicks[clicks.length - 2]
      const pair = last && last.index !== index && now - last.at <= 650 ? last.index : undefined
      const tail = clicks.slice(-4)
      const reverse = tail.length === 4 && now - tail[0]!.at <= 6000 && tail.every((c, i) => c.index === 3 - i)
      const imitate = clicks.filter(c => c.index === index).length >= 3
      if (reverse || imitate) clicks = []
      return { pair, reverse, imitate }
    },
    beat(now: number) {
      beats = [...beats.filter(at => now - at <= 2200), now].slice(-3)
      const rhythm = beats.length === 3 && beats[1]! - beats[0]! >= 160 && beats[2]! - beats[1]! >= 160
        && Math.abs((beats[1]! - beats[0]!) - (beats[2]! - beats[1]!)) <= 350
      if (rhythm) beats = []
      return rhythm
    },
    reset() { clicks = []; beats = [] },
  }
}

/** 闭合路径需绕中心一周；直划识别风，零碎抖动与来回涂抹不当作圈。 */
export function recognizeWorldGesture(points: WorldPoint[]) {
  if (points.length < 4) return null
  const xs = points.map(p => p.x), ys = points.map(p => p.y)
  const width = Math.max(...xs) - Math.min(...xs), height = Math.max(...ys) - Math.min(...ys)
  const center = { x: (Math.max(...xs) + Math.min(...xs)) / 2, y: (Math.max(...ys) + Math.min(...ys)) / 2 }
  const first = points[0]!, last = points[points.length - 1]!
  let winding = 0, length = 0
  points.slice(1).forEach((p, i) => {
    const q = points[i]!, a = Math.atan2(p.y - center.y, p.x - center.x) - Math.atan2(q.y - center.y, q.x - center.x)
    winding += Math.atan2(Math.sin(a), Math.cos(a))
    length += Math.hypot(p.x - q.x, p.y - q.y)
  })
  if (width >= 56 && height >= 56 && width / height > 0.45 && width / height < 2.2
    && Math.hypot(last.x - first.x, last.y - first.y) < Math.max(width, height) * 0.35 && Math.abs(winding) > 4.8) {
    return { kind: 'orbit' as const, center, radius: Math.min(130, Math.max(width, height) / 2), direction: Math.sign(winding) }
  }
  return Math.hypot(last.x - first.x, last.y - first.y) > 60 && length > 70
    ? { kind: 'wind' as const, center, radius: 0, direction: Math.sign(last.x - first.x) || 1 } : null
}

export function worldPose(story: WorldStory, seconds: number, index: number) {
  const duration = story.kind === 'pass' ? 6.5 : 3.4
  const fade = Math.sin(Math.min(1, Math.max(0, seconds / duration)) * Math.PI)
  const delay = index === 1 ? 0.2 : index === 3 ? 0.32 : 0
  const t = Math.max(0, seconds - delay), involved = index === story.source || index === story.target
  const pose = { x: 0, y: 0, tilt: 0, stretch: 1, turn: 0, lid: 1, hat: 0 }
  switch (story.kind) {
    case 'watch': case 'orbit': pose.turn = (index % 2 ? -0.12 : 0.12) * fade; pose.y = 3 * fade; break
    case 'catch': pose.y = Math.max(0, Math.sin(t * 5)) * (involved ? (index === 3 ? 29 : 22) : 5) * fade; pose.tilt = Math.sin(t * 3) * 0.08 * fade; break
    case 'miss': if (involved || story.tier === 'rare') { pose.turn = Math.sin(t * 4 + index) * 0.25 * fade; pose.y = Math.max(0, Math.sin(t * 6)) * 7 * fade }; break
    case 'wind':
      pose.tilt = -(story.direction ?? 1) * 0.2 * fade; pose.x = (story.direction ?? 1) * 9 * fade
      if (story.tier === 'rare') {
        if (index === 0) pose.hat = Math.sin(t * 3) * 0.35 * fade
        if (index === 1) { pose.x = -9 * fade; pose.y = 7 * fade; pose.turn = 0.16 * fade }
      }
      break
    case 'visit':
      if (involved) { pose.x = (index === story.source ? 1 : -1) * Math.sign((story.target ?? 0) - story.source) * 12 * fade; pose.tilt = -pose.x / 100 }
      if (story.tier === 'rare') pose.y = Math.max(0, Math.sin(t * 5 - index * 0.25)) * 14 * fade
      break
    case 'tickle': if (involved) { pose.tilt = Math.sin(t * 15) * 0.12 * fade; pose.stretch = 1 - 0.1 * fade; pose.lid = 1 - 0.7 * fade }; break
    case 'imitate': if (involved) { pose.y = Math.max(0, Math.sin(t * 5 - (index === story.target ? 0.65 : 0))) * 13 * fade; pose.tilt = Math.sin(t * 4) * 0.12 * fade }; break
    case 'wave': case 'reverse': pose.y = Math.max(0, Math.sin(t * 4 - (story.kind === 'reverse' ? 3 - index : (index + story.source) % 4) * 0.75)) * 23 * fade; pose.tilt = Math.sin(t * 4 - index) * 0.1 * fade; break
    case 'yawn': if (involved) { const yawn = Math.max(0, Math.sin(t * 1.8 - (index === story.target ? 0.8 : 0))) * fade; pose.stretch = 1 + yawn * 0.1; pose.lid = 1 - yawn * 0.85 }; break
    case 'peek': if (index === story.source) { pose.y = 15 * fade; pose.turn = 0.18 * fade }; break
    case 'pass': pose.y = Math.max(0, Math.sin(t * 3 - index * 0.8)) * (story.tier === 'rare' ? 19 : 8) * fade; pose.turn = Math.sin(t * 3 - index) * 0.15 * fade; break
  }
  return pose
}
