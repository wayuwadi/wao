import { describe, expect, it } from 'vitest'
import { dragDirectionFromDelta, PET_ANIMATIONS, spritePosition } from './petAnimation'

describe('pet animation sprite mapping', () => {
  it('maps the forty-nine frames onto a seven by seven sheet', () => {
    expect(spritePosition(0)).toBe('0% 0%')
    expect(spritePosition(8)).toBe(`${100 / 6}% ${100 / 6}%`)
    expect(spritePosition(48)).toBe('100% 100%')
  })

  it('keeps every animation inside the sprite sheet', () => {
    const frames = Object.values(PET_ANIMATIONS).flatMap((sequence) => sequence.frames)
    expect(frames.every((frame) => frame >= 0 && frame <= 48)).toBe(true)
    expect(() => spritePosition(49)).toThrow(RangeError)
  })

  it('provides an individual duration for every animation frame', () => {
    expect(Object.values(PET_ANIMATIONS).every(
      (sequence) => sequence.frames.length === sequence.durations.length,
    )).toBe(true)
  })

  it('maps window movement to the dominant drag direction', () => {
    expect(dragDirectionFromDelta(4, -3)).toBe('neutral')
    expect(dragDirectionFromDelta(-24, 8)).toBe('left')
    expect(dragDirectionFromDelta(24, -8)).toBe('right')
    expect(dragDirectionFromDelta(8, -24)).toBe('up')
    expect(dragDirectionFromDelta(-8, 24)).toBe('down')
  })
})
