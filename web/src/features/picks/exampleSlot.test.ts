import { describe, expect, it } from 'vitest'
import { exampleSlot } from './PicksPage'

// the example cards between the picks: after three rows, then every eight, three at most (user 2026-10-22)
describe('exampleSlot', () => {
  it('PC, four columns: after the 12th, 44th and 76th book', () => {
    const after = Array.from({ length: 120 }, (_, index) => exampleSlot(index, 4))
      .map((slot, index) => (slot === null ? null : [index + 1, slot]))
      .filter(Boolean)
    expect(after).toEqual([
      [12, 0],
      [44, 1],
      [76, 2],
    ])
  })

  it('phones, two columns: after the 6th, 22nd and 38th book; tablets after the 9th', () => {
    expect(exampleSlot(5, 2)).toBe(0)
    expect(exampleSlot(13, 2)).toBeNull()
    expect(exampleSlot(21, 2)).toBe(1)
    expect(exampleSlot(37, 2)).toBe(2)
    expect(exampleSlot(53, 2)).toBeNull()
    expect(exampleSlot(8, 3)).toBe(0)
    expect(exampleSlot(10, 3)).toBeNull()
  })
})
