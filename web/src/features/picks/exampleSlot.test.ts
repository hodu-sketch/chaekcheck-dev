import { describe, expect, it } from 'vitest'
import { exampleSlot } from './PicksPage'

// the example card between the picks: once, after three rows (user 2026-10-22: "한 번만 나타나게")
describe('exampleSlot', () => {
  it('PC, four columns: once, after the 12th book', () => {
    const after = Array.from({ length: 120 }, (_, index) => exampleSlot(index, 4))
      .map((slot, index) => (slot === null ? null : [index + 1, slot]))
      .filter(Boolean)
    expect(after).toEqual([[12, 0]])
  })

  it('phones, two columns: after the 6th book only; tablets after the 9th', () => {
    expect(exampleSlot(5, 2)).toBe(0)
    expect(exampleSlot(21, 2)).toBeNull()
    expect(exampleSlot(8, 3)).toBe(0)
    expect(exampleSlot(10, 3)).toBeNull()
  })
})
