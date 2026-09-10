/** 张三丰 v2 图集契约；网页宠物和三维场景共用同一套帧坐标。 */
export const PET_ATLAS = { columns: 8, rows: 11, cellWidth: 192, cellHeight: 208 } as const

export const PET_SEQUENCES = {
  idle: { row: 0, durations: [560, 220, 220, 280, 280, 640], loop: true },
  'running-right': { row: 1, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true },
  'running-left': { row: 2, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true },
  waving: { row: 3, durations: [140, 140, 140, 280], loop: false },
  jumping: { row: 4, durations: [140, 140, 140, 140, 280], loop: false },
  failed: { row: 5, durations: [140, 140, 140, 140, 140, 140, 140, 240], loop: false },
  waiting: { row: 6, durations: [150, 150, 150, 150, 150, 260], loop: true },
  running: { row: 7, durations: [120, 120, 120, 120, 120, 220], loop: true },
  review: { row: 8, durations: [150, 150, 150, 150, 150, 280], loop: true },
} as const

export type PetSpriteState = keyof typeof PET_SEQUENCES
export interface PetFrame { row: number; column: number; finished: boolean }

export function petFrameAt(state: PetSpriteState, elapsedMs: number): PetFrame {
  const sequence = PET_SEQUENCES[state]
  const total = sequence.durations.reduce<number>((sum, duration) => sum + duration, 0)
  const elapsed = Math.max(0, Number.isFinite(elapsedMs) ? elapsedMs : 0)
  if (!sequence.loop && elapsed >= total) {
    return { row: sequence.row, column: sequence.durations.length - 1, finished: true }
  }
  let remaining = elapsed % total
  for (let column = 0; column < sequence.durations.length; column += 1) {
    if (remaining < sequence.durations[column]!) return { row: sequence.row, column, finished: false }
    remaining -= sequence.durations[column]!
  }
  return { row: sequence.row, column: 0, finished: false }
}

/** 屏幕坐标：正上方为 0，顺时针每 22.5 度一个方向。 */
export function petLookFrame(degrees: number): PetFrame {
  const angle = Number.isFinite(degrees) ? degrees : 0
  const index = Math.round(((angle % 360 + 360) % 360) / 22.5) % 16
  return { row: 9 + Math.floor(index / 8), column: index % 8, finished: false }
}

export function petBackgroundPosition(frame: Pick<PetFrame, 'row' | 'column'>) {
  return `${frame.column * 100 / (PET_ATLAS.columns - 1)}% ${frame.row * 100 / (PET_ATLAS.rows - 1)}%`
}
