import { describe, expect, it } from 'vitest'
import { applyRowVerdict, type Batch } from './rowVerdict'
import { testRow } from './testRows'

function batch(batchNo: number, rows: Batch['rows']): Batch {
  return { batchNo, kind: 'round', status: 'loading', requestedLibraries: [], rows }
}

describe('applyRowVerdict', () => {
  const pending = testRow({ libraryId: 12, rank: 4, availability: 'PENDING', availabilityBasis: 'SITE_DETAIL' })

  it('updates the matching row of its own batch (AC-15)', () => {
    const batches = [batch(1, [pending])]

    const next = applyRowVerdict(batches, {
      batchNo: 1,
      libraryId: 12,
      page: 1,
      rank: 4,
      availability: 'RESERVABLE',
      availabilityBasis: 'SITE_DETAIL',
    })

    expect(next[0].rows[0].availability).toBe('RESERVABLE')
  })

  it('never touches another batch, even with the same library and rank (AC-24)', () => {
    const batches = [batch(1, [pending]), batch(2, [{ ...pending }])]

    const next = applyRowVerdict(batches, {
      batchNo: 2,
      libraryId: 12,
      page: 1,
      rank: 4,
      availability: 'AVAILABLE',
      availabilityBasis: 'SITE_DETAIL',
    })

    expect(next[0]).toBe(batches[0])
    expect(next[0].rows[0].availability).toBe('PENDING')
    expect(next[1].rows[0].availability).toBe('AVAILABLE')
  })

  it('matches the page too', () => {
    const batches = [batch(1, [pending])]

    const next = applyRowVerdict(batches, {
      batchNo: 1,
      libraryId: 12,
      page: 2,
      rank: 4,
      availability: 'AVAILABLE',
      availabilityBasis: 'SITE_DETAIL',
    })

    expect(next[0].rows[0].availability).toBe('PENDING')
  })
})
