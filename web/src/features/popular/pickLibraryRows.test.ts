import { describe, expect, it } from 'vitest'
import { testRow } from '../search/testRows'
import { pickLibraryRows } from './pickLibraryRows'

const plan = new Map([
  [1, 0],
  [2, 1],
  [3, 2],
  [4, 3],
])

/** R6-3, R6-5 (AC-8): borrowable and subscription first, on loan by reservations, failed last, at most three. */
describe('the window library lines', () => {
  it('puts borrowable and subscription first, in plan order', () => {
    const rows = [
      testRow({ libraryId: 1, rank: 1, availability: 'RESERVABLE', reservations: 0 }),
      testRow({ libraryId: 3, rank: 3, availability: 'AVAILABLE', availabilityBasis: 'SUBSCRIPTION' }),
      testRow({ libraryId: 2, rank: 2, availability: 'AVAILABLE' }),
    ]
    expect(pickLibraryRows(rows, plan).map((r) => r.libraryId)).toEqual([2, 3, 1])
  })

  it('orders libraries on loan by fewer reservations, unknown counts after', () => {
    const rows = [
      testRow({ libraryId: 1, rank: 1, availability: 'RESERVABLE', reservations: 4 }),
      testRow({ libraryId: 2, rank: 2, availability: 'RESERVABLE', reservations: null }),
      testRow({ libraryId: 3, rank: 3, availability: 'RESERVABLE', reservations: 1 }),
    ]
    expect(pickLibraryRows(rows, plan).map((r) => r.libraryId)).toEqual([3, 1, 2])
  })

  it('puts failed lookups last, after undecided ones', () => {
    const rows = [
      testRow({ libraryId: 1, rank: 1, failed: true, availability: 'UNKNOWN' }),
      testRow({ libraryId: 2, rank: 2, availability: 'UNKNOWN' }),
      testRow({ libraryId: 3, rank: 3, availability: 'RESERVABLE', reservations: 9 }),
    ]
    expect(pickLibraryRows(rows, plan).map((r) => r.libraryId)).toEqual([3, 2, 1])
  })

  it('keeps at most three lines', () => {
    const rows = [1, 2, 3, 4].map((id) => testRow({ libraryId: id, rank: id }))
    expect(pickLibraryRows(rows, plan)).toHaveLength(3)
  })
})
