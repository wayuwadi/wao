import { clamp, initialArrows } from './engine'
import { isWorld, type WorldId } from './worlds'
export interface Piece {
  id: number
  x: number
  y: number
  angle: number
}
export interface Sticker {
  id: number
  buddy: number
  x: number
  y: number
  angle: number
}
export interface Stroke {
  points: number[][]
  color: string
}
export interface RelaxProgress {
  version: 1
  world: WorldId
  place: 'rest' | 'play'
  anecdote: string
  memory: { best: number; level: number; sequence: number[] }
  lounge: { pieces: Piece[] }
  orbit: { level: number; arrows: number[]; wins: number }
  platform: {
    x: number
    y: number
    role: number
    bridge: boolean
    crate: number
    gate: boolean
    lit: boolean
    collected: number[]
    won: boolean
  }
  journal: { text: string; stickers: Sticker[]; strokes: Stroke[] }
  gallery: { level: number; angles: number[]; light: number; wins: number }
}
export function freshProgress(): RelaxProgress {
  return {
    version: 1,
    world: 'theater',
    place: 'rest',
    anecdote: '',
    memory: { best: 0, level: 1, sequence: [] },
    lounge: {
      pieces: Array.from({ length: 8 }, (_, id) => ({
        id,
        x: 70 + id * 73,
        y: id < 5 ? 55 : 220,
        angle: 0,
      })),
    },
    orbit: { level: 0, arrows: initialArrows(0), wins: 0 },
    platform: {
      x: 30,
      y: 280,
      role: 0,
      bridge: false,
      crate: 270,
      gate: false,
      lit: false,
      collected: [],
      won: false,
    },
    journal: { text: '', stickers: [], strokes: [] },
    gallery: { level: 0, angles: [0, 0, 0], light: 0, wins: 0 },
  }
}
const object = (v: unknown): Record<string, unknown> =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {}
const num = (v: unknown, fallback: number, min: number, max: number) =>
  typeof v === 'number' && Number.isFinite(v) ? clamp(v, min, max) : fallback
const ints = (v: unknown, length: number, min: number, max: number) =>
  Array.isArray(v)
    ? v.slice(0, length).filter((n): n is number => Number.isInteger(n) && n >= min && n <= max)
    : []
/** 从不信任的本机存档恢复有限字段；不接受额外 HTML、原型或无界数组。 */
export function readProgress(raw: string | null): RelaxProgress {
  if (!raw) return freshProgress()
  if (raw.length > 1000000) throw new Error('存档过大')
  const r = object(JSON.parse(raw))
  if (r.version !== 1) throw new Error('存档版本不兼容')
  const out = freshProgress()
  if (isWorld(r.world)) out.world = r.world
  out.place = r.place === 'play' ? 'play' : 'rest'
  out.anecdote = typeof r.anecdote === 'string' ? r.anecdote.slice(0, 100) : ''
  const m = object(r.memory)
  out.memory = {
    best: Math.floor(num(m.best, 0, 0, 100)),
    level: Math.floor(num(m.level, 1, 1, 20)),
    sequence: ints(m.sequence, 22, 0, 4),
  }
  const l = object(r.lounge)
  if (Array.isArray(l.pieces) && l.pieces.length === 8)
    out.lounge.pieces = l.pieces.map((p, id) => {
      const a = object(p)
      return {
        id,
        x: num(a.x, 70 + id * 73, 0, 650),
        y: num(a.y, 55, 0, 340),
        angle: num(a.angle, 0, -180, 180),
      }
    })
  const o = object(r.orbit),
    level = Math.floor(num(o.level, 0, 0, 4)),
    arrows = ints(o.arrows, 20, 0, 3)
  out.orbit = {
    level,
    arrows: arrows.length === 20 ? arrows : initialArrows(level),
    wins: Math.floor(num(o.wins, 0, 0, 999)),
  }
  const p = object(r.platform)
  out.platform = {
    x: num(p.x, 30, 0, 1160),
    y: num(p.y, 280, 0, 390),
    role: Math.floor(num(p.role, 0, 0, 4)),
    bridge: p.bridge === true,
    crate: num(p.crate, 270, 180, 370),
    gate: p.gate === true,
    lit: p.lit === true,
    collected: [...new Set(ints(p.collected, 5, 0, 4))],
    won: p.won === true,
  }
  const j = object(r.journal)
  out.journal.text = typeof j.text === 'string' ? j.text.slice(0, 240) : ''
  if (Array.isArray(j.stickers))
    out.journal.stickers = j.stickers.slice(0, 30).map((v, id) => {
      const s = object(v)
      return {
        id,
        buddy: Math.floor(num(s.buddy, 0, 0, 4)),
        x: num(s.x, 300, 0, 620),
        y: num(s.y, 200, 0, 350),
        angle: num(s.angle, 0, -180, 180),
      }
    })
  if (Array.isArray(j.strokes))
    out.journal.strokes = j.strokes.slice(0, 80).map((v) => {
      const s = object(v)
      return {
        color: ['#3659cf', '#ef745e', '#253a62'].includes(String(s.color))
          ? String(s.color)
          : '#3659cf',
        points: Array.isArray(s.points)
          ? s.points
              .slice(0, 160)
              .filter(
                (p) =>
                  Array.isArray(p) &&
                  p.length === 2 &&
                  p.every((n) => typeof n === 'number' && Number.isFinite(n)),
              )
              .map((p) => [clamp(p[0], 0, 700), clamp(p[1], 0, 420)])
          : [],
      }
    })
  const g = object(r.gallery)
  out.gallery = {
    level: Math.floor(num(g.level, 0, 0, 4)),
    angles: Array.from({ length: 3 }, (_, i) =>
      num(Array.isArray(g.angles) ? g.angles[i] : 0, 0, 0, 350),
    ),
    light: num(g.light, 0, 0, 350),
    wins: Math.floor(num(g.wins, 0, 0, 999)),
  }
  return out
}
export const progressKey = (member: string) => `wao-relax-worlds:v1:${member || 'guest'}`
