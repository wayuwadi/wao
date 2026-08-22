import { describe, expect, it } from 'vitest'
import { updateProgress } from './updateProgress'

describe('updateProgress', () => {
  it('reports determinate progress without reaching 100 before install finishes', () => {
    expect(updateProgress(25, 100)).toBe(25)
    expect(updateProgress(100, 100)).toBe(99)
  })

  it('keeps an unknown content length indeterminate', () => {
    expect(updateProgress(20)).toBeUndefined()
    expect(updateProgress(20, 0)).toBeUndefined()
  })
})
