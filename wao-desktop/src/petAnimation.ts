export type PetAnimation =
  | 'idle'
  | 'wave'
  | 'surprised'
  | 'dragging'
  | 'sleepy'
  | 'sleeping'
  | 'wake'
  | 'working'

export interface PetAnimationSequence {
  frames: readonly number[]
  interval: number
  loop: boolean
}

export const PET_ANIMATIONS: Record<PetAnimation, PetAnimationSequence> = {
  idle: { frames: [0, 2, 0, 3], interval: 680, loop: true },
  wave: { frames: [4, 5, 4, 6, 7], interval: 180, loop: false },
  surprised: { frames: [8, 9, 8], interval: 220, loop: false },
  dragging: { frames: [10, 11], interval: 170, loop: true },
  sleepy: { frames: [12, 13], interval: 620, loop: false },
  sleeping: { frames: [14], interval: 1_000, loop: true },
  wake: { frames: [14, 15, 0], interval: 260, loop: false },
  working: { frames: [6, 7], interval: 300, loop: true },
}

export function spritePosition(frame: number) {
  if (!Number.isInteger(frame) || frame < 0 || frame > 15) {
    throw new RangeError(`Invalid pet sprite frame: ${frame}`)
  }
  const column = frame % 4
  const row = Math.floor(frame / 4)
  return `${(column * 100) / 3}% ${(row * 100) / 3}%`
}
