import { describe, expect, it } from 'vitest'
import { exampleSlot } from './PicksPage'

// the example cards between the picks: after three rows, then every four, three at most (user 2026-10-22)
describe('exampleSlot', () => {
  it('PC, four columns: after the 12th, 28th and 44th book', () => {
    const after = Array.from({ length: 80 }, (_, index) => exampleSlot(index, 4))
      .map((slot, index) => (slot === null ? null : [index + 1, slot]))
      .filter(Boolean)
    expect(after).toEqual([
      [12, 0],
      [28, 1],
      [44, 2],
    ])
  })

  it('phones, two columns: after the 6th, 14th and 22nd book; tablets after the 9th', () => {
    expect(exampleSlot(5, 2)).toBe(0)
    expect(exampleSlot(13, 2)).toBe(1)
    expect(exampleSlot(21, 2)).toBe(2)
    expect(exampleSlot(29, 2)).toBeNull()
    expect(exampleSlot(8, 3)).toBe(0)
    expect(exampleSlot(10, 3)).toBeNull()
  })
})
