import { describe, expect, it } from 'vitest'
import { dragDirectionFromDelta, PET_ANIMATIONS, spritePosition } from './petAnimation'

describe('pet animation sprite mapping', () => {
  it('maps the sixteen frames onto a four by four sheet', () => {
    expect(spritePosition(0)).toBe('0% 0%')
    expect(spritePosition(5)).toBe(`${100 / 3}% ${100 / 3}%`)
    expect(spritePosition(15)).toBe('100% 100%')
  })

  it('keeps every animation inside the sprite sheet', () => {
    const frames = Object.values(PET_ANIMATIONS).flatMap((sequence) => sequence.frames)
    expect(frames.every((frame) => frame >= 0 && frame <= 15)).toBe(true)
    expect(() => spritePosition(16)).toThrow(RangeError)
  })

  it('maps window movement to the dominant drag direction', () => {
    expect(dragDirectionFromDelta(4, -3)).toBe('neutral')
    expect(dragDirectionFromDelta(-24, 8)).toBe('left')
    expect(dragDirectionFromDelta(24, -8)).toBe('right')
    expect(dragDirectionFromDelta(8, -24)).toBe('up')
    expect(dragDirectionFromDelta(-8, 24)).toBe('down')
  })
})
