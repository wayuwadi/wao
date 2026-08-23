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
  durations: readonly number[]
  loop: boolean
}

export type DragDirection = 'neutral' | 'left' | 'right' | 'up' | 'down'

const DRAG_DIRECTION_THRESHOLD = 8

export const PET_ANIMATIONS: Record<PetAnimation, PetAnimationSequence> = {
  idle: {
    frames: [0, 1, 2, 3, 4, 5, 6, 5, 4, 3],
    durations: [1_600, 150, 120, 180, 420, 180, 1_200, 180, 260, 520],
    loop: true,
  },
  wave: {
    frames: [45, 46, 47, 48, 47, 46, 45],
    durations: [180, 180, 260, 420, 260, 180, 260],
    loop: false,
  },
  surprised: { frames: [42, 43, 44, 45], durations: [180, 260, 360, 240], loop: false },
  dragging: { frames: [42, 0], durations: [240, 320], loop: true },
  'dragging-left': {
    frames: [14, 15, 16, 17, 18, 19, 20],
    durations: [110, 100, 100, 110, 100, 100, 160],
    loop: true,
  },
  'dragging-right': {
    frames: [7, 8, 9, 10, 11, 12, 13],
    durations: [110, 100, 100, 110, 100, 100, 160],
    loop: true,
  },
  'dragging-up': {
    frames: [21, 22, 23, 24, 25, 26],
    durations: [160, 130, 150, 180, 180, 220],
    loop: true,
  },
  'dragging-down': {
    frames: [42, 43, 44, 43],
    durations: [150, 170, 240, 170],
    loop: true,
  },
  sleepy: { frames: [35, 36, 37], durations: [520, 620, 720], loop: false },
  sleeping: { frames: [38, 39, 40, 39], durations: [900, 760, 1_100, 760], loop: true },
  wake: { frames: [40, 39, 41, 0], durations: [240, 220, 360, 260], loop: false },
  working: {
    frames: [28, 29, 30, 31, 32, 33, 34],
    durations: [220, 180, 180, 220, 180, 420, 260],
    loop: true,
  },
}

export function dragDirectionFromDelta(deltaX: number, deltaY: number): DragDirection {
  if (Math.abs(deltaX) < DRAG_DIRECTION_THRESHOLD && Math.abs(deltaY) < DRAG_DIRECTION_THRESHOLD) {
    return 'neutral'
  }

  if (Math.abs(deltaX) >= Math.abs(deltaY)) return deltaX < 0 ? 'left' : 'right'
  return deltaY < 0 ? 'up' : 'down'
}

export function spritePosition(frame: number) {
  if (!Number.isInteger(frame) || frame < 0 || frame > 48) {
    throw new RangeError(`Invalid pet sprite frame: ${frame}`)
  }
  const column = frame % 7
  const row = Math.floor(frame / 7)
  return `${(column * 100) / 6}% ${(row * 100) / 6}%`
}
