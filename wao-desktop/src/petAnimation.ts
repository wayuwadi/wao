export type PetAnimation =
  | 'idle'
  | 'wave'
  | 'surprised'
  | 'dragging'
  | 'dragging-left'
  | 'dragging-right'
  | 'dragging-up'
  | 'dragging-down'
  | 'sleepy'
  | 'sleeping'
  | 'wake'
  | 'working'

export interface PetAnimationSequence {
  frames: readonly number[]
  interval: number
  loop: boolean
}

export type DragDirection = 'neutral' | 'left' | 'right' | 'up' | 'down'

const DRAG_DIRECTION_THRESHOLD = 8

export const PET_ANIMATIONS: Record<PetAnimation, PetAnimationSequence> = {
  idle: {
    frames: [0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0],
    interval: 360,
    loop: true,
  },
  wave: { frames: [4, 5, 4, 5, 6, 7], interval: 300, loop: false },
  surprised: { frames: [8, 9, 8], interval: 220, loop: false },
  dragging: { frames: [8, 9], interval: 320, loop: true },
  'dragging-left': { frames: [8, 9], interval: 300, loop: true },
  'dragging-right': { frames: [9, 8], interval: 300, loop: true },
  'dragging-up': { frames: [4, 5], interval: 360, loop: true },
  'dragging-down': { frames: [10, 11], interval: 300, loop: true },
  sleepy: { frames: [12, 13], interval: 620, loop: false },
  sleeping: { frames: [14], interval: 1_000, loop: true },
  wake: { frames: [14, 15, 0], interval: 260, loop: false },
  working: { frames: [6, 7], interval: 300, loop: true },
}

export function dragDirectionFromDelta(deltaX: number, deltaY: number): DragDirection {
  if (Math.abs(deltaX) < DRAG_DIRECTION_THRESHOLD && Math.abs(deltaY) < DRAG_DIRECTION_THRESHOLD) {
    return 'neutral'
  }

  if (Math.abs(deltaX) >= Math.abs(deltaY)) return deltaX < 0 ? 'left' : 'right'
  return deltaY < 0 ? 'up' : 'down'
}

export function spritePosition(frame: number) {
  if (!Number.isInteger(frame) || frame < 0 || frame > 15) {
    throw new RangeError(`Invalid pet sprite frame: ${frame}`)
  }
  const column = frame % 4
  const row = Math.floor(frame / 4)
  return `${(column * 100) / 3}% ${(row * 100) / 3}%`
}
